@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)

package com.arcanatable

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.json.JSONObject

/**
 * Whole-screen layouts for the Studio presets (owner, Nov 9: "I want it to look like a different game/app").
 * Each one draws the game its own way; Arcana's own pop-ups (choices, combat, payment, stack, zones, previews) still
 * open on top, so every rule and decision works as before. Studio always edits on the classic screen; the menu in
 * every layout can switch back to the classic screen.
 */
internal object ChimeraSkins {
    val CUSTOM = linkedMapOf("arena" to "Duel Arena", "orbit" to "Orbit", "across" to "Across the Table", "feed" to "Feed",
        "terminal" to "Terminal", "focus" to "Focus", "storybook" to "Storybook", "xp" to "XP Desktop")
    private val prefs: SharedPreferences? by lazy {
        runCatching {
            val app = Class.forName("android.app.ActivityThread").getMethod("currentApplication").invoke(null) as Context
            app.getSharedPreferences("chimera_skin", Context.MODE_PRIVATE)
        }.getOrNull()
    }
    var id by mutableStateOf(prefs?.getString("skin", "").orEmpty())
        private set

    fun set(value: String) { id = value; prefs?.edit()?.putString("skin", value)?.apply() }

    /** The typeface on Arcana's own screen ("" = Arcana's), set by Glass Cockpit (condensed) and Command Center (mono). */
    var classicFontName by mutableStateOf(prefs?.getString("font", "").orEmpty())
        private set
    fun setClassicFont(value: String) { classicFontName = value; prefs?.edit()?.putString("font", value)?.apply() }
    fun classicFont(): FontFamily? = classicFontName.takeIf { it.isNotEmpty() }?.let { font(it) }

    fun active(t: TableController): Boolean = id in CUSTOM && t.argentumEnabled && t.argentumSnapshot != null && !t.homeOpen &&
        t.studioControlsRuntime?.editing != true

    fun font(name: String): FontFamily = when (name) {
        "serif" -> FontFamily.Serif
        "mono" -> FontFamily.Monospace
        "condensed" -> runCatching { FontFamily(android.graphics.Typeface.create("sans-serif-condensed", android.graphics.Typeface.NORMAL)) }.getOrDefault(FontFamily.SansSerif)
        else -> FontFamily.SansSerif
    }
}

/** What a layout needs from the game, and the moves it can make (all through Arcana's own controller). */
internal class SkinGame(val t: TableController) {
    val snap = t.argentumSnapshot!!
    val seat = t.localSeat
    val me: TablePlayer? = t.players.getOrNull(seat)
    val opponents: List<TablePlayer> = t.players.filterIndexed { i, _ -> i != seat }
    val myTurn = snap.activePlayer == seat
    val phase = ChimeraPhaseStops.phaseOf(snap.stepName) ?: snap.stepName
    val step = snap.stepName.replace('_', ' ').lowercase().replaceFirstChar { it.uppercase() }
    val playable = t.argentumPlayableIds
    val autoPassing = t.passToMyTurnFrom != null
    val busy = t.argentumAwaitingUpdate || t.argentumPendingDecision != null || autoPassing || t.gameEndedOnline
    val passLabel = if (autoPassing) "AUTO-PASSING · STOP" else t.argentumPrimaryLabel ?: "WAITING"
    val canPass = autoPassing || (t.argentumPrimaryLabel != null && !t.argentumAwaitingUpdate)
    val canUndo = t.argentumUndoAvailable && !t.argentumAwaitingUpdate
    val actions: List<JSONObject> = handDrawerActions(snap.legalActions, busy)
    val hand = me?.hand.orEmpty()
    val stackCount = t.visibleStackCount
    val commanders = ChimeraCommanders.forSeat(me?.id)
    val commanderGlow = !busy && commanders.any { it.card.id in playable }
    val log = snap.fullLog.filterNot { it.isTurnMarker }

    fun pass() { if (autoPassing) t.togglePassToMyTurn() else if (!t.argentumAwaitingUpdate) t.argentumPrimary() }
    fun holdPass() { if (autoPassing || !busy) t.togglePassToMyTurn() }
    fun undo() { if (canUndo) t.requestArgentumUndo() }
    fun openActions() { if (actions.isNotEmpty()) { t.argentumActionCard = null; t.argentumActionChoices = actions } }
    fun castable(card: GameCard) = !busy && card.id in playable
    fun cast(card: GameCard?) {
        if (busy) return
        if (card != null && card.id in playable) { t.openArgentumActions(card); return }
        val casts = handDrawerCastActions(actions, hand.mapNotNull { snap.serverCardIds[it.id] }.toSet())
        if (casts.isNotEmpty()) { t.argentumActionCard = null; t.argentumActionChoices = casts }
    }
    fun preview(card: GameCard) { t.cardZoom.hold(card); t.cardZoom.release(false) }
    fun tapPermanent(card: GameCard, mine: Boolean) { if (mine && !busy && card.id in playable) t.openArgentumActions(card) else preview(card) }
    fun graveyard() { t.zoneDialog = seat to "Graveyard" }
    fun exile() { t.zoneDialog = seat to "Exile" }
    fun stack() { t.stackOpen = true }
    fun log() { t.logOpen = true }
}

