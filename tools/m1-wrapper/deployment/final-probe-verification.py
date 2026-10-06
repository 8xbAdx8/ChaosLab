"""Install tested probe wrapper, regress FAKE root chain, restore REAL read-only.
No REAL authorization or real create is ever permitted by this procedure.
"""
import hashlib
import json
import os
from pathlib import Path
import runpy
import sqlite3
import stat
import subprocess
import sys

STAGE=Path('/home/w/chaoslab-m1-2d3-20261004')
POLICY=Path('/etc/chaoslab-m1/policy.json')
STATE=Path('/var/lib/chaoslab-m1/state')
FAKE=Path('/var/lib/chaoslab-m1/fixture-state')
WRAPPER=Path('/usr/local/libexec/chaoslab-m1-wrapper')
BACKUP=Path('/var/backups/chaoslab-m1-2d3-20261004')
OLD='7b4ccbbc85c21bff728c9e7c065353b1e0826da3a0991c2f993b5cb9bbb91f13'
NEW='2e27c2f2b15fb011c1562f0a2fc19640e7b5e0f3fef1d9c166bf54969c654b94'
SUITE_SHA='57b720496c9dfd24e71abfe8d260b808e4af7755253fadbc2e0ebbb659c7b985'
UPGRADE_SHA='b7886588b3ab63ac5c1f2debd16fdc2531084f9442c5e9da7a46f0468d16e4ab'
IDLE_SHA='e498834001c352908d8884d1eb798b9c1c7e40fb01e006fc3a170e5a3e8d3dd4'
REAL_DIGEST='9645e6c589812c933cd24d4ce648992940154677bb2504171a28a594b21c27ca'
EMPTY_NATIVE_SHA='6cf2cb3949e05ae8b4c61999e2970c0338f49b82b209872d135f5c02658c7433'


def sha(b):return hashlib.sha256(b).hexdigest()
def encode(v):return json.dumps(v,separators=(',',':')).encode()


def no_live_fixture():
 for p in Path('/proc').iterdir():
  if not p.name.isdigit():continue
  try:
   if os.readlink(p/'exe') in [str(WRAPPER),'/opt/chaoslab/m1-fixture/v1/fake-blade',
       '/opt/chaoslab/m1/api3-identified/blade-chaoslab-api3-identified',
       '/opt/chaoslab/m1/api3-identified/bin/nsexec','/opt/chaoslab/m1/api3-identified/bin/chaos_os']:
    raise RuntimeError('live wrapper/fixture present')
  except FileNotFoundError:pass


