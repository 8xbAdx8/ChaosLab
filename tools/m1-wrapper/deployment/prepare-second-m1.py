"""One approved R1 archive -> R2 generation -> exact a1cd739 read-only deployment.
No authorization, native create/destroy, R1 update/delete or automatic rollback.
Fixed paths only; must be invoked once by the VM administrator with python3 -I.
"""
import hashlib
import io
import json
import os
from pathlib import Path, PurePosixPath
import pwd
import runpy
import secrets
import signal
import sqlite3
import stat
import subprocess
import sys
import time
import urllib.request
import urllib.parse
import zipfile

COMMIT='a1cd7394cf945390b257ef2e65007da9c9ac395f'
STAGE=Path('/home/w/chaoslab-m1-2d3-20261004')
STATE=Path('/var/lib/chaoslab-m1/state')
POLICY=Path('/etc/chaoslab-m1/policy.json')
AUDIT=Path('/var/lib/chaoslab-m1/audit')
ARCHIVE=Path('/var/lib/chaoslab-m1/archive/m1-r1-21d2d20071b3f449')
DEST=Path('/opt/chaoslab-backend/m1-a1cd739')
CONFIG=Path('/etc/chaoslab-backend-m1-r2')
WORK=Path('/var/lib/chaoslab-backend-m1-r2')
OLD_DEST=Path('/opt/chaoslab-backend/m1-698a905')
WRAPPER=Path('/usr/local/libexec/chaoslab-m1-wrapper')
JAVA='/usr/lib/jvm/java-21-openjdk-amd64/bin/java'
EXECUTION='8781633f-5915-47cf-86b7-87abae66a7cf'
UID='21d2d20071b3f449'
R2_STATE_ID='m1-real-state-r2'
APP_SHA='1b7bec8f07f83e5cd6a48b4119ecde9588f127b07fa5d52266107251e7e069fd'
HARNESS_SHA='ab81768b4655c5844e536a0f5a9016ba7f44c063c3a883884ee5ceecbd364e17'
OLD_APP_SHA='aa8bdfa116326ef6466648dc01eb804f64d04bba37c826e719767fae24dd23f1'
WRAPPER_SHA='2e27c2f2b15fb011c1562f0a2fc19640e7b5e0f3fef1d9c166bf54969c654b94'
R1_POLICY_SHA='9645e6c589812c933cd24d4ce648992940154677bb2504171a28a594b21c27ca'
EVIDENCE_SHA='7e8867fcdd2475ecf5c969c0eb6c1bf7539503c9a4ca65d42d0d0178dec5fffa'
HELPER_SHA='72c813452c54bbee93e1481c2ae0551ff4ef994f67082868bf0fd1b82a5e72de'
IDLE_SHA='e498834001c352908d8884d1eb798b9c1c7e40fb01e006fc3a170e5a3e8d3dd4'
LIBS={
 'mockito-core-5.23.0.jar':'ae295bebd5d11fab97ab297815dc7617188b86003cbce3dfd5c0d5c3a6cc4a0c',
 'byte-buddy-1.18.10.jar':'8b31f4ea806afaa900b67bffd8498760d1f65464f4c2ea78cdbda2f3e633898b',
 'byte-buddy-agent-1.18.10.jar':'9cfa3c71c8f07bfb5f4c3c68db7715940d792801eba82d346fc343f7bd7c2401',
 'objenesis-3.3.jar':'02dfd0b0439a5591e35b708ed2f5474eb0948f53abf74637e959b8e4ef69bfeb',
}
ENV={'PATH':'/usr/sbin:/usr/bin:/sbin:/bin','HOME':'/root','LANG':'C','LC_ALL':'C'}
CLIENT=urllib.request.build_opener(urllib.request.ProxyHandler({}))
NATIVE_NAMES={'.chaoslab-state-id','binding.json','chaosblade.dat','chaosblade.dat-wal','chaosblade.dat-shm','chaosblade.dat-journal'}
R1_QUERY="""SELECT JSON_OBJECT('executionId',x.id,'status',x.status,'version',x.version,'errorMessage',x.error_message,
 'uid',b.blade_uid,'format',b.snapshot_format,'stateId',b.state_directory_id)
 FROM chaoslab_m1.experiment_executions x JOIN chaoslab_m1.blade_execution_snapshots b ON b.execution_id=x.id;"""

