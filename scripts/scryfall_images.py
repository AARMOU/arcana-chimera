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
    def file_link(entry):
        """The bulk file's link, whatever Scryfall calls the field (download_uri, or a data.scryfall.io link)."""
        if entry.get("download_uri"):
            return entry["download_uri"]
        for k, v in entry.items():
            if isinstance(v, str) and v.startswith("https://") and ("download" in k or "data.scryfall.io" in v):
                return v
        return None

    listing = json.load(get("https://api.scryfall.com/bulk-data"))
    entries = listing.get("data", []) if isinstance(listing, dict) else []
    entry = next((e for e in entries if e.get("type") == "oracle_cards"), None)
    if entry is None:
        raise RuntimeError("no oracle_cards in Scryfall's bulk listing")
    uri = file_link(entry)
    if not uri and entry.get("uri"):  # the entry's own page lists the file
        entry = json.load(get(entry["uri"]))
        uri = file_link(entry)
    if not uri:
        raise RuntimeError("no file link in Scryfall's oracle_cards entry; fields: " + ", ".join(sorted(entry)))
    print(f"scryfall_images: downloading {uri}")
    raw = get(uri).read()
    if raw[:2] == b"\x1f\x8b":
        import gzip
        raw = gzip.decompress(raw)
    cards = json.loads(raw)
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
