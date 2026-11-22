"""Fast tests for the guest rejoin fix (owner, Nov 21)."""
import importlib.util
from pathlib import Path
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[1]
spec = importlib.util.spec_from_file_location("guest_rejoin", ROOT / "scripts/patch_guest_rejoin.py")
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)


def staged(tmp: Path, skip: str | None = None) -> Path:
    src = tmp / "src/main/java/com/arcanatable"
    src.mkdir(parents=True)
    (src / "ChimeraGuestSeat.kt").write_text("// overlay", encoding="utf-8")
    for name, edits in module.EDITS.items():
        body = "\n".join(old for old, _ in edits if name != skip)
        (src / name).write_text("// before\n" + body + "\n// after\n", encoding="utf-8")
    return tmp


class GuestRejoinTests(unittest.TestCase):
    def test_every_anchor_replaced_once(self):
        with tempfile.TemporaryDirectory() as d:
            app = staged(Path(d))
            module.patch(app)
            src = app / "src/main/java/com/arcanatable"
            for name, edits in module.EDITS.items():
                text = (src / name).read_text(encoding="utf-8")
                for old, new in edits:
                    self.assertIn(new, text)
            wifi = (src / "TableWifi.kt").read_text(encoding="utf-8")
            self.assertIn("ChimeraGuestSeat.clear(context)", wifi)  # leaving forgets the seat
            self.assertIn("start(context, guest = true)", wifi)  # guests kept running
            with self.assertRaises(ValueError):  # never applied twice
                module.patch(app)

    def test_missing_anchor_writes_nothing(self):
        with tempfile.TemporaryDirectory() as d:
            app = staged(Path(d), skip="TableOverlays.kt")
            before = (app / "src/main/java/com/arcanatable/ArgentumCloudMulti.kt").read_text(encoding="utf-8")
            with self.assertRaises(ValueError):
                module.patch(app)
            self.assertEqual(before, (app / "src/main/java/com/arcanatable/ArgentumCloudMulti.kt").read_text(encoding="utf-8"))

    def test_build_runs_it_after_the_earlier_patches(self):
        build = (ROOT / "scripts/build_arcana_forge_apk.sh").read_text(encoding="utf-8")
        self.assertIn("patch_guest_rejoin.py", build)
        self.assertIn(" ChimeraGuestSeat", build)
        self.assertLess(build.index("patch_arcana_nov9c.py"), build.index("patch_guest_rejoin.py"))


    def test_host_resends_seat_and_game_start_on_resume(self):
        host = (ROOT / "forge-host/src/main/java/org/arcanachimera/forgehost/ArcanaForgeHost.java").read_text(encoding="utf-8")
        self.assertIn('message.optBoolean("resume")', host)
        self.assertIn("v.resumeStarted =", host)
        seat = (ROOT / "arcana-overlay/app/src/main/java/com/arcanatable/ChimeraGuestSeat.kt").read_text(encoding="utf-8")
        self.assertIn('put("resume", true)', seat)


if __name__ == "__main__":
    unittest.main()
