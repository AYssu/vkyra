"""Negative controls for the distribution publication gate (no real assets needed)."""
from __future__ import annotations

import hashlib
import importlib.util
import json
from pathlib import Path
import tempfile
import unittest

spec = importlib.util.spec_from_file_location("distribution_check", Path(__file__).with_name("check.py"))
checker = importlib.util.module_from_spec(spec)
spec.loader.exec_module(checker)


class DistributionCheckTest(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.addCleanup(self.directory.cleanup)
        self.root = Path(self.directory.name)
        self.notices = ["LICENSE", "LICENSE.md", "THIRD_PARTY_NOTICES.md", "licenses/SOURCES.json",
                        "termux-shared/LICENSE.md", "terminal-emulator/LICENSE.md", "terminal-view/LICENSE.md"]
        for name in self.notices:
            path = self.root / name
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text("notice\n")
        self.asset = self.root / "app/src/main/assets/demo.apk"
        self.asset.parent.mkdir(parents=True)
        self.asset.write_bytes(b"test asset")
        self.requirement = {"requirement": "Publish corresponding source", "status": "unresolved", "evidence": []}
        self.manifest = {"schemaVersion": 1, "requiredNotices": self.notices,
                         "releaseRequirements": [dict(self.requirement)],
                         "assets": [{"path": "app/src/main/assets/demo.apk", "sha256": checker.digest(self.asset),
                                     "sizeBytes": self.asset.stat().st_size,
                                     "releaseRequirements": [dict(self.requirement)]}]}
        (self.root / "distribution").mkdir()
        sources = {"documents": [{"path": "LICENSE", "source": "https://example.org/license", "sha256": checker.digest(self.root / "LICENSE")}]}
        (self.root / "licenses/SOURCES.json").write_text(json.dumps(sources))
        self.save()

    def save(self):
        (self.root / "distribution/manifest.json").write_text(json.dumps(self.manifest))

    def test_ordinary_ci_passes_while_official_release_is_blocked(self):
        self.assertEqual(checker.validate(self.root)["officialRelease"], "BLOCKED")
        with self.assertRaisesRegex(checker.InvalidManifest, "Official release blocked"):
            checker.validate(self.root, release=True)

    def test_lfs_pointer_or_changed_binary_is_rejected(self):
        self.asset.write_bytes(b"version https://git-lfs.github.com/spec/v1\n")
        with self.assertRaisesRegex(checker.InvalidManifest, "Asset bytes differ"):
            checker.validate(self.root)

    def test_unlisted_archive_is_rejected(self):
        extra = self.root / "app/src/x86_64/assets/new-tool.tar.xz"
        extra.parent.mkdir(parents=True)
        extra.write_bytes(b"unlisted")
        with self.assertRaisesRegex(checker.InvalidManifest, "Asset inventory mismatch"):
            checker.validate(self.root)

    def test_verified_status_without_evidence_is_rejected(self):
        self.manifest["releaseRequirements"][0]["status"] = "verified"
        self.save()
        with self.assertRaisesRegex(checker.InvalidManifest, "needs public evidence"):
            checker.validate(self.root)

    def test_private_or_escaping_evidence_is_rejected(self):
        for name in [".private/evidence.md", "../evidence.md", "/tmp/evidence.md"]:
            with self.subTest(name=name):
                self.manifest["releaseRequirements"][0]["evidence"] = [name]
                self.save()
                with self.assertRaises(checker.InvalidManifest):
                    checker.validate(self.root)

    def test_symlink_into_private_evidence_is_rejected(self):
        private = self.root / ".private"
        private.mkdir()
        (private / "record.md").write_text("private record")
        (self.root / "distribution/alias").symlink_to(private, target_is_directory=True)
        self.manifest["releaseRequirements"][0]["evidence"] = ["distribution/alias/record.md"]
        self.save()
        with self.assertRaisesRegex(checker.InvalidManifest, "cannot use symlinks"):
            checker.validate(self.root)

    def test_changed_legal_text_is_rejected(self):
        (self.root / "LICENSE").write_text("replaced notice")
        with self.assertRaisesRegex(checker.InvalidManifest, "License document digest changed"):
            checker.validate(self.root)

    def test_duplicate_asset_is_rejected(self):
        self.manifest["assets"].append(dict(self.manifest["assets"][0]))
        self.save()
        with self.assertRaisesRegex(checker.InvalidManifest, "Duplicate asset"):
            checker.validate(self.root)

    def test_maintenance_followups_do_not_block_license_cleared_release(self):
        self.manifest["maintenanceItems"] = [dict(self.requirement)]
        self.manifest["assets"][0]["maintenanceItems"] = [dict(self.requirement)]
        for requirement in self.manifest["releaseRequirements"] + self.manifest["assets"][0]["releaseRequirements"]:
            requirement["status"] = "verified"
            requirement["evidence"] = ["LICENSE.md"]
        self.save()
        result = checker.validate(self.root, release=True)
        self.assertEqual(result["officialRelease"], "READY_FOR_MAINTAINER_REVIEW")
        self.assertEqual(result["openMaintenanceItems"], 2)

    def test_evidence_backed_requirements_can_reach_maintainer_review(self):
        evidence = self.root / "distribution/source-record.md"
        evidence.write_text("Fixture: reviewed public source record")
        for requirement in self.manifest["releaseRequirements"] + self.manifest["assets"][0]["releaseRequirements"]:
            requirement["status"] = "verified"
            requirement["evidence"] = ["distribution/source-record.md"]
        self.save()
        self.assertEqual(checker.validate(self.root, release=True)["officialRelease"], "READY_FOR_MAINTAINER_REVIEW")


if __name__ == "__main__":
    unittest.main()
