#!/usr/bin/env python3
"""Chimera-only: every life change goes into the diagnostic log, with its cause (owner, Nov 17).

The owner went from 40 to 2 life in one game, and the diagnostic report couldn't say how: Arcana only wrote down a
life change when it happened in a combat-damage step or knocked someone out. The Forge host now sends each change
with the state that follows it ("chimeraLifeEvents": who, from, to, cause, turn, step); this writes one line per
change, for every player:

    Life · You 40 → 37 (−3): paid 3 life for mana from Treasonous Ogre · turn 5 MAIN1

Fail-closed: the donor anchor must match exactly once. Apply to an ephemeral staged Arcana donor, NEVER to arcana_table.
"""
from pathlib import Path
import sys

ANCHOR = "                // Close the retained block board only when combat has truly moved on.\n"
NEW = '''                // Chimera (owner, Nov 17): every life change, with what caused it, from the Forge host.
                incoming.optJSONArray("chimeraLifeEvents")?.let { lifeEvents ->
                    for (i in 0 until lifeEvents.length()) {
                        val e = lifeEvents.optJSONObject(i) ?: continue
                        val who = if (e.optBoolean("isYou") || (currentYou != null && e.optString("playerId") == currentYou.id)) "You"
                            else e.optString("name", "?")
                        val change = e.optInt("change")
                        val amount = if (e.optString("kind") == "poison") "poison ${e.optInt("from")} → ${e.optInt("to")}"
                            else "${e.optInt("from")} → ${e.optInt("to")} (${if (change > 0) "+" else "−"}${kotlin.math.abs(change)})"
                        recordDiagnostic(
                            "Life · $who $amount: ${e.optString("cause")} · turn ${e.optInt("turn")} ${e.optString("step")}",
                            DiagnosticCategory.ENGINE,
                        )
                    }
                }
'''


def patch(app: Path) -> None:
    path = app / "src/main/java/com/arcanatable/TableArgentum.kt"
    source = path.read_text(encoding="utf-8")
    if "chimeraLifeEvents" in source:
        raise RuntimeError("life-log patch already applied")
    if source.count(ANCHOR) != 1:
        raise RuntimeError(f"life-log anchor found {source.count(ANCHOR)} times, expected 1")
    path.write_text(source.replace(ANCHOR, NEW + ANCHOR), encoding="utf-8")
    print("Diagnostics: every life change is logged with its cause")


if __name__ == "__main__":
    if len(sys.argv) != 2:
        raise SystemExit("usage: patch_life_log.py STAGED_ANDROID_APP")
    patch(Path(sys.argv[1]))
