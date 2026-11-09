#!/usr/bin/env python3
"""APK build check: the ManaBox reader (ChimeraDeckImport.manaboxText, mirrored line for line) on a real deck page.

Informational, never fails the build. Prints e.g. "MANABOX CHECK: 100 cards, commander Hazel of the Rootbloom".
Usage: manabox_check.py <deck link>
"""
import re
import sys
import urllib.request

HEADING = re.compile(r"^(Commanders?|Companions?|Planeswalkers?|Creatures?|Instants?|Sorcery|Sorceries|Artifacts?|Enchantments?|Lands?|Battles?|Other|Sideboard|Maybeboard|Mainboard|Deck)(\s*\(?\d+\)?)?$", re.I)


def manabox_text(html):
    html = re.sub(r"(?s)<(script|style|svg)[^>]*>.*?</\1>", "\n", html)
    html = re.sub(r"<[^>]+>", "\n", html)
    for a, b in (("&amp;", "&"), ("&#x27;", "'"), ("&#39;", "'"), ("&quot;", '"'), ("&nbsp;", " ")):
        html = html.replace(a, b)
    lines = [l.strip() for l in html.splitlines() if l.strip()]
    entries, section, i = [], "", 0
    while i < len(lines):
        line = lines[i]
        m = HEADING.match(line)
        if m:
            section = m.group(1).lower(); i += 1; continue
        if line.isdigit() and 1 <= int(line) <= 99 and i + 1 < len(lines):
            name = lines[i + 1]
            if len(name) > 1 and not name.isdigit() and not HEADING.match(name) and any(c.isalpha() for c in name):
                entries.append((section, int(line), name)); i += 2; continue
        i += 1
    key = lambda e: (e[1], e[2].lower())
    lst, run = entries, min(8, len(entries) // 2)
    found = False
    for start in range(0, min(4, len(entries))):
        for k in range(start + run, len(entries) - run + 1):
            if all(key(entries[start + t]) == key(entries[k + t]) for t in range(run)):
                lst, found = entries[start:k], True
                break
        if found:
            break
    totals = {}
    for sec, q, name in lst:
        where = ("commander" if sec.startswith("commander") else "companion" if sec.startswith("companion")
                 else "side" if sec == "sideboard" else "maybe" if sec == "maybeboard" else "main")
        totals[(where, name)] = totals.get((where, name), 0) + q
    cmd = {n.lower() for (w, n) in totals if w == "commander"}
    commander = [(q, n) for (w, n), q in totals.items() if w == "commander"]
    main = [(q, n) for (w, n), q in totals.items() if w == "main" and n.lower() not in cmd]
    return commander, main


try:
    req = urllib.request.Request(sys.argv[1], headers={"User-Agent": "Mozilla/5.0 (Linux; Android 15) ArcanaTable",
                                                       "Accept": "text/html,application/json;q=0.9,*/*;q=0.8"})
    page = urllib.request.urlopen(req, timeout=30).read().decode("utf-8", "replace")
    c, m = manabox_text(page)
    total = sum(q for q, _ in c) + sum(q for q, _ in m)
    print(f"MANABOX CHECK: {total} cards, commander {', '.join(n for _, n in c) or 'none'}; first: {m[:3]}")
    if total < 60:
        print("MANABOX CHECK: page sample:", re.sub(r"\s+", " ", re.sub(r"<[^>]+>", " ", page))[:1500])
except Exception as e:
    print(f"MANABOX CHECK: skipped ({type(e).__name__}: {e})")
