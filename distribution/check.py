#!/usr/bin/env python3
"""Validate bundled asset integrity; refuse release with missing public evidence."""
from __future__ import annotations

import argparse
import hashlib
import json
from pathlib import Path
import re
import sys


class InvalidManifest(ValueError):
    pass


def inside(root: Path, value: object) -> Path:
    if not isinstance(value, str) or not value or "\\" in value:
        raise InvalidManifest(f"Invalid repository path: {value!r}")
    relative = Path(value)
    if relative.is_absolute() or ".." in relative.parts or ".private" in relative.parts:
        raise InvalidManifest(f"Path must be public and relative: {value!r}")
    target = root / relative
    if not target.resolve().is_relative_to(root.resolve()):
        raise InvalidManifest(f"Path escapes repository: {value!r}")
    current = root
    for component in relative.parts:
        current = current / component
        if current.is_symlink():
            raise InvalidManifest(f"Public evidence cannot use symlinks: {value!r}")
    if not target.is_file():
        raise InvalidManifest(f"Missing public file: {value}")
    return target


def digest(path: Path) -> str:
    result = hashlib.sha256()
    with path.open("rb") as stream:
        for part in iter(lambda: stream.read(1024 * 1024), b""):
            result.update(part)
    return result.hexdigest()


def requirements(root: Path, entries: object, owner: str) -> list[str]:
    if not isinstance(entries, list) or not entries:
        raise InvalidManifest(f"{owner}: releaseRequirements must be nonempty")
    pending = []
    for entry in entries:
        if not isinstance(entry, dict) or not isinstance(entry.get("requirement"), str) or not entry["requirement"].strip():
            raise InvalidManifest(f"{owner}: missing requirement description")
        if entry.get("status") not in {"unresolved", "verified"}:
            raise InvalidManifest(f"{owner}: invalid requirement status")
        evidence = entry.get("evidence")
        if not isinstance(evidence, list):
            raise InvalidManifest(f"{owner}: evidence must be a list of public repository files")
        for item in evidence:
            inside(root, item)
        if entry["status"] == "verified" and not evidence:
            raise InvalidManifest(f"{owner}: verified requirement needs public evidence files")
        if entry["status"] == "unresolved":
            pending.append(f"{owner}: {entry['requirement']}")
    return pending


def validate(root: Path, release: bool = False) -> dict:
    data = json.loads((root / "distribution/manifest.json").read_text(encoding="utf-8"))
    if not isinstance(data, dict) or data.get("schemaVersion") != 1:
        raise InvalidManifest("Unsupported manifest schema")
    notices = data.get("requiredNotices")
    expected_notices = {"LICENSE", "LICENSE.md", "THIRD_PARTY_NOTICES.md", "licenses/SOURCES.json",
                        "termux-shared/LICENSE.md", "terminal-emulator/LICENSE.md", "terminal-view/LICENSE.md"}
    if not isinstance(notices, list) or not expected_notices.issubset(set(notices)):
        raise InvalidManifest("requiredNotices omits required project/component license documents")
    for path in notices:
        inside(root, path)
    sources = json.loads((root / "licenses/SOURCES.json").read_text(encoding="utf-8"))
    if not isinstance(sources.get("documents"), list) or not sources["documents"]:
        raise InvalidManifest("Missing upstream license source inventory")
    for source in sources["documents"]:
        path = inside(root, source.get("path"))
        if digest(path) != source.get("sha256"):
            raise InvalidManifest(f"License document digest changed: {source.get('path')}")
        if not isinstance(source.get("source"), str) or not source["source"].startswith("https://"):
            raise InvalidManifest(f"Missing HTTPS source for {source.get('path')}")
    pending = requirements(root, data.get("releaseRequirements"), "repository")
    maintenance = requirements(root, data["maintenanceItems"], "repository maintenance") if data.get("maintenanceItems") else []
    assets = data.get("assets")
    if not isinstance(assets, list) or not assets:
        raise InvalidManifest("Asset inventory cannot be empty")
    paths = set()
    for asset in assets:
        path = asset.get("path")
        target = inside(root, path)
        if path in paths:
            raise InvalidManifest(f"Duplicate asset: {path}")
        paths.add(path)
        if not re.fullmatch(r"[0-9a-f]{64}", str(asset.get("sha256", ""))):
            raise InvalidManifest(f"Invalid SHA-256: {path}")
        if target.stat().st_size != asset.get("sizeBytes") or digest(target) != asset["sha256"]:
            raise InvalidManifest(f"Asset bytes differ from manifest: {path}")
        pending.extend(requirements(root, asset.get("releaseRequirements"), path))
        if asset.get("maintenanceItems"):
            maintenance.extend(requirements(root, asset["maintenanceItems"], f"{path} maintenance"))
    actual = {p.relative_to(root).as_posix() for p in root.glob("app/src/*/assets/*.tar.xz")}
    actual.add("app/src/main/assets/demo.apk")
    if actual != paths:
        raise InvalidManifest(f"Asset inventory mismatch: missing={sorted(actual - paths)}, extra={sorted(paths - actual)}")
    if release and pending:
        raise InvalidManifest("Official release blocked by public provenance requirements:\n- " + "\n- ".join(pending))
    return {"integrity": "PASS", "assetCount": len(paths), "unresolvedRequirements": len(pending), "openMaintenanceItems": len(maintenance),
            "officialRelease": "BLOCKED" if pending else "READY_FOR_MAINTAINER_REVIEW"}


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--release", action="store_true", help="also require public source/provenance evidence for publication")
    args = parser.parse_args()
    try:
        result = validate(Path(__file__).resolve().parent.parent, args.release)
    except (InvalidManifest, OSError, ValueError, TypeError, AttributeError, KeyError) as error:
        print(f"Distribution check failed: {error}", file=sys.stderr)
        return 1
    print(json.dumps(result, indent=2))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
