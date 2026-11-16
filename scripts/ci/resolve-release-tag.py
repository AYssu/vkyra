#!/usr/bin/env python3
"""Resolve one validated remote tag to its immutable commit via authenticated gh."""
import json
import os
import re
import subprocess

name = os.environ.get('RELEASE_TAG', '')
repository = os.environ.get('GITHUB_REPOSITORY', '')
if not re.fullmatch(r'v\d+\.\d+\.\d+(?:[-+][A-Za-z0-9.-]+)?', name):
    raise SystemExit('Use an existing version tag such as v1.1.2')
if not re.fullmatch(r'[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+', repository):
    raise SystemExit('Invalid repository')

def api(path):
    return json.loads(subprocess.check_output(['gh', 'api', path], text=True))

obj = api(f'repos/{repository}/git/ref/tags/{name}')['object']
for _ in range(10):
    if obj['type'] == 'commit':
        break
    if obj['type'] != 'tag' or not re.fullmatch(r'[a-f0-9]{40}', obj['sha']):
        raise SystemExit('Tag did not resolve to a commit')
    obj = api(f'repos/{repository}/git/tags/{obj["sha"]}')['object']
else:
    raise SystemExit('Too many nested annotated tags')
commit = obj['sha']
if not re.fullmatch(r'[a-f0-9]{40}', commit):
    raise SystemExit('Invalid commit SHA')
expected = os.environ.get('RELEASE_COMMIT')
if expected and commit != expected:
    raise SystemExit('Release tag moved after CI; refusing to attach tested APKs to different source')
if os.environ.get('GITHUB_OUTPUT'):
    with open(os.environ['GITHUB_OUTPUT'], 'a') as output:
        output.write(f'tag={name}\ncommit={commit}\n')
print(f'{name}: {commit}')
