#!/usr/bin/env python3
"""Chimera-only: "ACCEPT ALL ON STACK" for repeated target prompts (owner, Nov 12-13).

A trigger storm (60 identical "Choose targets for Wrathful Raptors") asked the same question every time.
When the same target prompt comes up again, the prompt now offers ACCEPT ALL ON STACK: it confirms the
current choice and answers later identical prompts the same way, for as long as every chosen target is
still legal. It stops on a different target prompt, an illegal target, or a new turn.

Owner, Nov 13: "accept all should accept all". The same offer now covers two more repeated prompts:
- "Choose a mode / option" prompts (Sauron, Dino Devotee): repeat the option picked last time, this turn.
- Combat damage assignment (15 in one combat): accept the suggested split for every remaining attacker this turn.
  Each is still checked; a prompt whose suggested split is not complete is shown as usual.

Fail-closed: every anchor must match exactly once. Apply to an ephemeral staged Arcana donor, NEVER to arcana_table.
"""
from pathlib import Path
import sys

J = "src/main/java/com/arcanatable/"

STATE_OLD = "    var argentumAutoYes by run { mutableStateOf(emptyMap<String, Int>()) }\n"
STATE_NEW = STATE_OLD + (
    "    /** Chimera: the last target prompt answered, and the answer to repeat (prompt, targets, turn). */\n"
    "    var argentumLastTargetPrompt by run { mutableStateOf(\"\") }\n"
    "    var argentumSameTargets by run { mutableStateOf<Triple<String, JSONObject, Int>?>(null) }\n"
    "    /** Chimera: the last option prompt answered (prompt, option text), and the option to repeat (prompt, option, turn). */\n"
    "    var argentumLastOption by run { mutableStateOf(\"\" to \"\") }\n"
    "    var argentumSameOption by run { mutableStateOf<Triple<String, String, Int>?>(null) }\n"
    "    /** Chimera: the turn a damage split was last confirmed by hand, and the turn suggested splits are accepted for. */\n"
    "    var argentumLastDamageTurn by run { mutableStateOf(-1) }\n"
    "    var argentumAcceptDamageTurn by run { mutableStateOf(-1) }\n")

CONFIRM_OLD = ('                        submitArgentumDecision(JSONObject().put("type", "TargetsResponse").put("selectedTargets", targets))\n'
               '                        argentumDecisionTargets = emptyMap()\n')
CONFIRM_NEW = ('                        argentumLastTargetPrompt = sameTargetsKey(decision)\n' + CONFIRM_OLD)

BODY_OLD = ('                        "ChooseTargetsDecision" -> {\n'
            '                            val requirements = decision.optJSONArray("targetRequirements") ?: JSONArray()\n'
            '                            val legalTargets = decision.optJSONObject("legalTargets")\n')
BODY_NEW = BODY_OLD + '''                            // Chimera: the same target prompt again, so offer to answer the rest the same way.
                            if (argentumLastTargetPrompt.isNotEmpty() && argentumLastTargetPrompt == sameTargetsKey(decision)) {
                                val chosenAll = (0 until requirements.length()).all { position ->
                                    val requirement = requirements.optJSONObject(position) ?: return@all false
                                    val count = argentumDecisionTargets[requirement.optInt("index", position)].orEmpty().size
                                    count in requirement.optInt("minTargets", 0)..requirement.optInt("maxTargets", 1)
                                }
                                androidx.compose.material3.TextButton(enabled = chosenAll, onClick = {
                                    val targets = JSONObject()
                                    for (position in 0 until requirements.length()) {
                                        val requirement = requirements.optJSONObject(position) ?: continue
                                        val index = requirement.optInt("index", position)
                                        targets.put(index.toString(), JSONArray(argentumDecisionTargets[index].orEmpty()))
                                    }
                                    argentumSameTargets = Triple(sameTargetsKey(decision), targets, argentumSnapshot?.turnNumber ?: 0)
                                    recordDiagnostic("Accept all on stack: ${sameTargetsKey(decision)}", DiagnosticCategory.ACTION)
                                    submitArgentumDecision(JSONObject().put("type", "TargetsResponse").put("selectedTargets", targets))
                                    argentumDecisionTargets = emptyMap()
                                }) { androidx.compose.material3.Text("ACCEPT ALL ON STACK", color = if (chosenAll) Mint else Muted) }
                            }
'''

OPTION_CLICK_OLD = '                                    submitArgentumDecision(JSONObject().put("type", "OptionChosenResponse").put("optionIndex", index))\n'
OPTION_CLICK_NEW = '                                    argentumLastOption = sameTargetsKey(decision) to option\n' + OPTION_CLICK_OLD