/** A layout's colours, type and shapes. */
internal data class SkinLook(
    val bg: Color, val panel: Color, val raised: Color, val accent: Color, val text: Color, val muted: Color, val onAccent: Color,
    val font: FontFamily, val radius: Dp, val border: Dp = 1.dp, val line: Color = muted.copy(alpha = 0.4f),
) {
    val shape: Shape get() = RoundedCornerShape(radius)
}

@Composable
internal fun ChimeraSkinScreen(t: TableController) {
    val g = SkinGame(t)
    var selected by remember { mutableStateOf<Int?>(null) }
    var commanderOpen by remember { mutableStateOf(false) }
    val sel = g.hand.firstOrNull { it.id == selected }
    val pick: (GameCard) -> Unit = { selected = if (selected == it.id) null else it.id }
    val ui = SkinUi(g, sel, pick, { commanderOpen = true })
    Box(Modifier.fillMaxSize().testTag("chimera-skin-${ChimeraSkins.id}")) {
        when (ChimeraSkins.id) {
            "arena" -> SkinArena(ui)
            "orbit" -> SkinOrbit(ui)
            "across" -> SkinAcross(ui)
            "feed" -> SkinFeed(ui)
            "terminal" -> SkinTerminal(ui)
            "focus" -> SkinFocus(ui)
            "xp" -> ChimeraXpDesktop(ui)
            else -> SkinStorybook(ui)
        }
    }
    if (commanderOpen) ChimeraCommanderWindow(g.commanders, { g.castable(it) }, { c -> commanderOpen = false; g.t.openArgentumActions(c) },
        { g.preview(it) }, { commanderOpen = false })
}

internal class SkinUi(val g: SkinGame, val selected: GameCard?, val pick: (GameCard) -> Unit, val openCommander: () -> Unit)

// ---------------------------------------------------------------- shared pieces

@Composable
private fun SkinText(text: String, look: SkinLook, size: TextUnit = 12.sp, color: Color = look.text, weight: FontWeight = FontWeight.Normal,
    modifier: Modifier = Modifier, align: TextAlign? = null, maxLines: Int = Int.MAX_VALUE) {
    Text(text, color = color, fontSize = size, fontWeight = weight, fontFamily = look.font, modifier = modifier, textAlign = align,
        maxLines = maxLines, overflow = TextOverflow.Ellipsis, lineHeight = size * 1.2f)
}

/** A button that always shows its text; [glow] pulses its edge; [onHold] for Pass (auto-pass). */
@Composable
private fun SkinButton(label: String, look: SkinLook, onClick: () -> Unit, modifier: Modifier = Modifier, strong: Boolean = false,
    enabled: Boolean = true, glow: Boolean = false, onHold: (() -> Unit)? = null, shape: Shape = look.shape, size: TextUnit = 12.sp,
    fill: Color? = null, ink: Color? = null, outline: Color? = null) {
    val haptic = LocalHapticFeedback.current
    val bg = fill ?: if (strong) look.accent else look.raised
    val fg = ink ?: if (strong) look.onAccent else look.text
    Box(modifier.heightIn(min = 44.dp).chimeraGlow(glow, shape).clip(shape).background(if (enabled) bg else bg.copy(alpha = 0.45f))
        .border(look.border, outline ?: if (strong) Color.Transparent else look.line, shape)
        .combinedClickable(enabled = enabled, onClick = onClick,
            onLongClick = onHold?.let { h -> { haptic.performHapticFeedback(HapticFeedbackType.LongPress); h() } })
        .padding(horizontal = 8.dp, vertical = 6.dp), contentAlignment = Alignment.Center) {
        SkinText(label, look, size, if (enabled) fg else fg.copy(alpha = 0.5f), FontWeight.Bold, align = TextAlign.Center)
    }
}

@Composable
private fun SkinCard(card: GameCard, width: Dp, g: SkinGame, mine: Boolean, modifier: Modifier = Modifier, radius: Float = 8f, inHand: Boolean = false,
    selected: Boolean = false, onTap: (() -> Unit)? = null) {
    val glow = if (inHand) g.castable(card) else mine && card.id in g.playable && !g.busy
    CardFace(card, modifier.width(width).then(if (selected) Modifier.border(3.dp, Color.White, RoundedCornerShape(radius.dp)) else Modifier),
        onClick = { onTap?.invoke() ?: g.tapPermanent(card, mine) }, onLongClick = { g.preview(card) }, showCounters = !inHand, glow = glow,
        tutorialZone = if (inHand) "hand" else null, cornerRadius = radius)
}

