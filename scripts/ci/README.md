# Public CI entry points

CI uses Java 17, compileSdk 37, build-tools 36.0.0 and NDK 28.2.13676358. The application keeps minSdk 26 and targetSdk 28. Read the root contribution and maintenance guides before upgrading these boundaries.

- `bash scripts/ci/install-sdk.sh build` installs the pinned host-build SDK packages. `26`, `30` or `37` also installs that API's default x86_64 emulator image. This accepts Android SDK licenses; run it only in an SDK you own.
- `VKYRA_HOST_CLANGD=/path/to/clangd bash scripts/ci/verify.sh` runs source/LFS validation, the distribution manifest checks and their negative tests, CI-parser regression tests, app and terminal JVM tests, lint, both ABI debug and normal R8 release builds, and the instrumentation build. A missing real host clangd or skipped test fails the gate.
- `run-emulator.sh API` is a launcher **only for disposable GitHub Actions Linux runners with KVM**. It creates `vkyra_ci_apiAPI` on `emulator-5556` and destroys only the emulator process it starts.
- `emulator-smoke.py` can run on an explicitly named owned Vkyra test AVD. It refuses arbitrary devices. It verifies input hashes, installs an actual bundled compiler, runs the complete Android suite, then installs the genuinely shrunk release with a disposable matching test certificate and runs the black-box release smoke. APK payloads, non-debuggable flags and installed APK hash are checked. An existing app with another signing certificate makes installation fail; no data wipe or uninstall is automatic. A local `--test-keystore PATH --test-key-alias androiddebugkey` may reuse an existing **debug** certificate, with the standard `android` test password. Never supply a production release key.
- `check-reports.py` reads actual XML reports. It rejects missing/empty/partial JUnit reports, failures, errors and skipped cases. Android `am instrument` is independently parsed because the shell can return zero after tests fail.

## Lint policy

`lint-baseline.json` is an explicit allowance generated from the actual lint XML when public CI was introduced, rather than a blanket suppression. Each allowed warning includes its issue ID, exact message, normalized file locations and instance count. Line numbers do not form part of the key, so unrelated insertions do not create false additions. Cache locations retain the complete dependency coordinates and hash while removing the developer's home path. Errors always fail; new warning identities and extra duplicate instances fail; fixing warnings is allowed.

The inherited warnings include localization/accessibility/layout debt and the following reviewed compatibility cases:

- `InsecureBaseConfiguration`: users can explicitly use HTTP Git remotes, including local servers. Update lookup/download still requires HTTPS. HTTP remotes are not confidential/authenticated transport.
- `TrustAllX509TrustManager`: BouncyCastle's EST helper is not wired into app networking. JGit's compatibility path is disabled by default but can be enabled by explicit `http.sslVerify=false` Git configuration. The allowance does not claim this configuration is safe.
- `StaticFieldLeak`: the plugin singleton is constructed with application context; plugin callback ownership is separately tested.
- Dependency update suggestions are reviewed with compatibility evidence; they are not an instruction to silently replace Android compatibility ceilings. Their exact messages can change when upstream versions change, in which case CI requests review.

To inspect current warnings, run `python3 scripts/ci/lint_policy.py app/build/reports/lint-results-x86_64Debug.xml`. If an unavoidable warning has been reviewed, manually run `python3 scripts/ci/lint_policy.py --record-reviewed app/build/reports/lint-results-x86_64Debug.xml`, inspect the diff and explain each new allowance in the PR. CI never rewrites this file. Do not regenerate it just to make a failing PR green.

## Results and release boundary

`manifest.json` binds the exact commit, Git dirty status, APK/mapping hashes and a `sourceFileHashes` map of selected source/build-code files. `sourceTreeSha256` hashes that listed map; it is a source-code snapshot fingerprint, **not** a complete Git tree hash or a claim to include every document/configuration/asset. Official release provenance additionally requires the clean tested commit and exact artifact hashes. A local workspace without a first commit is explicitly labeled `UNCOMMITTED`, so it cannot be mistaken for a release source commit.

The `CI required` check requires the host job and **all API 26/30/37 device jobs** to succeed. A cancelled, skipped or failed dependency cannot pass the aggregate check. The test count is inventoried from ordinary JUnit4 `@Test` methods; introducing generated/parameterized device tests requires updating that inventory deliberately.

CI artifacts are development/test outputs. Debug APKs and unsigned release APKs are not official signed releases. The locally test-signed release is used for runtime validation only. Device jobs never receive production signing credentials.

The separate release workflow resolves an existing version tag to a commit, requires the distribution gate, reruns the same full CI at that commit, then passes **those exact CI-built APKs** to `sign-release.py` in the protected `release` environment. The signer verifies artifact/mapping hashes, tag/version/application ID, non-debuggable status and unchanged APK payloads. It refuses debug certificates and removes its temporary key. A final tag-resolution check rejects a moved tag. The resulting GitHub Release is a draft for maintainer review. Missing signing secrets or unresolved distribution sources stop this workflow.

Fork PR checks use `contents: read`, no signing secrets, read-only Gradle caches and no persisted checkout credentials. The workflows do not use `pull_request_target`. A maintainer must still review source and workflow changes before merging; passing tests is not a security proof.

LFS payloads and full APKs are large. The host job restores cached LFS objects before materializing and verifying assets; device jobs reuse build artifacts without downloading LFS again. SDK/NDK and each device image have separate monthly/configuration cache keys, and SDK installs are skipped on exact hits. Device jobs install no build-only SDK/NDK. Only main-branch pushes save these caches; PRs restore them without writing. AVD user data, credentials and test results are never cached, and all verification still runs. Artifacts expire after 7 days. GitHub LFS transfer/storage, Actions minutes, caches and artifact storage still consume the repository owner's quota. Tune retention and device coverage with measured costs, while keeping required checks truthful.

## Manual Debug APK build

Run **Actions → Debug APK → Run workflow** on `main` to build only the two installable Debug variants. Download `vkyra-debug-apks` from the successful run: `Vkyra-debug-aarch64.apk`, `Vkyra-debug-x86_64.apk`, `COMMIT.txt` and `SHA256SUMS.txt`. Artifacts expire after 7 days. It uses the normal development certificate and no release secrets; keys can differ between clean runners, so it may not update an existing installation in place. Preserve your projects before uninstalling an app with a different certificate.

This workflow reuses the pinned build environment and LFS/SDK caches. It does not replace the full `CI required` gate on pushes and PRs. It does not publish a GitHub Release.
