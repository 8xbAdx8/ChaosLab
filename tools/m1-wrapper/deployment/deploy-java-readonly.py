"""Approved Java21/MySQL/ordinary backend deployment; NEVER authorizes a real fault.
Fixed artifact and one-shot diagnostic main. No sudo/account/policy/tool changes.
"""
import errno
import hashlib
import io
import json
import os
from pathlib import Path, PurePosixPath
import pwd
import secrets
import signal
import stat
import subprocess
import sys
import time
import urllib.request
import zipfile

STAGE=Path('/home/w/chaoslab-m1-2d3-20261004')
AUDIT=Path('/var/lib/chaoslab-m1/audit')
STATE=Path('/var/lib/chaoslab-m1/state')
POLICY=Path('/etc/chaoslab-m1/policy.json')
WRAPPER=Path('/usr/local/libexec/chaoslab-m1-wrapper')
DEST=Path('/opt/chaoslab-backend/m1-698a905')
CONFIG=Path('/etc/chaoslab-backend-m1')
WORK=Path('/var/lib/chaoslab-backend-m1')
JAVA='/usr/lib/jvm/java-21-openjdk-amd64/bin/java'
APP_SHA='aa8bdfa116326ef6466648dc01eb804f64d04bba37c826e719767fae24dd23f1'
HARNESS_SHA='9a8ed0469c3f99aa1486040c8e7390f8425074afa205a858153c7380b878083e'
WRAPPER_SHA='2e27c2f2b15fb011c1562f0a2fc19640e7b5e0f3fef1d9c166bf54969c654b94'
POLICY_SHA='9645e6c589812c933cd24d4ce648992940154677bb2504171a28a594b21c27ca'
IDLE_SHA='e498834001c352908d8884d1eb798b9c1c7e40fb01e006fc3a170e5a3e8d3dd4'
ENV={'PATH':'/usr/sbin:/usr/bin:/sbin:/bin','HOME':'/root','LANG':'C','LC_ALL':'C','DEBIAN_FRONTEND':'noninteractive'}


def sha(raw):return hashlib.sha256(raw).hexdigest()
def trust(p):
 for x in [p,*p.parents]:
  s=x.lstat()
  if s.st_uid!=0 or s.st_gid!=0 or s.st_mode&0o022 or stat.S_ISLNK(s.st_mode):raise RuntimeError('untrusted root path')
  for key in ['system.posix_acl_access','system.posix_acl_default','security.capability']:
   try:
    if os.getxattr(x,key,follow_symlinks=False):raise RuntimeError('ACL/capability on trusted path')
   except OSError as e:
    if e.errno not in [errno.ENODATA,errno.ENOTSUP]:raise
def write(p,data,mode=0o600,gid=0,uid=0):
 fd=os.open(p,os.O_WRONLY|os.O_CREAT|os.O_EXCL|os.O_NOFOLLOW,mode)
 with os.fdopen(fd,'wb') as f:
  os.fchown(f.fileno(),uid,gid);os.fchmod(f.fileno(),mode);f.write(data);f.flush();os.fsync(f.fileno())
def command(args,data=None,timeout=30):
 p=subprocess.run(args,input=data,env=ENV,capture_output=True,timeout=timeout)
 if p.returncode:raise RuntimeError('command failed: '+Path(args[0]).name)
 return p.stdout
def mysql(sql):return command(['/usr/bin/mysql','--no-defaults','--protocol=socket','--socket=/var/run/mysqld/mysqld.sock','--batch','--skip-column-names'],sql.encode())
def absent():
 for root in [STATE,Path('/var/lib/chaoslab-m1/fixture-state')]:
  if os.path.lexists(root/'authorization.json'):raise RuntimeError('authorization exists; STOP')
 for n in ['binding.json','operation.lock','chaosblade.dat','chaosblade.dat-wal','chaosblade.dat-shm','chaosblade.dat-journal']:
  if os.path.lexists(STATE/n):raise RuntimeError('REAL experimental state exists; STOP')
def tool_processes(p):
 matches=[]
 fixed=[p['executable'],str(Path(p['executable']).parent/'bin/nsexec'),str(Path(p['executable']).parent/'bin/chaos_os'),'/opt/chaoslab/m1-fixture/v1/fake-blade']
 for item in Path('/proc').iterdir():
  if not item.name.isdigit():continue
  try:
   exe=os.readlink(item/'exe')
   if exe.removesuffix(' (deleted)') in fixed:matches.append(item.name)
  except FileNotFoundError:pass
 return matches


