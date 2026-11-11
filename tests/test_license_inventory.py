"""Synthetic only; no network, signing keys, or app build."""
import sys
from pathlib import Path
import tempfile
import unittest
import zipfile

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "scripts"))
from license_inventory import inventory, main


class LicenseInventoryTests(unittest.TestCase):
    def setUp(self):
        tmp = tempfile.TemporaryDirectory()
        self.addCleanup(tmp.cleanup)
        self.root = Path(tmp.name)
        self.forge = self.root / "forge-libs"
        self.app = self.root / "app-libs"
        self.forge.mkdir()
        self.app.mkdir()
        self.report = self.root / "gradle.txt"
        self.report.write_text(
            "releaseRuntimeClasspath - Runtime classpath\n"
            "+--- androidx.core:core-ktx:1.16.0\n")
        with zipfile.ZipFile(self.forge / "forge.jar", "w") as z:
            z.writestr("META-INF/LICENSE.txt", "fixture")
            z.writestr("META-INF/maven/example/forge/pom.properties",
                       "groupId=example\nartifactId=forge\nversion=1.2.3\n")
        with zipfile.ZipFile(self.app / "other.jar", "w") as z:
            z.writestr("Hello.class", "binary dummy")

    def test_records_evidence_but_never_approves(self):
        report = inventory(self.forge, self.app, self.report)
        self.assertFalse(report["release_approved"])
        self.assertEqual(report["jar_count"], 2)
        self.assertEqual(report["missing_embedded_notice_count"], 1)
        self.assertIn("example:forge:1.2.3",
                      report["staged_jar_evidence"][0]["pom_coordinates"])
        self.assertIn("androidx.core:core-ktx:1.16.0",
                      report["gradle_runtime_coordinates"])

    def test_missing_or_wrong_report_blocks(self):
        self.report.write_text("otherconfiguration")
        with self.assertRaisesRegex(ValueError, "releaseRuntimeClasspath"):
            inventory(self.forge, self.app, self.report)

    def test_empty_jar_folder_blocks(self):
        (self.app / "other.jar").unlink()
        with self.assertRaisesRegex(ValueError, "empty"):
            inventory(self.forge, self.app, self.report)

    def test_bad_zip_blocks(self):
        (self.app / "other.jar").write_bytes(b"not a jar")
        with self.assertRaisesRegex(ValueError, "Unreadable"):
            inventory(self.forge, self.app, self.report)

    def test_cli_outputs_pending_json(self):
        dest = self.root / "out" / "evidence.json"
        self.assertEqual(main(["--forge-libs", str(self.forge), "--app-libs",
                               str(self.app), "--gradle-report", str(self.report),
                               "--out", str(dest)]), 0)
        self.assertIn('"release_approved": false', dest.read_text())


if __name__ == "__main__":
    unittest.main()
