#!/usr/bin/env python3
"""Fail early for unresolved LFS payloads or reintroduced non-R8 code protection."""
import pathlib
import re
import sys

root = pathlib.Path(__file__).resolve().parents[2]
assets = sorted(root.glob('app/src/*/assets/*.tar.xz'))
assets.append(root / 'app/src/main/assets/demo.apk')
errors = []
if len(assets) != 7:
    errors.append(f'Expected six toolchain archives and one APK template; found {len(assets)}. Update this gate when changing the asset manifest.')
for asset in assets:
    if not asset.is_file():
        errors.append(f'Missing asset: {asset.relative_to(root)}')
        continue
    with asset.open('rb') as stream:
        magic = stream.read(128)
    expected = b'PK\x03\x04' if asset.suffix == '.apk' else b'\xfd7zXZ\x00'
    if not magic.startswith(expected):
        errors.append(f'Not a real {asset.suffix} payload: {asset.relative_to(root)}. Run git lfs install && git lfs pull.')
# These names designate build-time string encryption/extra obfuscation, not APK signing
# or Keystore-backed credential encryption, which remain legitimate app features.
build_files = [root / 'build.gradle', root / 'settings.gradle', root / 'gradle/libs.versions.toml']
for module in ('app', 'terminal-emulator', 'terminal-view', 'termux-shared'):
    build_files.extend([root / module / 'build.gradle', root / module / 'proguard-rules.pro'])
for path in build_files:
    if not path.is_file():
        continue
    text = path.read_text()
    for pattern in [r'(?i)stringfog', r'(?i)blackobfuscator', r'(?m)^\s*-\s*(?:class|package)?obfuscationdictionary\b']:
        if re.search(pattern, text):
            errors.append(f'Extra code protection found in {path.relative_to(root)}: {pattern}')
if errors:
    sys.exit('\n'.join(errors))
print('Source checks: real LFS assets present; no additional string encryption/obfuscation layer.')