@Composable
private fun CardRow(cards: List<GameCard>, width: Dp, g: SkinGame, mine: Boolean, look: SkinLook, empty: String = "Nothing here", radius: Float = 8f,
    overlap: Dp = 0.dp) {
    if (cards.isEmpty()) { SkinText(empty, look, 11.sp, look.muted); return }
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(if (overlap > 0.dp) -overlap else 6.dp)) {
        cards.forEach { SkinCard(it, width, g, mine, radius = radius) }
    }
}

private fun lands(p: TablePlayer?) = p?.battlefield.orEmpty().filter { it.type.contains("Land", true) }
private fun others(p: TablePlayer?) = p?.battlefield.orEmpty().filterNot { it.type.contains("Land", true) }

/** The ⋮ menu every layout has: stack, log, concede, leave, and the way back to the classic screen. */
@Composable
private fun SkinMenu(g: SkinGame, look: SkinLook, label: String = "⋮") {
    var open by remember { mutableStateOf(false) }
    Box {
        SkinButton(label, look, { open = true }, Modifier.widthIn(min = 44.dp), size = 16.sp)
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(text = { Text("Stack · ${g.stackCount}") }, onClick = { open = false; g.stack() })
            DropdownMenuItem(text = { Text("Game log") }, onClick = { open = false; g.log() })
            DropdownMenuItem(text = { Text("Board summary") }, onClick = { open = false; ChimeraFeatures.boardOpen = true })
            if (ChimeraFeatureSettings.coach) DropdownMenuItem(text = { Text("Coach: what would the AI do?") }, onClick = { open = false; ChimeraCoach.ask(g.t) })
            DropdownMenuItem(text = { Text("Game history") }, onClick = { open = false; ChimeraFeatures.historyOpen = true })
            DropdownMenuItem(text = { Text(if (g.autoPassing) "Stop auto-pass" else "Pass to my turn") }, onClick = { open = false; g.holdPass() })
            DropdownMenuItem(text = { Text("Classic screen") }, onClick = { open = false; ChimeraSkins.set("") })
            DropdownMenuItem(text = { Text("Options") }, onClick = { open = false; g.t.optionsOpen = true })
            DropdownMenuItem(text = { Text("Concede", color = Color(0xFFE9978B)) }, onClick = { open = false; ChimeraConcede.asking = true })
            DropdownMenuItem(text = { Text("Leave game…") }, onClick = { open = false; g.t.argentumLeaveConfirm = true })
        }
    }
}

/** The hand's buttons, all with their text: Undo, Actions, Cast, Commander, Library, Graveyard, Exile. */
@Composable
private fun RowScope.UndoActionsCast(ui: SkinUi, look: SkinLook, shape: Shape = look.shape, size: TextUnit = 12.sp) {
    val g = ui.g
    SkinButton("UNDO", look, { g.undo() }, Modifier.weight(1f), enabled = g.canUndo, shape = shape, size = size)
    SkinButton("ACTIONS · ${g.actions.size}", look, { g.openActions() }, Modifier.weight(1.3f), enabled = g.actions.isNotEmpty(), shape = shape, size = size)
    SkinButton("CAST", look, { g.cast(ui.selected) }, Modifier.weight(1f), enabled = !g.busy, shape = shape, size = size)
}

@Composable
private fun RowScope.Zones(ui: SkinUi, look: SkinLook, shape: Shape = look.shape, size: TextUnit = 11.sp, commander: Boolean = true) {
    val g = ui.g
    if (commander) SkinButton("COMMANDER", look, ui.openCommander, Modifier.weight(1.3f), glow = g.commanderGlow, shape = shape, size = size)
    SkinButton("LIBRARY ${g.me?.library?.size ?: 0}", look, {}, Modifier.weight(1f), shape = shape, size = size)
    SkinButton("GRAVEYARD ${g.me?.graveyard?.size ?: 0}", look, { g.graveyard() }, Modifier.weight(1.2f), shape = shape, size = size)
    SkinButton("EXILE ${g.me?.exile?.size ?: 0}", look, { g.exile() }, Modifier.weight(1f), shape = shape, size = size)
}

@Composable
private fun PassButton(g: SkinGame, look: SkinLook, modifier: Modifier, shape: Shape = look.shape, size: TextUnit = 15.sp, label: String = g.passLabel) {
    SkinButton(label, look, { g.pass() }, modifier, strong = true, enabled = g.canPass, onHold = { g.holdPass() }, shape = shape, size = size,
        glow = g.t.argentumCanRespond)
}

@Composable
private fun Screen(look: SkinLook, content: @Composable () -> Unit) {
    Box(Modifier.fillMaxSize().background(look.bg).windowInsetsPadding(WindowInsets.safeDrawing)) { content() }
}

// ---------------------------------------------------------------- 4 · Duel Arena

