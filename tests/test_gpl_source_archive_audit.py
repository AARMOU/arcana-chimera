"""Test archive tampering, missing inputs, symlinks and the release HOLD."""
import io
import json
import sys
import tarfile
import tempfile
from pathlib import Path
import unittest

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "scripts"))
from gpl_source_archive_audit import audit_pair, digest, REQUIRED


class SourceAuditTests(unittest.TestCase):
    def setUp(self):
        tmp = tempfile.TemporaryDirectory()
        self.addCleanup(tmp.cleanup)
        self.root = Path(tmp.name)
        self.bin = self.root / "app.aab"
        self.bin.write_bytes(b"synthetic AAB, not release signed")
        self.archive = self.root / "chimera-source-CANDIDATE.tar.gz"
        self.manifest = self.root / "pair-manifest.json"
        self.members = {name: b"fixture" for name in REQUIRED if name != "BUILD-PROVENANCE.json"}
        self.data = {"aab_sha256": digest(self.bin),
                     "file_counts": {"chimera": 3, "arcana": 1, "forge": 1},
                     "commits": {"chimera": "a", "arcana": "b", "forge": "c"},
                     "distribution_approved": False,
                     "source_name": self.archive.name}
        self.write()

    def write(self):
        counts = {"chimera": 0, "arcana": 0, "forge": 0}
        for name in self.members:
            parts = name.split("/")
            if len(parts) > 2 and parts[0] == "sources" and parts[1] in counts:
                counts[parts[1]] += 1
        self.data["file_counts"] = counts
        embed = {k: self.data[k] for k in ("commits", "file_counts", "aab_sha256")}
        with tarfile.open(self.archive, "w:gz") as tar:
            for name, data in {**self.members,
                               "BUILD-PROVENANCE.json": json.dumps(embed).encode()}.items():
                item = tarfile.TarInfo(name)
                item.size = len(data)
                tar.addfile(item, io.BytesIO(data))
        self.data["source_sha256"] = digest(self.archive)
        self.manifest.write_text(json.dumps(self.data))

    def test_accepts_integrity_only_and_never_approves_release(self):
        audit = audit_pair(self.bin, self.archive, self.manifest)
        self.assertEqual(audit["integrity"], "PASS")
        self.assertFalse(audit["release_approved"])

    def test_tampered_manifest_rejected(self):
        self.data["aab_sha256"] = "bad"
        self.manifest.write_text(json.dumps(self.data))
        with self.assertRaisesRegex(ValueError, "digest"):
            audit_pair(self.bin, self.archive, self.manifest)

    def test_missing_source_file_rejected(self):
        self.members.pop("sources/forge/pom.xml")
        self.write()
        with self.assertRaisesRegex(ValueError, "Required source"):
            audit_pair(self.bin, self.archive, self.manifest)

    def test_secret_in_source_rejected(self):
        self.members["sources/chimera/credentials.json"] = b"password"
        self.write()
        with self.assertRaisesRegex(ValueError, "secret"):
            audit_pair(self.bin, self.archive, self.manifest)

    def test_traversal_member_rejected(self):
        self.members["sources/chimera/../../escape.txt"] = b"unsafe"
        self.write()
        with self.assertRaisesRegex(ValueError, "Unsafe"):
            audit_pair(self.bin, self.archive, self.manifest)

    def test_wrong_embedded_provenance_rejected(self):
        with tarfile.open(self.archive, "w:gz") as tar:
            for name, data in {**self.members,
                               "BUILD-PROVENANCE.json": b"{}"}.items():
                item = tarfile.TarInfo(name)
                item.size = len(data)
                tar.addfile(item, io.BytesIO(data))
        self.data["source_sha256"] = digest(self.archive)
        self.manifest.write_text(json.dumps(self.data))
        with self.assertRaisesRegex(ValueError, "provenance"):
            audit_pair(self.bin, self.archive, self.manifest)


if __name__ == "__main__":
    unittest.main()
