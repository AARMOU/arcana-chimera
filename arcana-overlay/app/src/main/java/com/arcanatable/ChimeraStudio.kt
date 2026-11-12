package com.arcanatable

import android.content.SharedPreferences
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

// ------------------------------------------------------------------ one hand design for every orientation (owner, Nov 9)

internal object ChimeraOrientation {
    const val SEPARATE = "chimera_separate_landscape_hand"

    /** The orientation whose hand design is used: always the shared one unless "Separate landscape design" is on. */
    fun handKey(prefs: SharedPreferences, orientation: String): String {
        if (prefs.getBoolean(SEPARATE, false)) return orientation
        val hasPortrait = prefs.all.keys.any { it.startsWith("studio_native_v1_hand_portrait") || it == "studio_active_v1_portrait" || it == "studio_profiles_v1_portrait" }
        val hasLandscape = prefs.all.keys.any { it.startsWith("studio_native_v1_hand_landscape") }
        return if (!hasPortrait && hasLandscape) "landscape" else "portrait"
    }
}

// ------------------------------------------------------------------ designs adapt to the screen (owner, Nov 9, items 3 and 10)

internal object ChimeraAdapt {
    const val MIN = 0.8f
    const val MAX = 1.25f

    private val prefs: SharedPreferences? by lazy {
        runCatching {
            val app = Class.forName("android.app.ActivityThread").getMethod("currentApplication").invoke(null) as android.content.Context
            app.getSharedPreferences("chimera_skin", android.content.Context.MODE_PRIVATE)
        }.getOrNull()
    }

    /** Studio switch (owner, Nov 9): on short, wide screens the button rows move into a column beside the cards. */
    var sideColumn by mutableStateOf(prefs?.getBoolean("side_column", true) ?: true)
        private set
    fun changeSideColumn(on: Boolean) { sideColumn = on; prefs?.edit()?.putBoolean("side_column", on)?.apply() }

    /** Heights and text grow or shrink at most 25%; widths and positions follow the screen. */
    fun scale(raw: Float): Float = raw.coerceIn(MIN, MAX)

    /** Spreads a design across a wider (or narrower) screen: positions and button widths by [sx], cards keep their shape. */
    fun spread(e: StudioElement, sx: Float): StudioElement =
        if (sx in 0.99f..1.01f) e
        else if (e.kind == "card") e.copy(x = e.x * sx + (e.w * sx - e.w) / 2f)
        else e.copy(x = e.x * sx, w = e.w * sx)

    /**
     * A short, wide drawer (landscape): the button rows move into a column beside the cards, on the side Pass is on,
     * so the cards keep their height. Everything in design units; [width] is the canvas width after [spread].
     */
    fun columnize(elements: List<StudioElement>, width: Float, available: Float, origin: Float): List<StudioElement> {
        val buttons = elements.filter { it.kind in setOf("button", "metric") && it.visible }
        val cards = elements.filter { it.kind == "card" }
        if (buttons.isEmpty() || cards.isEmpty() || width < available * 1.6f) return elements
        val top = elements.filter { it.kind in setOf("status", "icon") }
        val stripBottom = (top.maxOfOrNull { it.y + it.h } ?: origin)
        val rowsHeight = buttons.maxOf { it.y + it.h } - buttons.minOf { it.y }
        if (rowsHeight < available * 0.35f) return elements // rows already take little height: keep them
        val passRight = buttons.firstOrNull { it.binding == "pass" }?.let { it.x + it.w / 2f > width / 2f } ?: false
        val colW = (width * 0.26f).coerceIn(120f, 260f)
        val gap = 6f
        val room = (available - (stripBottom - origin) - 8f).coerceAtLeast(120f)
        val order = buttons.sortedWith(compareBy({ if (it.binding == "pass") 0 else 1 }, { it.y }, { it.x }))
        val h = min(48f, (room - gap * (order.size - 1)) / order.size).coerceAtLeast(40f)
        val perCol = max(1, ((room + gap) / (h + gap)).toInt())
        val cols = (order.size + perCol - 1) / perCol
        val colX0 = if (passRight) width - 12f - cols * colW - (cols - 1) * gap else 12f
        val placed = order.mapIndexed { i, b ->
            val c = i / perCol; val r = i % perCol
            b.copy(x = colX0 + c * (colW + gap), y = stripBottom + 6f + r * (h + gap), w = colW, h = h)
        }
        val cardLeft = if (passRight) 12f else colX0 + cols * (colW + gap) + 6f
        val cardRight = if (passRight) colX0 - 12f else width - 12f
        val template = cards.minBy { it.binding.removePrefix("card").toIntOrNull() ?: 99 }
        val laid = cards.filter { !it.id.startsWith("overflow") }.mapIndexed { i, c ->
            c.copy(x = if (i == 0) cardLeft else max(cardLeft, cardRight - template.w), y = stripBottom + 6f, w = template.w, h = template.h)
        }
        val hidden = elements.filter { it.kind in setOf("button", "metric") && !it.visible }
        return elements.filter { it.kind !in setOf("button", "metric", "card") } + hidden + placed + laid
    }
}

// ------------------------------------------------------------------ the hand editor (owner, Nov 9, items 8 and 9)

internal object ChimeraStudioUi {
    /** True while something is being dragged or resized in the editor: Studio's windows fade so the canvas shows. */
    var dragging by mutableStateOf(false)
    /** Where the inspector was dragged to. */
    var offset by mutableStateOf(Offset.Zero)

    @Composable
    fun bodyMax(): Dp = (LocalConfiguration.current.screenHeightDp * 0.22f).dp.coerceIn(140.dp, 260.dp)

    /** The card slots move and resize together as one cards area. */
    fun editCards(runtime: StudioControlsRuntime, layout: StudioLayout, sel: StudioElement, r: FloatArray) {
        val kx = r[2] / sel.w.coerceAtLeast(1f); val ky = r[3] / sel.h.coerceAtLeast(1f)
        val ids = layout.elements.filter { it.kind == "card" }.map { it.id }.toSet()
        StudioHandEditing.editMany(runtime, layout, ids) {
            it.copy(x = r[0] + (it.x - sel.x) * kx, y = r[1] + (it.y - sel.y) * ky, w = (it.w * kx).coerceAtLeast(24f), h = (it.h * ky).coerceAtLeast(24f))
        }
    }
}

/** A drag handle above Studio's inspector: drag it anywhere; double-tap puts it back. */
@Composable
internal fun ChimeraInspectorHandle() {
    Box(Modifier.fillMaxWidth().height(22.dp).pointerInput(Unit) {
        detectDragGestures(onDrag = { change, amount -> change.consume(); ChimeraStudioUi.offset += amount })
    }, contentAlignment = Alignment.Center) {
        Box(Modifier.size(48.dp, 5.dp).background(androidx.compose.ui.graphics.Color(0xAAFFFFFF), RoundedCornerShape(3.dp)))
    }
}

internal fun Modifier.chimeraInspectorPlacement(): Modifier =
    this.offset { IntOffset(ChimeraStudioUi.offset.x.roundToInt(), ChimeraStudioUi.offset.y.roundToInt()) }
        .graphicsLayer { alpha = if (ChimeraStudioUi.dragging) 0.2f else 1f }
