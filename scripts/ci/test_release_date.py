"""Release reruns derive their build date from immutable source and the tag."""
import os
from pathlib import Path
import subprocess
import tempfile
import unittest


SCRIPT = Path(__file__).with_name('resolve-release-tag.py')
SHA = 'a' * 40


class ReleaseDateTest(unittest.TestCase):
    def resolve(self, tag, committed='2026-11-16T03:00:00Z', expected=SHA):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            fake = root / 'gh'
            fake.write_text('''#!/usr/bin/env python3
import json, os, sys
assert sys.argv[1] == 'api'
path = sys.argv[2]
if '/git/ref/tags/' in path:
    result = {'object': {'type': 'commit', 'sha': 'a' * 40}}
elif '/git/commits/' in path:
    result = {'committer': {'date': os.environ['TEST_COMMITTED']}}
else:
    raise AssertionError(path)
print(json.dumps(result))
''')
            fake.chmod(0o755)
            output = root / 'output'
            result = subprocess.run(['python3', str(SCRIPT)], text=True, capture_output=True, timeout=10,
                env={**os.environ, 'PATH': str(root) + os.pathsep + os.environ['PATH'],
                     'RELEASE_TAG': tag, 'GITHUB_REPOSITORY': 'owner/repo',
                     'RELEASE_COMMIT': expected, 'TEST_COMMITTED': committed, 'GITHUB_OUTPUT': str(output)})
            return result, output.read_text() if output.exists() else ''

    def test_rerun_keeps_tag_date(self):
        result, output = self.resolve('v0.1.01116')
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertIn('build-date=2026-11-16\n', output)

    def test_next_year_and_shanghai_timezone(self):
        result, output = self.resolve('v0.2.00101', '2026-12-31T20:00:00Z')
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertIn('build-date=2027-01-01\n', output)

    def test_invalid_calendar_date_is_rejected(self):
        result, output = self.resolve('v0.1.00230')
        self.assertNotEqual(0, result.returncode)
        self.assertEqual('', output)

    def test_moved_tag_is_rejected(self):
        result, output = self.resolve('v0.1.01116', expected='b' * 40)
        self.assertNotEqual(0, result.returncode)
        self.assertIn('tag moved', result.stderr)
        self.assertEqual('', output)


if __name__ == '__main__':
    unittest.main()
