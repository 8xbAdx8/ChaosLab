"""Install only the approved account, wrapper and exact sudo boundary.

No policy, authorization, binding, candidate execution or Docker operations.
Fail closed on existing targets or changed sudo baseline. No automatic rollback.
"""
import errno
import hashlib
import json
import os
from pathlib import Path
import pwd
import grp
import stat
import subprocess
import sys

STAGE = Path('/home/w/chaoslab-m1-2d3-20261004')
BACKUP = Path('/var/backups/chaoslab-m1-2d3-20261004')
WRAPPER = Path('/usr/local/libexec/chaoslab-m1-wrapper')
SHA = '17a7ec21e7b3e9a74a15f5bba7fb9528ade23d0683f5e5ecbeb53c8dfc8696a2'
SUDO_SHA = '5bac27ce5ff1a78ace8f3ef81bfd60cbd44810ac3f3d280da9d7649fe90c18f8'
README_SHA = 'b428c9b673c3c806370f2aa28a98293a9cb578c70c3a8a2d1a39031861b3dbd8'
FRAGMENT = Path('/etc/sudoers.d/chaoslab-m1')
RULE = b'chaoslab ALL=(root) NOPASSWD: /usr/local/libexec/chaoslab-m1-wrapper ""\n'
ENV = {'PATH': '/usr/sbin:/usr/bin:/sbin:/bin', 'HOME': '/root', 'LANG': 'C', 'LC_ALL': 'C'}
DIRS = [('/usr/local/libexec', 0o755), ('/etc/chaoslab-m1', 0o700),
        ('/var/lib/chaoslab-m1', 0o700), ('/var/lib/chaoslab-m1/state', 0o700),
        ('/var/lib/chaoslab-m1/audit', 0o700)]


def trusted(path):
    for p in [path, *path.parents]:
        s = p.lstat()
        if s.st_uid != 0 or s.st_gid != 0 or s.st_mode & 0o022 or stat.S_ISLNK(s.st_mode):
            raise RuntimeError('untrusted root path: ' + str(p))
        for attr in ['system.posix_acl_access', 'system.posix_acl_default', 'security.capability']:
            try:
                if os.getxattr(p, attr, follow_symlinks=False):
                    raise RuntimeError('unexpected ACL/capability: ' + str(p))
            except OSError as e:
                if e.errno not in [errno.ENODATA, errno.ENOTSUP]:
                    raise


def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def command(args, data=None):
    return subprocess.run(args, input=data, env=ENV, stdout=subprocess.PIPE,
                          stderr=subprocess.PIPE, timeout=15)


def checked(args):
    p = command(args)
    if p.returncode:
        raise RuntimeError('command failed: ' + args[0])
    return p.stdout.decode('utf-8', 'replace')


def create(path, data, mode):
    trusted(path.parent)
    fd = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_EXCL | os.O_NOFOLLOW, mode)
    with os.fdopen(fd, 'wb') as f:
        os.fchmod(f.fileno(), mode)
        f.write(data)
        f.flush()
        os.fsync(f.fileno())


