#!/usr/bin/env python3
"""Chimera-only: the status strip under the phase bar can be found and hidden in Studio (owner, Nov 21).

The strip shows the engine-busy message ("Waiting for Forge to confirm…"), what's on the stack, and problems
(disconnects, game over, a put-off decision). It was not a Studio item, so tapping it in Studio selected the table
behind it, and the only switch (Layout > Show on screen > Engine "thinking" bar) hid just the busy message.

* The strip is a Studio item of its own ("Status & stack strip"): tap it for Hide, opacity, colour, size, position.
* Show on screen gets "Status strip" (the whole strip) and "Status strip: what's on the stack"; the old switch is
  renamed "Status strip: engine busy message". The stack still shows in the Stack window and the table's card pile.
* Problems always show, plainly, whatever is hidden (owner's choice: never stuck without knowing the engine dropped).
* No empty strip: it used to appear as a blank box when it had nothing to say (the Studio preview). While Studio is
  open it shows a sample busy message instead, so there is something to tap.

Fail-closed: every anchor must match exactly once; nothing is written unless all match.
Apply to an ephemeral staged Arcana donor, NEVER to arcana_table.
"""
from pathlib import Path
import sys

MARK = "Status & stack strip"

PIECES_OLD = '''    "engineThinking" to "Engine \\"thinking\\" bar",'''
PIECES_NEW = '''    "statusStrip" to "Status strip (under the phase bar)",
    "engineThinking" to "Status strip: engine busy message",
    "stackList" to "Status strip: what's on the stack",'''

NEEDED_OLD = '''        val stripNeeded = argentumStatus != ArgentumConnectionStatus.CONNECTED || argentumNeedsHandling != null || thinkingVisible || gameEndedOnline ||
            argentumSnapshot == null || argentumSnapshot?.stack?.isNotEmpty() == true'''
NEEDED_NEW = '''        // Owner, Nov 21: the strip can be switched off (Studio > Layout > Show on screen > Status strip), with separate
        // switches for the busy message and the stack list. Problems always show. Nothing to say: no strip (it used to
        // show as an empty box); while Studio is open it shows a sample so it can be tapped and edited.
        val stripHiddenPieces = if (BuildConfig.STUDIO_ENABLED) studioControlsRuntime?.project()?.hiddenPieces ?: emptySet() else emptySet()
        val stripOn = "statusStrip" !in stripHiddenPieces
        val stripStack = if (stripOn && "stackList" !in stripHiddenPieces) argentumSnapshot?.stack.orEmpty() else emptyList()
        val stripProblem = argentumStatus != ArgentumConnectionStatus.CONNECTED || argentumNeedsHandling != null || gameEndedOnline
        val stripStudioSample = BuildConfig.STUDIO_ENABLED && studioControlsRuntime?.editing == true && stripOn && !stripProblem
        val stripNeeded = stripProblem || stripOn && (thinkingVisible || stripStack.isNotEmpty()) || stripStudioSample'''
AWAIT_OLD = '''                awaitingUpdate = thinkingVisible,'''
AWAIT_NEW = '''                awaitingUpdate = stripOn && thinkingVisible || stripStudioSample && stripStack.isEmpty(),'''
STACK_OLD = '''                stack = argentumSnapshot?.stack.orEmpty(),'''
STACK_NEW = '''                stack = stripStack,'''

BOX_OLD = '''        Modifier.fillMaxWidth().padding(bottom = 4.dp).clip(RoundedCornerShape(10.dp)).background(Panel)
            .padding(horizontal = 10.dp, vertical = 6.dp).testTag("argentum-status")'''
BOX_NEW = '''        Modifier.fillMaxWidth().padding(bottom = 4.dp).then(
            // Owner, Nov 21: a Studio item of its own (tapping it used to select the table behind). A problem draws
            // plainly, so hiding or fading the item in Studio never hides a disconnect or game over.
            if (model.connection != ArgentumConnectionStatus.CONNECTED || model.gameOver || model.needsHandling != null)
                Modifier.clip(RoundedCornerShape(10.dp)).background(Panel)
            else Modifier.studioPanel("ArgentumUi.Panel.1", "Table and player", "Status & stack strip", RoundedCornerShape(10.dp), Panel))
            .padding(horizontal = 10.dp, vertical = 6.dp).testTag("argentum-status")'''

SITES_OLD = '''        "ArgentumUi.clickable.2" to "Table and player",'''
SITES_NEW = '''        "ArgentumUi.clickable.2" to "Table and player",
        "ArgentumUi.Panel.1" to "Table and player",'''

EDITS = {
    "StudioControls.kt": [(PIECES_OLD, PIECES_NEW)],
    "TableMainLayout.kt": [(NEEDED_OLD, NEEDED_NEW), (AWAIT_OLD, AWAIT_NEW), (STACK_OLD, STACK_NEW)],
    "ArgentumUi.kt": [(BOX_OLD, BOX_NEW)],
    "StudioSites.kt": [(SITES_OLD, SITES_NEW)],
}


def patch(app: Path) -> None:
    src = app / "src/main/java/com/arcanatable"
    if MARK in (src / "ArgentumUi.kt").read_text(encoding="utf-8"):
        raise ValueError("status-strip patch already applied")
    staged = {}
    for name, edits in EDITS.items():
        text = (src / name).read_text(encoding="utf-8")
        for old, new in edits:
            count = text.count(old)
            if count != 1:
                raise ValueError(f"{name}: status-strip anchor found {count} times, expected 1: {old[:70]!r}")
            text = text.replace(old, new)
        staged[name] = text
    for name, text in staged.items():
        (src / name).write_text(text, encoding="utf-8")
    print("Status strip: its own Studio item, switchable on screen; problems always show")


if __name__ == "__main__":
    if len(sys.argv) != 2:
        raise SystemExit("usage: patch_status_strip.py STAGED_ANDROID_APP")
    patch(Path(sys.argv[1]))
