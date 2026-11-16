"""Check warning additions, repeated instances and portable fingerprints."""
import json
import pathlib
import tempfile
import unittest
from unittest.mock import patch
import lint_policy


class LintAllowanceTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = pathlib.Path(self.temp.name)
        self.report = self.root / 'lint.xml'
        self.baseline = self.root / 'baseline.json'
        self.baseline.write_text(json.dumps({'warnings': [{'id': 'Existing', 'message': 'old', 'locations': ['app/src/main/A.java'], 'count': 1}]}))
        self.patch = patch.object(lint_policy, 'ALLOWANCE', self.baseline)
        self.patch.start()
        self.addCleanup(self.patch.stop)

    def check(self, body):
        self.report.write_text('<issues>' + body + '</issues>')
        return lint_policy.check_lint(self.report)

    def test_existing_warning_and_removed_warning_are_accepted(self):
        self.assertEqual({'Existing': 1}, self.check('<issue id="Existing" message="old" severity="Warning"><location file="app/src/main/A.java" line="999"/></issue>'))
        self.assertEqual({}, self.check(''))

    def test_new_location_message_or_issue_is_not_hidden_by_baseline(self):
        for issue, message, file in [('New', 'old', 'app/src/main/A.java'), ('Existing', 'changed', 'app/src/main/A.java'), ('Existing', 'old', 'app/src/main/B.java')]:
            with self.subTest(issue=issue, message=message, file=file), self.assertRaises(ValueError):
                self.check(f'<issue id="{issue}" message="{message}" severity="Warning"><location file="{file}"/></issue>')

    def test_duplicate_warning_count_increase_is_rejected(self):
        item = '<issue id="Existing" message="old" severity="Warning"><location file="app/src/main/A.java"/></issue>'
        with self.assertRaises(ValueError):
            self.check(item + item)

    def test_error_never_becomes_an_allowed_warning(self):
        with self.assertRaises(ValueError):
            self.check('<issue id="Existing" message="old" severity="Error"><location file="app/src/main/A.java"/></issue>')

    def test_gradle_cache_location_is_portable_but_retains_artifact_identity(self):
        suffix = 'org.example/library/1.2/abc/library-1.2.jar'
        self.assertEqual(lint_policy.location_key('/home/one/.gradle/caches/modules-2/files-2.1/' + suffix), lint_policy.location_key('/home/two/.gradle/caches/modules-2/files-2.1/' + suffix))
        self.assertNotEqual(lint_policy.location_key('/unknown/one/F.java'), lint_policy.location_key('/unknown/two/F.java'))


if __name__ == '__main__':
    unittest.main()
