#!/usr/bin/env python3
"""APK build helper: one small name -> picture file for the game (owner, Nov 9: blank mulligan cards).

Arcana's own picture lookup reads its full card files (~33k long lines) the first time, which takes longer than
Forge needs to deal the opening hand. This writes the same answers, a card's own picture (the last "metadata"
imageUri on its line, as CardImages.bundledImage does), as `lowercased name <TAB> link`, which the phone loads in a
fraction of a second.

Usage: chimera_pictures.py <assets/argentum dir>   (writes chimera-pictures.tsv there)
"""
import json
import os
import re
import sys

base = sys.argv[1]
name_rx = re.compile(r'^\{"name":"((?:[^"\\]|\\.)*)"')
image_rx = re.compile(r'"metadata":\{[^{}]*?"imageUri":"(https://[^"]+)"')
out = {}
for f in ("custom-cards.jsonl", "all-cards.jsonl", "forge-cards.jsonl"):
    path = os.path.join(base, f)
    if not os.path.exists(path):
        continue
    with open(path, encoding="utf-8") as fh:
        for line in fh:
            n = name_rx.match(line)
            imgs = image_rx.findall(line)
            if not n or not imgs:
                continue
            name = json.loads('"' + n.group(1) + '"')
            key = name.lower()
            out.setdefault(key, imgs[-1])
            if " // " in key:
                out.setdefault(key.split(" // ")[0], imgs[-1])
with open(os.path.join(base, "chimera-pictures.tsv"), "w", encoding="utf-8") as fh:
    for k, v in out.items():
        if "\t" not in k and "\n" not in k:
            fh.write(f"{k}\t{v}\n")
print(f"chimera_pictures: {len(out)} names")
