"""Finish FAKE isolation acceptance, install pinned REAL resources, read-only checks.
Never creates REAL authorization, dispatches a fault, or changes Docker configuration.
"""
import hashlib
import json
import os
from pathlib import Path
import runpy
import signal
import stat
import subprocess
import sys
import time

STAGE=Path('/home/w/chaoslab-m1-2d3-20261004')
SOURCE=Path('/home/w/chaosblade-api3.r3cL98')
DEST=Path('/opt/chaoslab/m1/api3-identified')
STATE=Path('/var/lib/chaoslab-m1/state')
FAKE_STATE=Path('/var/lib/chaoslab-m1/fixture-state')
POLICY=Path('/etc/chaoslab-m1/policy.json')
BACKUP=Path('/var/backups/chaoslab-m1-2d3-20261004')
WRAPPER=Path('/usr/local/libexec/chaoslab-m1-wrapper')
WRAPPER_SHA='7b4ccbbc85c21bff728c9e7c065353b1e0826da3a0991c2f993b5cb9bbb91f13'
UPGRADE_SHA='b7886588b3ab63ac5c1f2debd16fdc2531084f9442c5e9da7a46f0468d16e4ab'
IDLE_SHA='e498834001c352908d8884d1eb798b9c1c7e40fb01e006fc3a170e5a3e8d3dd4'
SUITE_SHA='5e08672a206ef6439e7a835b2a5884dd6ff67d8cec9c2f74d579ef57c50cddb8'
PINS={
 'blade-chaoslab-api3-identified':'c0c987bbd0aa9d158e90ab48f737680fe1b7eefdbf6c9e74744444c49c847e96',
 'bin/nsexec':'693219257100421d3c2321c2b1bfb3d285d1b6eed1fa82feabd033797a51e793',
 'bin/chaos_os':'dee72446e32411f6a0d7a29c71b0ba0cc4dabbab1fcaba212c6c87d0ea965cfb',
 'yaml/chaosblade-cri-spec-1.8.1.yaml':'f8deebf2b90c44f414745cc6316e0cef545332173a7ef9a1998b9804531e7880',
 'bin/tc':'281f637609dcc6575afcb979f20ddeb2d443378e14972358994ec59242d35ab5',
 'bin/chaos_fuse':'c4709c158e2d547359607b4bbdd3becc3824d53d4182a80dba4fa933d4b14685',
 'bin/chaos_cloud':'3dce37e0b29360821399615d4d557b86c2b947a3dcc851f510ba2e3cb5b29aec',
 'bin/chaos_middleware':'02f25bbc612884b94c98d3b7a8b3103c3aa9b11fbe5c6d11c993f91621d1a3e8',
 'bin/strace':'235f42a684175641f72032b5b77a3148f02423e91444cfed8847e2c28eb63880',
 'yaml/chaosblade-middleware-spec-1.8.1.yaml':'a0d04d8b17fe631ea8fd06c4c062e854c9add8893e417f1c447989c6156aa3db',
 'yaml/chaosblade-os-spec-1.8.1.yaml':'63238f42a838e5d4f1925bc7c3ee2f2c875370d7532addd460ec3f6d5a054fbb',
 'yaml/chaosblade-k8s-spec-1.8.1.yaml':'a958616cec165fc6632c7024563983fd5242b31f2c0d3b8ee0603667d4cc1304',
 'yaml/chaosblade-jvm-spec-1.8.1.yaml':'e4188bec5cf4d1f00b62a5c7a628119ca9cb9b7bc3e95be746cc5d64aff4bf05',
 'yaml/chaosblade-cloud-spec-1.8.1.yaml':'67eb6bf86f189b48f53349c12739d79f6f2e73e317960ebcdfaddb35963ab9ea',
 'yaml/chaosblade-check-spec-1.8.1.yaml':'5fbc2b8d1daf1b8919e20f6b74191f02a0a2df69e9c2ff101301d26ff07dfc78',
}


def sha(b):return hashlib.sha256(b).hexdigest()
def encode(v):return json.dumps(v,separators=(',',':')).encode()


