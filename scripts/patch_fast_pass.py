#!/usr/bin/env python3
"""Chimera-only: "Pass to my turn" lets Forge pass the empty windows of opponents' turns itself (owner, Nov 13).

Slowdown: every automatic pass was a round trip (table build, send, 300 ms settle, pass back); one logged game
had 83. With each automatic pass the app now tells the Forge bridge it may pass on its own while the stack is
empty and it is an opponent's turn. The bridge stops doing so, and shows the table as before, for anything else.
Only asked for when the engine runs on this phone and no opponent-turn phase stop is set, so stops still stop.

Fail-closed: every anchor must match exactly once. Apply to an ephemeral staged Arcana donor, NEVER to arcana_table.
"""
from pathlib import Path
import sys

J = "src/main/java/com/arcanatable/"

PASS_OLD = "            ArgentumPassing.Step.PASS -> sendArgentumPriority()\n"
PASS_NEW = '''            ArgentumPassing.Step.PASS -> {
                // Chimera: Forge may pass the empty windows of opponents' turns itself until something needs showing.
                if (argentumOffline && ChimeraPhaseStops.stops.none { it.startsWith("opp:") })
                    argentumSocket.send("setPassToMyTurn", JSONObject().put("enabled", true))
                sendArgentumPriority()
            }
'''
# Owner, Nov 13: the log says whether Forge's own passing is in use, and why not when it isn't.
START_OLD = '    recordDiagnostic("Pass to my turn: started on turn ${snapshot.turnNumber}", DiagnosticCategory.ACTION)\n'
START_NEW = ('''    recordDiagnostic("Pass to my turn: started on turn ${snapshot.turnNumber} · Forge passes empty opponent windows: " +
        (if (!argentumOffline) "no (the engine is not on this phone)" else if (ChimeraPhaseStops.stops.any { it.startsWith("opp:") }) "no (an opponent-turn stop is set)" else "yes"), DiagnosticCategory.ACTION)
''')
STOP_OLD = '    if (passToMyTurnFrom != null) { passToMyTurnFrom = null; recordDiagnostic("Pass to my turn: stopped by player", DiagnosticCategory.ACTION); return }\n'
STOP_NEW = '    if (passToMyTurnFrom != null) { passToMyTurnFrom = null; argentumSocket.send("setPassToMyTurn", JSONObject().put("enabled", false)); recordDiagnostic("Pass to my turn: stopped by player", DiagnosticCategory.ACTION); return }\n'


def once(path, old, new, label):
    source = path.read_text(encoding="utf-8")
    if new in source:
        raise RuntimeError("fast-pass patch already applied")
    if source.count(old) != 1:
        raise RuntimeError(f"fast-pass {label} anchor found {source.count(old)} times, expected 1")
    path.write_text(source.replace(old, new), encoding="utf-8")


def patch(app: Path) -> None:
    once(app / (J + "TableEffects.kt"), PASS_OLD, PASS_NEW, "auto-pass")
    once(app / (J + "TableArgentum.kt"), STOP_OLD, STOP_NEW, "stop")
    once(app / (J + "TableArgentum.kt"), START_OLD, START_NEW, "start")
    print("Pass to my turn: Forge passes empty opponent windows itself")


if __name__ == "__main__":
    if len(sys.argv) != 2:
        raise SystemExit("usage: patch_fast_pass.py STAGED_ANDROID_APP")
    patch(Path(sys.argv[1]))
