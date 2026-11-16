# Licensing

Vkyra is derived from Akode and Termux. The combined application follows the
inherited **GNU General Public License version 3 only (GPL-3.0-only)**; the
unmodified license text is in [LICENSE](LICENSE). This statement does not claim
ownership of upstream work or replace a file's existing license or copyright.

The upstream Termux license statement is preserved in
[licenses/termux-upstream-LICENSE.md](licenses/termux-upstream-LICENSE.md).
Termux includes Android Terminal Emulator code under Apache-2.0. Its terminal
modules contain both Termux work and that inherited exception; they must not be
blanket-relicensed as Apache-2.0. See their module notices and
[termux-shared/LICENSE.md](termux-shared/LICENSE.md), which also records MIT,
GPL-2.0-only WITH Classpath-exception-2.0, and Apache-2.0 exceptions.

Other dependencies, grammars, icons, fonts, compiler tools and prebuilt APK
contents retain their own licenses. See [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md)
and [licenses/](licenses/). Existing copyright and attribution notices must be
retained when copying or changing code. Changes do not remove upstream rights.
New contributions to this application are supplied under the applicable existing
license unless a separately licensed component explicitly says otherwise.

Publishing an APK also requires the corresponding source and notices required by
its included components. Merely placing a GPL text in this repository does not
complete that work. Source disclosure/rebuild obligations depend on the actual
component license; an Apache-2.0 component does not unconditionally require
publishing modified source. Current redistribution and maintenance gaps are tracked in
[distribution/manifest.json](distribution/manifest.json); the official release
gate refuses publication while required license/notice items remain unresolved;
separate maintenance items do not block release. Source CI and local test
builds remain available. Contributors must establish the actual sources and
rights of imported material, not replace an unresolved origin with a guessed
license.
