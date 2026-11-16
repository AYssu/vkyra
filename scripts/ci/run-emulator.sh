#!/usr/bin/env bash
# Creates and destroys only this job's dedicated AVD on an ephemeral hosted runner.
set -euo pipefail
[[ "${GITHUB_ACTIONS:-}" == true ]] || { echo 'This AVD launcher is for disposable GitHub runners only.' >&2; exit 1; }
api="${1:?Usage: run-emulator.sh 26|30|37}"
case "$api" in 26|30|37) ;; *) exit 2;; esac
cd "$(dirname "$0")/../.."
sdk_root="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}"
[[ -n "$sdk_root" ]] || exit 1
adb="$sdk_root/platform-tools/adb"
avdmanager_bin="$sdk_root/cmdline-tools/latest/bin/avdmanager"
[[ -x "$avdmanager_bin" ]] || avdmanager_bin="$(command -v avdmanager)"
serial=emulator-5556
if "$adb" -s "$serial" get-state >/dev/null 2>&1; then
  echo "Refusing to reuse existing device $serial" >&2; exit 1
fi
[[ -c /dev/kvm ]] || { echo 'A Linux runner with KVM is required.' >&2; exit 1; }
sudo chmod a+rw /dev/kvm
export ANDROID_AVD_HOME
ANDROID_AVD_HOME="$(mktemp -d "${RUNNER_TEMP:?}/vkyra-avds.XXXXXX")"
avd_name="vkyra_ci_api$api"
image_api="$api"
[[ "$api" != 37 ]] || image_api=37.0
printf 'no\n' | "$avdmanager_bin" create avd --name "$avd_name" --path "$ANDROID_AVD_HOME/$avd_name.avd" --package "system-images;android-$image_api;default;x86_64" --device pixel_2
[[ -f "$ANDROID_AVD_HOME/$avd_name.ini" ]] || { echo 'AVD configuration was not created in ANDROID_AVD_HOME.' >&2; exit 1; }
"$sdk_root/emulator/emulator" -list-avds | grep -Fx "$avd_name"
mkdir -p build/ci-evidence
"$sdk_root/emulator/emulator" -avd "$avd_name" -port 5556 -no-window -no-audio -no-boot-anim \
  -no-snapshot -gpu swiftshader_indirect -memory 3072 -cores 2 >build/ci-evidence/emulator.log 2>&1 &
emulator_pid=$!
cleanup() {
  # Kill only the process created above, never another emulator/device.
  if kill -0 "$emulator_pid" 2>/dev/null; then kill "$emulator_pid" 2>/dev/null || true; fi
  wait "$emulator_pid" 2>/dev/null || true
}
trap cleanup EXIT INT TERM
sleep 2
if ! kill -0 "$emulator_pid" 2>/dev/null; then
  cat build/ci-evidence/emulator.log >&2
  echo 'The dedicated emulator exited during startup.' >&2
  exit 1
fi
python3 scripts/ci/emulator-smoke.py --serial "$serial" --api "$api" --inputs build/ci-inputs --output build/ci-evidence
