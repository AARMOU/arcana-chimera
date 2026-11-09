package com.arcanatable

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.setValue
import kotlin.math.roundToInt

/**
 * Hand drawer opacity (owner, Nov 9: a way to change it, 0-100%). Only the drawer's background fades (and the shade
 * Arcana puts over the table behind it, with it); cards and buttons stay solid. Kept on this phone; set in
 * Arcana Studio → Hand, under the hand palette.
 */
object ChimeraDrawer {
    private const val KEY = "chimera_drawer_opacity"

    private val prefs: SharedPreferences? by lazy {
        runCatching {
            val app = Class.forName("android.app.ActivityThread").getMethod("currentApplication").invoke(null) as Context
            app.getSharedPreferences("chimera_drawer", Context.MODE_PRIVATE)
        }.getOrNull()
    }

    var opacity by mutableFloatStateOf(prefs?.getFloat(KEY, 1f)?.coerceIn(0f, 1f) ?: 1f)
        private set

    fun set(value: Float) {
        opacity = value.coerceIn(0f, 1f)
        prefs?.edit()?.putFloat(KEY, opacity)?.apply()
    }

    /** One step of 10% up or down. */
    fun step(direction: Int) = set(((opacity * 10f).roundToInt() + direction) / 10f)

    val label: String get() = "${(opacity * 100f).roundToInt()}%"
}
