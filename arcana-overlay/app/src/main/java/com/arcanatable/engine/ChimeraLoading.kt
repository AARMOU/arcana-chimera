package com.arcanatable.engine

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * The game-loading screen's progress (owner, Nov 9: "Your game is loading, please wait" with a status bar).
 * The bridge reports its real start-up steps: unpacking Forge (first start after an install), loading the card
 * rules, building each deck, starting the game, dealing. A guest's phone has no steps to show: it gets a moving bar.
 */
object ChimeraLoading {
    var fraction by mutableFloatStateOf(0f)
        private set
    var label by mutableStateOf("")
        private set
    /** True on the phone running the game (the host or a solo game); a guest only waits. */
    var hosting by mutableStateOf(false)

    fun report(f: Float, text: String) {
        fraction = if (f <= 0f) 0f else maxOf(fraction, f.coerceIn(0f, 1f)) // never moves backwards within a start
        label = text
    }

    fun reset() { fraction = 0f; label = ""; hosting = false }
}

@Composable
fun ChimeraLoadingBar(barColor: Color, trackColor: Color, textColor: Color) {
    Column(Modifier.fillMaxWidth().padding(top = 18.dp)) {
        if (ChimeraLoading.hosting) {
            val shown by animateFloatAsState(ChimeraLoading.fraction, tween(350), label = "loading")
            Box(Modifier.fillMaxWidth().height(10.dp).clip(RoundedCornerShape(5.dp)).background(trackColor)) {
                Box(Modifier.fillMaxWidth(shown.coerceIn(0.02f, 1f)).fillMaxHeight().clip(RoundedCornerShape(5.dp)).background(barColor))
            }
            Text(
                ChimeraLoading.label.ifBlank { "Getting ready…" } + "  ·  ${(ChimeraLoading.fraction * 100).toInt()}%",
                color = textColor, fontSize = 14.sp, modifier = Modifier.padding(top = 10.dp)
            )
        } else {
            LinearProgressIndicator(Modifier.fillMaxWidth().height(10.dp).clip(RoundedCornerShape(5.dp)), color = barColor, trackColor = trackColor)
            Text("Waiting for the host's phone to start the game…", color = textColor, fontSize = 14.sp, modifier = Modifier.padding(top = 10.dp))
        }
    }
}
