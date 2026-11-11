"""Offline legal-asset preflight unit tests (no APK compilation)."""
from pathlib import Path
import sys
import tempfile
import unittest
import warnings
import zipfile

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "scripts"))
from verify_apk_legal_assets import check_apk, REQUIRED, THIRD_PARTY_NOTICES


class ApkLegalPreflightTests(unittest.TestCase):
    def setUp(self):
        tmp = tempfile.TemporaryDirectory()
        self.addCleanup(tmp.cleanup)
        self.apk = Path(tmp.name) / "test.apk"

    def fixture(self, *, published=False, notices=False, missing=False, invalid_url=False):
        source = ("SOURCE_VERSION_NAME=1.2.3\nSOURCE_URL=https://example.org/source.tar.gz\n"
                  "SOURCE_SHA256=" + "a" * 64 + "\n") if published else (
                  "SOURCE_VERSION_NAME=UNPUBLISHED\nSOURCE_URL=UNPUBLISHED\n"
                  "SOURCE_SHA256=UNPUBLISHED\n")
        if invalid_url:
            source = source.replace("https://example.org", "http://example.org")
        with zipfile.ZipFile(self.apk, "w") as z:
            for key, name in REQUIRED.items():
                if missing and key == "FORGE_GPL":
                    continue
                z.writestr(name, source if key == "SOURCE_ACCESS" else "test content")
            if notices:
                z.writestr(THIRD_PARTY_NOTICES, "third-party legal notices")

    def test_missing_gpl_file_fails(self):
        self.fixture(published=True, notices=True, missing=True)
        self.assertIn("MISSING_LICENSE_ASSETS", check_apk(self.apk)["reasons"])

    def test_unpublished_source_fails(self):
        self.fixture(notices=True)
        self.assertIn("SOURCE_ACCESS_NOT_READY", check_apk(self.apk)["reasons"])

    def test_missing_dependency_notices_fails(self):
        self.fixture(published=True)
        self.assertIn("THIRD_PARTY_NOTICES_NOT_PACKAGED", check_apk(self.apk)["reasons"])

    def test_complete_metadata_still_needs_review(self):
        self.fixture(published=True, notices=True)
        record = check_apk(self.apk)
        self.assertEqual(record["preflight_status"], "NEEDS_EXTERNAL_HUMAN_REVIEW")
        self.assertFalse(record["release_approved"])

    def test_insecure_url_fails(self):
        self.fixture(published=True, notices=True, invalid_url=True)
        self.assertIn("SOURCE_ACCESS_NOT_READY", check_apk(self.apk)["reasons"])

    def test_duplicate_notice_asset_fails(self):
        self.fixture(published=True, notices=True)
        with warnings.catch_warnings():
            warnings.simplefilter("ignore", UserWarning)
            with zipfile.ZipFile(self.apk, "a") as z:
                z.writestr(REQUIRED["SOURCE_ACCESS"], "SOURCE_VERSION_NAME=UNPUBLISHED")
        self.assertIn("UNREADABLE_LICENSE_ASSETS", check_apk(self.apk)["reasons"])

    def test_symlink_fails(self):
        self.fixture()
        link = self.apk.with_name("link.apk")
        link.symlink_to(self.apk)
        with self.assertRaisesRegex(ValueError, "regular"):
            check_apk(link)


if __name__ == "__main__":
    unittest.main()
