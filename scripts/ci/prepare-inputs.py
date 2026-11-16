#!/usr/bin/env python3
"""Package exact host-built APKs for the device matrix; no release key is included."""
import hashlib
import json
import pathlib
import os
import re
import shutil
import subprocess

root = pathlib.Path(__file__).resolve().parents[2]
out = root / 'build/ci-inputs'
out.mkdir(parents=True, exist_ok=True)
patterns = {
    'debug.apk': 'app/build/outputs/apk/x86_64/debug/*.apk',
    'androidTest.apk': 'app/build/outputs/apk/androidTest/x86_64/debug/*.apk',
    'release-smoke.apk': 'release-smoke/build/outputs/apk/debug/*.apk',
    'release-unsigned.apk': 'app/build/outputs/apk/x86_64/release/*.apk',
    'mapping.txt': 'app/build/outputs/mapping/x86_64Release/mapping.txt',
}
revision = subprocess.run(['git', 'rev-parse', '--verify', 'HEAD'], cwd=root, text=True, capture_output=True)
if revision.returncode and __import__('os').environ.get('GITHUB_ACTIONS') == 'true':
    raise SystemExit('CI requires a checked-out commit')
commit = revision.stdout.strip() if revision.returncode == 0 else 'UNCOMMITTED'
expected = os.environ.get('VKYRA_CI_EXPECTED_SHA') or os.environ.get('GITHUB_SHA')
if os.environ.get('GITHUB_ACTIONS') == 'true' and expected and commit != expected:
    raise SystemExit(f'Checked-out commit {commit} differs from the requested CI commit {expected}')
status = subprocess.run(['git', 'status', '--porcelain', '--untracked-files=normal'], cwd=root, capture_output=True, text=True, check=True)
source_hashes = {}
for directory, subdirs, names in os.walk(root):
    subdirs[:] = [name for name in subdirs if name not in {'.git', '.gradle', '.private', 'build', '.cxx', '.idea', '.code-review-graph', '.codex', '.agents'}]
    for name in names:
        path = pathlib.Path(directory) / name
        if path.suffix in {'.java', '.c', '.h', '.mk', '.gradle', '.toml', '.xml', '.pro', '.sh', '.py', '.yml', '.yaml'} or name == 'gradle-wrapper.properties':
            source_hashes[str(path.relative_to(root))] = hashlib.sha256(path.read_bytes()).hexdigest()
manifest = {'sourceCommit': commit, 'dirty': bool(status.stdout.strip()), 'sourceFileHashes': source_hashes, 'sha256': {}}
manifest['sourceTreeSha256'] = hashlib.sha256(json.dumps(source_hashes, sort_keys=True).encode()).hexdigest()
for name, pattern in patterns.items():
    paths = list(root.glob(pattern))
    if len(paths) != 1:
        raise SystemExit(f'Expected exactly one {pattern}; found {len(paths)}')
    shutil.copyfile(paths[0], out / name)
    manifest['sha256'][name] = hashlib.sha256((out / name).read_bytes()).hexdigest()
# This project uses ordinary JUnit4 tests without generated/parameterized device cases.
# Keeping a source inventory here catches an interrupted/partially filtered instrument run.
count = sum(len(re.findall(r'@Test\b', path.read_text())) for path in (root / 'app/src/androidTest/java').rglob('*.java'))
if count < 1:
    raise SystemExit('No Android tests found')
manifest['androidTestCount'] = count
manifest['buildArtifacts'] = {str(path.relative_to(root)): hashlib.sha256(path.read_bytes()).hexdigest() for parent in (root / 'app/build/outputs/apk', root / 'app/build/outputs/mapping') for path in parent.rglob('*') if path.is_file() and path.suffix in {'.apk', '.txt'}}
(out / 'manifest.json').write_text(json.dumps(manifest, indent=2) + '\n')
print(f'Prepared {count} Android tests and exact debug/release inputs in {out}')
