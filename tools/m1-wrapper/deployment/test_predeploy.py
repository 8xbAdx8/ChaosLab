import unittest
from pathlib import Path
import runpy

folder = Path(__file__).resolve().parent
source = folder / 'predeploy-fixed.py'
if not source.exists():
    source = folder / 'predeploy.py'
no_new_privileges = runpy.run_path(str(source))['no_new_privileges']


class SecurityOptionTests(unittest.TestCase):
    def test_enabled_spellings(self):
        for value in ['no-new-privileges', 'no-new-privileges:true', 'no-new-privileges=true']:
            with self.subTest(value=value):
                self.assertTrue(no_new_privileges([value]))

    def test_fail_closed(self):
        for value in [None, [], 'no-new-privileges=true', [None],
                      ['no-new-privileges=false'], ['no-new-privileges:false'],
                      ['no-new-privileges=trueX'], ['no-new-privileges=1'],
                      ['no-new-privileges=true', 'no-new-privileges=false'],
                      ['no-new-privileges=true', 'no-new-privileges=true']]:
            with self.subTest(value=value):
                self.assertFalse(no_new_privileges(value))


if __name__ == '__main__':
    unittest.main()
