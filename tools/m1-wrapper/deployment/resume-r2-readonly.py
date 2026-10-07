"""Resume ONLY the stopped R2 readonly acceptance after the audited umask issue.
No database creation, state rotation, archive modification, authorization or fault.
Only normal backend artifact directory traversal modes are corrected to root0755.
"""
import json
import os
from pathlib import Path
import runpy
import signal
import stat
import subprocess
import sys
import time
import urllib.parse
import urllib.request
import zipfile

STAGE=Path('/home/w/chaoslab-m1-2d3-20261004')
PREP_SHA='605d2be66675aee737ce5d3d8da3700b50c8a5f6b27f144d3a50dd2dbd9d15aa'
MANIFEST_SHA='b641a077fa9d77c006fb6d247d272f45e80e52cae9dd55b15c6e97b53780be86'
HELPER_SHA='72c813452c54bbee93e1481c2ae0551ff4ef994f67082868bf0fd1b82a5e72de'

def main():
 if os.geteuid()!=0 or len(sys.argv)!=1:raise RuntimeError('administrator required; no arguments')
 os.umask(0o077)
 import hashlib
 if hashlib.sha256((STAGE/'prepare-second-m1.py').read_bytes()).hexdigest()!=PREP_SHA:raise RuntimeError('executed initializer changed; STOP')
 h=runpy.run_path(str(STAGE/'prepare-second-m1.py'))
 if h['digest'](STAGE/'deploy-java-readonly.py')!=HELPER_SHA:raise RuntimeError('reviewed helper changed')
 base=runpy.run_path(str(STAGE/'deploy-java-readonly.py'));trust=base['trust'];write=base['write']
 require=h['require'];digest=h['digest'];mysql=h['mysql'];command=h['command'];encode=h['encode'];sha=h['sha']
 STATE=h['STATE'];POLICY=h['POLICY'];DEST=h['DEST'];WORK=h['WORK'];CONFIG=h['CONFIG'];ARCHIVE=h['ARCHIVE'];AUDIT=h['AUDIT'];ENV=h['ENV'];JAVA=h['JAVA'];WRAPPER=h['WRAPPER']
 for path in [STATE,POLICY,DEST,DEST/'diagnostic-libs',AUDIT,ARCHIVE,ARCHIVE/'manifest.json']:trust(path)
 require(digest(ARCHIVE/'manifest.json')==MANIFEST_SHA,'archive manifest mismatch')
 manifest=json.loads((ARCHIVE/'manifest.json').read_bytes())
 for name,item in manifest['files'].items():require(digest(ARCHIVE/name)==item['sha256'],'R1 archive changed')
 require(manifest['firstM1']=='INCOMPLETE' and manifest['recoveryVerified'] is False,'R1 outcome changed')
 require(sha(mysql(h['R1_QUERY']).encode())==manifest['files']['r1-history-row.json']['sha256'],'R1 database changed')
 require(digest(AUDIT/'first-real-m1-result.json')==h['EVIDENCE_SHA'],'R1 evidence changed')
 prior=json.loads((AUDIT/'second-m1-preparation.json').read_bytes())
 require(prior.get('ready') is False and prior.get('failedPhase')=='actual R2 Java readonly acceptance'
  and prior.get('blocker')=='R2 backend stopped; inspect restricted report/log','not the audited resume condition')
 require(not os.path.lexists(AUDIT/'r2-readonly-resume-attempt.json') and not os.path.lexists(AUDIT/'second-m1-readonly-resume.json'),'resume already attempted; STOP')
 require(not os.path.lexists(WORK/'java-readonly-result.json'),'Java already produced a report; inspect without rerunning')
 h['active_clean']()
 p=json.loads(POLICY.read_bytes());old=json.loads((ARCHIVE/'policy-r1.json').read_bytes())
 require(p==h['rotate_policy'](old),'R2 scope/generation changed')
 require(digest(WRAPPER)==h['WRAPPER_SHA'] and digest(h['OLD_DEST']/'backend.jar')==h['OLD_APP_SHA']
  and digest(DEST/'backend.jar')==h['APP_SHA'] and digest(DEST/'acceptance.jar')==h['HARNESS_SHA'],'artifact pin changed')
 for name,pin in h['LIBS'].items():require(digest(DEST/'diagnostic-libs'/name)==pin,'diagnostic pin changed')
 for proc in Path('/proc').iterdir():
  if not proc.name.isdigit():continue
  try:require(os.readlink(proc/'exe')!=JAVA,'Java already running; do not launch a second backend')
  except FileNotFoundError:pass
 # Exact root0755 intended deployment modes; never modify any privileged-boundary path.
 modes={str(path):stat.S_IMODE(path.stat().st_mode) for path in [DEST,DEST/'diagnostic-libs']}
 require(all(mode in [0o700,0o755] for mode in modes.values()),'unexpected artifact directory mode')
 startup_log=(WORK/'backend.log').read_text()
 require(len(startup_log)<=65536 and 'Error opening zip file or JAR manifest missing' in startup_log,'startup error not the audited readability failure')
 rules=[Path('/etc/sudoers'),*sorted(Path('/etc/sudoers.d').iterdir())];sudo_before={str(path):digest(path) for path in rules}
 require(command(['/usr/bin/id','-G','chaoslab']).strip()==b'987','service groups changed')
 values={}
 for line in (CONFIG/'application.properties').read_text().splitlines():
  key,sep,value=line.partition('=');require(sep and key not in values,'invalid restricted config');values[key]=value
 require(values['spring.datasource.username']=='chaoslab_m1_r2' and values['spring.datasource.url'].startswith('jdbc:mysql://127.0.0.1:3306/chaoslab_m1_r2?')
  and values['chaoslab.blade.state-id']=='m1-real-state-r2','R2 config mismatch')
 write(AUDIT/'r2-readonly-resume-attempt.json',encode({'initializerNotRepeated':True,'realCreateAuthorized':False,'beforeDirectoryModes':modes}))
 r={'ready':False,'status':'SECOND M1 NOT READY','commit':h['COMMIT'],'firstM1':'INCOMPLETE','firstRecoveryVerified':False,
  'realAuthorizationCreated':False,'realCreateInvoked':False,'backendArtifactSha256':h['APP_SHA'],'harnessSha256':h['HARNESS_SHA'],
  'r1Archive':prior['r1Archive'],'startupCause':'UMASK_077_REMOVED_ARTIFACT_DIRECTORY_TRAVERSAL','beforeDirectoryModes':modes}
 process=None
 try:
  for path in [DEST,DEST/'diagnostic-libs']:os.chmod(path,0o755);trust(path)
  require(command(['/usr/sbin/runuser','-u','chaoslab','--','/usr/bin/test','-r',str(DEST/'diagnostic-libs/byte-buddy-agent-1.18.10.jar')])==b'','ordinary backend still cannot read artifact')
  idle=runpy.run_path(str(STAGE/'start-idle.py'));require(digest(STAGE/'start-idle.py')==h['IDLE_SHA'],'idle inspector changed')
  v=idle['inspect']()
  if not v['State']['Running']:idle['docker']('start',v['Id']);v=idle['inspect']()
  require(v['State']['Running'],'sandbox not running');runtime=idle['sample'](v['State']['Pid'])
  require(h['root_preflight']()['policy']==p,'R2 root preflight mismatch')
  cp=str(DEST/'acceptance.jar')+':'+str(DEST/'app/BOOT-INF/classes')+':'+str(DEST/'app/BOOT-INF/lib/*')+':'+str(DEST/'diagnostic-libs/*')
  env={'PATH':'/usr/bin:/bin','HOME':'/nonexistent','LANG':'C','LC_ALL':'C','CHAOSLAB_DB_URL':values['spring.datasource.url'],
   'CHAOSLAB_DB_USERNAME':values['spring.datasource.username'],'CHAOSLAB_DB_PASSWORD':values['spring.datasource.password']}
  args=['/usr/sbin/runuser','-u','chaoslab','--',JAVA,'-Xms64m','-Xmx512m','-javaagent:'+str(DEST/'diagnostic-libs/byte-buddy-agent-1.18.10.jar'),
   '-cp',cp,'com.chaoslab.engine.infrastructure.blade.M1R2ReadOnlyAcceptance','--spring.config.additional-location=file:'+str(CONFIG/'application.properties')]
  logfile=WORK/'backend-resume.log';write(logfile,b'',0o600,987,999)
  print('R1 archive/generation verified unchanged; corrected only new backend directory read/traverse modes.',flush=True)
  print('Starting a1cd739 ordinary Java backend; real readonly chain and memory-only settling proof.',flush=True)
  with logfile.open('ab') as log:process=subprocess.Popen(args,env=env,cwd=WORK,stdout=log,stderr=subprocess.STDOUT,start_new_session=True)
  deadline=time.monotonic()+200;lineages=[];report_file=WORK/'java-readonly-result.json'
  while time.monotonic()<deadline and not report_file.exists():
   require(process.poll() is None,'R2 backend stopped; inspect restricted resume report/log')
   for proc in Path('/proc').iterdir():
    if not proc.name.isdigit():continue
    try:
     if os.readlink(proc/'exe')==str(WRAPPER) and proc.stat().st_uid==0:
      ancestor=int(proc.name);lineage=[]
      for _ in range(16):
       lineage.append(ancestor);ancestor=int(Path('/proc',str(ancestor),'stat').read_text().rsplit(') ',1)[1].split()[1])
       if ancestor<=1:break
      lineages.append(lineage)
    except FileNotFoundError:pass
   time.sleep(.1)
  require(report_file.exists() and report_file.stat().st_size<=32768,'R2 report missing/oversized')
  java=json.loads(report_file.read_bytes());r['javaResult']=java
  require(java.get('passed') is True and java.get('commit')==h['COMMIT'],'R2 Java acceptance failed')
  pid=int(java['pid']);status=Path('/proc',str(pid),'status').read_text()
  require('\nUid:\t999\t999\t999\t999\n' in status and '\nGid:\t987\t987\t987\t987\n' in status
   and os.readlink('/proc/'+str(pid)+'/exe')==JAVA,'JVM identity mismatch')
  with zipfile.ZipFile(DEST/'backend.jar') as z:engine_sha=sha(z.read('BOOT-INF/classes/com/chaoslab/engine/infrastructure/blade/ChaosBladeEngine.class'))
  source=urllib.parse.urlparse(java['engineCodeSource'])
  require(java['engineClassSha256']==engine_sha and source.scheme=='file' and Path(source.path)==DEST/'app/BOOT-INF/classes','loaded engine identity mismatch')
  require(cp in Path('/proc',str(pid),'cmdline').read_bytes().decode().split('\0'),'fixed JVM invocation mismatch')
  require(any(pid in lineage for lineage in lineages),'actual Java-to-root-wrapper ancestry missing')
  require(java['policy']==p and java['policyDigest']==sha(encode(p)),'Java/R2 policy mismatch')
  require(json.loads(h['CLIENT'].open('http://127.0.0.1:18080/actuator/health',timeout=5).read(4096)).get('status')=='UP','HTTP not UP')
  for table in ['experiments','experiment_executions','blade_execution_snapshots']:
   require(mysql('SELECT count(*) FROM chaoslab_m1_r2.'+table+';')=='0','R2 experimental rows exist')
  require(sha(mysql(h['R1_QUERY']).encode())==manifest['files']['r1-history-row.json']['sha256'] and digest(AUDIT/'first-real-m1-result.json')==h['EVIDENCE_SHA'],'R1 history/evidence changed')
  for name,item in manifest['files'].items():require(digest(ARCHIVE/name)==item['sha256'],'R1 archive changed')
  h['active_clean']();pre=h['root_preflight']();require(pre['policy']==p,'final root policy mismatch')
  current=idle['inspect']();sample=idle['sample'](current['State']['Pid'])
  require(current['State']['Running'] and all(sample[k]==runtime[k] for k in ['pid','startTime','cgroup']),'sandbox runtime changed')
  require(not base['tool_processes'](p),'known tool/helper residual')
  require(digest(WRAPPER)==h['WRAPPER_SHA'] and sudo_before=={str(path):digest(path) for path in rules}
   and digest(DEST/'backend.jar')==h['APP_SHA'] and digest(h['OLD_DEST']/'backend.jar')==h['OLD_APP_SHA'],'boundary or artifact changed')
  negative={}
  for exe in ['/bin/sh','/bin/bash','/usr/bin/docker','/usr/bin/env','/usr/bin/python3','/usr/bin/systemctl','/usr/bin/systemd-run',p['executable']]:
   negative[exe]=subprocess.run(['/usr/sbin/runuser','-u','chaoslab','--','/usr/bin/sudo','-n','-l','--',exe],env=ENV,capture_output=True,timeout=5).returncode!=0
  require(all(negative.values()),'unexpected sudo authorization')
  for user in ['chaoslab','w']:
   for path in [ARCHIVE,STATE,POLICY,WRAPPER,Path(p['executable'])]:
    require(subprocess.run(['/usr/sbin/runuser','-u',user,'--','/usr/bin/test','-w',str(path)],env=ENV,capture_output=True,timeout=3).returncode!=0,'ordinary privileged path write access')
  command(['/usr/sbin/visudo','-c']);h['active_clean']()
  r.update(ready=True,status='SECOND M1 READY',actualRootWrapperObserved=True,http='127.0.0.1:18080',databaseScope='127.0.0.1:3306/chaoslab_m1_r2; schema-only',
   r1DatabasePreserved=True,r1EvidenceUnchanged=True,r2ExperimentalRows=0,policyDigest=sha(encode(p)),stateId=p['stateId'],
   wrapperSha256=h['WRAPPER_SHA'],finalPreflight=pre,finalSandboxSample=sample,negativeSudo=negative,
   authorizationAbsent=True,bindingAbsent=True,nativeDbAbsent=True,sudoUnchanged=True)
 except BaseException as e:
  r['blocker']=str(e) if isinstance(e,RuntimeError) else type(e).__name__
  if process is not None and process.poll() is None:
   os.killpg(process.pid,signal.SIGTERM)
   try:process.wait(timeout=20)
   except subprocess.TimeoutExpired:r['backendStopUnconfirmed']=True
 finally:
  raw=json.dumps(r,indent=2).encode();write(AUDIT/'second-m1-readonly-resume.json',raw);write(STAGE/'second-m1-readonly-resume.json',raw,0o644)
 print(r['status']);print('WAITING FOR USER CONFIRMATION; SECOND REAL CREATE NOT AUTHORIZED')

if __name__=='__main__':main()
