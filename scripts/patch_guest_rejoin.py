#!/usr/bin/env python3
"""Chimera-only: a guest can get back into an internet game after Android closed the app (owner, Nov 21).

Owner: "my phone went asleep and I got disconnected from the room and couldn't rejoin". The diagnostic report showed
the app starting from scratch mid-game (no disconnect logged), then a join by code that never got a seat.

* Guests are kept running like the host: the same "keep running" notification while in an internet game.
* The seat key is saved on the phone when the game starts (ChimeraGuestSeat.kt), so reopening the app offers
  "Rejoin your game"; the rejoin takes the same seat with that key and asks the host to send the seat, the game
  start and the table again (ArcanaForgeHost: requestResync with "resume").
* Leaving the game forgets the seat; a refused join or rejoin is written to the diagnostic log.

Fail-closed: every anchor must match exactly once; nothing is written unless all match.
Apply to an ephemeral staged Arcana donor (after ChimeraGuestSeat.kt is copied in), NEVER to arcana_table.
"""
from pathlib import Path
import sys

MARK = "ChimeraGuestSeat"

EDITS = {
    "ArgentumCloudMulti.kt": [
        ('''    private val graceMs: Long = 45L * 60_000L,
) {''', '''    private val graceMs: Long = 45L * 60_000L,
    // Owner, Nov 21: a guest coming back after Android closed the app starts with its saved seat and key.
    resumeSeat: Int = -1,
    resumeToken: String? = null,
) {'''),
        ('''    @Volatile var ownSeat: Int = -1
        private set''', '''    @Volatile var ownSeat: Int = resumeSeat
        private set'''),
        ('''    @Volatile private var seatToken: String? = null
''', '''    @Volatile private var seatToken: String? = resumeToken
    /** Guest: this phone's seat and its private key, saved so the seat can be taken back after the app is closed. */
    fun seatCredential(): Pair<Int, String>? = seatToken?.let { token -> ownSeat.takeIf { it in 1..3 }?.let { it to token } }
'''),
        ('''internal class ArgentumCloudMultiGuest(
    endpoint: String, code: String, name: String,
) : ArgentumGuestLink {
    private val link = ArgentumDirectedLink(
        endpoint, ArgentumCloudLink.cleanCode(code), false, name)
''', '''internal class ArgentumCloudMultiGuest(
    endpoint: String, code: String, name: String, resumeSeat: Int = -1, resumeToken: String? = null,
) : ArgentumGuestLink {
    private val link = ArgentumDirectedLink(
        endpoint, ArgentumCloudLink.cleanCode(code), false, name, resumeSeat = resumeSeat, resumeToken = resumeToken)
    fun seatCredential(): Pair<Int, String>? = link.seatCredential()
'''),
    ],
    "TableWifi.kt": [
        ('''    val guest = ArgentumCloudMultiGuest(lobbyCloudEndpoint, clean, friendlyName(commander))
    argentumSocket.attachLanGuest(guest)
''', '''    val guest = ArgentumCloudMultiGuest(lobbyCloudEndpoint, clean, friendlyName(commander))
    ChimeraGuestSeat.link = guest // owner, Nov 21: its seat is saved when the game starts
    ChimeraGuestSeat.code = clean
    ChimeraGuestSeat.name = friendlyName(commander)
    argentumSocket.attachLanGuest(guest)
'''),
        ('''        recordDiagnostic("Multiplayer lobby joined: internet; awaiting host roster", DiagnosticCategory.CONNECTION)
''', '''        recordDiagnostic("Multiplayer lobby joined: internet; awaiting host roster", DiagnosticCategory.CONNECTION)
        // Owner, Nov 21: keep a guest's app running while the phone sleeps, as the host's is.
        if (!ChimeraRoomHostGuardService.start(context, guest = true))
            recordDiagnostic("Multiplayer: Android did not allow the keep-running notification for this guest", DiagnosticCategory.CONNECTION)
'''),
        ('''internal fun TableController.stopWifi() {
    ChimeraRoomHostGuardService.stop(context)
''', '''internal fun TableController.stopWifi() {
    ChimeraRoomHostGuardService.stop(context)
    ChimeraGuestSeat.clear(context) // left the game: nothing to rejoin
'''),
        ('''            message = "Couldn't join the multiplayer room: $failure"
''', '''            message = "Couldn't join the multiplayer room: $failure"
            recordDiagnostic("Multiplayer lobby join refused: $failure", DiagnosticCategory.CONNECTION)
'''),
    ],
    "TableArgentum.kt": [
        ('''        "gameStarted" -> {
            wifiGuestWaiting = false
''', '''        "gameStarted" -> {
            if (wifiRole == "online-guest") ChimeraGuestSeat.saveCurrent(context) // owner, Nov 21: rejoin after the app closes
            wifiGuestWaiting = false
'''),
    ],
    "TableOverlays.kt": [
        ('''            StudioScreen("Home") { ArcanaHomeScreen(
''', '''            ChimeraGuestRejoinPrompt() // owner, Nov 21: "Rejoin your game" after Android closed the app
            StudioScreen("Home") { ArcanaHomeScreen(
'''),
    ],
}


def patch(app: Path) -> None:
    src = app / "src/main/java/com/arcanatable"
    if not (src / "ChimeraGuestSeat.kt").is_file():
        raise ValueError("ChimeraGuestSeat.kt must be copied in before patch_guest_rejoin.py")
    if MARK in (src / "TableWifi.kt").read_text(encoding="utf-8"):
        raise ValueError("guest-rejoin patch already applied")
    staged = {}
    for name, edits in EDITS.items():
        text = (src / name).read_text(encoding="utf-8")
        for old, new in edits:
            count = text.count(old)
            if count != 1:
                raise ValueError(f"{name}: guest-rejoin anchor found {count} times, expected 1: {old[:70]!r}")
            text = text.replace(old, new)
        staged[name] = text
    for name, text in staged.items():
        (src / name).write_text(text, encoding="utf-8")
    print("Guests: kept running in online games; saved seat offers Rejoin after the app is closed")


if __name__ == "__main__":
    if len(sys.argv) != 2:
        raise SystemExit("usage: patch_guest_rejoin.py STAGED_ANDROID_APP")
    patch(Path(sys.argv[1]))
