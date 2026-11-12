"""Synthetic donor compatibility and fail-closed tests for Chimera #39."""
import importlib.util
from pathlib import Path
import tempfile
import unittest

PATCH = Path(__file__).resolve().parents[1] / "scripts" / "patch_offline_ai_decks.py"
spec = importlib.util.spec_from_file_location("chimera_ai_offline", PATCH)
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)


class AiOfflinePatchTests(unittest.TestCase):
    def test_refuses_source_drift(self):
        with tempfile.TemporaryDirectory() as t:
            base = Path(t) / "src/main/java/com/arcanatable"
            base.mkdir(parents=True)
            for name in ("TableController.kt", "TableOverlays.kt", "HomeScreen.kt"):
                (base / name).write_text("changed donor", encoding="utf-8")
            with self.assertRaises(RuntimeError):
                module.patch(t)
            self.assertEqual((base / "TableController.kt").read_text(), "changed donor")

    def test_original_selector_remains_stable_until_full_patch(self):
        with tempfile.TemporaryDirectory() as t:
            path = Path(t) / "HomeScreen.kt"
            path.write_text("unchanged", encoding="utf-8")
            with self.assertRaises(RuntimeError):
                module.swap(path, "missing original anchor", "updated")
            self.assertEqual(path.read_text(), "unchanged")

    def test_one_anchor_only(self):
        with tempfile.TemporaryDirectory() as t:
            path = Path(t) / "HomeScreen.kt"
            path.write_text("x\nx\n", encoding="utf-8")
            with self.assertRaises(RuntimeError):
                module.swap(path, "x", "replaced")
            self.assertEqual(path.read_text(), "x\nx\n")

    def test_one_anchor_patch(self):
        with tempfile.TemporaryDirectory() as t:
            path = Path(t) / "HomeScreen.kt"
            path.write_text("before|original|after", encoding="utf-8")
            module.swap(path, "original", "new")
            self.assertEqual(path.read_text(), "before|new|after")


if __name__ == "__main__":
    unittest.main()
