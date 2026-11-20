#!/usr/bin/env python3
"""Chimera-only patch: let AI lobby seats choose any imported or bundled Commander deck.

Apply to an ephemeral staged Arcana donor, NEVER to the arcana_table repository.
The shared importDeck() pipeline still validates/loads the chosen AI seat's cards.
"""
from pathlib import Path
import sys

def patch(app: Path) -> None:
    path = app / "src/main/java/com/arcanatable/TableOverlaysB.kt"
    source = path.read_text(encoding="utf-8")
    anchor = '                        StudioTextButton("TableOverlaysB.TextButton.3", "Dialogs and overlays", onClick = { run { savedDecksOpen = true } }, modifier = Modifier.fillMaxWidth().heightIn(min = 42.dp)) { StudioText("TableOverlaysB.Text.8", "Dialogs and overlays", true, "SAVED DECKS · ${savedDecks.size}", color = Mint) }'
    assert source.count(anchor) == 1 and "ASSIGN EXISTING DECK TO THIS AI" not in source, "AI deck selector: donor changed or already patched"
    addition = '''
                        // Chimera: AI seats use the same saved/imported and WotC decklists as human seats.
                        // Importing into selectedPlayer preserves per-seat game state and lobby readiness.
                        if (players.getOrNull(selectedPlayer)?.isComputer == true && !importing) {
                            StudioText("AI deck selection", "Dialogs and overlays", true,
                                "ASSIGN EXISTING DECK TO THIS AI", color = Mint, fontSize = 12.sp)
                            var aiLibraryExpanded by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
                            androidx.compose.material3.TextButton(
                                onClick = { aiLibraryExpanded = !aiLibraryExpanded },
                                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
                            ) {
                                androidx.compose.material3.Text(
                                    if (aiLibraryExpanded) "HIDE DECK LIBRARY" else "CHOOSE FROM DECK LIBRARY",
                                    color = Mint
                                )
                            }
                            if (aiLibraryExpanded) {
                            savedDecks.forEach { deck ->
                                androidx.compose.material3.TextButton(
                                    onClick = { importDeck(deck.text, saveAsMine = false) },
                                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
                                ) {
                                    androidx.compose.material3.Text(deck.name, color = SoftWhite)
                                }
                            }
                            preconDecks.forEach { deck ->
                                androidx.compose.material3.TextButton(
                                    onClick = { importDeck(deck.decklist, saveAsMine = false) },
                                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
                                ) {
                                    androidx.compose.material3.Text(deck.name, color = SoftWhite)
                                }
                            }
                            }
                        }
'''
    path.write_text(source.replace(anchor, anchor + "\n" + addition.rstrip("\n")), encoding="utf-8")
    print("Chimera AI seat deck selector installed (saved decks and official precons)")

if __name__ == "__main__":
    if len(sys.argv) != 2:
        raise SystemExit("usage: patch_ai_deck_selector.py STAGED_ANDROID_APP")
    patch(Path(sys.argv[1]))
