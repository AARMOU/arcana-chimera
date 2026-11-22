"""Fast tests for the Nov 21 memory fixes: crash history, deck editor check, Forge in its own process."""
import importlib.util
from pathlib import Path
import re
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[1]
ENGINE = ROOT / "arcana-overlay/app/src/main/java/com/arcanatable/engine"


def load(name):
    spec = importlib.util.spec_from_file_location(name, ROOT / f"scripts/{name}.py")
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


memory = load("patch_memory_safety")
process = load("patch_forge_process")


class MemorySafetyTests(unittest.TestCase):
    def staged(self, tmp: Path, skip=None) -> Path:
        src = tmp / "src/main/java/com/arcanatable"
        src.mkdir(parents=True)
        (src / "ChimeraCrashLog.kt").write_text("// overlay", encoding="utf-8")
        for name, edits in memory.EDITS.items():
            body = "\n".join(old for old, _ in edits if name != skip)
            (src / name).write_text("// before\n" + body + "\n// after\n", encoding="utf-8")
        return tmp

    def test_applies_once_and_fails_closed(self):
        with tempfile.TemporaryDirectory() as d:
            app = self.staged(Path(d))
            memory.patch(app)
            deck = (app / "src/main/java/com/arcanatable/DeckBuilderScreen.kt").read_text(encoding="utf-8")
            self.assertIn("OutOfMemoryError", deck)
            self.assertIn("Not enough memory", deck)
            with self.assertRaises(ValueError):
                memory.patch(app)
        with tempfile.TemporaryDirectory() as d:
            app = self.staged(Path(d), skip="TableEffects.kt")
            before = (app / "src/main/java/com/arcanatable/MainActivity.kt").read_text(encoding="utf-8")
            with self.assertRaises(ValueError):
                memory.patch(app)
            self.assertEqual(before, (app / "src/main/java/com/arcanatable/MainActivity.kt").read_text(encoding="utf-8"))


class ForgeProcessTests(unittest.TestCase):
    def test_manifest_declares_the_forge_process_once(self):
        with tempfile.TemporaryDirectory() as d:
            app = Path(d)
            engine = app / "src/main/java/com/arcanatable/engine"
            engine.mkdir(parents=True)
            for name in ("ForgeProcessService.kt", "ForgeRemote.kt", "ForgeIpc.kt", "ForgeEngineCore.kt"):
                (engine / name).write_text("//", encoding="utf-8")
            (app / "src/main/AndroidManifest.xml").write_text("<application>\n" + process.ANCHOR + "</application>\n", encoding="utf-8")
            process.patch(app)
            text = (app / "src/main/AndroidManifest.xml").read_text(encoding="utf-8")
            self.assertEqual(1, text.count('android:process=":forge"'))
            with self.assertRaises(ValueError):
                process.patch(app)

    def test_local_host_keeps_arcanas_api(self):
        host = (ENGINE / "LocalArgentumHost.kt").read_text(encoding="utf-8")
        for needle in ("class LocalArgentumHost(", "registry: CardRegistry", "private val emit: (JSONObject) -> Unit",
                       "var quietOpponentTurns: Boolean", "var respondToOwnStack: Boolean", "val isWifi: Boolean",
                       "fun receive(message: JSONObject, from: EntityId = HUMAN_ID)", "fun shutdown()",
                       "fun setGuest(seat: LocalSeat, guest: (JSONObject) -> Unit)",
                       "fun setGuest(seatId: EntityId, seat: LocalSeat, guest: (JSONObject) -> Unit)",
                       "val HUMAN_ID", "val AI_ID", "val GUEST_ID", "fun forgeAssetsDir(): String"):
            self.assertIn(needle, host)
        self.assertIn("ForgeEngineCore(", host)  # fallback in this process

    def test_both_sides_use_the_same_message_kinds(self):
        ipc = (ENGINE / "ForgeIpc.kt").read_text(encoding="utf-8")
        kinds = set(re.findall(r"const val ([A-Z_]+) = \d+", ipc))
        self.assertTrue({"CREATE", "RECEIVE", "ADD_GUEST", "FLAGS", "SHUTDOWN", "EMIT", "PROGRESS"} <= kinds)
        service = (ENGINE / "ForgeProcessService.kt").read_text(encoding="utf-8")
        remote = (ENGINE / "ForgeRemote.kt").read_text(encoding="utf-8")
        for kind in ("CREATE", "RECEIVE", "ADD_GUEST", "FLAGS", "SHUTDOWN"):
            self.assertIn(f"ForgeIpc.{kind}", service)
            self.assertIn(f"ForgeIpc.{kind}", remote)
        for kind in ("EMIT", "PROGRESS"):
            self.assertIn(f"ForgeIpc.{kind}", service)
            self.assertIn(f"ForgeIpc.{kind}", remote)

    def test_build_copies_and_runs_everything(self):
        build = (ROOT / "scripts/build_arcana_forge_apk.sh").read_text(encoding="utf-8")
        self.assertIn("ForgeEngineCore ForgeIpc ForgeProcessService ForgeRemote", build)
        self.assertIn("ChimeraCrashLog; do", build)
        self.assertLess(build.index("patch_memory_safety.py"), build.index("patch_forge_process.py"))
        proof = (ROOT / "scripts/forge_host_proof.sh").read_text(encoding="utf-8")
        self.assertIn("-Xmx512m", proof)
        self.assertIn("ForgeMemoryProbe", proof)


if __name__ == "__main__":
    unittest.main()