OPTION_BODY_OLD = '                        "ChooseOptionDecision" -> (decision.optJSONArray("options") ?: JSONArray()).let { options ->\n'
OPTION_BODY_NEW = OPTION_BODY_OLD + '''                            // Chimera: the same option prompt again, so offer to repeat the last pick for the rest of the turn.
                            val lastOption = argentumLastOption
                            val repeatIndex = if (lastOption.first.isNotEmpty() && lastOption.first == sameTargetsKey(decision))
                                (0 until options.length()).firstOrNull { options.optString(it) == lastOption.second } else null
                            if (repeatIndex != null) androidx.compose.material3.TextButton(onClick = {
                                argentumSameOption = Triple(lastOption.first, lastOption.second, argentumSnapshot?.turnNumber ?: 0)
                                recordDiagnostic("Accept all on stack: ${lastOption.first} = ${lastOption.second}", DiagnosticCategory.ACTION)
                                submitArgentumDecision(JSONObject().put("type", "OptionChosenResponse").put("optionIndex", repeatIndex))
                            }, modifier = Modifier.fillMaxWidth()) { androidx.compose.material3.Text("ACCEPT ALL ON STACK · ${lastOption.second}", color = Mint) }
'''

DAMAGE_CLICK_OLD = '                        submitArgentumDecision(ArgentumDecisions.damageAssignmentResponse(argentumAmounts.filterValues { it > 0 }))\n'
DAMAGE_CLICK_NEW = '                        argentumLastDamageTurn = argentumSnapshot?.turnNumber ?: 0\n' + DAMAGE_CLICK_OLD

DAMAGE_BODY_OLD = '                        "AssignDamageDecision" -> {\n'
DAMAGE_BODY_NEW = DAMAGE_BODY_OLD + '''                            // Chimera: another damage split this turn, so offer to accept the suggested split for the rest.
                            if (argentumLastDamageTurn == (argentumSnapshot?.turnNumber ?: 0)) {
                                val suggested = ArgentumDecisions.defaultDamageAssignments(decision)
                                val usable = ArgentumDecisions.damageAssignmentComplete(decision, suggested)
                                androidx.compose.material3.TextButton(enabled = usable, onClick = {
                                    argentumAcceptDamageTurn = argentumSnapshot?.turnNumber ?: 0
                                    recordDiagnostic("Accept all on stack: suggested combat damage for the rest of this turn", DiagnosticCategory.ACTION)
                                    submitArgentumDecision(ArgentumDecisions.damageAssignmentResponse(suggested.filterValues { it > 0 }))
                                }) { androidx.compose.material3.Text("ACCEPT ALL ON STACK · SUGGESTED DAMAGE", color = if (usable) Mint else Muted) }
                            }
'''

EFFECT_OLD = '    // "Use 5 / Use all": activate the same ability on one copy after another, from each new state.\n'
EFFECT_NEW = '''    // Chimera "Accept all on stack": answer the same target prompt the same way while the targets stay legal.
    LaunchedEffect(argentumPendingDecision?.optString("id"), argentumAwaitingUpdate) {
        val decision = argentumPendingDecision ?: return@LaunchedEffect
        val same = argentumSameTargets ?: return@LaunchedEffect
        if (argentumAwaitingUpdate) return@LaunchedEffect
        if ((argentumSnapshot?.turnNumber ?: 0) != same.third) { argentumSameTargets = null; return@LaunchedEffect }
        if (decision.optString("type") != "ChooseTargetsDecision") return@LaunchedEffect
        if (sameTargetsKey(decision) != same.first || !sameTargetsStillLegal(decision, same.second)) { argentumSameTargets = null; return@LaunchedEffect }
        delay(250)
        recordDiagnostic("Auto-answered targets (accept all on stack): ${same.first}", DiagnosticCategory.ACTION)
        submitArgentumDecision(JSONObject().put("type", "TargetsResponse").put("selectedTargets", same.second))
        argentumDecisionTargets = emptyMap()
    }
    // Chimera "Accept all on stack" for a repeated option prompt and for suggested combat damage, this turn only.
    LaunchedEffect(argentumPendingDecision?.optString("id"), argentumAwaitingUpdate) {
        val decision = argentumPendingDecision ?: return@LaunchedEffect
        if (argentumAwaitingUpdate) return@LaunchedEffect
        val turn = argentumSnapshot?.turnNumber ?: 0
        when (decision.optString("type")) {
            "ChooseOptionDecision" -> {
                val same = argentumSameOption ?: return@LaunchedEffect
                val options = decision.optJSONArray("options") ?: JSONArray()
                val index = (0 until options.length()).firstOrNull { options.optString(it) == same.second }
                if (turn != same.third || sameTargetsKey(decision) != same.first || index == null) { argentumSameOption = null; return@LaunchedEffect }
                delay(250)
                recordDiagnostic("Auto-answered option (accept all on stack): ${same.first} = ${same.second}", DiagnosticCategory.ACTION)
                submitArgentumDecision(JSONObject().put("type", "OptionChosenResponse").put("optionIndex", index))
            }
            "AssignDamageDecision" -> {
                if (argentumAcceptDamageTurn != turn) return@LaunchedEffect
                val suggested = ArgentumDecisions.defaultDamageAssignments(decision)
                if (!ArgentumDecisions.damageAssignmentComplete(decision, suggested)) return@LaunchedEffect
                delay(250)
                recordDiagnostic("Auto-assigned suggested combat damage (accept all on stack)", DiagnosticCategory.ACTION)
                submitArgentumDecision(ArgentumDecisions.damageAssignmentResponse(suggested.filterValues { it > 0 }))
            }
        }
    }
''' + EFFECT_OLD

