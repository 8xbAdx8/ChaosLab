"""Read-only diagnosis of the stopped FAKE preflight; never authorizes/dispatches."""
import hashlib
import json
import os
from pathlib import Path
import runpy
import subprocess
import sys

STAGE = Path('/home/w/chaoslab-m1-2d3-20261004')
WRAPPER = Path('/usr/local/libexec/chaoslab-m1-wrapper')
WRAPPER_SHA = '00e3b03f4e88621890bce0f7abea092e09a4d13e13200637439ccf77d31a8657'
UPGRADE_SHA = 'b7886588b3ab63ac5c1f2debd16fdc2531084f9442c5e9da7a46f0468d16e4ab'
IDLE_SHA = 'e498834001c352908d8884d1eb798b9c1c7e40fb01e006fc3a170e5a3e8d3dd4'


def main():
    if os.geteuid() != 0 or len(sys.argv) != 1:
        raise RuntimeError('administrator required; no arguments')
    os.umask(0o077)
    upgrade, idle = STAGE/'upgrade-wrapper.py', STAGE/'start-idle.py'
    for path, pin in [(upgrade, UPGRADE_SHA), (idle, IDLE_SHA), (WRAPPER, WRAPPER_SHA)]:
        if path.is_symlink() or hashlib.sha256(path.read_bytes()).hexdigest() != pin:
            raise RuntimeError('reviewed source/binary mismatch')
    api = runpy.run_path(str(upgrade))
    trust, write = api['trust'], api['write_new']
    policy_path = Path('/etc/chaoslab-m1/policy.json')
    trust(WRAPPER)
    trust(policy_path)
    p = json.loads(policy_path.read_bytes())
    if p.get('deployment') != 'FAKE' or p.get('executable') != '/opt/chaoslab/m1-fixture/v1/fake-blade' or p.get('stateDirectory') != '/var/lib/chaoslab-m1/fixture-state':
        raise RuntimeError('not the fixed FAKE deployment')
    for state in ['/var/lib/chaoslab-m1/state', p['stateDirectory']]:
        if any(os.path.lexists(Path(state)/name) for name in ['authorization.json','binding.json','operation.lock']):
            raise RuntimeError('unexpected live authorization/binding/lock; STOP')
    paths = [Path(p['executable']), Path(p['stateDirectory']), policy_path.parent/'node-id', Path(p['stateDirectory'])/'.chaoslab-state-id']
    for path in paths:
        trust(path)
    prefix = ['/usr/sbin/runuser','-u','chaoslab','--','/usr/bin/sudo','-n','--',str(WRAPPER)]
    proc = subprocess.run(prefix, input=b'{"operation":"preflight"}', capture_output=True,
                          env=api['ENV'], timeout=10)
    r = {'readOnly':True, 'authorizationCreated':False, 'childDispatched':False,
         'wrapperExit':proc.returncode, 'stderrBytes':len(proc.stderr), 'wrapperSha256':WRAPPER_SHA}
    try:
        out = json.loads(proc.stdout)
        r['preflightCode'] = out.get('code')
        r['cleanupComplete'] = out.get('cleanupComplete')
    except (ValueError, UnicodeError):
        r['preflightCode'] = 'TRANSPORT_UNKNOWN'
    # No automatic start in this diagnosis. Inspect is read-only and validates
    # the full previously approved idle workload and isolation configuration.
    v = runpy.run_path(str(idle))['inspect']()
    h = v['HostConfig']
    r['sandboxRunning'] = v['State']['Running']
    r['dockerLimitField'] = {'NanoCpusPresent':'NanoCpus' in h,
                            'NanoCPUsPresent':'NanoCPUs' in h, 'NanoCpus':h.get('NanoCpus')}
    r['candidateCause'] = ('WRAPPER_DOCKER_FIELD_CASE_MISMATCH' if r['preflightCode']=='TARGET_UNKNOWN'
                          and 'NanoCpus' in h and 'NanoCPUs' not in h and v['State']['Running']
                          else 'UNKNOWN; do not relax checks')
    data = json.dumps(r, indent=2).encode()
    write(Path('/var/lib/chaoslab-m1/audit/fake-preflight-diagnosis.json'),data,0o600)
    write(STAGE/'fake-preflight-diagnosis.json',data,0o644)
    print('READ-ONLY DIAGNOSIS RECORDED; NO AUTHORIZATION OR CHILD')
    print('REAL EXECUTION NOT AUTHORIZED')


if __name__ == '__main__':
    main()
