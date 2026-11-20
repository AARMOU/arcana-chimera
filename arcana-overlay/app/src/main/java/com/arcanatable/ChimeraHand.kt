package com.arcanatable

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import java.util.concurrent.ConcurrentHashMap

// ------------------------------------------------------------------ the drawer's top strip (owner, Nov 9, items 3 and 4)

/**
 * One compact strip at the top of the hand drawer: the grab bar in the middle, the status line on the left, the menu
 * and close on the right (it used to be a 28dp grip strip plus a 44dp status row). The drawer installs [drag] (its
 * resize gesture) each time it draws; the strip and the status line carry it, so dragging them resizes the drawer.
 */
internal object ChimeraDrawerTop {
    var drag: Modifier = Modifier
    /** Engine games in Studio builds draw the strip in the hand itself (native drawer or hand design). */
    fun ownsTop(argentum: Boolean) = argentum && BuildConfig.STUDIO_ENABLED
}

@Composable
internal fun ChimeraGripBar(modifier: Modifier = Modifier) {
    Box(modifier.size(width = 40.dp, height = 5.dp).background(Muted, RoundedCornerShape(3.dp))
        .testTag("hand-drawer-handle").tutorialTarget("hand-drawer-grip"))
}

/** The native drawer's top strip. */
@Composable
internal fun ChimeraNativeTop(line: String, onClose: () -> Unit, menu: @Composable (close: () -> Unit) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth().height(36.dp).then(ChimeraDrawerTop.drag), verticalAlignment = Alignment.CenterVertically) {
        Text(line, color = SoftWhite, fontSize = 12.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f).testTag("hand-context"))
        ChimeraGripBar(Modifier.padding(horizontal = 12.dp))
        Row(Modifier.weight(1f), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
            Box {
                IconButton(onClick = { open = true }, modifier = Modifier.size(36.dp).testTag("hand-menu")) {
                    Icon(Icons.Filled.MoreVert, "More hand options", tint = Muted)
                }
                DropdownMenu(expanded = open, onDismissRequest = { open = false }) { menu { open = false } }
            }
            IconButton(onClick = onClose, modifier = Modifier.size(36.dp).testTag("hand-close")) {
                Icon(Icons.Filled.Close, "Close hand", tint = SoftWhite)
            }
        }
    }
}

/** The drawer's ⋮ menu (both drawers). Stack lives here now that its button became Commander. */
@Composable
internal fun ChimeraHandMenu(stackCount: Int, onStack: () -> Unit, commanderCanCast: Boolean, onCastCommander: () -> Unit,
    onLog: () -> Unit, autoPassing: Boolean, autoPassEnabled: Boolean, onAutoPass: () -> Unit, close: () -> Unit) {
    DropdownMenuItem(text = { Text("Stack · $stackCount") }, onClick = { close(); onStack() })
    if (commanderCanCast) DropdownMenuItem(text = { Text("Cast commander") }, onClick = { close(); onCastCommander() })
    DropdownMenuItem(text = { Text("Game log") }, onClick = { close(); onLog() })
    DropdownMenuItem(text = { Text(if (autoPassing) "Stop auto-pass" else "Pass to my turn") }, enabled = autoPassing || autoPassEnabled,
        onClick = { close(); onAutoPass() })
}

/** The status line text: the selected card first, then hand, phase, playable and stack. */
internal fun chimeraStatusLine(selected: GameCard?, line: String): String = if (selected != null) "${selected.name} · $line" else line

// ------------------------------------------------------------------ commander button and window (owner, Nov 9, item 7)

/** Each seat's commanders as the engine reports them (zone and tax), recorded with every game state. */
internal object ChimeraCommanders {
    data class Entry(val card: GameCard, val zone: String, val tax: Int)

    private val bySeat = ConcurrentHashMap<String, List<Entry>>()

