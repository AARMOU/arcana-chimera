package com.arcanatable

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.dp

// ------------------------------------------------------------------ auto-pass stops on the phase bar (owner, Nov 9)

/**
 * Hold a phase on the phase bar to make "Pass to my turn" stop there, on your turn, your opponents' turns or both.
 * Kept on this phone. A phase with a stop shows a dot.
 */
internal object ChimeraPhaseStops {
    val PHASES = listOf("BEGIN", "MAIN 1", "COMBAT", "MAIN 2", "END")
    private val prefs: SharedPreferences? by lazy {
        runCatching {
            val app = Class.forName("android.app.ActivityThread").getMethod("currentApplication").invoke(null) as Context
            app.getSharedPreferences("chimera_phase_stops", Context.MODE_PRIVATE)
        }.getOrNull()
    }
    /** "mine:MAIN 1", "opp:COMBAT", ... */
    var stops by mutableStateOf(prefs?.getStringSet("stops", emptySet())?.toSet().orEmpty())
        private set
    /** The phase whose menu is open, or null. */
    var menuFor by mutableStateOf<String?>(null)
    /** The turn and step auto-pass started in (or last stopped at): no stop there. */
    @Volatile var skip: String? = null

    fun has(phase: String) = "mine:$phase" in stops || "opp:$phase" in stops
    fun set(key: String, on: Boolean) {
        stops = if (on) stops + key else stops - key
        prefs?.edit()?.putStringSet("stops", stops)?.apply()
    }

    fun phaseOf(step: String): String? = when (step.uppercase().replace(' ', '_')) {
        "UNTAP", "UPKEEP", "DRAW" -> "BEGIN"
        "PRECOMBAT_MAIN" -> "MAIN 1"
        "BEGIN_COMBAT", "DECLARE_ATTACKERS", "DECLARE_BLOCKERS", "FIRST_STRIKE_COMBAT_DAMAGE", "COMBAT_DAMAGE", "END_COMBAT" -> "COMBAT"
        "POSTCOMBAT_MAIN" -> "MAIN 2"
        "END", "END_STEP", "CLEANUP" -> "END"
        else -> null
    }

    fun key(turn: Int, step: String) = "$turn|${phaseOf(step)}"

    /** The stop auto-pass has reached ("your Main 1"), or null to keep passing. */
    fun hit(turn: Int, step: String, yourTurn: Boolean): String? {
        val phase = phaseOf(step) ?: return null
        val k = key(turn, step)
        if (k == skip) return null
        if ((if (yourTurn) "mine:" else "opp:") + phase !in stops) return null
        skip = k
        return (if (yourTurn) "your " else "opponent's ") + phase.lowercase().replaceFirstChar { it.uppercase() }
    }
}

/** On a phase of the phase bar: hold to set stops; a dot marks a phase that has one. */
internal fun Modifier.chimeraPhaseStop(phase: String): Modifier = composed {
    val haptic = LocalHapticFeedback.current
    pointerInput(phase) {
        detectTapGestures(onLongPress = { haptic.performHapticFeedback(HapticFeedbackType.LongPress); ChimeraPhaseStops.menuFor = phase })
    }.drawWithContent {
        drawContent()
        if (ChimeraPhaseStops.has(phase)) drawCircle(androidx.compose.ui.graphics.Color(0xFFE5534B), radius = 3.dp.toPx(),
            center = Offset(size.width - 5.dp.toPx(), 5.dp.toPx()))
    }
}

@Composable
internal fun ChimeraPhaseStopMenu() {
    val phase = ChimeraPhaseStops.menuFor ?: return
    AlertDialog(onDismissRequest = { ChimeraPhaseStops.menuFor = null },
        title = { Text("Auto-pass stops · ${phase.lowercase().replaceFirstChar { it.uppercase() }}") },
        text = {
            Column {
                Text("\"Pass to my turn\" stops here so you can act.", color = Muted)
                listOf("mine:$phase" to "Stop here on my turn", "opp:$phase" to "Stop here on opponents' turns").forEach { (key, label) ->
                    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable { ChimeraPhaseStops.set(key, key !in ChimeraPhaseStops.stops) },
                        verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(key in ChimeraPhaseStops.stops, { ChimeraPhaseStops.set(key, it) })
                        Text(label, modifier = Modifier.padding(start = 4.dp))
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { ChimeraPhaseStops.menuFor = null }) { Text("Done") } })
}

// ------------------------------------------------------------------ Concede (owner, Nov 9: replaces "Propose ending the game")

internal object ChimeraConcede {
    var asking by mutableStateOf(false)
}

@Composable
internal fun ChimeraConcedeDialog(onConcede: () -> Unit) {
    if (!ChimeraConcede.asking) return
    AlertDialog(onDismissRequest = { ChimeraConcede.asking = false },
        title = { Text("Concede this game?") },
        text = { Text("You lose this game. This can't be undone. You can stay and watch, or leave from the menu.") },
        confirmButton = { TextButton(onClick = { ChimeraConcede.asking = false; onConcede() }) { Text("Concede", color = androidx.compose.ui.graphics.Color(0xFFE9978B)) } },
        dismissButton = { TextButton(onClick = { ChimeraConcede.asking = false }) { Text("Cancel") } })
}