def main():
 if os.geteuid()!=0 or len(sys.argv)!=1:raise RuntimeError('administrator required; no arguments')
 os.umask(0o077)
 for path,pin in [(STAGE/'upgrade-wrapper.py',UPGRADE_SHA),(STAGE/'start-idle.py',IDLE_SHA),
                  (STAGE/'fake-root-suite-v3.py',SUITE_SHA),(WRAPPER,WRAPPER_SHA)]:
  if path.is_symlink() or sha(path.read_bytes())!=pin:raise RuntimeError('reviewed source/binary mismatch')
 upgrade=runpy.run_path(str(STAGE/'upgrade-wrapper.py'))
 trust,write,env=upgrade['trust'],upgrade['write_new'],upgrade['ENV']
 for path in [WRAPPER,POLICY,STATE,FAKE_STATE,BACKUP,Path('/opt/chaoslab')]:trust(path)
 root_report=Path('/var/lib/chaoslab-m1/audit/fake-root-suite-resumed.json');trust(root_report)
 prior=json.loads(root_report.read_bytes())
 if prior.get('coreLifecyclePassed') is not True or prior.get('authorizationAbsent') is not True or prior.get('blocker') or prior.get('cleanupBlocker'):
  raise RuntimeError('FAKE root lifecycle gate not passed')
 for root in [STATE,FAKE_STATE]:
  for name in ['authorization.json','binding.json','scenario','invocation.json']:
   if os.path.lexists(root/name):raise RuntimeError('unexpected live state; STOP')
 if any(STATE.iterdir()) or os.path.lexists(DEST):raise RuntimeError('REAL state/deployment already exists; refuse overwrite')
 # Exact observed FAKE processes must all be gone before archiving the stale
 # FAKE lock left by the deliberately injected wrapper SIGKILL test.
 for entry in Path('/proc').iterdir():
  if not entry.name.isdigit():continue
  try:
   executable=os.readlink(entry/'exe')
   if executable in [str(WRAPPER),'/opt/chaoslab/m1-fixture/v1/fake-blade']:
    raise RuntimeError('active wrapper/FAKE process; STOP')
  except FileNotFoundError:pass
 lock=FAKE_STATE/'operation.lock'
 if lock.exists():
  trust(lock)
  if lock.stat().st_size!=0:raise RuntimeError('unexpected FAKE lock data')
  archived=BACKUP/'fake-stale-lock-after-sigkill'
  if os.path.lexists(archived):raise RuntimeError('lock archive exists')
  os.rename(lock,archived)
 old=POLICY.read_bytes();fake=json.loads(old)
 if fake.get('deployment')!='FAKE' or fake.get('executable')!='/opt/chaoslab/m1-fixture/v1/fake-blade' or fake.get('stateDirectory')!=str(FAKE_STATE):
  raise RuntimeError('unexpected policy; STOP')
 write(BACKUP/'fake-policy-before-real.json',old,0o600)
 suite=runpy.run_path(str(STAGE/'fake-root-suite-v3.py'))
 idle=runpy.run_path(str(STAGE/'start-idle.py'))
 v=idle['inspect']()
 if not v['State']['Running']:idle['docker']('start',v['Id']);v=idle['inspect']()
 if not v['State']['Running']:raise RuntimeError('sandbox not running')
 chain=['/usr/sbin/runuser','-u','chaoslab','--','/usr/bin/sudo','-n','--',str(WRAPPER)]
 def call(q):
  proc=subprocess.run(chain,input=encode(q),capture_output=True,env=env,timeout=10)
  if proc.stderr or len(proc.stdout)>65536:raise RuntimeError('wrapper transport unknown')
  return json.loads(proc.stdout)
 def policy_replace(data):
  temp=POLICY.with_name('policy.readonly-new.json');write(temp,data,0o600);trust(temp)
  os.replace(temp,POLICY)
  fd=os.open(POLICY.parent,os.O_RDONLY|os.O_DIRECTORY)
  try:os.fsync(fd)
  finally:os.close(fd)
 r={'realAuthorizationCreated':False,'realCreateInvoked':False,'passed':False,'negativeTests':[]}
 try:
  for _ in range(3):
   if call(suite['Q'])['code']!='CREATE_DISABLED':raise RuntimeError('no-authorization create not disabled')
  # Every temporary authorization remains bound to the FAKE executable/hashes;
  # no REAL-capable object is created or transferred to REAL state.
  for label,changed in [('fakeAuthAgainstRealSlot',dict(fake,deployment='REAL')),
                        ('policyChanged',dict(fake,cpuPercent=11))]:
   auth=suite['authorization'](fake);write(FAKE_STATE/'authorization.json',encode(auth),0o600)
   try:
    policy_replace(encode(changed));out=call(suite['Q'])
    expected='IDENTITY_REJECTED' if label=='fakeAuthAgainstRealSlot' else 'CREATE_DISABLED'
    if out['code']!=expected:raise RuntimeError('cross-slot/policy change accepted')
    r['negativeTests'].append({'test':label,'code':out['code']})
   finally:
    policy_replace(old);(FAKE_STATE/'authorization.json').unlink()
  # REAL-tagged but FAKE-path identity cannot be used by either fixed slot.
  auth=suite['authorization'](fake);auth['deployment']='REAL'
  write(FAKE_STATE/'authorization.json',encode(auth),0o600)
  try:
   out=call(suite['Q'])
   if out['code']!='CREATE_DISABLED':raise RuntimeError('REAL tag accepted by FAKE')
   r['negativeTests'].append({'test':'realTagAgainstFake','code':out['code']})
  finally:(FAKE_STATE/'authorization.json').unlink()
  if call(suite['Q'])['code']!='CREATE_DISABLED':raise RuntimeError('FAKE authorization not absent')
  r['fakeRootAcceptance']='PASS; root handoff/failures + fixed-slot negative tests'
  # Copy all fixed reviewed distribution resources, NOT the old DB/logs/API2 CLI.
  data={}
  for rel,pin in PINS.items():
   source=SOURCE/rel
   for ancestor in [source,*source.parents]:
    if ancestor.is_symlink():raise RuntimeError('source symlink')
   fd=os.open(source,os.O_RDONLY|os.O_NOFOLLOW)
   with os.fdopen(fd,'rb') as f:
    st=os.fstat(f.fileno())
    if not stat.S_ISREG(st.st_mode) or not 0<st.st_size<=128*1024*1024:raise RuntimeError('source type/size')
    raw=f.read(128*1024*1024+1)
   if sha(raw)!=pin:raise RuntimeError('source resource hash mismatch')
   data[rel]=raw
  for path in [DEST.parent,DEST,DEST/'bin',DEST/'yaml']:
   if not path.exists():trust(path.parent);path.mkdir(mode=0o755)
   trust(path)
  r['toolFiles']={}
  for rel,raw in data.items():
   path=DEST/rel;mode=0o644 if rel.startswith('yaml/') else 0o755
   write(path,raw,mode);trust(path)
   if sha(path.read_bytes())!=PINS[rel]:raise RuntimeError('installed hash mismatch')
   r['toolFiles'][rel]={'sha256':PINS[rel],'owner':'root:root','mode':oct(mode)}
   for user in ['w','chaoslab']:
    if subprocess.run(['/usr/sbin/runuser','-u',user,'--','/usr/bin/test','-w',str(path)],env=env,timeout=5).returncode==0:
     raise RuntimeError('ordinary user can alter candidate')
  write(STATE/'.chaoslab-state-id',b'm1-real-state\n',0o600)
  real=dict(fake,deployment='REAL',executable=str(DEST/'blade-chaoslab-api3-identified'),
            stateDirectory=str(STATE),stateId='m1-real-state',toolSha256=PINS['blade-chaoslab-api3-identified'],
            nsexecSha256=PINS['bin/nsexec'],chaosOsSha256=PINS['bin/chaos_os'],
            yamlSha256=PINS['yaml/chaosblade-cri-spec-1.8.1.yaml'])
  policy_replace(encode(real));trust(POLICY)
  r['policy']=real;r['policyDigest']=sha(encode(real));r['wrapperSha256']=WRAPPER_SHA
  preflight=call({'operation':'preflight'})
  if preflight.get('code')!='OK' or preflight.get('policy')!=real or preflight.get('policyDigest')!=r['policyDigest']:
   raise RuntimeError('REAL readonly attestation rejected')
  r['realPreflight']=preflight
  # Fixed help/version only, never invoke any real experiment handler.
  controlled={'PATH':'/usr/bin:/bin','LANG':'C','LC_ALL':'C','HOME':str(STATE),'CHAOSBLADE_DATAFILE_PATH':str(STATE)}
  r['cliReadOnly']={}
  for label,args in [('version',['version']),('help',['--help'])]:
   proc=subprocess.Popen([real['executable'],*args],cwd=STATE,env=controlled,
                         stdout=subprocess.PIPE,stderr=subprocess.PIPE,start_new_session=True)
   try:out,err=proc.communicate(timeout=5)
   except subprocess.TimeoutExpired:
    os.killpg(proc.pid,signal.SIGKILL);proc.communicate(timeout=2)
    raise RuntimeError('readonly CLI timed out; STOP')
   if proc.returncode or len(out)+len(err)>65536:raise RuntimeError('readonly CLI failed')
   r['cliReadOnly'][label]={'exitCode':proc.returncode,'stdoutSha256':sha(out),'stdoutBytes':len(out),'stderrBytes':len(err)}
  samples=[]
  for i in range(5):
   current=idle['inspect']()
   if not current['State']['Running']:raise RuntimeError('sandbox stopped during baseline')
   s=idle['sample'](current['State']['Pid'])
   if samples and any(s[k]!=samples[0][k] for k in ['pid','startTime','cgroup']):raise RuntimeError('sandbox process identity changed')
   samples.append(s)
   if i<4:time.sleep(1)
  a,b=samples[0],samples[-1];elapsed=b['monotonicNs']-a['monotonicNs'];usage=b['usageUsec']-a['usageUsec']
  if elapsed<=0 or usage<0:raise RuntimeError('invalid baseline counter')
  r['baseline']={'oneCpuPercent':usage*100000.0/elapsed,'samples':samples}
  for root in [STATE,FAKE_STATE]:
   if os.path.lexists(root/'authorization.json'):raise RuntimeError('authorization unexpectedly present')
  r['authorizationAbsent']=True
  r['passed']=True
  r['status']='NOT REAL EXECUTION READY; probes/final gate still pending'
 except Exception as e:r['blocker']=str(e) if isinstance(e,RuntimeError) else type(e).__name__
 finally:
  payload=json.dumps(r,indent=2).encode()
  write(Path('/var/lib/chaoslab-m1/audit/real-readonly-deployment.json'),payload,0o600)
  write(STAGE/'real-readonly-deployment.json',payload,0o644)
 print('READONLY DEPLOYMENT:', 'PASS' if r['passed'] else 'STOP; inspect report')
 print('REAL EXECUTION NOT AUTHORIZED')


if __name__=='__main__':main()
