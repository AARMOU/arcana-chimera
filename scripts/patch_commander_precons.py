#!/usr/bin/env python3
"""Apply the isolated Commander-precon library overlay to staged Arcana source.

Chimera owns this patch; it NEVER edits AARMOU/arcana_table. All anchors are
strict and fail rather than silently hiding old decks on a drifted Arcana build.
"""
from pathlib import Path
import sys

base = Path(sys.argv[1]) / "src/main/java/com/arcanatable"

def replace_one(path: Path, old: str, new: str) -> None:
    raw = path.read_text(encoding="utf-8")
    count = raw.count(old)
    if count != 1:
        raise SystemExit(f"WotC precon overlay refused {path.name}: expected one source anchor, found {count}")
    path.write_text(raw.replace(old, new), encoding="utf-8")
    print(f"WotC precon overlay: patched {path.name}")

controller = base / "TableController.kt"
source = controller.read_text(encoding="utf-8")
begin = source.find("    val preconDecks = listOf(")
end = source.find("    var savedDecksOpen", begin)
if begin == -1 or end == -1:
    raise SystemExit("WotC precon overlay: original precon declaration changed; stop and reconcile")
old_block = source[begin:end]
if "WipPrecons.available" not in old_block or "fallout_scrappy_survivors" not in old_block:
    raise SystemExit("WotC precon overlay: original six/legacy precon declaration not recognized")
replace_one(controller, old_block,
            '    val preconDecks = ChimeraPrecons.load { path -> context.assets.open(path) }\n')

overlay = base / "TableOverlays.kt"
replace_one(overlay,
    '                    premadeDecks.map { DeckChoice("builtin:${it.id}", it.name, it.commander, builtIn = true, category = argentumDeckCategory(it.id)) } +\n',
    '')  # Keep existing premade decks for offline AI, but NOT in Change Deck.

home = base / "HomeScreen.kt"
replace_one(home,
    '''                DeckChoiceCategory.PRECON to "WOTC PRECONS",
                DeckChoiceCategory.ARCANA to "ARCANA",
                DeckChoiceCategory.ARGENTUM to "ARGENTUM PREMADE DECKS",
                DeckChoiceCategory.OTHER to "YOUR OTHER DECKS"''',
    '''                DeckChoiceCategory.PRECON to "WOTC COMMANDER PRECONS",
                DeckChoiceCategory.OTHER to "YOUR OTHER DECKS"''')
replace_one(home,
    '''                list.forEach { d ->
                    Row(''',
    '''                if (category == DeckChoiceCategory.PRECON) ChimeraPreconTree(list, activeId, onPick)
                else list.forEach { d ->
                    Row(''')
