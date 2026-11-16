#!/usr/bin/env python3
"""Validate and copy one exact Debug APK into its standalone download directory."""
import argparse
import hashlib
import json
import pathlib
import re
import shutil
import subprocess
import zipfile

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--kind', choices=('full', 'lite'), required=True)
parser.add_argument('--abi', choices=('aarch64', 'x86_64'), required=True)
args = parser.parse_args()
root = pathlib.Path(__file__).resolve().parents[2]
source = root / f'app/build/outputs/apk/{args.abi}/debug'
metadata = json.loads((source / 'output-metadata.json').read_text())
element, = metadata['elements']
version = element['versionName']
if not re.fullmatch(r'\d+\.\d+\.\d+', version):
    raise SystemExit('Invalid APK version')
apk = source / element['outputFile']
with zipfile.ZipFile(apk) as archive:
    names = set(archive.namelist())
    archives = {name for name in names if name.endswith('.tar.xz')}
    expected = {f'assets/clang-21-android-{args.abi}.tar.xz',
                f'assets/zsh-5.9-android-{args.abi}.tar.xz', f'assets/build-tools-{args.abi}.tar.xz'}
    if archives != (expected if args.kind == 'full' else set()):
        raise SystemExit(f'Unexpected toolchain payloads in {args.kind} APK: {sorted(archives)}')
    if not {'assets/clang-version', 'assets/demo.apk'}.issubset(names):
        raise SystemExit('APK lost toolchain version metadata or the APK export template')
    if args.kind == 'lite' and apk.stat().st_size - sum(info.compress_size for info in archive.infolist()) > 4 * 1048576:
        raise SystemExit('Lite APK retains excessive unused ZIP bytes; rebuild final APK outputs')
commit = subprocess.check_output(['git', 'rev-parse', 'HEAD'], cwd=root, text=True).strip()
destination = root / f'build/debug-downloads/{args.abi}-{args.kind}'
if destination.exists():
    shutil.rmtree(destination)
destination.mkdir(parents=True)
target = destination / f'Vkyra-{version}-debug-{args.abi}-{args.kind}.apk'
shutil.copyfile(apk, target)
(destination / 'COMMIT.txt').write_text(commit + '\n')
with target.open('rb') as stream:
    digest = hashlib.file_digest(stream, 'sha256').hexdigest()
(destination / 'SHA256SUMS.txt').write_text(digest + '  ' + target.name + '\n')
(destination / 'BUILD.json').write_text(json.dumps({'commit': commit, 'versionName': version,
    'versionCode': element['versionCode'], 'abi': args.abi, 'kind': args.kind,
    'bundledToolchainArchives': sorted(archives), 'bytes': target.stat().st_size}, indent=2) + '\n')
print(f'{target.name}: {target.stat().st_size / 1048576:.1f} MiB')
