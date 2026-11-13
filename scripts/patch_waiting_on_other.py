#!/usr/bin/env python3
"""Chimera-only: when another player has the open question, don't say "answer the open prompt first"
and don't raise the "did not confirm an action" alarm (owner, Nov 12).

The table now knows the viewer's id from the game state (viewingPlayerId) and compares it with the open
decision's owner. If someone else has the question, the refused pass says the game is waiting on that
player, and the watchdog stays quiet instead of asking for a state refresh.

Fail-closed: every anchor must match exactly once. Apply to an ephemeral staged Arcana donor, NEVER to arcana_table.
"""
from pathlib import Path
import sys

BACKEND = "src/main/java/com/arcanatable/ArgentumBackend.kt"
ARGENTUM = "src/main/java/com/arcanatable/TableArgentum.kt"
EFFECTS = "src/main/java/com/arcanatable/TableEffects.kt"

SNAPSHOT_FIELD_OLD = "    val winnerName: String? = null,\n) {"
SNAPSHOT_FIELD_NEW = "    val winnerName: String? = null,\n    /** The viewing player's id from the game state; null when unknown. */\n    val viewerId: String? = null,\n) {"

PARSE_OLD = '                viewingPlayer = playerIds.indexOf(state.optString("viewingPlayerId")).coerceAtLeast(0),\n'
PARSE_NEW = PARSE_OLD + '                viewerId = state.optString("viewingPlayerId").takeIf { it.isNotBlank() && it != "null" },\n'

REFUSAL_OLD = ('    argentumPendingDecision?.takeIf { it.optString("type") != "PlayCardDecision" }?.let { decision ->\n'
               '        if (argentumDeferredDecisionId == decision.optString("id")) argentumDeferredDecisionId = null\n'
               '        message = "Answer the open prompt first."\n')
REFUSAL_NEW = ('    argentumPendingDecision?.takeIf { it.optString("type") != "PlayCardDecision" }?.let { decision ->\n'
               '        if (argentumDeferredDecisionId == decision.optString("id")) argentumDeferredDecisionId = null\n'
               '        message = if (argentumDecisionBelongsToOther(decision)) "Waiting for another player to answer." else "Answer the open prompt first."\n')

HELPER = '''

/** Chimera (owner, Nov 12): true when the open question belongs to another player, not this viewer. */
internal fun TableController.argentumDecisionBelongsToOther(decision: JSONObject): Boolean {
    val owner = decision.optString("playerId").takeUnless { it.isBlank() || it == "null" } ?: return false
    val viewer = argentumSnapshot?.viewerId ?: return false
    return owner != viewer
}

/** Chimera: the game is waiting on someone else's question, so an unconfirmed action is not a stuck game. */
internal fun TableController.argentumWaitingOnOther(): Boolean =
    argentumPendingDecision?.let { argentumDecisionBelongsToOther(it) } ?: false
'''

WATCHDOG_OLD = ('            if (argentumAwaitingUpdate) {\n'
                '                recordDiagnostic("Argentum did not confirm an action')
WATCHDOG_NEW = ('            if (argentumAwaitingUpdate && !argentumWaitingOnOther()) {\n'
                '                recordDiagnostic("Argentum did not confirm an action')


def replace_once(source: str, old: str, new: str, label: str) -> str:
    count = source.count(old)
    if count != 1:
        raise RuntimeError(f"{label} anchor found {count} times, expected 1")
    return source.replace(old, new)


def patch(app: Path) -> None:
    backend = app / BACKEND
    argentum = app / ARGENTUM
    effects = app / EFFECTS
    b = backend.read_text(encoding="utf-8")
    if "val viewerId: String? = null," in b:
        raise RuntimeError("waiting-on-other patch already applied")
    b = replace_once(b, SNAPSHOT_FIELD_OLD, SNAPSHOT_FIELD_NEW, "snapshot field")
    b = replace_once(b, PARSE_OLD, PARSE_NEW, "snapshot parse")
    backend.write_text(b, encoding="utf-8")

    a = argentum.read_text(encoding="utf-8")
    a = replace_once(a, REFUSAL_OLD, REFUSAL_NEW, "refusal message")
    argentum.write_text(a.rstrip("\n") + "\n" + HELPER, encoding="utf-8")

    e = effects.read_text(encoding="utf-8")
    e = replace_once(e, WATCHDOG_OLD, WATCHDOG_NEW, "watchdog")
    effects.write_text(e, encoding="utf-8")
    print("Waiting on another player: no false alarm, and the refusal names the wait")


if __name__ == "__main__":
    if len(sys.argv) != 2:
        raise SystemExit("usage: patch_waiting_on_other.py STAGED_ANDROID_APP")
    patch(Path(sys.argv[1]))
