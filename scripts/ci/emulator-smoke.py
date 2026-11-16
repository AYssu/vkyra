#!/usr/bin/env python3
"""Actual bundled-toolchain, full debug, and separately installed R8 release gates.

Refuses physical/unowned devices. Release is copied and locally test-signed solely for
instrumentation. Its non-signature ZIP payloads and installed APK hash are verified;
this local test artifact is never a distributable release.
"""
import argparse
import hashlib
import json
import os
import pathlib
import re
import subprocess
import time
import zipfile
from report_policy import check_instrumentation

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--serial', required=True)
parser.add_argument('--api', required=True, type=int, choices=(26, 30, 37))
parser.add_argument('--inputs', type=pathlib.Path, required=True)
parser.add_argument('--output', type=pathlib.Path, required=True)
parser.add_argument('--test-keystore', type=pathlib.Path, help='Optional existing local DEBUG key, to preserve an owned AVD app installation')
parser.add_argument('--test-key-alias', default='androiddebugkey')
args = parser.parse_args()
owned_avds = {('emulator-5556', api): f'vkyra_ci_api{api}' for api in (26, 30, 37)}
owned_avds.update({('emulator-5580', 30): 'vkyra_test_only', ('emulator-5582', 26): 'vkyra_test_only_api26', ('emulator-5584', 37): 'vkyra_test_only_api37'})
expected_avd = owned_avds.get((args.serial, args.api))
if expected_avd is None:
    parser.error('Serial/API must identify a dedicated Vkyra CI or acceptance AVD')
sdk = pathlib.Path(os.environ.get('ANDROID_HOME') or os.environ.get('ANDROID_SDK_ROOT') or '/not-configured')
adb = sdk / 'platform-tools/adb'
tools = sdk / 'build-tools/36.0.0'
output = args.output.resolve()
output.mkdir(parents=True, exist_ok=True)
inputs = args.inputs.resolve()
manifest = json.loads((inputs / 'manifest.json').read_text())
for name, expected in manifest['sha256'].items():
    if pathlib.Path(name).name != name or hashlib.sha256((inputs / name).read_bytes()).hexdigest() != expected:
        raise SystemExit(f'Input artifact hash mismatch: {name}')

def command(argv, name=None, timeout=180):
    completed = subprocess.run([str(part) for part in argv], stdout=subprocess.PIPE, stderr=subprocess.STDOUT, timeout=timeout)
    if name:
        (output / name).write_bytes(completed.stdout)
    if completed.returncode:
        raise RuntimeError(f'{argv[0]} failed ({completed.returncode}): {completed.stdout.decode(errors="replace")[-6000:]}')
    return completed.stdout.decode(errors='replace')

def device(*argv, name=None, timeout=180):
    return command([adb, '-s', args.serial, *argv], name=name, timeout=timeout)

def instrumentation(name, class_name, count, timeout=1800, package='com.vkyra.code.test'):
    options = ['-e', 'class', class_name] if class_name else []
    text = device('shell', 'am', 'instrument', '-w', '-r', *options,
                  package + '/androidx.test.runner.AndroidJUnitRunner', name=name, timeout=timeout)
    check_instrumentation(text, count)

# Boot is bounded; wait-for-device without a timeout can otherwise consume a runner forever.
deadline = time.monotonic() + 360
while True:
    probe = subprocess.run([str(adb), '-s', args.serial, 'shell', 'getprop', 'sys.boot_completed'], capture_output=True, text=True, timeout=15)
    if probe.returncode == 0 and probe.stdout.strip() == '1':
        break
    if time.monotonic() > deadline:
        raise SystemExit('Dedicated emulator did not finish booting within 360 seconds')
    time.sleep(2)
avd = device('emu', 'avd', 'name', name='avd.txt').splitlines()[0].strip()
if avd != expected_avd:
    raise SystemExit(f'Refusing unowned AVD: {avd}')
actual_api = device('shell', 'getprop', 'ro.build.version.sdk').strip()
if actual_api != str(args.api):
    raise SystemExit(f'Expected API {args.api}, got {actual_api}')
for scale in ('window_animation_scale', 'transition_animation_scale', 'animator_duration_scale'):
    device('shell', 'settings', 'put', 'global', scale, '0')
device('shell', 'input', 'keyevent', '82')
# A fresh, job-owned certificate allows the same instrumentation APK to target the
# shrunk release without adding debuggable=true or changing any class/resource payload.
owns_key = args.test_keystore is None
key = output / 'ci-test-only.p12' if owns_key else args.test_keystore.resolve()
key_alias = 'ci-test-only' if owns_key else args.test_key_alias
if owns_key:
    if key.exists():
        raise SystemExit('Output directory already contains a test key; use a fresh evidence directory')
    command(['keytool', '-genkeypair', '-keystore', key, '-storetype', 'PKCS12', '-storepass', 'android',
             '-keypass', 'android', '-alias', key_alias, '-keyalg', 'RSA', '-keysize', '2048', '-validity', '2',
             '-dname', 'CN=Vkyra CI Test Only'], name='test-key-generation.txt')
