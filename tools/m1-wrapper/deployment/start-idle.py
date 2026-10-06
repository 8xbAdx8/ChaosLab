"""One fixed existing idle container: inspect, conditional start, read-only baseline."""
import json
import os
from pathlib import Path
import subprocess
import sys
import time

STAGE = Path('/home/w/chaoslab-m1-2d3-20261004')
CID = '18bb4f8734edbd6e1dc35582692677bcfcf98fef175832fc1d1e29361faab97f'
IMAGE = 'sha256:5291449c3df73caf6ed85e649dec1b9e818b39a5d8c871e97afc13e9cd5e8fa8'
ENV = {'PATH': '/usr/bin:/bin', 'LANG': 'C', 'HOME': '/root'}
EXPECTED = {'NetworkMode': 'none', 'Binds': None, 'Privileged': False,
            'CapAdd': None, 'CapDrop': ['ALL'], 'SecurityOpt': ['no-new-privileges=true'],
            'NanoCpus': 500000000, 'Memory': 134217728, 'PidsLimit': 32,
            'RestartPolicy': {'Name': 'no', 'MaximumRetryCount': 0},
            'ReadonlyRootfs': True, 'Devices': [], 'PidMode': '',
            'IpcMode': 'private', 'CgroupnsMode': 'private', 'Init': None}


def docker(*args):
    p = subprocess.run(['/usr/bin/docker', '--host=unix:///var/run/docker.sock', *args],
                       env=ENV, stdout=subprocess.PIPE, stderr=subprocess.PIPE, timeout=15)
    if p.returncode or len(p.stdout) > 1024 * 1024:
        raise RuntimeError('Docker command failed; no retry')
    return p.stdout


def inspect():
    v, = json.loads(docker('inspect', '--type=container', CID))
    c, h, s = v['Config'], v['HostConfig'], v['State']
    if (v['Id'] != CID or v['Image'] != IMAGE or v['Name'] != '/chaoslab-cpu-sandbox'
            or c.get('Entrypoint') is not None or c.get('Cmd') != ['sleep', '3600']
            or v.get('Path') != 'sleep' or v.get('Args') != ['3600']
            or c.get('Healthcheck') is not None or c['User'] != '65534:65534'
            or v['Mounts'] != [] or any(h.get(k) != value for k, value in EXPECTED.items())
            or any(s.get(k) is not False for k in ['Paused', 'Restarting', 'Dead'])):
        raise RuntimeError('identity/workload/security mismatch; STOP')
    return v


def sample(pid):
    p = Path('/proc') / str(pid)
    raw = (p / 'stat').read_text()
    start = raw[raw.rfind(') ') + 2:].split()[19]
    lines = (p / 'cgroup').read_text().splitlines()
    if len(lines) != 1 or not lines[0].startswith('0::/'):
        raise RuntimeError('not cgroup v2')
    group = Path('/sys/fs/cgroup') / lines[0][4:]
    if group.resolve() != group or '..' in group.parts or group == Path('/sys/fs/cgroup'):
        raise RuntimeError('unsafe cgroup path')
    members = (group / 'cgroup.procs').read_text().split()
    if members != [str(pid)]:
        raise RuntimeError('unexpected cgroup processes')
    # No nested cgroups may conceal additional members.
    if any(x.is_dir() for x in group.iterdir()):
        raise RuntimeError('unexpected nested cgroup')
    cmd = (p / 'cmdline').read_bytes().split(b'\0')
    if cmd != [b'sleep', b'3600', b'']:
        raise RuntimeError('unexpected process command')
    status = dict(line.split(':', 1) for line in (p / 'status').read_text().splitlines() if ':' in line)
    if status['NoNewPrivs'].strip() != '1' or any(int(x) != 65534 for x in status['Uid'].split()):
        raise RuntimeError('runtime privilege mismatch')
    if int(status['CapEff'].strip(), 16) != 0:
        raise RuntimeError('unexpected effective capabilities')
    interfaces = [line.split(':')[0].strip() for line in (p / 'net/dev').read_text().splitlines()[2:]]
    if interfaces != ['lo']:
        raise RuntimeError('unexpected network interface')
    fields = dict(line.split() for line in (group / 'cpu.stat').read_text().splitlines())
    return {'monotonicNs': time.monotonic_ns(), 'usageUsec': int(fields['usage_usec']),
            'cpuStat': fields, 'pid': pid, 'startTime': start, 'cgroup': str(group),
            'members': members, 'command': ['sleep', '3600'], 'interfaces': interfaces}


def main():
    if os.geteuid() != 0 or len(sys.argv) != 1:
        raise RuntimeError('administrator required; no arguments')
    report_path = STAGE / 'idle-baseline.json'
    fd = os.open(report_path, os.O_WRONLY | os.O_CREAT | os.O_EXCL | os.O_NOFOLLOW, 0o644)
    os.fchmod(fd, 0o644)
    r = {'containerId': CID, 'imageId': IMAGE, 'startAttempted': False,
         'realExecutionAuthorized': False, 'status': 'NOT REAL EXECUTION READY'}
    try:
        if Path('/var/lib/chaoslab-m1/state/authorization.json').exists():
            raise RuntimeError('unexpected authorization file')
        before = inspect()
        r['beforeState'] = before['State']['Status']
        if not before['State']['Running']:
            r['startAttempted'] = True
            # Start exact ID to avoid a concurrent rename/name reuse selecting another target.
            docker('start', CID)
        after = inspect()
        if not after['State']['Running']:
            raise RuntimeError('container not running after start')
        r['securityConfiguration'] = EXPECTED
        r['samples'] = []
        pid = after['State']['Pid']
        for i in range(5):
            current = inspect()
            if not current['State']['Running'] or current['State']['Pid'] != pid:
                raise RuntimeError('target changed during baseline')
            s = sample(pid)
            if r['samples'] and any(s[k] != r['samples'][0][k] for k in ['startTime', 'cgroup']):
                raise RuntimeError('process identity changed')
            r['samples'].append(s)
            if i < 4:
                time.sleep(1)
        r['oneCpuPercent'] = []
        for a, b in zip(r['samples'], r['samples'][1:]):
            elapsed, usage = b['monotonicNs'] - a['monotonicNs'], b['usageUsec'] - a['usageUsec']
            if elapsed <= 0 or usage < 0:
                raise RuntimeError('invalid counter delta')
            r['oneCpuPercent'].append(usage * 100000.0 / elapsed)
        final = inspect()
        if not final['State']['Running'] or final['State']['Pid'] != pid:
            raise RuntimeError('final target changed')
        r['running'] = True
        r['health'] = 'NO_HEALTHCHECK_CONFIGURED; process/state baseline only'
        r['baselineResult'] = 'PASS; not recovery or real execution readiness evidence'
    except Exception as e:
        r['blocker'] = str(e) if isinstance(e, RuntimeError) else type(e).__name__
        r['baselineResult'] = 'STOP; no automatic retry, stop, or configuration change'
    finally:
        with os.fdopen(fd, 'w') as f:
            json.dump(r, f, indent=2)
            f.flush()
            os.fsync(f.fileno())
    print(r['baselineResult'])
    print('REAL EXECUTION NOT AUTHORIZED')


if __name__ == '__main__':
    main()