@Composable
private fun SkinArena(ui: SkinUi) {
    val g = ui.g
    val look = SkinLook(Color(0xFF16112A), Color(0xFF1F1838), Color(0xFF2A2245), Color(0xFFE6B85C), Color(0xFFF2EEE6), Color(0xFFA99FBF),
        Color(0xFF120E1C), ChimeraSkins.font("serif"), 999.dp, line = Color(0xFF8A7AB8))
    Screen(look) {
        Column(Modifier.fillMaxSize().padding(horizontal = 12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Row(Modifier.weight(1f).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    g.opponents.forEach { o -> LifeRing(o.name.uppercase(), o.life, Color(0xFFC0392B), look, 58.dp) }
                }
                SkinMenu(g, look)
            }
            CardRow(others(g.opponents.firstOrNull()) + lands(g.opponents.firstOrNull()), 64.dp, g, false, look)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Box(Modifier.weight(1f).height(1.dp).background(look.line))
                ChimeraPhaseStops.PHASES.forEach { p ->
                    if (p == g.phase) SkinText(p, look, 11.sp, look.onAccent, FontWeight.Bold, Modifier.clip(CircleShape).background(look.accent).padding(horizontal = 10.dp, vertical = 3.dp).chimeraPhaseStop(p))
                    else Box(Modifier.size(9.dp).clip(CircleShape).background(look.line).chimeraPhaseStop(p))
                }
                Box(Modifier.weight(1f).height(1.dp).background(look.line))
            }
            CardRow(others(g.me), 78.dp, g, true, look, "Your creatures and spells appear here")
            CardRow(lands(g.me), 56.dp, g, true, look, "")
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                LifeRing("YOU", g.me?.life ?: 0, Color(0xFF3FB6A8), look, 54.dp)
                Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(6.dp)) { Zones(ui, look, size = 10.sp) }
            }
            Box(Modifier.weight(1f).fillMaxWidth()) {
                ChimeraHandCards("fan", g.hand, if (g.busy) emptySet() else g.playable, ui.selected?.id, null, ui.pick, { g.preview(it) }, Modifier.fillMaxSize())
            }
            Row(Modifier.fillMaxWidth().padding(bottom = 8.dp), verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) { UndoActionsCast(ui, look) }
                    SkinText("Hand ${g.hand.size} · ${g.step}${if (g.myTurn) " · your turn" else ""}", look, 11.sp, look.muted)
                }
                PassButton(g, look, Modifier.size(104.dp), CircleShape, 14.sp)
            }
        }
    }
}

@Composable
private fun LifeRing(name: String, life: Int, ring: Color, look: SkinLook, size: Dp) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.size(size).clip(CircleShape).border(4.dp, ring, CircleShape).background(look.panel), contentAlignment = Alignment.Center) {
            SkinText("$life", look, (size.value / 2.6f).sp, look.text, FontWeight.Bold)
        }
        SkinText(name, look, 10.sp, look.muted, FontWeight.Bold, maxLines = 1, modifier = Modifier.widthIn(max = size + 30.dp))
    }
}

// ---------------------------------------------------------------- 5 · Orbit

@Composable
private fun SkinOrbit(ui: SkinUi) {
    val g = ui.g
    val look = SkinLook(Color(0xFF0B1020), Color(0xFF121A33), Color(0xFF18223F), Color(0xFFFF8A3D), Color(0xFFEAF0FF), Color(0xFF8C9AC4),
        Color(0xFF0B1020), ChimeraSkins.font("sans"), 999.dp, line = Color(0xFF3A4A7A))
    Screen(look) {
        Column(Modifier.fillMaxSize().padding(horizontal = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                LifeRing("YOU", g.me?.life ?: 0, Color(0xFF4DD4FF), look, 56.dp)
                Box(Modifier.weight(1f), contentAlignment = Alignment.Center) { PhaseDial(g, look) }
                Column(horizontalAlignment = Alignment.End) {
                    SkinMenu(g, look)
                    g.opponents.forEach { o -> SkinText("${o.name} · ${o.life}", look, 11.sp, look.muted, maxLines = 1) }
                }
            }
            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp)).background(look.panel).padding(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                CardRow(g.opponents.flatMap { it.battlefield }, 56.dp, g, false, look, "Opponents have nothing out", radius = 12f)
                Box(Modifier.fillMaxWidth().height(1.dp).background(look.line))
                CardRow(g.me?.battlefield.orEmpty(), 64.dp, g, true, look, "Your battlefield", radius = 12f)
            }
            Box(Modifier.weight(1f).fillMaxWidth()) {
                ChimeraHandCards("fan", g.hand, if (g.busy) emptySet() else g.playable, ui.selected?.id, null, ui.pick, { g.preview(it) }, Modifier.fillMaxSize())
            }
            BoxWithConstraints(Modifier.fillMaxWidth().height(230.dp)) {
                val cx = maxWidth / 2; val r = minOf(maxWidth / 2 - 50.dp, 150.dp)
                Canvas(Modifier.fillMaxSize()) {
                    drawCircle(look.panel, radius = (r + 70.dp).toPx(), center = Offset(size.width / 2, size.height + 30.dp.toPx()))
                }
                val items = listOf<Triple<String, Boolean, () -> Unit>>(
                    Triple("UNDO", g.canUndo, { g.undo() }), Triple("ACTIONS · ${g.actions.size}", g.actions.isNotEmpty(), { g.openActions() }),
                    Triple("CAST", !g.busy, { g.cast(ui.selected) }), Triple("COMMANDER", true, ui.openCommander),
                    Triple("GRAVEYARD ${g.me?.graveyard?.size ?: 0}", true, { g.graveyard() }), Triple("EXILE ${g.me?.exile?.size ?: 0}", true, { g.exile() }))
                items.forEachIndexed { i, (label, enabled, act) ->
                    val a = Math.toRadians((180.0 + 18.0 + i * (144.0 / (items.size - 1))))
                    val x = cx + r * kotlin.math.cos(a).toFloat() - 48.dp
                    val y = 168.dp + r * kotlin.math.sin(a).toFloat() * 0.85f
                    SkinButton(label, look, act, Modifier.offset(x, y).width(96.dp), enabled = enabled, size = 10.sp,
                        glow = label == "COMMANDER" && g.commanderGlow)
                }
                PassButton(g, look, Modifier.align(Alignment.BottomCenter).padding(bottom = 6.dp).size(112.dp), CircleShape, 14.sp)
            }
        }
    }
}

