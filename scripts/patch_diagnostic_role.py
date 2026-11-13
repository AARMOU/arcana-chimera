#!/usr/bin/env python3
"""Chimera-only: a guest's diagnostic report must not say "Role: Forge (on this device)" (owner, Nov 12).

The donor checked argentumEnabled first, so a guest who joined a Forge game reported the same role as a
device running its own game. Now the host and guest roles are checked first, and only a device that runs
its own game reports the on-device Forge role. The wording patch still renames that remaining case.

Fail-closed: the donor line must match exactly once. Apply to an ephemeral staged Arcana donor, NEVER to arcana_table.
"""
from pathlib import Path
import sys

OLD = ('appendLine("Role: ${if (argentumEnabled) "Argentum client" else if (lobbyCreated) "host" '
       'else if (roomClient != null || cloudRoomClient != null) "client" else "offline"}")')
NEW = ('appendLine("Role: ${if (lobbyCreated) "host" else if (roomClient != null || cloudRoomClient != null) "guest" '
       'else if (argentumEnabled) "Argentum client" else "offline"}")')


def patch(app: Path) -> None:
    diag = app / "src/main/java/com/arcanatable/TableDiagnostics.kt"
    source = diag.read_text(encoding="utf-8")
    if NEW in source:
        raise RuntimeError("diagnostic-role patch already applied")
    if source.count(OLD) != 1:
        raise RuntimeError(f"diagnostic-role anchor found {source.count(OLD)} times, expected 1")
    diag.write_text(source.replace(OLD, NEW), encoding="utf-8")
    print("Diagnostics: a guest reports Role: guest")


if __name__ == "__main__":
    if len(sys.argv) != 2:
        raise SystemExit("usage: patch_diagnostic_role.py STAGED_ANDROID_APP")
    patch(Path(sys.argv[1]))
