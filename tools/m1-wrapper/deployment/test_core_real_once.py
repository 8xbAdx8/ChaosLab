"""New operator tests only; no network, privileged operation, native tool or fault."""
import ast
import pathlib
import runpy
import unittest
import uuid

SOURCE=pathlib.Path(__file__).with_name('core-real-once.py')
M=runpy.run_path(str(SOURCE))

class CoreOnceTests(unittest.TestCase):
 def test_production_success_is_not_vetoed_by_unknown_cause_or_native_time(self):
  self.assertEqual(M['core_result']({'status':'SUCCESS','cause':'UNKNOWN','CreateTime':'late','UpdateTime':'late'}),'M1 CORE PASS')
  for status in ['CREATE_UNCERTAIN','PREPARING','RUNNING','DESTROYING','ROLLBACK_FAILED','FAILED']:
   self.assertEqual(M['core_result']({'status':status}),'M1 CORE INCOMPLETE')
 def test_no_direct_privileged_writes_are_available(self):
  for operation in ['create-cpu','destroy','create','sh']:
   with self.assertRaisesRegex(RuntimeError,'operator may not dispatch native writes'):M['wrapper_read'](operation)
 def test_new_program_is_not_a_historical_harness(self):
  text=SOURCE.read_text();self.assertNotIn('second-real-m1.py',text);self.assertNotIn('strace',text)
  tree=ast.parse(text)
  imports=[x for x in ast.walk(tree) if isinstance(x,ast.Call) and isinstance(x.func,ast.Attribute) and x.func.attr=='run_path']
  self.assertEqual(len(imports),2) # approved clone helper and immutable idle inspector only
  self.assertIn("not os.path.lexists(ATTEMPT)",text);self.assertIn("write(ATTEMPT",text)
 def test_authorization_has_full_identity_and_short_separate_expiry(self):
  p={'executable':'/fixed','containerId':'a'*64,'imageId':'sha256:'+'b'*64,'nodeId':'m1-core-r3-executor','stateId':'m1-core-r3-state',
     'toolSha256':'1'*64,'nsexecSha256':'2'*64,'chaosOsSha256':'3'*64,'yamlSha256':'4'*64}
  a=M['authorization'](p,str(uuid.uuid4()),'0123456789abcdef')
  self.assertEqual(a['deployment'],'REAL');self.assertEqual(a['executable'],p['executable']);self.assertEqual(a['policyDigest'],M['POLICY_SHA'])
  for key in ['toolSha256','nsexecSha256','chaosOsSha256','yamlSha256','containerId','imageId','nodeId']:self.assertEqual(a[key],p[key])
  self.assertNotEqual(a['expiresAt'],a['createdAt']);self.assertEqual(a['stateIdentity'],p['stateId'])
  for bad in ['ABCDEF0123456789',';shell','0123',None]:
   with self.assertRaises((RuntimeError,TypeError)):M['authorization'](p,str(uuid.uuid4()),bad)
 def test_main_requires_admin_before_any_operation(self):
  import unittest.mock
  with unittest.mock.patch.dict(M['main'].__globals__,os=unittest.mock.Mock(geteuid=lambda:999)):
   with self.assertRaisesRegex(RuntimeError,'administrator required'):M['main']()

if __name__=='__main__':unittest.main()
