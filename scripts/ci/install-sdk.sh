#!/usr/bin/env bash
# Run on a disposable Linux CI runner, or an SDK you explicitly own.
set -euo pipefail
mode="${1:-build}"
case "$mode" in build|26|30|37) ;; *) echo 'Usage: install-sdk.sh [build|26|30|37]' >&2; exit 2;; esac
sdk_root="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}"
[[ -n "$sdk_root" ]] || { echo 'Set ANDROID_HOME to your Android SDK.' >&2; exit 1; }
sdkmanager_bin="$sdk_root/cmdline-tools/latest/bin/sdkmanager"
[[ -x "$sdkmanager_bin" ]] || sdkmanager_bin="$(command -v sdkmanager)"
# sdkmanager closes stdin once all license prompts have been answered.
(yes || true) | "$sdkmanager_bin" --sdk_root="$sdk_root" --licenses >/dev/null
packages=('platform-tools' 'platforms;android-37.0' 'build-tools;36.0.0' 'ndk;28.2.13676358')
if [[ "$mode" != build ]]; then
  image_api="$mode"
  [[ "$mode" != 37 ]] || image_api=37.0
  packages+=('emulator' "system-images;android-$image_api;default;x86_64")
fi
"$sdkmanager_bin" --sdk_root="$sdk_root" --install "${packages[@]}"