def require(value,label):
 if not value:raise RuntimeError(label)
def sha(raw):return hashlib.sha256(raw).hexdigest()
def encode(value):return json.dumps(value,separators=(',',':')).encode()
def digest(path):
 require(path.is_file() and not path.is_symlink() and path.stat().st_size<=128*1024*1024,'invalid bounded file')
 h=hashlib.sha256()
 with path.open('rb') as f:
  for block in iter(lambda:f.read(1024*1024),b''):h.update(block)
 return h.hexdigest()
def command(args,data=None,timeout=30,limit=16*1024*1024):
 p=subprocess.run(args,input=data,env=ENV,capture_output=True,timeout=timeout)
 require(p.returncode==0 and len(p.stdout)<=limit,'fixed command failed: '+Path(args[0]).name)
 return p.stdout
def mysql(sql):
 return command(['/usr/bin/mysql','--no-defaults','--protocol=socket','--socket=/var/run/mysqld/mysqld.sock','--batch','--skip-column-names'],sql.encode()).decode().strip()
def rotate_policy(policy):
 require(policy.get('deployment')=='REAL' and sha(encode(policy))==R1_POLICY_SHA,'R1 policy changed')
 rotated=dict(policy,stateId=R2_STATE_ID)
 require(all(rotated[k]==v for k,v in policy.items() if k!='stateId'),'policy scope changed')
 return rotated
def r1_evidence(value):
 require(value.get('result')=='M1 INCOMPLETE' and value.get('recoveryVerified') is False
  and value.get('executionId')==EXECUTION and value.get('nativeUid')==UID
  and value.get('createApiAttempts')==1,'R1 evidence is not approved incomplete result')
def no_authorization():
 for root in [STATE,Path('/var/lib/chaoslab-m1/fixture-state')]:
  require(not os.path.lexists(root/'authorization.json'),'authorization exists; STOP')
def active_clean():
 no_authorization()
 require({p.name for p in STATE.iterdir()}=={'.chaoslab-state-id'},'R2 active generation not clean')
 require((STATE/'.chaoslab-state-id').read_text().strip()==R2_STATE_ID,'R2 marker mismatch')
def root_preflight():
 raw=command(['/usr/sbin/runuser','-u','chaoslab','--','/usr/bin/sudo','-n','--',str(WRAPPER)],b'{"operation":"preflight"}',timeout=12,limit=65536)
 value=json.loads(raw)
 require(value.get('code')=='OK' and value.get('observation',{}).get('residual')=='CLEAR','root readiness not CLEAR')
 require(value['observation'].get('probeReady') is True and value['observation'].get('cpuPercent',999)<=1
  and value['observation'].get('health')=='UNKNOWN','unbound readiness invalid')
 return value
def fsync_directory(path):
 fd=os.open(path,os.O_RDONLY|os.O_DIRECTORY)
 try:os.fsync(fd)
 finally:os.close(fd)
def stop_old_backend():
 old_classpath=str(OLD_DEST/'acceptance.jar')+':'+str(OLD_DEST/'app/BOOT-INF/classes')+':'+str(OLD_DEST/'app/BOOT-INF/lib/*')
 for proc in Path('/proc').iterdir():
  if not proc.name.isdigit():continue
  try:
   if os.readlink(proc/'exe')!=JAVA:continue
   argv=(proc/'cmdline').read_bytes().decode().split('\0')
   require(old_classpath in argv and 'com.chaoslab.engine.infrastructure.blade.M1ReadOnlyAcceptance' in argv,'unexpected Java deployment; STOP')
   require('\nUid:\t999\t999\t999\t999\n' in (proc/'status').read_text(),'old backend identity changed')
   # pidfd targets this exact live process, never a recycled PID or unrelated JVM.
   fd=os.pidfd_open(int(proc.name))
   try:signal.pidfd_send_signal(fd,signal.SIGTERM)
   finally:os.close(fd)
   deadline=time.monotonic()+20
   while proc.exists() and time.monotonic()<deadline:time.sleep(.1)
   require(not proc.exists(),'old backend did not stop; STOP')
  except FileNotFoundError:pass

