"""Read-only historical integrity and disabled-harness regression; never a VM runner."""
import hashlib
import pathlib
import runpy
import unittest

ROOT = pathlib.Path(__file__).resolve().parents[3]


class CoreContractTests(unittest.TestCase):
    def test_original_r1_r2_evidence_is_unchanged(self):
        pins = {
            'm1-first-real-20261007.json': '7e8867fcdd2475ecf5c969c0eb6c1bf7539503c9a4ca65d42d0d0178dec5fffa',
            'm1-second-real-20261007.json': 'db66f4ac5df2ccbd1dc09bfd68c9e07389f6b73c9b440ff4385dae9f26909e67',
        }
        for name, digest in pins.items():
            with self.subTest(name=name):
                self.assertEqual(hashlib.sha256((ROOT / 'docs/evidence' / name).read_bytes()).hexdigest(), digest)

    def test_old_post_success_veto_harness_cannot_be_reused(self):
        module = runpy.run_path(str(pathlib.Path(__file__).with_name('second-real-m1.py')))
        with self.assertRaisesRegex(RuntimeError, 'HISTORICAL_R2_HARNESS_DISABLED'):
            module['main']()


if __name__ == '__main__':
    unittest.main()
