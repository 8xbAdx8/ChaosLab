"""Pure/AST checks; never import old root helpers or run HTTP/auth/Blade."""
import ast
import json
from pathlib import Path
import runpy
import shutil
import subprocess
import sys
import tempfile
import unittest

SOURCE = Path(__file__).with_name('second-real-m1.py')
M = runpy.run_path(str(SOURCE))
WRAPPER = M['WRAPPER']


def write_line(epoch, envelope):
    raw = json.dumps(envelope, separators=(',', ':')) + '\n'
    return f'{epoch:.6f} write(1, {json.dumps(raw)}, {len(raw.encode())}) = {len(raw.encode())}'


class SecondM1Tests(unittest.TestCase):
    def test_historical_harness_cannot_execute_or_veto_a_future_success(self):
        with self.assertRaisesRegex(RuntimeError, 'HISTORICAL_R2_HARNESS_DISABLED'):
            M['main']()  # Raises before root checks, imports, tracing, SQL or HTTP.

    @unittest.skipUnless(sys.platform == 'linux' and shutil.which('strace'), 'Linux strace format fixture only')
    def test_real_strace_format_with_unprivileged_printf_only(self):
        # Never sudo or run the production wrapper: a fixed ordinary printf child.
        parser = M['trace_envelopes']
        globals_ = parser.__globals__
        prior = globals_['WRAPPER']
        try:
            globals_['WRAPPER'] = '/usr/bin/printf'
            with tempfile.TemporaryDirectory(prefix='m1-r2-trace-parser-') as temp:
                prefix = str(Path(temp) / 'pipe')
                result = subprocess.run(['/usr/bin/strace', '-ff', '-ttt', '-s', '65536', '-e', 'trace=execve,write,clone,clone3',
                                         '-o', prefix, '/usr/bin/printf', '%s\n', '{"version":1,"code":"OK"}'],
                                        capture_output=True, timeout=5)
                self.assertEqual(result.returncode, 0)
                rows = parser({int(p.suffix[1:]): p.read_text() for p in Path(temp).glob('pipe.*')})
                self.assertEqual([r['envelope'] for r in rows], [{'version': 1, 'code': 'OK'}])
        finally:
            globals_['WRAPPER'] = prior

    def test_wrapper_thread_stdout_only(self):
        root = f'100.000001 execve("{WRAPPER}", ["{WRAPPER}"], 0x0 /* 3 vars */) = 0\n'
        root += '100.000002 clone(child_stack=NULL, flags=CLONE_VM|CLONE_THREAD|CLONE_SIGHAND) = 81\n'
        envelope = {'version': 1, 'code': 'OK', 'observation': {'residual': 'CLEAR'}}
        files = {80: root, 81: write_line(101, envelope), 99: write_line(102, envelope)}
        rows = M['trace_envelopes'](files)
        self.assertEqual(len(rows), 1)
        self.assertEqual(rows[0]['tid'], 81)
        self.assertEqual(rows[0]['envelope'], envelope)

    def test_truncated_or_short_write_not_claimed(self):
        root = f'100.000001 execve("{WRAPPER}", [], 0x0 /* 3 vars */) = 0\n'
        root += '100.000002 write(1, "{\\"version\\":1}"..., 70000) = 70000\n'
        root += '100.000003 write(1, "{}", 2) = 1\n'
        self.assertEqual(M['trace_envelopes']({80: root}), [])

    def test_rounds_fresh_status_not_reused_and_identity_checked(self):
        p = {'nodeId': 'n', 'containerId': 'c', 'imageId': 'i'}
        def observation(residual):
            return {'executionId': 'e', 'nativeUid': 'u', 'nodeId': 'n', 'containerId': 'c', 'imageId': 'i', 'residual': residual, 'health': 'HEALTHY'}
        status = {'response': {'result': {'Uid': 'u', 'Status': 'Destroyed', 'UpdateTime': 'x', 'CreateTime': 'y'}}}
        rows = [{'traceEpoch': 2, 'envelope': status}, {'traceEpoch': 3, 'envelope': {'observation': observation('PRESENT')}},
                {'traceEpoch': 4, 'envelope': status}, {'traceEpoch': 5, 'envelope': {'observation': observation('CLEAR')}}]
        result = M['settling_rows'](rows, 'e', 'u', p, '1970-01-01T00:00:01Z')
        self.assertEqual([r['observation']['residual'] for r in result], ['PRESENT', 'CLEAR'])
        self.assertEqual([r['engineStatus']['traceEpoch'] for r in result], [2, 4])
        bad = {'traceEpoch': 6, 'envelope': {'observation': dict(observation('CLEAR'), nativeUid='wrong')}}
        with self.assertRaises(RuntimeError):
            M['settling_rows'](rows + [bad], 'e', 'u', p, '1970-01-01T00:00:01Z')

    def test_unbound_preflight_not_counted_as_gate_round(self):
        rows = [{'traceEpoch': 2, 'envelope': {'observation': {'residual': 'CLEAR', 'health': 'UNKNOWN'}}}]
        self.assertEqual(M['settling_rows'](rows, 'e', 'u', {}, '1970-01-01T00:00:01Z'), [])

    def test_single_start_destroy_and_no_direct_wrapper_create_destroy(self):
        tree = ast.parse(SOURCE.read_text())
        submits = [n for n in ast.walk(tree) if isinstance(n, ast.Call) and isinstance(n.func, ast.Attribute) and n.func.attr == 'submit']
        self.assertEqual(len(submits), 1)
        destroy_http = [n for n in ast.walk(tree) if isinstance(n, ast.Call) and isinstance(n.func, ast.Attribute)
                        and n.func.attr == 'http' and n.args and isinstance(n.args[0], ast.BinOp)
                        and isinstance(n.args[0].right, ast.Constant) and n.args[0].right.value == '/destroy']
        self.assertEqual(len(destroy_http), 1)
        wrappers = [n for n in ast.walk(tree) if isinstance(n, ast.Call) and isinstance(n.func, ast.Attribute) and n.func.attr == 'wrapper']
        self.assertFalse(any(n.args[0].value in ['create-cpu', 'destroy'] for n in wrappers))

    def test_clean_preflight_and_commit_precede_authorization(self):
        text = SOURCE.read_text()
        self.assertLess(text.index('first.compare_intent(intent'), text.index("write(STATE / 'authorization.json'"))
        self.assertLess(text.index('readonlyTraceVerifiedBeforeAuthorization'), text.index('start_future = pool.submit'))
        self.assertIn('second-real-m1-attempt.json', text)
        self.assertIn('marker', text)
        self.assertIn("chaoslab_m1_r2.", text)
        self.assertNotIn('UPDATE chaoslab_m1.', text)
        self.assertNotIn('DELETE FROM', text)
        self.assertNotIn('CREATE DATABASE', text)
        self.assertIn('r1_unchanged()', text)


if __name__ == '__main__':
    unittest.main()
