"""Audit report export tests: metadata-only, fail closed, no release."""
import json
from pathlib import Path
import sys
import tempfile
import unittest

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "scripts"))
from export_gpl_audit_evidence import export

H = "a" * 64
S = "b" * 64

class AuditExportTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        root = Path(self.temp.name)
        self.records = [
            {"distribution_approved": False, "apk_sha256": H, "source_sha256": S},
            {"release_approved": False, "integrity": "PASS", "binary_sha256": H, "candidate_sha256": S},
            {"schema": "chimera-license-evidence-v1", "release_approved": False,
             "jar_count": 1, "staged_jar_evidence": [
                 {"scope": "forge", "file": "foo-1.jar", "sha256": H}]},
            {"schema": "chimera-pom-license-review-evidence-v1",
             "distribution_approved": False, "license_compatibility_verified": False, "evidence_only": True,
             "staged_jars": [{"scope": "forge", "file": "foo-1.jar", "sha256": H,
                 "review": "HUMAN_REVIEW_REQUIRED", "notice_paths": [],
                 "candidate_pom_coordinates": ["com.test:foo:1"],
                 "declared_licenses": [{"name": "MIT", "url": "https://mit-license.org", "distribution": "repo"}],
                 "unresolved": "LICENSE_DECLARATION_NOT_VERIFIED"}],
             "gradle_dependencies": []}
        ]
        self.paths = [root / (name + ".json") for name in ("pair", "integrity", "inventory", "pom")]
        self.save()

    def save(self):
        for path, record in zip(self.paths, self.records):
            path.write_text(json.dumps(record))

    def test_export_never_approves_release(self):
        data = export(*self.paths)
        self.assertFalse(data["distribution_approved"])
        self.assertFalse(data["source_complete_certified"])
        self.assertEqual(data["forge_and_android_staged_jar_count"], 1)
        self.assertNotIn("archive", data)

    def test_source_digest_tampering_fails(self):
        self.records[1]["candidate_sha256"] = "c" * 64
        self.save()
        with self.assertRaisesRegex(ValueError, "hash disagreement"):
            export(*self.paths)

    def test_approval_flag_cannot_sneak_in(self):
        self.records[3]["distribution_approved"] = True
        self.save()
        with self.assertRaisesRegex(ValueError, "HOLD"):
            export(*self.paths)

    def test_secret_like_metadata_is_rejected(self):
        self.records[3]["staged_jars"][0]["declared_licenses"][0]["url"] = "https://example.org/?token=secret"
        self.save()
        with self.assertRaisesRegex(ValueError, "Potential secret"):
            export(*self.paths)

    def test_symlink_input_rejected(self):
        link = self.paths[0].parent / "link.json"
        link.symlink_to(self.paths[0])
        with self.assertRaisesRegex(ValueError, "unsafe"):
            export(link, *self.paths[1:])

if __name__ == "__main__":
    unittest.main()
