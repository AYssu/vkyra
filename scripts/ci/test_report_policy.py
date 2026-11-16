"""Regression cases: a zero shell exit must not turn broken test evidence green."""
import pathlib
import tempfile
import unittest
from report_policy import check_instrumentation, junit_count


class AndroidEvidenceTest(unittest.TestCase):
    @staticmethod
    def passing(count=2):
        return ''.join(f'INSTRUMENTATION_STATUS: test=case{i}\nINSTRUMENTATION_STATUS_CODE: 1\nINSTRUMENTATION_STATUS_CODE: 0\n' for i in range(count)) + f'OK ({count} tests)\nINSTRUMENTATION_CODE: -1\n'

    def test_real_finished_contract_accepts_expected_cases(self):
        self.assertEqual(2, check_instrumentation(self.passing(), 2))

    def test_failed_ignored_assumed_and_crashed_runs_cannot_pass_with_ok_text(self):
        for status in (-2, -3, -4):
            with self.subTest(status=status), self.assertRaises(ValueError):
                check_instrumentation(self.passing().replace('INSTRUMENTATION_STATUS_CODE: 0', f'INSTRUMENTATION_STATUS_CODE: {status}', 1), 2)
        for marker in ('FAILURES!!!', 'Process crashed', 'INSTRUMENTATION_FAILED', 'INSTRUMENTATION_ABORTED'):
            with self.subTest(marker=marker), self.assertRaises(ValueError):
                check_instrumentation(self.passing() + marker, 2)

    def test_partial_filtered_or_unfinished_runs_do_not_count_as_full_acceptance(self):
        for text in (self.passing(1), self.passing().replace('INSTRUMENTATION_STATUS_CODE: 1\n', '', 1), self.passing().replace('INSTRUMENTATION_CODE: -1', '')):
            with self.subTest(text=text), self.assertRaises(ValueError):
                check_instrumentation(text, 2)


class JUnitEvidenceTest(unittest.TestCase):
    def check(self, xml):
        with tempfile.TemporaryDirectory() as directory:
            path = pathlib.Path(directory) / 'TEST-real.xml'
            path.write_text(xml)
            return junit_count([path])

    def test_passing_suite(self):
        self.assertEqual(1, self.check('<testsuite tests="1" failures="0" errors="0" skipped="0"><testcase classname="Suite" name="case"/></testsuite>'))

    def test_failure_skip_error_detected_even_when_suite_counters_claim_success(self):
        for tag in ('failure', 'error', 'skipped'):
            with self.subTest(tag=tag), self.assertRaises(ValueError):
                self.check(f'<testsuite tests="1" failures="0"><testcase name="case"><{tag}/></testcase></testsuite>')

    def test_missing_empty_partial_and_counter_failures(self):
        with self.assertRaises(ValueError):
            junit_count([])
        for xml in ('<testsuite tests="0"/>', '<testsuite tests="2"><testcase name="one"/></testsuite>', '<testsuite tests="1" failures="1"><testcase name="bad"/></testsuite>'):
            with self.subTest(xml=xml), self.assertRaises(ValueError):
                self.check(xml)


if __name__ == '__main__':
    unittest.main()
