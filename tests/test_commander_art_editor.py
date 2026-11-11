"""Regression coverage for the Chimera commander-art deck editor overlay.

Synthetic tests exercise exact anchored replacements without touching Arcana.
End-to-end Android compile and on-device rendering remain separate gates.
"""
from pathlib import Path
import importlib.util
import tempfile
import unittest

PATCH = Path(__file__).resolve().parents[1] / "scripts" / "patch_commander_art_editor.py"
spec = importlib.util.spec_from_file_location("patch_commander_art_editor", PATCH)
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)


class CommanderArtPatchTests(unittest.TestCase):
    def test_no_silent_source_drift(self):
        with tempfile.TemporaryDirectory() as temp:
            source = Path(temp) / "DeckBuilderScreen.kt"
            source.write_text("donor changed", encoding="utf-8")
            with self.assertRaises(RuntimeError):
                module.replace_once(source, "expected upstream snippet", "patched")
            self.assertEqual(source.read_text(encoding="utf-8"), "donor changed")

    def test_one_anchor_only(self):
        with tempfile.TemporaryDirectory() as temp:
            source = Path(temp) / "DeckBuilderScreen.kt"
            source.write_text("old\nold\n", encoding="utf-8")
            with self.assertRaises(RuntimeError):
                module.replace_once(source, "old", "new")
            self.assertEqual(source.read_text(encoding="utf-8"), "old\nold\n")

    def test_correct_replacement(self):
        with tempfile.TemporaryDirectory() as temp:
            source = Path(temp) / "DeckBuilderScreen.kt"
            source.write_text("before\nchosen default\nend", encoding="utf-8")
            module.replace_once(source, "chosen default", "ChimeraArt.get(name) ?: default")
            self.assertIn("ChimeraArt.get(name) ?: default", source.read_text(encoding="utf-8"))

    def test_implementation_covers_all_commander_presentation_paths(self):
        source = PATCH.read_text(encoding="utf-8")
        self.assertIn('testTag("commander-change-art")', source)
        self.assertIn("ChimeraArtPicker(name)", source)
        self.assertIn("remember(name, commanderArtRevision)", source)
        self.assertIn("ChimeraArt.get(name) ?: card?.imageUrl", source)
        self.assertIn("ChimeraArt.get(it) ?: catalog.find(it)?.imageUrl", source)
        self.assertIn("ChimeraArt.get(name) ?: homeArtUrl", source)
        self.assertNotIn("ChimeraArt.set(", source)  # let the existing picker own saving


if __name__ == "__main__":
    unittest.main()
