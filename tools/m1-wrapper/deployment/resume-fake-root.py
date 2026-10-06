"""Pinned, fixed FAKE-only resume after the recorded preflight stop. No REAL tools."""
import hashlib
import json
import os
from pathlib import Path
import runpy
import subprocess
import sys

STAGE = Path('/home/w/chaoslab-m1-2d3-20261004')
WRAPPER = Path('/usr/local/libexec/chaoslab-m1-wrapper')
OLD = '00e3b03f4e88621890bce0f7abea092e09a4d13e13200637439ccf77d31a8657'
NEW = '7b4ccbbc85c21bff728c9e7c065353b1e0826da3a0991c2f993b5cb9bbb91f13'
SUITE_SHA = '5e08672a206ef6439e7a835b2a5884dd6ff67d8cec9c2f74d579ef57c50cddb8'
UPGRADE_SHA = 'b7886588b3ab63ac5c1f2debd16fdc2531084f9442c5e9da7a46f0468d16e4ab'
IDLE_SHA = 'e498834001c352908d8884d1eb798b9c1c7e40fb01e006fc3a170e5a3e8d3dd4'
FAKE_SHA = '3c9ed3e06b629297d503cfe2659604f1c74459ad226dd6d8b3feff50fbc33dc9'
POLICY = Path('/etc/chaoslab-m1/policy.json')
STATE = Path('/var/lib/chaoslab-m1/fixture-state')
TOOL = Path('/opt/chaoslab/m1-fixture/v1/fake-blade')
REPORT = 'fake-root-suite-resumed.json'


def sha(b):
    return hashlib.sha256(b).hexdigest()


def main():
    if os.geteuid() != 0 or len(sys.argv) != 1:
        raise RuntimeError('administrator required; no arguments')
    os.umask(0o077)
    inputs = [(STAGE/'upgrade-wrapper.py',UPGRADE_SHA), (STAGE/'start-idle.py',IDLE_SHA),
              (STAGE/'fake-root-suite-v3.py',SUITE_SHA), (STAGE/'chaoslab-m1-wrapper-v3',NEW)]
    for path,pin in inputs:
        if path.is_symlink() or sha(path.read_bytes())!=pin:
            raise RuntimeError('reviewed source/binary mismatch')
    upgrade = runpy.run_path(str(inputs[0][0]))
    trust,write = upgrade['trust'],upgrade['write_new']
    for path in [WRAPPER,POLICY,STATE,TOOL,Path('/var/backups/chaoslab-m1-2d3-20261004')]:
        trust(path)
    if sha(WRAPPER.read_bytes())!=OLD or sha(TOOL.read_bytes())!=FAKE_SHA:
        raise RuntimeError('installed version changed')
    old_report=json.loads((STAGE/'fake-root-suite.json').read_bytes())
    if old_report.get('blocker')!='fake preflight failed' or old_report.get('tests')!=[] or old_report.get('authorizationAbsent') is not True:
        raise RuntimeError('not the recorded pre-dispatch stop')
    for root in [STATE,Path('/var/lib/chaoslab-m1/state')]:
        for name in ['authorization.json','binding.json','operation.lock','invocation.json','scenario']:
            if os.path.lexists(root/name):raise RuntimeError('unexpected live state; STOP')
    for path in [STAGE/REPORT,Path('/var/lib/chaoslab-m1/audit')/REPORT]:
        if os.path.lexists(path):raise RuntimeError('report exists; refuse repeat')
    p=json.loads(POLICY.read_bytes())
    small=sha(b'fixture')
    expected=dict(deployment='FAKE',executable=str(TOOL),stateDirectory=str(STATE),
                  containerId='18bb4f8734edbd6e1dc35582692677bcfcf98fef175832fc1d1e29361faab97f',
                  imageId='sha256:5291449c3df73caf6ed85e649dec1b9e818b39a5d8c871e97afc13e9cd5e8fa8',
                  containerName='chaoslab-cpu-sandbox',user='65534:65534',nanoCpus=500000000,
                  memory=134217728,pids=32,cpuPercent=10,durationSeconds=10,nodeId='m1-executor',
                  stateId='m1-fixture-state',toolSha256=FAKE_SHA,nsexecSha256=small,chaosOsSha256=small,yamlSha256=small)
    if p!=expected:raise RuntimeError('unexpected FAKE policy; refuse replacement')
    for rel,pin in [('bin/nsexec',small),('bin/chaos_os',small),('yaml/chaosblade-cri-spec-1.8.1.yaml',small)]:
        path=TOOL.parent/rel;trust(path)
        if sha(path.read_bytes())!=pin:raise RuntimeError('companion changed')
    # No old fixture child may be silently adopted by the resumed acceptance.
    for entry in Path('/proc').iterdir():
        if not entry.name.isdigit():continue
        try:
            if os.readlink(entry/'exe')==str(TOOL):raise RuntimeError('old FAKE process present')
        except FileNotFoundError:pass
    sudo_files=[Path('/etc/sudoers'),*sorted(Path('/etc/sudoers.d').iterdir())]
    rules={str(x):sha(x.read_bytes()) for x in sudo_files}
    if Path('/etc/sudoers.d/chaoslab-m1').read_bytes()!=b'chaoslab ALL=(root) NOPASSWD: /usr/local/libexec/chaoslab-m1-wrapper ""\n':
        raise RuntimeError('sudo boundary changed')
    if subprocess.run(['/usr/sbin/visudo','-c'],env=upgrade['ENV'],capture_output=True,timeout=10).returncode:
        raise RuntimeError('sudo validation failed')
    backup=Path('/var/backups/chaoslab-m1-2d3-20261004')/'wrapper-before-preflight-fix'
    write(backup,WRAPPER.read_bytes(),0o700)
    temporary=WRAPPER.with_name('chaoslab-m1-wrapper.preflight-new')
    write(temporary,inputs[-1][0].read_bytes(),0o755);trust(temporary)
    os.replace(temporary,WRAPPER)
    fd=os.open(WRAPPER.parent,os.O_RDONLY|os.O_DIRECTORY)
    try:os.fsync(fd)
    finally:os.close(fd)
    trust(WRAPPER)
    if sha(WRAPPER.read_bytes())!=NEW or rules!={str(x):sha(x.read_bytes()) for x in sudo_files}:
        raise RuntimeError('installed digest/sudo boundary mismatch')
    idle=runpy.run_path(str(STAGE/'start-idle.py'))
    v=idle['inspect']()
    if not v['State']['Running']:
        idle['docker']('start',v['Id'])
        v=idle['inspect']()
    if not v['State']['Running']:raise RuntimeError('sandbox failed to start')
    identity=idle['sample'](v['State']['Pid'])
    suite=runpy.run_path(str(STAGE/'fake-root-suite-v3.py'))
    suite['run_suite'](expected,idle,identity,REPORT)
    print('WRAPPER UPDATED; FAKE-ONLY ROOT REPORT RECORDED')
    print('NO REAL AUTHORIZATION; REAL EXECUTION NOT AUTHORIZED')


if __name__=='__main__':main()
