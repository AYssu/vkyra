#!/usr/bin/env bash
# Full host gate. It deliberately fails if the real host clangd suite cannot run.
set -euo pipefail
cd "$(dirname "$0")/../.."
export VKYRA_HOST_CLANGD="${VKYRA_HOST_CLANGD:-$(command -v clangd || true)}"
[[ -x "$VKYRA_HOST_CLANGD" ]] || { echo 'Install clangd and set VKYRA_HOST_CLANGD.' >&2; exit 1; }
"$VKYRA_HOST_CLANGD" --version
python3 scripts/ci/check-source.py
python3 -m unittest discover -s scripts/ci -p 'test_*.py'
python3 distribution/check.py
python3 -m unittest discover -s distribution -p 'test_*.py'
./gradlew --no-daemon --max-workers=2 \
  :app:testX86_64DebugUnitTest :terminal-emulator:testDebugUnitTest \
  :app:lintX86_64Debug :app:assembleX86_64DebugAndroidTest :release-smoke:assembleDebug \
  :app:assembleAarch64Debug :app:assembleX86_64Debug \
  :app:assembleAarch64Release :app:assembleX86_64Release
python3 scripts/ci/check-reports.py
python3 scripts/ci/prepare-inputs.py