elif not key.is_file():
    raise SystemExit('Existing local debug test keystore does not exist')

def payloads(path):
    with zipfile.ZipFile(path) as archive:
        if len(archive.namelist()) != len(set(archive.namelist())):
            raise RuntimeError('Duplicate ZIP entry in APK')
        return {name: hashlib.sha256(archive.read(name)).hexdigest() for name in archive.namelist()
                if not re.fullmatch(r'META-INF/(?:MANIFEST\.MF|[^/]+\.(?:SF|RSA|DSA|EC))', name, re.IGNORECASE)}

def sign(source_name, destination_name):
    source, destination = inputs / source_name, output / destination_name
    command([tools / 'apksigner', 'sign', '--ks', key, '--ks-key-alias', key_alias, '--ks-pass', 'pass:android',
             '--key-pass', 'pass:android', '--out', destination, source], name=destination_name + '.sign.txt')
    command([tools / 'apksigner', 'verify', '--verbose', '--print-certs', destination], name=destination_name + '.verify.txt')
    if payloads(source) != payloads(destination):
        raise RuntimeError(f'Signing modified APK payloads: {source_name}')
    return destination

try:
    debug = sign('debug.apk', 'debug-ci-test.apk')
    test = sign('androidTest.apk', 'androidTest-ci-test.apk')
    release = sign('release-unsigned.apk', 'release-ci-test.apk')
    release_driver = sign('release-smoke.apk', 'release-smoke-ci-test.apk')
    device('install', '-r', '-g', debug, name='install-debug.txt')
    device('install', '-r', '-g', test, name='install-tests.txt')
    for permission in ('READ_EXTERNAL_STORAGE', 'WRITE_EXTERNAL_STORAGE'):
        device('shell', 'pm', 'grant', 'com.vkyra.code', 'android.permission.' + permission)
    # Do not require clearing system buffers: API26 logd can reject it even when
    # instrumentation and log reading work. Preserve logs and anchor this run.
    device('shell', 'date', '+%s', name='device-test-start-time.txt')
    device('shell', 'df', '-k', '/data', name='storage-before-toolchain.txt')
    instrumentation('toolchain-preparation.txt', 'com.vkyra.code.toolchain.ToolchainRuntimeTest', 1)
    device('shell', 'df', '-k', '/data', name='storage-before-regression.txt')
    instrumentation('debug-full.txt', None, int(manifest['androidTestCount']))
    # Install the exact real R8 output with the test certificate, retaining only the
    # freshly installed toolchain data. The installed app must remain non-debuggable.
    device('install', '-r', '-g', release, name='install-release.txt')
    package = device('shell', 'dumpsys', 'package', 'com.vkyra.code', name='release-package.txt')
    flags = [line for line in package.splitlines() if re.search(r'\b(?:pkgFlags|flags)=\[', line)]
    if not flags or any('DEBUGGABLE' in line for line in flags):
        raise RuntimeError('Installed release is debuggable or package flags are missing')
    installed = device('shell', 'pm', 'path', 'com.vkyra.code', name='release-installed-path.txt').strip()
    if not installed.startswith('package:') or '\n' in installed:
        raise RuntimeError('Expected one installed base APK')
    device('pull', installed[len('package:'):], output / 'installed-release-base.apk', name='release-pull.txt')
    release_hash = hashlib.sha256(release.read_bytes()).hexdigest()
    if hashlib.sha256((output / 'installed-release-base.apk').read_bytes()).hexdigest() != release_hash:
        raise RuntimeError('Installed release differs from tested R8 APK')
    # The driver owns its complete test runtime, independently of R8's target-app
    # Kotlin/AndroidX optimizations. Test-only install never changes target flags.
    device('install', '-r', '-t', release_driver, name='install-release-driver.txt')
    instrumentation('release-smoke.txt', 'com.vkyra.code.ReleaseSmokeTest', 2, timeout=300,
                    package='com.vkyra.code.release.test')
    evidence = {'result': 'PASS', 'api': args.api, 'serial': args.serial, 'avd': avd,
                'sourceCommit': manifest['sourceCommit'], 'inputHashes': manifest['sha256'],
                'debugTests': manifest['androidTestCount'], 'releaseTests': 2,
                'releaseLocallyTestSigned': True, 'releaseDebuggable': False,
                'releaseDriverPackage': 'com.vkyra.code.release.test',
                'releaseDriverSha256': hashlib.sha256(release_driver.read_bytes()).hexdigest(),
                'installedReleaseSha256': release_hash, 'unchangedReleasePayloads': len(payloads(release))}
    (output / 'result.json').write_text(json.dumps(evidence, indent=2) + '\n')
    print(json.dumps(evidence, indent=2))
finally:
    try:
        device('logcat', '-d', name='device-logcat.txt', timeout=30)
    finally:
        # Do not upload even this disposable key; only test APK signatures are evidence.
        if owns_key:
            key.unlink(missing_ok=True)
