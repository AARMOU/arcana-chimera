"""Tests for menu-only Chimera GPL notice navigation; no Forge compile."""
import ast
import importlib.util
from pathlib import Path
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[1]
spec = importlib.util.spec_from_file_location("legal_nav", ROOT / "scripts/patch_chimera_legal_navigation.py")
nav = importlib.util.module_from_spec(spec)
spec.loader.exec_module(nav)


def edits_from_script():
    code = ast.parse((ROOT / "scripts/patch_chimera_legal_navigation.py").read_text())
    entries = []
    for node in ast.walk(code):
        if (isinstance(node, ast.Call) and isinstance(node.func, ast.Name)
                and node.func.id == "replace_once"
                and len(node.args) == 3
                and isinstance(node.args[0], ast.Name)
                and isinstance(node.args[1], ast.Constant)):
            entries.append((node.args[0].id, node.args[1].value))
    return entries


class LegalNavigationTests(unittest.TestCase):
    def test_all_expected_menu_targets_patch_without_gpl_removal(self):
        with tempfile.TemporaryDirectory() as d:
            app = Path(d)
            folder = app / "src/main/java/com/arcanatable"
            folder.mkdir(parents=True)
            mapping = {"features": "ChimeraFeatures.kt", "xp": "ChimeraXp.kt",
                       "skins": "ChimeraSkins.kt", "settings": "TableDialogs.kt",
                       "legal": "ChimeraLegal.kt"}
            edits = edits_from_script()
            self.assertEqual(len(edits), 6)
            for variable, filename in mapping.items():
                file = folder / filename
                file.write_text("\n".join(anchor for key, anchor in edits if key == variable))
            nav.patch(app)
            features = (folder / mapping["features"]).read_text()
            xp = (folder / mapping["xp"]).read_text()
            skins = (folder / mapping["skins"]).read_text()
            settings = (folder / mapping["settings"]).read_text()
            legal = (folder / mapping["legal"]).read_text()
            self.assertNotIn('"LICENSES" to', features)
            self.assertNotIn('"Licenses & source" to', xp)
            self.assertNotIn('Text("Licenses & source")', skins)
            self.assertEqual(settings.count('LinkRow("Licenses & source code"'), 1)
            self.assertIn("ChimeraLegal.open = true", settings)
            self.assertIn("WITHOUT WARRANTY", legal)
            self.assertIn("GPLv3", legal)
            with self.assertRaises(RuntimeError):
                nav.patch(app)

    def test_unknown_structure_is_not_silently_skipped(self):
        with tempfile.TemporaryDirectory() as d:
            target = Path(d) / "ChimeraFeatures.kt"
            target.write_text("not the expected source")
            with self.assertRaises(RuntimeError):
                nav.replace_once(target, "NOT FOUND", "change")


if __name__ == "__main__":
    unittest.main()
