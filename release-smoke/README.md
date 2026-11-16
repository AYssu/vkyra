# Release smoke driver

This is a **test-only application APK**, not a Vkyra product or distribution module. It has application ID `com.vkyra.code.release.test` and declares `androidx.test.runner.AndroidJUnitRunner` targeting `com.vkyra.code`.

`PrepareReleaseSmokeSource` declares exactly `app/src/androidTest/java/com/vkyra/code/ReleaseSmokeTest.java` as its input and syncs that file into a generated build source directory, registered through AGP’s variant source API. No second source copy is committed and no symlink is required. Tests that access application internals are not included. The same two black-box tests still run in the app's normal debug suite. Runner, AndroidX Test, Espresso, JUnit and their runtime dependencies are packaged by this driver instead of depending on classes that R8 may legitimately remove or rename in the target application. Target terminal View and AppCompat types are compile-only, so the driver does not supply competing production View classes.

Build with `./gradlew :release-smoke:assembleDebug`. Output is `release-smoke/build/outputs/apk/debug/release-smoke-debug.apk`. The CI harness test-signs the driver with the same disposable certificate as the genuinely shrunk target APK, installs the driver using `adb install -t`, then runs:

```text
adb -s OWNED_SERIAL shell am instrument -w -r -e class com.vkyra.code.ReleaseSmokeTest com.vkyra.code.release.test/androidx.test.runner.AndroidJUnitRunner
```

Use the public CI harness to verify device ownership, exact APK hashes and test completion. A passing result requires both tests, no skipped/failing statuses and a normal runner finish. The production release remains non-debuggable and retains ordinary R8 optimization; no production blanket keep rule is added to accommodate the test runner. Never attach this driver APK to a formal Release.

Instrumentation shares a class loader with its target. The driver therefore constrains shared Lifecycle, Kotlin, coroutines, annotations and tracing runtimes to versions actually resolved by the target. In particular AndroidX Test’s old Lifecycle 2.3.1 cannot provide the Kotlin `Lifecycle.Event.Companion` ABI required by the target’s Lifecycle 2.6.2. These are driver-only alignment constraints, not production dependencies or keep rules. When updating the app’s AndroidX/Kotlin dependencies, compare both runtime graphs and update the driver constraints with the upgrade; all three API release checks must pass.

The driver also packages Guava `24.1-jre`, matching the app's existing resolved version. The project excludes the standalone `listenablefuture` artifact; Espresso still needs `com.google.common.util.concurrent.ListenableFuture` under its original name even when the optimized app removes or renames its own copy. Supplying Guava in the driver keeps this testing requirement out of the production R8 rules. AndroidX Test's `concurrent-futures` and `concurrent-futures-ktx` remain at `1.2.0`: the shared public future APIs retain the signatures used by the app's `1.1.0` dependency, and the test runtime requires the newer pair.
