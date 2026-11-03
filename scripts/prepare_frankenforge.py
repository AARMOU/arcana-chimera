#!/usr/bin/env python3
"""Prepare a temporary *upstream Forge clone* for the Chimera Android device spike.

Only writes below the supplied temporary Forge directory. Arcana is never read/written
by this script. No Forge code or resources are checked into Chimera.
"""
from __future__ import annotations

import re
import shutil
import subprocess
import sys
from pathlib import Path

FORGE_SHA = "7c34365c749ca46ab71336c5f267340d6088e600"
APP_ID = "org.arcanachimera.frankenarcana"


def required_replace(text: str, old: str, new: str, label: str) -> str:
    if old not in text:
        raise RuntimeError(f"Upstream Android manifest changed ({label}); fail closed")
    return text.replace(old, new, 1)


def prepare(forge: Path, chimera: Path) -> None:
    forge = forge.resolve(strict=True)
    chimera = chimera.resolve(strict=True)
    expected = (chimera / "third_party" / "forge-device").resolve()
    if forge != expected:
        raise RuntimeError("Refusing to modify an unexpected location")
    if not (forge / ".git").is_dir():
        raise RuntimeError("Target is not an upstream Forge checkout")
    head = subprocess.check_output(
        ["git", "-C", str(forge), "rev-parse", "HEAD"], text=True
    ).strip()
    if head != FORGE_SHA:
        raise RuntimeError(f"Wrong Forge commit: {head}")

    src = chimera / "prototype" / "android" / "ChimeraLauncher.java"
    if not src.read_text(encoding="utf-8").startswith("package forge.app;"):
        raise RuntimeError("Unexpected Chimera launcher source")

    android = forge / "forge-gui-android"
    manifest = android / "src" / "main" / "AndroidManifest.xml"
    original = manifest.read_text(encoding="utf-8")

    modified = required_replace(
        original, 'package="forge.app"', f'package="{APP_ID}"', "app package"
    )
    modified = required_replace(
        modified, 'android:name=".Launcher"',
        'android:name="forge.app.ChimeraLauncher"', "launcher activity"
    )
    modified = required_replace(
        modified, 'android:name=".Main"',
        'android:name="forge.app.Main"', "game activity"
    )
    if 'android:label="@string/app_name"' not in modified:
        raise RuntimeError("Upstream application label changed")
    modified = modified.replace(
        'android:label="@string/app_name"',
        'android:label="FrankenArcana Lab"'
    )
    # Prevent production Forge's fixed file-provider authority from conflicting
    # with an installed copy of upstream Forge.
    modified = required_replace(
        modified,
        'android:authorities="com.mydomain.publicfileprovider"',
        f'android:authorities="{APP_ID}.publicfileprovider"',
        "file-provider authority"
    )
    modified = required_replace(
        modified, 'android:allowBackup="true"',
        'android:allowBackup="false"', "application backup"
    )

    # No crash reports from this research-only clone. Keep the SDK classes
    # present because the upstream Android client uses them, but blank the DSN.
    # Also disable default PII reporting from the source manifest.
    modified, dsns = re.subn(
        r'(<meta-data android:name="io[.]sentry[.]dsn" android:value=")[^"]*(")',
        r'\1\2', modified
    )
    if dsns != 1:
        raise RuntimeError("Expected exactly one upstream Sentry DSN")
    modified = required_replace(
        modified,
        'android:name="io.sentry.send-default-pii" android:value="true"',
        'android:name="io.sentry.send-default-pii" android:value="false"',
        "Sentry PII setting"
    )
    if "<intent-filter>" not in modified:
        raise RuntimeError("Unexpected upstream manifest; no launcher intent filter")
    # Patch only this ignored Forge checkout so its existing mobile game view
    # stays active underneath our Arcana-style table. No copy of Arcana source.
    main_java = android / "src" / "forge" / "app" / "Main.java"
    main_original = main_java.read_text(encoding="utf-8")
    anchor = "        addContentView(contentView, params);"
    if main_original.count(anchor) != 1:
        raise RuntimeError("Unexpected Forge activity layout; refusing to inject table")
    main_modified = main_original.replace(
        anchor,
        anchor + '\n'
        + '        if (contentView == forgeView) { ArcanaTableOverlay.install(this); }',
        1
    )
    overlay = chimera / "prototype" / "android" / "ArcanaTableOverlay.java"
    if not overlay.read_text(encoding="utf-8").startswith("package forge.app;"):
        raise RuntimeError("Missing Chimera's live table source")
    # Write only after manifest, activity and overlay preflight checks pass.
    manifest.write_text(modified, encoding="utf-8")
    main_java.write_text(main_modified, encoding="utf-8")
    target = android / "src" / "forge" / "app" / "ChimeraLauncher.java"
    shutil.copyfile(src, target)
    shutil.copyfile(overlay, android / "src" / "forge" / "app" / "ArcanaTableOverlay.java")
    print(f"CHIMERA_PREPARED: app={APP_ID}; Forge={head[:12]}")
    print("SOURCE_BOUNDARY: upstream temporary clone only; Arcana untouched")
    print("TELEMETRY: upstream Sentry DSN cleared; PII disabled")
    print("LIVE_GAME_TABLE: Arcana colors and real Forge match data; no mock state")


if __name__ == "__main__":
    if len(sys.argv) != 2:
        sys.exit("Usage: prepare_frankenforge.py <ignored-third_party/forge-device>")
    prepare(Path(sys.argv[1]), Path(__file__).resolve().parent.parent)
