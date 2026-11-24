package com.arcanatable

import android.content.SharedPreferences
import android.content.Context
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.heightIn
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.composed
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.findRootCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.toSize
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

/**
 * A drag handle above Studio's panels: drag it to move the panel, double-tap to put it back (owner, Nov 19: a panel
 * dragged down to "minimise" it vanished for good). The panel's top row always stays on screen, and every Studio
 * session starts with panels where they dock.
 */
@Composable
internal fun ChimeraInspectorHandle() {
    Box(Modifier.fillMaxWidth().height(28.dp)
        .pointerInput(Unit) { detectTapGestures(onDoubleTap = { ChimeraStudioUi.offset = Offset.Zero }) }
        .pointerInput(Unit) {
            detectDragGestures(onDrag = { change, amount -> change.consume(); ChimeraStudioUi.offset = ChimeraPanelBounds.clamp(ChimeraStudioUi.offset + amount) })
        }.semantics { contentDescription = "Drag to move this panel. Double-tap to put it back." }, contentAlignment = Alignment.Center) {
        Box(Modifier.size(48.dp, 5.dp).background(androidx.compose.ui.graphics.Color(0xAAFFFFFF), RoundedCornerShape(3.dp)))
    }
}

/** Where a Studio panel docks and how far it may be dragged: its top row never leaves the screen. */
internal object ChimeraPanelBounds {
    /** The panel where it docks, before any drag, in root pixels. */
    var base by mutableStateOf(Rect.Zero)
    var root by mutableStateOf(Size.Zero)
    /** Pixels of the panel that stay on screen at the right, left and bottom edges. */
    var keep = 160f

    fun clamp(o: Offset): Offset {
        if (root.width <= 0f || base.width <= 0f) return o
        val minX = -base.left - (base.width - keep).coerceAtLeast(0f)
        val maxX = root.width - base.left - keep
        val minY = -base.top
        val maxY = root.height - base.top - keep
        return Offset(o.x.coerceIn(min(minX, maxX), max(minX, maxX)), o.y.coerceIn(min(minY, maxY), max(minY, maxY)))
    }
}

internal fun Modifier.chimeraInspectorPlacement(): Modifier = composed {
    val keep = with(LocalDensity.current) { 72.dp.toPx() }
    SideEffect { ChimeraPanelBounds.keep = keep }
    // Measured before the offset, so this is where the panel docks; the drag is clamped against it every frame,
    // which also pulls a panel back after the screen turns or folds.
    this.onGloballyPositioned { c -> ChimeraPanelBounds.base = c.boundsInRoot(); ChimeraPanelBounds.root = c.findRootCoordinates().size.toSize() }
        .offset { ChimeraPanelBounds.clamp(ChimeraStudioUi.offset).let { IntOffset(it.x.roundToInt(), it.y.roundToInt()) } }
        .graphicsLayer { alpha = if (ChimeraStudioUi.dragging) 0.2f else 1f }
}

// ------------------------------------------------------------------ one save rule (owner, Nov 19)

/**
 * Studio's settings that save the moment you tap them (presets' card and button sizes, screen layouts, drawer
 * opacity, typeface, the home picture and the wide-screen button column) are now kept only when you tap APPLY,
 * like every other Studio change. Leaving or discarding puts them back.
 */
internal object ChimeraStudioSession {
    private data class Snapshot(val before: ChimeraPresets.Before, val look: StudioLook?, val homeArt: String, val sideColumn: Boolean)
    private var snapshot: Snapshot? = null
    private var host: StudioLookHost? = null
    private var prefs: SharedPreferences? = null

    private fun current(): Snapshot? {
        val p = prefs ?: return null
        return Snapshot(ChimeraPresets.Before(null, ChimeraDrawer.opacity), host?.read(), ChimeraHomeArt.mode(p), ChimeraAdapt.sideColumn)
    }

    fun begin(context: Context, look: StudioLookHost?) {
        prefs = context.getSharedPreferences("arcana_options", Context.MODE_PRIVATE)
        host = look
        snapshot = current()
        ChimeraStudioUi.offset = Offset.Zero
        StudioLog.add("Studio session started")
    }

    /** True when an instant setting differs from how it was when Studio opened. */
    fun dirty(): Boolean = snapshot?.let { it != current() } == true

    fun commit() { if (dirty()) StudioLog.add("Applied: looks, typeface, home picture and drawer settings kept"); snapshot = null }

