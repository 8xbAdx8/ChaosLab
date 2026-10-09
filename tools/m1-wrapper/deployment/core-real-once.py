"""ONE explicitly approved R3 M1 Core. Never run historical R2 harnesses.
No deployment/init/replay. Only production REST dispatch; Java owns UID and Gate.
An existing root attempt marker or experimental row makes this program refuse rerun.
"""
import concurrent.futures
from datetime import datetime, timedelta, timezone
import hashlib
import ipaddress
import json
import os
from pathlib import Path
import re
import runpy
import sqlite3
import subprocess
import sys
import time
import urllib.error
import urllib.request
import uuid

STAGE=Path('/home/w/chaoslab-m1-core-r3-stage')
AUDIT=Path('/var/lib/chaoslab-m1/audit')
STATE=Path('/var/lib/chaoslab-m1/state')
POLICY=Path('/etc/chaoslab-m1/policy.json')
DEST=Path('/opt/chaoslab-backend/m1-core-76a2fd')
WORK=Path('/var/lib/chaoslab-backend-m1-core-r3')
WRAPPER=Path('/usr/local/libexec/chaoslab-m1-wrapper')
SCHEMA='chaoslab_m1_core_r3'
TARGET='66666666-6666-4666-8666-666666666666'
SCENARIO='00000000-0000-0000-0000-000000000101'
MERGE='0cb29a13690e89a5d4bda2be1c64ebb3408f5536'
APP_SHA='7be24e5e60d018ff3e938b49765f005e2e838e219ae39a065c0c22641b7d1768'
WRAPPER_SHA='2e27c2f2b15fb011c1562f0a2fc19640e7b5e0f3fef1d9c166bf54969c654b94'
POLICY_SHA='e12cee75d1e08fcdf74e0089b45d6949f6c816db8c774aafd00776b9e6ed2eef'
HELPER_SHA='badae2939b1292ce31d451de4e6f75676ee83f5d52337ed9faa4c1dbff7064c2'
IDLE_SHA='c925d2d98705a99b2f68465cfac46c5fbaae1980367445edc5577f144c030fbe'
REPORT=AUDIT/'m1-core-real-result.json'
ATTEMPT=AUDIT/'m1-core-real-attempt.json'
ENV={'PATH':'/usr/sbin:/usr/bin:/sbin:/bin','HOME':'/root','LANG':'C','LC_ALL':'C'}

def require(ok,label):
 if not ok:raise RuntimeError(label)
def encode(v):return json.dumps(v,separators=(',',':')).encode()
def sha(raw):return hashlib.sha256(raw).hexdigest()
def now():return datetime.now(timezone.utc).isoformat(timespec='microseconds').replace('+00:00','Z')
class NoRedirect(urllib.request.HTTPRedirectHandler):
 def redirect_request(self,*args,**kwargs):return None
CLIENT=urllib.request.build_opener(urllib.request.ProxyHandler({}),NoRedirect())
def http(path,body=None,key=None,timeout=50):
 headers={'Content-Type':'application/json'}
 if key:headers['Idempotency-Key']=key
 request=urllib.request.Request('http://127.0.0.1:18080'+path,data=encode(body) if body is not None else None,headers=headers,method='POST' if body is not None else 'GET')
 with CLIENT.open(request,timeout=timeout) as response:
  raw=response.read(65537);require(len(raw)<=65536,'HTTP response limit');return json.loads(raw)
def mysql(sql):
 p=subprocess.run(['/usr/bin/mysql','--no-defaults','--protocol=socket','--socket=/var/run/mysqld/mysqld.sock','--batch','--skip-column-names'],input=("SET time_zone='+00:00'; SET SESSION TRANSACTION ISOLATION LEVEL READ COMMITTED; "+sql).encode(),env=ENV,capture_output=True,timeout=4)
 require(p.returncode==0 and len(p.stdout)<=1024*1024,'fixed MySQL read unavailable');return p.stdout.decode().strip()
def canonical_id(value):
 require(isinstance(value,str) and str(uuid.UUID(value))==value,'invalid UUID');return value
