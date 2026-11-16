"""Exercise sdkmanager arguments without downloading or changing a real SDK."""
import json
import os
import pathlib
import subprocess
import tempfile
import unittest


INSTALLER = pathlib.Path(__file__).with_name('install-sdk.sh')


class SdkInstallTest(unittest.TestCase):
    def invoke(self, mode, fail_install=False):
        with tempfile.TemporaryDirectory() as directory:
            root = pathlib.Path(directory)
            manager = root / 'cmdline-tools/latest/bin/sdkmanager'
            manager.parent.mkdir(parents=True)
            manager.write_text('''#!/usr/bin/env python3
import json, os, sys
with open(os.environ['SDK_TEST_CALLS'], 'a') as stream:
    stream.write(json.dumps(sys.argv[1:]) + '\\n')
if '--install' in sys.argv and os.environ.get('SDK_TEST_FAIL') == '1':
    sys.exit(17)
''')
            manager.chmod(0o755)
            calls = root / 'calls.jsonl'
            result = subprocess.run(
                ['bash', str(INSTALLER), mode], capture_output=True, text=True,
                env={**os.environ, 'ANDROID_HOME': str(root),
                     'SDK_TEST_CALLS': str(calls),
                     'SDK_TEST_FAIL': '1' if fail_install else '0'},
                timeout=10,
            )
            recorded = [json.loads(line) for line in calls.read_text().splitlines()] if calls.exists() else []
            return result, recorded

    def test_build_installs_published_platform_package(self):
        result, calls = self.invoke('build')
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertIn('--licenses', calls[0])
        self.assertIn('platforms;android-37.0', calls[1])
        self.assertNotIn('platforms;android-37', calls[1])
        self.assertIn('ndk;28.2.13676358', calls[1])
        self.assertFalse(any(value.startswith('system-images;') for value in calls[1]))

    def test_device_jobs_install_matching_published_image(self):
        for api, package_api in [('26', '26'), ('30', '30'), ('37', '37.0')]:
            with self.subTest(api=api):
                result, calls = self.invoke(api)
                self.assertEqual(0, result.returncode, result.stderr)
                self.assertIn(f'system-images;android-{package_api};default;x86_64', calls[1])
                self.assertIn('emulator', calls[1])

    def test_install_errors_remain_failures(self):
        result, _ = self.invoke('build', fail_install=True)
        self.assertEqual(17, result.returncode)

    def test_invalid_mode_does_not_modify_sdk(self):
        result, calls = self.invoke('invalid')
        self.assertEqual(2, result.returncode)
        self.assertEqual([], calls)


if __name__ == '__main__':
    unittest.main()
