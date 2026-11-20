#!/usr/bin/env python3
"""APK build check: the ManaBox reader (ChimeraDeckImport.manaboxData, mirrored) on a real deck page.

ManaBox pages embed the deck as data (one entry per printing: internalId, name, quantity, boardCategory, setId,
collectorNumber, imageUrlNormal), HTML-escaped inside the page. Informational, never fails the build.
Usage: manabox_check.py <deck link> [--dump]
"""
import re
import sys
import urllib.request
from collections import Counter

ENTRY = re.compile(r'"internalId":\[0,(\d+)\]')


def field(chunk, key, num=False):
    m = re.search(r'"%s":\[0,%s\]' % (key, r'(\d+)' if num else r'"((?:[^"\\]|\\.)*)"'), chunk)
    return (int(m.group(1)) if num else m.group(1)) if m else None


def manabox_data(html):
    text = html.replace("&quot;", '"').replace("&amp;", "&").replace("&#39;", "'").replace("&#x27;", "'")
    starts = [m.start() for m in ENTRY.finditer(text)]
    seen, out = set(), []
    for i, s in enumerate(starts):
        chunk = text[s: starts[i + 1] if i + 1 < len(starts) else s + 6000]
        iid = field(chunk, "internalId", True)
        name, qty, cat = field(chunk, "name"), field(chunk, "quantity", True), field(chunk, "boardCategory", True)
        if iid is None or not name or qty is None or iid in seen:
            continue
        seen.add(iid)
        out.append(dict(id=iid, name=name, qty=qty, cat=cat, set=field(chunk, "setId"),
                        cn=field(chunk, "collectorNumber"), img=field(chunk, "imageUrlNormal")))
    return out


try:
    req = urllib.request.Request(sys.argv[1], headers={"User-Agent": "Mozilla/5.0 (Linux; Android 15) ArcanaTable",
                                                       "Accept": "text/html,application/json;q=0.9,*/*;q=0.8"})
    page = urllib.request.urlopen(req, timeout=30).read().decode("utf-8", "replace")
    cards = manabox_data(page)
    cats = Counter()
    for c in cards:
        cats[c["cat"]] += c["qty"]
    print(f"MANABOX CHECK: {sum(c['qty'] for c in cards)} cards in {len(cards)} entries; by boardCategory {dict(cats)}")
    for cat in sorted(cats):
        print(f"MANABOX CHECK: category {cat}: " + ", ".join(f"{c['qty']} {c['name']}" for c in cards if c["cat"] == cat)[:300])
    if "--dump" in sys.argv:
        for key in ("boardCategories", "boards", "commander", "categories"):
            i = page.find(key)
            if i >= 0:
                print(f"MANABOX CHECK: near {key}:", re.sub(r"\s+", " ", page[max(0, i - 200): i + 600].replace("&quot;", '"')))
except Exception as e:
    print(f"MANABOX CHECK: skipped ({type(e).__name__}: {e})")