def intent_query(experiment):
 canonical_id(experiment)
 return """SELECT JSON_OBJECT('executionId',x.id,'nativeUid',b.blade_uid,'status',x.status,'attempt',x.attempt,
 'experimentId',e.id,'format',b.snapshot_format,'targetId',b.target_id,'containerId',b.container_id,'imageId',b.image_id,
 'nodeId',b.executor_instance_id,'stateId',b.state_directory_id,'toolSha256',b.tool_sha256,'toolVersion',b.tool_version,
 'cpuPercent',b.cpu_percent,'durationSeconds',b.duration_seconds,'scenarioCode',s.code,
 'ageUsec',TIMESTAMPDIFF(MICROSECOND,b.recorded_at,UTC_TIMESTAMP(6)))
 FROM chaoslab_m1_core_r3.blade_execution_snapshots b JOIN chaoslab_m1_core_r3.experiment_executions x ON x.id=b.execution_id
 JOIN chaoslab_m1_core_r3.experiments e ON e.id=x.experiment_id JOIN chaoslab_m1_core_r3.fault_scenarios s ON s.id=e.scenario_id
 WHERE e.id='"""+experiment+"';"
def compare_intent(v,p,experiment):
 require(v['experimentId']==experiment and v['status']=='PREPARING' and v['attempt']==1,'unexpected committed execution')
 canonical_id(v['executionId']);require(re.fullmatch('[0-9a-f]{16}',v.get('nativeUid') or '') is not None,'invalid durable native UID')
 expected={'format':'CRI_CPU_V1','targetId':TARGET,'containerId':p['containerId'],'imageId':p['imageId'],'nodeId':p['nodeId'],'stateId':p['stateId'],'toolSha256':p['toolSha256'],'toolVersion':'api3-identified','cpuPercent':10,'durationSeconds':10,'scenarioCode':'CPU_LOAD'}
 require(all(v.get(k)==x for k,x in expected.items()) and 0<=v['ageUsec']<=5000000,'durable intent identity/limits/freshness rejected')
def authorization(p,execution,uid):
 canonical_id(execution);require(re.fullmatch('[0-9a-f]{16}',uid) is not None,'invalid authorization UID')
 t=datetime.now(timezone.utc)
 return {'deployment':'REAL','executable':p['executable'],'policyDigest':POLICY_SHA,'toolSha256':p['toolSha256'],'nsexecSha256':p['nsexecSha256'],'chaosOsSha256':p['chaosOsSha256'],'yamlSha256':p['yamlSha256'],
  'expiresAt':(t+timedelta(seconds=20)).isoformat(timespec='microseconds').replace('+00:00','Z'),'executionId':execution,'nativeUid':uid,'containerId':p['containerId'],'imageId':p['imageId'],
  'toolIdentity':sha(':'.join(p[k] for k in ['toolSha256','nsexecSha256','chaosOsSha256','yamlSha256']).encode()),'stateIdentity':p['stateId'],'nodeId':p['nodeId'],'createdAt':t.isoformat(timespec='microseconds').replace('+00:00','Z')}
def core_result(final):
 # Production owns Core admission. No timestamp/caller/late observer veto.
 return 'M1 CORE PASS' if final is not None and final.get('status')=='SUCCESS' else 'M1 CORE INCOMPLETE'
def wrapper_read(operation,execution=None,uid=None):
 require(operation in ['preflight','status','observe'],'operator may not dispatch native writes')
 q={'operation':operation}
 if execution is not None:canonical_id(execution);require(re.fullmatch('[0-9a-f]{16}',uid) is not None,'invalid recovery UID');q.update(executionId=execution,nativeUid=uid)
 p=subprocess.run(['/usr/sbin/runuser','-u','chaoslab','--','/usr/bin/sudo','-n','--',str(WRAPPER)],input=encode(q),env=ENV,capture_output=True,timeout=12)
 require(p.returncode==0 and not p.stderr and len(p.stdout)<=65536,'readonly privileged transport unavailable')
 value=json.loads(p.stdout);require(value.get('code')=='OK','readonly wrapper rejected');return value