def main():
 require(os.geteuid()==0 and len(sys.argv)==1,'administrator required; no arguments')
 os.umask(0o077)
 require(digest(STAGE/'deploy-java-readonly.py')==HELPER_SHA,'reviewed helpers changed')
 helper=runpy.run_path(str(STAGE/'deploy-java-readonly.py'));trust=helper['trust'];write=helper['write']
 for p in [STATE,POLICY,AUDIT,WRAPPER,OLD_DEST/'backend.jar']:trust(p)
 for p in [ARCHIVE,DEST,CONFIG,WORK,AUDIT/'second-m1-preparation.json',AUDIT/'r2-preparation-attempt.json']:
  require(not os.path.lexists(p),'R2 attempt/deployment already exists; do not rerun')
 account=pwd.getpwnam('chaoslab')
 require((account.pw_uid,account.pw_gid,account.pw_shell)==(999,987,'/usr/sbin/nologin')
  and command(['/usr/bin/id','-G','chaoslab']).strip()==b'987','service account changed')
 require(digest(WRAPPER)==WRAPPER_SHA and digest(OLD_DEST/'backend.jar')==OLD_APP_SHA,'old deployment pin changed')
 for name,pin in {'backend-a1cd739.jar':APP_SHA,'m1-r2-acceptance.jar':HARNESS_SHA,**LIBS}.items():
  require(digest(STAGE/name)==pin,'new reviewed artifact mismatch')
 require(digest(STAGE/'start-idle.py')==IDLE_SHA,'idle inspector changed')
 require(digest(AUDIT/'first-real-m1-result.json')==EVIDENCE_SHA,'original root evidence changed')
 evidence=json.loads((AUDIT/'first-real-m1-result.json').read_bytes());r1_evidence(evidence)
 policy_bytes=POLICY.read_bytes();policy=json.loads(policy_bytes);new_policy=rotate_policy(policy)
 for relative,key in [('', 'toolSha256'),('bin/nsexec','nsexecSha256'),('bin/chaos_os','chaosOsSha256'),('yaml/chaosblade-cri-spec-1.8.1.yaml','yamlSha256')]:
  path=Path(policy['executable']) if not relative else Path(policy['executable']).parent/relative
  trust(path);require(digest(path)==policy[key],'candidate identity changed')
 no_authorization()
 require(not helper['tool_processes'](policy),'known tool/helper residual; STOP')
 require((STATE/'.chaoslab-state-id').read_text().strip()=='m1-real-state','R1 marker changed')
 require({p.name for p in STATE.iterdir()}<=NATIVE_NAMES
  and {'binding.json','chaosblade.dat','.chaoslab-state-id'}<={p.name for p in STATE.iterdir()},'unexpected R1 active files')
 binding=json.loads((STATE/'binding.json').read_bytes())
 require(binding.get('executionId')==EXECUTION and binding.get('nativeUid')==UID and binding.get('policyDigest')==R1_POLICY_SHA,'R1 binding changed')
 require(mysql('SELECT @@bind_address,@@mysqlx_bind_address;')=='127.0.0.1\t127.0.0.1','MySQL scope changed')
 require(mysql("SELECT count(*) FROM information_schema.SCHEMATA WHERE SCHEMA_NAME='chaoslab_m1_r2';")=='0'
  and mysql("SELECT count(*) FROM mysql.user WHERE user='chaoslab_m1_r2';")=='0','R2 database/principal already exists')
 rules=[Path('/etc/sudoers'),*sorted(Path('/etc/sudoers.d').iterdir())]
 sudo_before={str(p):digest(p) for p in rules}
 require(command(['/usr/sbin/visudo','-c']) is not None,'sudoers invalid')
 idle=runpy.run_path(str(STAGE/'start-idle.py'))
 v=idle['inspect']() # existing exact idle workload/security only
 write(AUDIT/'r2-preparation-attempt.json',encode({'commit':COMMIT,'secondCreateAuthorized':False,'singlePreparationAttempt':True}))
 fsync_directory(AUDIT)
 r={'ready':False,'status':'SECOND M1 NOT READY','commit':COMMIT,'firstM1':'INCOMPLETE','firstRecoveryVerified':False,
    'realAuthorizationCreated':False,'realCreateInvoked':False,'backendArtifactSha256':APP_SHA,'harnessSha256':HARNESS_SHA}
 phase='freeze R1';process=None
 try:
  print('Freezing R1 backend; no execution API calls.',flush=True)
  stop_old_backend()
  if not v['State']['Running']:idle['docker']('start',v['Id']);r['existingSandboxRestarted']=True
  v=idle['inspect']();require(v['State']['Running'],'sandbox not Running')
  runtime=idle['sample'](v['State']['Pid']);time.sleep(1);after=idle['sample'](v['State']['Pid'])
  require(all(after[k]==runtime[k] for k in ['pid','startTime','cgroup']),'runtime changed')
  require((after['usageUsec']-runtime['usageUsec'])*100000.0/(after['monotonicNs']-runtime['monotonicNs'])<=1,'baseline not idle')
  pre=root_preflight();require(pre['policy']==policy and pre['policyDigest']==R1_POLICY_SHA,'R1 preflight policy changed')
  first_row=mysql(R1_QUERY);row=json.loads(first_row)
  require(row['executionId']==EXECUTION and row['uid']==UID and row['status']=='ROLLBACK_FAILED'
   and row['format']=='CRI_CPU_V1' and row['stateId']=='m1-real-state','R1 history changed')
  for table in ['experiments','experiment_executions','blade_execution_snapshots']:
   require(mysql('SELECT count(*) FROM chaoslab_m1.'+table+';')=='1','unexpected R1 history rows')
  phase='R1 archive'
  print('Creating root-only full R1 archive and verifying every SHA before rotation.',flush=True)
  if not ARCHIVE.parent.exists():ARCHIVE.parent.mkdir(mode=0o700)
  trust(ARCHIVE.parent);ARCHIVE.mkdir(mode=0o700);trust(ARCHIVE)
  (ARCHIVE/'state-copy').mkdir(mode=0o700)
  archive_files={}
  def archive_file(name,raw):
   target=ARCHIVE/name;write(target,raw);trust(target)
   require(digest(target)==sha(raw),'archive read-back mismatch')
   archive_files[name]={'sha256':sha(raw),'size':len(raw)}
  dump=command(['/usr/bin/mysqldump','--no-defaults','--protocol=socket','--socket=/var/run/mysqld/mysqld.sock',
   '--single-transaction','--routines','--events','--triggers','--hex-blob','--no-tablespaces','--set-gtid-purged=OFF','--databases','chaoslab_m1'],timeout=60)
  require(b'CREATE TABLE `experiment_executions`' in dump and UID.encode() in dump and b'ROLLBACK_FAILED' in dump,'R1 full dump missing history')
  archive_file('chaoslab_m1.sql',dump)
  for source in sorted(STATE.iterdir()):
   trust(source);require(source.is_file() and source.stat().st_size<=32*1024*1024,'unbounded/nonregular native state')
   archive_file('state-copy/'+source.name,source.read_bytes())
  archive_file('policy-r1.json',policy_bytes)
  archive_file('first-real-m1-result.json',(AUDIT/'first-real-m1-result.json').read_bytes())
  archive_file('r1-history-row.json',first_row.encode())
  metadata={'firstM1':'INCOMPLETE','recoveryVerified':False,'executionId':EXECUTION,'nativeUid':UID,
   'backendCommit':'698a905322104063ae130f810999723b72d9aca2','backendJarSha256':OLD_APP_SHA,
   'wrapperSha256':WRAPPER_SHA,'policyFileSha256':sha(policy_bytes),'policyDigest':R1_POLICY_SHA,
   'toolShaSet':{k:policy[k] for k in ['toolSha256','nsexecSha256','chaosOsSha256','yamlSha256']},'files':archive_files}
  manifest=encode(metadata);write(ARCHIVE/'manifest.json',manifest);fsync_directory(ARCHIVE)
  for name,item in archive_files.items():require(digest(ARCHIVE/name)==item['sha256'],'archive verification failed')
  # Verify native state in the COPY, never rewrite original R1 state or record.
  conn=sqlite3.connect((ARCHIVE/'state-copy/chaosblade.dat').as_uri()+'?mode=ro',uri=True)
  try:
   conn.execute('PRAGMA query_only=ON')
   require(conn.execute('PRAGMA integrity_check').fetchall()==[('ok',)],'archived SQLite integrity failed')
   require(conn.execute('SELECT uid,status FROM experiment').fetchall()==[(UID,'Destroyed')],'archived native UID/status mismatch')
  finally:conn.close()
  # Inspection must not change any archived bytes; WAL/SHM are included if present.
  for name,item in archive_files.items():require(digest(ARCHIVE/name)==item['sha256'],'archive changed during readonly verification')
  require(mysql(R1_QUERY)==first_row,'R1 history changed before rotation')
  r['r1Archive']={'path':str(ARCHIVE),'manifestSha256':sha(manifest),'files':archive_files,'readBackVerified':True}
  phase='R2 generation/database'
  print('Archive PASS; rotating fixed active state generation and creating dedicated R2 schema.',flush=True)
  require(STATE.stat().st_dev==ARCHIVE.stat().st_dev,'rotation not same filesystem')
  os.rename(STATE,ARCHIVE/'retired-active-state');STATE.mkdir(mode=0o700);trust(STATE)
  write(STATE/'.chaoslab-state-id',(R2_STATE_ID+'\n').encode());fsync_directory(STATE)
  pending=POLICY.with_name('policy.r2.tmp');write(pending,encode(new_policy));os.replace(pending,POLICY);fsync_directory(POLICY.parent)
  active_clean();require(not helper['tool_processes'](new_policy),'tool appeared during rotation')
  password=secrets.token_hex(32)
  mysql("CREATE DATABASE chaoslab_m1_r2 CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci; CREATE USER 'chaoslab_m1_r2'@'127.0.0.1' IDENTIFIED BY '"+password+"'; GRANT SELECT,INSERT,UPDATE,DELETE,CREATE,ALTER,INDEX,REFERENCES,DROP ON chaoslab_m1_r2.* TO 'chaoslab_m1_r2'@'127.0.0.1';")
  grants=mysql("SHOW GRANTS FOR 'chaoslab_m1_r2'@'127.0.0.1';").splitlines()
  require(len(grants)==2 and any(line.startswith('GRANT USAGE ON *.* ') for line in grants)
   and any('ON `chaoslab_m1_r2`.* ' in line and 'GRANT OPTION' not in line for line in grants),'R2 privileges not schema-only')
  phase='a1cd739 side-by-side deployment'
  DEST.mkdir(mode=0o755);os.chmod(DEST,0o755);trust(DEST)
  write(DEST/'backend.jar',(STAGE/'backend-a1cd739.jar').read_bytes(),0o644)
  write(DEST/'acceptance.jar',(STAGE/'m1-r2-acceptance.jar').read_bytes(),0o644)
  (DEST/'diagnostic-libs').mkdir(mode=0o755);os.chmod(DEST/'diagnostic-libs',0o755)
  for name in LIBS:write(DEST/'diagnostic-libs'/name,(STAGE/name).read_bytes(),0o644)
  engine_entry='BOOT-INF/classes/com/chaoslab/engine/infrastructure/blade/ChaosBladeEngine.class'
  with zipfile.ZipFile(DEST/'backend.jar') as z:
   require(sum(i.file_size for i in z.infolist())<=256*1024*1024,'artifact oversized')
   engine_sha=sha(z.read(engine_entry))
   for entry in z.infolist():
    name=PurePosixPath(entry.filename)
    require(not name.is_absolute() and '..' not in name.parts and '\\' not in entry.filename
     and not stat.S_ISLNK(entry.external_attr>>16),'unsafe jar entry')
    if not entry.filename.startswith(('BOOT-INF/classes/','BOOT-INF/lib/')):continue
    target=DEST/'app'/entry.filename
    if entry.is_dir():target.mkdir(mode=0o755,parents=True,exist_ok=True)
    else:target.parent.mkdir(mode=0o755,parents=True,exist_ok=True);write(target,z.read(entry),0o644)
  for directory in [DEST/'app',*(p for p in (DEST/'app').rglob('*') if p.is_dir())]:os.chmod(directory,0o755);trust(directory)
  CONFIG.mkdir(mode=0o750);os.chown(CONFIG,0,987);os.chmod(CONFIG,0o750)
  WORK.mkdir(mode=0o700);os.chown(WORK,999,987)
  values={'spring.datasource.url':'jdbc:mysql://127.0.0.1:3306/chaoslab_m1_r2?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC',
   'spring.datasource.username':'chaoslab_m1_r2','spring.datasource.password':password,'server.address':'127.0.0.1','server.port':'18080',
   'chaoslab.engine':'blade','chaoslab.execution.max-active-executions':'1','chaoslab.blade.executable':new_policy['executable'],
   'chaoslab.blade.state-directory':str(STATE),'chaoslab.blade.node-marker':'/etc/chaoslab-m1/node-id','chaoslab.blade.node-id':new_policy['nodeId'],
   'chaoslab.blade.state-id':R2_STATE_ID,'chaoslab.blade.tool-version':'api3-identified','chaoslab.blade.cli-sha256':new_policy['toolSha256'],
   'chaoslab.blade.nsexec-sha256':new_policy['nsexecSha256'],'chaoslab.blade.chaos-os-sha256':new_policy['chaosOsSha256'],'chaoslab.blade.cri-yaml-sha256':new_policy['yamlSha256']}
  write(CONFIG/'application.properties','\n'.join(k+'='+v for k,v in values.items()).encode()+b'\n',0o640,987)
  child_env={'PATH':'/usr/bin:/bin','HOME':'/nonexistent','LANG':'C','LC_ALL':'C','CHAOSLAB_DB_URL':values['spring.datasource.url'],
   'CHAOSLAB_DB_USERNAME':'chaoslab_m1_r2','CHAOSLAB_DB_PASSWORD':password}
  cp=str(DEST/'acceptance.jar')+':'+str(DEST/'app/BOOT-INF/classes')+':'+str(DEST/'app/BOOT-INF/lib/*')+':'+str(DEST/'diagnostic-libs/*')
  log=WORK/'backend.log';write(log,b'',0o600,987,999)
  args=['/usr/sbin/runuser','-u','chaoslab','--',JAVA,'-Xms64m','-Xmx512m',
   '-javaagent:'+str(DEST/'diagnostic-libs/byte-buddy-agent-1.18.10.jar'),'-cp',cp,
   'com.chaoslab.engine.infrastructure.blade.M1R2ReadOnlyAcceptance','--spring.config.additional-location=file:'+str(CONFIG/'application.properties')]
  print('Starting ordinary R2 backend; real readonly chain and same-loaded-class harmless settling seam.',flush=True)
  with log.open('ab') as output:process=subprocess.Popen(args,env=child_env,cwd=WORK,stdout=output,stderr=subprocess.STDOUT,start_new_session=True)
  phase='actual R2 Java readonly acceptance';deadline=time.monotonic()+200;ancestries=[]
  report_file=WORK/'java-readonly-result.json'
  while time.monotonic()<deadline and not report_file.exists():
   require(process.poll() is None,'R2 backend stopped; inspect restricted report/log')
   for proc in Path('/proc').iterdir():
    if not proc.name.isdigit():continue
    try:
     if os.readlink(proc/'exe')==str(WRAPPER) and proc.stat().st_uid==0:
      ancestor=int(proc.name);lineage=[]
      for _ in range(16):
       lineage.append(ancestor);ancestor=int(Path('/proc',str(ancestor),'stat').read_text().rsplit(') ',1)[1].split()[1])
       if ancestor<=1:break
      ancestries.append(lineage)
    except FileNotFoundError:pass
   time.sleep(.1)
  require(report_file.exists() and report_file.stat().st_size<=32768,'R2 Java report missing/oversized')
  java_result=json.loads(report_file.read_bytes());r['javaResult']=java_result
  require(java_result.get('passed') is True and java_result.get('commit')==COMMIT,'R2 Java acceptance failed')
  jpid=int(java_result['pid']);status=Path('/proc',str(jpid),'status').read_text()
  require('\nUid:\t999\t999\t999\t999\n' in status and '\nGid:\t987\t987\t987\t987\n' in status
   and os.readlink('/proc/'+str(jpid)+'/exe')==JAVA,'R2 process identity mismatch')
  require(java_result['engineClassSha256']==engine_sha and java_result['policy']==new_policy
   and java_result['policyDigest']==sha(encode(new_policy)),'loaded R2 class/policy mismatch')
  code_source=urllib.parse.urlparse(java_result['engineCodeSource'])
  require(code_source.scheme=='file' and Path(code_source.path)==DEST/'app/BOOT-INF/classes','loaded engine not from fixed R2 artifact')
  live_argv=Path('/proc',str(jpid),'cmdline').read_bytes().decode().split('\0')
  require(cp in live_argv and 'com.chaoslab.engine.infrastructure.blade.M1R2ReadOnlyAcceptance' in live_argv,'R2 JVM invocation changed')
  require(any(jpid in lineage for lineage in ancestries),'root wrapper ancestry not linked to actual R2 Java PID')
  health=json.loads(CLIENT.open('http://127.0.0.1:18080/actuator/health',timeout=5).read(4096))
  require(health.get('status')=='UP','R2 HTTP health not UP')
  targets=json.loads(CLIENT.open('http://127.0.0.1:18080/api/v1/targets',timeout=5).read(16384))
  require(len(targets)==1 and targets[0].get('name')=='order-service','R2 target metadata not fixed')
  for table in ['experiments','experiment_executions','blade_execution_snapshots']:
   require(mysql('SELECT count(*) FROM chaoslab_m1_r2.'+table+';')=='0','R2 experimental rows exist')
  active_clean();final_pre=root_preflight();require(final_pre['policy']==new_policy,'final R2 preflight mismatch')
  latest=idle['inspect']();sample=idle['sample'](latest['State']['Pid'])
  require(latest['State']['Running'] and all(sample[k]==runtime[k] for k in ['pid','startTime','cgroup']),'sandbox runtime changed')
  require(not helper['tool_processes'](new_policy),'known tool/helper appeared')
  require(mysql(R1_QUERY)==first_row and digest(AUDIT/'first-real-m1-result.json')==EVIDENCE_SHA,'R1 history/evidence changed')
  for name,item in archive_files.items():require(digest(ARCHIVE/name)==item['sha256'],'R1 archive changed')
  require(digest(ARCHIVE/'manifest.json')==sha(manifest),'R1 manifest changed')
  require(sudo_before=={str(p):digest(p) for p in rules} and digest(WRAPPER)==WRAPPER_SHA
   and digest(OLD_DEST/'backend.jar')==OLD_APP_SHA and digest(DEST/'backend.jar')==APP_SHA,'privilege/artifact boundary changed')
  negative={}
  for exe in ['/bin/sh','/bin/bash','/usr/bin/docker','/usr/bin/env','/usr/bin/python3','/usr/bin/systemctl','/usr/bin/systemd-run',new_policy['executable']]:
   negative[exe]=subprocess.run(['/usr/sbin/runuser','-u','chaoslab','--','/usr/bin/sudo','-n','-l','--',exe],env=ENV,capture_output=True,timeout=5).returncode!=0
  require(all(negative.values()),'unexpected sudo authorization')
  for user in ['chaoslab','w']:
   for path in [ARCHIVE,STATE,POLICY,WRAPPER,Path(new_policy['executable'])]:
    require(subprocess.run(['/usr/sbin/runuser','-u',user,'--','/usr/bin/test','-w',str(path)],env=ENV,capture_output=True,timeout=3).returncode!=0,'ordinary user can modify privileged paths')
  command(['/usr/sbin/visudo','-c']);active_clean()
  r.update(ready=True,status='SECOND M1 READY',actualRootWrapperObserved=True,http='127.0.0.1:18080',databaseScope='127.0.0.1:3306/chaoslab_m1_r2; schema-only',
   r1DatabasePreserved=True,r1EvidenceUnchanged=True,r1Row=row,r2ExperimentalRows=0,policyDigest=sha(encode(new_policy)),stateId=R2_STATE_ID,
   wrapperSha256=WRAPPER_SHA,finalPreflight=final_pre,finalSandboxSample=sample,negativeSudo=negative,
   authorizationAbsent=True,bindingAbsent=True,nativeDbAbsent=True,sudoUnchanged=True)
 except BaseException as e:
  r['blocker']=str(e) if isinstance(e,RuntimeError) else type(e).__name__;r['failedPhase']=phase
  if process is not None and process.poll() is None:
   os.killpg(process.pid,signal.SIGTERM)
   try:process.wait(timeout=20)
   except subprocess.TimeoutExpired:r['backendStopUnconfirmed']=True
  # No archive/database removal, state reset, rollback, create or recovery attempt.
 finally:
  raw=json.dumps(r,indent=2).encode();write(AUDIT/'second-m1-preparation.json',raw)
  write(STAGE/'second-m1-preparation.json',raw,0o644)
 print(r['status'],flush=True)
 print('WAITING FOR USER CONFIRMATION; SECOND REAL CREATE NOT AUTHORIZED',flush=True)

if __name__=='__main__':main()
