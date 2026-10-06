"""Phase 2D-3 rollback capture + read-only audit. Not an installer.

Run only in the dedicated VM, as its administrator, with python3 -I.
Never creates accounts, sudo rules, authorizations, containers or experiments.
Only new backup/staging-report files are written. No secrets in the public report.
"""
import hashlib
import http.client
import json
import os
from pathlib import Path
import pwd
import re
import socket
import stat
import subprocess
import sys
import time

STAGE = Path('/home/w/chaoslab-m1-2d3-20261004')
BACKUP = Path('/var/backups/chaoslab-m1-2d3-20261004')
SOURCE = Path('/home/w/chaosblade-api3.r3cL98')
REPORT = STAGE / 'predeploy-report.json'
PINS = {
    'blade-chaoslab-api3-identified': 'c0c987bbd0aa9d158e90ab48f737680fe1b7eefdbf6c9e74744444c49c847e96',
    'bin/nsexec': '693219257100421d3c2321c2b1bfb3d285d1b6eed1fa82feabd033797a51e793',
    'bin/chaos_os': 'dee72446e32411f6a0d7a29c71b0ba0cc4dabbab1fcaba212c6c87d0ea965cfb',
    'yaml/chaosblade-cri-spec-1.8.1.yaml': 'f8deebf2b90c44f414745cc6316e0cef545332173a7ef9a1998b9804531e7880',
}


def digest(b):
    return hashlib.sha256(b).hexdigest()


def no_new_privileges(options):
    # Docker inspect may serialize the enabled option with '=true'.
    # Reject conflicting, false, malformed and duplicate NNP options.
    if not isinstance(options, list) or not all(isinstance(s, str) for s in options):
        return False
    matches = [s for s in options if s.startswith('no-new-privileges')]
    return len(matches) == 1 and matches[0] in (
        'no-new-privileges', 'no-new-privileges:true', 'no-new-privileges=true')


def metadata(path):
    try:
        s = path.lstat()
    except FileNotFoundError:
        return {'exists': False}
    return {'exists': True, 'uid': s.st_uid, 'gid': s.st_gid,
            'mode': oct(stat.S_IMODE(s.st_mode)), 'size': s.st_size,
            'symlink': stat.S_ISLNK(s.st_mode)}


def bounded_file(path, maximum=1024 * 1024):
    s = path.lstat()
    if not stat.S_ISREG(s.st_mode) or s.st_size > maximum:
        raise RuntimeError('unsafe file type or size')
    with path.open('rb') as f:
        data = f.read(maximum + 1)
    if len(data) > maximum:
        raise RuntimeError('oversized file')
    return data


def write_new(path, data, mode=0o600):
    fd = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_EXCL | os.O_NOFOLLOW, mode)
    os.fchmod(fd, mode)
    with os.fdopen(fd, 'wb') as f:
        f.write(data)
        f.flush()
        os.fsync(f.fileno())
    d = os.open(path.parent, os.O_RDONLY | os.O_DIRECTORY)
    try:
        os.fsync(d)
    finally:
        os.close(d)


def command(argv):
    p = subprocess.run(argv, env={'PATH': '/usr/sbin:/usr/bin:/sbin:/bin',
                                 'LANG': 'C', 'LC_ALL': 'C', 'HOME': '/root'},
                       capture_output=True, timeout=10, check=False)
    return p.returncode, p.stdout.decode('utf-8', 'replace'), p.stderr.decode('utf-8', 'replace')


class DockerConnection(http.client.HTTPConnection):
    def connect(self):
        self.sock = socket.socket(socket.AF_UNIX, socket.SOCK_STREAM)
        self.sock.settimeout(3)
        self.sock.connect('/var/run/docker.sock')


def inspect(identifier):
    if not (identifier == 'chaoslab-cpu-sandbox' or re.fullmatch('[0-9a-f]{64}', identifier)):
        raise RuntimeError('invalid inspect target')
    c = DockerConnection('localhost', timeout=3)
    try:
        c.request('GET', '/containers/' + identifier + '/json')
        response = c.getresponse()
        data = response.read(1024 * 1024 + 1)
        if response.status != 200 or len(data) > 1024 * 1024:
            raise RuntimeError('inspect unavailable')
        return json.loads(data)
    finally:
        c.close()


