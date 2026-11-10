import os
from pathlib import Path
import sys
import tempfile
import unittest
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "scripts"))
from play_release_tools import (  # noqa: E402
    PINNED_UPLOAD_CERT_SHA256, fingerprint, read_certificate_sha256, prepare_gradle,
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

    def test_pinned_console_upload_certificate_is_valid_sha256(self):
        self.assertEqual(
            PINNED_UPLOAD_CERT_SHA256,
            fingerprint("FD:52:2A:67:4B:8A:03:8A:D8:C2:08:4B:9B:F8:BD:BF:"
                        "9B:89:80:9E:0D:C2:F1:19:7F:88:AD:1C:95:68:03:F7")
        )

    def test_explicit_certificate_override_cannot_bypass_pin(self):
        with tempfile.TemporaryDirectory() as temp:
            keyfile = Path(temp) / "key.jks"
            keyfile.write_bytes(b"fixture")
            env = {
                "CHIMERA_UPLOAD_KEYSTORE": str(keyfile),
                "CHIMERA_UPLOAD_STORE_PASSWORD": "fixture",
                "CHIMERA_UPLOAD_KEY_PASSWORD": "fixture",
                "CHIMERA_UPLOAD_ALIAS": "arcana-upload",
                "CHIMERA_EXPECTED_UPLOAD_CERT_SHA256": "AB" * 32,
            }
            with patch.dict(os.environ, env, clear=True):
                with self.assertRaisesRegex(ValueError, "pinned Play Console"):
                    require_signing_environment()

    def test_pinned_certificate_is_returned_when_env_unset(self):
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
                self.assertEqual(expected, PINNED_UPLOAD_CERT_SHA256)

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