def audits(execution):
 canonical_id(execution)
 raw=mysql("SELECT JSON_OBJECT('operation',operation,'parameters',parameters,'result',result,'occurredAt',occurred_at) FROM "+SCHEMA+".audit_logs WHERE execution_id='"+execution+"' ORDER BY occurred_at,id;")
 return [json.loads(line) for line in raw.splitlines() if line]
def native(uid):
 require(re.fullmatch('[0-9a-f]{16}',uid) is not None,'invalid native record UID')
 c=sqlite3.connect((STATE/'chaosblade.dat').as_uri()+'?mode=ro',uri=True,timeout=3)
 try:
  c.execute('PRAGMA query_only=ON');rows=c.execute('SELECT uid,command,sub_command,status FROM experiment WHERE uid=?',(uid,)).fetchall()
  return {'totalExperiments':c.execute('SELECT count(*) FROM experiment').fetchone()[0],'records':[dict(zip(['uid','command','subCommand','status'],row)) for row in rows]}
 finally:c.close()

def main():
 require(os.geteuid()==0 and len(sys.argv)==1,'administrator required; no argv');os.umask(0o077)
 require(sha((STAGE/'deploy-core-clone-readonly.py').read_bytes())==HELPER_SHA,'approved helper changed')
 h=runpy.run_path(str(STAGE/'deploy-core-clone-readonly.py'));trust=h['trust'];write=h['write'];digest=h['digest']
 require(Path('/etc/hostname').read_text().strip()=='chaoslab-m1-core-r3' and Path('/sys/class/net/ens160/address').read_text().strip()==h['MAC'],'not the approved independent VM')
 for q in [AUDIT,STATE,POLICY,WRAPPER,DEST/'backend.jar']:trust(q)
 require(not os.path.lexists(ATTEMPT) and not os.path.lexists(REPORT),'one-shot attempt already exists; NEVER rerun')
 r={'result':'M1 CORE INCOMPLETE','mergeCommit':MERGE,'createApiAttempts':0,'destroyApiAttempts':0,'realAuthorizationCreated':False,'recoveryCause':'UNKNOWN','physicalRecovery':'UNKNOWN','automaticRetry':False}
 experiment=execution=uid=auth_raw=p=idle=None;future=None;pool=concurrent.futures.ThreadPoolExecutor(max_workers=1)
 def checkpoint():
  tmp=AUDIT/'m1-core-real-result.tmp';require(not os.path.lexists(tmp),'stale result checkpoint');write(tmp,json.dumps(r,indent=2).encode());os.replace(tmp,REPORT)
 def emergency(reason):
  require(p is not None and idle is not None,'no trusted emergency target')
  current=idle['inspect']();require(current['Id']==p['containerId'] and current['Image']==p['imageId'],'emergency target identity rejected')
  r['emergencyContainmentNotRecoveryVerified']=True;r['emergencyReason']=reason;checkpoint()
  stop=subprocess.run(['/usr/bin/docker','--host=unix:///var/run/docker.sock','stop','--time','2',p['containerId']],env=ENV,capture_output=True,timeout=10)
  r['emergencySandboxStopAcknowledged']=stop.returncode==0
 try:
  require(digest(DEST/'backend.jar')==APP_SHA and digest(WRAPPER)==WRAPPER_SHA,'backend/wrapper identity rejected')
  p=json.loads(POLICY.read_bytes());require(sha(encode(p))==POLICY_SHA and p['deployment']=='REAL' and p['nodeId']=='m1-core-r3-executor' and p['stateId']=='m1-core-r3-state' and p['cpuPercent']==10 and p['durationSeconds']==10,'policy rejected')
  prior=json.loads((AUDIT/'m1-core-r3-resume.json').read_bytes());require(prior['passed'] is True and prior['mergeCommit']==MERGE,'readonly acceptance mismatch');pid=prior['javaResult']['pid']
  s=Path('/proc',str(pid),'status').read_text();require('\nUid:\t999\t999\t999\t999\n' in s and '\nGid:\t987\t987\t987\t987\n' in s and os.readlink('/proc/'+str(pid)+'/exe')==h['JAVA'],'Java user/runtime changed')
  for cap in ['CapEff','CapPrm','CapAmb']:require(next(x for x in s.splitlines() if x.startswith(cap+':')).split()[1]=='0000000000000000','Java capability anomaly')
  require(next(x for x in s.splitlines() if x.startswith('Groups:')).split()[1:]==['987'] and h['cmd'](['/usr/bin/id','-G','chaoslab']).strip()==b'987','service groups changed')
  expected_cp=str(DEST/'acceptance-resume.jar')+':'+str(DEST/'app/BOOT-INF/classes')+':'+str(DEST/'app/BOOT-INF/lib/*')+':'+str(DEST/'diagnostic-libs/*')
  argv=Path('/proc',str(pid),'cmdline').read_bytes().decode().split('\0')
  require('-cp' in argv and expected_cp in argv and 'com.chaoslab.engine.infrastructure.blade.M1CoreReadOnlyAcceptance' in argv,'unexpected Java classpath')
  require(digest(DEST/'acceptance-resume.jar')=='bb9cae9068451f7f75677e7bf265664310643798551d3a1e2580330ed1477cc3' and digest(DEST/'app/BOOT-INF/classes/com/chaoslab/engine/infrastructure/blade/ChaosBladeEngine.class')==prior['javaResult']['engineClassSha256'],'loaded deployment class identity changed')
  require(http('/actuator/health')['status']=='UP','backend not UP')
  listeners=[]
  for line in h['cmd'](['/usr/bin/ss','-ltnH']).decode().splitlines():
   local=line.split()[3]
   if not local.endswith(':18080'):continue
   a=ipaddress.ip_address(local.rsplit(':',1)[0].strip('[]'));require(a.is_loopback or isinstance(a,ipaddress.IPv6Address) and a.ipv4_mapped is not None and a.ipv4_mapped.is_loopback,'backend publicly exposed');listeners.append(local)
  require(listeners,'backend listener missing')
  require(mysql('SELECT @@innodb_flush_log_at_trx_commit,@@sync_binlog;')=='1\t1','MySQL durability not confirmed')
  require(mysql('SELECT COUNT(*) FROM '+SCHEMA+'.flyway_schema_history WHERE success=1;')=='11','Flyway state changed')
  for table in ['experiments','experiment_executions','blade_execution_snapshots']:require(mysql('SELECT COUNT(*) FROM '+SCHEMA+'.'+table+';')=='0','new environment already contains experimental state')
  require(h['history']()==(h['ARCHIVE']/'historical-rows.txt').read_text(),'historical terminal rows changed')
  for db in ['chaoslab_m1','chaoslab_m1_r2']:require(sha(h['dump'](db))==digest(h['ARCHIVE']/(db+'.sql')),'historical database changed')
  for name,pin in h['EVIDENCE'].items():require(digest(AUDIT/name)==pin,'historical evidence changed')
  h['clean']();h['no_tool_children'](p)
  require(digest(STAGE/'start-idle.py')==IDLE_SHA,'idle inspector changed');idle=runpy.run_path(str(STAGE/'start-idle.py'))
  v=idle['inspect']()
  if not v['State']['Running']:idle['docker']('start',v['Id']);v=idle['inspect']()
  require(v['State']['Running'],'sandbox not Running');runtime=idle['sample'](v['State']['Pid'])
  pre=wrapper_read('preflight');require(pre['policy']==p and pre['policyDigest']==POLICY_SHA,'fresh root identity mismatch');o=pre['observation']
  require(o['probeReady'] and o['residual']=='CLEAR' and o['cpuPercent']<=1 and o['health']=='UNKNOWN','baseline/readiness rejected')
  require(o['sample']['pid']==runtime['pid'] and o['sample']['startTime']==runtime['startTime'] and o['sample']['cgroup']==runtime['cgroup'],'sandbox runtime identity mismatch')
  for user in ['chaoslab','w']:
   for q in [WRAPPER,POLICY,STATE,Path(p['executable'])]:require(subprocess.run(['/usr/sbin/runuser','-u',user,'--','/usr/bin/test','-w',str(q)],env=ENV,capture_output=True,timeout=3).returncode!=0,'privileged path writable')
  require(subprocess.run(['/usr/sbin/runuser','-u','chaoslab','--','/usr/bin/test','-w','/var/run/docker.sock'],env=ENV,capture_output=True,timeout=3).returncode!=0,'direct Docker socket writable')
  for exe in ['/bin/sh','/bin/bash','/usr/bin/docker','/usr/bin/env','/usr/bin/python3','/usr/bin/systemctl','/usr/bin/systemd-run',p['executable']]:require(subprocess.run(['/usr/sbin/runuser','-u','chaoslab','--','/usr/bin/sudo','-n','-l','--',exe],env=ENV,capture_output=True,timeout=4).returncode!=0,'unexpected sudo permission')
  h['cmd'](['/usr/sbin/visudo','-c']);h['clean']()
  write(ATTEMPT,encode({'approvedScope':'ONE R3 CPU10/count1/timeout10s','reservedAt':now(),'mergeCommit':MERGE,'automaticRetry':False}))
  r.update(preflight=pre,baselineCpuPercent=o['cpuPercent'],sandboxRuntime=runtime,freshPreflightPassed=True,startedAt=now());checkpoint()
  definition=http('/api/v1/experiments',{'name':'M1 Core R3 single approved CPU10','hypothesis':'Direct cgroup fault and same-UID active request followed by physical recovery; cause UNKNOWN','targetId':TARGET,'scenarioId':SCENARIO,'durationSeconds':10,'parameters':{'percent':10}})
  experiment=canonical_id(definition['id']);r['experimentId']=experiment
  require(http('/api/v1/experiments/'+experiment+'/validation',{})['status']=='VALIDATED','validation failed')
  dry=http('/api/v1/experiments/'+experiment+'/dry-run',{});require(dry['accepted'] and dry['experiment']['status']=='READY','dry run rejected')
  h['clean']();r['createApiAttempts']=1;r['startSubmittedAt']=now();checkpoint()
  future=pool.submit(http,'/api/v1/experiments/'+experiment+'/executions',{},'m1-core-once-'+str(uuid.uuid4()))
  deadline=time.monotonic()+12;intent=None
  while time.monotonic()<deadline:
   raw=mysql(intent_query(experiment))
   if raw:require('\n' not in raw,'multiple intents');intent=json.loads(raw);break
   if future.done():break
   time.sleep(.01)
  require(intent is not None,'no committed UID; no authorization created');compare_intent(intent,p,experiment)
  execution=intent['executionId'];uid=intent['nativeUid'];r.update(executionId=execution,nativeUid=uid,committedIntent=intent,commitProof='Independent READ COMMITTED MySQL query with durability1/1 BEFORE authorization');checkpoint()
  h['clean']();auth_raw=encode(authorization(p,execution,uid));write(STATE/'authorization.json',auth_raw)
  fd=os.open(STATE,os.O_RDONLY|os.O_DIRECTORY)
  try:os.fsync(fd)
  finally:os.close(fd)
  r['realAuthorizationCreated']=True;r['authorizationCreatedAt']=now();checkpoint()
  started=future.result(timeout=50);r['createResponse']=started;checkpoint()
  require(started['id']==execution and started.get('engineExperimentId')=='blade-'+execution,'create identity/reference rejected; NO RETRY')
  # Also allow the normal backend's uncertain-create cleanup path, ONCE, without
  # pretending missing CPU proof is a Core pass. There is never another create.
  require(started['status'] in ['RUNNING','CREATE_UNCERTAIN'],'no safely recoverable execution reference')
  require(not os.path.lexists(STATE/'authorization.json'),'authorization not consumed')
  binding=json.loads((STATE/'binding.json').read_bytes());require(binding['executionId']==execution and binding['nativeUid']==uid and binding['policyDigest']==POLICY_SHA,'binding identity rejected')
  r['rootBinding']=binding;r['productionAuditsAfterCreate']=audits(execution)
  r['destroyApiAttempts']=1;r['activeDestroyRequestedAt']=now();checkpoint()
  # Production reads during CPU and commits evidence BEFORE returning RUNNING.
  # No extra operator CPU workload, during sampling delay, or direct native destroy.
  r['backendDestroyResponse']=http('/api/v1/experiments/'+experiment+'/executions/'+execution+'/destroy',{},timeout=50);checkpoint()
 except BaseException as failure:
  r['blocker']=str(failure) if isinstance(failure,RuntimeError) else type(failure).__name__
 finally:
  # Revoke only our unconsumed authorization. Never create another or remove binding.
  if auth_raw is not None and os.path.lexists(STATE/'authorization.json'):
   if (STATE/'authorization.json').read_bytes()==auth_raw:(STATE/'authorization.json').unlink();r['unconsumedAuthorizationRevoked']=True
   else:r['authorizationCleanupUnknown']=True
  if future is not None:
   try:r.setdefault('createResponse',future.result(timeout=55))
   except BaseException as failure:r['createResponseUncertain']=type(failure).__name__
  pool.shutdown(wait=True,cancel_futures=True)
  if execution:
   try:
    final=http('/api/v1/experiments/'+experiment+'/executions/'+execution);r['finalBackendExecution']=final;r['result']=core_result(final)
    r['occupancyRetained']=final['status'] in ['PREPARING','CREATE_UNCERTAIN','RUNNING','DESTROYING','ROLLBACK_FAILED']
    r['productionAudits']=audits(execution)
    physical=[a['parameters'] for a in r['productionAudits'] if a['operation']=='M1_PHYSICAL_RECOVERY']
    cpu=[a['parameters'] for a in r['productionAudits'] if a['operation']=='M1_CPU_OBSERVATION']
    if cpu:r['productionCpuEvidence']=cpu[-1];r['duringCpuPercent']=cpu[-1]['cpuPercent']
    if physical:r['productionPhysicalAssessment']=physical[-1];r['physicalRecovery']=physical[-1]['physicalRecovery']
    rounds=[]
    for line in (WORK/'backend-resume.log').read_text().splitlines():
     if 'M1_RECOVERY_SUMMARY ' not in line:continue
     summary=json.loads(line.split('M1_RECOVERY_SUMMARY ',1)[1])
     if summary.get('executionId')==execution:rounds.append(summary)
    r['recoveryRounds']=rounds;r['recoveryGate']=rounds[-1]['gateOutcome'] if rounds else 'UNKNOWN'
    r['nativeRecord']=native(uid)
    observed=wrapper_read('observe',execution,uid);r['afterObservation']=observed;r['afterCpuPercent']=observed['observation'].get('cpuPercent')
    # Reporting corroboration is NOT an extra admission condition after SUCCESS.
    # Stop only for actual unsafe/unobservable physical state, never replay dispatch.
    if final['status']!='SUCCESS' and (observed['observation'].get('residual') in ['PRESENT','UNKNOWN'] or observed['observation'].get('cpuPercent',999)>1):
     emergency('physical recovery unsafe/unconfirmed after incomplete production result')
   except BaseException as failure:
    r['evidenceExportError']=str(failure) if isinstance(failure,RuntimeError) else type(failure).__name__
    if r.get('result')!='M1 CORE PASS' and auth_raw is not None and os.path.lexists(STATE/'binding.json'):
     try:emergency('dispatched experiment recovery visibility unavailable')
     except BaseException as stop_error:r['emergencyStopError']=type(stop_error).__name__
  r['authorizationAbsent']=not os.path.lexists(STATE/'authorization.json');r['authorizationConsumed']=bool(auth_raw is not None and not os.path.lexists(STATE/'authorization.json') and not r.get('unconsumedAuthorizationRevoked'))
  r['bindingPresent']=os.path.lexists(STATE/'binding.json');r['nativeDbPresent']=os.path.lexists(STATE/'chaosblade.dat')
  try:
   r['historicalRowsUnchanged']=h['history']()==(h['ARCHIVE']/'historical-rows.txt').read_text()
   r['historicalEvidenceUnchanged']=all(digest(AUDIT/name)==pin for name,pin in h['EVIDENCE'].items())
  except BaseException:r['historicalVerificationUnavailable']=True
  r['finishedAt']=now();checkpoint();write(STAGE/'m1-core-real-result.json',json.dumps(r,indent=2).encode(),0o644)
 print(r['result']);print('ONE ROUND ONLY; STOPPED; NO MORE REAL CREATE AUTHORIZED')

if __name__=='__main__':main()
