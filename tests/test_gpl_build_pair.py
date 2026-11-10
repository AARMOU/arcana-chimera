"""Offline synthetic regression tests; never build or distribute a real APK."""
import importlib.util
import json
from pathlib import Path
import subprocess
import tarfile
import tempfile
import unittest

module_path = Path(__file__).resolve().parents[1] / "scripts/gpl_build_pair.py"
spec = importlib.util.spec_from_file_location("gpl_build_pair", module_path)
gpl = importlib.util.module_from_spec(spec)
spec.loader.exec_module(gpl)


def git(folder, *args):
    return subprocess.run(["git", "-C", str(folder), *args], capture_output=True,
                          check=True).stdout.decode().strip()


class GPLPairTests(unittest.TestCase):
    def setUp(self):
        t = tempfile.TemporaryDirectory()
        self.addCleanup(t.cleanup)
        base = Path(t.name)
        self.repos = {}
        for name in ("chimera", "arcana", "forge"):
            p = base / name
            p.mkdir()
            git(p, "init", "-q")
            git(p, "config", "user.name", "Test")
            git(p, "config", "user.email", "test@example.invalid")
            (p / "sample.txt").write_text(name)
            if name == "chimera":
                lic = p / "third_party_licenses"
                lic.mkdir()
                (lic / "Forge-GPL-3.0.txt").write_text("Synthetic license fixture")
            git(p, "add", ".")
            git(p, "commit", "-qm", "baseline")
            self.repos[name] = p
        self.apk = base / "test.apk"
        self.apk.write_bytes(b"not an actual APK")
        self.out = base / "out"

    def pair(self, **kwargs):
        a = self.repos["arcana"]
        f = self.repos["forge"]
        return gpl.make_pair(apk=kwargs.get("apk", self.apk), out=self.out,
                             expected_arcana=kwargs.get("expected_arcana", git(a, "rev-parse", "HEAD")),
                             expected_forge=git(f, "rev-parse", "HEAD"),
                             artifact_type=kwargs.get("artifact_type", "apk"), **self.repos)

    def test_modified_and_generated_android_source_is_included(self):
        arcana = self.repos["arcana"]
        (arcana / "sample.txt").write_text("patched")
        overlay = arcana / "android/app/src/main/java/com/example/Overlay.kt"
        overlay.parent.mkdir(parents=True)
        overlay.write_text("class Overlay")
        manifest = self.pair()
        self.assertFalse(manifest["distribution_approved"])
        with tarfile.open(self.out / manifest["source_name"]) as bundle:
            self.assertEqual(bundle.extractfile("sources/arcana/sample.txt").read(), b"patched")
            self.assertEqual(bundle.extractfile(
                "sources/arcana/android/app/src/main/java/com/example/Overlay.kt"
            ).read(), b"class Overlay")
            internal = json.load(bundle.extractfile("BUILD-PROVENANCE.json"))
            self.assertEqual(internal["apk_sha256"], manifest["apk_sha256"])

    def test_aab_paired_with_correct_artifact_hash(self):
        aab = self.apk.with_suffix(".aab")
        aab.write_bytes(b"synthetic, not a signed AAB")
        manifest = self.pair(apk=aab, artifact_type="aab")
        self.assertIn("aab_sha256", manifest)
        self.assertEqual(manifest["aab_name"], aab.name)
        self.assertNotIn("apk_sha256", manifest)
        self.assertFalse(manifest["distribution_approved"])
        with tarfile.open(self.out / manifest["source_name"]) as bundle:
            internal = json.load(bundle.extractfile("BUILD-PROVENANCE.json"))
            self.assertEqual(internal["aab_sha256"], manifest["aab_sha256"])

    def test_wrong_revision_blocks(self):
        with self.assertRaisesRegex(RuntimeError, "revision mismatch"):
            self.pair(expected_arcana="0000000000")

    def test_missing_apk_blocks(self):
        with self.assertRaisesRegex(RuntimeError, "nonempty APK"):
            self.pair(apk=self.apk.parent / "missing.apk")

    def test_only_explicit_public_pem_is_included(self):
        chimera = self.repos["chimera"]
        public = chimera / "scripts/delivery/frankenarcana-public.pem"
        public.parent.mkdir(parents=True)
        public.write_text("-----BEGIN PUBLIC KEY-----\\nTEST\\n-----END PUBLIC KEY-----\\n")
        git(chimera, "add", ".")
        git(chimera, "commit", "-qm", "track-public-key")
        manifest = self.pair()
        with tarfile.open(self.out / manifest["source_name"]) as bundle:
            self.assertIsNotNone(bundle.getmember(
                "sources/chimera/scripts/delivery/frankenarcana-public.pem"))
        secret = chimera / "scripts/delivery/private.pem"
        secret.write_text("-----BEGIN PRIVATE KEY-----\\nFIXTURE\\n")
        git(chimera, "add", ".")
        git(chimera, "commit", "-qm", "track-dangerous-file")
        with self.assertRaisesRegex(RuntimeError, "Potential credential"):
            self.pair()

    def test_secrets_block_without_leaving_archive(self):
        p = self.repos["arcana"] / "android/app/src/main/assets/credentials.json"
        p.parent.mkdir(parents=True)
        p.write_text("secret")
        with self.assertRaisesRegex(RuntimeError, "Potential credential"):
            self.pair()
        self.assertFalse((self.out / "chimera-source-CANDIDATE.tar.gz").exists())


if __name__ == "__main__":
    unittest.main()
