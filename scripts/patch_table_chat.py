#!/usr/bin/env python3
"""Chimera-only: table chat in Forge multiplayer games (owner, Nov 23: "What happened to the chat feature?").

Arcana's chat travelled inside its older room state; engine games send their own messages, so Arcana hid the chat
button in every engine game. Chat now rides the game's own link: a message goes to the host (LocalArgentumHost), which
shows it there and passes it to everyone else with the sender's seat name. Wi-Fi and internet games, host and guests.
Offline games against AI have nobody to chat with, so the button stays hidden there.

Fail-closed: every anchor must match exactly once; nothing is written unless all match.
Apply to an ephemeral staged Arcana donor, NEVER to arcana_table.
"""
from pathlib import Path
import sys

MARK = "arcanaChat"

EDITS = {
    "TableArgentum.kt": [
        ('''        "lanSeat" -> {
''', '''        "arcanaChat" -> { // owner, Nov 23: chat in multiplayer games, passed on by the host
            val text = incoming.optString("text").trim().take(500)
            if (text.isNotEmpty()) chatMessages = (chatMessages + ChatMessage(incoming.optString("author").ifBlank { "Player" }, text,
                formatClock(elapsedGameSeconds))).takeLast(200)
        }
        "lanSeat" -> {
'''),
    ],
    "TableMainLayout.kt": [
        ('''            if (!argentumEnabled) Box(Modifier) {
''', '''            // Owner, Nov 23: engine games with other people carry chat over the game's own link (the host passes it on).
            if (!argentumEnabled || wifiRole == "guest" || wifiRole == "online-guest" || argentumSocket.runningLocalHost?.isWifi == true) Box(Modifier) {
'''),
    ],
    "TableOverlaysB.kt": [
        ('''players = players, activePlayer = if (onlineMode) localSeat else activePlayer, messages = chatMessages''',
         '''players = players, activePlayer = if (onlineMode || argentumEnabled) localSeat else activePlayer, messages = chatMessages'''),
        ('''                val speaker = if (onlineMode) localSeat else activePlayer
                chatMessages = chatMessages + ChatMessage(players[speaker.coerceIn(players.indices)].name, text, formatClock(elapsedGameSeconds))
                logAction(speaker, "sent a chat message")
''', '''                val speaker = if (onlineMode || argentumEnabled) localSeat else activePlayer
                chatMessages = chatMessages + ChatMessage(players[speaker.coerceIn(players.indices)].name, text, formatClock(elapsedGameSeconds))
                if (argentumEnabled) argentumSocket.send(org.json.JSONObject().put("type", "arcanaChat").put("text", text.take(500)))
                else logAction(speaker, "sent a chat message")
'''),
    ],
}


def patch(app: Path) -> None:
    src = app / "src/main/java/com/arcanatable"
    if MARK in (src / "TableArgentum.kt").read_text(encoding="utf-8"):
        raise ValueError("table-chat patch already applied")
    staged = {}
    for name, edits in EDITS.items():
        text = (src / name).read_text(encoding="utf-8")
        for old, new in edits:
            count = text.count(old)
            if count != 1:
                raise ValueError(f"{name}: table-chat anchor found {count} times, expected 1: {old[:70]!r}")
            text = text.replace(old, new)
        staged[name] = text
    for name, text in staged.items():
        (src / name).write_text(text, encoding="utf-8")
    print("Table chat in Forge multiplayer games")


if __name__ == "__main__":
    if len(sys.argv) != 2:
        raise SystemExit("usage: patch_table_chat.py STAGED_ANDROID_APP")
    patch(Path(sys.argv[1]))
