#!/usr/bin/env bash
# Build full and Lite with the same application ID, version and development key.
set -euo pipefail
cd "$(dirname "$0")/../.."
export ORG_GRADLE_PROJECT_vkyraBuildDate="${ORG_GRADLE_PROJECT_vkyraBuildDate:-$(TZ=Asia/Shanghai date +%F)}"
python3 scripts/ci/check-source.py
for kind in full lite; do
  omit=false
  [[ "$kind" != lite ]] || omit=true
  # Incremental ZIP packaging can retain unused archive bytes after removing
  # entries. Recreate only our final APK outputs so Lite is physically smaller.
  rm -rf app/build/outputs/apk/aarch64/debug app/build/outputs/apk/x86_64/debug
  ./gradlew :app:assembleAarch64Debug :app:assembleX86_64Debug \
    "-PremoveAllArchAssets=$omit" --console=plain
  for abi in aarch64 x86_64; do
    python3 scripts/ci/package-debug.py --kind "$kind" --abi "$abi"
  done
done
