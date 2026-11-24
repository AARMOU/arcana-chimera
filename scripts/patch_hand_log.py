#!/usr/bin/env python3
"""Chimera-only: Game log replaces Cast in the hand drawer (owner, Nov 23: "Remove the cast button by default but leave
it in studio. Replace the cast button with the game log").

The plain drawer, Studio's starting designs, the reference design and the owner's design all show LOG where CAST was.
Studio keeps "Cast selected card" as an action any button can use, and designs you already made keep their CAST
button. Tapping a card still only selects it.

Fail-closed: every anchor must match exactly once; nothing is written unless all match.
Apply to an ephemeral staged Arcana donor, NEVER to arcana_table.
"""
from pathlib import Path
import sys

MARK = "hand-log"

EDITS = {
    "src/main/java/com/arcanatable/HandDrawerControls.kt": [
        ('''    commanderLabel: String? = null, commanderGlow: Boolean = false, onCommander: () -> Unit = {},
) {''', '''    commanderLabel: String? = null, commanderGlow: Boolean = false, onCommander: () -> Unit = {},
    // Owner, Nov 23: Game log in Cast's place; [onCast] stays for callers and tests.
    onLog: () -> Unit = {}, undoLabel: String = "UNDO",
) {'''),
        ('''            DrawerControl("UNDO", "hand-undo", undoAvailable, onUndo, 63f),''',
         '''            DrawerControl(undoLabel, "hand-undo", undoAvailable, onUndo, 63f),'''),
        ('''            DrawerControl("CAST", "hand-cast", castAvailable, onCast, 71f))''',
         '''            DrawerControl("LOG", "hand-log", true, onLog, 71f))'''),
    ],
    "src/main/java/com/arcanatable/TableBoardViews.kt": [
        ('''                    "undo" to "UNDO", "actions" to "ACTIONS · ${if (actionsBusy) 0 else legalActionCount}", "cast" to "CAST",''',
         '''                    "undo" to ChimeraUndoState.label, "actions" to "ACTIONS · ${if (actionsBusy) 0 else legalActionCount}", "cast" to "CAST", "log" to "LOG",'''),
        ('''                    addAll(listOf("metric0","metric1","metric2","metric3","commander","close","menu"))''',
         '''                    addAll(listOf("metric0","metric1","metric2","metric3","commander","close","menu","log"))'''),
        ('''                    "cast" -> if (!actionsBusy) onCast(selectedCard)
''', '''                    "cast" -> if (!actionsBusy) onCast(selectedCard)
                    "log" -> onMoreAction("LOG")
'''),
        ('''                onPassHold = if (autoPassing || autoPassAvailable && !actionsBusy) onAutoPass else null)''',
         '''                onPassHold = if (autoPassing || autoPassAvailable && !actionsBusy) onAutoPass else null,
                onLog = { onMoreAction("LOG") }, undoLabel = ChimeraUndoState.label)'''),
    ],
    "src/main/java/com/arcanatable/StudioLayout.kt": [
        ('''        val bindings = listOf("pass", "undo", "actions", "cast", "metric0", "metric1", "metric2", "metric3")''',
         '''        val bindings = listOf("pass", "undo", "actions", "cast", "log", "metric0", "metric1", "metric2", "metric3")'''),
        ('''            val ids=bindings
            val labels=listOf("PASS","UNDO","ACTIONS","CAST","STACK","LIBRARY","GRAVEYARD","EXILE")''',
         '''            val ids=listOf("pass","undo","actions","log","metric0","metric1","metric2","metric3") // owner, Nov 23: LOG, not CAST
            val labels=listOf("PASS","UNDO","ACTIONS","LOG","STACK","LIBRARY","GRAVEYARD","EXILE")'''),
        ('''            StudioElement("cast","button","CAST",320f,344f,71f,44f),''',
         '''            StudioElement("log","button","LOG",320f,344f,71f,44f),'''),
    ],
    "src/main/java/com/arcanatable/StudioHandEditor.kt": [
        ('''"cast" to "Cast selected card",''', '''"cast" to "Cast selected card","log" to "Game log",'''),
    ],
    "src/main/assets/studio/owner-hand.json": [
        ('''        "id": "cast",
        "kind": "button",
        "label": "CAST",''', '''        "id": "log",
        "kind": "button",
        "label": "LOG",'''),
    ],
}


def patch(app: Path) -> None:
    if MARK in (app / "src/main/java/com/arcanatable/HandDrawerControls.kt").read_text(encoding="utf-8"):
        raise ValueError("hand-log patch already applied")
    staged = {}
    for name, edits in EDITS.items():
        text = (app / name).read_text(encoding="utf-8")
        for old, new in edits:
            count = text.count(old)
            if count != 1:
                raise ValueError(f"{name}: hand-log anchor found {count} times, expected 1: {old[:70]!r}")
            text = text.replace(old, new)
        staged[name] = text
    for name, text in staged.items():
        (app / name).write_text(text, encoding="utf-8")
    print("Game log in Cast's place in the hand drawer")


if __name__ == "__main__":
    if len(sys.argv) != 2:
        raise SystemExit("usage: patch_hand_log.py STAGED_ANDROID_APP")
    patch(Path(sys.argv[1]))
