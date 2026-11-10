#!/usr/bin/env python3
"""Build-time: Arcana's tutorial brought up to date for Arcana with Forge (owner, Nov 10: "Audit the tutorial and update
any irrelevant or wrong information, as well as update the tutorial to work with the current version as-is").

Edits assets/tutorials/catalog.json by chapter and step id. Each edit names text that must be present, so a changed
Arcana tutorial fails the build loudly instead of being silently half-edited. Never committed to arcana_table.
"""
import json
import sys
from pathlib import Path

path = Path(sys.argv[1]) / "src/main/assets/tutorials/catalog.json"
catalog = json.loads(path.read_text(encoding="utf-8"))
chapters = {c["id"]: c for c in catalog["chapters"]}
count = 0


def step(chapter, step_id):
    c = chapters.get(chapter) or sys.exit(f"patch_arcana_tutorial: no chapter {chapter}")
    for s in c["steps"]:
        if s["id"] == step_id:
            return s
    sys.exit(f"patch_arcana_tutorial: no step {chapter}/{step_id}")


def text(chapter, step_id, must_contain, title=None, body=None, **fields):
    """Replace a step's text; must_contain is checked against its current title + body."""
    global count
    s = step(chapter, step_id)
    current = s.get("title", "") + " " + s.get("body", "")
    if must_contain not in current:
        sys.exit(f"patch_arcana_tutorial: {chapter}/{step_id} no longer says {must_contain!r}")
    if title is not None: s["title"] = title
    if body is not None: s["body"] = body
    s.update(fields)
    count += 1


def chapter_text(chapter, must_contain, **fields):
    global count
    c = chapters[chapter]
    if must_contain not in c.get("description", "") + c.get("title", ""):
        sys.exit(f"patch_arcana_tutorial: chapter {chapter} no longer says {must_contain!r}")
    c.update(fields)
    count += 1


# ---- Quick Start
text("first-turn", "complete", "Settings › Learn & tutorials",
     body="You played a land, made mana, cast a creature and passed priority. More lessons are in the menu (⋮) or Settings, "
          "under Learn & tutorials, whenever you want them.")

# ---- Home, setup
text("home", "settings", "Settings controls card size",
     body="Settings has card size, gameplay behavior, notifications and help. The look of the whole game, from colours to "
          "button layouts, is edited in Arcana Studio.")
text("setup", "mode", "Argentum runs locally",
     body="Forge, the rules engine, runs on your phone. You don't need an internet connection for an offline match.")
text("setup", "two", "choose Play with a friend",
     body="Offline, choose 2, 3 or 4 players. Each extra player is an AI opponent with its own deck. To play with friends, "
          "use Multiplayer: host or join a game online or on Wi-Fi.")

# ---- Cards and the hand drawer
chapter_text("cards", "Argentum-provided", description="Open your hand, inspect cards and choose the actions the game allows.")
text("cards", "tap", "Argentum currently allows", body="Tap a card to see the actions the game allows right now.")
text("cards", "choose", "what Argentum allows",
     body="This sheet lists only what the game allows with that card right now: play, cast, or an ability. Choose one, or close the sheet.")
text("cards", "resize", "Drag the hand drawer",
     body="Drag the top of the hand drawer to trade battlefield space for larger hand cards. It stays where you let go.")
text("cards", "close", "Tap outside the drawer",
     body="Tap outside the drawer or its ✕, or flick it down, to close your hand and return to the table.")
text("mobile", "hand-resize", "Drag the drawer up",
     body="Drag the drawer up for card readability or down for a larger battlefield. It stays where you let go; a quick flick down closes it.")

# ---- Mana, turns, stack
text("mana", "cast", "Argentum checks the cost",
     body="Tap Elvish Mystic and choose to cast it. The game checks the cost and timing, and pays from your mana.")
text("turns", "pass", "Pass tells Argentum",
     body="Pass tells the game you don't want to act right now. It doesn't always mean your entire turn is over.")
text("turns", "stop", "Auto-pass stops by itself",
     title="Stop auto-passing",
     body="Auto-pass stops by itself when something needs your attention, and you can turn it off at any time. To make it "
          "stop at a phase of your choosing, press and hold that phase on the phase bar; hold it again to remove the stop.")
text("stack", "priority", "Argentum says are legal",
     body="Whoever has priority may act. Arcana only offers actions the game says are legal right now.")

# ---- Commander
text("commander", "commandzone", "Argentum's actions",
     body="Your commander starts in the command zone, shown by this chip. Tapping it opens its actions, including Cast when "
          "you can afford it. In the hand drawer, the COMMANDER button opens the same window and glows when you can cast it.")
