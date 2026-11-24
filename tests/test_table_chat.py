"""Fast tests for table chat in Forge multiplayer games (owner, Nov 23)."""
import importlib.util
from pathlib import Path
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[1]
spec = importlib.util.spec_from_file_location("table_chat", ROOT / "scripts/patch_table_chat.py")
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)


def staged(tmp: Path, skip=None) -> Path:
    src = tmp / "src/main/java/com/arcanatable"
    src.mkdir(parents=True)
    for name, edits in module.EDITS.items():
        body = "\n".join(old for old, _ in edits if name != skip)
        (src / name).write_text("// before\n" + body + "\n// after\n", encoding="utf-8")
    return tmp


class TableChatTests(unittest.TestCase):
    def test_applies_once_and_fails_closed(self):
        with tempfile.TemporaryDirectory() as d:
            app = staged(Path(d))
            module.patch(app)
            sent = (app / "src/main/java/com/arcanatable/TableOverlaysB.kt").read_text(encoding="utf-8")
            self.assertIn('put("type", "arcanaChat")', sent)
            with self.assertRaises(ValueError):
                module.patch(app)
        with tempfile.TemporaryDirectory() as d:
            app = staged(Path(d), skip="TableMainLayout.kt")
            before = (app / "src/main/java/com/arcanatable/TableArgentum.kt").read_text(encoding="utf-8")
            with self.assertRaises(ValueError):
                module.patch(app)
            self.assertEqual(before, (app / "src/main/java/com/arcanatable/TableArgentum.kt").read_text(encoding="utf-8"))

    def test_host_relays_chat_and_keeps_it_from_forge(self):
        host = (ROOT / "arcana-overlay/app/src/main/java/com/arcanatable/engine/LocalArgentumHost.kt").read_text(encoding="utf-8")
        receive = host.index("fun receive(message: JSONObject, from: EntityId = HUMAN_ID) {")
        self.assertLess(receive, host.index('"arcanaChat") { relayChat(message, from.value); return }'))
        self.assertIn("guestNames[seatId.value] = ArcanaForgeHost.seatDisplayName(seat.name, seat.commander, true)", host)  # the seat's shown name
        self.assertIn("if (seat != from)", host)  # not echoed to the sender

    def test_build_runs_it(self):
        build = (ROOT / "scripts/build_arcana_forge_apk.sh").read_text(encoding="utf-8")
        self.assertLess(build.index("patch_forge_process.py"), build.index("patch_table_chat.py"))


if __name__ == "__main__":
    unittest.main()
