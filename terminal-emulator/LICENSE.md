# Terminal emulator licensing

This is a modified copy of the Termux terminal-emulator module. The inherited
Termux repository license is GPL-3.0-only, with the upstream exception for code
derived from Android Terminal Emulator under Apache-2.0. This notice preserves
that distinction; it does not relicense all Termux changes under Apache-2.0.

See [../LICENSE](../LICENSE),
[../licenses/termux-upstream-LICENSE.md](../licenses/termux-upstream-LICENSE.md),
[../licenses/Apache-2.0.txt](../licenses/Apache-2.0.txt), and
[../licenses/android-terminal-emulator-NOTICE.txt](../licenses/android-terminal-emulator-NOTICE.txt).
Existing file notices take precedence for the files they cover. Changes in this
fork include owned process execution, process-group cancellation, JNI argument
handling and application integration; the upstream module has not been replaced
wholesale with a newer Termux release.
