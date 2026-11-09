"""No Forge rule tests: only decklist compilation and the Chimera picker overlay contract."""
import importlib.util
import io
import json
import sys
import tarfile
import tempfile
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
spec = importlib.util.spec_from_file_location(
    "wotc_precons", ROOT / "scripts" / "build_wotc_commander_precons.py")
compiler = importlib.util.module_from_spec(spec)
sys.modules[spec.name] = compiler
spec.loader.exec_module(compiler)

def product(name, code="C13", kind="Commander Deck", date="2013-11-01"):
    return {"name": name, "code": code, "type": kind, "releaseDate": date,
            "commander": [{"name": "Commander Name", "count": 1, "colorIdentity": ["W", "U"]}],
            "mainBoard": [{"name": "Island", "count": 49}, {"name": "Plains", "count": 50}]}

class CommanderPreconTests(unittest.TestCase):
    def test_original_decklist_and_identity(self):
        p = compiler.make_deck(product("Test Commander"), {"C13": "Commander 2013"}, "Test_C13", "2026-11-08")
        self.assertEqual(p["setName"], "Commander 2013")
        self.assertEqual(p["colors"], "WU")
        self.assertEqual(p["name"], "Test Commander")
        self.assertEqual(p["decklist"], "Commander\n1 Commander Name\nDeck\n49 Island\n50 Plains\n")

    def test_noncommander_and_incomplete_are_excluded(self):
        self.assertIsNone(compiler.make_deck(product("Theme", kind="Theme Deck"), {}, "Theme_C13", "2026-11-08"))
        d = product("Incomplete")
        d["mainBoard"][0]["count"] = 40
        self.assertIsNone(compiler.make_deck(d, {}, "Incomplete_C13", "2026-11-08"))
        self.assertIsNone(compiler.make_deck(product("Future", date="2027-01-01"), {}, "Future_C13", "2026-11-08"))

    def test_original_six_and_all_other_products_in_sample_archive(self):
        with tempfile.TemporaryDirectory() as temp:
            base = Path(temp)
            sets = base / "sets.json"
            sets.write_text(json.dumps({"data": [{"code": "C13", "name": "Commander 2013"}]}))
            tarpath = base / "decks.tar.gz"
            names = sorted(compiler.SIX) + [f"Example Precon {i}" for i in range(100)]
            with tarfile.open(tarpath, "w:gz") as archive:
                for index, name in enumerate(names):
                    raw = json.dumps({"data": product(name)}).encode()
                    item = tarfile.TarInfo(f"AllDeckFiles/Deck{index}_C13.json")
                    item.size = len(raw)
                    archive.addfile(item, io.BytesIO(raw))
                raw = json.dumps({"data": product("Fan-made", kind="Theme Deck")}).encode()
                item = tarfile.TarInfo("AllDeckFiles/Fan_C13.json")
                item.size = len(raw)
                archive.addfile(item, io.BytesIO(raw))
            result = compiler.build(tarpath, sets, "2026-11-08")
            self.assertEqual(len(result["decks"]), 106)
            self.assertTrue(compiler.SIX.issubset({d["name"] for d in result["decks"]}))
            self.assertFalse(any(d["name"] == "Fan-made" for d in result["decks"]))
            self.assertEqual(len({d["id"] for d in result["decks"]}), 106)

    def test_source_is_chimera_only(self):
        patch = (ROOT / "scripts" / "patch_commander_precons.py").read_text()
        self.assertIn("ChimeraPreconTree", patch)
        self.assertIn("ChimeraPrecons.load", patch)
        self.assertIn("premadeDecks.map", patch) # explicitly hidden from picker
        build = (ROOT / "scripts" / "build_arcana_forge_apk.sh").read_text()
        self.assertIn('build_wotc_commander_precons.py', build)
        self.assertIn('patch_commander_precons.py', build)

if __name__ == "__main__":
    unittest.main()
