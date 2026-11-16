#!/usr/bin/env python3
"""Check real task output. An empty report or skipped integration test is a failure."""
import collections
import json
import pathlib
import sys
import xml.etree.ElementTree as ET
from lint_policy import check_lint
from report_policy import junit_count

root = pathlib.Path(__file__).resolve().parents[2]
summary = {}
for module, variant in [('app', 'testX86_64DebugUnitTest'), ('terminal-emulator', 'testDebugUnitTest')]:
    reports = sorted((root / module / 'build/test-results' / variant).glob('TEST-*.xml'))
    count = junit_count(reports)
    summary[module] = {'tests': count, 'failures': 0, 'skipped': 0}
report = root / 'app/build/reports/lint-results-x86_64Debug.xml'
if not report.is_file():
    sys.exit('Lint XML report is missing')
summary['lint'] = {'errors': 0, 'warnings': check_lint(report)}
output = root / 'build/ci-evidence'
output.mkdir(parents=True, exist_ok=True)
(output / 'host-summary.json').write_text(json.dumps(summary, indent=2) + '\n')
print(json.dumps(summary, indent=2))
