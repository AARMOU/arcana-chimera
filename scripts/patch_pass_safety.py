#!/usr/bin/env python3
"""Chimera-only: two "Pass to my turn" fixes from the owner's Nov 13-14 games.

1. It stops once when a lethal unblocked attack is coming. With nothing to block with there is no block
   question, so it used to pass straight through combat damage (Borg Queen game, turn 19: 4 life, 57 damage).
   The Forge bridge puts the warning in the table (chimeraAttackWarning); this reads it and stops, once a turn.
2. Starting it again on the same spell passes that spell. It stopped for "X targets you or your cards", and
   every restart stopped on the same spell again (Repel Calamity, four times), so you had to pass by hand.

Needs patch_waiting_on_other.py first (it adds the snapshot field this one sits beside). Fail-closed: every
anchor must match exactly once. Apply to an ephemeral staged Arcana donor, NEVER to arcana_table.
"""
from pathlib import Path
import sys

J = "src/main/java/com/arcanatable/"

FIELD_OLD = "    val viewerId: String? = null,\n) {"
FIELD_NEW = ("    val viewerId: String? = null,\n"
             "    /** Chimera: a lethal unblocked attack on the viewer is in progress; null otherwise. */\n"
             "    val attackWarning: String? = null,\n) {")
PARSE_OLD = '                viewerId = state.optString("viewingPlayerId").takeIf { it.isNotBlank() && it != "null" },\n'
PARSE_NEW = PARSE_OLD + '                attackWarning = state.optString("chimeraAttackWarning").takeIf { it.isNotBlank() && it != "null" },\n'

# Other Chimera patches add lines after the settle delay, so this goes in just before the decision itself.
SETTLE_OLD = "        val outcome = ArgentumPassing.next(\n"
SETTLE_NEW = '''        // Chimera: a lethal unblocked attack with no block question. Stop once this turn and say so.
        snapshot.attackWarning?.let { warning ->
            if (ChimeraPlayState.attackStopTurn != snapshot.turnNumber && argentumPendingDecision == null && !argentumAwaitingUpdate && !snapshot.gameOver) {
                ChimeraPlayState.attackStopTurn = snapshot.turnNumber
                passToMyTurnFrom = null
                recordDiagnostic("Pass to my turn: stopped ($warning)", DiagnosticCategory.ACTION)
                android.widget.Toast.makeText(context, warning, android.widget.Toast.LENGTH_LONG).show()
                return@LaunchedEffect
            }
        }
        val stackStopKey = "${snapshot.turnNumber}:${snapshot.interactionEpoch}"
        val outcome = ArgentumPassing.next(
'''
STACK_OLD = ('''            opponentStackItem = ArgentumPassing.interruptingStackItem(
                snapshot.stack, snapshot.targets, snapshot.players.getOrNull(localSeat)?.id,
            ),
''')
STACK_NEW = ('''            // Chimera: started again on the spell it just stopped for, so that spell is passed this time
            opponentStackItem = if (ChimeraPlayState.stackStopSeen == stackStopKey) null else ArgentumPassing.interruptingStackItem(
                snapshot.stack, snapshot.targets, snapshot.players.getOrNull(localSeat)?.id,
            ),
''')
STOP_OLD = ('''            ArgentumPassing.Step.INTERRUPTED -> {
                passToMyTurnFrom = null
                recordDiagnostic("Pass to my turn: stopped (${outcome.reason})", DiagnosticCategory.ACTION)
''')
STOP_NEW = ('''            ArgentumPassing.Step.INTERRUPTED -> {
                passToMyTurnFrom = null
                ChimeraPlayState.stackStopSeen = stackStopKey
                recordDiagnostic("Pass to my turn: stopped (${outcome.reason})", DiagnosticCategory.ACTION)
''')


def once(source, old, new, label):
    if source.count(old) != 1:
        raise RuntimeError(f"pass-safety {label} anchor found {source.count(old)} times, expected 1")
    return source.replace(old, new)


def patch(app: Path) -> None:
    backend, effects = app / (J + "ArgentumBackend.kt"), app / (J + "TableEffects.kt")
    b = backend.read_text(encoding="utf-8")
    if "val attackWarning: String? = null," in b:
        raise RuntimeError("pass-safety patch already applied")
    b = once(b, FIELD_OLD, FIELD_NEW, "snapshot field")
    backend.write_text(once(b, PARSE_OLD, PARSE_NEW, "snapshot parse"), encoding="utf-8")
    e = effects.read_text(encoding="utf-8")
    e = once(e, SETTLE_OLD, SETTLE_NEW, "settle")
    e = once(e, STACK_OLD, STACK_NEW, "stack item")
    effects.write_text(once(e, STOP_OLD, STOP_NEW, "stop"), encoding="utf-8")
    print("Pass to my turn: stops for a lethal unblocked attack; restarting passes the same spell")


if __name__ == "__main__":
    if len(sys.argv) != 2:
        raise SystemExit("usage: patch_pass_safety.py STAGED_ANDROID_APP")
    patch(Path(sys.argv[1]))
