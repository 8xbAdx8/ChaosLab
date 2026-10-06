"""Fixed FAKE deployment and root-chain acceptance. Never installs/runs real Blade."""
import datetime as dt
import hashlib
import json
import os
from pathlib import Path
import runpy
import signal
import subprocess
import sys
import time

STAGE = Path('/home/w/chaoslab-m1-2d3-20261004')
STATE = Path('/var/lib/chaoslab-m1/fixture-state')
TOOL = Path('/opt/chaoslab/m1-fixture/v1/fake-blade')
POLICY = Path('/etc/chaoslab-m1/policy.json')
AUDIT = Path('/var/lib/chaoslab-m1/audit/fake-root-suite.json')
WRAPPER = '/usr/local/libexec/chaoslab-m1-wrapper'
WRAPPER_SHA = '00e3b03f4e88621890bce0f7abea092e09a4d13e13200637439ccf77d31a8657'
FAKE_SHA = '3c9ed3e06b629297d503cfe2659604f1c74459ad226dd6d8b3feff50fbc33dc9'
ENV = {'PATH': '/usr/bin:/bin', 'LANG': 'C', 'LC_ALL': 'C', 'HOME': '/nonexistent'}
CHAIN = ['/usr/sbin/runuser', '-u', 'chaoslab', '--', '/usr/bin/sudo', '-n', '--', WRAPPER]
Q = {'operation': 'create-cpu', 'executionId': '22222222-2222-4222-8222-222222222222', 'nativeUid': '0123456789abcdef'}


def sha(data):
    return hashlib.sha256(data).hexdigest()


def encode(v):
    return json.dumps(v, separators=(',', ':'), ensure_ascii=False).encode()


def put(path, data, mode=0o600):
    fd = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_EXCL | os.O_NOFOLLOW, mode)
    with os.fdopen(fd, 'wb') as f:
        os.fchmod(f.fileno(), mode)
        f.write(data)
        f.flush()
        os.fsync(f.fileno())


def call(op='create-cpu'):
    q = dict(Q, operation=op) if op != 'preflight' else {'operation': op}
    p = subprocess.run(CHAIN, input=encode(q), env=ENV, capture_output=True, timeout=10)
    if len(p.stdout)>65536 or p.stderr:
        raise RuntimeError('unexpected wrapper transport output')
    return json.loads(p.stdout)


def authorization(p):
    now = dt.datetime.now(dt.timezone.utc)
    stamp = lambda t: t.isoformat(timespec='microseconds').replace('+00:00', 'Z')
    hashes = [p[k] for k in ['toolSha256','nsexecSha256','chaosOsSha256','yamlSha256']]
    return dict(deployment='FAKE', executable=str(TOOL), policyDigest=sha(encode(p)),
                toolSha256=hashes[0], nsexecSha256=hashes[1], chaosOsSha256=hashes[2], yamlSha256=hashes[3],
                expiresAt=stamp(now+dt.timedelta(seconds=15)), executionId=Q['executionId'], nativeUid=Q['nativeUid'],
                containerId=p['containerId'], imageId=p['imageId'], toolIdentity=sha(':'.join(hashes).encode()),
                stateIdentity=p['stateId'], nodeId=p['nodeId'], createdAt=stamp(now))


def helper():
    info = json.loads((STATE/'invocation.json').read_bytes())
    if info['euid'] != 0:
        raise RuntimeError('fixture was not root')
    pid = info.get('helperPid')
    if pid is None:
        return None
    start = info['helperStat'].rsplit(') ',1)[1].split()[19]
    return pid,start


def alive(identity):
    if identity is None:
        return False
    pid,start = identity
    try:
        f = Path('/proc',str(pid),'stat').read_text().rsplit(') ',1)[1].split()
        return f[19]==start and f[0] not in ['Z','X']
    except FileNotFoundError:
        return False


def cleanup(identity):
    if not alive(identity):
        return
    fd = os.pidfd_open(identity[0])
    try:
        if alive(identity):
            signal.pidfd_send_signal(fd,signal.SIGKILL)
    finally:
        os.close(fd)
    for _ in range(100):
        if not alive(identity):
            return
        time.sleep(.01)
    raise RuntimeError('fixture helper could not be cleaned')


def remove_test_files():
    # Only fixed, root-owned FAKE state files from this suite; never REAL state.
    for name in ['authorization.json','binding.json','scenario','invocation.json']:
        p=STATE/name
        if p.exists():
            if p.is_symlink() or not p.is_file() or p.stat().st_uid!=0:
                raise RuntimeError('unsafe fixture state')
            p.unlink()


