#!/usr/bin/env python3
"""Chimera-only: after a multiplayer game, PLAY AGAIN takes the host back to the same room (owner, Nov 13).

Before, PLAY AGAIN closed the room and opened the setup for a new one: friends had to join again and the AI
seats went back to their defaults. Now, when the whole game is over and the room's connection is still open,
the host's PLAY AGAIN ends the finished game's engine but keeps the room: same seats, same AI decks and
difficulty, same friends. Friends who are still on their game-over screen are taken back to the room's lobby
and mark READY again; the host presses START GAME as before. If the host was only eliminated (others still
playing), or the room is gone, PLAY AGAIN behaves as it did.

The room service is not changed. Fail-closed: every anchor must match exactly once.
Apply to an ephemeral staged Arcana donor, NEVER to arcana_table.
"""
from pathlib import Path
import sys

J = "src/main/java/com/arcanatable/"

ROOM_OLD = "    /** Whether [newCount] seats is allowed now: no seated guest may be dropped by shrinking. */\n"
ROOM_NEW = '''    /** Chimera: the game is over and the same room waits again. Seated guests stay seated and ready up again. */
    fun reopen(): Boolean {
        if (!started) return false
        started = false
        readySeats.clear()
        return true
    }

''' + ROOM_OLD

WIFI_ADD = '''

/**
 * Chimera (owner, Nov 13): the host's PLAY AGAIN after a finished multiplayer game. Ends that game's engine and
 * returns everyone still connected to the same room. False when there is no such room, so the caller does what
 * PLAY AGAIN always did.
 */
internal fun TableController.rematchSameRoom(): Boolean {
    if (wifiRole != "host" && wifiRole != "online-host") return false
    if (argentumMatchUnfinished || !gameEndedOnline) return false // others may still be playing on this engine
    val server = wifiServer ?: return false
    val room = wifiMultiLobby ?: return false
    val setup = wifiMultiSetup
    val registry = wifiMultiRegistry ?: return false
    if (server !is ArgentumLanMultiServer && server !is ArgentumCloudMultiHost) return false
    if (!room.reopen()) return false
    recordDiagnostic("Play again: back to the same room", DiagnosticCategory.ACTION)
    // closeArgentumSession() ends the engine and resets the table, and would also close the room: hold the room
    // aside while it runs, then put it back.
    wifiServer = null
    closeArgentumSession(concede = false)
    wifiServer = server
    wifiMultiLobby = room
    wifiMultiSetup = setup
    wifiMultiRegistry = registry
    if (server is ArgentumCloudMultiHost) onlineRoomCode = server.code
    publishMultiLobby() // shows the lobby here and sends the roster to every friend still connected
    return true
}

/** Chimera: a friend's phone, when the host reopens the room after a finished game. The room connection stays. */
internal fun TableController.guestBackToRoom() {
    recordDiagnostic("The host reopened the room after the game", DiagnosticCategory.CONNECTION)
    argentumEngineAdvancing = false
    argentumLastCompletedEngineMs = null
    argentumLastError = null
    setArgentumGameActive(false)
    argentumSnapshot = null
    argentumPendingDecision = null
    argentumActionChoices = emptyList(); argentumActionCard = null; argentumComposer = null
    argentumAttackPlan = null; argentumBlockPlan = null; argentumBlockGuard.reset()
    playPopup = null; playFeed = emptyList(); playBanner = null; recapText = null; aiRecaps = emptyList(); argentumStackUse = null; argentumAutoYes = emptyMap()
    argentumLocalPlayersBackup?.let { players = it }
    gameStartedOnline = false
    gameEndedOnline = false
    argentumMatchUnfinished = false
    argentumGameOverDismissed = false
    gameTimerRunning = false
    turnTimerRunning = false
    showOverview = true
    showHand = false
    wifiGuestWaiting = true
}
'''

GUEST_OLD = "                } else wifiGuestLobbyState = incoming\n"
GUEST_NEW = ("                } else {\n"
             "                    if (gameEndedOnline && !argentumMatchUnfinished) guestBackToRoom() // Chimera: same room, next game\n"
             "                    wifiGuestLobbyState = incoming\n"
             "                }\n")

AGAIN_OLD = ('''                val wasOffline = argentumOffline
                val wifi = wifiRole
                closeArgentumSession(concede = false)
                when {
''')
AGAIN_NEW = ('''                val sameRoom = rematchSameRoom() // Chimera: a host with a finished game goes back to the same room
                val wasOffline = argentumOffline
                val wifi = wifiRole
                if (!sameRoom) closeArgentumSession(concede = false)
                when {
                    sameRoom -> Unit
''')


def once(path, old, new, label):
    source = path.read_text(encoding="utf-8")
    if source.count(old) != 1:
        raise RuntimeError(f"same-room {label} anchor found {source.count(old)} times, expected 1")
    path.write_text(source.replace(old, new), encoding="utf-8")


def patch(app: Path) -> None:
    wifi = app / (J + "TableWifi.kt")
    if "fun TableController.rematchSameRoom" in wifi.read_text(encoding="utf-8"):
        raise RuntimeError("same-room patch already applied")
    once(app / (J + "ArgentumWaitingLobby.kt"), ROOM_OLD, ROOM_NEW, "room")
    wifi.write_text(wifi.read_text(encoding="utf-8").rstrip("\n") + "\n" + WIFI_ADD, encoding="utf-8")
    once(app / (J + "TableArgentum.kt"), GUEST_OLD, GUEST_NEW, "guest lobby")
    once(app / (J + "TableOverlays.kt"), AGAIN_OLD, AGAIN_NEW, "play again")
    print("Play again: a host with a finished game returns to the same room")


if __name__ == "__main__":
    if len(sys.argv) != 2:
        raise SystemExit("usage: patch_rematch_same_room.py STAGED_ANDROID_APP")
    patch(Path(sys.argv[1]))
