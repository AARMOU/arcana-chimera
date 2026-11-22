#!/usr/bin/env python3
"""Chimera-only: no wrong card pictures in the second and later games (owner, Nov 21).

The owner saw Abhorrent Oculus's picture labelled "Volcanic Island" in a Scalding Tarn search, and other wrong
pictures in library searches and pick lists, but only after the first game. The cause:

* A library card is in no table zone, so the pick grid had no name for it and asked Scryfall for a placeholder
  link, ``named?exact=Object <id>``. That always fails, so the picture loader fell back to the real name from the
  decision and drew the right card...
* ...but remembered that picture under the placeholder link, in memory and in the phone's picture folder.
* Forge numbers cards afresh in every game. In the next game a different card with the same number makes the same
  placeholder link, and gets the previous game's picture. The label came from the engine, so it stayed right.

Fixes (each anchor must match exactly once; fail closed):
1. A picture found through the fallback name is remembered under the link *and* that name, so one link can no
   longer stand for two different cards. Pictures already saved under a bare placeholder link are not read again.
2. The pick grid uses the decision's own picture for cards it only knows from the decision (library searches),
   and no longer makes a placeholder ``Object <id>`` link at all.

Apply to an ephemeral staged Arcana donor after patch_arcana_nov9c.py, NEVER to arcana_table.
"""
from pathlib import Path
import sys

MARK = "pictureCacheKey"

# CardImages.kt: the key a picture is remembered under.
KEY_ANCHOR = '''    /** The card name inside a `named?exact=` link, if any. */'''
KEY_NEW = '''    /**
     * The key a picture is remembered under (owner, Nov 21: wrong pictures after the first game). A link that names
     * a different card than the one being drawn (Forge's per-game placeholder "Object <id>") is remembered together
     * with the card's name, so the next game's card with the same number can't get this card's picture.
     */
    internal fun pictureCacheKey(url: String, fallbackName: String?): String {
        val wanted = fallbackName?.trim()?.takeIf { it.isNotEmpty() && it != "Hidden card" } ?: return url
        val inLink = nameIn(url) ?: return url
        fun norm(s: String) = s.substringBefore(" // ").trim().lowercase(java.util.Locale.ROOT)
        return if (norm(inLink) == norm(wanted)) url else "$url#card=${norm(wanted)}"
    }

    /** The card name inside a `named?exact=` link, if any. */'''

LOAD_OLD = '''        withContext(Dispatchers.IO) {
            CardBitmapCache.get(url)?.let { return@withContext CardArtLoadResult.Loaded(it) }
            cacheFile(url)?.takeIf { it.exists() && it.length() > 0 }?.let { file ->
                BitmapFactory.decodeFile(file.path)?.let {
                    CardBitmapCache.put(url, it)'''
LOAD_NEW = '''        withContext(Dispatchers.IO) {
            val cacheKey = pictureCacheKey(url, fallbackName)
            CardBitmapCache.get(cacheKey)?.let { return@withContext CardArtLoadResult.Loaded(it) }
            cacheFile(cacheKey)?.takeIf { it.exists() && it.length() > 0 }?.let { file ->
                BitmapFactory.decodeFile(file.path)?.let {
                    CardBitmapCache.put(cacheKey, it)'''

SAVE_OLD = '''                        runCatching { cacheFile(url)?.writeBytes(bytes) }
                        CardBitmapCache.put(url, bitmap)'''
SAVE_NEW = '''                        runCatching { cacheFile(cacheKey)?.writeBytes(bytes) }
                        CardBitmapCache.put(cacheKey, bitmap)'''

# TableBoardViews.kt: CardArt's first look and the fallback chain (the chain is added by patch_arcana_nov9c.py).
REMEMBER_OLD = '''    var bitmap by remember(url) { mutableStateOf<Bitmap?>(CardBitmapCache.get(url)) }'''
REMEMBER_NEW = '''    var bitmap by remember(url, fallbackName, label) {
        mutableStateOf<Bitmap?>(CardBitmapCache.get(CardImages.pictureCacheKey(url, (fallbackName ?: label)?.takeIf { it.isNotBlank() })))
    }'''
CHAIN_OLD = '''if (got != null) { if (link != url) CardBitmapCache.put(url, got); bitmap = got;'''
CHAIN_NEW = '''if (got != null) { if (link != url) CardBitmapCache.put(CardImages.pictureCacheKey(url, name), got); bitmap = got;'''

# TableArgentum.kt: no placeholder "Object <id>" picture link for a card the table doesn't know.
PICK_OLD = '''    return PickCard(id, refName, artByName(refName, token = ref?.type?.contains("Token", ignoreCase = true) == true), ref?.type.orEmpty(), owner,'''
PICK_NEW = '''    // Owner, Nov 21: an unknown card (a library search) gets its picture from the decision, never from a placeholder name.
    return PickCard(id, refName, ref?.let { artByName(refName, token = it.type.contains("Token", ignoreCase = true)) }, ref?.type.orEmpty(), owner,'''

# ArgentumCardPick.kt: when the decision supplies the name, its picture goes with it.
INFO_OLD = '''    val image = imageUrl ?: str("imageUri") ?: if (isPlayer) null else artByName(shownName, isToken)'''
INFO_NEW = '''    // Owner, Nov 21: the name and the picture come from the same place (no other card's picture under this name).
    val image = (if (!nameKnown) str("imageUri") else null) ?: imageUrl ?: str("imageUri") ?: if (isPlayer) null else artByName(shownName, isToken)'''

EDITS = {
    "CardImages.kt": [(KEY_ANCHOR, KEY_NEW), (LOAD_OLD, LOAD_NEW), (SAVE_OLD, SAVE_NEW)],
    "TableBoardViews.kt": [(REMEMBER_OLD, REMEMBER_NEW), (CHAIN_OLD, CHAIN_NEW)],
    "TableArgentum.kt": [(PICK_OLD, PICK_NEW)],
    "ArgentumCardPick.kt": [(INFO_OLD, INFO_NEW)],
}


def patch(app: Path) -> None:
    src = app / "src/main/java/com/arcanatable"
    if MARK in (src / "CardImages.kt").read_text(encoding="utf-8"):
        raise ValueError("stale-picture fix already applied")
    staged = {}
    for name, edits in EDITS.items():
        text = (src / name).read_text(encoding="utf-8")
        for old, new in edits:
            count = text.count(old)
            if count != 1:
                raise ValueError(f"{name}: picture anchor found {count} times, expected 1: {old[:70]!r}")
            text = text.replace(old, new)
        staged[name] = text
    for name, text in staged.items():  # write only once every anchor matched
        (src / name).write_text(text, encoding="utf-8")
    print("Card pictures are remembered per card, not per game-specific number")


if __name__ == "__main__":
    if len(sys.argv) != 2:
        raise SystemExit("usage: patch_stale_pictures.py STAGED_ANDROID_APP")
    patch(Path(sys.argv[1]))
