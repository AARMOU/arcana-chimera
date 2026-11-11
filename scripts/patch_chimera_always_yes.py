#!/usr/bin/env python3
"""Chimera-only: the yes/no screen's ALWAYS button uses Forge's own "always accept this trigger".

Owner, Nov 11 2026: "YES · ALWAYS THIS TURN" did nothing for Burgeoning and other cards. Arcana remembered the exact
prompt for the current turn only; Burgeoning triggers on opponents' turns and its prompt names the land that
triggered it, so the remembered answer never matched. For an optional trigger the Forge bridge now marks the decision
`alwaysAvailable`; the button sends `always: true` and Forge stops asking for that card's trigger this game.
Other yes/no questions keep Arcana's this-turn behaviour.

Apply to an ephemeral staged Arcana donor, NEVER to the arcana_table repository.
"""
from pathlib import Path
import sys

OLD = '''StudioTextButton("TableOverlays.TextButton.14", "Dialogs and overlays", onClick = {
                            argentumAutoYes = argentumAutoYes + (autoYesKey(decision) to (argentumSnapshot?.turnNumber ?: 0))
                            submitArgentumDecision(JSONObject().put("type", "YesNoResponse").put("choice", true))
                        }) { StudioText("TableOverlays.Text.58", "Dialogs and overlays", true, "${decision.decisionDisplayText("yesText", "YES").uppercase()} · ALWAYS THIS TURN", fontSize = 12.sp) }'''

NEW = '''StudioTextButton("TableOverlays.TextButton.14", "Dialogs and overlays", onClick = {
                            if (decision.optBoolean("alwaysAvailable")) { // Chimera: Forge remembers it for this card, this game
                                recordDiagnostic("Always yes for this card's trigger: ${decision.optString("prompt").lineSequence().first()}", DiagnosticCategory.ACTION)
                                submitArgentumDecision(JSONObject().put("type", "YesNoResponse").put("choice", true).put("always", true))
                            } else {
                                argentumAutoYes = argentumAutoYes + (autoYesKey(decision) to (argentumSnapshot?.turnNumber ?: 0))
                                submitArgentumDecision(JSONObject().put("type", "YesNoResponse").put("choice", true))
                            }
                        }) { StudioText("TableOverlays.Text.58", "Dialogs and overlays", true, "${decision.decisionDisplayText("yesText", "YES").uppercase()} · " + (if (decision.optBoolean("alwaysAvailable")) "ALWAYS THIS GAME" else "ALWAYS THIS TURN"), fontSize = 12.sp) }'''


def patch(app: Path) -> None:
    f = app / "src/main/java/com/arcanatable/TableOverlays.kt"
    source = f.read_text(encoding="utf-8")
    if "alwaysAvailable" in source:
        raise RuntimeError("always-yes patch already applied")
    if source.count(OLD) != 1:
        raise RuntimeError("always-yes anchor missing in TableOverlays.kt")
    f.write_text(source.replace(OLD, NEW), encoding="utf-8")
    print("ALWAYS on trigger questions uses Forge's always-accept")


if __name__ == "__main__":
    if len(sys.argv) != 2:
        raise SystemExit("usage: patch_chimera_always_yes.py STAGED_ANDROID_APP")
    patch(Path(sys.argv[1]))
