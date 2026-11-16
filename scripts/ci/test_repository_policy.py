"""Exercise protection previews with a local fake gh; never contact GitHub."""
import json
import os
import pathlib
import subprocess
import tempfile
import unittest


CONFIGURATOR = pathlib.Path(__file__).resolve().parents[1] / 'github/configure-repository.py'


class RepositoryPolicyTest(unittest.TestCase):
    def preview(self, *flags, admin=True, conclusion='success', previous=None):
        with tempfile.TemporaryDirectory() as directory:
            root = pathlib.Path(directory)
            gh = root / 'gh'
            gh.write_text('''#!/usr/bin/env python3
import json, os, sys
assert sys.argv[1:4] == ['api', '--method', 'GET'], 'preview must not mutate GitHub'
path = sys.argv[4]
if path == 'repos/owner/repo':
    data = {'permissions': {'admin': os.environ['TEST_ADMIN'] == '1'}}
elif path.endswith('/protection'):
    data = json.loads(os.environ['TEST_PROTECTION'])
elif '/check-runs?' in path:
    data = {'check_runs': [{'name': 'CI required', 'conclusion': os.environ['TEST_CONCLUSION'], 'app': {'slug': 'github-actions', 'id': 15368}}]}
else:
    data = {'commit': {'sha': 'test-commit'}}
print(json.dumps(data))
''')
            gh.chmod(0o755)
            result = subprocess.run(
                ['python3', str(CONFIGURATOR), 'owner/repo', '--dry-run', *flags],
                capture_output=True, text=True, timeout=10,
                env={**os.environ, 'PATH': str(root) + os.pathsep + os.environ['PATH'],
                     'TEST_ADMIN': '1' if admin else '0', 'TEST_CONCLUSION': conclusion,
                     'TEST_PROTECTION': json.dumps(previous or {})},
            )
            data = json.JSONDecoder().raw_decode(result.stdout)[0] if result.returncode == 0 else None
            return result, data

    def test_default_policy_requires_admins_and_real_ci(self):
        result, data = self.preview()
        self.assertEqual(0, result.returncode, result.stderr)
        protection = data['branchProtection']
        self.assertTrue(protection['enforce_admins'])
        self.assertTrue(protection['required_status_checks']['strict'])
        self.assertIn({'context': 'CI required', 'app_id': 15368}, protection['required_status_checks']['checks'])
        self.assertEqual(1, protection['required_pull_request_reviews']['required_approving_review_count'])
        self.assertFalse(protection['allow_force_pushes'])
        self.assertFalse(protection['allow_deletions'])

    def test_requested_owner_bypass_still_requires_code_owner_review(self):
        result, data = self.preview('--allow-admin-bypass', '--require-code-owner-reviews')
        self.assertEqual(0, result.returncode, result.stderr)
        protection = data['branchProtection']
        self.assertFalse(protection['enforce_admins'])
        self.assertTrue(protection['required_pull_request_reviews']['require_code_owner_reviews'])
        self.assertTrue(protection['required_pull_request_reviews']['dismiss_stale_reviews'])
        self.assertTrue(protection['required_conversation_resolution'])

    def test_previous_stronger_reviews_and_checks_are_preserved(self):
        previous = {'required_pull_request_reviews': {'required_approving_review_count': 2, 'require_code_owner_reviews': True},
                    'required_status_checks': {'checks': [{'context': 'Other gate', 'app_id': 123}]}}
        result, data = self.preview(previous=previous)
        self.assertEqual(0, result.returncode, result.stderr)
        protection = data['branchProtection']
        self.assertEqual(2, protection['required_pull_request_reviews']['required_approving_review_count'])
        self.assertTrue(protection['required_pull_request_reviews']['require_code_owner_reviews'])
        self.assertIn({'context': 'Other gate', 'app_id': 123}, protection['required_status_checks']['checks'])

    def test_no_admin_or_failed_ci_prevents_configuration(self):
        for options in ({'admin': False}, {'conclusion': 'failure'}):
            with self.subTest(options=options):
                result, data = self.preview(**options)
                self.assertNotEqual(0, result.returncode)
                self.assertIsNone(data)


if __name__ == '__main__':
    unittest.main()
