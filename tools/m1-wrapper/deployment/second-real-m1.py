"""Exactly ONE approved R2 create through unchanged a1cd739 backend. No replay.
Root-only strace records actual wrapper stdout for settling-round evidence; it
does not replace ports, alter production bytecode or supply recovery decisions.
R1 is read/hashed only. All paths and identities are fixed, not caller inputs.
"""
import ast
import concurrent.futures
from datetime import datetime, timezone
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import re
import runpy
import signal
import subprocess
import sys
import time
import uuid
import zipfile

STAGE = Path('/home/w/chaoslab-m1-2d3-20261004')
AUDIT = Path('/var/lib/chaoslab-m1/audit')
STATE = Path('/var/lib/chaoslab-m1/state')
POLICY = Path('/etc/chaoslab-m1/policy.json')
ARCHIVE = Path('/var/lib/chaoslab-m1/archive/m1-r1-21d2d20071b3f449')
DEST = Path('/opt/chaoslab-backend/m1-a1cd739')
WORK = Path('/var/lib/chaoslab-backend-m1-r2')
WRAPPER = '/usr/local/libexec/chaoslab-m1-wrapper'
APP_SHA = '1b7bec8f07f83e5cd6a48b4119ecde9588f127b07fa5d52266107251e7e069fd'
POLICY_SHA = 'b82056c51d558339fa46f9d57be3b967881d75c582a69ff2b6cbf990d2f89a11'
FIRST_SCRIPT_SHA = '0049074dffb64d4f9f287c91371884ba4124c257bc5c47a1b06ec0d7022f4b4c'
PREP_SHA = '605d2be66675aee737ce5d3d8da3700b50c8a5f6b27f144d3a50dd2dbd9d15aa'
READY_SHA = '24b3b577d96d950004885c3045d0d71ec95eca15441befc5afb9c0d01a22a1ed'
MANIFEST_SHA = 'b641a077fa9d77c006fb6d247d272f45e80e52cae9dd55b15c6e97b53780be86'
ENV = {'PATH': '/usr/sbin:/usr/bin:/sbin:/bin', 'HOME': '/root', 'LANG': 'C', 'LC_ALL': 'C'}


def require(value, reason):
    if not value:
        raise RuntimeError(reason)


def sha(raw):
    return hashlib.sha256(raw).hexdigest()


def now():
    return datetime.now(timezone.utc).isoformat(timespec='microseconds').replace('+00:00', 'Z')


def trace_envelopes(files):
    """Extract ONLY stdout of fixed wrapper process/thread groups, never logs.
    files is a bounded mapping TID -> strace text (also used by harmless tests).
    Unknown/truncated/split writes fail coverage checks rather than invent rounds.
    """
    require(sum(len(v) for v in files.values()) <= 16 * 1024 * 1024, 'trace output bound')
    roots = set()
    edges = []
    for tid, text in files.items():
        for line in text.splitlines():
            if 'execve("' + WRAPPER + '",' in line and line.endswith(' = 0'):
                roots.add(tid)
            if ('clone(' in line or 'clone3(' in line) and 'CLONE_THREAD' in line:
                match = re.search(r'= (\d+)$', line)
                if match:
                    edges.append((tid, int(match.group(1))))
    owned = set(roots)
    for _ in range(len(edges) + 1):
        before = len(owned)
        for parent, child in edges:
            if parent in owned:
                owned.add(child)
        if len(owned) == before:
            break
    rows = []
    pattern = re.compile(r'^(\d+\.\d+) write\(1, ("(?:[^"\\]|\\.)*"), (\d+)\) = (\d+)$')
    for tid in owned:
        for line in files.get(tid, '').splitlines():
            match = pattern.fullmatch(line)
            if not match or match[3] != match[4]:
                continue
            try:
                raw = ast.literal_eval(match[2])
                require(len(raw.encode()) == int(match[3]), 'partial trace write')
                value = json.loads(raw)
            except (ValueError, SyntaxError, RuntimeError):
                continue
            if isinstance(value, dict) and value.get('version') == 1 and 'code' in value:
                rows.append({'traceEpoch': float(match[1]), 'tid': tid, 'envelope': value})
    return sorted(rows, key=lambda row: row['traceEpoch'])


