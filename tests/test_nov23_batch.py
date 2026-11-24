"""Fast tests for the owner's Nov 23 batch: commander names, LOG for CAST, auto-pass, pop-ups, Catalogue opening
hand, follow the active player, and Forge undo."""
import importlib.util
from pathlib import Path
import re
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[1]
OVERLAY = ROOT / "arcana-overlay/app/src/main/java/com/arcanatable"
HOST = (ROOT / "forge-host/src/main/java/org/arcanachimera/forgehost/ArcanaForgeHost.java").read_text(encoding="utf-8")


def load(name):
    spec = importlib.util.spec_from_file_location(name, ROOT / f"scripts/{name}.py")
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def staged(module, tmp: Path, base: str, skip=None) -> Path:
    for name, edits in module.EDITS.items():
        path = tmp / base / name if base else tmp / name
        path.parent.mkdir(parents=True, exist_ok=True)
        body = "\n".join(old for old, _ in edits if name != skip)
        path.write_text("// before\n" + body + "\n// after\n", encoding="utf-8")
    return tmp


class PatchTests(unittest.TestCase):
    def check(self, module, base, probe, expect, skip):
        with tempfile.TemporaryDirectory() as d:
            app = staged(module, Path(d), base)
            module.patch(app)
            self.assertIn(expect, (app / base / probe).read_text(encoding="utf-8"))
            with self.assertRaises(ValueError):
                module.patch(app)
        with tempfile.TemporaryDirectory() as d:
            app = staged(module, Path(d), base, skip=skip)
            before = {p: p.read_text(encoding="utf-8") for p in Path(d).rglob("*") if p.is_file()}
            with self.assertRaises(ValueError):
                module.patch(app)
            self.assertEqual(before, {p: p.read_text(encoding="utf-8") for p in Path(d).rglob("*") if p.is_file()})

    def test_hand_log_patch(self):
        m = load("patch_hand_log")
        self.check(m, "", "src/main/java/com/arcanatable/HandDrawerControls.kt", 'DrawerControl("LOG", "hand-log"', "src/main/assets/studio/owner-hand.json")
        tbv = dict(m.EDITS)["src/main/java/com/arcanatable/TableBoardViews.kt"]
        self.assertTrue(any('"log" -> onMoreAction("LOG")' in new for _, new in tbv))
        layout = dict(m.EDITS)["src/main/java/com/arcanatable/StudioLayout.kt"]
        self.assertTrue(any('"cast", "log"' in new for _, new in layout))  # Cast stays a Studio action

    def test_table_assist_patch(self):
        m = load("patch_table_assist")
        self.check(m, "src/main/java/com/arcanatable", "TableEffects.kt", "ChimeraAssist.skipWhenCantRespond", "ArgentumScreens.kt")

    def test_build_order(self):
        build = (ROOT / "scripts/build_arcana_forge_apk.sh").read_text(encoding="utf-8")
        self.assertLess(build.index("patch_table_chat.py"), build.index("patch_hand_log.py"))
        self.assertLess(build.index("patch_hand_log.py"), build.index("patch_table_assist.py"))
        self.assertIn("ChimeraCrashLog ChimeraTableAssist; do", build)
        proof = (ROOT / "scripts/forge_host_proof.sh").read_text(encoding="utf-8")
        self.assertIn("org.arcanachimera.forgehost.UndoProof", proof)


class OverlayTests(unittest.TestCase):
    def test_presets_and_skins_show_log(self):
        presets = (OVERLAY / "ChimeraPresets.kt").read_text(encoding="utf-8")
        self.assertNotIn('"cast" to "CAST"', presets)
        self.assertIn('"log" to "LOG"', presets)
        skins = (OVERLAY / "ChimeraSkins.kt").read_text(encoding="utf-8")
        self.assertIn('SkinButton("LOG", look, { g.log() }', skins)

    def test_defaults(self):
        assist = (OVERLAY / "ChimeraTableAssist.kt").read_text(encoding="utf-8")
        for flag, default in [("popups", "true"), ("pop_opponents", "false"), ("expand_active", "true"), ("expand_defender", "true"),
                              ("opening_hand_catalogue", "true"), ("skip_cant_respond", "false"), ("stop_attacked", "true")]:
            self.assertRegex(assist, rf'ChimeraFlag\("{flag}", {default}\)')
        self.assertIn('"Pass to end of turn"', assist)
        self.assertIn("ChimeraAutoPassMenu(autoPassing, autoPassEnabled, onAutoPass, close)", (OVERLAY / "ChimeraHand.kt").read_text(encoding="utf-8"))
        self.assertIn("ChimeraOpeningHandLook()", (OVERLAY / "ChimeraStudio.kt").read_text(encoding="utf-8"))
        self.assertIn("ChimeraAssistSettingsRows()", (OVERLAY / "ChimeraFeatures.kt").read_text(encoding="utf-8"))


def display_name(name, commander, person):
    """Python mirror of ArcanaForgeHost.seatDisplayName, checked against the Java regex below."""
    c = (commander or "").strip()
    if " // " in c:
        c = c.split(" // ")[0]
    c = c.split(",")[0].strip() if "," in c and c.index(",") > 0 else c.strip()
    n = (name or "").strip()
    generic = re.fullmatch(r"(?i)\s*(|you|player|player \d+|human|host|guest|guest \d+|opponent|opponent \d+|computer|computer \d+|ai|ai \d+|ai player|ai player \d+|offline ai|ai · .*)\s*", n)
    if not c:
        return n or ("Player" if person else "AI")
    if not generic:
        return n
    return c if person else "AI · " + c


class HostTests(unittest.TestCase):
    def test_names_use_commanders(self):
        self.assertIn("(?i)\\\\s*(|you|player|player \\\\d+|human|host|guest|guest \\\\d+|opponent|opponent \\\\d+|computer|computer \\\\d+|ai|ai \\\\d+|ai player|ai player \\\\d+|offline ai|ai · .*)\\\\s*", HOST)
        self.assertEqual(display_name("AI 2", "Ghalta, Primal Hunger", False), "AI · Ghalta")
        self.assertEqual(display_name("Offline AI", "Atraxa, Praetors' Voice", False), "AI · Atraxa")
        self.assertEqual(display_name("AI Player", "Ghalta, Primal Hunger", False), "AI · Ghalta")
        self.assertEqual(display_name("YOU", "Pantlaza, Sun-Favored", True), "Pantlaza")
        self.assertEqual(display_name("Aaron", "Pantlaza, Sun-Favored", True), "Aaron")
        self.assertIn("rp.setPlayer(i == 0 ? GamePlayerUtil.getGuiPlayer()", HOST)
        self.assertIn("GamePlayerUtil.createAiPlayer(shown.get(i), i)", HOST)
        self.assertIn("dedupeNames(shown);", HOST)

    def test_undo_wired(self):
        self.assertNotIn('error("Undo isn\'t available in this version.")', HOST)
        self.assertIn("you.tryUndoLastAction()", HOST)
        self.assertIn('.put("undoAvailable", priority && you != null && you.canUndoLastAction())', HOST)
        self.assertIn('.put("chimeraDefenders", defenders(stateCombat))', HOST)


if __name__ == "__main__":
    unittest.main()