def main():
 if os.geteuid()!=0 or len(sys.argv)!=1:raise RuntimeError('administrator required; no arguments')
 os.umask(0o077)
 for p in [POLICY,WRAPPER,STATE,AUDIT,Path('/opt'),Path('/etc')]:trust(p)
 absent()
 account=pwd.getpwnam('chaoslab')
 if (account.pw_uid,account.pw_gid,account.pw_shell)!=(999,987,'/usr/sbin/nologin') or command(['/usr/bin/id','-G','chaoslab']).strip()!=b'987':raise RuntimeError('service identity changed')
 for p in [DEST,CONFIG,WORK,Path('/etc/mysql'),Path('/var/lib/mysql')]:
  if os.path.lexists(p):raise RuntimeError('deployment/package state exists; do not rerun initializer')
 policy_bytes=POLICY.read_bytes();policy=json.loads(policy_bytes)
 if policy.get('deployment')!='REAL' or sha(json.dumps(policy,separators=(',',':')).encode())!=POLICY_SHA:raise RuntimeError('REAL policy changed')
 if sha(WRAPPER.read_bytes())!=WRAPPER_SHA or tool_processes(policy):raise RuntimeError('wrapper changed/tool process exists')
 rules=[Path('/etc/sudoers'),*sorted(Path('/etc/sudoers.d').iterdir())]
 sudo_before={str(p):sha(p.read_bytes()) for p in rules}
 for name,pin in [('chaoslab-backend-698a905.jar',APP_SHA),('m1-readonly-acceptance.jar',HARNESS_SHA),('start-idle.py',IDLE_SHA)]:
  p=STAGE/name
  if p.is_symlink() or sha(p.read_bytes())!=pin:raise RuntimeError('reviewed artifact mismatch')
 app=(STAGE/'chaoslab-backend-698a905.jar').read_bytes();harness=(STAGE/'m1-readonly-acceptance.jar').read_bytes()
 import runpy
 idle=runpy.run_path(str(STAGE/'start-idle.py'))
 report={'passed':False,'commit':'698a905322104063ae130f810999723b72d9aca2','realAuthorizationCreated':False,'realCreateInvoked':False,'backendArtifactSha256':APP_SHA,'acceptanceHarnessSha256':HARNESS_SHA}
 process=None
 try:
  print('Installing signed Ubuntu Java 21 and local MySQL packages; no full-system upgrade.',flush=True)
  # Create the bind-only fragment before the package can start its daemon.
  for d in [Path('/etc/mysql'),Path('/etc/mysql/mysql.conf.d')]:d.mkdir(mode=0o755);os.chmod(d,0o755);trust(d)
  write(Path('/etc/mysql/mysql.conf.d/zz-chaoslab-m1.cnf'),b'[mysqld]\nbind-address=127.0.0.1\nmysqlx-bind-address=127.0.0.1\nskip-name-resolve\nlocal-infile=0\n',0o644)
  aptlog=AUDIT/'java-mysql-install.log'
  with aptlog.open('xb') as log:
   for args in [['/usr/bin/apt-get','update'],['/usr/bin/apt-get','install','-y','--no-install-recommends','openjdk-21-jre-headless','mysql-server']]:
    p=subprocess.run(args,env=ENV,stdout=log,stderr=subprocess.STDOUT,timeout=900)
    if p.returncode:raise RuntimeError('APT installation failed; inspect root-only install log')
  version=subprocess.run([JAVA,'-version'],env=ENV,capture_output=True,timeout=10)
  if version.returncode or b'version "21.' not in version.stderr:raise RuntimeError('Java is not 21')
  report['javaVersion']=version.stderr.decode().splitlines()[0]
  command(['/usr/bin/systemctl','restart','mysql'],timeout=60)
  if mysql("SELECT @@bind_address, @@mysqlx_bind_address;").strip()!=b'127.0.0.1\t127.0.0.1':raise RuntimeError('MySQL not loopback-only')
  if mysql("SELECT @@datadir, @@port;").strip()!=b'/var/lib/mysql/\t3306':raise RuntimeError('unexpected MySQL deployment')
  schemas=set(mysql('SHOW DATABASES;').decode().splitlines())
  if schemas!={'information_schema','mysql','performance_schema','sys'}:raise RuntimeError('unexpected non-system database; STOP')
  if mysql("SELECT count(*) FROM mysql.user WHERE user='chaoslab_m1';").strip()!=b'0':raise RuntimeError('M1 DB account exists')
  password=secrets.token_hex(32)
  mysql("CREATE DATABASE chaoslab_m1 CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;\nCREATE USER 'chaoslab_m1'@'127.0.0.1' IDENTIFIED BY '"+password+"';\nGRANT SELECT,INSERT,UPDATE,DELETE,CREATE,ALTER,INDEX,REFERENCES,DROP ON chaoslab_m1.* TO 'chaoslab_m1'@'127.0.0.1';")
  report['databaseScope']='MySQL; 127.0.0.1:3306/chaoslab_m1 only; no global privileges'
  print('Installing pinned backend and restricted configuration.',flush=True)
  for d in [DEST.parent,DEST]:d.mkdir(mode=0o755);os.chmod(d,0o755);trust(d)
  write(DEST/'backend.jar',app,0o644);write(DEST/'acceptance.jar',harness,0o644)
  with zipfile.ZipFile(io.BytesIO(app)) as z:
   if sum(i.file_size for i in z.infolist())>256*1024*1024:raise RuntimeError('artifact oversized')
   for item in z.infolist():
    name=PurePosixPath(item.filename)
    if name.is_absolute() or '..' in name.parts or '\\' in item.filename or stat.S_ISLNK(item.external_attr>>16):raise RuntimeError('unsafe artifact entry')
    if not item.filename.startswith(('BOOT-INF/classes/','BOOT-INF/lib/')):continue
    target=DEST/'app'/item.filename
    if item.is_dir():target.mkdir(mode=0o755,parents=True,exist_ok=True)
    else:target.parent.mkdir(mode=0o755,parents=True,exist_ok=True);write(target,z.read(item),0o644)
  for d in [DEST/'app',*(p for p in (DEST/'app').rglob('*') if p.is_dir())]:
   os.chmod(d,0o755);trust(d)
  CONFIG.mkdir(mode=0o750);os.chown(CONFIG,0,987);os.chmod(CONFIG,0o750)
  WORK.mkdir(mode=0o700);os.chown(WORK,999,987)
  values={
   'spring.datasource.url':'jdbc:mysql://127.0.0.1:3306/chaoslab_m1?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC',
   'spring.datasource.username':'chaoslab_m1','spring.datasource.password':password,
   'server.address':'127.0.0.1','server.port':'18080','chaoslab.engine':'blade','chaoslab.execution.max-active-executions':'1',
   'chaoslab.blade.executable':policy['executable'],'chaoslab.blade.state-directory':policy['stateDirectory'],
   'chaoslab.blade.node-marker':'/etc/chaoslab-m1/node-id','chaoslab.blade.node-id':policy['nodeId'],'chaoslab.blade.state-id':policy['stateId'],
   'chaoslab.blade.tool-version':'api3-identified','chaoslab.blade.cli-sha256':policy['toolSha256'],
   'chaoslab.blade.nsexec-sha256':policy['nsexecSha256'],'chaoslab.blade.chaos-os-sha256':policy['chaosOsSha256'],
   'chaoslab.blade.cri-yaml-sha256':policy['yamlSha256']}
  write(CONFIG/'application.properties','\n'.join(k+'='+v for k,v in values.items()).encode()+b'\n',0o640,987)
  # Boot's original application.yml DB placeholders also require these values.
  child_env={'PATH':'/usr/bin:/bin','HOME':'/nonexistent','LANG':'C','LC_ALL':'C',
   'CHAOSLAB_DB_URL':values['spring.datasource.url'],'CHAOSLAB_DB_USERNAME':'chaoslab_m1','CHAOSLAB_DB_PASSWORD':password}
  v=idle['inspect']()
  if not v['State']['Running']:idle['docker']('start',v['Id']);v=idle['inspect']()
  if not v['State']['Running']:raise RuntimeError('sandbox not running')
  runtime=idle['sample'](v['State']['Pid'])
  cp=str(DEST/'acceptance.jar')+':'+str(DEST/'app/BOOT-INF/classes')+':'+str(DEST/'app/BOOT-INF/lib/*')
  logpath=WORK/'backend.log'
  write(logpath,b'',0o600,987,999)
  args=['/usr/sbin/runuser','-u','chaoslab','--',JAVA,'-Xms64m','-Xmx512m','-cp',cp,
   'com.chaoslab.engine.infrastructure.blade.M1ReadOnlyAcceptance','--spring.config.additional-location=file:'+str(CONFIG/'application.properties')]
  print('Starting ordinary chaoslab Spring backend; read-only actual bean acceptance.',flush=True)
  with logpath.open('ab') as log:process=subprocess.Popen(args,env=child_env,cwd=WORK,stdout=log,stderr=subprocess.STDOUT,start_new_session=True)
  deadline=time.monotonic()+150
  root_ancestries=[]
  while time.monotonic()<deadline and not (WORK/'java-readonly-result.json').exists():
   if process.poll() is not None:raise RuntimeError('backend stopped; inspect local backend log')
   for item in Path('/proc').iterdir():
    if not item.name.isdigit():continue
    try:
     if os.readlink(item/'exe')==str(WRAPPER) and item.stat().st_uid==0:
      ancestry=[];ancestor=int(item.name)
      for _ in range(16):
       ancestry.append(ancestor)
       fields=Path('/proc',str(ancestor),'stat').read_text().rsplit(') ',1)[1].split()
       ancestor=int(fields[1])
       if ancestor<=1:break
      root_ancestries.append(ancestry)
    except FileNotFoundError:pass
   time.sleep(.1)
  resultfile=WORK/'java-readonly-result.json'
  if not resultfile.exists() or resultfile.stat().st_size>16384:raise RuntimeError('Java acceptance report missing/oversized')
  java_result=json.loads(resultfile.read_bytes())
  if java_result.get('passed') is not True:raise RuntimeError('Java acceptance failed; inspect local report/log')
  jpid=int(java_result['pid']);status=Path('/proc',str(jpid),'status').read_text()
  if '\nUid:\t999\t999\t999\t999\n' not in status or '\nGid:\t987\t987\t987\t987\n' not in status:raise RuntimeError('backend UID/GID mismatch')
  if os.readlink('/proc/'+str(jpid)+'/exe')!=JAVA:raise RuntimeError('backend Java path mismatch')
  if java_result.get('policy')!=policy or java_result.get('policyDigest')!=POLICY_SHA:raise RuntimeError('Java/root policy mismatch')
  if not any(jpid in lineage for lineage in root_ancestries):raise RuntimeError('root wrapper ancestry not linked to actual Java PID')
  health=json.loads(urllib.request.urlopen('http://127.0.0.1:18080/actuator/health',timeout=5).read(4096))
  if health.get('status')!='UP':raise RuntimeError('Spring health not UP')
  targets=json.loads(urllib.request.urlopen('http://127.0.0.1:18080/api/v1/targets',timeout=5).read(16384))
  if len(targets)!=1 or targets[0].get('name')!='order-service':raise RuntimeError('actual API repository read failed')
  for table in ['experiments','experiment_executions','blade_execution_snapshots']:
   if mysql('SELECT count(*) FROM chaoslab_m1.'+table+';').strip()!=b'0':raise RuntimeError('experiment/UID rows exist')
  command(['/usr/sbin/visudo','-c'])
  if sudo_before!={str(p):sha(p.read_bytes()) for p in rules} or POLICY.read_bytes()!=policy_bytes or sha(WRAPPER.read_bytes())!=WRAPPER_SHA:raise RuntimeError('privilege boundary changed')
  denied={}
  for exe in ['/bin/sh','/bin/bash','/usr/bin/docker','/usr/bin/env','/usr/bin/python3','/usr/bin/systemctl','/usr/bin/systemd-run',policy['executable']]:
   denied[exe]=subprocess.run(['/usr/sbin/runuser','-u','chaoslab','--','/usr/bin/sudo','-n','-l','--',exe],env=ENV,capture_output=True,timeout=5).returncode!=0
  if not all(denied.values()):raise RuntimeError('unexpected sudo permission')
  current=idle['inspect']();last=idle['sample'](current['State']['Pid'])
  if not current['State']['Running'] or any(last[k]!=runtime[k] for k in ['pid','startTime','cgroup']):raise RuntimeError('sandbox runtime changed')
  elapsed=last['monotonicNs']-runtime['monotonicNs'];usage=last['usageUsec']-runtime['usageUsec']
  if elapsed<=0 or usage<0:raise RuntimeError('invalid final CPU counter')
  final_cpu=usage*100000.0/elapsed
  if final_cpu>1:raise RuntimeError('sandbox CPU not at idle baseline')
  absent()
  if tool_processes(policy):raise RuntimeError('unexpected ChaosBlade process')
  report.update(javaResult=java_result,actualRootWrapperObserved=True,httpHealth='UP',httpAddress='127.0.0.1:18080',negativeSudo=denied,
   authorizationAbsent=True,nativeStateAbsent=True,sudoUnchanged=True,wrapperSha256=WRAPPER_SHA,finalSandboxSample=last,finalCpuPercent=final_cpu,passed=True)
 except Exception as e:
  report['blocker']=str(e) if isinstance(e,RuntimeError) else type(e).__name__
  if process is not None and process.poll() is None:
   os.killpg(process.pid,signal.SIGTERM)
   try:process.wait(timeout=20)
   except subprocess.TimeoutExpired:os.killpg(process.pid,signal.SIGKILL);process.wait(timeout=5)
 finally:
  data=json.dumps(report,indent=2).encode()
  write(AUDIT/'java-readonly-deployment.json',data)
  write(STAGE/'java-readonly-deployment.json',data,0o644)
 print('JAVA/MYSQL READONLY DEPLOYMENT:', 'PASS' if report['passed'] else 'STOP; inspect report')
 print('REAL EXECUTION NOT AUTHORIZED')


if __name__=='__main__':main()
