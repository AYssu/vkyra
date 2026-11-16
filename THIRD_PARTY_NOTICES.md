# Third-party notices and provenance

This inventory describes known direct dependencies and bundled material. It is
not a complete generated SBOM or a claim that all prebuilt contents have been
cleared for redistribution. Versions below match the current version catalog;
the Gradle runtime graph, including transitives, must be reviewed again when
publishing or upgrading. Upstream copyrights remain with their respective
holders; full available notices are retained in [licenses/](licenses/).

## Source modules and resources

| Component | Source / applicable notice | Status |
| --- | --- | --- |
| Termux app-derived code, terminal-emulator, terminal-view, termux-shared | [Termux](https://github.com/termux/termux-app), [upstream license](licenses/termux-upstream-LICENSE.md), [shared exceptions](termux-shared/LICENSE.md) | GPL-3.0-only with upstream file/component exceptions; modified fork, not latest upstream |
| Android Terminal Emulator-derived portions | [Upstream](https://github.com/jackpal/Android-Terminal-Emulator), [Apache notice](licenses/android-terminal-emulator-NOTICE.txt) | Apache-2.0; preserve attribution and identify changes |
| OpenJDK/libcore filesystem portions | Original Oracle headers in `termux-shared/src/main/java/com/termux/shared/file/filesystem/`; [license and Classpath exception](licenses/GPL-2.0-with-classpath.txt) | GPL-2.0-only WITH Classpath-exception-2.0 for designated files |
| VS Code C/C++, JSON and Make grammars | `app/src/main/assets/textmate/provenance.json`, sibling `licenses/vscode-MIT.txt` and `vscode-notices.txt` | Commit recorded; retain bundled upstream notices, including grammar subcomponents |
| Material Icon Theme SVG files | [Upstream](https://github.com/PKief/vscode-material-icon-theme), [MIT notice](licenses/material-icon-theme-MIT.txt) | Origin named in FileIconProvider; exact imported revision is a maintenance follow-up. Current upstream notice is retained as reference, not proof of the imported revision; preserve historical attribution if later identified |
| JetBrains Mono fonts | [Upstream](https://github.com/JetBrains/JetBrainsMono), [OFL-1.1](licenses/JetBrainsMono-OFL.txt) | Upstream notice supplied; recording the exact imported font release is a maintenance follow-up |

## Java / Android runtime dependencies

These are library licenses, not licenses assigned by Vkyra. Generic texts do not
replace library-specific copyright or NOTICE files. Dependency updates must
refresh runtime/transitive notices; [distribution/manifest.json](distribution/manifest.json)
retains a release blocker until that complete inventory is attached.

| Dependency | Version | Upstream license / retained text |
| --- | --- | --- |
| Sora Editor and language-textmate | 0.24.7 | [LGPL-2.1](licenses/LGPL-2.1.txt); [source tag](https://github.com/Rosemoe/sora-editor/tree/0.24.7) |
| Eclipse LSP4J and JSON-RPC | 1.0.0 | [EPL-2.0 OR EDL-1.0](licenses/LSP4J-LICENSE.txt), as offered by upstream LICENSE; retain the dual license |
| Eclipse JGit | 5.13.3.202401111512-r | [EDL-1.0](licenses/JGit-LICENSE.txt) |
| Gson | 2.13.2 | [Apache-2.0](licenses/Gson-LICENSE.txt) |
| Markwon and extensions | 4.6.2 | [Apache-2.0](licenses/Markwon-LICENSE.txt) |
| libsu core and io | 6.0.0 | [Apache-2.0](licenses/Apache-2.0.txt); [source](https://github.com/topjohnwu/libsu/tree/6.0.0) |
| AndroidX, Material Components, Google apksig, ZXing | Pinned in Gradle | Apache-2.0; retain each artifact's copyright and NOTICE |
| AndroidSVG 1.4, Commons Compress 1.28.0, commons-io 2.5, Guava | Pinned in Gradle | Apache-2.0; retain each artifact's notices |
| ARSCLib | 1.3.8 | [Apache-2.0](licenses/ARSCLib-LICENSE.txt) |
| apk-parser | 2.6.10 | [BSD-2-Clause](licenses/apk-parser-LICENSE.txt); upstream project notice retained, imported artifact notice must also be checked |
| XZ for Java | 1.11 | [0BSD](licenses/XZ-Java-1.11-COPYING.txt) |
| Bouncy Castle provider, PKIX and utilities | 1.83 | [Bouncy Castle license](licenses/BouncyCastle-LICENSE.txt) |
| OkHttp and its transitive Okio/Kotlin dependencies | 4.12.0 / resolved graph | Apache-2.0; preserve resolved artifact notices |
| SLF4J API and no-op implementation | 1.7.36 | [MIT notice](licenses/SLF4J-LICENSE.txt) |
| Zip4j 2.11.6, java-semver 0.10.2 | Pinned in Gradle | Apache-2.0; preserve artifact notices |
| CustomActivityOnCrash | 2.4.0 | Apache-2.0; preserve artifact notices |
| desugar_jdk_libs | 2.1.5 | OpenJDK-derived components include GPL-2.0 WITH Classpath exception; check shipped artifact notices and embedded dependencies |

JUnit, Robolectric, Espresso, AndroidX Test, Gradle and AGP are development/test
or build dependencies; do not confuse that graph with packaged runtime content.
Their own licenses and distribution terms still apply when redistributing them.

## Bundled executable assets and APK template

`app/src/aarch64/assets/` and `app/src/x86_64/assets/` contain prebuilt native
tools. The following original upstream texts are supplied as references; they
do not establish that those are the complete licenses of each archive:

- [LLVM 21.1.8](licenses/LLVM-21.1.8-LICENSE.txt): Apache-2.0 with LLVM exceptions
  plus component-specific notices. `clang-version` names an AYssu fork commit;
  the old local compiler scripts target LLVM 18.1.8 and do not reproduce these
  Clang 21 archives. Recording fork sources, patches and a build recipe is a
  maintenance follow-up. Apache-2.0 with LLVM exceptions does not unconditionally
  require publishing modified compiler source; included components may impose
  separate obligations.
- [CMake 3.28.1](licenses/CMake-3.28.1-Copyright.txt),
  [Ninja 1.12.1](licenses/Ninja-1.12.1-COPYING.txt), GNU Make, and linked libraries
  need an actual archive component inventory. A `CPack.GenericLicense.txt`
  template inside the archive is not a license for all those binaries.
- [Zsh 5.9](licenses/Zsh-5.9-LICENCE.txt), ncurses and included plugins keep
  their individual terms. Two plugin notices are present inside the archives;
  that alone does not cover the shell and its linked dependencies. Record the
  exact plugin revisions and all linked-library notices.
- `app/src/main/assets/demo.apk` is a prebuilt, modified export template. It
  contains native/runtime and Java components. The existing AndroidX notice is
  not proof of its full provenance. Permission to redistribute the template
  and complete dependency/license notices must be established. Record its
  source repository as maintenance evidence. Supply corresponding source where
  a component license requires
  it. A repeatable source build is a maintenance goal, distinct from any
  license-specific source obligations.

The machine-readable distribution manifest records exact asset digests and
unresolved requirements. Run `python3 distribution/check.py` for the integrity
check, and `python3 distribution/check.py --release` for the release gate. The
latter currently fails for missing redistribution notices/rights and applicable
source obligations. Separate `maintenanceItems` (for example exact permissive
font/icon revisions or reproducible LLVM builds) do not block release. Do not
clear a license blocker by merely changing its status or attaching a generic
license text.