    fun revert() {
        val s = snapshot ?: return
        if (s != current()) StudioLog.add("Left without applying: looks, typeface, home picture and drawer settings put back")
        ChimeraPresets.restore(s.before)
        s.look?.let { host?.write(it) }
        prefs?.let { ChimeraHomeArt.set(it, s.homeArt) }
        if (ChimeraAdapt.sideColumn != s.sideColumn) ChimeraAdapt.changeSideColumn(s.sideColumn)
        snapshot = null
    }
}

// ------------------------------------------------------------------ app-wide looks, out of the Table panel (owner, Nov 19)

/** Home deck picture, typeface and Terminal settings: they change the whole app, so they live at the top of Studio options. */
@Composable
internal fun ChimeraAppLookSection() {
    val context = androidx.compose.ui.platform.LocalContext.current
    val homePrefs = remember { context.getSharedPreferences("arcana_options", Context.MODE_PRIVATE) }
    Text("Whole app", color = StudioEditorRequests.chromeGold, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 10.dp, bottom = 2.dp))
    Text("Previewed now; kept when you tap APPLY.", color = darkTableColors.muted, fontSize = 11.sp)
    Text("Home deck picture crop", color = darkTableColors.text, fontSize = 13.sp, modifier = Modifier.padding(top = 6.dp))
    Row(Modifier.hintedHorizontalScroll(), verticalAlignment = Alignment.CenterVertically) {
        ChimeraHomeArt.ALL.forEach { (id, label) ->
            StudioChip(label, true, "chimera-home-art-$id", selected = ChimeraHomeArt.mode(homePrefs) == id) { ChimeraHomeArt.set(homePrefs, id) }
        }
    }
    ChimeraOpeningHandLook() // owner, Nov 23: Catalogue (default) or Grid
    Text("Typeface", color = darkTableColors.text, fontSize = 13.sp, modifier = Modifier.padding(top = 6.dp))
    Row(Modifier.hintedHorizontalScroll(), verticalAlignment = Alignment.CenterVertically) {
        listOf("" to "Arcana", "serif" to "Serif", "mono" to "Mono", "condensed" to "Condensed").forEach { (id, label) ->
            StudioChip(label, true, "chimera-font-${id.ifEmpty { "arcana" }}", selected = ChimeraSkins.classicFontName == id) { ChimeraSkins.setClassicFont(id) }
        }
    }
    Text("Terminal layout", color = darkTableColors.text, fontSize = 13.sp, modifier = Modifier.padding(top = 6.dp))
    Text("Its colours, text size, scanlines and glow. These save as you change them.", color = darkTableColors.muted, fontSize = 11.sp)
    Row(verticalAlignment = Alignment.CenterVertically) { ChimeraTerminalStudioChip() }
}

// ------------------------------------------------------------------ Looks gallery (owner, Nov 19: presets first)

/** Presets and screen layouts as cards with their descriptions, so each look says what it does before you try it. */
@Composable
internal fun ChimeraLookCards(presets: List<StudioPreset>, trying: String?, onTry: (StudioPreset) -> Unit) {
    presets.forEach { preset ->
        val on = preset.name == trying
        androidx.compose.foundation.layout.Column(Modifier.fillMaxWidth().padding(vertical = 3.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(if (on) StudioEditorRequests.chromeGold.copy(alpha = 0.18f) else darkTableColors.raised)
            .clickable { onTry(preset) }.heightIn(min = 48.dp).padding(horizontal = 12.dp, vertical = 8.dp)
            .testTag("studio-preset-${preset.name}")) {
            Text(preset.name, color = if (on) StudioEditorRequests.chromeGold else darkTableColors.text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            Text(preset.description, color = darkTableColors.muted, fontSize = 11.sp)
        }
    }
}

// ------------------------------------------------------------------ Game options inside Studio (owner, Nov 19: retire Adjust UI)

/** The game's own options (card sizes, quick actions, notices), drawn as a page of Studio options instead of a separate window. */
internal object ChimeraGameOptions {
    var content by mutableStateOf<(@Composable () -> Unit)?>(null)
}

// ------------------------------------------------------------------ developer tools switch (owner, Nov 19)

/** Off for players: Studio's Classic panel and the owner's hand designs. On from Options › Troubleshooting. */
internal object ChimeraDev {
    private val prefs: SharedPreferences? by lazy {
        runCatching {
            val app = Class.forName("android.app.ActivityThread").getMethod("currentApplication").invoke(null) as Context
            app.getSharedPreferences("chimera_skin", Context.MODE_PRIVATE)
        }.getOrNull()
    }
    var on by mutableStateOf(prefs?.getBoolean("developer_tools", false) ?: false)
        private set
    fun change(v: Boolean) { on = v; prefs?.edit()?.putBoolean("developer_tools", v)?.apply() }
}
