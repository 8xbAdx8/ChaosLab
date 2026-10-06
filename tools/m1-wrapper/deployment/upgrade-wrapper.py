"""Replace only the pinned wrapper; no policy, authorization, Docker or child fixture."""
import hashlib
import json
import os
from pathlib import Path
import stat
import subprocess
import sys
import errno

STAGE = Path('/home/w/chaoslab-m1-2d3-20261004')
DEST = Path('/usr/local/libexec/chaoslab-m1-wrapper')
BACKUP = Path('/var/backups/chaoslab-m1-2d3-20261004')
OLD = '17a7ec21e7b3e9a74a15f5bba7fb9528ade23d0683f5e5ecbeb53c8dfc8696a2'
NEW = '00e3b03f4e88621890bce0f7abea092e09a4d13e13200637439ccf77d31a8657'
ENV = {'PATH': '/usr/sbin:/usr/bin:/sbin:/bin', 'HOME': '/root', 'LANG': 'C', 'LC_ALL': 'C'}


def sha(b):
    return hashlib.sha256(b).hexdigest()


def trust(p):
    for x in [p, *p.parents]:
        s = x.lstat()
        if s.st_uid != 0 or s.st_gid != 0 or s.st_mode & 0o022 or stat.S_ISLNK(s.st_mode):
            raise RuntimeError('unsafe trusted path')
        for a in ['system.posix_acl_access', 'system.posix_acl_default', 'security.capability']:
            try:
                if os.getxattr(x, a, follow_symlinks=False):
                    raise RuntimeError('unexpected ACL/capability')
            except OSError as e:
                if e.errno not in [errno.ENODATA, errno.ENOTSUP]:
                    raise


def write_new(path, data, mode):
    fd = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_EXCL | os.O_NOFOLLOW, mode)
    with os.fdopen(fd, 'wb') as f:
        os.fchmod(f.fileno(), mode)
        f.write(data)
        f.flush()
        os.fsync(f.fileno())


def command(args, data=None):
    return subprocess.run(args, input=data, stdout=subprocess.PIPE, stderr=subprocess.PIPE,
                          env=ENV, timeout=15)


def main():
    if os.geteuid() != 0 or len(sys.argv) != 1:
        raise RuntimeError('root required; no args')
    os.umask(0o077)
    for p in [DEST, BACKUP, Path('/etc/chaoslab-m1'), Path('/var/lib/chaoslab-m1')]:
        trust(p)
    # This upgrade is only for the previously observed empty deployment.
    for p in ['/etc/chaoslab-m1/policy.json', '/var/lib/chaoslab-m1/fixture-state',
              '/var/lib/chaoslab-m1/state/authorization.json',
              '/var/lib/chaoslab-m1/state/binding.json', '/var/lib/chaoslab-m1/state/operation.lock']:
        if os.path.lexists(p):
            raise RuntimeError('unexpected deployment state; STOP')
    old = DEST.read_bytes()
    new = (STAGE / 'chaoslab-m1-wrapper-v2').read_bytes()
    if sha(old) != OLD or sha(new) != NEW:
        raise RuntimeError('binary hash mismatch')
    files = [Path('/etc/sudoers'), *sorted(Path('/etc/sudoers.d').iterdir())]
    rules = {str(p): sha(p.read_bytes()) for p in files}
    expected_rule = b'chaoslab ALL=(root) NOPASSWD: /usr/local/libexec/chaoslab-m1-wrapper ""\n'
    if Path('/etc/sudoers.d/chaoslab-m1').read_bytes() != expected_rule:
        raise RuntimeError('sudo boundary changed')
    if command(['/usr/sbin/visudo', '-c']).returncode:
        raise RuntimeError('invalid sudo configuration')
    # Preserve reviewed old binary before atomic replacement; never overwrite backups.
    write_new(BACKUP / 'wrapper-before-slots', old, 0o700)
    temp = DEST.with_name('chaoslab-m1-wrapper.slots-new')
    write_new(temp, new, 0o755)
    trust(temp)
    os.replace(temp, DEST)
    fd = os.open(DEST.parent, os.O_RDONLY | os.O_DIRECTORY)
    os.fsync(fd)
    os.close(fd)
    r = {'wrapperSha256': sha(DEST.read_bytes()), 'authorizationCreated': False,
         'policyCreated': False, 'completed': False}
    try:
        trust(DEST)
        if r['wrapperSha256'] != NEW:
            raise RuntimeError('installed hash mismatch')
        after = [Path('/etc/sudoers'), *sorted(Path('/etc/sudoers.d').iterdir())]
        r['sudoUnchanged'] = rules == {str(p): sha(p.read_bytes()) for p in after}
        if not r['sudoUnchanged']:
            raise RuntimeError('sudo rules changed')
        prefix = ['/usr/sbin/runuser', '-u', 'chaoslab', '--', '/usr/bin/sudo', '-n']
        r['withoutPolicyCreate'] = []
        q = b'{"operation":"create-cpu","executionId":"11111111-1111-1111-1111-111111111111","nativeUid":"0123456789abcdef"}'
        for _ in range(3):
            p = command(prefix + ['--', str(DEST)], q)
            response = json.loads(p.stdout)
            if p.returncode != 1 or response.get('code') != 'IDENTITY_REJECTED':
                raise RuntimeError('unexpected create result')
            r['withoutPolicyCreate'].append(response)
        r['note'] = 'No policy: IDENTITY_REJECTED; CREATE_DISABLED with valid FAKE policy still pending'
        r['negativeSudo'] = {}
        for exe in ['/bin/sh', '/bin/bash', '/usr/bin/docker', '/usr/bin/env',
                    '/usr/bin/python3', '/usr/bin/systemctl', '/usr/bin/systemd-run']:
            denied = command(prefix + ['-l', '--', exe]).returncode != 0
            r['negativeSudo'][exe] = denied
            if not denied:
                raise RuntimeError('unexpected sudo permission')
        if command(prefix + ['-l', '--', str(DEST), 'FAKE']).returncode == 0:
            raise RuntimeError('caller argv permitted')
        r['completed'] = True
    except Exception as e:
        r['blocker'] = str(e) if isinstance(e, RuntimeError) else type(e).__name__
    data = json.dumps(r, indent=2).encode()
    write_new(BACKUP / 'wrapper-upgrade-result.json', data, 0o600)
    write_new(STAGE / 'wrapper-upgrade-result.json', data, 0o644)
    print('WRAPPER UPGRADE:', 'PASS' if r['completed'] else 'STOP; read report')
    print('NO AUTHORIZATION GENERATED; REAL EXECUTION NOT AUTHORIZED')


if __name__ == '__main__':
    main()
