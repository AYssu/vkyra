# Bundled assets and the official release gate

Ordinary CI runs `python3 distribution/check.py`. It checks all six toolchain
archives plus the export APK template against recorded SHA-256 and byte sizes,
requires the public license documents, verifies their source-document hashes,
and rejects missing or unlisted archives. Run Git LFS checkout before this check;
LFS pointer text is not the real binary asset.

`python3 distribution/check.py --release` additionally blocks an official public
release while a redistribution requirement in `manifest.json` is unresolved. Separate
`maintenanceItems` never block publication: a missing exact permissive font/icon
revision or repeatable LLVM build is tracked without inventing a legal duty to
publish modified Apache-2.0 source.
It currently fails for real missing information. This does not prevent a local
build or ordinary PR verification. CI passing does not grant redistribution
rights for unknown binary contents.

To replace or upgrade an asset:

1. Establish its original public source, exact revision, complete dependency list,
   license/copyright notices, patches and a portable build recipe. Supply the
   corresponding source if required by included licenses (for example GNU
   Make GPL). Apache-2.0/LLVM exceptions do not unconditionally require modified
   source disclosure. Exact build recipes/revisions are maintenance evidence;
   they are distinct from mandatory redistribution notices and source duties.
2. Put the reviewed public evidence in a repository document and store the
   necessary license documents in `licenses/`. Do not put evidence in ignored
   `.private` paths or use machine-local source locations.
3. Build and test both ABIs. Review every archive entry and linked runtime;
   preserve notices when packaging. Record the resulting SHA-256 and size in
   `manifest.json`. Digests establish identity, not legal permission.
4. Only after actual review, set each satisfied requirement to `verified` and
   attach nonempty `evidence` paths to the public records. Keep maintenance
   follow-ups in `maintenanceItems`, not as invented legal blockers. Keep unresolved items
   as `unresolved`. Do not delete requirements or replace them with a generic
   license to make a gate green.
5. Run both checker modes and the usual build/runtime checks. The publication
   workflow invokes the release mode before using signing credentials or
   publishing. There is no bypass/approval flag in this checker.

`licenses/SOURCES.json` records where the retained legal documents were obtained.
Some source URLs refer to mutable upstream branches. The saved document hashes
identify the texts; they do not identify a bundled font/icon/binary revision.
The normal runtime dependency inventory remains a separate requirement: this
asset digest manifest is not an SPDX/CycloneDX SBOM and does not inspect binary
license compatibility automatically.
