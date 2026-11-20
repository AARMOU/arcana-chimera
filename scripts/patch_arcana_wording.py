#!/usr/bin/env python3
"""Build-time: no "Argentum" or "ARGENTUM" on screen (owner, Nov 9: "It still says argentum in the game tools menu").

Forge is the rules engine now. Every user-visible text in Arcana's Kotlin that names Argentum is reworded (a few by
hand, the rest "Argentum" -> "Forge"); code names, Studio IDs and Studio screen names stay as they are, so saved
settings and Studio designs keep working. Only the text parts of string literals change, never code inside ${...}.
The build fails if any visible "Argentum" is left. Never committed to arcana_table.
"""
import re
import sys
from pathlib import Path

app = Path(sys.argv[1]) / "src/main/java/com/arcanatable"

BY_HAND = {
    "Argentum legal actions · ": "Legal actions · ",
    "Argentum room code copied.": "Room code copied.",
    "Argentum room code": "Room code",
    "Argentum room": "Online room",
    "Argentum isn't offering you any actions at the moment.": "There's nothing you can do right now.",
    "Argentum offers no action for this card at this moment (wrong timing, no mana, or not your priority).":
        "You can't do anything with this card right now (wrong timing, not enough mana, or not your priority).",
    "Argentum-supported cards only (works offline)": "Cards Forge supports (works offline)",
    "Argentum library check unavailable in this build.": "Every card goes to Forge, which reports any it lacks when the game starts.",
    "Leave Argentum game?": "Leave this game?",
    "Leaving a live game concedes the match to Argentum.": "Leaving a game in progress concedes it.",
    "This will leave the Argentum room.": "This will leave the room.",
    "Argentum needs a choice from you.": "The game needs a choice from you.",
    "Argentum needs a choice": "Your choice",
    "Argentum is waiting for you.": "It's your move.",
    "Argentum has nothing to undo right now.": "There's nothing to undo right now.",
    "Argentum has not offered a pass-priority action right now.": "You can't pass priority right now.",
    "Argentum stack and priority": "Stack and priority",
    "Find an Argentum-supported card": "Find a card",
    "Import your deck, then choose Play with Argentum.": "Import your deck, then choose Play.",
    "Argentum's engine can't run": "Forge can't run",
    "Argentum client": "Forge (on this device)",
}
# Capitals (labels, headings, buttons): owner, Nov 9: "ARGENTUM" in any capitalisation goes too.
BY_HAND_UPPER = {
    "ARGENTUM AI · READY": "FORGE AI · READY",
    "ARGENTUM ENGINE": "FORGE ENGINE",
    "ARGENTUM ROOM CODE": "ROOM CODE",
    "ARGENTUM GAME MAY STILL BE RUNNING": "THE GAME MAY STILL BE RUNNING",
    "ARGENTUM RUNS THE RULES": "FORGE RUNS THE RULES",
    "CHECKING DECK WITH ARGENTUM…": "CHECKING DECK…",
    "PLAY ON ARGENTUM WITHOUT THEM": "PLAY WITHOUT THEM",
    "PLAY WITH ARGENTUM": "PLAY",
    "SEARCH ARGENTUM": "SEARCH CARDS",
    "SUGGEST ARGENTUM SWAPS": "SUGGEST SWAPS",
    "SUGGESTED ARGENTUM SWAPS": "SUGGESTED SWAPS",
    "WAITING FOR ARGENTUM": "WAITING FOR FORGE",
}
WORD = re.compile(r"\bArgentum\b")
ANY = re.compile(r"\b(?:Argentum|ARGENTUM)\b")  # what reads on screen, in either capitalisation
ID_LIKE = re.compile(r"^[A-Za-z]+(\.[A-Za-z0-9]+)*\.\d+$|^[A-Z][A-Za-z]*( [A-Z][A-Za-z]*)* \d+$")
STUDIO_ARG = re.compile(r"[Ss]tudio\w*\(\s*(\"(?:[^\"\\]|\\.)*\"\s*,\s*)?$")


