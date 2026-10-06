"""ONE explicitly approved M1 only. Backend generates/commits UID; never direct create.
No replay/resume path. Failure retains occupancy; emergency stop is NOT recovery.
"""
import concurrent.futures
from datetime import datetime, timedelta, timezone
import hashlib
import json
import os
from pathlib import Path
import re
import runpy
import signal
import subprocess
import sys
import time
import urllib.request
import uuid

STAGE=Path('/home/w/chaoslab-m1-2d3-20261004')
STATE=Path('/var/lib/chaoslab-m1/state')
AUDIT=Path('/var/lib/chaoslab-m1/audit')
POLICY=Path('/etc/chaoslab-m1/policy.json')
WRAPPER='/usr/local/libexec/chaoslab-m1-wrapper'
JAVA='/usr/lib/jvm/java-21-openjdk-amd64/bin/java'
BACKEND=Path('/opt/chaoslab-backend/m1-698a905/backend.jar')
APP_SHA='aa8bdfa116326ef6466648dc01eb804f64d04bba37c826e719767fae24dd23f1'
WRAPPER_SHA='2e27c2f2b15fb011c1562f0a2fc19640e7b5e0f3fef1d9c166bf54969c654b94'
POLICY_SHA='9645e6c589812c933cd24d4ce648992940154677bb2504171a28a594b21c27ca'
DEPLOY_SHA='72c813452c54bbee93e1481c2ae0551ff4ef994f67082868bf0fd1b82a5e72de'
IDLE_SHA='e498834001c352908d8884d1eb798b9c1c7e40fb01e006fc3a170e5a3e8d3dd4'
TARGET='55555555-5555-4555-8555-555555555555'
SCENARIO='00000000-0000-0000-0000-000000000101'
URL='http://127.0.0.1:18080'
ENV={'PATH':'/usr/sbin:/usr/bin:/sbin:/bin','HOME':'/root','LANG':'C','LC_ALL':'C'}
CLIENT=urllib.request.build_opener(urllib.request.ProxyHandler({}))


def require(v,label):
 if not v:raise RuntimeError(label)
def sha(b):return hashlib.sha256(b).hexdigest()
def encode(v):return json.dumps(v,separators=(',',':')).encode()
def instant(d):return d.isoformat(timespec='microseconds').replace('+00:00','Z')
def mysql(sql):
 proc=subprocess.run(['/usr/bin/mysql','--no-defaults','--protocol=socket','--socket=/var/run/mysqld/mysqld.sock','--batch','--skip-column-names'],
  input=('SET SESSION TRANSACTION ISOLATION LEVEL READ COMMITTED; SET time_zone=\'+00:00\'; '+sql).encode(),env=ENV,capture_output=True,timeout=3)
 require(proc.returncode==0,'dedicated MySQL read failed')
 return proc.stdout.decode().strip()
def http(path,body=None,key=None,timeout=18):
 headers={'Content-Type':'application/json'}
 if key is not None:headers['Idempotency-Key']=key
 request=urllib.request.Request(URL+path,data=encode(body) if body is not None else None,headers=headers,
  method='POST' if body is not None else 'GET')
 with CLIENT.open(request,timeout=timeout) as response:
  raw=response.read(65537);require(len(raw)<=65536,'HTTP output too large');return json.loads(raw)
def wrapper(operation,execution=None,uid=None):
 q={'operation':operation}
 if execution is not None:q.update(executionId=execution,nativeUid=uid)
 proc=subprocess.run(['/usr/sbin/runuser','-u','chaoslab','--','/usr/bin/sudo','-n','--',WRAPPER],
  input=encode(q),env=ENV,capture_output=True,timeout=10)
 require(not proc.stderr and len(proc.stdout)<=65536,'privileged transport unknown')
 result=json.loads(proc.stdout)
 require(proc.returncode==0 and result.get('code')=='OK','wrapper read/recovery not confirmed')
 return result
def authorization(p,execution,uid):
 now=datetime.now(timezone.utc)
 return {'deployment':'REAL','executable':p['executable'],'policyDigest':POLICY_SHA,
  'toolSha256':p['toolSha256'],'nsexecSha256':p['nsexecSha256'],'chaosOsSha256':p['chaosOsSha256'],'yamlSha256':p['yamlSha256'],
  'expiresAt':instant(now+timedelta(seconds=15)),'executionId':execution,'nativeUid':uid,'containerId':p['containerId'],
  'imageId':p['imageId'],'toolIdentity':sha(':'.join(p[k] for k in ['toolSha256','nsexecSha256','chaosOsSha256','yamlSha256']).encode()),
  'stateIdentity':p['stateId'],'nodeId':p['nodeId'],'createdAt':instant(now)}
