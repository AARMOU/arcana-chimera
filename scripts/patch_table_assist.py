#!/usr/bin/env python3
"""Chimera-only: the owner's Nov 23 table batch (overlay: ChimeraTableAssist.kt).

* Auto-pass in the hand's ⋮ menu: "Pass to end of turn" next to "Pass to my turn", "Skip when I can't respond", and
  "Always stop when" I'm attacked / something targets me or my permanents / a spell is cast.
* Pop-ups when something happens to your permanents (Options), drawn over the table; tap one to see the card.
* The opening hand in Catalogue style (Studio › Looks › Opening hand: Catalogue or Grid), with "N of M chosen".
* The table view expands the active player (and the player being attacked) and shrinks the others to fit.
* Undo for Forge games: UNDO (n) with the steps Forge can take back, and the reason when it can't.

Fail-closed: every anchor must match exactly once; nothing is written unless all match.
Apply to an ephemeral staged Arcana donor, NEVER to arcana_table.
"""
from pathlib import Path
import sys

MARK = "ChimeraAssist"

EDITS = {
    "MainActivity.kt": [
        ('''        ChimeraCrashLog.install(this, "App") // owner, Nov 21: keep every crash, not only the last
''', '''        ChimeraCrashLog.install(this, "App") // owner, Nov 21: keep every crash, not only the last
        ChimeraAssist.init(this) // owner, Nov 23: auto-pass, pop-up, table and opening-hand settings
'''),
        ('''            TableOverlays()
            TableOverlaysB()
''', '''            TableOverlays()
            TableOverlaysB()
            ChimeraPermanentPopups() // owner, Nov 23: what happened to your permanents
'''),
    ],
    "TableArgentum.kt": [
        ('''    if (!argentumUndoAvailable) { message = "There's nothing to undo right now."; return }
''', '''    if (!argentumUndoAvailable) {
        message = ChimeraUndoState.why.takeIf { it.isNotBlank() }?.let { "Can't undo: $it." } ?: "There's nothing to undo right now."
        return
    }
'''),
        ('''    passToMyTurnFrom = snapshot.turnNumber
''', '''    passToMyTurnFrom = snapshot.turnNumber
    ChimeraAssist.toEndOfTurn = ChimeraAssist.pendingEndOfTurn; ChimeraAssist.pendingEndOfTurn = false // owner, Nov 23
'''),
        ('''                argentumUndoAvailable = incoming.optBoolean("undoAvailable", false)
''', '''                argentumUndoAvailable = incoming.optBoolean("undoAvailable", false)
                ChimeraUndoState.update(argentumUndoAvailable, incoming.optInt("chimeraUndoSteps"), incoming.optString("chimeraUndoWhy"))
                ChimeraFollow.defenderIds = incoming.optJSONObject("state")?.optJSONArray("chimeraDefenders")
                    ?.let { a -> (0 until a.length()).map { a.optString(it) }.toSet() }.orEmpty()
'''),
        ('''                tutorialSnapshot(previousTutorialSnapshot, snapshot)
''', '''                tutorialSnapshot(previousTutorialSnapshot, snapshot)
                if (firstSnapshot) ChimeraPopups.clear() else ChimeraPopups.observe(previousTutorialSnapshot, snapshot, localSeat)
'''),
    ],
    "TableOverlays.kt": [
        ('''                        onUndo = if (argentumEnabled && argentumUndoAvailable && !argentumAwaitingUpdate) ({ requestArgentumUndo() }) else null,''',
         '''                        // Owner, Nov 23: tapping UNDO when it can't be used says why ("Can't undo: card drawn.").
                        onUndo = if (argentumEnabled && !argentumAwaitingUpdate) ({ requestArgentumUndo() }) else null,'''),
        ('''            primaryLabel = "CONFIRM ${argentumBottomSelection.size}/$argentumBottomCardCount",''',
         '''            needed = argentumBottomCardCount,
            primaryLabel = "CONFIRM ${argentumBottomSelection.size}/$argentumBottomCardCount",'''),
    ],
    "ArgentumScreens.kt": [
        ('''    secondaryLabel: String?, secondaryEnabled: Boolean, onSecondary: () -> Unit,
) {
    // The hand gets its own zoom''', '''    secondaryLabel: String?, secondaryEnabled: Boolean, onSecondary: () -> Unit,
    needed: Int = 0,
) {
    // The hand gets its own zoom'''),
        ('''                BoxWithConstraints(Modifier.weight(1f).fillMaxWidth().then(if (selectable) Modifier.tutorialTarget("mulligan-bottom-choice") else Modifier)) {
''', '''                // Owner, Nov 23: Catalogue by default (Studio › Looks › Opening hand switches back to the grid).
                if (ChimeraAssist.catalogueOpeningHand.value) ChimeraOpeningCatalogue(
                    Modifier.weight(1f).fillMaxWidth().then(if (selectable) Modifier.tutorialTarget("mulligan-bottom-choice") else Modifier),
                    cards, selectable, selection, onToggle, needed)
                else BoxWithConstraints(Modifier.weight(1f).fillMaxWidth().then(if (selectable) Modifier.tutorialTarget("mulligan-bottom-choice") else Modifier)) {
'''),
    ],
    "TableMainLayout.kt": [
        ('''            LaunchedEffect(activePlayer, players.size) {
                if (activePlayer in players.indices) {
                    if (reducedSwipeMotion) overviewGridState.scrollToItem(activePlayer)
                    else overviewGridState.animateScrollToItem(activePlayer)
                }
            }
''', '''            // Owner, Nov 23: the active player's board full size, the others shrunk to fit; during an attack the
            // defender's too. Scrolling or zooming pauses following until the next turn.
            val chimeraTurn = argentumSnapshot?.turnNumber ?: turnNumber
            val chimeraExpanded = ChimeraFollow.expanded(argentumSnapshot, players, activePlayer, chimeraTurn)
            val chimeraTarget = ChimeraFollow.scrollTarget(argentumSnapshot, players, activePlayer)
            LaunchedEffect(activePlayer, players.size, chimeraTarget, chimeraTurn) {
                val target = if (ChimeraFollow.following(chimeraTurn)) chimeraTarget else activePlayer
                if (target in players.indices) {
                    ChimeraFollow.programmatic = true
                    try {
                        if (reducedSwipeMotion) overviewGridState.scrollToItem(target)
                        else overviewGridState.animateScrollToItem(target)
                    } finally { ChimeraFollow.programmatic = false }
                }
            }
            LaunchedEffect(overviewGridState.isScrollInProgress) {
                if (overviewGridState.isScrollInProgress && !ChimeraFollow.programmatic) ChimeraFollow.pause(chimeraTurn)
            }
'''),
        ('''                        compact = compactOverview,
''', '''                        compact = compactOverview || (chimeraExpanded.isNotEmpty() && index !in chimeraExpanded),
'''),
        ('''                         onTableZoomEnd = { saveTableZoom(tableZoomContext, tableZoomConfig.screenWidthDp, tableZoomConfig.screenHeightDp, it) },''',
         '''                         onTableZoomEnd = { saveTableZoom(tableZoomContext, tableZoomConfig.screenWidthDp, tableZoomConfig.screenHeightDp, it); ChimeraFollow.pause(chimeraTurn) },'''),
    ],
    "TableEffects.kt": [
        ('''        if (argentumSnapshot !== snapshot || argentumAwaitingUpdate) return@LaunchedEffect // decide from the newest state only
''', '''        if (argentumSnapshot !== snapshot || argentumAwaitingUpdate) return@LaunchedEffect // decide from the newest state only
        if (ChimeraAssist.toEndOfTurn && snapshot.turnNumber > start) { // owner, Nov 23: "Pass to end of turn"
            passToMyTurnFrom = null
            recordDiagnostic("Pass to end of turn: turn $start is over", DiagnosticCategory.ACTION)
            android.widget.Toast.makeText(context, "Turn over.", android.widget.Toast.LENGTH_SHORT).show()
            return@LaunchedEffect
        }
'''),
        ('''        val stackStopKey = "${snapshot.turnNumber}:${snapshot.interactionEpoch}"
''', '''        val stackStopKey = "${snapshot.turnNumber}:${snapshot.interactionEpoch}"
        // Owner, Nov 23: "Always stop when a spell is cast" (another player's spell or ability on the stack).
        if (ChimeraAssist.stopSpellCast.value && ChimeraPlayState.stackStopSeen != stackStopKey && argentumPendingDecision == null) {
            snapshot.stack.firstOrNull { !it.isYours }?.let { item ->
                passToMyTurnFrom = null
                ChimeraPlayState.stackStopSeen = stackStopKey
                recordDiagnostic("Pass to my turn: stopped (${item.controllerName} cast ${item.name})", DiagnosticCategory.ACTION)
                android.widget.Toast.makeText(context, "${item.controllerName}: ${item.name}. You can respond.", android.widget.Toast.LENGTH_LONG).show()
                return@LaunchedEffect
            }
        }
        // Owner, Nov 23: "I'm attacked" unticked: declare no blocks once per turn (a lethal attack still stopped above).
        if (!ChimeraAssist.stopAttacked.value && argentumPendingDecision == null && ChimeraAssist.autoBlockTurn != snapshot.turnNumber) {
            snapshot.legalActions.firstOrNull { it.optJSONObject("action")?.optString("type") == "DeclareBlockers" }?.let { legal ->
                ChimeraAssist.autoBlockTurn = snapshot.turnNumber
                val epoch = ArgentumActions.epochFor(legal, snapshot.interactionEpoch)
                if (epoch != null && submitArgentumAction(legal.getJSONObject("action"), epoch)) {
                    recordDiagnostic("Pass to my turn: declared no blocks", DiagnosticCategory.ACTION)
                    return@LaunchedEffect
                }
            }
        }
'''),
        ('''            opponentStackItem = if (ChimeraPlayState.stackStopSeen == stackStopKey) null else ArgentumPassing.interruptingStackItem(''',
         '''            opponentStackItem = if (!ChimeraAssist.stopTargeted.value || ChimeraPlayState.stackStopSeen == stackStopKey) null else ArgentumPassing.interruptingStackItem('''),
        ('''                if (argentumOffline && ChimeraPhaseStops.stops.none { it.startsWith("opp:") })''',
         '''                if (argentumOffline && !ChimeraAssist.toEndOfTurn && !ChimeraAssist.stopSpellCast.value && ChimeraPhaseStops.stops.none { it.startsWith("opp:") })'''),
        ('''    // Following turns navigates only between player boards. Table view is sticky.
''', '''    // Owner, Nov 23: "Skip when I can't respond": pass by itself when passing is all you can do.
    LaunchedEffect(argentumSnapshot, argentumPendingDecision, argentumAwaitingUpdate, ChimeraAssist.skipWhenCantRespond.value) {
        if (!ChimeraAssist.skipWhenCantRespond.value || passToMyTurnFrom != null || !argentumEnabled) return@LaunchedEffect
        val snapshot = argentumSnapshot ?: return@LaunchedEffect
        if (argentumPendingDecision != null || argentumAwaitingUpdate || snapshot.gameOver || gameEndedOnline) return@LaunchedEffect
        if (snapshot.priorityPlayer != localSeat || !ChimeraAssist.nothingToDo(snapshot.legalActions)) return@LaunchedEffect
        delay(700) // long enough to see what just happened
        if (argentumSnapshot !== snapshot || argentumAwaitingUpdate || argentumPendingDecision != null || passToMyTurnFrom != null) return@LaunchedEffect
        recordDiagnostic("Skipped a priority window: nothing you can do (${snapshot.stepName})", DiagnosticCategory.ACTION)
        sendArgentumPriority()
    }
    // Following turns navigates only between player boards. Table view is sticky.
'''),
    ],
}


def patch(app: Path) -> None:
    src = app / "src/main/java/com/arcanatable"
    if MARK in (src / "TableEffects.kt").read_text(encoding="utf-8"):
        raise ValueError("table-assist patch already applied")
    staged = {}
    for name, edits in EDITS.items():
        text = (src / name).read_text(encoding="utf-8")
        for old, new in edits:
            count = text.count(old)
            if count != 1:
                raise ValueError(f"{name}: table-assist anchor found {count} times, expected 1: {old[:70]!r}")
            text = text.replace(old, new)
        staged[name] = text
    for name, text in staged.items():
        (src / name).write_text(text, encoding="utf-8")
    print("Auto-pass choices, permanent pop-ups, Catalogue opening hand, follow the active player, Forge undo")


if __name__ == "__main__":
    if len(sys.argv) != 2:
        raise SystemExit("usage: patch_table_assist.py STAGED_ANDROID_APP")
    patch(Path(sys.argv[1]))