    fun record(seat: String, zones: List<Pair<String, List<GameCard>>>, tax: (GameCard) -> Int) {
        val command = zones.firstOrNull { it.first == "Command zone" }?.second.orEmpty()
        val fallback = if (command.none { it.isCommander }) command.firstOrNull() else null
        bySeat[seat] = zones.flatMap { (zone, cards) ->
            cards.filter { it.isCommander || it === fallback }.map { Entry(it, zone, tax(it)) }
        }.distinctBy { it.card.id }
    }

    fun forSeat(seat: String?): List<Entry> = seat?.let { bySeat[it] }.orEmpty()
    fun clear() = bySeat.clear()
}

/** The Commander button's label (owner, Nov 9: just "COMMANDER"; the glow says when it can be cast). */
@Suppress("UNUSED_PARAMETER")
internal fun chimeraCommanderLabel(entries: List<ChimeraCommanders.Entry>, castable: Boolean): String = "COMMANDER"

/** A softly pulsing edge for something you can do right now (Commander, Cast). */
@Composable
internal fun Modifier.chimeraGlow(on: Boolean, shape: Shape): Modifier {
    if (!on) return this
    val pulse = rememberInfiniteTransition(label = "chimera-glow")
    val alpha by pulse.animateFloat(0.45f, 1f, infiniteRepeatable(tween(900), RepeatMode.Reverse), label = "chimera-glow-alpha")
    return this.border(2.5.dp, Mint.copy(alpha = alpha), shape)
}

@Composable
internal fun ChimeraCommanderWindow(entries: List<ChimeraCommanders.Entry>, castable: (GameCard) -> Boolean,
    onCast: (GameCard) -> Unit, onPreview: (GameCard) -> Unit, onDismiss: () -> Unit) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxWidth(0.92f).widthIn(max = 560.dp), shape = RoundedCornerShape(18.dp), color = Panel) {
            Column(Modifier.padding(16.dp)) {
                Text(if (entries.size > 1) "COMMANDERS" else "COMMANDER", color = Mint, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                if (entries.isEmpty()) Text("Your commander isn't in the game yet.", color = Muted, fontSize = 14.sp, modifier = Modifier.padding(vertical = 18.dp))
                Row(Modifier.fillMaxWidth().padding(top = 10.dp).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    entries.forEach { e ->
                        val can = castable(e.card)
                        Column(Modifier.widthIn(min = 150.dp, max = 230.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                            CardFace(e.card, Modifier.widthIn(min = 150.dp, max = 230.dp).fillMaxWidth(), onClick = { onPreview(e.card) },
                                onLongClick = { onPreview(e.card) }, showCounters = false, glow = can, cornerRadius = 10f)
                            Text(e.card.name, color = SoftWhite, fontSize = 14.sp, fontWeight = FontWeight.Bold, maxLines = 2, textAlign = TextAlign.Center,
                                modifier = Modifier.padding(top = 8.dp))
                            Text(listOfNotNull(e.zone, if (e.tax > 0) "tax +${e.tax}" else null).joinToString(" · "), color = Muted, fontSize = 12.sp)
                            Button(onClick = { onCast(e.card) }, enabled = can,
                                modifier = Modifier.padding(top = 8.dp).fillMaxWidth().heightIn(min = 48.dp).chimeraGlow(can, RoundedCornerShape(10.dp)),
                                shape = RoundedCornerShape(10.dp), colors = ButtonDefaults.buttonColors(containerColor = Mint, contentColor = Panel)) {
                                Text("CAST", fontWeight = FontWeight.Bold)
                            }
                            if (!can) Text(if (e.zone == "Command zone") "Not castable right now" else "Not in the command zone",
                                color = Muted, fontSize = 11.sp, modifier = Modifier.padding(top = 4.dp))
                        }
                    }
                }
                TextButton(onClick = onDismiss, modifier = Modifier.align(Alignment.End).padding(top = 6.dp)) { Text("Close") }
            }
        }
    }
}

// ------------------------------------------------------------------ hand designs (owner, Nov 9, items 2, 4, 7, 9, 10, 12)