def main():
 if os.geteuid()!=0 or len(sys.argv)!=1:raise RuntimeError('administrator required; no arguments')
 os.umask(0o077)
 for path,pin in [(STAGE/'upgrade-wrapper.py',UPGRADE_SHA),(STAGE/'start-idle.py',IDLE_SHA),
                  (STAGE/'fake-root-suite-v4.py',SUITE_SHA),(STAGE/'chaoslab-m1-wrapper-v4',NEW),(WRAPPER,OLD)]:
  if path.is_symlink() or sha(path.read_bytes())!=pin:raise RuntimeError('reviewed source/binary mismatch')
 upgrade=runpy.run_path(str(STAGE/'upgrade-wrapper.py'))
 trust,write,env=upgrade['trust'],upgrade['write_new'],upgrade['ENV']
 for path in [WRAPPER,POLICY,STATE,FAKE,BACKUP,Path('/var/lib/chaoslab-m1/audit')]:trust(path)
 for root in [STATE,FAKE]:
  for name in ['authorization.json','binding.json','operation.lock','invocation.json','scenario']:
   if os.path.lexists(root/name):raise RuntimeError('unexpected live state; STOP')
 for suffix in ['-wal', '-shm', '-journal']:
  if os.path.lexists(STATE/('chaosblade.dat'+suffix)):raise RuntimeError('unexpected REAL native state')
 no_live_fixture()
 real_bytes=POLICY.read_bytes();real=json.loads(real_bytes)
 if real.get('deployment')!='REAL' or sha(encode(real))!=REAL_DIGEST:raise RuntimeError('REAL policy changed')
 old_fake=BACKUP/'fake-policy-before-real.json';trust(old_fake)
 fake_bytes=old_fake.read_bytes();fake=json.loads(fake_bytes)
 if fake.get('deployment')!='FAKE' or fake.get('executable')!='/opt/chaoslab/m1-fixture/v1/fake-blade' or fake.get('stateDirectory')!=str(FAKE):
  raise RuntimeError('untrusted FAKE policy')
 rules=[Path('/etc/sudoers'),*sorted(Path('/etc/sudoers.d').iterdir())]
 sudo_before={str(p):sha(p.read_bytes()) for p in rules}
 # Root-only diagnosis proved eager version/help initialization, not an experiment.
 # Preserve that exact empty DB recoverably; never adopt/delete any native records.
 native=STATE/'chaosblade.dat';archive=BACKUP/'real-empty-native-before-probes.dat'
 archived_native=None
 if os.path.lexists(native):
  trust(native);st=native.stat()
  if not stat.S_ISREG(st.st_mode) or st.st_size!=49152 or sha(native.read_bytes())!=EMPTY_NATIVE_SHA:
   raise RuntimeError('native DB differs from diagnosed empty store; STOP')
  conn=sqlite3.connect(native.as_uri()+'?mode=ro&immutable=1',uri=True)
  try:
   conn.execute('PRAGMA query_only=ON')
   tables=conn.execute("SELECT name FROM sqlite_master WHERE type='table' ORDER BY name").fetchall()
   if tables!=[('experiment',),('preparation',),('sqlite_sequence',)]:raise RuntimeError('unexpected native schema')
   if conn.execute('PRAGMA integrity_check').fetchall()!=[('ok',)]:raise RuntimeError('native integrity failed')
   for name in ['experiment','preparation','sqlite_sequence']:
    if conn.execute('SELECT count(*) FROM "'+name+'"').fetchone()[0]!=0:raise RuntimeError('native records exist; STOP')
  finally:conn.close()
  no_live_fixture()
  if sha(native.read_bytes())!=EMPTY_NATIVE_SHA or native.stat().st_ino!=st.st_ino or os.path.lexists(archive):
   raise RuntimeError('native state changed/archive exists; STOP')
  os.rename(native,archive)
  for parent in [STATE,BACKUP]:
   fd=os.open(parent,os.O_RDONLY|os.O_DIRECTORY)
   try:os.fsync(fd)
   finally:os.close(fd)
  trust(archive);archived_native={'path':str(archive),'sha256':EMPTY_NATIVE_SHA,'tableCounts':{'experiment':0,'preparation':0,'sqlite_sequence':0}}
 write(BACKUP/'wrapper-before-probes',WRAPPER.read_bytes(),0o700)
 temp=WRAPPER.with_name('chaoslab-m1-wrapper.probes-new')
 write(temp,(STAGE/'chaoslab-m1-wrapper-v4').read_bytes(),0o755);trust(temp)
 os.replace(temp,WRAPPER)
 fd=os.open(WRAPPER.parent,os.O_RDONLY|os.O_DIRECTORY)
 try:os.fsync(fd)
 finally:os.close(fd)
 trust(WRAPPER)
 if sha(WRAPPER.read_bytes())!=NEW or sudo_before!={str(p):sha(p.read_bytes()) for p in rules}:
  raise RuntimeError('wrapper digest/sudo changed')
 def replace_policy(data):
  temp=POLICY.with_name('policy.probes-new.json');write(temp,data,0o600);trust(temp);os.replace(temp,POLICY)
  fd=os.open(POLICY.parent,os.O_RDONLY|os.O_DIRECTORY)
  try:os.fsync(fd)
  finally:os.close(fd)
 r={'passed':False,'realAuthorizationCreated':False,'realCreateInvoked':False,'wrapperSha256':NEW,'sudoUnchanged':True}
 if archived_native is not None:r['emptyNativeStoreArchived']=archived_native
 try:
  idle=runpy.run_path(str(STAGE/'start-idle.py'))
  v=idle['inspect']()
  if not v['State']['Running']:idle['docker']('start',v['Id']);v=idle['inspect']()
  if not v['State']['Running']:raise RuntimeError('sandbox not running')
  identity=idle['sample'](v['State']['Pid'])
  replace_policy(fake_bytes)
  try:
   suite=runpy.run_path(str(STAGE/'fake-root-suite-v4.py'))
   suite['run_suite'](fake,idle,identity,'fake-root-suite-probes.json')
   result=json.loads(Path('/var/lib/chaoslab-m1/audit/fake-root-suite-probes.json').read_bytes())
   if result.get('coreLifecyclePassed') is not True or result.get('cleanupBlocker') or result.get('blocker'):
    raise RuntimeError('probe wrapper FAKE regression failed')
   r['fakeRootLifecycleAndProbes']='PASS'
  finally:replace_policy(real_bytes)
  no_live_fixture()
  lock=FAKE/'operation.lock'
  if lock.exists():
   trust(lock)
   if lock.stat().st_size!=0:raise RuntimeError('unexpected FAKE lock data')
   archive=BACKUP/'fake-probes-stale-lock-after-sigkill'
   if os.path.lexists(archive):raise RuntimeError('lock archive exists')
   os.rename(lock,archive)
   r['fakeStaleLockArchived']=str(archive)
  chain=['/usr/sbin/runuser','-u','chaoslab','--','/usr/bin/sudo','-n','--',str(WRAPPER)]
  def call(q):
   proc=subprocess.run(chain,input=encode(q),env=env,capture_output=True,timeout=10)
   if proc.stderr or len(proc.stdout)>65536:raise RuntimeError('privileged transport unknown')
   return json.loads(proc.stdout)
  preflight=call({'operation':'preflight'})
  if preflight.get('code')!='OK' or preflight.get('policy')!=real or preflight.get('policyDigest')!=REAL_DIGEST:
   raise RuntimeError('REAL preflight attestation rejected')
  o=preflight.get('observation',{})
  if o.get('probeReady') is not True or o.get('residual')!='CLEAR' or o.get('health')!='UNKNOWN' or o.get('cpuPercent',999)>1:
   raise RuntimeError('REAL read-only probe readiness not clear/idle')
  r['finalPreflight']=preflight
  r['realNoAuthorizationCreate']=[]
  for _ in range(3):
   out=call({'operation':'create-cpu','executionId':'44444444-4444-4444-8444-444444444444','nativeUid':'fedcba9876543210'})
   if out.get('code')!='CREATE_DISABLED':raise RuntimeError('REAL create not disabled')
   r['realNoAuthorizationCreate'].append(out)
  r['negativeSudo']={}
  prefix=['/usr/sbin/runuser','-u','chaoslab','--','/usr/bin/sudo','-n','-l','--']
  for exe in ['/bin/sh','/bin/bash','/usr/bin/docker','/usr/bin/env','/usr/bin/python3','/usr/bin/systemctl','/usr/bin/systemd-run',real['executable']]:
   denied=subprocess.run(prefix+[exe],env=env,capture_output=True,timeout=5).returncode!=0
   r['negativeSudo'][exe]=denied
   if not denied:raise RuntimeError('unexpected sudo permission')
  if subprocess.run(prefix+[str(WRAPPER),'FAKE'],env=env,capture_output=True,timeout=5).returncode==0:
   raise RuntimeError('wrapper argv allowed')
  status=dict(line.split(':',1) for line in Path('/proc/self/status').read_text().splitlines() if ':' in line)
  effective=int(status['CapEff'].strip(),16)
  required=[1,5,19,21,24]
  if any(not effective&(1<<cap) for cap in required):raise RuntimeError('root namespace/cgroup capabilities missing')
  if os.statvfs('/sys/fs/cgroup').f_flag&os.ST_RDONLY:raise RuntimeError('host cgroup mount read-only')
  r['rootRequiredCapabilitiesPresent']=True;r['hostCgroupWritableMount']=True
  for root in [STATE,FAKE]:
   if os.path.lexists(root/'authorization.json'):raise RuntimeError('authorization remains')
  current=idle['inspect']();last=idle['sample'](current['State']['Pid'])
  if any(last[k]!=identity[k] for k in ['pid','startTime','cgroup']):raise RuntimeError('sandbox runtime identity changed')
  r['finalSandboxSample']=last;r['authorizationAbsent']=True;r['passed']=True
  r['healthMeaning']='same running sandbox and CPU within persisted baseline + 1 percentage point; no application healthcheck'
 except Exception as e:r['blocker']=str(e) if isinstance(e,RuntimeError) else type(e).__name__
 finally:
  if POLICY.read_bytes()!=real_bytes:replace_policy(real_bytes)
  for root in [STATE,FAKE]:
   if os.path.lexists(root/'authorization.json'):r['authorizationCleanupBlocker']=True
  data=json.dumps(r,indent=2).encode()
  write(Path('/var/lib/chaoslab-m1/audit/final-probe-verification.json'),data,0o600)
  write(STAGE/'final-probe-verification.json',data,0o644)
 print('FINAL HARMLESS ROOT VERIFICATION:', 'PASS' if r['passed'] else 'STOP; inspect report')
 print('REAL EXECUTION NOT AUTHORIZED')


if __name__=='__main__':main()
