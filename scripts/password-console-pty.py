"""Unix real-PTY Node -> Java regression, no packages/credentials/files required.

Windows ConPTY is not POSIX pty; its manual hidden-input check stays NOT_RUN.
Do not emit the captured transcript: it contains a disposable BCrypt hash.
"""
import os
import select
import signal
import subprocess
import sys
import time
import uuid
from pathlib import Path

if os.name == "nt":
    print("NOT_RUN: Unix PTY test; Windows hidden-input manual check is separate.")
    sys.exit(0)

import pty

root = Path(__file__).resolve().parents[1]
master, slave = pty.openpty()
child = subprocess.Popen(["node", "scripts/password-console-fixture.mjs"], cwd=root,
                         stdin=slave, stdout=slave, stderr=slave, start_new_session=True)
os.close(slave)
transcript = bytearray()
offset = 0
deadline = time.monotonic() + 40
password = ("Fixture-" + str(uuid.uuid4())).encode()
wrong = ("Wrong-" + str(uuid.uuid4())).encode()

def expect(text):
    global offset
    value = text.encode()
    while True:
        found = transcript.find(value, offset)
        if found >= 0:
            offset = found + len(value)
            return
        if time.monotonic() >= deadline:
            raise AssertionError("PTY prompt deadline exceeded (transcript withheld)")
        readable, _, _ = select.select([master], [], [], 0.2)
        if readable:
            try:
                data = os.read(master, 4096)
            except OSError:
                data = b""
            if not data:
                raise AssertionError("Console ended before expected prompt (transcript withheld)")
            transcript.extend(data)

try:
    expect("Fixture username: ")
    os.write(master, b"fixture.admin\n")
    expect("Password (not echoed): ")
    os.write(master, password + b"\n")
    expect("Confirm password: ")
    os.write(master, wrong + b"\n")
    expect("Passwords do not match. No account saved.")
    expect("Password (not echoed): ")
    os.write(master, password + b"\n")
    expect("Confirm password: ")
    os.write(master, password + b"\n")
    expect("$2a$12$")
    child.wait(timeout=max(0.1, deadline - time.monotonic()))
    assert child.returncode == 0, "Console child failed"
    assert password not in transcript and wrong not in transcript, "Hidden input echoed (transcript withheld)"
    print("PASS: real Unix PTY, Node input released, mismatch retry, BCrypt generated, no password echo.")
except Exception:
    # Avoid a traceback containing subprocess output or local credential material.
    print("FAIL: password console PTY acceptance (transcript withheld).", file=sys.stderr)
    sys.exit(1)
finally:
    if child.poll() is None:
        # Dedicated start_new_session group, including its Java child. No global kill.
        os.killpg(child.pid, signal.SIGKILL)
        child.wait(timeout=5)
    os.close(master)
    transcript[:] = b"\0" * len(transcript)