@Composable
private fun PhaseDial(g: SkinGame, look: SkinLook) {
    Box(Modifier.size(120.dp), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val seg = 360f / 5f
            ChimeraPhaseStops.PHASES.forEachIndexed { i, p ->
                drawArc(if (p == g.phase) look.accent else look.raised, -90f + i * seg + 2f, seg - 4f, false,
                    style = Stroke(12.dp.toPx()), topLeft = Offset(8.dp.toPx(), 8.dp.toPx()), size = Size(size.width - 16.dp.toPx(), size.height - 16.dp.toPx()))
            }
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            SkinText("TURN ${g.snap.turnNumber}", look, 10.sp, look.muted)
            SkinText(g.phase, look, 15.sp, look.accent, FontWeight.Bold, Modifier.chimeraPhaseStop(g.phase))
            SkinText(if (g.myTurn) "your turn" else "their turn", look, 10.sp, look.muted)
        }
    }
}

// ---------------------------------------------------------------- 6 · Across the Table

@Composable
private fun SkinAcross(ui: SkinUi) {
    val g = ui.g
    val look = SkinLook(Color(0xFF5B3A22), Color(0xFF1F4A3A), Color(0x55000000), Color(0xFFF2C14E), Color(0xFFF7EFE2), Color(0xFFD9CBB4),
        Color(0xFF3A2412), ChimeraSkins.font("sans"), 10.dp, line = Color(0x66F7EFE2))
    Screen(look) {
        Row(Modifier.fillMaxSize().padding(8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                g.opponents.take(3).forEach { o ->
                    Column(Modifier.weight(1f).fillMaxWidth().graphicsLayer { rotationZ = 180f }.clip(RoundedCornerShape(14.dp)).background(look.panel)
                        .padding(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            SkinText(o.name.uppercase(), look, 12.sp, weight = FontWeight.Bold, modifier = Modifier.weight(1f))
                            SkinText("${o.life}", look, 24.sp, weight = FontWeight.Bold)
                        }
                        CardRow(o.battlefield, 52.dp, g, false, look, "Empty mat", 5f)
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Slot("LIBRARY\n${o.library.size}", look); Slot("GRAVE\n${o.graveyard.size}", look); Slot("EXILE\n${o.exile.size}", look)
                        }
                    }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                    ChimeraPhaseStops.PHASES.forEachIndexed { i, p ->
                        if (i > 0) SkinText("›", look, 12.sp, look.muted)
                        SkinText(p, look, 11.sp, if (p == g.phase) look.onAccent else look.muted, FontWeight.Bold,
                            Modifier.padding(horizontal = 3.dp).then(if (p == g.phase) Modifier.clip(CircleShape).background(look.accent).padding(horizontal = 8.dp, vertical = 2.dp) else Modifier).chimeraPhaseStop(p))
                    }
                }
                Column(Modifier.weight(1.4f).fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(look.panel)
                    .border(2.dp, if (g.myTurn) look.accent else Color.Transparent, RoundedCornerShape(14.dp)).padding(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        SkinText("YOU${if (g.myTurn) " · YOUR TURN" else ""}", look, 12.sp, weight = FontWeight.Bold, modifier = Modifier.weight(1f))
                        SkinText("${g.me?.life ?: 0}", look, 24.sp, weight = FontWeight.Bold)
                    }
                    CardRow(g.me?.battlefield.orEmpty(), 58.dp, g, true, look, "Empty mat", 5f)
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Slot("LIBRARY\n${g.me?.library?.size ?: 0}", look)
                        Slot("GRAVE\n${g.me?.graveyard?.size ?: 0}", look) { g.graveyard() }
                        Slot("EXILE\n${g.me?.exile?.size ?: 0}", look) { g.exile() }
                        Slot("COMMAND\nZONE", look, glow = g.commanderGlow) { ui.openCommander() }
                    }
                    Spacer(Modifier.weight(1f))
                    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy((-12).dp)) {
                        g.hand.forEach { c -> SkinCard(c, 56.dp, g, true, radius = 5f, inHand = true, selected = c.id == ui.selected?.id, onTap = { ui.pick(c) }) }
                    }
                }
            }
            Column(Modifier.width(96.dp).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                SkinMenu(g, look, "⋮ MENU")
                SkinButton("GAME LOG", look, { g.log() }, Modifier.fillMaxWidth(), size = 11.sp)
                Spacer(Modifier.weight(1f))
                SkinButton("COMMANDER", look, ui.openCommander, Modifier.fillMaxWidth(), glow = g.commanderGlow, size = 10.sp)
                SkinButton("CAST", look, { g.cast(ui.selected) }, Modifier.fillMaxWidth(), enabled = !g.busy, size = 11.sp)
                SkinButton("ACTIONS · ${g.actions.size}", look, { g.openActions() }, Modifier.fillMaxWidth(), enabled = g.actions.isNotEmpty(), size = 10.sp)
                SkinButton("UNDO", look, { g.undo() }, Modifier.fillMaxWidth(), enabled = g.canUndo, size = 11.sp)
                PassButton(g, look, Modifier.fillMaxWidth().height(110.dp), size = 13.sp)
            }
        }
    }
}