internal object ChimeraHandDesign {
    /** Bindings beyond Arcana's: the status line, close, menu, and the Commander button (was Stack). */
    val EXTRA_BINDINGS = setOf("status", "close", "menu", "commander")
    val KINDS = setOf("status", "icon")
    val CARD_STYLES = listOf("grid" to "Grid", "row" to "Single row", "fan" to "Fan", "catalogue" to "Catalogue")
    val SHAPES = listOf("" to "Rounded", "square" to "Square", "pill" to "Pill", "round" to "Round")
    const val VERSION = 1

    /**
     * Saved designs gain the top strip's pieces where they sat (status left, ⋮ and ✕ right, the design moves down by
     * the strip) and Stack becomes Commander. Runs once per design; a piece you later remove stays removed.
     */
    fun upgrade(l: StudioLayout): StudioLayout {
        if (l.chimera >= VERSION) return l
        var els = l.elements.map {
            if (it.binding == "metric0") it.copy(binding = "commander", label = if (it.label.trim().uppercase().startsWith("STACK")) "COMMANDER" else it.label) else it
        }
        if (els.none { it.binding in setOf("status", "close", "menu") } && els.size <= 61) {
            val o = l.originY; val w = l.canvasWidth; val strip = 44f
            els = els.map { if (it.y >= o - 1f) it.copy(y = it.y + strip) else it }
            els = els + listOf(
                StudioElement("chimera-status", "status", "Hand 7 · Main 1 · 2 playable", 12f, o + 8f, (w / 2f - 40f).coerceAtLeast(96f), 24f, fs = 11f, r = 0f, binding = "status"),
                StudioElement("chimera-menu", "icon", "⋮", w - 100f, o, 44f, 40f, fs = 16f, r = 8f, binding = "menu"),
                StudioElement("chimera-close", "icon", "✕", w - 52f, o, 44f, 40f, fs = 15f, r = 8f, binding = "close"))
        }
        return l.copy(elements = els, chimera = VERSION)
    }

    fun shape(e: StudioElement, scale: Float): Shape = when (e.shape) {
        "square" -> RoundedCornerShape(0.dp)
        "pill", "round" -> RoundedCornerShape(50)
        else -> RoundedCornerShape((e.r * scale).dp)
    }

    /** Round buttons are circles when the label fits one; a longer label widens the circle into a pill instead of cutting it. */
    fun roundSize(e: StudioElement, label: String): Pair<Float, Float> {
        if (e.shape != "round") return e.w to e.h
        val side = minOf(e.w, e.h)
        val longest = label.split('\n').maxOfOrNull { it.length } ?: 0
        val needs = longest * e.fs * 0.62f + 12f
        return if (needs <= side) side to side else minOf(e.w, maxOf(side, needs)) to side
    }
}

/** Studio → Hand: how the cards sit, and the selected item's shape. */
@Composable
internal fun ChimeraHandStyleChips(layout: StudioLayout, onChange: (StudioLayout) -> Unit) {
    Text("Cards", color = StudioEditorRequests.chromeGold, fontSize = 12.sp, modifier = Modifier.padding(top = 10.dp))
    Row(Modifier.horizontalScroll(rememberScrollState())) {
        ChimeraHandDesign.CARD_STYLES.forEach { (id, name) ->
            StudioChip(name, true, "chimera-hand-cards-$id", selected = layout.cards == id) { onChange(layout.copy(cards = id)) }
        }
    }
}

@Composable
internal fun ChimeraShapeChips(e: StudioElement, change: ((StudioElement) -> StudioElement) -> Unit) {
    if (e.kind == "card" || e.kind == "status") return
    Text("Shape", color = androidx.compose.ui.graphics.Color(0xFF9AA7A2), fontSize = 12.sp)
    Row(Modifier.horizontalScroll(rememberScrollState())) {
        ChimeraHandDesign.SHAPES.forEach { (id, name) ->
            StudioChip(name, true, "chimera-shape-${id.ifEmpty { "rounded" }}", selected = e.shape == id) { change { it.copy(shape = id) } }
        }
    }
}
