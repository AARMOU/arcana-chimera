#!/usr/bin/env python3
"""Chimera-only: center the "<NAME> CAN BLOCK WITH" strip on the declare-blockers screen (owner, Nov 11-12).

The label and the card row both aligned to the start, so the strip looked off-centre on wide screens.
Both now center. A row that is wider than the screen still scrolls from its start, because Compose
falls back to start alignment when content overflows.

Fail-closed: each anchor must match exactly once. Apply to an ephemeral staged Arcana donor, NEVER to arcana_table.
"""
from pathlib import Path
import sys

LABEL_OLD = ('"${name.uppercase()} CAN BLOCK WITH", color = Muted, fontSize = 9.sp, fontWeight = FontWeight.Black, '
             'letterSpacing = 0.8.sp, modifier = Modifier.padding(top = 4.dp))')
LABEL_NEW = ('"${name.uppercase()} CAN BLOCK WITH", color = Muted, fontSize = 9.sp, fontWeight = FontWeight.Black, '
             'letterSpacing = 0.8.sp, textAlign = androidx.compose.ui.text.style.TextAlign.Center, '
             'modifier = Modifier.fillMaxWidth().padding(top = 4.dp))')
ROW_OLD = ('Row(Modifier.fillMaxWidth().hintedHorizontalScroll(rememberScrollState()).padding(vertical = 4.dp), '
           'horizontalArrangement = Arrangement.spacedBy(6.dp)) {')
ROW_NEW = ('Row(Modifier.fillMaxWidth().hintedHorizontalScroll(rememberScrollState()).padding(vertical = 4.dp), '
           'horizontalArrangement = Arrangement.spacedBy(6.dp, androidx.compose.ui.Alignment.CenterHorizontally)) {')


def patch(app: Path) -> None:
    board = app / "src/main/java/com/arcanatable/ArgentumCombatBoard.kt"
    source = board.read_text(encoding="utf-8")
    if LABEL_NEW in source or ROW_NEW in source:
        raise RuntimeError("blocker-strip centering already applied")
    for old in (LABEL_OLD, ROW_OLD):
        if source.count(old) != 1:
            raise RuntimeError(f"blocker-strip anchor found {source.count(old)} times, expected 1")
        source = source.replace(old, LABEL_NEW if old == LABEL_OLD else ROW_NEW)
    board.write_text(source, encoding="utf-8")
    print("Opponent blockers strip is centered")


if __name__ == "__main__":
    if len(sys.argv) != 2:
        raise SystemExit("usage: patch_blocker_strip_center.py STAGED_ANDROID_APP")
    patch(Path(sys.argv[1]))