@Composable
private fun Slot(text: String, look: SkinLook, glow: Boolean = false, onClick: (() -> Unit)? = null) {
    Box(Modifier.size(50.dp, 70.dp).chimeraGlow(glow, RoundedCornerShape(5.dp)).clip(RoundedCornerShape(5.dp))
        .border(1.dp, look.line, RoundedCornerShape(5.dp))
        .then(if (onClick != null) Modifier.combinedClickable(onClick = onClick) else Modifier), contentAlignment = Alignment.Center) {
        SkinText(text, look, 8.sp, look.text, FontWeight.Bold, align = TextAlign.Center)
    }
}

// ---------------------------------------------------------------- 7 · Feed

@Composable
private fun SkinFeed(ui: SkinUi) {
    val g = ui.g
    val look = SkinLook(Color(0xFFF4F2EE), Color(0xFFFFFFFF), Color(0xFFFFFFFF), Color(0xFF1F7A4D), Color(0xFF1E1E24), Color(0xFF6B6B76),
        Color.White, ChimeraSkins.font("sans"), 999.dp, line = Color(0xFFD6D2CA))
    var boardOpen by remember { mutableStateOf(false) }
    Screen(look) {
        Column(Modifier.fillMaxSize()) {
            Row(Modifier.fillMaxWidth().background(Color.White).padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    SkinText("You vs ${g.opponents.joinToString { it.name }}", look, 15.sp, weight = FontWeight.Bold, maxLines = 1)
                    SkinText("Turn ${g.snap.turnNumber} · ${g.step} · ${if (g.myTurn) "your turn" else "their turn"}", look, 12.sp, look.muted, maxLines = 1)
                }
                SkinText((listOf(g.me?.life ?: 0) + g.opponents.map { it.life }).joinToString(" – "), look, 13.sp, look.accent, FontWeight.Bold,
                    Modifier.clip(CircleShape).background(Color(0xFFEAF4EE)).padding(horizontal = 10.dp, vertical = 6.dp))
                Spacer(Modifier.width(6.dp)); SkinMenu(g, look)
            }
            Column(Modifier.fillMaxWidth().padding(10.dp).clip(RoundedCornerShape(16.dp)).background(Color.White).border(1.dp, look.line, RoundedCornerShape(16.dp))
                .combinedClickable(onClick = { boardOpen = !boardOpen }).padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                SkinText("Your board · ${g.me?.battlefield?.size ?: 0} · ${if (boardOpen) "tap to close" else "tap to open"}", look, 12.sp, weight = FontWeight.Bold)
                if (boardOpen) {
                    CardRow(g.me?.battlefield.orEmpty(), 60.dp, g, true, look, "Nothing yet")
                    g.opponents.forEach { o -> SkinText("${o.name}'s board", look, 12.sp, look.muted); CardRow(o.battlefield, 52.dp, g, false, look, "Nothing yet") }
                } else SkinText(g.opponents.joinToString(" · ") { "${it.name}: ${it.battlefield.size} permanents" }, look, 12.sp, look.muted)
            }
            Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState(), reverseScrolling = true).padding(horizontal = 12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)) {
                g.log.takeLast(40).forEach { line ->
                    val mine = line.isYours == true
                    Box(Modifier.fillMaxWidth(), contentAlignment = if (mine) Alignment.CenterEnd else Alignment.CenterStart) {
                        SkinText(line.description, look, 13.sp, if (mine) Color.White else look.text,
                            modifier = Modifier.widthIn(max = 300.dp).clip(RoundedCornerShape(14.dp)).background(if (mine) look.accent else Color.White)
                                .border(if (mine) 0.dp else 1.dp, if (mine) Color.Transparent else look.line, RoundedCornerShape(14.dp)).padding(10.dp))
                    }
                }
                if (g.myTurn && g.canPass) SkinText("Your ${g.step.lowercase()}. ${g.hand.count { g.castable(it) }} card(s) can be played.", look, 13.sp,
                    modifier = Modifier.align(Alignment.CenterHorizontally).clip(RoundedCornerShape(14.dp)).background(Color(0xFFFFF6DE))
                        .border(1.dp, Color(0xFFF2C14E), RoundedCornerShape(14.dp)).padding(10.dp))
            }
            Column(Modifier.fillMaxWidth().background(Color.White).padding(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    g.hand.forEach { c -> SkinCard(c, 54.dp, g, true, inHand = true, selected = c.id == ui.selected?.id, onTap = { ui.pick(c) }) }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) { UndoActionsCast(ui, look, size = 13.sp) }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) { Zones(ui, look, size = 11.sp) }
                PassButton(g, look, Modifier.fillMaxWidth().height(52.dp))
            }
        }
    }
}