def main():
    if os.geteuid()!=0 or len(sys.argv)!=1:
        raise RuntimeError('root required; no arguments')
    os.umask(0o077)
    # Trusted helpers are copied inline from the reviewed upgrade logic via a pinned script.
    upgrade=STAGE/'upgrade-wrapper.py'
    if sha(upgrade.read_bytes()) != UPGRADE_SHA:
        raise RuntimeError('upgrade helper source mismatch')
    trust=runpy.run_path(str(upgrade))['trust']
    for p in [Path(WRAPPER),Path('/opt'),Path('/var/lib/chaoslab-m1'),POLICY.parent,AUDIT.parent]:
        trust(p)
    if sha(Path(WRAPPER).read_bytes())!=WRAPPER_SHA or sha((STAGE/'m1-fake').read_bytes())!=FAKE_SHA:
        raise RuntimeError('binary mismatch')
    for p in [STATE,POLICY,TOOL.parent,AUDIT,STAGE/'fake-root-suite.json']:
        if os.path.lexists(p):
            raise RuntimeError('existing fixture state/report; refuse retry')
    if any(Path('/var/lib/chaoslab-m1/state').iterdir()):
        raise RuntimeError('REAL state not empty')
    # Existing approved idle inspector: all security fields + exact sleep argv.
    idle=STAGE/'start-idle.py'
    if sha(idle.read_bytes())!=IDLE_SHA:
        raise RuntimeError('idle checker source mismatch')
    idle_api=runpy.run_path(str(idle))
    v=idle_api['inspect']()
    if not v['State']['Running']:
        idle_api['docker']('start',v['Id'])
        v=idle_api['inspect']()
    identity=idle_api['sample'](v['State']['Pid'])
    for p in [Path('/opt/chaoslab'),Path('/opt/chaoslab/m1-fixture'),TOOL.parent,TOOL.parent/'bin',TOOL.parent/'yaml',STATE]:
        if not p.exists():
            trust(p.parent);p.mkdir(mode=0o700)
        trust(p)
    put(TOOL,(STAGE/'m1-fake').read_bytes(),0o700)
    small=b'fixture'
    for name in ['bin/nsexec','bin/chaos_os','yaml/chaosblade-cri-spec-1.8.1.yaml']:
        put(TOOL.parent/name,small,0o600 if name.endswith('.yaml') else 0o700)
    node='m1-executor';marker=POLICY.parent/'node-id'
    if marker.exists():
        trust(marker)
        if marker.read_text()!=node+'\n': raise RuntimeError('node marker mismatch')
    else: put(marker,(node+'\n').encode())
    put(STATE/'.chaoslab-state-id',b'm1-fixture-state\n')
    # Field order exactly matches complete Go Policy JSON canonical encoding.
    p=dict(deployment='FAKE',executable=str(TOOL),stateDirectory=str(STATE),containerId=v['Id'],
           imageId=v['Image'],containerName='chaoslab-cpu-sandbox',user='65534:65534',nanoCpus=500000000,
           memory=134217728,pids=32,cpuPercent=10,durationSeconds=10,nodeId=node,stateId='m1-fixture-state',
           toolSha256=FAKE_SHA,nsexecSha256=sha(small),chaosOsSha256=sha(small),yamlSha256=sha(small))
    put(POLICY,encode(p))
    r={'passed':False,'tests':[],'realAuthorizationCreated':False,'realBladeInvoked':False}
    known=None
    try:
        if call('preflight')['code']!='OK':raise RuntimeError('fake preflight failed')
        for _ in range(3):
            if call()['code']!='CREATE_DISABLED':raise RuntimeError('missing auth not denied')
        r['withoutAuthorization']='CREATE_DISABLED x3'
        # Never create a REAL-capable authorization. Every negative object retains FAKE path/SHA.
        for field,value in [('deployment','REAL'),('nativeUid','fedcba9876543210'),
                            ('executionId','33333333-3333-4333-8333-333333333333'),
                            ('policyDigest','0'*64),('toolSha256','0'*64),
                            ('expiresAt','2000-01-01T00:00:00Z')]:
            a=authorization(p);a[field]=value;put(STATE/'authorization.json',encode(a))
            result=call()
            if result['code']!='CREATE_DISABLED':raise RuntimeError('invalid authorization accepted')
            (STATE/'authorization.json').unlink()
            r['tests'].append({'negativeAuthorization':field,'code':result['code']})
        for op,mode,expected in [('create-cpu','handoff','HANDOFF'),('create-cpu','detached','HANDOFF'),
                                ('create-cpu','timeout','TIMEOUT'),('create-cpu','nonzero','NONZERO_EXIT'),
                                ('create-cpu','malformed','INVALID_RESPONSE'),('create-cpu','overflow','OUTPUT_LIMIT'),
                                ('status','normal','EXITED'),('destroy','normal','EXITED'),
                                ('status','handoff','DESCENDANTS_REMAINED'),('destroy','handoff','DESCENDANTS_REMAINED')]:
            current=idle_api['inspect']()
            sample=idle_api['sample'](current['State']['Pid'])
            if any(sample[k]!=identity[k] for k in ['pid','startTime','cgroup']):raise RuntimeError('sandbox changed')
            remove_test_files();put(STATE/'scenario',mode.encode())
            put(STATE/('authorization.json' if op=='create-cpu' else 'binding.json'),encode(authorization(p)))
            result=call(op);known=helper()
            if result.get('outcome')!=expected:raise RuntimeError('unexpected outcome: '+str(result))
            if expected=='HANDOFF':
                if result['cleanupComplete'] or not result['handoff'] or not alive(known):raise RuntimeError('invalid handoff')
                if (STATE/'authorization.json').exists():raise RuntimeError('authorization unconsumed')
                if call()['code']!='CREATE_DISABLED':raise RuntimeError('replay accepted')
            elif not result['cleanupComplete'] or alive(known):raise RuntimeError('known child cleanup incomplete')
            r['tests'].append({'operation':op,'mode':mode,'result':result,'rootChild':True})
            cleanup(known);known=None
        for victim,sig in [('wrapper',signal.SIGTERM),('sudo',signal.SIGTERM),('wrapper',signal.SIGKILL)]:
            remove_test_files();put(STATE/'scenario',b'signal')
            put(STATE/'authorization.json',encode(authorization(p)))
            process=subprocess.Popen(CHAIN,stdin=subprocess.PIPE,stdout=subprocess.PIPE,stderr=subprocess.PIPE,env=ENV)
            process.stdin.write(encode(Q));process.stdin.close();process.stdin=None
            end=time.monotonic()+4
            while not (STATE/'invocation.json').exists() and time.monotonic()<end:time.sleep(.02)
            info=json.loads((STATE/'invocation.json').read_bytes());known=helper()
            fields=Path('/proc',str(info['pid']),'stat').read_text().rsplit(') ',1)[1].split()
            cli=(info['pid'],fields[19]);parent=int(fields[1])
            selected=None
            for _ in range(8):
                raw=Path('/proc',str(parent),'stat').read_text().rsplit(') ',1)[1].split()
                exe=os.readlink('/proc/'+str(parent)+'/exe')
                if (victim=='wrapper' and exe==WRAPPER) or (victim=='sudo' and exe=='/usr/bin/sudo'):
                    selected=(parent,raw[19]);break
                parent=int(raw[1])
            if selected is None:raise RuntimeError('signal target ancestry unknown')
            fd=os.pidfd_open(selected[0])
            try:
                if not alive(selected):raise RuntimeError('signal target changed')
                signal.pidfd_send_signal(fd,sig)
            finally:os.close(fd)
            try:
                out,err=process.communicate(timeout=9)
                try:result=json.loads(out)
                except (ValueError,UnicodeError):result=None
                if victim=='wrapper' and sig==signal.SIGTERM:
                    if result is None or result.get('outcome')!='INTERRUPTED' or not result['cleanupComplete'] or alive(known):
                        raise RuntimeError('wrapper SIGTERM cleanup failed')
                elif result is not None:
                    if result.get('code')=='OK' or (result.get('cleanupComplete') and alive(known)):
                        raise RuntimeError('false cleanup/success after termination')
                r['tests'].append({'terminationTarget':victim,'signal':int(sig),'result':result,
                                   'noResultMeaning':'UNKNOWN, never cleanup success',
                                   'helperAliveBeforeHarnessCleanup':alive(known)})
            finally:
                cleanup(cli);cleanup(known);known=None
        r['coreLifecyclePassed']=True
        r['remaining']=['cross-slot production-boundary integration pending; unit tests passed']
    except Exception as e:
        r['blocker']=str(e) if isinstance(e,RuntimeError) else type(e).__name__
    finally:
        try:
            if known is None and (STATE/'invocation.json').exists():known=helper()
            cleanup(known)
            remove_test_files()
            r['authorizationAbsent']=not (STATE/'authorization.json').exists()
        except Exception as e:r['cleanupBlocker']=type(e).__name__
        data=json.dumps(r,indent=2).encode()
        put(AUDIT,data);put(STAGE/'fake-root-suite.json',data,0o644)
    print('CORE FAKE SUITE RECORDED; full A gate still pending termination checks')
    print('REAL EXECUTION NOT AUTHORIZED')


UPGRADE_SHA = 'b7886588b3ab63ac5c1f2debd16fdc2531084f9442c5e9da7a46f0468d16e4ab'
IDLE_SHA = 'e498834001c352908d8884d1eb798b9c1c7e40fb01e006fc3a170e5a3e8d3dd4'
if __name__=='__main__':main()