def scan(text, i=0, n=None):
    """Yields (start, end, literal_start) for the text parts of every string literal, including literals nested in ${...}."""
    n = len(text) if n is None else n
    while i < n:
        c = text[i]
        if text.startswith("//", i):
            i = text.find("\n", i); i = n if i < 0 else i; continue
        if text.startswith("/*", i):
            j = text.find("*/", i + 2); i = n if j < 0 else j + 2; continue
        if c == "'":
            j = i + 1
            while j < n and text[j] != "'":
                j += 2 if text[j] == "\\" else 1
            i = j + 1; continue
        if c == '"':
            yield from string(text, i)
            i = skip_string(text, i); continue
        i += 1


def skip_string(text, i):
    if text.startswith('"""', i):
        j = text.find('"""', i + 3)
        return len(text) if j < 0 else j + 3
    j = i + 1
    while j < len(text):
        if text[j] == "\\": j += 2; continue
        if text[j] == '"': return j + 1
        if text.startswith("${", j): j = skip_code(text, j + 2); continue
        j += 1
    return j


def skip_code(text, j):
    depth = 1
    while j < len(text) and depth:
        if text[j] == '"': j = skip_string(text, j); continue
        if text[j] == "{": depth += 1
        elif text[j] == "}": depth -= 1
        j += 1
    return j


def string(text, i):
    if text.startswith('"""', i):
        return  # raw strings hold no user text here
    start = i + 1; j = start; seg = start
    while j < len(text):
        if text[j] == "\\": j += 2; continue
        if text[j] == '"':
            yield (seg, j, i); return
        if text.startswith("${", j):
            yield (seg, j, i); end = skip_code(text, j + 2)
            yield from scan(text, j + 2, end - 1)  # e.g. "Role: ${if (x) "Argentum client" else ...}"
            j = end; seg = j; continue
        j += 1


def visible(text, lit_start, whole):
    if ID_LIKE.match(whole): return False
    line_start = text.rfind("\n", 0, lit_start) + 1
    if re.search(r"\bTAG\s*=\s*$", text[line_start:lit_start]): return False  # Android log tag
    return not STUDIO_ARG.search(text[line_start:lit_start])


def literal_text(text, lit_start):
    end = skip_string(text, lit_start)
    return text[lit_start + 1:end - 1]


changed_files, changed, left = 0, 0, []
for f in sorted(app.rglob("*.kt")):
    if f.name.startswith("Chimera"): continue
    text = f.read_text()
    if "rgentum" not in text and "RGENTUM" not in text: continue
    edits = []
    for (a, b, lit) in scan(text):
        part = text[a:b]
        if not ANY.search(part): continue
        if not visible(text, lit, literal_text(text, lit)): continue
        new = part
        for old, rep in sorted(BY_HAND.items(), key=lambda kv: -len(kv[0])):
            new = new.replace(old, rep)
        new = re.sub(r"\ban Argentum\b", "a Forge", new)
        new = re.sub(r"\bAn Argentum\b", "A Forge", new)
        new = new.replace("Argentum's", "Forge's")
        new = WORD.sub("Forge", new)
        for old, rep in sorted(BY_HAND_UPPER.items(), key=lambda kv: -len(kv[0])):
            new = new.replace(old, rep)
        new = re.sub(r"\bARGENTUM\b", "FORGE", new)
        if new != part: edits.append((a, b, new))
    if edits:
        for a, b, new in reversed(edits):
            text = text[:a] + new + text[b:]
        f.write_text(text); changed_files += 1; changed += len(edits)
    for (a, b, lit) in scan(text):
        if ANY.search(text[a:b]) and visible(text, lit, literal_text(text, lit)):
            left.append(f"{f.name}: {text[a:b][:80]}")

print(f"patch_arcana_wording: reworded {changed} text(s) in {changed_files} file(s)")
if left:
    sys.exit("patch_arcana_wording: 'Argentum' still on screen:\n  " + "\n  ".join(left))
