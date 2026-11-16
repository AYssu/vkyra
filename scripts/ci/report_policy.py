"""Strict parsers for actual JUnit and Android runner results."""
import pathlib
import re
import xml.etree.ElementTree as ET


def junit_count(reports):
    reports = list(reports)
    if not reports:
        raise ValueError('JUnit reports are missing')
    count = 0
    for path in reports:
        suite = ET.parse(path).getroot()
        if suite.tag != 'testsuite':
            raise ValueError(f'Unexpected JUnit root in {path}')
        cases = list(suite.findall('testcase'))
        if int(suite.get('tests', '-1')) != len(cases):
            raise ValueError(f'Partial JUnit report in {path}')
        if any(int(suite.get(key, '0')) != 0 for key in ('failures', 'errors', 'skipped')):
            raise ValueError(f'Non-passing JUnit suite in {path}')
        for case in cases:
            if any(case.find(tag) is not None for tag in ('failure', 'error', 'skipped')):
                raise ValueError(f'Non-passing test in {path}: {case.get("classname")}.{case.get("name")}')
        count += len(cases)
    if count == 0:
        raise ValueError('JUnit reports contain no tests')
    return count


def check_instrumentation(text, expected_count):
    if expected_count < 1:
        raise ValueError('Expected test count must be positive')
    if not re.search(rf'\bOK \({expected_count} tests?\)', text):
        raise ValueError(f'Expected {expected_count} completed passing Android tests')
    if any(value in text for value in ('FAILURES!!!', 'Process crashed', 'INSTRUMENTATION_FAILED', 'INSTRUMENTATION_ABORTED')):
        raise ValueError('Android runner reported a failure')
    statuses = [int(value) for value in re.findall(r'INSTRUMENTATION_STATUS_CODE: (-?\d+)', text)]
    # Ignored and assumption-failed tests use -3/-4, and am can exit zero on failure.
    if statuses.count(0) != expected_count or statuses.count(1) != expected_count or any(value not in (0, 1, 2) for value in statuses):
        raise ValueError('Failed, skipped or partial Android test status stream')
    if not re.search(r'^INSTRUMENTATION_CODE: -1\s*$', text, re.MULTILINE):
        raise ValueError('Android runner did not finish normally')
    return expected_count
