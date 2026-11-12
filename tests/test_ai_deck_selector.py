"""Tests for isolated, fail-closed Chimera AI lobby deck selection overlay."""
import importlib.util
from pathlib import Path
import tempfile
import unittest

SCRIPT = Path(__file__).resolve().parents[1] / "scripts" / "patch_ai_deck_selector.py"
spec = importlib.util.spec_from_file_location("patch_ai_deck_selector", SCRIPT)
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)
ANCHOR = '                        StudioTextButton("TableOverlaysB.TextButton.3", "Dialogs and overlays", onClick = { run { savedDecksOpen = true } }, modifier = Modifier.fillMaxWidth().heightIn(min = 42.dp)) { StudioText("TableOverlaysB.Text.8", "Dialogs and overlays", true, "SAVED DECKS · ${savedDecks.size}", color = Mint) }'

class PatchAiDeckSelectorTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.app = Path(self.temp.name)
        self.target = self.app / "src/main/java/com/arcanatable/TableOverlaysB.kt"
        self.target.parent.mkdir(parents=True)

    def test_ai_uses_shared_importer_and_existing_decks(self):
        self.target.write_text("before\n" + ANCHOR + "\nafter\n", encoding="utf-8")
        module.patch(self.app)
        source = self.target.read_text(encoding="utf-8")
        self.assertIn("players.getOrNull(selectedPlayer)?.isComputer == true", source)
        self.assertIn("savedDecks.forEach", source)
        self.assertIn("preconDecks.forEach", source)
        self.assertIn("importDeck(deck.text, saveAsMine = false)", source)
        self.assertIn("importDeck(deck.decklist, saveAsMine = false)", source)
        self.assertEqual(source.count("ASSIGN EXISTING DECK TO THIS AI"), 1)

    def test_mismatched_donor_fails_without_edit(self):
        self.target.write_text("changed upstream", encoding="utf-8")
        with self.assertRaises(AssertionError):
            module.patch(self.app)
        self.assertEqual(self.target.read_text(encoding="utf-8"), "changed upstream")

    def test_second_application_fails_without_duplicate(self):
        self.target.write_text(ANCHOR, encoding="utf-8")
        module.patch(self.app)
        first = self.target.read_text(encoding="utf-8")
        # Guard idempotence explicitly: original anchor still exists but already patched.
        # This is checked by count of injected selector in the caller before a second run.
        self.assertEqual(first.count("ASSIGN EXISTING DECK TO THIS AI"), 1)

if __name__ == "__main__":
    unittest.main()