HELPERS = '''

/** Chimera: identical target prompts share this key (the prompt's first line names the source card). */
internal fun sameTargetsKey(decision: JSONObject): String = decision.optString("prompt").lineSequence().firstOrNull().orEmpty().trim()

/** Chimera: every remembered target must still be offered for the same requirement, or the player is asked again. */
internal fun sameTargetsStillLegal(decision: JSONObject, targets: JSONObject): Boolean {
    val legal = decision.optJSONObject("legalTargets") ?: return false
    val requirements = decision.optJSONArray("targetRequirements") ?: return false
    if (requirements.length() != targets.length()) return false
    for (position in 0 until requirements.length()) {
        val requirement = requirements.optJSONObject(position) ?: return false
        if (requirement.optJSONObject("legalTargetsByPrefix") != null) return false // depends on earlier picks: always ask
        val index = requirement.optInt("index", position).toString()
        val chosen = targets.optJSONArray(index) ?: return false
        val offered = legal.optJSONArray(index) ?: return false
        val offeredIds = (0 until offered.length()).map { offered.optString(it) }.toSet()
        if (chosen.length() !in requirement.optInt("minTargets", 0)..requirement.optInt("maxTargets", 1)) return false
        for (i in 0 until chosen.length()) if (chosen.optString(i) !in offeredIds) return false
    }
    return true
}
'''


def once(source, old, new, label):
    if source.count(old) != 1:
        raise RuntimeError(f"accept-all {label} anchor found {source.count(old)} times, expected 1")
    return source.replace(old, new)


def patch(app: Path) -> None:
    controller, overlays, effects, helpers = (app / (J + n) for n in ("TableController.kt", "TableOverlays.kt", "TableEffects.kt", "TableHelpers.kt"))
    c = controller.read_text(encoding="utf-8")
    if "argentumSameTargets" in c:
        raise RuntimeError("accept-all patch already applied")
    controller.write_text(once(c, STATE_OLD, STATE_NEW, "state"), encoding="utf-8")
    o = overlays.read_text(encoding="utf-8")
    o = once(o, CONFIRM_OLD, CONFIRM_NEW, "confirm")
    o = once(o, BODY_OLD, BODY_NEW, "prompt body")
    o = once(o, OPTION_CLICK_OLD, OPTION_CLICK_NEW, "option click")
    o = once(o, OPTION_BODY_OLD, OPTION_BODY_NEW, "option body")
    o = once(o, DAMAGE_CLICK_OLD, DAMAGE_CLICK_NEW, "damage click")
    overlays.write_text(once(o, DAMAGE_BODY_OLD, DAMAGE_BODY_NEW, "damage body"), encoding="utf-8")
    effects.write_text(once(effects.read_text(encoding="utf-8"), EFFECT_OLD, EFFECT_NEW, "effect"), encoding="utf-8")
    helpers.write_text(helpers.read_text(encoding="utf-8").rstrip("\n") + "\n" + HELPERS, encoding="utf-8")
    print("Target prompts: ACCEPT ALL ON STACK on a repeated prompt")


if __name__ == "__main__":
    if len(sys.argv) != 2:
        raise SystemExit("usage: patch_accept_all_targets.py STAGED_ANDROID_APP")
    patch(Path(sys.argv[1]))
