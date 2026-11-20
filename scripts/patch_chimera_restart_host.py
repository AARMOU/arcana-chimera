#!/usr/bin/env python3
"""Chimera-only: Host Lobby must not silently do nothing while an old game is still open.

Phone, Nov 11 2026: after a game froze, the owner went back to the menu and tapped Host Lobby five times; nothing
happened until the app was force-stopped. The host start returned early because the previous session was still
open. Now that session is ended (the owner asked for a new lobby) and the new one starts.

Apply to an ephemeral staged Arcana donor, NEVER to the arcana_table repository.
"""
from pathlib import Path
import sys

HELPER = '''

/** Chimera (owner, Nov 11): a frozen or abandoned game left its session open, and Host Lobby silently did nothing. */
internal fun TableController.chimeraEndStaleSession() {
    recordDiagnostic("Multiplayer: ended the previous game's open session to host a new lobby", DiagnosticCategory.CONNECTION)
    runCatching { closeArgentumSession(concede = true) }
    if (wifiServer != null) runCatching { stopWifi() }
    message = "The previous game was still open, so it was ended to host this lobby."
}
'''


def patch(app: Path) -> None:
    wifi = app / "src/main/java/com/arcanatable/TableWifi.kt"
    source = wifi.read_text(encoding="utf-8")
    if "chimeraEndStaleSession" in source:
        raise RuntimeError("restart-host patch already applied")
    for name in ("startMultiWifiHost", "startMultiOnlineHost"):
        old = (f"internal fun TableController.{name}() {{\n"
               "    if (offlineStarting || argentumEnabled || wifiServer != null) return\n")
        new = (f"internal fun TableController.{name}() {{\n"
               "    if (offlineStarting) return\n"
               "    if (argentumEnabled || wifiServer != null) chimeraEndStaleSession()\n")
        if source.count(old) != 1:
            raise RuntimeError(f"restart-host anchor missing for {name}")
        source = source.replace(old, new)
    wifi.write_text(source.rstrip("\n") + "\n" + HELPER, encoding="utf-8")
    print("Host Lobby ends a stale session instead of doing nothing")


if __name__ == "__main__":
    if len(sys.argv) != 2:
        raise SystemExit("usage: patch_chimera_restart_host.py STAGED_ANDROID_APP")
    patch(Path(sys.argv[1]))