def compare_intent(v,p,experiment):
 require(v['experimentId']==experiment and v['status']=='PREPARING' and v['attempt']==1,'unexpected execution reservation')
 require(re.fullmatch('[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}',v['executionId']) is not None,'bad execution ID')
 require(re.fullmatch('[0-9a-f]{16}',v['nativeUid'] or '') is not None,'bad committed UID')
 expected={'format':'CRI_CPU_V1','targetId':TARGET,'containerId':p['containerId'],'imageId':p['imageId'],
  'nodeId':p['nodeId'],'stateId':p['stateId'],'toolSha256':p['toolSha256'],'toolVersion':'api3-identified',
  'cpuPercent':10,'durationSeconds':10,'scenarioCode':'CPU_LOAD'}
 require(all(v.get(k)==value for k,value in expected.items()),'committed intent identity/limits mismatch')
 require(0<=v['ageUsec']<=3000000,'committed intent too old/future')
def intent_query(experiment):
 require(str(uuid.UUID(experiment))==experiment,'unsafe experiment ID')
 return """SELECT JSON_OBJECT('executionId',x.id,'nativeUid',b.blade_uid,'status',x.status,'attempt',x.attempt,
 'experimentId',e.id,'format',b.snapshot_format,'targetId',b.target_id,'containerId',b.container_id,'imageId',b.image_id,
 'nodeId',b.executor_instance_id,'stateId',b.state_directory_id,'toolSha256',b.tool_sha256,'toolVersion',b.tool_version,
 'cpuPercent',b.cpu_percent,'durationSeconds',b.duration_seconds,'scenarioCode',s.code,
 'ageUsec',TIMESTAMPDIFF(MICROSECOND,b.recorded_at,UTC_TIMESTAMP(6)))
 FROM chaoslab_m1.blade_execution_snapshots b JOIN chaoslab_m1.experiment_executions x ON x.id=b.execution_id
 JOIN chaoslab_m1.experiments e ON e.id=x.experiment_id JOIN chaoslab_m1.fault_scenarios s ON s.id=e.scenario_id
 WHERE e.id='"""+experiment+"';"
def cpu_window(idle,runtime,seconds):
 def sample():
  # Baseline inspector intentionally rejects extra members. DURING must retain
  # and identify fault members, not use that baseline-only rejection as evidence.
  current=idle['inspect']();pid=runtime['pid'];p=Path('/proc',str(pid))
  require(current['State']['Running'] and current['State']['Pid']==pid,'target state changed')
  fields=(p/'stat').read_text().rsplit(') ',1)[1].split()
  require(fields[19]==runtime['startTime'] and (p/'cmdline').read_bytes()==b'sleep\x003600\x00','target process changed')
  lines=(p/'cgroup').read_text().splitlines()
  require(lines==['0::'+runtime['cgroup'].removeprefix('/sys/fs/cgroup')],'target cgroup changed')
  group=Path(runtime['cgroup']);require(group.resolve()==group,'cgroup symlink')
  counters=dict(line.split() for line in (group/'cpu.stat').read_text().splitlines())
  members=(group/'cgroup.procs').read_text().split();require(len(members)<=512,'cgroup member bound exceeded')
  tools=[]
  fixed='/opt/chaoslab/m1/api3-identified/bin/'
  for member in members:
   require(member.isdigit(),'invalid cgroup member')
   try:
    executable=os.readlink('/proc/'+member+'/exe')
    if executable in [fixed+'nsexec',fixed+'chaos_os']:tools.append({'pid':int(member),'executable':executable})
   except FileNotFoundError:pass
  return {'pid':pid,'startTime':fields[19],'cgroup':runtime['cgroup'],'usageUsec':int(counters['usage_usec']),
   'monotonicNs':time.monotonic_ns(),'members':members,'knownToolMembers':tools}
 a=sample();time.sleep(seconds);b=sample()
 require(all(a[k]==b[k]==runtime[k] for k in ['pid','startTime','cgroup']),'runtime changed during CPU sample')
 elapsed=b['monotonicNs']-a['monotonicNs'];usage=b['usageUsec']-a['usageUsec']
 require(elapsed>0 and usage>=0,'bad CPU counter')
 return {'samples':[a,b],'oneCpuPercent':usage*100000.0/elapsed,'windowSeconds':elapsed/1e9}
