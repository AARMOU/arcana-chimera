#!/usr/bin/env python3
"""Chimera-only: allow commander art changes in deck editor and persist preview choice.

Run on an ephemeral staged Arcana app AFTER patch_arcana_for_forge.py.
No edits to the paused Arcana/Argentum repository.
"""
from pathlib import Path
import sys

def replace_once(path: Path, old: str, new: str) -> None:
    source = path.read_text(encoding="utf-8")
    if source.count(old) != 1 or new in source:
        raise RuntimeError(f"Commander art overlay: incompatible or previously patched {path.name}: {source.count(old)} anchors")
    path.write_text(source.replace(old, new), encoding="utf-8")

def patch(app: Path) -> None:
    src = app / "src/main/java/com/arcanatable"
    editor = src / "DeckBuilderScreen.kt"
    summary = src / "DeckBuilder.kt"
    overlays = src / "TableOverlays.kt"
    replace_once(editor,
        '''    val flagged = remember(issues) { issues.filter { it.level == IssueLevel.ERROR }.flatMap { it.cards }.map { it.lowercase() }.toSet() }
    LazyColumn(modifier, verticalArrangement = Arrangement.spacedBy(5.dp)) {''',
        '''    val flagged = remember(issues) { issues.filter { it.level == IssueLevel.ERROR }.flatMap { it.cards }.map { it.lowercase() }.toSet() }
    // The commander must be able to select artwork without moving zones.
    // ChimeraArt stores the printing by card name, not by the deck zone.
    var commanderArtName by remember { mutableStateOf<String?>(null) }
    var commanderArtRevision by remember { mutableIntStateOf(0) }
    commanderArtName?.let { name ->
        ChimeraArtPicker(name) { commanderArtName = null; commanderArtRevision++ }
    }
    LazyColumn(modifier, verticalArrangement = Arrangement.spacedBy(5.dp)) {''')
    replace_once(editor,
        '''                    card?.imageUrl?.let { CardArt(it, Modifier.size(width = 52.dp, height = 72.dp).clip(RoundedCornerShape(4.dp)), ContentScale.Crop, fallbackName = name) }''',
        '''                    val commanderPicture = remember(name, commanderArtRevision) { ChimeraArt.get(name) ?: card?.imageUrl }
                    commanderPicture?.let { picture ->
                        CardArt(picture, Modifier.size(width = 52.dp, height = 72.dp).clip(RoundedCornerShape(4.dp)), ContentScale.Crop, fallbackName = name)
                    }''')
    replace_once(editor,
        '''                    if (card == null) StudioTextButton("DeckBuilderScreen.TextButton.17", "Decks", onClick = { onReplace(name) }) { StudioText("DeckBuilderScreen.Text.97", "Decks", false, "REPLACE", color = Mint, fontSize = 11.sp) }
                    StudioTextButton("DeckBuilderScreen.TextButton.18", "Decks", onClick = { onRemoveCommander(name) }, modifier = Modifier.heightIn(min = 48.dp)) { StudioText("DeckBuilderScreen.Text.98", "Decks", false, "TO DECK", color = Muted, fontSize = 11.sp) }''',
        '''                    if (card == null) StudioTextButton("DeckBuilderScreen.TextButton.17", "Decks", onClick = { onReplace(name) }) { StudioText("DeckBuilderScreen.Text.97", "Decks", false, "REPLACE", color = Mint, fontSize = 11.sp) }
                    androidx.compose.material3.TextButton(
                        onClick = { commanderArtName = name },
                        modifier = Modifier.heightIn(min = 48.dp).testTag("commander-change-art")
                    ) { androidx.compose.material3.Text("ART", color = Mint, fontSize = 11.sp) }
                    StudioTextButton("DeckBuilderScreen.TextButton.18", "Decks", onClick = { onRemoveCommander(name) }, modifier = Modifier.heightIn(min = 48.dp)) { StudioText("DeckBuilderScreen.Text.98", "Decks", false, "TO DECK", color = Muted, fontSize = 11.sp) }''')
    replace_once(editor,
        '''                card?.imageUrl?.let { CardArt(it, Modifier.fillMaxWidth().aspectRatio(0.716f).clip(RoundedCornerShape(10.dp)), fallbackName = card.name, fallbackType = card.typeLine) }''',
        '''                (ChimeraArt.get(name) ?: card?.imageUrl)?.let {
                    CardArt(it, Modifier.fillMaxWidth().aspectRatio(0.716f).clip(RoundedCornerShape(10.dp)), fallbackName = name, fallbackType = card?.typeLine)
                }''')
    replace_once(summary,
        '''draft.commanders.firstOrNull()?.let { catalog.find(it)?.imageUrl }''',
        '''draft.commanders.firstOrNull()?.let { ChimeraArt.get(it) ?: catalog.find(it)?.imageUrl }''')
    replace_once(overlays,
        '''            val art = commanderName?.let { name -> homeArtUrl(tableArt, bundledArt, name) }''',
        '''            val art = commanderName?.let { name -> ChimeraArt.get(name) ?: homeArtUrl(tableArt, bundledArt, name) }''')
    print("Commander art selector, deck preview and home artwork now use the same saved choice")

if __name__ == "__main__":
    if len(sys.argv) != 2:
        raise SystemExit("usage: patch_commander_art_editor.py STAGED_APP")
    patch(Path(sys.argv[1]))
