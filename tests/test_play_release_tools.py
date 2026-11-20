import os
from pathlib import Path
import sys
import tempfile
import unittest
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "scripts"))
from play_release_tools import (  # noqa: E402
    EXPECTED_UPLOAD_CERT_SHA256, fingerprint, read_certificate_sha256, prepare_gradle,
    require_signing_environment, validate_version,
)


DONOR = """
android {
    defaultConfig {
        applicationId = "com.arcanatable"
        targetSdk = 36
    }
}
// CHIMERA OVERLAY
android { defaultConfig { applicationId = "org.arcanachimera.arcanaforge" } }
"""


class PlayReleaseToolsTests(unittest.TestCase):
    def test_fingerprint_accepts_play_console_colons(self):
        raw = "ab:" * 31 + "ab"
        self.assertEqual(fingerprint(raw), "AB" * 32)

    def test_reported_play_upload_pin_is_valid_sha256(self):
        self.assertEqual(
            EXPECTED_UPLOAD_CERT_SHA256,
            fingerprint("4C:CA:DD:51:2C:EC:DA:DD:A5:41:2C:FB:8B:F3:3F:02:"
                        "BF:5A:18:34:D4:09:20:98:8C:5F:9D:CC:E9:30:0D:8F")
        )

    def test_reported_upload_pin_cannot_be_overridden(self):
        with tempfile.TemporaryDirectory() as temp:
            keyfile = Path(temp) / "key.jks"
            keyfile.write_bytes(b"fixture")
            env = {
                "CHIMERA_UPLOAD_KEYSTORE": str(keyfile),
                "CHIMERA_UPLOAD_STORE_PASSWORD": "fixture",
                "CHIMERA_UPLOAD_KEY_PASSWORD": "fixture",
                "CHIMERA_UPLOAD_ALIAS": "arcana-upload",
            }
            with patch.dict(os.environ, env, clear=True):
                store, expected = require_signing_environment()
                self.assertEqual(store, keyfile.resolve())
                self.assertEqual(expected, EXPECTED_UPLOAD_CERT_SHA256)
            env["CHIMERA_EXPECTED_UPLOAD_CERT_SHA256"] = "AB" * 32
            with patch.dict(os.environ, env, clear=True):
                with self.assertRaisesRegex(ValueError, "differs from the confirmed public pin"):
                    require_signing_environment()
            env["CHIMERA_EXPECTED_UPLOAD_CERT_SHA256"] = EXPECTED_UPLOAD_CERT_SHA256
            with patch.dict(os.environ, env, clear=True):
                _, expected = require_signing_environment()
                self.assertEqual(expected, "4CCADD512CECDADDA5412CFB8BF33F02BF5A1834D40920988C5F9DCCE9300D8F")

    def test_fingerprint_rejects_missing_certificate(self):
        with self.assertRaises(ValueError):
            fingerprint("12:34")

    def test_keytool_extracts_sha256(self):
        sample = "SHA1: 11:22\nSHA256: " + ":".join(["CD"] * 32) + "\n"
        self.assertEqual(read_certificate_sha256(sample), "CD" * 32)

    def test_version_code_fails_closed(self):
        for value in (0, 138, 142, 2100000001):
            with self.assertRaises(ValueError):
                validate_version(value)
        self.assertEqual(validate_version(143), 143)

    def test_signing_alias_must_be_original(self):
        with tempfile.TemporaryDirectory() as temp:
            keyfile = Path(temp) / "key.jks"
            keyfile.write_bytes(b"dummy-nonsecret-fixture")
            env = {
                "CHIMERA_UPLOAD_KEYSTORE": str(keyfile),
                "CHIMERA_UPLOAD_STORE_PASSWORD": "fixture",
                "CHIMERA_UPLOAD_KEY_PASSWORD": "fixture",
                "CHIMERA_UPLOAD_ALIAS": "arcanatable-upload",
                "CHIMERA_EXPECTED_UPLOAD_CERT_SHA256": "AB" * 32,
            }
            with patch.dict(os.environ, env, clear=True):
                with self.assertRaisesRegex(ValueError, "original Play"):
                    require_signing_environment()

    def test_prepares_release_only_play_override(self):
        with tempfile.TemporaryDirectory() as temp:
            gradle = Path(temp) / "build.gradle.kts"
            gradle.write_text(DONOR)
            prepare_gradle(gradle, 143, "0.35.0-chimera-143")
            contents = gradle.read_text()
            self.assertIn('applicationId = "com.arcanatable"', contents)
            self.assertIn("versionCode = 143", contents)
            self.assertIn('getByName("release")', contents)
            self.assertIn('create("chimeraPlay")', contents)
            self.assertIn('System.getenv("CHIMERA_UPLOAD_STORE_PASSWORD")', contents)
            self.assertNotIn("fixture", contents)
            with self.assertRaisesRegex(ValueError, "already added"):
                prepare_gradle(gradle, 144, "0.35.0-chimera-144")

    def test_ci_unsigned_bundle_uses_play_package_without_signing_key(self):
        with tempfile.TemporaryDirectory() as temp:
            gradle = Path(temp) / "build.gradle.kts"
            gradle.write_text(DONOR)
            prepare_gradle(gradle, 999999, "ci-unsigned-999999", unsigned_ci=True)
            contents = gradle.read_text()
            self.assertIn('applicationId = "com.arcanatable"', contents)
            self.assertIn("versionCode = 999999", contents)
            self.assertIn('buildConfigField("boolean", "STUDIO_ENABLED", "true")', contents)
            self.assertNotIn('create("chimeraPlay")', contents)
            self.assertNotIn("CHIMERA_UPLOAD_STORE_PASSWORD", contents)

    def test_prevents_outdated_target(self):
        with tempfile.TemporaryDirectory() as temp:
            gradle = Path(temp) / "build.gradle.kts"
            gradle.write_text(DONOR.replace("targetSdk = 36", "targetSdk = 35"))
            with self.assertRaisesRegex(ValueError, "targetSdk 36"):
                prepare_gradle(gradle, 143, "test")

    def test_rejects_unexpected_gradle_file(self):
        with tempfile.TemporaryDirectory() as temp:
            gradle = Path(temp) / "build.gradle.kts"
            gradle.write_text('targetSdk = 36')
            with self.assertRaisesRegex(ValueError, "not the expected"):
                prepare_gradle(gradle, 143, "test")

    def test_rejects_bad_version_name(self):
        with tempfile.TemporaryDirectory() as temp:
            gradle = Path(temp) / "build.gradle.kts"
            gradle.write_text(DONOR)
            with self.assertRaisesRegex(ValueError, "Unsafe versionName"):
                prepare_gradle(gradle, 143, 'bad"version')


if __name__ == "__main__":
    unittest.main()