def baseline(container_id, pid):
    proc = Path('/proc') / str(pid)
    original_stat = (proc / 'stat').read_text()
    start = original_stat[original_stat.rfind(') ') + 2:].split()[19]
    group_lines = (proc / 'cgroup').read_text().splitlines()
    groups = [line[3:] for line in group_lines if line.startswith('0::')]
    if len(groups) != 1 or not groups[0].startswith('/') or '..' in groups[0].split('/'):
        raise RuntimeError('unsupported target cgroup')
    group = Path('/sys/fs/cgroup') / groups[0].lstrip('/')
    if group.resolve() != group or not str(group).startswith('/sys/fs/cgroup/'):
        raise RuntimeError('unsafe cgroup path')
    samples = []
    for i in range(5):
        current = inspect(container_id)
        now_stat = (proc / 'stat').read_text()
        now_start = now_stat[now_stat.rfind(') ') + 2:].split()[19]
        if current['Id'] != container_id or not current['State']['Running'] or current['State']['Pid'] != pid or now_start != start:
            raise RuntimeError('target changed while sampling')
        if (proc / 'cgroup').read_text().splitlines() != group_lines:
            raise RuntimeError('target cgroup changed')
        fields = dict(line.split() for line in (group / 'cpu.stat').read_text().splitlines())
        samples.append({'unixNs': time.time_ns(), 'monotonicNs': time.monotonic_ns(),
                        'usageUsec': int(fields['usage_usec'])})
        if i < 4:
            time.sleep(1)
    intervals = []
    for a, b in zip(samples, samples[1:]):
        ns = b['monotonicNs'] - a['monotonicNs']
        usage = b['usageUsec'] - a['usageUsec']
        if ns <= 0 or usage < 0:
            raise RuntimeError('invalid CPU sample')
        intervals.append({'intervalNs': ns, 'usageDeltaUsec': usage,
                          'oneCpuPercent': usage * 100000.0 / ns})
    return {'cgroup': str(group), 'samples': samples, 'intervals': intervals,
            'meaning': 'read-only baseline; not health/recovery evidence',
            'members': (group / 'cgroup.procs').read_text().splitlines()[:64]}


