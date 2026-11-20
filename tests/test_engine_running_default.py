"""Fast tests for the in-game engine status notice preference."""
import importlib.util
from pathlib import Path
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[1]
spec = importlib.util.spec_from_file_location("engine_notice", ROOT / "scripts/patch_engine_running_default.py")
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)


class EngineRunningDefaultTests(unittest.TestCase):
    def test_default_off_without_removing_preference(self):
        with tempfile.TemporaryDirectory() as d:
            app = Path(d)
            path = app / "src/main/java/com/arcanatable/TableController.kt"
            path.parent.mkdir(parents=True)
            path.write_text('val visible = ' + module.OLD + "\n")
            module.patch(app)
            patched = path.read_text()
            self.assertIn(module.NEW, patched)
            self.assertIn('"show_engine_status"', patched)
            self.assertNotIn("BuildConfig.PREVIEW", patched)
            with self.assertRaises(ValueError):
                module.patch(app)

    def test_missing_anchor_fails_closed(self):
        with tempfile.TemporaryDirectory() as d:
            app = Path(d)
            path = app / "src/main/java/com/arcanatable/TableController.kt"
            path.parent.mkdir(parents=True)
            path.write_text("no matching engine status preference here")
            with self.assertRaises(ValueError):
                module.patch(app)


if __name__ == "__main__":
    unittest.main()
