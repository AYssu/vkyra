#!/usr/bin/env python3
"""Sign the exact CI-tested unsigned APK payloads; never sign a freshly untested build."""
import argparse
import base64
import hashlib
import json
import os
import pathlib
import re
import subprocess
import tempfile
import zipfile

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--inputs', required=True, type=pathlib.Path)
parser.add_argument('--output', required=True, type=pathlib.Path)
parser.add_argument('--tag', required=True)
parser.add_argument('--commit', required=True)
args = parser.parse_args()
if not re.fullmatch(r'v\d+\.\d+\.\d+(?:[-+][A-Za-z0-9.-]+)?', args.tag):
    raise SystemExit('Expected a version tag such as v1.1.2')
required = ('VKYRA_KEYSTORE_BASE64', 'VKYRA_KEYSTORE_PASSWORD', 'VKYRA_KEY_ALIAS', 'VKYRA_KEY_PASSWORD')
missing = [key for key in required if not os.environ.get(key)]
if missing:
    raise SystemExit('Missing release secrets: ' + ', '.join(missing))
inputs = args.inputs.resolve()
manifest = json.loads((inputs / 'build/ci-inputs/manifest.json').read_text())
if manifest['sourceCommit'] != args.commit or manifest['dirty']:
    raise SystemExit('Release requires a clean, exact CI-tested commit')
sdk = pathlib.Path(os.environ.get('ANDROID_HOME') or os.environ['ANDROID_SDK_ROOT'])
tools = sdk / 'build-tools/36.0.0'
args.output.mkdir(parents=True, exist_ok=True)
if any(args.output.iterdir()):
    raise SystemExit('Release output must be empty')

def run(argv):
    return subprocess.check_output([str(part) for part in argv], stderr=subprocess.STDOUT, text=True)

def payloads(path):
    with zipfile.ZipFile(path) as archive:
        if len(archive.namelist()) != len(set(archive.namelist())):
            raise SystemExit('Duplicate ZIP entries')
        return {name: hashlib.sha256(archive.read(name)).hexdigest() for name in archive.namelist()
                if not re.fullmatch(r'META-INF/(?:MANIFEST\.MF|[^/]+\.(?:SF|RSA|DSA|EC))', name, re.IGNORECASE)}

signed = []
with tempfile.TemporaryDirectory(prefix='vkyra-release-key-', dir=os.environ.get('RUNNER_TEMP')) as temp:
    key = pathlib.Path(temp) / 'release.keystore'
    key.write_bytes(base64.b64decode(os.environ['VKYRA_KEYSTORE_BASE64'], validate=True))
    key.chmod(0o600)
    for abi in ('aarch64', 'x86_64'):
        matches = list((inputs / 'app/build/outputs/apk' / abi / 'release').glob('*.apk'))
        if len(matches) != 1:
            raise SystemExit(f'Expected one CI-built {abi} release APK')
        source = matches[0]
        relative = str(source.relative_to(inputs))
        if hashlib.sha256(source.read_bytes()).hexdigest() != manifest['buildArtifacts'].get(relative):
            raise SystemExit(f'CI artifact hash mismatch: {relative}')
        badging = run([tools / 'aapt2', 'dump', 'badging', source])
        package = re.search(r"package: name='([^']+)' versionCode='(\d+)' versionName='([^']+)'", badging)
        if not package or package.group(1) != 'com.vkyra.code' or package.group(3) != args.tag[1:]:
            raise SystemExit('Tag/version/applicationId mismatch')
        if 'application-debuggable' in badging:
            raise SystemExit('A debuggable APK cannot be published as a release')
        destination = args.output / f'Vkyra-{args.tag[1:]}-{abi}.apk'
        run([tools / 'apksigner', 'sign', '--ks', key, '--ks-key-alias', os.environ['VKYRA_KEY_ALIAS'],
             '--ks-pass', 'env:VKYRA_KEYSTORE_PASSWORD', '--key-pass', 'env:VKYRA_KEY_PASSWORD',
             '--out', destination, source])
        verification = run([tools / 'apksigner', 'verify', '--verbose', '--print-certs', destination])
        if 'Android Debug' in verification or 'Vkyra CI Test Only' in verification:
            raise SystemExit('A debug/CI certificate cannot be used for a formal release')
        (args.output / f'{destination.name}.certificate.txt').write_text(verification)
        if payloads(source) != payloads(destination):
            raise SystemExit('Release signing changed the CI-tested APK payload')
        signed.append(destination)
        mapping = inputs / 'app/build/outputs/mapping' / f'{abi}Release/mapping.txt'
        if hashlib.sha256(mapping.read_bytes()).hexdigest() != manifest['buildArtifacts'].get(str(mapping.relative_to(inputs))):
            raise SystemExit('CI mapping hash mismatch')
        # Mapping stays in CI artifacts for maintainers, not in the public Release assets.
(args.output / 'SHA256SUMS').write_text(''.join(f'{hashlib.sha256(path.read_bytes()).hexdigest()}  {path.name}\n' for path in signed))
(args.output / 'provenance.json').write_text(json.dumps({'commit': args.commit, 'tag': args.tag,
    'sourceTreeSha256': manifest['sourceTreeSha256'], 'inputArtifactHashes': manifest['buildArtifacts'],
    'signedApks': {path.name: hashlib.sha256(path.read_bytes()).hexdigest() for path in signed},
    'signingPreservedTestedPayloads': True}, indent=2) + '\n')
print('Signed both exact CI-tested release APKs; key removed from temporary storage.')
