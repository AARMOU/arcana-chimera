#!/usr/bin/env python3
"""Make the in-game 'Engine running notice' OFF by default in the staged donor.

Retain the existing Help & Diagnostics toggle and all explicit user preferences.
"""
from pathlib import Path
import sys

OLD = 'optionPrefs.getBoolean("show_engine_status", BuildConfig.PREVIEW)'
NEW = 'optionPrefs.getBoolean("show_engine_status", false)'


def patch(app):
    controller = Path(app) / "src/main/java/com/arcanatable/TableController.kt"
    contents = controller.read_text(encoding="utf-8")
    if contents.count(OLD) != 1:
        raise ValueError(f"Engine notice default anchor missing or ambiguous: {contents.count(OLD)}")
    controller.write_text(contents.replace(OLD, NEW), encoding="utf-8")
    print("Engine running notice: default OFF; user preference and toggle retained")


if __name__ == "__main__":
    if len(sys.argv) != 2:
        raise SystemExit("Usage: patch_engine_running_default.py STAGED_ANDROID_APP_DIR")
    patch(sys.argv[1])
