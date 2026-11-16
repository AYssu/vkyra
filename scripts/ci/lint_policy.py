#!/usr/bin/env python3
"""Exact inherited lint warning allowance; line changes do not hide new warnings.

The allowance comes from an actual lint XML, never from CI failures automatically.
Editing it is a source change requiring maintainer review and a reason in the PR.
"""
import collections
import json
import pathlib
import sys
import xml.etree.ElementTree as ET

ROOT = pathlib.Path(__file__).resolve().parents[2]
ALLOWANCE = pathlib.Path(__file__).with_name('lint-baseline.json')


def location_key(value):
    path = value.replace('\\', '/')
    project = str(ROOT).replace('\\', '/') + '/'
    if path.startswith(project):
        return path[len(project):]
    marker = '/caches/modules-2/files-2.1/'
    if marker in path:
        return '$GRADLE_CACHE/modules-2/files-2.1/' + path.split(marker, 1)[1]
    # Relative paths produced by a different lint CLI remain relative.
    if not pathlib.PurePosixPath(path).is_absolute():
        return path
    return path  # Unknown absolute locations must fail, rather than collapse by basename.


def warnings(report):
    issues = list(ET.parse(report).getroot().iter('issue'))
    errors = [issue.get('id') for issue in issues if issue.get('severity') in ('Error', 'Fatal')]
    if errors:
        raise ValueError('Lint errors: ' + ', '.join(errors))
    counter = collections.Counter()
    for issue in issues:
        if issue.get('severity') == 'Warning':
            counter[(issue.get('id'), issue.get('message'), tuple(sorted(location_key(x.get('file', '')) for x in issue.findall('location'))))] += 1
    return counter


def check_lint(report):
    actual = warnings(report)
    data = json.loads(ALLOWANCE.read_text())
    allowed = collections.Counter({(item['id'], item['message'], tuple(item['locations'])): item['count'] for item in data['warnings']})
    new = actual - allowed
    if new:
        details = '\n'.join(f'{count}x {key[0]} @ {", ".join(key[2])}: {key[1]}' for key, count in new.items())
        raise ValueError('New lint warnings require a fix or an explicitly reviewed allowance change:\n' + details)
    return dict(collections.Counter({issue: sum(count for key, count in actual.items() if key[0] == issue) for issue in {key[0] for key in actual}}))


if __name__ == '__main__':
    if len(sys.argv) == 3 and sys.argv[1] == '--record-reviewed':
        report = pathlib.Path(sys.argv[2])
        current = warnings(report)
        data = {'schema': 1, 'policy': 'Only inherited exact issue + message + file fingerprints are allowed. Counts may decrease. New warnings fail CI. Changes require an explicit maintainer-reviewed explanation; never auto-update in CI.',
                'source': 'Actual app lintX86_64Debug XML at initial public CI setup, 2026-11-16. See docs/MAINTENANCE.md for triage and upgrade policy.',
                'totalWarnings': sum(current.values()),
                'warnings': [{'id': key[0], 'message': key[1], 'locations': list(key[2]), 'count': count} for key, count in sorted(current.items())]}
        ALLOWANCE.write_text(json.dumps(data, ensure_ascii=False, indent=2) + '\n')
        print(f'Recorded {sum(current.values())} actual warnings; review this source diff before merging.')
    elif len(sys.argv) == 2:
        print(json.dumps(check_lint(pathlib.Path(sys.argv[1])), indent=2))
    else:
        raise SystemExit('Usage: lint_policy.py REPORT.xml | --record-reviewed REPORT.xml')