def native_record(uid):
 import sqlite3
 # No CLI side effect. mode=ro observes the actual native state, not immutable
 # assumptions while its candidate owns SQLite. No SQL mutation is allowed.
 conn=sqlite3.connect((STATE/'chaosblade.dat').as_uri()+'?mode=ro',uri=True)
 try:
  conn.execute('PRAGMA query_only=ON')
  require(conn.execute('SELECT count(*) FROM experiment').fetchone()[0]==1,'not exactly one native experiment')
  rows=conn.execute('SELECT uid,command,sub_command,status,flag FROM experiment WHERE uid=?',(uid,)).fetchall()
  require(len(rows)==1,'same native UID record not found')
  return dict(zip(['uid','command','subCommand','status','flags'],rows[0]))
 finally:conn.close()


def main():
 require(os.geteuid()==0 and len(sys.argv)==1,'root administrator required, no arguments')
 os.umask(0o077)
 # Read pinned installer helpers only; never run its deployment initializer.
 require(sha((STAGE/'deploy-java-readonly.py').read_bytes())==DEPLOY_SHA,'installer helper changed')
 helpers=runpy.run_path(str(STAGE/'deploy-java-readonly.py'));trust=helpers['trust'];write=helpers['write']
 for p in [STATE,POLICY,Path(WRAPPER),AUDIT,BACKEND]:trust(p)
 marker=AUDIT/'first-real-m1-attempt.json'
 require(not os.path.lexists(marker),'first M1 already reserved; NEVER rerun')
 write(marker,encode({'approvedOperation':'ONE M1 CPU10 count1 timeout10','reservedAt':instant(datetime.now(timezone.utc)),'automaticRetry':False}))
 fd=os.open(AUDIT,os.O_RDONLY|os.O_DIRECTORY)
 try:os.fsync(fd)
 finally:os.close(fd)
 r={'result':'M1 INCOMPLETE','createApiAttempts':0,'realAuthorizationCreated':False,'recoveryVerified':False,
  'policyDigest':POLICY_SHA,'noAutomaticRetry':True}
 experiment=None;execution=None;uid=None;auth_raw=None;idle=None;policy=None;start_future=None
 pool=concurrent.futures.ThreadPoolExecutor(max_workers=1)
 def checkpoint():
  temp=AUDIT/'first-real-m1.result.tmp'
  if os.path.lexists(temp):raise RuntimeError('unexpected report temp')
  write(temp,json.dumps(r,indent=2).encode());os.replace(temp,AUDIT/'first-real-m1-result.json')
  fd=os.open(AUDIT,os.O_RDONLY|os.O_DIRECTORY)
  try:os.fsync(fd)
  finally:os.close(fd)
 def stop_sandbox(reason):
  r['emergencyStopReason']=reason;r['recoveryVerified']=False
  checkpoint()
  p=subprocess.run(['/usr/bin/docker','--host','unix:///var/run/docker.sock','stop','--time','2',policy['containerId']],env=ENV,capture_output=True,timeout=8)
  r['emergencySandboxStop']={'acknowledged':p.returncode==0,'notRecoveryVerified':True}
  if p.returncode!=0:
   r['emergencyVmPoweroffRequested']=True;checkpoint()
   subprocess.run(['/usr/bin/systemctl','poweroff'],env=ENV,capture_output=True,timeout=5)
 try:
  require(sha(BACKEND.read_bytes())==APP_SHA and sha(Path(WRAPPER).read_bytes())==WRAPPER_SHA,'backend/wrapper changed')
  policy=json.loads(POLICY.read_bytes())
  require(sha(encode(policy))==POLICY_SHA and policy['deployment']=='REAL' and policy['cpuPercent']==10 and policy['durationSeconds']==10,'REAL policy changed')
  helpers['absent']();require(not helpers['tool_processes'](policy),'known tool residual before experiment')
  require(subprocess.run(['/usr/bin/id','-G','chaoslab'],env=ENV,capture_output=True,timeout=3).stdout.strip()==b'987','service group changed')
  prior=json.loads((AUDIT/'java-readonly-deployment.json').read_bytes());pid=prior['javaResult']['pid']
  stat=Path('/proc',str(pid),'status').read_text()
  require('\nUid:\t999\t999\t999\t999\n' in stat and '\nGid:\t987\t987\t987\t987\n' in stat and os.readlink('/proc/'+str(pid)+'/exe')==JAVA,'backend identity changed')
  require(http('/actuator/health')['status']=='UP','backend not UP')
  require(mysql('SELECT @@innodb_flush_log_at_trx_commit,@@sync_binlog;')=='1\t1','MySQL durability settings not confirmed')
  for table in ['experiments','experiment_executions','blade_execution_snapshots']:
   require(mysql('SELECT count(*) FROM chaoslab_m1.'+table+';')=='0','M1 database already contains experiment/intent')
  require(sha((STAGE/'start-idle.py').read_bytes())==IDLE_SHA,'idle inspector changed')
  idle=runpy.run_path(str(STAGE/'start-idle.py'));v=idle['inspect']()
  if not v['State']['Running']:idle['docker']('start',v['Id']);v=idle['inspect']()
  require(v['State']['Running'],'sandbox not Running')
  runtime=idle['sample'](v['State']['Pid'])
  pre=wrapper('preflight');require(pre['policy']==policy and pre['policyDigest']==POLICY_SHA,'preflight identity mismatch')
  o=pre['observation'];require(o['probeReady'] and o['residual']=='CLEAR' and o['cpuPercent']<=1,'baseline not clear/idle')
  r['preflight']=pre;r['baseline']=cpu_window(idle,runtime,1);checkpoint()
  require(r['baseline']['oneCpuPercent']<=1,'CPU baseline not idle')
  created=http('/api/v1/experiments',{'name':'M1 first real CPU10','hypothesis':'Fixed sandbox CPU rises with one bounded worker and recovers after same-UID destroy',
   'targetId':TARGET,'scenarioId':SCENARIO,'durationSeconds':10,'parameters':{'percent':10}})
  experiment=created['id'];r['experimentId']=experiment
  validated=http('/api/v1/experiments/'+experiment+'/validation',{});require(validated['status']=='VALIDATED','definition validation failed')
  dry=http('/api/v1/experiments/'+experiment+'/dry-run',{});r['dryRun']=dry
  require(dry['accepted'] is True and dry['experiment']['status']=='READY','dry run not READY')
  helpers['absent']()
  # Exactly ONE normal backend start call. No retry of HTTP or native dispatch.
  r['createApiAttempts']=1;r['startSubmittedAt']=instant(datetime.now(timezone.utc));checkpoint()
  start_future=pool.submit(http,'/api/v1/experiments/'+experiment+'/executions',{},'m1-once-'+str(uuid.uuid4()))
  deadline=time.monotonic()+12;intent=None
  while time.monotonic()<deadline:
   raw=mysql(intent_query(experiment))
   if raw:
    require('\n' not in raw,'multiple intents');intent=json.loads(raw);break
   if start_future.done():break
   time.sleep(.02)
  require(intent is not None,'committed UID/intent not observed; no authorization')
  compare_intent(intent,policy,experiment)
  execution=intent['executionId'];uid=intent['nativeUid']
  r['committedIntent']=intent;r['executionId']=execution;r['nativeUid']=uid
  r['commitProof']='independent MySQL READ COMMITTED connection; durable settings=1/1; observed BEFORE authorization'
  checkpoint()
  helpers['absent']();auth_raw=encode(authorization(policy,execution,uid))
  write(STATE/'authorization.json',auth_raw)
  fd=os.open(STATE,os.O_RDONLY|os.O_DIRECTORY)
  try:os.fsync(fd)
  finally:os.close(fd)
  r['realAuthorizationCreated']=True;r['authorizationCreatedAt']=instant(datetime.now(timezone.utc));checkpoint()
  started=start_future.result(timeout=18);r['createResponse']=started
  require(started['id']==execution and started['status']=='RUNNING' and started['engineExperimentId']=='blade-'+execution,'create uncertain/mismatched; NO SECOND CREATE')
  binding=json.loads((STATE/'binding.json').read_bytes())
  require(binding['executionId']==execution and binding['nativeUid']==uid and binding['policyDigest']==POLICY_SHA and not os.path.lexists(STATE/'authorization.json'),'root binding/consumption mismatch')
  r['rootBinding']=binding;r['nativeAfterCreate']=native_record(uid)
  require(r['nativeAfterCreate']['command']=='cri' and r['nativeAfterCreate']['subCommand']=='cpu fullload' and r['nativeAfterCreate']['status']=='Success','native create not successful')
  r['createUidEquality']='RUNNING requires strict HANDOFF receipt UID==precommitted UID; root binding/native SQLite same UID'
  r['during']=cpu_window(idle,runtime,1.2)
  during=r['during']['oneCpuPercent'];baseline=r['baseline']['oneCpuPercent']
  tool_seen=any(s['knownToolMembers'] for s in r['during']['samples'])
  r['faultObserved']=during>=baseline+2 and during>=2 and during<=25 and tool_seen
  checkpoint()
  if during>25:
   stop_sandbox('unexpected/uncontrolled CPU');raise RuntimeError('CPU exceeded bounded observation threshold')
  if not r['faultObserved']:
   # Recover known UID through the boundary, but never finalize/release the
   # backend execution on unproven fault. This is manual mitigation, not PASS.
   r['manualKnownUidDestroy']=wrapper('destroy',execution,uid)
   raise RuntimeError('real CPU fault not proven; occupancy retained')
  r['activeDestroyRequestedAt']=instant(datetime.now(timezone.utc));checkpoint()
  destroyed=http('/api/v1/experiments/'+experiment+'/executions/'+execution+'/destroy',{})
  r['backendDestroy']=destroyed
  # Read the SAME known UID only. Backend already performed status + probes +
  # existing Validator/Gate; root corroboration does not rewrite its terminal result.
  status=wrapper('status',execution,uid);r['engineEvidence']=status
  body=status.get('response',{}).get('result',{})
  require(isinstance(body,dict) and body.get('Uid')==uid and body.get('Status')=='Destroyed','same UID Destroyed not confirmed')
  observation=wrapper('observe',execution,uid);r['finalObservation']=observation
  evidence=observation['observation'];at=datetime.fromisoformat(evidence['observedAt'].replace('Z','+00:00'))
  require(evidence['executionId']==execution and evidence['nativeUid']==uid and evidence['nodeId']==policy['nodeId']
   and evidence['containerId']==policy['containerId'] and evidence['imageId']==policy['imageId'],'final evidence identity mismatch')
  require(0<=(datetime.now(timezone.utc)-at).total_seconds()<=10,'final evidence stale/future')
  require(evidence['residual']=='CLEAR' and evidence['health']=='HEALTHY','final residual/health not verified')
  require(destroyed['status']=='SUCCESS' and destroyed['id']==execution,'existing backend Validator/Gate did not verify recovery')
  require(mysql("SELECT count(*) FROM chaoslab_m1.experiment_executions WHERE status IN ('PREPARING','CREATE_UNCERTAIN','RUNNING','DESTROYING','ROLLBACK_FAILED');")=='0','occupancy not released')
  require(mysql('SELECT count(*) FROM chaoslab_m1.blade_execution_snapshots;')=='1','unexpected number of intents')
  require(not os.path.lexists(STATE/'authorization.json'),'authorization remains')
  r['nativeAfterRecovery']=native_record(uid)
  require(r['nativeAfterRecovery']['status']=='Destroyed','native durable recovery state not Destroyed')
  r['recoveryVerified']=True;r['occupancyReleased']=True;r['result']='M1 PASS'
 except BaseException as e:
  r['blocker']=str(e) if isinstance(e,RuntimeError) else type(e).__name__
  r['result']='M1 INCOMPLETE';r['recoveryVerified']=False
  # No create replay. Revoke only this invocation's unconsumed authorization.
  if auth_raw is not None and os.path.lexists(STATE/'authorization.json'):
   require((STATE/'authorization.json').read_bytes()==auth_raw,'unexpected authorization replacement')
   (STATE/'authorization.json').unlink()
  if uid is not None and os.path.lexists(STATE/'binding.json'):
   try:
    observed=wrapper('observe',execution,uid);r['failureObservation']=observed
    o=observed['observation']
    if o['residual']=='UNKNOWN' or o.get('cpuPercent',999)>1:
     stop_sandbox('recovery CPU/residual cannot be confirmed')
   except BaseException:
    try:stop_sandbox('known dispatched experiment recovery visibility failed')
    except BaseException as stop_error:r['emergencyStopError']=type(stop_error).__name__
  if execution is not None:
   try:r['finalBackendExecution']=http('/api/v1/experiments/'+experiment+'/executions/'+execution)
   except Exception:r['finalBackendExecution']='UNKNOWN'
 finally:
  if auth_raw is not None and os.path.lexists(STATE/'authorization.json'):
   if (STATE/'authorization.json').read_bytes()==auth_raw:(STATE/'authorization.json').unlink()
   else:r['authorizationCleanupUnknown']=True
  r['authorizationAbsent']=not os.path.lexists(STATE/'authorization.json')
  r['finishedAt']=instant(datetime.now(timezone.utc));checkpoint()
  write(STAGE/'first-real-m1-result.json',json.dumps(r,indent=2).encode(),0o644)
  pool.shutdown(wait=True,cancel_futures=True)
 print(r['result'])
 print('ONE ATTEMPT ONLY; STOPPED; NO FURTHER CREATE AUTHORIZED')


if __name__=='__main__':main()