def main():
    if os.geteuid() != 0 or len(sys.argv) != 1:
        raise RuntimeError('root required; no arguments')
    os.umask(0o077)
    trusted(BACKUP)
    if digest(Path('/etc/sudoers')) != SUDO_SHA:
        raise RuntimeError('sudo baseline changed')
    if sorted(p.name for p in Path('/etc/sudoers.d').iterdir()) != ['README']:
        raise RuntimeError('sudo include set changed')
    if digest(Path('/etc/sudoers.d/README')) != README_SHA:
        raise RuntimeError('sudo README changed')
    for p in [Path('/etc/sudoers'), Path('/etc/sudoers.d'), Path('/usr/local'),
              Path('/var/lib'), Path('/etc')]:
        trusted(p)
    for name in ['passwd', 'shadow', 'group', 'gshadow']:
        if digest(Path('/etc') / name) != digest(BACKUP / ('etc-' + name)):
            raise RuntimeError('account baseline changed; refresh rollback audit first')
    for lookup in [pwd.getpwnam, grp.getgrnam]:
        try:
            lookup('chaoslab')
        except KeyError:
            continue
        raise RuntimeError('account/group already exists; no implicit reuse')
    for p in [WRAPPER, FRAGMENT, *[Path(p) for p, _ in DIRS]]:
        if os.path.lexists(p):
            raise RuntimeError('target already exists: ' + str(p))
    data = (STAGE / 'chaoslab-m1-wrapper').read_bytes()
    if len(data) > 128 * 1024 * 1024 or hashlib.sha256(data).hexdigest() != SHA:
        raise RuntimeError('reviewed wrapper hash mismatch')
    checked(['/usr/sbin/visudo', '-c'])
    plan = {'modifiedPaths': [p for p, _ in DIRS] + [str(WRAPPER), str(FRAGMENT),
            '/etc/passwd', '/etc/shadow', '/etc/group', '/etc/gshadow',
            '/etc/subuid', '/etc/subgid'], 'authorizationCreated': False,
            'candidateExecuted': False, 'sandboxModified': False}
    # Additional useradd-managed files, if present, retained privately.
    for name in ['subuid', 'subgid']:
        p = Path('/etc') / name
        if p.exists():
            create(BACKUP / ('boundary-before-' + name), p.read_bytes(), 0o600)
    create(BACKUP / 'boundary-plan.json', json.dumps(plan, indent=2).encode(), 0o600)
    r = dict(plan, completed=False)
    try:
        checked(['/usr/sbin/useradd', '--system', '--user-group', '--no-create-home',
                 '--home-dir', '/nonexistent', '--shell', '/usr/sbin/nologin', 'chaoslab'])
        account = pwd.getpwnam('chaoslab')
        gids = os.getgrouplist('chaoslab', account.pw_gid)
        if gids != [account.pw_gid] or account.pw_shell != '/usr/sbin/nologin' or account.pw_uid == 0:
            raise RuntimeError('unexpected service account permissions')
        r['id'] = checked(['/usr/bin/id', 'chaoslab']).strip()
        r['groups'] = [grp.getgrgid(g).gr_name for g in gids]
        if r['groups'] != ['chaoslab']:
            raise RuntimeError('unexpected service group')
        for path, mode in DIRS:
            p = Path(path)
            trusted(p.parent)
            p.mkdir(mode=mode)
            os.chmod(p, mode)
            trusted(p)
        create(WRAPPER, data, 0o755)
        trusted(WRAPPER)
        r['wrapperSha256'] = digest(WRAPPER)
        if r['wrapperSha256'] != SHA:
            raise RuntimeError('installed wrapper mismatch')
        r['wrapperOwnerMode'] = 'root:root 0755; no ACL/capability/symlink'
        tmp = BACKUP / 'sudoers-chaoslab-m1.fragment'
        create(tmp, RULE, 0o440)
        checked(['/usr/sbin/visudo', '-cf', str(tmp)])
        create(FRAGMENT, RULE, 0o440)
        checked(['/usr/sbin/visudo', '-c'])
        r['sudoList'] = checked(['/usr/bin/sudo', '-n', '-l', '-U', 'chaoslab'])
        prefix = ['/usr/sbin/runuser', '-u', 'chaoslab', '--', '/usr/bin/sudo', '-n']
        forbidden = ['/bin/sh', '/bin/bash', '/usr/bin/docker', '/usr/bin/env',
                     '/usr/bin/python3', '/usr/bin/systemctl', '/usr/bin/systemd-run',
                     '/usr/bin/id', '/opt/chaoslab/m1/api3-identified/blade-chaoslab-api3-identified']
        r['sudoNegativeAuthorizationQueries'] = {}
        for exe in forbidden:
            # Query effective permission, NEVER invoke the forbidden command.
            p = command(prefix + ['-l', '--', exe])
            r['sudoNegativeAuthorizationQueries'][exe] = p.returncode != 0
            if p.returncode == 0:
                raise RuntimeError('unexpected sudo permission: ' + exe)
        p = command(prefix + ['-l', '--', str(WRAPPER), 'unexpected-argument'])
        r['wrapperArgvDenied'] = p.returncode != 0
        if not r['wrapperArgvDenied']:
            raise RuntimeError('wrapper argv permitted')
        p = command(prefix + ['--', str(WRAPPER)], b'{"operation":"preflight"}')
        response = json.loads(p.stdout)
        r['rootWrapperWithoutPolicy'] = response
        if p.returncode != 1 or response.get('code') != 'IDENTITY_REJECTED':
            raise RuntimeError('unexpected wrapper entry behavior')
        r['writableChecks'] = {}
        for user in ['w', 'chaoslab']:
            for path in [str(WRAPPER), str(FRAGMENT), *[p for p, _ in DIRS]]:
                p = command(['/usr/sbin/runuser', '-u', user, '--', '/usr/bin/test', '-w', path])
                r['writableChecks'][user + ':' + path] = p.returncode == 0
                if p.returncode != 1:
                    raise RuntimeError('write access or check failure')
        r['completed'] = True
    except Exception as e:
        r['blocker'] = str(e) if isinstance(e, RuntimeError) else type(e).__name__
    create(BACKUP / 'boundary-result.json', json.dumps(r, indent=2).encode(), 0o600)
    # Public sanitized report only; no passwords/account database contents.
    fd = os.open(STAGE / 'boundary-result.json', os.O_WRONLY | os.O_CREAT | os.O_EXCL | os.O_NOFOLLOW, 0o644)
    with os.fdopen(fd, 'w') as f:
        os.fchmod(f.fileno(), 0o644)
        json.dump(r, f, indent=2)
    print('BOUNDARY BOOTSTRAP:', 'PASS' if r['completed'] else 'STOP; inspect report')
    print('Fake root lifecycle and candidate deployment NOT YET VERIFIED')
    print('REAL EXECUTION NOT AUTHORIZED')


if __name__ == '__main__':
    main()
