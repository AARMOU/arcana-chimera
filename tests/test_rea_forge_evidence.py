"""Synthetic-only offline safety and provenance tests for REA forensic kit."""
import importlib.util
import json
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest
import zipfile

ROOT = Path(__file__).resolve().parents[1]
SCRIPT = ROOT / "scripts" / "rea_forge_evidence.py"
spec = importlib.util.spec_from_file_location("rea_forge_evidence", SCRIPT)
rea = importlib.util.module_from_spec(spec)
spec.loader.exec_module(rea)


def fake_apk(path, code):
    with zipfile.ZipFile(path, "w") as z:
        z.writestr("AndroidManifest.xml", "FAKE-MANIFEST")
        z.writestr("classes.dex", code)
        z.writestr("assets/private-deck-name.txt", "PRIVATE-NO-LEAK")


class EvidenceTests(unittest.TestCase):
    def test_components_are_stable_without_private_names(self):
        with tempfile.TemporaryDirectory() as tmp:
            path = Path(tmp) / "sensitive-path.apk"
            fake_apk(path, b"FAKE-DEX-CODE")
            first = rea.snapshot(path)
            self.assertEqual(first, rea.snapshot(path))
            self.assertEqual(first["components"]["dex"]["count"], 1)
            self.assertEqual(first["components"]["assets"]["count"], 1)
            self.assertNotIn("private-deck-name", json.dumps(first))
            self.assertNotIn("sensitive-path", json.dumps(first))

    def test_compiled_code_change_is_detected(self):
        with tempfile.TemporaryDirectory() as tmp:
            a, b = Path(tmp) / "a.apk", Path(tmp) / "b.apk"
            fake_apk(a, b"FIRST-DEX")
            fake_apk(b, b"SECOND-DEX")
            result = rea.compare(rea.snapshot(a), rea.snapshot(b))
            self.assertFalse(result["same_binary"])
            self.assertTrue(result["component_changes"]["dex"])
            self.assertFalse(result["component_changes"]["manifest"])

    def test_bad_zip_and_missing_dex_rejected(self):
        with tempfile.TemporaryDirectory() as tmp:
            bad = Path(tmp) / "bad.apk"
            bad.write_text("not zip")
            with self.assertRaises(ValueError):
                rea.snapshot(bad)
            with zipfile.ZipFile(bad, "w") as z:
                z.writestr("AndroidManifest.xml", "ok")
            with self.assertRaises(ValueError):
                rea.snapshot(bad)

    def test_duplicate_entry_names_rejected(self):
        import warnings
        with tempfile.TemporaryDirectory() as tmp:
            bad = Path(tmp) / "duplicates.apk"
            with warnings.catch_warnings():
                warnings.simplefilter("ignore", UserWarning)
                with zipfile.ZipFile(bad, "w") as z:
                    z.writestr("AndroidManifest.xml", "ok")
                    z.writestr("classes.dex", "a")
                    z.writestr("classes.dex", "b")
            with self.assertRaises(ValueError):
                rea.snapshot(bad)

    def test_version_bounds(self):
        for v in ("v22.23.4", "v24.22.1", "v26.12.1"):
            self.assertTrue(rea._version_ok(v))
        for v in ("v22.16.0", "v24.22.0", "v25.0.0", "invalid"):
            self.assertFalse(rea._version_ok(v))

    def test_no_sensitive_path_in_cli_error(self):
        result = subprocess.run(
            [sys.executable, str(SCRIPT), "inspect", "/secret/path/private.apk"],
            capture_output=True, text=True)
        self.assertEqual(result.returncode, 2)
        self.assertNotIn("/secret/", result.stderr)


if __name__ == "__main__":
    unittest.main()
