"""Read-only native-state diagnosis. No CLI, authorization, container or state mutation."""
import hashlib
import json
import os
from pathlib import Path
import sqlite3
import stat
import sys

ROOT = Path('/var/lib/chaoslab-m1/state')


def main():
    if os.geteuid() != 0 or len(sys.argv) != 1:
        raise RuntimeError('administrator required; no arguments')
    for p in [ROOT, *ROOT.parents]:
        s = p.lstat()
        if stat.S_ISLNK(s.st_mode) or s.st_uid != 0 or s.st_mode & 0o022:
            raise RuntimeError('untrusted state parent')
    files = {}
    for p in ROOT.iterdir():
        s = p.lstat()
        files[p.name] = {'size': s.st_size, 'uid': s.st_uid, 'mode': oct(stat.S_IMODE(s.st_mode))}
    print(json.dumps({'stateFiles': files}))
    db = ROOT / 'chaosblade.dat'
    s = db.lstat()
    if not stat.S_ISREG(s.st_mode) or s.st_uid != 0 or s.st_mode & 0o022 or s.st_size > 16 * 1024 * 1024:
        raise RuntimeError('untrusted or oversized native DB')
    for suffix in ['-wal', '-shm', '-journal']:
        if os.path.lexists(str(db) + suffix):
            raise RuntimeError('sidecar exists; no immutable snapshot assumption')
    before = hashlib.sha256(db.read_bytes()).hexdigest()
    conn = sqlite3.connect(db.as_uri() + '?mode=ro&immutable=1', uri=True)
    try:
        conn.execute('PRAGMA query_only=ON')
        tables = conn.execute("SELECT name FROM sqlite_master WHERE type='table' ORDER BY name").fetchall()
        counts = {}
        for (name,) in tables:
            if not name.replace('_', '').isalnum():
                raise RuntimeError('unexpected schema name')
            counts[name] = conn.execute('SELECT count(*) FROM "' + name + '"').fetchone()[0]
        integrity = conn.execute('PRAGMA integrity_check').fetchall()
    finally:
        conn.close()
    after = hashlib.sha256(db.read_bytes()).hexdigest()
    if before != after or s.st_ino != db.stat().st_ino:
        raise RuntimeError('native DB changed during diagnosis')
    print(json.dumps({'nativeDbSha256': before, 'tableCounts': counts, 'integrity': integrity,
                      'stateUnchanged': True, 'realCreateInvoked': False}))
    print('REAL EXECUTION NOT AUTHORIZED')


if __name__ == '__main__':
    main()
