"""Cheap host-survival patch tests. No Forge, Android SDK or Cloudflare deploy."""
import ast
import importlib.util
from pathlib import Path
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[1]


def load(name, path):
    spec = importlib.util.spec_from_file_location(name, path)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


android = load("patch_chimera_host_survival", ROOT / "scripts/patch_chimera_host_survival.py")
worker = load("patch_directed_room_resumption", ROOT / "scripts/patch_directed_room_resumption.py")


def anchors(path, function):
    tree = ast.parse(path.read_text())
    return [
        (node.args[0].id if isinstance(node.args[0], ast.Name) else "?",
         node.args[1].value)
        for node in ast.walk(tree)
        if isinstance(node, ast.Call)
        and isinstance(node.func, ast.Name)
        and node.func.id == function
        and len(node.args) >= 3
        and isinstance(node.args[1], ast.Constant)
        and isinstance(node.args[1].value, str)
    ]


class HostSurvivalTests(unittest.TestCase):
    def setUp(self):
        tmp = tempfile.TemporaryDirectory()
        self.addCleanup(tmp.cleanup)
        self.root = Path(tmp.name)

    def test_android_overlay_is_fail_closed_and_idempotent_rejected(self):
        app = self.root / "app"
        src = app / "src/main/java/com/arcanatable"
        src.mkdir(parents=True)
        names = {"rooms": "ArgentumCloudMulti.kt", "wifi": "TableWifi.kt"}
        all_anchors = anchors(ROOT / "scripts/patch_chimera_host_survival.py", "change")
        self.assertGreaterEqual(len(all_anchors), 8)
        for role, filename in names.items():
            (src / filename).write_text("\n".join(a for key, a in all_anchors if key == role))
        (src / "ChimeraRoomHostGuardService.kt").write_text("package com.arcanatable")
        manifest = app / "src/main/AndroidManifest.xml"
        manifest.parent.mkdir(parents=True, exist_ok=True)
        manifest.write_text('<manifest><application></application></manifest>')
        android.patch_app(app)
        self.assertIn("ChimeraRoomHostGuardService", manifest.read_text())
        self.assertIn("45L * 60_000L", (src / "ArgentumCloudMulti.kt").read_text())
        self.assertIn("ChimeraRoomHostGuardService.start(context)", (src / "TableWifi.kt").read_text())
        with self.assertRaises(RuntimeError):
            android.patch_app(app)

    def test_android_anchor_mismatch_does_not_silently_skip(self):
        app = self.root / "missing.kt"
        app.write_text("a")
        with self.assertRaises(RuntimeError):
            android.change(app, "absent", "other")

    def test_worker_patch_is_fail_closed_and_retains_seat_credentials(self):
        path = self.root / "index.ts"
        fixture = "\n".join(a for key, a in
            anchors(ROOT / "scripts/patch_directed_room_resumption.py", "replace_once"))
        self.assertIn("storage.deleteAll", fixture)
        path.write_text(fixture)
        worker.patch_worker(path)
        src = path.read_text()
        self.assertIn("DIRECTED_ROOM_RECOVERY_MS", src)
        self.assertIn("offlineDeadline", src)
        self.assertIn("successor", src)
        self.assertIn("!this.room.relayMode && this.room.state.gameStarted", src)
        with self.assertRaises(ValueError):
            worker.patch_worker(path)

    def test_worker_patch_does_not_deploy(self):
        source = (ROOT / "scripts/patch_directed_room_resumption.py").read_text()
        self.assertNotIn("wrangler deploy", source)
        self.assertNotIn("subprocess.", source)


if __name__ == "__main__":
    unittest.main()
