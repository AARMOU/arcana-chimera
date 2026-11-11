"""ALWAYS button patch: fail-closed anchor, Forge always-accept only for triggers, this-turn behaviour kept."""
import importlib.util
from pathlib import Path
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[1]
spec = importlib.util.spec_from_file_location("always", ROOT / "scripts/patch_chimera_always_yes.py")
always = importlib.util.module_from_spec(spec)
spec.loader.exec_module(always)


class AlwaysYesTests(unittest.TestCase):
    def setUp(self):
        tmp = tempfile.TemporaryDirectory()
        self.addCleanup(tmp.cleanup)
        self.app = Path(tmp.name)
        self.kt = self.app / "src/main/java/com/arcanatable/TableOverlays.kt"
        self.kt.parent.mkdir(parents=True)

    def test_trigger_questions_send_always(self):
        self.kt.write_text("    if (x) " + always.OLD + "\n")
        always.patch(self.app)
        out = self.kt.read_text()
        self.assertIn('.put("always", true)', out)
        self.assertIn('decision.optBoolean("alwaysAvailable")', out)
        self.assertIn("ALWAYS THIS TURN", out)  # other yes/no questions unchanged
        self.assertIn('"Always yes for this card\'s trigger: ', out)  # on-screen/diagnostic wording has no "Argentum"
        with self.assertRaises(RuntimeError):
            always.patch(self.app)

    def test_missing_anchor_fails(self):
        self.kt.write_text("nothing here\n")
        with self.assertRaises(RuntimeError):
            always.patch(self.app)


if __name__ == "__main__":
    unittest.main()
