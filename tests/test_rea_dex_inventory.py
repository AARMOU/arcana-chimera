"""Synthetic DEX only: no private APK, card database or user diagnostics in tests."""
import importlib.util
from pathlib import Path
import struct
import subprocess
import sys
import tempfile
import unittest
import zipfile

SCRIPT = Path(__file__).resolve().parents[1] / "scripts" / "rea_dex_inventory.py"
spec = importlib.util.spec_from_file_location("rea_dex_inventory", SCRIPT)
audit = importlib.util.module_from_spec(spec)
spec.loader.exec_module(audit)


def minimal_dex(class_name="Lforge/game/Game;", with_method=False):
    strings = [class_name] + (["handle"] if with_method else [])
    data = bytearray(b"\0" * 0x70)
    data[:8] = b"dex\n035\x00"
    strings_at = len(data)
    data.extend(b"\0" * (4 * len(strings)))
    types_at = len(data)
    data.extend(b"\0" * 4)
    method_at = len(data)
    if with_method:
        data.extend(b"\0" * 8)
    classes_at = len(data)
    data.extend(b"\0" * 32)
    if with_method:
        class_data_at = len(data)
        data.extend(b"\x00\x00\x01\x00\x00\x01\x00")
        struct.pack_into("<I", data, classes_at + 24, class_data_at)
        struct.pack_into("<HHI", data, method_at, 0, 0, 1)
    for i, entry in enumerate(strings):
        struct.pack_into("<I", data, strings_at + i * 4, len(data))
        encoded = entry.encode("ascii")
        assert len(encoded) < 0x80
        data.extend(bytes([len(encoded)]) + encoded + b"\x00")
    struct.pack_into("<I", data, types_at, 0)
    struct.pack_into("<I", data, classes_at, 0)
    for off, value in {
        0x20: len(data), 0x24: 0x70,
        0x38: len(strings), 0x3C: strings_at,
        0x40: 1, 0x44: types_at,
        0x58: int(with_method), 0x5C: method_at,
        0x60: 1, 0x64: classes_at,
    }.items():
        struct.pack_into("<I", data, off, value)
    return bytes(data)


class DexTests(unittest.TestCase):
    def test_forge_class_and_method(self):
        report = audit.parse_dex(minimal_dex(with_method=True))
        self.assertEqual(report["class_definitions"], 1)
        self.assertEqual(report["package_counts"]["forge"], 1)
        self.assertEqual(report["targets"]["forge_game"], ["handle"])

    def test_argentum_class_does_not_imply_runtime_usage(self):
        report = audit.parse_dex(minimal_dex("Lcom/wingedsheep/engine/Sample;"))
        self.assertEqual(report["package_counts"]["argentum"], 1)
        self.assertFalse(report["targets"])

    def test_truncated_and_wrong_magic_rejected(self):
        with self.assertRaises(ValueError):
            audit.parse_dex(b"invalid")
        with self.assertRaises((ValueError, struct.error)):
            audit.parse_dex(minimal_dex()[:120])

    def test_archive_summary_no_private_filenames(self):
        with tempfile.TemporaryDirectory() as tmp:
            apk = Path(tmp) / "private-deck-identity.apk"
            with zipfile.ZipFile(apk, "w") as z:
                z.writestr("classes.dex", minimal_dex())
                z.writestr("assets/secret-deck-cards.txt", "SECRET NOT FOR PUBLICATION")
            report = audit.inspect(apk)
            self.assertEqual(report["dex_files"], 1)
            self.assertEqual(report["class_definitions"], 1)
            self.assertNotIn("private-deck", str(report))
            self.assertNotIn("secret-deck", str(report))

    def test_duplicate_target_cross_dex_rejected(self):
        with tempfile.TemporaryDirectory() as tmp:
            apk = Path(tmp) / "duplicate.apk"
            with zipfile.ZipFile(apk, "w") as z:
                z.writestr("classes.dex", minimal_dex())
                z.writestr("classes2.dex", minimal_dex())
            with self.assertRaises(ValueError):
                audit.inspect(apk)

    def test_cli_path_redaction(self):
        r = subprocess.run([sys.executable, str(SCRIPT), "/very/private/missing.apk"], capture_output=True, text=True)
        self.assertEqual(r.returncode, 2)
        self.assertNotIn("very/private", r.stderr)


if __name__ == "__main__":
    unittest.main()
