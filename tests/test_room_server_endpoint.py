"""Fast tests for moving the app's online rooms to Arcana's own domain."""
import importlib.util
import os
from pathlib import Path
import tempfile
import unittest
from unittest import mock

ROOT = Path(__file__).resolve().parents[1]
spec = importlib.util.spec_from_file_location("room_endpoint", ROOT / "scripts/patch_room_server_endpoint.py")
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)

MODEL = '''internal const val DEFAULT_CLOUD_ROOM_ENDPOINT = "https://arcana-table-rooms.arcanamtg.workers.dev"
internal const val LEGACY_EXAMPLE_CLOUD_ROOM_ENDPOINT = "https://arcana-table-rooms.example.workers.dev"

internal fun isLegacyExampleCloudEndpoint(endpoint: String): Boolean =
    endpoint.trim().trimEnd('/').equals(LEGACY_EXAMPLE_CLOUD_ROOM_ENDPOINT, ignoreCase = true)
'''
CLIENT = '''            fail("Enter a valid Cloudflare Worker URL, for example https://arcana-table-rooms.yourname.workers.dev")
'''


def staged(directory):
    src = Path(directory) / "src/main/java/com/arcanatable"
    src.mkdir(parents=True)
    (src / "TableModel.kt").write_text(MODEL)
    (src / "CloudRoomClient.kt").write_text(CLIENT)
    return src


class RoomServerEndpointTests(unittest.TestCase):
    def test_new_default_and_old_default_migrates(self):
        with tempfile.TemporaryDirectory() as d:
            src = staged(d)
            module.patch(d, "rooms.example.com")
            model = (src / "TableModel.kt").read_text()
            self.assertIn('DEFAULT_CLOUD_ROOM_ENDPOINT = "https://rooms.example.com"', model)
            self.assertIn('PREVIOUS_DEFAULT_CLOUD_ROOM_ENDPOINT = "https://arcana-table-rooms.arcanamtg.workers.dev"', model)
            self.assertIn("it.equals(PREVIOUS_DEFAULT_CLOUD_ROOM_ENDPOINT, ignoreCase = true)", model)
            self.assertIn("it.equals(LEGACY_EXAMPLE_CLOUD_ROOM_ENDPOINT, ignoreCase = true)", model)
            self.assertIn("https://rooms.example.com", (src / "CloudRoomClient.kt").read_text())
            with self.assertRaises(ValueError):  # anchors are gone: never patch twice
                module.patch(d, "rooms.example.com")

    def test_hostname_is_cleaned_and_validated(self):
        with mock.patch.dict(os.environ, {"ROOM_SERVER_HOSTNAME": " HTTPS://Rooms.Example.com/ "}):
            self.assertEqual(module.configured_hostname(), "rooms.example.com")
        with mock.patch.dict(os.environ, {"ROOM_SERVER_HOSTNAME": "not a host"}):
            with self.assertRaises(ValueError):
                module.configured_hostname()
        with mock.patch.dict(os.environ, {"ROOM_SERVER_HOSTNAME": ""}):
            self.assertEqual(module.configured_hostname(), "")


if __name__ == "__main__":
    unittest.main()
