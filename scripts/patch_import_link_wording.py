#!/usr/bin/env python3
"""Chimera-only: the import deck screen says it takes deck links (owner, Nov 13 12:18).

Chimera already imports Moxfield, ManaBox and Archidekt links pasted into the box; the screen only spoke of
pasting a list or choosing a file.

Fail-closed: each anchor must match exactly once. Apply to an ephemeral staged Arcana donor, NEVER to arcana_table.
"""
from pathlib import Path
import sys

SWAPS = [
    ('"Paste a deck list or choose a .txt/.csv file. Import replaces this player\'s deck."',
     '"Paste a deck list or a deck link (Moxfield, ManaBox or Archidekt), or choose a .txt/.csv file. Import replaces this player\'s deck."'),
    ('"1 Sol Ring\\n36 Island\\n..."',
     '"1 Sol Ring\\n36 Island\\n...\\n\\nor a link: moxfield.com/decks/..."'),
]


def patch(app: Path) -> None:
    f = app / "src/main/java/com/arcanatable/TableOverlaysB.kt"
    source = f.read_text(encoding="utf-8")
    if "or a deck link (Moxfield" in source:
        raise RuntimeError("import-link wording already applied")
    for old, new in SWAPS:
        if source.count(old) != 1:
            raise RuntimeError(f"import-link wording anchor found {source.count(old)} times, expected 1: {old[:40]}")
        source = source.replace(old, new)
    f.write_text(source, encoding="utf-8")
    print("Import deck screen: says it takes deck links")


if __name__ == "__main__":
    if len(sys.argv) != 2:
        raise SystemExit("usage: patch_import_link_wording.py STAGED_ANDROID_APP")
    patch(Path(sys.argv[1]))
