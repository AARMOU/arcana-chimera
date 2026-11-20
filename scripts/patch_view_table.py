#!/usr/bin/env python3
"""Chimera-only: the declare-attackers screen's LATER button says VIEW TABLE (owner, Nov 12).

The button already closes the attack declaration back to the table, so only the label changes.
The declare-blockers screen is left as it is: it cannot close while a block plan is pending.

Fail-closed: exactly one anchor must match. Apply to an ephemeral staged Arcana donor, NEVER to arcana_table.
"""
from pathlib import Path
import sys

OLD = 'TrayButton("LATER", Muted, onDismiss)'
NEW = 'TrayButton("VIEW TABLE", Muted, onDismiss)'


def patch(app: Path) -> None:
    board = app / "src/main/java/com/arcanatable/CombatStackBoard.kt"
    source = board.read_text(encoding="utf-8")
    if NEW in source:
        raise RuntimeError("view-table patch already applied")
    if source.count(OLD) != 1:
        raise RuntimeError(f"view-table anchor found {source.count(OLD)} times, expected 1")
    board.write_text(source.replace(OLD, NEW), encoding="utf-8")
    print("Declare attackers: LATER is now VIEW TABLE")


if __name__ == "__main__":
    if len(sys.argv) != 2:
        raise SystemExit("usage: patch_view_table.py STAGED_ANDROID_APP")
    patch(Path(sys.argv[1]))
