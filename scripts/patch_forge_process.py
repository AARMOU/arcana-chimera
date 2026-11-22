#!/usr/bin/env python3
"""Chimera-only: Forge runs in its own process with its own memory (owner, Nov 21).

Android gives each process of the app its own heap (512 MB with largeHeap on the owner's Fold). Offline Forge games
used 480-511 MB of the app's single heap from turn 1, so opening the deck editor, or a long game, ran the whole app out
of memory. ForgeProcessService (android:process=":forge") now holds Forge; LocalArgentumHost passes the same messages
across (ForgeRemote, ForgeIpc) and falls back to running Forge in the app's process if the service can't start.

This script only declares the service; the Kotlin files are Chimera overlay files copied in by the build.
Fail-closed: the anchor must match exactly once. Apply to an ephemeral staged donor, NEVER to arcana_table.
"""
from pathlib import Path
import sys

ANCHOR = '''        <service android:name=".ChimeraRoomHostGuardService" android:exported="false" android:foregroundServiceType="connectedDevice" />
'''
SERVICE = '''        <!-- Owner, Nov 21: Forge's game engine in its own process, with its own memory. -->
        <service android:name="com.arcanatable.engine.ForgeProcessService" android:process=":forge" android:exported="false" />
'''


def patch(app: Path) -> None:
    for name in ("ForgeProcessService.kt", "ForgeRemote.kt", "ForgeIpc.kt", "ForgeEngineCore.kt"):
        if not (app / "src/main/java/com/arcanatable/engine" / name).is_file():
            raise ValueError(f"{name} must be copied in before patch_forge_process.py")
    manifest = app / "src/main/AndroidManifest.xml"
    text = manifest.read_text(encoding="utf-8")
    if "ForgeProcessService" in text:
        raise ValueError("Forge process already declared")
    if text.count(ANCHOR) != 1:
        raise ValueError(f"AndroidManifest.xml: service anchor found {text.count(ANCHOR)} times, expected 1")
    manifest.write_text(text.replace(ANCHOR, ANCHOR + SERVICE), encoding="utf-8")
    print("Forge runs in its own process (:forge) with its own memory")


if __name__ == "__main__":
    if len(sys.argv) != 2:
        raise SystemExit("usage: patch_forge_process.py STAGED_ANDROID_APP")
    patch(Path(sys.argv[1]))
