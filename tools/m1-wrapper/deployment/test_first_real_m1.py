"""Pure/AST checks only: no root, HTTP, SQL, authorization files or Blade."""
import ast
from pathlib import Path
import runpy
import unittest

SOURCE=Path(__file__).with_name('first-real-m1.py')
M=runpy.run_path(str(SOURCE))


class SingleAttemptContract(unittest.TestCase):
    def setUp(self):
        self.policy={'containerId':'a'*64,'imageId':'sha256:'+'b'*64,'nodeId':'m1-executor','stateId':'m1-real-state','toolSha256':'c'*64}
        self.experiment='11111111-1111-4111-8111-111111111111'
        self.intent={'executionId':'22222222-2222-4222-8222-222222222222','nativeUid':'0123456789abcdef',
                     'experimentId':self.experiment,'status':'PREPARING','attempt':1,'format':'CRI_CPU_V1',
                     'targetId':M['TARGET'],'containerId':self.policy['containerId'],'imageId':self.policy['imageId'],
                     'nodeId':'m1-executor','stateId':'m1-real-state','toolSha256':self.policy['toolSha256'],
                     'toolVersion':'api3-identified','cpuPercent':10,'durationSeconds':10,'scenarioCode':'CPU_LOAD','ageUsec':100}

    def test_committed_identity_matches(self):
        M['compare_intent'](self.intent,self.policy,self.experiment)

    def test_wrong_or_noncommitted_identity_denied(self):
        for k,v in [('nativeUid','ABCDEF0123456789'),('status','RUNNING'),('attempt',2),('cpuPercent',20),
                    ('durationSeconds',30),('containerId','d'*64),('stateId','other'),('format','DOCKER_CPU_V1'),
                    ('scenarioCode','NETWORK_DELAY'),('ageUsec',3000001),('ageUsec',-1)]:
            with self.subTest(k=k,v=v), self.assertRaises(RuntimeError):
                M['compare_intent'](dict(self.intent,**{k:v}),self.policy,self.experiment)

    def test_unsafe_sql_identifier_rejected(self):
        with self.assertRaises((ValueError,RuntimeError)):
            M['intent_query']("';DROP DATABASE chaoslab_m1;--")

    def test_only_one_backend_start_site_and_no_direct_native_create(self):
        tree=ast.parse(SOURCE.read_text())
        submits=[n for n in ast.walk(tree) if isinstance(n,ast.Call) and isinstance(n.func,ast.Attribute) and n.func.attr=='submit']
        self.assertEqual(len(submits),1)
        wrappers=[n for n in ast.walk(tree) if isinstance(n,ast.Call) and isinstance(n.func,ast.Name) and n.func.id=='wrapper']
        self.assertFalse(any(n.args and isinstance(n.args[0],ast.Constant) and n.args[0].value=='create-cpu' for n in wrappers))
        self.assertIn('first-real-m1-attempt.json',SOURCE.read_text())
        self.assertIn('independent MySQL READ COMMITTED connection',SOURCE.read_text())


if __name__=='__main__':
    unittest.main()
