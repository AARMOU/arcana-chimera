"""Host Lobby restart patch: fail-closed anchors, both host paths, no silent early return left."""
import importlib.util
from pathlib import Path
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[1]
spec = importlib.util.spec_from_file_location("restart", ROOT / "scripts/patch_chimera_restart_host.py")
restart = importlib.util.module_from_spec(spec)
spec.loader.exec_module(restart)

FIXTURE = """internal fun TableController.startMultiWifiHost() {
    if (offlineStarting || argentumEnabled || wifiServer != null) return
}

internal fun TableController.startMultiOnlineHost() {
    if (offlineStarting || argentumEnabled || wifiServer != null) return
}
"""


class RestartHostTests(unittest.TestCase):
    def setUp(self):
        tmp = tempfile.TemporaryDirectory()
        self.addCleanup(tmp.cleanup)
        self.app = Path(tmp.name)
        self.kt = self.app / "src/main/java/com/arcanatable/TableWifi.kt"
        self.kt.parent.mkdir(parents=True)

    def test_both_hosts_end_stale_session(self):
        self.kt.write_text(FIXTURE)
        restart.patch(self.app)
        out = self.kt.read_text()
        self.assertNotIn("argentumEnabled || wifiServer != null) return", out)
        self.assertEqual(out.count("if (argentumEnabled || wifiServer != null) chimeraEndStaleSession()"), 2)
        self.assertIn("closeArgentumSession(concede = true)", out)
        self.assertNotIn("Argentum", out.split("chimeraEndStaleSession() {")[1].split("message =")[1])  # on-screen wording
        with self.assertRaises(RuntimeError):
            restart.patch(self.app)

    def test_missing_anchor_fails(self):
        self.kt.write_text("internal fun TableController.startMultiWifiHost() {\n}\n")
        with self.assertRaises(RuntimeError):
            restart.patch(self.app)


if __name__ == "__main__":
    unittest.main()
