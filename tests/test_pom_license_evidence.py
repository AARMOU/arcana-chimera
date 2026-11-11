"""Synthetic Maven POM declaration tests; never asserts license compatibility."""
import json
from pathlib import Path
import sys
import tempfile
import unittest

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "scripts"))
from pom_license_evidence import analyze, parse_pom


class MavenLicenseEvidenceTests(unittest.TestCase):
    def setUp(self):
        tmp = tempfile.TemporaryDirectory()
        self.addCleanup(tmp.cleanup)
        self.root = Path(tmp.name)
        self.maven = self.root / "maven"
        self.gradle = self.root / "gradle"
        self.forge = self.root / "forge"
        for x in (self.maven, self.gradle, self.forge):
            x.mkdir()
        d = self.maven / "com/example/widget/1.2.0"
        d.mkdir(parents=True)
        (d / "widget-1.2.0.pom").write_text(
            '<project xmlns="http://maven.apache.org/POM/4.0.0">'
            '<modelVersion>4.0.0</modelVersion><groupId>com.example</groupId>'
            '<artifactId>widget</artifactId><version>1.2.0</version>'
            '<licenses><license><name>Apache License, Version 2.0</name>'
            '<url>https://www.apache.org/licenses/LICENSE-2.0</url></license></licenses>'
            '</project>', encoding="utf-8")
        self.inventory = self.root / "evidence.json"
        self.base_data = {
            "schema": "chimera-license-evidence-v1",
            "release_approved": False,
            "gradle_runtime_coordinates": ["com.example:widget:1.2.0"],
            "staged_jar_evidence": [
                {"file": "widget-1.2.0.jar", "sha256": "x" * 64, "scope": "android",
                 "notice_paths": ["META-INF/NOTICE"]},
                {"file": "unknown-2.0.jar", "sha256": "y" * 64, "scope": "forge",
                 "notice_paths": []}],
        }
        self.inventory.write_text(json.dumps(self.base_data))

    def data(self):
        return analyze(self.inventory, [
            ("maven_cache", self.maven), ("gradle_cache", self.gradle),
            ("forge_source", self.forge),
        ])

    def test_declared_license_is_evidence_not_a_release_authorization(self):
        data = self.data()
        self.assertFalse(data["distribution_approved"])
        self.assertFalse(data["license_compatibility_verified"])
        self.assertEqual(data["pom_files_inspected"], 1)
        self.assertEqual(len(data["gradle_dependencies"]), 1)
        self.assertEqual(data["gradle_dependencies"][0]["declared_licenses"][0]["name"],
                         "Apache License, Version 2.0")
        self.assertEqual(data["staged_jars"][0]["candidate_pom_coordinates"],
                         ["com.example:widget:1.2.0"])
        self.assertEqual(data["staged_jars"][1]["unresolved"], "NO_MATCHING_POM")

    def test_missing_pom_never_infers_license_from_jar_name(self):
        p = next(self.maven.rglob("*.pom"))
        p.unlink()
        with self.assertRaisesRegex(ValueError, "No Maven/Gradle metadata"):
            self.data()

    def test_parent_license_not_inherited_automatically(self):
        p = next(self.maven.rglob("*.pom"))
        p.write_text('<project><parent><groupId>com.example</groupId>'
                     '<artifactId>parent</artifactId><version>1</version></parent>'
                     '<artifactId>widget</artifactId><version>1.2.0</version></project>')
        data = self.data()
        self.assertEqual(data["staged_jars"][0]["unresolved"], "NO_EXPLICIT_LICENSE")

    def test_bad_inventory_refused(self):
        self.base_data["release_approved"] = True
        self.inventory.write_text(json.dumps(self.base_data))
        with self.assertRaisesRegex(ValueError, "fail-closed"):
            self.data()

    def test_xml_namespace_and_property_placeholders_preserved(self):
        p = next(self.maven.rglob("*.pom"))
        parsed = parse_pom(p)
        self.assertEqual(parsed["coordinate"], "com.example:widget:1.2.0")
        self.assertTrue(parsed["metadata_only"])


if __name__ == "__main__":
    unittest.main()
