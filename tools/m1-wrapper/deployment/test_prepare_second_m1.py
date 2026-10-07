"""Portable pure/AST tests. Never import or execute the privileged installer."""
import ast
import hashlib
import json
from pathlib import Path
import unittest

SOURCE=Path(__file__).with_name('prepare-second-m1.py')
TEXT=SOURCE.read_text()
TREE=ast.parse(TEXT)
NAMES={'require','sha','encode','rotate_policy','r1_evidence'}
CONTEXT={'hashlib':hashlib,'json':json}
for node in TREE.body:
 if isinstance(node,ast.Assign) and len(node.targets)==1 and isinstance(node.targets[0],ast.Name) and node.targets[0].id in {'R1_POLICY_SHA','R2_STATE_ID','EXECUTION','UID'}:
  CONTEXT[node.targets[0].id]=ast.literal_eval(node.value)
exec(compile(ast.Module(body=[n for n in TREE.body if isinstance(n,ast.FunctionDef) and n.name in NAMES],type_ignores=[]),str(SOURCE),'exec'),CONTEXT)
EVIDENCE=json.loads((SOURCE.parents[3]/'docs/evidence/m1-first-real-20261007.json').read_text())

class R2PreparationContract(unittest.TestCase):
 def test_generation_is_only_policy_change(self):
  p=EVIDENCE['preflight']['policy']
  r=CONTEXT['rotate_policy'](p)
  self.assertEqual(r['stateId'],'m1-real-state-r2')
  self.assertEqual({k:v for k,v in r.items() if k!='stateId'},{k:v for k,v in p.items() if k!='stateId'})
  self.assertEqual(p['stateId'],'m1-real-state')
 def test_target_tool_limits_and_generation_tampering_rejected(self):
  p=EVIDENCE['preflight']['policy']
  for key in ['executable','stateDirectory','containerId','imageId','toolSha256','nodeId','cpuPercent','durationSeconds','stateId']:
   with self.subTest(key=key),self.assertRaises(RuntimeError):CONTEXT['rotate_policy'](dict(p,**{key:'wrong'}))
 def test_first_result_must_remain_incomplete_unverified_and_single_attempt(self):
  CONTEXT['r1_evidence'](EVIDENCE)
  for key,value in [('result','M1 PASS'),('recoveryVerified',True),('nativeUid','0'*16),('executionId','wrong'),('createApiAttempts',2)]:
   with self.subTest(key=key),self.assertRaises(RuntimeError):CONTEXT['r1_evidence'](dict(EVIDENCE,**{key:value}))
 def test_no_fault_authorization_or_r1_deletion_operation(self):
  for word in ['authorization.json\',','blade create','docker exec','docker update','docker run']:
   self.assertNotIn(word,TEXT)
  self.assertNotIn('shutil.rmtree',TEXT)
  self.assertFalse(any(isinstance(n,ast.Call) and isinstance(n.func,ast.Attribute) and n.func.attr in {'unlink','remove','rmdir'} for n in ast.walk(TREE)))
  self.assertNotIn('DELETE FROM',TEXT)
  self.assertNotIn('UPDATE chaoslab_m1.',TEXT)
  self.assertNotIn('DROP DATABASE',TEXT)
  calls=[n for n in ast.walk(TREE) if isinstance(n,ast.Call)]
  self.assertFalse(any(any(k.arg=='shell' and isinstance(k.value,ast.Constant) and k.value.value is True for k in n.keywords) for n in calls))
 def test_archive_verified_before_recoverable_rotation(self):
  self.assertLess(TEXT.index("archive_file('chaoslab_m1.sql'"),TEXT.index('os.rename(STATE,'))
  self.assertLess(TEXT.index("'archive changed during readonly verification'"),TEXT.index('os.rename(STATE,'))
  self.assertIn("os.rename(STATE,ARCHIVE/'retired-active-state')",TEXT)
  self.assertIn("CREATE DATABASE chaoslab_m1_r2",TEXT)
  self.assertIn("ON chaoslab_m1_r2.*",TEXT)
  self.assertIn("'R1 history/evidence changed'",TEXT)
 def test_http_and_wrapper_paths_readonly(self):
  self.assertIn("b'{\"operation\":\"preflight\"}'",TEXT)
  self.assertNotIn('/executions',TEXT)
  self.assertFalse(any(isinstance(n,ast.Constant) and isinstance(n.value,str) and n.value.startswith('/api/') and '/destroy' in n.value for n in ast.walk(TREE)))
  self.assertNotIn('create-cpu',TEXT)
  self.assertIn('SECOND REAL CREATE NOT AUTHORIZED',TEXT)
 def test_resume_cannot_reset_database_or_generation(self):
  resume=SOURCE.with_name('resume-r2-readonly.py').read_text()
  ast.parse(resume)
  for forbidden in ['CREATE DATABASE','DROP DATABASE','os.rename','os.replace','authorization.json','create-cpu','DELETE FROM','UPDATE chaoslab_m1.']:
   self.assertNotIn(forbidden,resume)
  self.assertIn('for path in [DEST,DEST/\'diagnostic-libs\']:os.chmod(path,0o755)',resume)
  self.assertIn("'R1 database changed'",resume)
  self.assertIn("'actual Java-to-root-wrapper ancestry missing'",resume)

if __name__=='__main__':unittest.main()
