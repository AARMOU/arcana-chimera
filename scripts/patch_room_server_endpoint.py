#!/usr/bin/env python3
"""Point the app's online rooms at Arcana's own room-server domain.

Reads ROOM_SERVER_HOSTNAME from config/room-server.env (or the environment).
Empty = no change: the app keeps the old arcana-table-rooms workers.dev Worker.

When set:
* the default room server becomes https://<hostname>;
* phones that saved the OLD default (most installs) move to the new one on
  their next launch, exactly like the old example address already does;
* a custom address a player typed in themselves is left alone.
Because the address is now a domain the owner controls, the server behind it
can later move anywhere with a DNS change and no app update.
"""
from pathlib import Path
import os
import re
import sys

OLD_DEFAULT = "https://arcana-table-rooms.arcanamtg.workers.dev"
ROOT = Path(__file__).resolve().parent.parent


def configured_hostname():
    value = os.environ.get("ROOM_SERVER_HOSTNAME")
    if value is None:
        config = ROOT / "config/room-server.env"
        for line in config.read_text(encoding="utf-8").splitlines():
            if line.startswith("ROOM_SERVER_HOSTNAME="):
                value = line.split("=", 1)[1]
    value = (value or "").strip().lower()
    value = re.sub(r"^https?://", "", value).rstrip("/")
    if value and not re.fullmatch(r"[a-z0-9-]+(\.[a-z0-9-]+)+", value):
        raise ValueError(f"ROOM_SERVER_HOSTNAME is not a hostname: {value!r}")
    return value


def replace_once(text, old, new, what):
    if text.count(old) != 1:
        raise ValueError(f"{what}: expected one anchor, found {text.count(old)}")
    return text.replace(old, new)


def patch(app, hostname):
    src = Path(app) / "src/main/java/com/arcanatable"
    model = src / "TableModel.kt"
    text = model.read_text(encoding="utf-8")
    text = replace_once(text,
        f'internal const val DEFAULT_CLOUD_ROOM_ENDPOINT = "{OLD_DEFAULT}"',
        f'internal const val DEFAULT_CLOUD_ROOM_ENDPOINT = "https://{hostname}"\n'
        f'internal const val PREVIOUS_DEFAULT_CLOUD_ROOM_ENDPOINT = "{OLD_DEFAULT}"',
        "default room server")
    text = replace_once(text,
        '    endpoint.trim().trimEnd(\'/\').equals(LEGACY_EXAMPLE_CLOUD_ROOM_ENDPOINT, ignoreCase = true)',
        '    endpoint.trim().trimEnd(\'/\').let {\n'
        '        it.equals(LEGACY_EXAMPLE_CLOUD_ROOM_ENDPOINT, ignoreCase = true) ||\n'
        '            it.equals(PREVIOUS_DEFAULT_CLOUD_ROOM_ENDPOINT, ignoreCase = true)\n'
        '    }',
        "saved old default migrates to the new server")
    model.write_text(text, encoding="utf-8")

    client = src / "CloudRoomClient.kt"
    text = client.read_text(encoding="utf-8")
    text = replace_once(text,
        'fail("Enter a valid Cloudflare Worker URL, for example https://arcana-table-rooms.yourname.workers.dev")',
        f'fail("Enter a valid room server address, for example https://{hostname}")',
        "address hint")
    client.write_text(text, encoding="utf-8")
    print(f"Online rooms: default server https://{hostname}; saved old Worker address migrates automatically")


if __name__ == "__main__":
    if len(sys.argv) != 2:
        raise SystemExit("Usage: patch_room_server_endpoint.py STAGED_ANDROID_APP_DIR")
    host = configured_hostname()
    if not host:
        print("Online rooms: ROOM_SERVER_HOSTNAME not set; keeping the Cloudflare Worker address")
    else:
        patch(sys.argv[1], host)