// ---------------------------------------------------------------- 8 · Terminal

@Composable
private fun SkinTerminal(ui: SkinUi) = ChimeraTerminal(ui) // reworked with five layouts and settings: ChimeraTerminal.kt

// ---------------------------------------------------------------- 9 · Focus

@Composable
private fun SkinFocus(ui: SkinUi) {
    val g = ui.g
    val look = SkinLook(Color(0xFF101114), Color(0xFF1B1C21), Color(0xFF1B1C21), Color(0xFF7C6CFF), Color(0xFFF5F5F7), Color(0xFF8E8E99),
        Color.White, ChimeraSkins.font("sans"), 14.dp, line = Color(0xFF3A3B44))
    var tab by remember { mutableStateOf("HAND") }
    val focus = ui.selected ?: g.hand.firstOrNull { g.castable(it) } ?: g.hand.firstOrNull()
    Screen(look) {
        Column(Modifier.fillMaxSize()) {
            Row(Modifier.fillMaxWidth().padding(16.dp, 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    SkinText("Turn ${g.snap.turnNumber} · ${g.step}", look, 12.sp, look.muted)
                    SkinText(if (g.canPass && !g.autoPassing) "Your move" else if (g.autoPassing) "Auto-passing" else "Waiting", look, 26.sp, weight = FontWeight.Black)
                }
                Column(horizontalAlignment = Alignment.End) {
                    SkinText("You ${g.me?.life ?: 0}", look, 13.sp); g.opponents.forEach { SkinText("${it.name} ${it.life}", look, 13.sp, look.muted, maxLines = 1) }
                }
                Spacer(Modifier.width(6.dp)); SkinMenu(g, look)
            }
            Column(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 16.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                when (tab) {
                    "HAND" -> {
                        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp)).background(look.panel).padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            SkinText("${g.hand.count { g.castable(it) }} card(s) can be played. Pick one, or pass.", look, 14.sp, look.muted)
                            if (focus != null) Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                                SkinCard(focus, 140.dp, g, true, radius = 14f, inHand = true, onTap = { g.preview(focus) })
                                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    SkinText(focus.name, look, 16.sp, weight = FontWeight.Black)
                                    SkinText(listOfNotNull(focus.type, focus.manaCost).joinToString(" · "), look, 12.sp, look.muted)
                                    focus.oracleText?.let { SkinText(it, look, 12.sp, Color(0xFFB9B9C4), maxLines = 6) }
                                    SkinButton("CAST", look, { g.cast(focus) }, Modifier.fillMaxWidth(), strong = true, enabled = g.castable(focus))
                                    SkinButton("ACTIONS · ${g.actions.size}", look, { g.openActions() }, Modifier.fillMaxWidth(), enabled = g.actions.isNotEmpty())
                                }
                            }
                        }
                        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            g.hand.forEach { c -> SkinCard(c, 52.dp, g, true, inHand = true, selected = c.id == focus?.id, onTap = { ui.pick(c) }) }
                        }
                    }
                    "BOARD" -> { SkinText("Your battlefield", look, 14.sp, weight = FontWeight.Bold); CardRow(others(g.me), 80.dp, g, true, look); CardRow(lands(g.me), 60.dp, g, true, look, "No lands") }
                    "OPPONENT" -> g.opponents.forEach { o -> SkinText("${o.name} · ${o.life} life · ${o.hand.size} in hand", look, 14.sp, weight = FontWeight.Bold); CardRow(o.battlefield, 70.dp, g, false, look) }
                    "ZONES" -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { Zones(ui, look, size = 12.sp) }
                    else -> g.log.takeLast(25).reversed().forEach { SkinText(it.description, look, 13.sp, if (it.isYours == true) look.text else look.muted) }
                }
            }
            Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SkinButton("UNDO", look, { g.undo() }, Modifier.weight(1f), enabled = g.canUndo)
                SkinButton("COMMANDER", look, ui.openCommander, Modifier.weight(1f), glow = g.commanderGlow, outline = look.accent)
            }
            PassButton(g, look, Modifier.fillMaxWidth().padding(16.dp, 10.dp).height(56.dp), RoundedCornerShape(18.dp), 16.sp)
            Row(Modifier.fillMaxWidth().background(Color(0xFF17181C)).padding(6.dp)) {
                listOf("HAND" to "HAND ${g.hand.size}", "BOARD" to "BOARD", "OPPONENT" to "OPPONENT", "ZONES" to "ZONES", "LOG" to "LOG").forEach { (k, label) ->
                    SkinButton(label, look, { tab = k }, Modifier.weight(1f), size = 10.sp, fill = Color.Transparent, outline = Color.Transparent,
                        ink = if (tab == k) look.accent else look.muted)
                }
            }
        }
    }
}

