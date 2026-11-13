#!/usr/bin/env python3
"""Chimera-only: the host can change an AI seat's deck in the multiplayer room lobby (owner, Nov 13).

The room lobby's AI seats only offered difficulty and OPEN FOR A FRIEND; their decks were fixed. Each AI
seat now has CHANGE DECK, listing the saved decks and the built-in preconstructed decks. The pick replaces
that seat's AI deck and commander (keeping its difficulty) before START; decks still never leave the host.

Needs patch_offline_ai_decks.py first (it adds chimeraAiSeat). Fail-closed: every anchor must match exactly
once. Apply to an ephemeral staged Arcana donor, NEVER to arcana_table.
"""
from pathlib import Path
import sys

J = "src/main/java/com/arcanatable/"

ROOM_OLD = "    /** Whether [newCount] seats is allowed now: no seated guest may be dropped by shrinking. */\n"
ROOM_NEW = '''    /** Chimera: host-selected deck for an AI seat; the seat keeps its difficulty. Ignored after START. */
    fun setAiSeat(index: Int, seat: LocalSeat): Boolean {
        if (started || index !in 1 until seatCount || kinds[index - 1] != Kind.AI || index in admitted) return false
        aiOptions[index - 1] = seat.copy(difficulty = aiOptions[index - 1].difficulty)
        return true
    }

''' + ROOM_OLD

WIFI_ADD = '''

/** Chimera (owner, Nov 13): host picks a saved or built-in deck for an AI seat in the room lobby, before START. */
internal fun TableController.setMultiSeatAiDeck(index: Int, deckId: String) {
    val room = wifiMultiLobby ?: return
    if (argentumSocket.runningLocalHost != null) return
    val seat = runCatching { chimeraAiSeat(index - 1, deckId) }.getOrElse {
        lobbyNotice = it.message ?: "That deck can't be used for an AI seat."
        recordDiagnostic("Room lobby: AI seat ${index + 1} deck refused: ${it.message}")
        return
    }
    val named = seat.copy(name = "AI · ${seat.commander.orEmpty().substringBefore(',').trim()}")
    if (room.setAiSeat(index, named)) {
        recordDiagnostic("Room lobby: AI seat ${index + 1} deck changed (commander ${named.commander})")
        publishMultiLobby()
    }
}
'''

PARAM_OLD = "    onDifficulty: (AiDifficulty) -> Unit,\n"
PARAM_NEW = PARAM_OLD + "    aiDecks: List<Pair<String, String>> = emptyList(),\n    onAiDeck: (String) -> Unit = {},\n"

CALL_OLD = "                            onDifficulty = { setMultiSeatAiDifficulty(index, it) },\n"
CALL_NEW = CALL_OLD + ('                            aiDecks = savedDecks.map { "saved:${it.name}" to it.name } + preconDecks.map { "precon:${it.id}" to it.name },\n'
                       "                            onAiDeck = { setMultiSeatAiDeck(index, it) },\n")

BUTTON_OLD = '                    StudioTextButton("TableMultiLobby.TextButton.6", "Rooms and lobby", onClick = onOpenForFriend,'
BUTTON_NEW = '''                    // Chimera: choose this AI seat's deck from the saved and built-in decks.
                    val deckListOpen = androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
                    androidx.compose.material3.TextButton(onClick = { deckListOpen.value = !deckListOpen.value }, enabled = controlsEnabled && aiDecks.isNotEmpty(), modifier = Modifier.heightIn(min = 44.dp)) {
                        androidx.compose.material3.Text(if (deckListOpen.value) "HIDE DECKS" else "CHANGE DECK", color = if (controlsEnabled) Mint else Muted, fontWeight = FontWeight.Bold)
                    }
                    if (deckListOpen.value) aiDecks.forEach { (deckId, deckName) ->
                        androidx.compose.material3.TextButton(onClick = { deckListOpen.value = false; onAiDeck(deckId) }, enabled = controlsEnabled, modifier = Modifier.fillMaxWidth().heightIn(min = 44.dp)) {
                            androidx.compose.material3.Text(deckName, color = SoftWhite)
                        }
                    }
''' + BUTTON_OLD


def once(source, old, new, label):
    if source.count(old) != 1:
        raise RuntimeError(f"online-ai-decks {label} anchor found {source.count(old)} times, expected 1")
    return source.replace(old, new)


def patch(app: Path) -> None:
    room, wifi, lobby, controller = (app / (J + n) for n in ("ArgentumWaitingLobby.kt", "TableWifi.kt", "TableMultiLobby.kt", "TableController.kt"))
    if "fun chimeraAiSeat(" not in controller.read_text(encoding="utf-8"):
        raise RuntimeError("online-ai-decks needs patch_offline_ai_decks.py applied first")
    r = room.read_text(encoding="utf-8")
    if "fun setAiSeat(" in r:
        raise RuntimeError("online-ai-decks patch already applied")
    room.write_text(once(r, ROOM_OLD, ROOM_NEW, "room"), encoding="utf-8")
    wifi.write_text(wifi.read_text(encoding="utf-8").rstrip("\n") + "\n" + WIFI_ADD, encoding="utf-8")
    l = lobby.read_text(encoding="utf-8")
    l = once(l, PARAM_OLD, PARAM_NEW, "seat card parameter")
    l = once(l, CALL_OLD, CALL_NEW, "seat card call")
    lobby.write_text(once(l, BUTTON_OLD, BUTTON_NEW, "seat card button"), encoding="utf-8")
    print("Room lobby: AI seats have CHANGE DECK")


if __name__ == "__main__":
    if len(sys.argv) != 2:
        raise SystemExit("usage: patch_online_ai_decks.py STAGED_ANDROID_APP")
    patch(Path(sys.argv[1]))