text("commander", "legal", "Let Argentum adjudicate", title="Let the game adjudicate",
     body="Lessons explain the rules; the rules engine (Forge) applies them. If an action isn't offered, the game doesn't allow it right now.")

# ---- Quick actions, opponents
text("quick-actions", "configure", "Adjust UI lets you choose",
     body="Arcana Studio lets you choose which buttons appear, where they sit and how they look. Open it from Settings or the menu.",
     screen="settings", target="setting-adjust-ui")
text("opponents", "inspect", "turn that off in Adjust UI",
     body="Tap a recent-play notice to see the card large. When the AI plays a card, a pop-up shows it too.")

# ---- Decks
chapter_text("decks", "gated future features", description="Use the deck picker: your own decks and the WotC precons.")
text("decks", "categories", "ARGENTUM PREMADE DECKS",
     body="Your imported decks come first under YOUR DECKS. Below them, WotC Precons opens to the official Commander decks, "
          "grouped by product, with each deck's colours.")
text("decks", "search", "YOUR OTHER DECKS",
     body="Decks you add with Import new are listed under YOUR DECKS, ready to choose like any other.")

# ---- Settings and layout: Arcana Studio replaced the old Adjust UI editor in this version
chapter_text("settings", "readable and comfortable", description="Make the game readable and comfortable, and find Arcana Studio.")
text("settings", "layout", "Open the layout editor", title="Arcana Studio",
     body="ARCANA STUDIO opens the editor for the game's screens: move, resize and restyle buttons, the hand drawer, colours, "
          "backgrounds and type.", advance={"kind": "next"})
text("settings", "reorder", "Resize the table", title="Start from a preset",
     body="In Studio, Layout › Presets restyles the whole game in one tap; every preset can then be changed piece by piece. "
          "Screen layouts are whole-screen designs, each with its own menu.", screen="settings", target=None)
text("settings", "bottom", "Your bottom controls", title="Design your hand drawer",
     body="Studio's Hand screen edits the drawer you play from: card style (row, fan or catalogue), button shapes and sizes, "
          "and whether the Pass button sits under your thumb.", screen="settings", target=None)
text("settings", "restore", "Undo and restore", title="Draft, apply and undo",
     body="Studio keeps your changes as a draft until you tap APPLY. Undo and redo work while you edit, and Reset puts a "
          "screen back to Arcana's default.", screen="settings", target=None)

# ---- Menus
text("tools", "menu", "Adjust UI and Options",
     body="The three-dot button holds Learn & tutorials, Game tools, Room & game, What's new, Arcana Studio and Options.")
text("tools", "legal", "Argentum supplies",
     body="The rules engine supplies the allowed actions for the current state, not the tutorial itself.")
text("tools", "room", "Resume, save or return",
     body="Return to the home screen, open the lobby in an online game, or reset.")
text("tools", "reset", "Resetting is destructive",
     body="Room & game holds Reset game and Reset and clear all data. They end your match or erase your data. To give up a "
          "game instead, use Concede in Game tools. Lessons never use either; only choose them when you mean it.")
text("mobile", "layout", "Adjust UI lets you rearrange",
     body="Arcana Studio lets you rearrange the game controls. Use one design for every orientation, or turn on separate "
          "portrait and landscape designs; Arcana fits the design to the folded or unfolded screen.")

# ---- Help
text("help", "status", "while Argentum works",
     body="Turn this on to show a banner with the elapsed time while Forge works out a big turn.")

# Studio builds rewrite any step that points at the Arcana Studio button (TutorialHubDialog.studioTutorialLessons), so
# the Studio steps above point at nothing and keep their own words.

glossary_path = Path(sys.argv[1]) / "src/main/assets/tutorials/glossary.json"
glossary = json.loads(glossary_path.read_text(encoding="utf-8"))
if "Argentum" not in glossary.get("Legal action", ""):
    sys.exit("patch_arcana_tutorial: glossary 'Legal action' changed")
glossary["Legal action"] = "An action currently permitted by the game rules; Arcana offers only these."
if any("Argentum" in v for v in glossary.values()):
    sys.exit("patch_arcana_tutorial: the glossary still says Argentum")
glossary_path.write_text(json.dumps(glossary, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")

# Nothing may still name Argentum, Arcana's old rules engine.
leftover = [f"{c['id']}/{s['id']}" for c in catalog["chapters"] for s in c["steps"]
            if "Argentum" in s.get("title", "") + s.get("body", "")]
leftover += [c["id"] for c in catalog["chapters"] if "Argentum" in c.get("title", "") + c.get("description", "")]
if leftover:
    sys.exit("patch_arcana_tutorial: still says Argentum: " + ", ".join(leftover))

path.write_text(json.dumps(catalog, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
print(f"patch_arcana_tutorial: {count} edits")
