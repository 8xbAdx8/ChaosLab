"""Read-only Docker inspection. Does not start containers or execute workloads."""
import json
import os
from pathlib import Path
import subprocess
import sys

STAGE = Path('/home/w/chaoslab-m1-2d3-20261004')
CID = '18bb4f8734edbd6e1dc35582692677bcfcf98fef175832fc1d1e29361faab97f'
IMAGE = 'sha256:5291449c3df73caf6ed85e649dec1b9e818b39a5d8c871e97afc13e9cd5e8fa8'


def main():
    if os.geteuid() != 0 or len(sys.argv) != 1:
        raise RuntimeError('administrator required; no arguments accepted')
    # Fixed executable and target; no shell, no caller Docker environment.
    p = subprocess.run(['/usr/bin/docker', '--host=unix:///var/run/docker.sock',
                        'inspect', '--type=container', CID],
                       env={'PATH': '/usr/bin:/bin', 'LANG': 'C', 'HOME': '/root'},
                       stdout=subprocess.PIPE, stderr=subprocess.PIPE, timeout=10)
    if p.returncode or len(p.stdout) > 1024 * 1024:
        raise RuntimeError('inspect failed')
    v, = json.loads(p.stdout)
    c, h = v['Config'], v['HostConfig']
    report = {'containerId': v['Id'], 'imageId': v['Image'], 'name': v['Name'],
              'identityMatches': v['Id'] == CID and v['Image'] == IMAGE,
              'entrypoint': c.get('Entrypoint'), 'cmd': c.get('Cmd'),
              'path': v.get('Path'), 'args': v.get('Args'),
              'healthcheck': c.get('Healthcheck'), 'user': c['User'],
              'state': {k: v['State'].get(k) for k in
                        ['Status', 'Running', 'Paused', 'Restarting', 'Dead', 'Pid', 'ExitCode']},
              'mounts': v['Mounts'],
              'hostConfig': {k: h.get(k) for k in
                             ['NetworkMode', 'Binds', 'Privileged', 'CapAdd', 'CapDrop',
                              'SecurityOpt', 'NanoCpus', 'Memory', 'PidsLimit',
                              'RestartPolicy', 'ReadonlyRootfs', 'Devices', 'PidMode',
                              'IpcMode', 'CgroupnsMode', 'Init']},
              'startedByThisScript': False,
              'decision': 'REVIEW REQUIRED; NO START PERFORMED'}
    data = (json.dumps(report, indent=2) + '\n').encode()
    # Refuse to overwrite any previous evidence, including symlinks.
    fd = os.open(STAGE / 'idle-inspect.json',
                 os.O_WRONLY | os.O_CREAT | os.O_EXCL | os.O_NOFOLLOW, 0o644)
    with os.fdopen(fd, 'wb') as f:
        f.write(data)
        f.flush()
        os.fsync(f.fileno())
    print('Read-only inspection saved. Container NOT started.')


if __name__ == '__main__':
    try:
        main()
    except Exception as e:
        print('STOP:', type(e).__name__, '; NOT REAL EXECUTION READY')
        sys.exit(1)
