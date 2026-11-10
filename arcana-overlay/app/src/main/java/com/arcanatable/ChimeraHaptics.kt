package com.arcanatable

import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import android.os.VibrationEffect
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Vibration (owner, Nov 10: "it doesn't seem like anything does" have haptic feedback). Android's touch-feedback haptic
 * is silent whenever the phone's touch vibration is off, so Chimera drives the vibrator directly:
 *  - your turn starts: one firm buzz
 *  - the game needs an answer from you, or you're being attacked: two quick taps
 *  - auto-pass starts or stops on its own, a card preview opens, a phase stop is set or removed: one short tick
 * Settings › Gameplay › Vibration: Off, Important only (the first two), All (default).
 */
internal object ChimeraHaptics {
    val LEVELS = listOf("off" to "Off", "important" to "Important only", "all" to "All")

    private val app: Context? by lazy {
        runCatching { Class.forName("android.app.ActivityThread").getMethod("currentApplication").invoke(null) as Context }.getOrNull()
    }
    private val prefs: SharedPreferences? by lazy { app?.getSharedPreferences("chimera_skin", Context.MODE_PRIVATE) }

    var level by mutableStateOf(prefs?.getString("vibration", "all") ?: "all")
        private set
    fun changeLevel(v: String) { level = v; prefs?.edit()?.putString("vibration", v)?.apply() }

    private fun play(effect: () -> VibrationEffect) {
        runCatching {
            val ctx = app ?: return
            val vibrator = if (Build.VERSION.SDK_INT >= 31)
                (ctx.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as android.os.VibratorManager).defaultVibrator
            else @Suppress("DEPRECATION") (ctx.getSystemService(Context.VIBRATOR_SERVICE) as android.os.Vibrator)
            if (vibrator.hasVibrator()) vibrator.vibrate(effect())
        }
    }

    fun turnStart() { if (level != "off" && appInForeground) play { VibrationEffect.createOneShot(70, VibrationEffect.DEFAULT_AMPLITUDE) } }

    fun attention() {
        if (level != "off" && appInForeground) play { VibrationEffect.createWaveform(longArrayOf(0, 35, 90, 35), -1) }
    }

    fun tick() {
        if (level != "all") return
        play {
            if (Build.VERSION.SDK_INT >= 29) VibrationEffect.createPredefined(VibrationEffect.EFFECT_TICK)
            else VibrationEffect.createOneShot(15, VibrationEffect.DEFAULT_AMPLITUDE)
        }
    }
}

/** Watches the game for the moments that vibrate: a new question for you, an attack on you, auto-pass, card previews. */
@Composable
internal fun ChimeraHapticWatch(decisionKey: String?, blocking: Boolean, mulligan: Boolean, autoPassing: Boolean, previewing: Boolean, myTurn: Boolean) {
    // Questions you caused yourself (targets for your own spell) don't buzz: only ones that arrive on another player's
    // turn or while auto-pass is running, when you may not be looking.
    LaunchedEffect(decisionKey) { if (decisionKey != null && (!myTurn || autoPassing)) ChimeraHaptics.attention() }
    LaunchedEffect(blocking) { if (blocking) ChimeraHaptics.attention() }
    LaunchedEffect(mulligan) { if (mulligan) ChimeraHaptics.attention() }
    val first = remember { mutableStateOf(true) }
    LaunchedEffect(autoPassing) { if (first.value) first.value = false else ChimeraHaptics.tick() }
    LaunchedEffect(previewing) { if (previewing) ChimeraHaptics.tick() }
}

/** Settings › Gameplay: the Vibration choice. */
@Composable
internal fun ChimeraVibrationSetting() {
    Column(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Text("Vibration", color = SoftWhite, fontSize = 12.sp, fontWeight = FontWeight.Bold)
        Text("Your turn, questions and attacks; All adds short ticks for auto-pass, previews and phase stops.", color = Muted, fontSize = 10.sp)
        Row(Modifier.fillMaxWidth().padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            ChimeraHaptics.LEVELS.forEach { (id, label) ->
                val on = ChimeraHaptics.level == id
                Text(label, color = if (on) Ink else SoftWhite, fontSize = 12.sp, fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f).clip(RoundedCornerShape(10.dp)).background(if (on) Mint else PanelLight)
                        .clickable { ChimeraHaptics.changeLevel(id); if (id != "off") ChimeraHaptics.turnStart() }
                        .heightIn(min = 44.dp).padding(horizontal = 8.dp, vertical = 12.dp).testTag("chimera-vibration-$id"))
            }
        }
    }
}