def main():
    if os.geteuid() != 0 or len(sys.argv) != 1 or Path(__file__).absolute() != STAGE / 'predeploy.py':
        raise RuntimeError('run the exact staged script as administrator')
    os.umask(0o077)
    if BACKUP.exists() or REPORT.exists():
        raise RuntimeError('existing audit artifacts; refuse overwrite, request review')
    if Path('/var/lib/chaoslab-m1/state/authorization.json').exists():
        raise RuntimeError('unexpected authorization; stop without touching it')
    # No backups under a symlink or non-root-writable parent.
    for parent in [Path('/'), Path('/var'), Path('/var/backups')]:
        s = parent.lstat()
        if not stat.S_ISDIR(s.st_mode) or s.st_uid != 0 or stat.S_IMODE(s.st_mode) & 0o022:
            raise RuntimeError('unsafe backup parent')
    BACKUP.mkdir(mode=0o700)
    report = {'phase': '2D-3 predeployment audit only', 'vmChanges': ['root-only rollback backup', 'sanitized report'],
              'backupPath': str(BACKUP), 'createAuthorizationGenerated': False,
              'realBladeInvoked': False, 'ready': False, 'blockers': []}
    report['scriptSha256'] = digest(bounded_file(STAGE / 'predeploy.py'))
    write_new(BACKUP / 'predeploy.py', bounded_file(STAGE / 'predeploy.py'))
    paths = ['/etc/sudoers', '/etc/sudoers.d', '/etc/passwd', '/etc/shadow', '/etc/group', '/etc/gshadow',
             '/usr/local/libexec', '/usr/local/libexec/chaoslab-m1-wrapper', '/etc/chaoslab-m1',
             '/var/lib/chaoslab-m1', '/var/log/chaoslab-m1', '/opt/chaoslab/m1/api3-identified']
    report['before'] = {p: metadata(Path(p)) for p in paths}
    # Backups may contain sensitive account data. They remain root-only and are
    # NEVER included in the public report or sent to the workstation.
    for name in ['sudoers', 'passwd', 'shadow', 'group', 'gshadow']:
        write_new(BACKUP / ('etc-' + name), bounded_file(Path('/etc') / name))
    expected = {'Defaults env_reset', 'Defaults mail_badpass', 'Defaults use_pty',
                'root ALL=(ALL:ALL) ALL', '%admin ALL=(ALL) ALL', '%sudo ALL=(ALL:ALL) ALL',
                '@includedir /etc/sudoers.d', '#includedir /etc/sudoers.d',
                'Defaults secure_path="/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin:/snap/bin"'}
    sudo_paths = [Path('/etc/sudoers')] + sorted(Path('/etc/sudoers.d').iterdir())
    rules = []
    for i, path in enumerate(sudo_paths):
        data = bounded_file(path)
        write_new(BACKUP / ('sudo-rule-' + str(i)), data)
        active = [' '.join(line.split()) for line in data.decode('utf-8').splitlines()
                  if line.strip() and (not line.lstrip().startswith('#') or line.lstrip().startswith(('#include ', '#includedir ')))]
        unknown = [digest(line.encode()) for line in active if line not in expected]
        rules.append({'name': path.name, 'sha256': digest(data), 'activeLineCount': len(active),
                      'matchesReviewedUbuntuBaseline': not unknown, 'unknownLineHashes': unknown,
                      'metadata': metadata(path)})
        if unknown:
            report['blockers'].append('sudoers non-baseline directives require review: ' + path.name)
    report['sudoRuleSummary'] = rules
    rc, out, err = command(['/usr/sbin/visudo', '-c'])
    report['visudoValid'] = rc == 0
    if rc:
        report['blockers'].append('visudo validation failed')
    rc, out, err = command(['/usr/bin/sudo', '-n', '-l', '-U', 'w'])
    report['wSudoSummary'] = {'querySucceeded': rc == 0, 'allCommandsAsAllUsers': '(ALL : ALL) ALL' in out or '(ALL:ALL) ALL' in out,
                             'usePty': 'use_pty' in out, 'rawOutputOmitted': True}
    for account in ['w', 'chaoslab']:
        try:
            p = pwd.getpwnam(account)
            report[account] = {'exists': True, 'uid': p.pw_uid, 'gid': p.pw_gid, 'groups': os.getgrouplist(account, p.pw_gid), 'shell': p.pw_shell}
        except KeyError:
            report[account] = {'exists': False}
    report['candidateFiles'] = {}
    for name, expected_hash in PINS.items():
        data = bounded_file(SOURCE / name, 128 * 1024 * 1024)
        actual = digest(data)
        report['candidateFiles'][name] = {'sha256': actual, 'pinMatches': actual == expected_hash,
                                        'metadata': metadata(SOURCE / name)}
        if actual != expected_hash:
            report['blockers'].append('candidate digest mismatch: ' + name)
    try:
        v = inspect('chaoslab-cpu-sandbox')
        h = v['HostConfig']
        view = {'id': v['Id'], 'imageId': v['Image'], 'name': v['Name'], 'running': v['State']['Running'],
                'user': v['Config']['User'], 'networkMode': h['NetworkMode'], 'privileged': h['Privileged'],
                'readonlyRootfs': h['ReadonlyRootfs'], 'mountCount': len(v['Mounts']), 'bindCount': len(h.get('Binds') or []),
                'deviceCount': len(h.get('Devices') or []), 'capAdd': h.get('CapAdd'), 'capDrop': h.get('CapDrop'),
                'securityOpt': h.get('SecurityOpt'), 'pidMode': h['PidMode'], 'ipcMode': h['IpcMode'],
                'cgroupnsMode': h['CgroupnsMode'], 'nanoCpus': h['NanoCpus'], 'memory': h['Memory'],
                'pidsLimit': h['PidsLimit'], 'restartPolicy': h['RestartPolicy']['Name']}
        report['sandbox'] = view
        checks = {'fullId': bool(re.fullmatch('[0-9a-f]{64}', v['Id'])),
                  'fullImageId': bool(re.fullmatch('sha256:[0-9a-f]{64}', v['Image'])),
                  'exactName': v['Name'] == '/chaoslab-cpu-sandbox', 'running': v['State']['Running'],
                  'notPaused': v['State'].get('Paused') is False,
                  'notRestarting': v['State'].get('Restarting') is False,
                  'notDead': v['State'].get('Dead') is False,
                  'nonRootUser': v['Config']['User'] == '65534:65534', 'noNetwork': h['NetworkMode'] == 'none',
                  'notPrivileged': not h['Privileged'], 'readonlyRootfs': h['ReadonlyRootfs'],
                  'noMounts': not v['Mounts'] and not h.get('Binds'), 'noDevices': not h.get('Devices'),
                  'capsDropped': not h.get('CapAdd') and h.get('CapDrop') == ['ALL'],
                  'noNewPrivileges': no_new_privileges(h.get('SecurityOpt')),
                  'privateCgroup': h['CgroupnsMode'] == 'private', 'noHostPID': h['PidMode'] == '',
                  'noHostIPC': h['IpcMode'] != 'host', 'cpuLimit': h['NanoCpus'] == 500000000,
                  'memoryLimit': h['Memory'] == 134217728, 'pidLimit': h['PidsLimit'] == 32,
                  'noRestart': h['RestartPolicy']['Name'] == 'no'}
        report['sandboxChecks'] = checks
        report['blockers'] += ['sandbox mismatch: ' + k for k, ok in checks.items() if not ok]
        if all(checks.values()):
            report['cpuBaseline'] = baseline(v['Id'], v['State']['Pid'])
        else:
            report['cpuBaseline'] = {'status': 'NOT_SAMPLED_UNSAFE_OR_INCOMPATIBLE_TARGET'}
    except Exception as e:
        report['blockers'].append('sandbox/baseline unavailable: ' + type(e).__name__)
    report['residualProbe'] = 'UNKNOWN_NOT_IMPLEMENTED'
    report['healthProbe'] = 'UNKNOWN_NOT_IMPLEMENTED'
    encoded = (json.dumps(report, indent=2, sort_keys=True) + '\n').encode()
    write_new(BACKUP / 'report.json', encoded)
    write_new(REPORT, encoded, 0o644)
    print('AUDIT COMPLETE; no account/sudoers/wrapper deployment performed')
    print('report:', REPORT)
    print('report SHA256:', digest(encoded))
    print('blockers:', len(report['blockers']))
    print('NOT REAL EXECUTION READY')


if __name__ == '__main__':
    try:
        main()
    except Exception as e:
        print('AUDIT STOPPED:', type(e).__name__, '(no automatic rollback or overwrite)')
        sys.exit(1)
