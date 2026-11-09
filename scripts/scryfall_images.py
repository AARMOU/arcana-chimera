#!/usr/bin/env python3
"""APK build helper: card name -> Scryfall picture link, for Forge's cards that Arcana's own library lacks.

Downloads Scryfall's free "Oracle Cards" bulk file (one entry per card) and writes a TSV of
name <TAB> image link (the "normal" size on cards.scryfall.io), front faces, back faces and full
"A // B" names. ForgeCatalogExport puts the link in each forge-cards.jsonl line, where Arcana's
picture lookup (CardImages.bundledImage) finds it, and the bridge sends it with every card.

Never fails the build: without the network it writes an empty file (cards then fall back to
Arcana's one-by-one Scryfall lookup, as before).
Usage: scryfall_images.py <out.tsv>
"""
import json
import sys
import urllib.error
import urllib.request

UA = {"User-Agent": "ArcanaChimera-build/1.0", "Accept": "application/json"}
out = sys.argv[1]


def get(url):
    return urllib.request.urlopen(urllib.request.Request(url, headers=UA), timeout=300)


rows = {}
try:
    listing = json.load(get("https://api.scryfall.com/bulk-data"))
    entries = listing.get("data", []) if isinstance(listing, dict) else []
    uri = next((e.get("download_uri") for e in entries if e.get("type") == "oracle_cards"), None)
    if not uri:
        raise RuntimeError("no oracle_cards in Scryfall's bulk listing: " + json.dumps(listing)[:400])
    print(f"scryfall_images: downloading {uri}")
    cards = json.load(get(uri))
    for c in cards:
        if c.get("layout") in ("art_series",):
            continue
        faces = c.get("card_faces") or []
        front = (c.get("image_uris") or {}).get("normal")
        if not front and faces and (faces[0].get("image_uris") or {}).get("normal"):
            front = faces[0]["image_uris"]["normal"]
        if front:
            rows.setdefault(c["name"].lower(), (c["name"], front))
            if faces:
                rows.setdefault(faces[0].get("name", "").lower(), (faces[0].get("name", ""), front))
        for f in faces[1:]:
            img = (f.get("image_uris") or {}).get("normal")
            if img and f.get("name"):
                rows.setdefault(f["name"].lower(), (f["name"], img))
except urllib.error.HTTPError as e:  # say what Scryfall answered, then keep building
    print(f"scryfall_images: skipped (HTTP {e.code}: {e.read()[:300]!r})", file=sys.stderr)
except Exception as e:  # no network or Scryfall changed: keep building
    print(f"scryfall_images: skipped ({type(e).__name__}: {e})", file=sys.stderr)

with open(out, "w", encoding="utf-8") as fh:
    for key, (name, img) in rows.items():
        if key and img.startswith("https://"):
            fh.write(f"{name}\t{img}\n")
print(f"scryfall_images: {len(rows)} names with pictures")