def settling_rows(rows, execution, uid, policy, requested_at):
    """Sanitized actual backend wrapper responses, not independent observer polls."""
    start = datetime.fromisoformat(requested_at.replace('Z', '+00:00')).timestamp()
    result = []
    latest_status = None
    for row in rows:
        if row['traceEpoch'] < start:
            continue
        envelope = row['envelope']
        native = envelope.get('response', {}).get('result')
        if isinstance(native, dict) and 'Status' in native:
            require(native.get('Uid') == uid, 'RECOVERY_IDENTITY_REJECTED')
            latest_status = {'uid': uid, 'status': native['Status'], 'traceEpoch': row['traceEpoch'],
                             'nativeUpdateTime': native.get('UpdateTime'), 'nativeCreateTime': native.get('CreateTime')}
        observation = envelope.get('observation')
        # Unbound preflight observations are identity checks, NOT Gate rounds.
        if not isinstance(observation, dict) or 'executionId' not in observation:
            continue
        require(all(observation.get(k) == v for k, v in {
            'executionId': execution, 'nativeUid': uid, 'nodeId': policy['nodeId'],
            'containerId': policy['containerId'], 'imageId': policy['imageId']}.items()),
            'RECOVERY_IDENTITY_REJECTED')
        result.append({'round': len(result) + 1, 'traceEpoch': row['traceEpoch'],
                       'engineStatus': latest_status, 'observation': observation})
        latest_status = None  # A later round cannot reuse the first status receipt.
    return result