// ---------------------------------------------------------------- 10 · Storybook

@Composable
private fun SkinStorybook(ui: SkinUi) {
    val g = ui.g
    val ink = Color(0xFF1A1A1A)
    val look = SkinLook(Color(0xFFFFF8E7), Color.White, Color.White, Color(0xFF2B7BD6), ink, Color(0xFF6A6A6A), Color.White,
        FontFamily.SansSerif, 0.dp, border = 3.dp, line = ink)
    val panel = Modifier.border(3.dp, ink).background(Color.White).padding(10.dp)
    Screen(look) {
        Column(Modifier.fillMaxSize().padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                SkinText("CHAPTER ${g.snap.turnNumber}: ${g.phase}!", look, 22.sp, ink, FontWeight.Black,
                    Modifier.weight(1f).graphicsLayer { rotationZ = -1f }.border(3.dp, ink).background(Color(0xFFFFD23F)).padding(8.dp, 4.dp).chimeraPhaseStop(g.phase))
                Spacer(Modifier.width(8.dp)); SkinMenu(g, look)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Column(Modifier.weight(1f).then(panel)) { SkinText("YOU", look, 16.sp, weight = FontWeight.Black); SkinText("${g.me?.life ?: 0}", look, 32.sp, Color(0xFF2B7BD6), FontWeight.Black) }
                g.opponents.take(2).forEach { o -> Column(Modifier.weight(1f).then(panel)) { SkinText(o.name.uppercase(), look, 16.sp, weight = FontWeight.Black, maxLines = 1); SkinText("${o.life}", look, 32.sp, Color(0xFFE2483D), FontWeight.Black) } }
            }
            Column(Modifier.fillMaxWidth().graphicsLayer { rotationZ = 0.6f }.then(panel), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                SkinText(g.log.lastOrNull()?.let { "Meanwhile… ${it.description}" } ?: "Our story begins…", look, 13.sp, weight = FontWeight.Bold, maxLines = 2)
                CardRow(g.opponents.flatMap { it.battlefield }, 54.dp, g, false, look, "The enemy waits…", 2f)
                CardRow(g.me?.battlefield.orEmpty(), 60.dp, g, true, look, "Your side of the page is empty.", 2f)
            }
            Column(Modifier.weight(1f).fillMaxWidth().then(panel), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SkinText("YOUR HAND · ${g.hand.size} · ${g.hand.count { g.castable(it) }} READY!", look, 17.sp, weight = FontWeight.Black)
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    g.hand.forEachIndexed { i, c -> SkinCard(c, 62.dp, g, true, Modifier.graphicsLayer { rotationZ = if (i % 2 == 0) -2.5f else 2.5f }, 2f, true, c.id == ui.selected?.id) { ui.pick(c) } }
                }
                Spacer(Modifier.weight(1f))
                val comic = look.copy(raised = Color.White)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { UndoActionsCast(ui, comic, RoundedCornerShape(0.dp), 15.sp) }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) { Zones(ui, comic, RoundedCornerShape(0.dp), 11.sp) }
                PassButton(g, look, Modifier.fillMaxWidth().height(58.dp).graphicsLayer { rotationZ = -1f }, RoundedCornerShape(0.dp), 22.sp,
                    g.passLabel + "!")
            }
        }
    }
}