def main():
    # Historical executable is preserved in commit b31086f with its original SHA.
    # It cannot be reused as a future acceptance harness: timing/causal criteria
    # now belong before backend SUCCESS, and strace is not an M1 dependency.
    raise RuntimeError('HISTORICAL_R2_HARNESS_DISABLED; THIRD M1 NOT AUTHORIZED')
    require(os.geteuid() == 0 and len(sys.argv) == 1, 'administrator required; no arguments')
    os.umask(0o077)
    for name, pin in [('first-real-m1.py', FIRST_SCRIPT_SHA), ('prepare-second-m1.py', PREP_SHA)]:
        require(not (STAGE / name).is_symlink() and sha((STAGE / name).read_bytes()) == pin, 'reviewed helper changed')
    # Import only pinned definitions; do NOT run either historical main/initializer.
    spec = importlib.util.spec_from_file_location('pinned_m1_read_helpers', STAGE / 'first-real-m1.py')
    first = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(first)
    first.POLICY_SHA = POLICY_SHA  # Pure authorization builder now binds exact R2 digest.
    prep = runpy.run_path(str(STAGE / 'prepare-second-m1.py'))
    require(sha((STAGE / 'deploy-java-readonly.py').read_bytes()) == prep['HELPER_SHA'], 'trusted helper changed')
    base = runpy.run_path(str(STAGE / 'deploy-java-readonly.py'))
    trust, write = base['trust'], base['write']
    for path in [AUDIT, STATE, POLICY, ARCHIVE, DEST / 'backend.jar', Path(WRAPPER)]:
        trust(path)
    marker = AUDIT / 'second-real-m1-attempt.json'
    require(not os.path.lexists(marker) and not os.path.lexists(AUDIT / 'second-real-m1-result.json'),
            'SECOND M1 already reserved; NEVER rerun')
    write(marker, first.encode({'reservedAt': now(), 'singleCreate': True, 'automaticRetry': False, 'stateId': 'm1-real-state-r2'}))
    prep['fsync_directory'](AUDIT)
    report = {'result': 'SECOND M1 ABORTED', 'recoveryVerified': False, 'createApiAttempts': 0,
              'realAuthorizationCreated': False, 'noAutomaticRetry': True, 'firstM1': 'INCOMPLETE',
              'firstRecoveryVerified': False, 'backendCommit': prep['COMMIT'], 'backendJarSha256': APP_SHA,
              'policyDigest': POLICY_SHA, 'settlingRounds': []}
    experiment = execution = uid = auth_raw = policy = tracer = None
    start_future = None
    pool = concurrent.futures.ThreadPoolExecutor(max_workers=1)
    trace_dir = AUDIT / 'second-m1-pipe-evidence'

    def checkpoint():
        temp = AUDIT / 'second-real-m1-result.tmp'
        write(temp, json.dumps(report, indent=2).encode())
        os.replace(temp, AUDIT / 'second-real-m1-result.json')
        prep['fsync_directory'](AUDIT)

    def r1_unchanged():
        require(sha((ARCHIVE / 'manifest.json').read_bytes()) == MANIFEST_SHA, 'R1 manifest changed')
        manifest = json.loads((ARCHIVE / 'manifest.json').read_bytes())
        require(manifest['firstM1'] == 'INCOMPLETE' and manifest['recoveryVerified'] is False, 'R1 outcome changed')
        for name, item in manifest['files'].items():
            require(sha((ARCHIVE / name).read_bytes()) == item['sha256'], 'R1 archive changed')
        require(sha(first.mysql(prep['R1_QUERY']).encode()) == manifest['files']['r1-history-row.json']['sha256'], 'R1 row changed')
        require(sha((AUDIT / 'first-real-m1-result.json').read_bytes()) == prep['EVIDENCE_SHA'], 'R1 evidence changed')
        report['r1ArchiveUnchanged'] = report['r1DatabaseUnchanged'] = report['r1EvidenceUnchanged'] = True

    def traces():
        files = list(trace_dir.glob('pipe.*'))
        require(len(files) <= 4096, 'trace file count bound')
        total = sum(path.stat().st_size for path in files)
        require(total <= 16 * 1024 * 1024, 'trace output bound')
        return trace_envelopes({int(path.suffix[1:]): path.read_text() for path in files})

    def stop_tracer():
        if tracer is not None and tracer.poll() is None:
            tracer.send_signal(signal.SIGINT)  # Detach only; do not signal backend/children.
            tracer.wait(timeout=8)

    def stop_sandbox(reason):
        report['emergencyStopReason'] = reason
        report['recoveryVerified'] = False
        checkpoint()
        proc = subprocess.run(['/usr/bin/docker', '--host', 'unix:///var/run/docker.sock', 'stop', '--time', '2', policy['containerId']],
                              env=ENV, capture_output=True, timeout=8)
        report['emergencySandboxStop'] = {'acknowledged': proc.returncode == 0, 'notRecoveryVerified': True}
        if proc.returncode != 0:
            report['emergencyVmPoweroffRequested'] = True
            checkpoint()
            subprocess.run(['/usr/bin/systemctl', 'poweroff'], env=ENV, capture_output=True, timeout=5)

    try:
        ready_raw = (AUDIT / 'second-m1-readonly-resume.json').read_bytes()
        require(sha(ready_raw) == READY_SHA, 'R2 acceptance changed')
        ready = json.loads(ready_raw)
        require(ready['ready'] is True and ready['commit'] == prep['COMMIT'], 'R2 not accepted')
        r1_unchanged()
        require(sha((DEST / 'backend.jar').read_bytes()) == APP_SHA and sha(Path(WRAPPER).read_bytes()) == prep['WRAPPER_SHA'], 'backend/wrapper changed')
        policy = json.loads(POLICY.read_bytes())
        require(policy == ready['javaResult']['policy'] and sha(first.encode(policy)) == POLICY_SHA
                and policy['deployment'] == 'REAL' and policy['stateId'] == 'm1-real-state-r2'
                and policy['cpuPercent'] == policy['durationSeconds'] == 10, 'R2 policy changed')
        for relative, key in [('', 'toolSha256'), ('bin/nsexec', 'nsexecSha256'), ('bin/chaos_os', 'chaosOsSha256'), ('yaml/chaosblade-cri-spec-1.8.1.yaml', 'yamlSha256')]:
            path = Path(policy['executable']) if not relative else Path(policy['executable']).parent / relative
            trust(path)
            require(prep['digest'](path) == policy[key], 'tool SHA changed')
        prep['active_clean']()
        require(not base['tool_processes'](policy), 'residual before experiment')
        require(subprocess.run(['/usr/bin/id', '-G', 'chaoslab'], env=ENV, capture_output=True, timeout=3).stdout.strip() == b'987', 'service groups changed')
        sudo_list = subprocess.run(['/usr/bin/sudo', '-l', '-U', 'chaoslab'], env=ENV, capture_output=True, timeout=3)
        command_lines = [line.strip() for line in sudo_list.stdout.decode().splitlines() if line.lstrip().startswith('(')]
        require(sudo_list.returncode == 0 and command_lines == ['(root) NOPASSWD: ' + WRAPPER + ' ""'], 'exact no-argv sudo boundary changed')
        negative_sudo = {}
        for executable in ['/bin/sh', '/bin/bash', '/usr/bin/docker', '/usr/bin/env', '/usr/bin/python3', '/usr/bin/systemctl', '/usr/bin/systemd-run', policy['executable']]:
            negative_sudo[executable] = subprocess.run(['/usr/sbin/runuser', '-u', 'chaoslab', '--', '/usr/bin/sudo', '-n', '-l', '--', executable],
                                                       env=ENV, capture_output=True, timeout=3).returncode != 0
        require(all(negative_sudo.values()), 'sudo boundary expanded')
        report['negativeSudo'] = negative_sudo
        pid = ready['javaResult']['pid']
        status = Path('/proc', str(pid), 'status').read_text()
        for text in ['Uid:\t999\t999\t999\t999', 'Gid:\t987\t987\t987\t987', 'Groups:\t987 ',
                     'CapPrm:\t0000000000000000', 'CapEff:\t0000000000000000', 'CapAmb:\t0000000000000000']:
            require('\n' + text + '\n' in status, 'backend identity/capabilities changed')
        require(os.readlink('/proc/' + str(pid) + '/exe') == prep['JAVA'], 'JVM executable changed')
        expected_cp = str(DEST / 'acceptance.jar') + ':' + str(DEST / 'app/BOOT-INF/classes') + ':' + str(DEST / 'app/BOOT-INF/lib/*') + ':' + str(DEST / 'diagnostic-libs/*')
        argv = Path('/proc', str(pid), 'cmdline').read_bytes().decode().split('\0')
        require(expected_cp in argv and 'com.chaoslab.engine.infrastructure.blade.M1R2ReadOnlyAcceptance' in argv, 'wrong running backend')
        entry = 'BOOT-INF/classes/com/chaoslab/engine/infrastructure/blade/ChaosBladeEngine.class'
        with zipfile.ZipFile(DEST / 'backend.jar') as jar:
            require(sha(jar.read(entry)) == prep['digest'](DEST / 'app' / entry), 'deployed engine class changed')
        require(first.http('/actuator/health')['status'] == 'UP', 'backend not UP')
        require(first.mysql('SELECT @@innodb_flush_log_at_trx_commit,@@sync_binlog;') == '1\t1', 'MySQL durability not confirmed')
        for table in ['experiments', 'experiment_executions', 'blade_execution_snapshots']:
            require(first.mysql('SELECT count(*) FROM chaoslab_m1_r2.' + table + ';') == '0', 'R2 experiment tables not empty')
        require(sha((STAGE / 'start-idle.py').read_bytes()) == prep['IDLE_SHA'], 'idle inspector changed')
        idle = runpy.run_path(str(STAGE / 'start-idle.py'))
        current = idle['inspect']()
        if not current['State']['Running']:
            idle['docker']('start', current['Id'])
            current = idle['inspect']()
            report['existingSandboxRestarted'] = True
        require(current['State']['Running'], 'sandbox not Running')
        runtime = idle['sample'](current['State']['Pid'])
        pre = first.wrapper('preflight')
        require(pre['policy'] == policy and pre['policyDigest'] == POLICY_SHA, 'preflight identity mismatch')
        observation = pre['observation']
        require(observation['probeReady'] and observation['residual'] == 'CLEAR' and observation['cpuPercent'] <= 1, 'baseline not idle/CLEAR')
        report['preflight'] = pre
        report['baseline'] = first.cpu_window(idle, runtime, 1)
        require(report['baseline']['oneCpuPercent'] <= 1, 'direct CPU baseline not idle')
        report['backendPid'] = pid
        # Observe unchanged production pipes; raw traces remain root-only.
        trace_dir.mkdir(mode=0o700)
        with (trace_dir / 'tracer.log').open('xb') as log:
            tracer = subprocess.Popen(['/usr/bin/strace', '-ff', '-ttt', '-s', '65536', '-e', 'trace=execve,write,clone,clone3',
                                       '-o', str(trace_dir / 'pipe'), '-p', str(pid)], env=ENV, stdout=log, stderr=log)
        time.sleep(.4)
        require(tracer.poll() is None, 'readonly trace attach failed; NO CREATE')
        # No experiment dispatch until preflight passes. Creating/validating the
        # definition now exercises Java readonly wrapper under trace, before auth.
        created = first.http('/api/v1/experiments', {'name': 'M1 R2 one real CPU10', 'hypothesis': 'One fixed bounded CPU worker; active same-UID recovery verified by fresh evidence',
                        'targetId': first.TARGET, 'scenarioId': first.SCENARIO, 'durationSeconds': 10, 'parameters': {'percent': 10}})
        experiment = created['id']
        report['experimentId'] = experiment
        require(first.http('/api/v1/experiments/' + experiment + '/validation', {})['status'] == 'VALIDATED', 'validation failed')
        dry = first.http('/api/v1/experiments/' + experiment + '/dry-run', {})
        require(dry['accepted'] is True and dry['experiment']['status'] == 'READY', 'dry run failed')
        report['dryRun'] = dry
        traced_preflights = [row for row in traces() if row['envelope'].get('policyDigest') == POLICY_SHA and row['envelope'].get('code') == 'OK']
        require(traced_preflights and tracer.poll() is None, 'Java-to-root preflight trace not verified; NO CREATE')
        report['readonlyTraceVerifiedBeforeAuthorization'] = True
        prep['active_clean']()
        latest = idle['inspect']()
        latest_sample = idle['sample'](latest['State']['Pid'])
        require(latest['State']['Running'] and all(latest_sample[k] == runtime[k] for k in ['pid', 'startTime', 'cgroup']), 'runtime changed before dispatch')
        checkpoint()
        print('Fresh R2 verification PASS; one backend start only; durable UID before single-use authorization.', flush=True)
        # ONE start request. Java SecureRandom + REQUIRES_NEW commits UID/intent.
        report['createApiAttempts'] = 1
        report['startSubmittedAt'] = now()
        checkpoint()
        start_future = pool.submit(first.http, '/api/v1/experiments/' + experiment + '/executions', {}, 'm1-r2-once-' + str(uuid.uuid4()))
        deadline = time.monotonic() + 12
        intent = None
        while time.monotonic() < deadline:
            query = first.intent_query(experiment).replace('chaoslab_m1.', 'chaoslab_m1_r2.')
            raw = first.mysql(query)
            if raw:
                require('\n' not in raw, 'multiple committed intents')
                intent = json.loads(raw)
                break
            if start_future.done():
                break
            time.sleep(.02)
        require(intent is not None, 'durable UID not observed; NO AUTHORIZATION')
        first.compare_intent(intent, policy, experiment)
        execution, uid = intent['executionId'], intent['nativeUid']
        require(execution != prep['EXECUTION'] and uid != prep['UID'], 'R1 identity reused')
        report.update(executionId=execution, nativeUid=uid, committedIntent=intent,
                      commitProof='independent MySQL READ COMMITTED; durable settings=1/1; observed BEFORE authorization')
        checkpoint()
        prep['active_clean']()
        require(tracer.poll() is None, 'trace unavailable before authorization')
        authorization = first.authorization(policy, execution, uid)
        auth_raw = first.encode(authorization)
        write(STATE / 'authorization.json', auth_raw)
        prep['fsync_directory'](STATE)
        report.update(realAuthorizationCreated=True, authorizationCreatedAt=now(), authorization=authorization, result='M1 INCOMPLETE')
        checkpoint()
        started = start_future.result(timeout=18)
        report['createResponse'] = started
        require(started['id'] == execution and started['status'] == 'RUNNING' and started['engineExperimentId'] == 'blade-' + execution, 'create uncertain; NO RETRY')
        binding = json.loads((STATE / 'binding.json').read_bytes())
        require(binding['executionId'] == execution and binding['nativeUid'] == uid and binding['policyDigest'] == POLICY_SHA
                and not os.path.lexists(STATE / 'authorization.json'), 'binding/consumption mismatch')
        report['rootBinding'] = binding
        report['nativeAfterCreate'] = first.native_record(uid)
        require(report['nativeAfterCreate']['status'] == 'Success', 'native create not successful')
        receipts = [row['envelope'] for row in traces() if row['envelope'].get('outcome') == 'HANDOFF']
        require(len(receipts) == 1 and receipts[0].get('nativeUid') == uid and receipts[0].get('response', {}).get('result') == uid,
                'create receipt UID mismatch or missing; NO RETRY')
        report['createTransportReceipt'] = receipts[0]
        report['during'] = first.cpu_window(idle, runtime, 1.2)
        cpu = report['during']['oneCpuPercent']
        tool_seen = any(sample['knownToolMembers'] for sample in report['during']['samples'])
        report['faultObserved'] = 6 <= cpu <= 15 and cpu >= report['baseline']['oneCpuPercent'] + 5 and tool_seen
        checkpoint()
        require(report['faultObserved'], 'CPU approximately 10 percent not proven')
        require((datetime.now(timezone.utc) - datetime.fromisoformat(binding['createdAt'].replace('Z', '+00:00'))).total_seconds() < 8,
                'active recovery cannot precede timeout safely')
        report['activeDestroyRequestedAt'] = now()
        checkpoint()
        # Exactly ONE normal backend destroy, whose existing engine runs the window.
        destroyed = first.http('/api/v1/experiments/' + experiment + '/executions/' + execution + '/destroy', {}, timeout=60)
        report['backendDestroy'] = destroyed
        report['destroyCompletedAt'] = now()
        stop_tracer()
        rows = traces()
        report['settlingRounds'] = settling_rows(rows, execution, uid, policy, report['activeDestroyRequestedAt'])
        acknowledgements = [row['envelope'] for row in rows if isinstance(row['envelope'].get('response', {}).get('result'), dict)
                            and set(row['envelope']['response']['result']) == {'target', 'action', 'flags', 'ActionProcessHang'}]
        # Native destroy api3 returns rebuilt ExpModel, NOT {"success":true}.
        require(len(acknowledgements) == 1, 'exactly one backend native destroy receipt not confirmed')
        report['destroyTransportReceipt'] = acknowledgements[0]
        require(report['settlingRounds'], 'actual Gate round trace unavailable')
        report['nativeDestroyedAt'] = report['settlingRounds'][0]['engineStatus']['nativeUpdateTime']
        require(all(row['engineStatus'] is not None and row['engineStatus']['status'] == 'Destroyed' for row in report['settlingRounds']), 'fresh same-UID status missing')
        native_status = report['settlingRounds'][0]['engineStatus']
        native_elapsed = (datetime.fromisoformat(native_status['nativeUpdateTime'].replace('Z', '+00:00'))
                          - datetime.fromisoformat(native_status['nativeCreateTime'].replace('Z', '+00:00'))).total_seconds()
        require(0 <= native_elapsed < 10, 'native Destroyed not before timeout')
        report['nativeDestroyedBeforeTimeout'] = True
        report['nativeLifetimeSeconds'] = native_elapsed
        last = report['settlingRounds'][-1]['observation']
        report['finalGateOutcome'] = 'VERIFIED' if destroyed['status'] == 'SUCCESS' else destroyed.get('errorMessage', 'UNKNOWN')
        # Independent readonly corroboration never replaces the production Gate.
        report['engineEvidence'] = first.wrapper('status', execution, uid)
        report['finalObservation'] = first.wrapper('observe', execution, uid)
        report['nativeAfterRecovery'] = first.native_record(uid)
        body = report['engineEvidence'].get('response', {}).get('result', {})
        require(body.get('Uid') == uid and body.get('Status') == 'Destroyed', 'same UID Destroyed not confirmed')
        require(last['residual'] == 'CLEAR' and last['health'] == 'HEALTHY', 'production final residual/health not verified')
        final = report['finalObservation']['observation']
        require(final['executionId'] == execution and final['nativeUid'] == uid and final['nodeId'] == policy['nodeId']
                and final['containerId'] == policy['containerId'] and final['imageId'] == policy['imageId'], 'RECOVERY_IDENTITY_REJECTED')
        require(final['residual'] == 'CLEAR' and final['health'] == 'HEALTHY', 'final corroboration not recovered')
        require(0 <= (datetime.now(timezone.utc) - datetime.fromisoformat(final['observedAt'].replace('Z', '+00:00'))).total_seconds() <= 10, 'fresh evidence unavailable')
        require(destroyed['status'] == 'SUCCESS' and destroyed['id'] == execution and destroyed['finishedAt'] is not None, 'production Gate did not verify recovery')
        final_execution = first.http('/api/v1/experiments/' + experiment + '/executions/' + execution)
        report['finalBackendExecution'] = final_execution
        require(final_execution['status'] == 'SUCCESS', 'committed execution not SUCCESS')
        require(first.mysql("SELECT count(*) FROM chaoslab_m1_r2.experiment_executions WHERE status IN ('PREPARING','CREATE_UNCERTAIN','RUNNING','DESTROYING','ROLLBACK_FAILED');") == '0', 'occupancy not released')
        for table in ['experiments', 'experiment_executions', 'blade_execution_snapshots']:
            require(first.mysql('SELECT count(*) FROM chaoslab_m1_r2.' + table + ';') == '1', 'unexpected R2 record count')
        require(report['nativeAfterRecovery']['status'] == 'Destroyed' and not os.path.lexists(STATE / 'authorization.json'), 'native state/auth not confirmed')
        r1_unchanged()
        report.update(result='SECOND M1 PASS', recoveryVerified=True, occupancyReleased=True, finalGateOutcome='VERIFIED')
    except BaseException as error:
        report['blocker'] = str(error) if isinstance(error, RuntimeError) else type(error).__name__
        report['result'] = ('M1 FAIL / MANUAL REVIEW' if 'IDENTITY_REJECTED' in report['blocker'] else 'M1 INCOMPLETE') if report['realAuthorizationCreated'] else 'SECOND M1 ABORTED'
        report['recoveryVerified'] = False
        if auth_raw is not None and os.path.lexists(STATE / 'authorization.json'):
            require((STATE / 'authorization.json').read_bytes() == auth_raw, 'unexpected authorization replacement')
            (STATE / 'authorization.json').unlink()
            prep['fsync_directory'](STATE)
        # No second destroy/create/re-authorization. Emergency isolation cannot
        # become RecoveryVerified and does not update/release application occupancy.
        if report['realAuthorizationCreated'] and os.path.lexists(STATE / 'binding.json'):
            try:
                observed = first.wrapper('observe', execution, uid)
                report['failureObservation'] = observed
                o = observed['observation']
                if o['residual'] != 'CLEAR' or o.get('cpuPercent', 999) > 1 or not report.get('faultObserved'):
                    stop_sandbox('recovery/fault evidence uncertain')
            except BaseException:
                try:
                    stop_sandbox('dispatched experiment visibility unavailable')
                except BaseException as stop_error:
                    report['emergencyStopError'] = type(stop_error).__name__
        if execution is not None:
            try:
                report['finalBackendExecution'] = first.http('/api/v1/experiments/' + experiment + '/executions/' + execution)
            except Exception:
                report['finalBackendExecution'] = 'UNKNOWN'
        try:
            r1_unchanged()
        except Exception:
            report['r1PreservationCheck'] = 'UNKNOWN'
    finally:
        try:
            stop_tracer()
            if execution and trace_dir.exists() and report.get('activeDestroyRequestedAt'):
                report['settlingRounds'] = settling_rows(traces(), execution, uid, policy, report['activeDestroyRequestedAt'])
        except Exception:
            report['traceCoverage'] = 'UNKNOWN'
        if auth_raw is not None and os.path.lexists(STATE / 'authorization.json'):
            if (STATE / 'authorization.json').read_bytes() == auth_raw:
                (STATE / 'authorization.json').unlink()
                prep['fsync_directory'](STATE)
            else:
                report['authorizationCleanupUnknown'] = True
        report['authorizationAbsent'] = not os.path.lexists(STATE / 'authorization.json')
        report['bindingPresent'] = os.path.lexists(STATE / 'binding.json')
        report['authorizationConsumed'] = report['realAuthorizationCreated'] and report['bindingPresent'] and report['authorizationAbsent']
        report['nativeDbPresent'] = os.path.lexists(STATE / 'chaosblade.dat')
        if isinstance(report.get('finalBackendExecution'), dict):
            report['executionFinalStatus'] = report['finalBackendExecution']['status']
            report['occupancyReleased'] = report['executionFinalStatus'] == 'SUCCESS'
        if uid is not None and report['nativeDbPresent']:
            try:
                report['nativeFinalRecord'] = first.native_record(uid)
            except Exception:
                report['nativeFinalRecord'] = 'UNKNOWN'
        report['finishedAt'] = now()
        checkpoint()
        write(STAGE / 'second-real-m1-result.json', json.dumps(report, indent=2).encode(), 0o644)
        pool.shutdown(wait=True, cancel_futures=True)
    print(report['result'], flush=True)
    if report['recoveryVerified']:
        print('RECOVERY VERIFIED', flush=True)
    print('ONE ATTEMPT ONLY; STOPPED; NO THIRD CREATE AUTHORIZED', flush=True)


if __name__ == '__main__':
    main()
