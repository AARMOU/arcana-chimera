@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.arcanatable

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import kotlin.math.floor
import kotlin.math.max

/**
 * The Terminal preset, reworked (owner, Nov 9: "the text doesn't fill to the end of my screen", "kinda confusing to
 * read", "multiple CLI type layouts ... including your original, as well as be able to change color and text size").
 *
 * Every line is laid out in columns measured from the real screen width and text size, so it reaches the right edge.
 * Five layouts (Shell = the original, cleaned up; Panes; Monitor; Tail; Retro), six colour themes (one custom),
 * a text size, scanlines and glow. Settings live in the terminal's menu and in Studio (Table panel). Every button
 * keeps its words. Colours always mean the same thing; the legend line says what.
 */
internal object ChimeraTerminalPrefs {
    val LAYOUTS = listOf("shell" to "Shell", "panes" to "Panes", "monitor" to "Monitor", "tail" to "Tail", "retro" to "Retro")
    val THEMES = listOf("green" to "Green", "amber" to "Amber", "ice" to "Ice blue", "white" to "White", "solarized" to "Solarized", "custom" to "Custom")
    val SWATCHES = listOf(0xFF7CF29A, 0xFFFFB000, 0xFF8FD8FF, 0xFFE8E8E8, 0xFFFF7AC6, 0xFFB79CFF, 0xFFFF6B5A, 0xFFF4E04D).map { Color(it) }
    const val MIN_SIZE = 9
    const val MAX_SIZE = 20

    private val prefs: SharedPreferences? by lazy {
        runCatching {
            val app = Class.forName("android.app.ActivityThread").getMethod("currentApplication").invoke(null) as Context
            app.getSharedPreferences("chimera_skin", Context.MODE_PRIVATE)
        }.getOrNull()
    }
    var layout by mutableStateOf(prefs?.getString("term_layout", "shell") ?: "shell"); private set
    var theme by mutableStateOf(prefs?.getString("term_theme", "green") ?: "green"); private set
    var custom by mutableStateOf(Color(prefs?.getInt("term_custom", 0xFFFF7AC6.toInt()) ?: 0xFFFF7AC6.toInt())); private set
    var size by mutableStateOf((prefs?.getInt("term_size", 13) ?: 13).coerceIn(MIN_SIZE, MAX_SIZE)); private set
    var scanlines by mutableStateOf(prefs?.getBoolean("term_scan", false) ?: false); private set
    var glow by mutableStateOf(prefs?.getBoolean("term_glow", false) ?: false); private set
    var settingsOpen by mutableStateOf(false)

    fun setLayout(v: String) { layout = v; prefs?.edit()?.putString("term_layout", v)?.apply() }
    fun setTheme(v: String) { theme = v; prefs?.edit()?.putString("term_theme", v)?.apply() }
    fun setCustom(v: Color) { custom = v; theme = "custom"; prefs?.edit()?.putInt("term_custom", v.toArgb())?.putString("term_theme", "custom")?.apply() }
    fun setSize(v: Int) { size = v.coerceIn(MIN_SIZE, MAX_SIZE); prefs?.edit()?.putInt("term_size", size)?.apply() }
    fun setScanlines(v: Boolean) { scanlines = v; prefs?.edit()?.putBoolean("term_scan", v)?.apply() }
    fun setGlow(v: Boolean) { glow = v; prefs?.edit()?.putBoolean("term_glow", v)?.apply() }

    /** bg, text, dim, hot = "you can act on this now", alt = "theirs", warn = danger/low life. */
    data class Palette(val bg: Color, val text: Color, val dim: Color, val hot: Color, val alt: Color, val warn: Color)

    fun palette(): Palette = when (theme) {
        "amber" -> Palette(Color(0xFF0B0700), Color(0xFFFFB000), Color(0xFF8A6000), Color(0xFFFFF0B0), Color(0xFFFF7A3D), Color(0xFFFF4D4D))
        "ice" -> Palette(Color(0xFF03080D), Color(0xFF8FD8FF), Color(0xFF3F6E8A), Color(0xFFE6FBFF), Color(0xFFB79CFF), Color(0xFFFF7A7A))
        "white" -> Palette(Color(0xFF0A0A0A), Color(0xFFE6E6E6), Color(0xFF7A7A7A), Color(0xFFFFE27A), Color(0xFF8FC8FF), Color(0xFFFF7A7A))
        "solarized" -> Palette(Color(0xFF002B36), Color(0xFF93A1A1), Color(0xFF586E75), Color(0xFFB58900), Color(0xFF268BD2), Color(0xFFDC322F))
        "custom" -> {
            val c = custom
            val hsv = FloatArray(3); android.graphics.Color.colorToHSV(c.toArgb(), hsv)
            val alt = Color(android.graphics.Color.HSVToColor(floatArrayOf((hsv[0] + 140f) % 360f, max(0.45f, hsv[1]), max(0.8f, hsv[2]))))
            val bg = Color(android.graphics.Color.HSVToColor(floatArrayOf(hsv[0], 0.5f, 0.05f)))
            Palette(bg, c, lerp(bg, c, 0.5f), lerp(c, Color.White, 0.55f), alt, Color(0xFFFF5A5A))
        }
        else -> Palette(Color(0xFF040904), Color(0xFF7CF29A), Color(0xFF3E8A55), Color(0xFFE8FF7A), Color(0xFF7AD7FF), Color(0xFFFF6B6B))
    }
}

/** Fixed-width text helpers. */
private fun fit(s: String, w: Int, right: Boolean = false): String {
    if (w <= 0) return ""
    val t = if (s.length > w) (if (w == 1) "…" else s.take(w - 1) + "…") else s
    return if (right) t.padStart(w) else t.padEnd(w)
}

private fun rule(title: String, cols: Int, ch: Char = '─'): String {
    val head = "$ch$ch $title "
    return if (head.length >= cols) head.take(cols) else head + ch.toString().repeat(cols - head.length)
}

private fun bar(value: Int, of: Int, w: Int): String {
    if (w < 3) return ""
    val inner = w - 2
    val n = ((value.coerceAtLeast(0).toFloat() / of.coerceAtLeast(1)) * inner).toInt().coerceIn(0, inner)
    return "[" + "█".repeat(n) + "·".repeat(inner - n) + "]"
}

private fun state(c: GameCard): String = when {
    c.combatRole == "ATTACKING" -> "ATK"
    c.combatRole == "BLOCKING" -> "BLK"
    c.tapped -> "TAP"
    c.summoningSick && c.type.contains("Creature", true) -> "NEW"
    else -> ""
}

private fun pt(c: GameCard) = c.power?.let { "$it/${c.toughness}" }.orEmpty()

/** One line of terminal text. */
@Composable
private fun TLine(text: String, color: Color, modifier: Modifier = Modifier, bold: Boolean = false, bg: Color = Color.Transparent) {
    val p = ChimeraTerminalPrefs
    Text(text, color = color, fontSize = p.size.sp, fontFamily = FontFamily.Monospace, fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal,
        maxLines = 1, softWrap = false, overflow = TextOverflow.Clip, lineHeight = (p.size * 1.25f).sp,
        style = if (p.glow) TextStyle(shadow = Shadow(color.copy(alpha = 0.75f), Offset.Zero, p.size * 0.7f)) else TextStyle.Default,
        modifier = modifier.background(bg))
}

/** A tappable line (board, hand, log): a little taller so a finger can hit it. */
@Composable
private fun TTap(text: String, color: Color, onTap: () -> Unit, onHold: () -> Unit, bg: Color = Color.Transparent, tag: String? = null) {
    TLine(text, color, Modifier.fillMaxWidth().combinedClickable(onClick = onTap, onLongClick = onHold).padding(vertical = 3.dp)
        .then(if (tag != null) Modifier.testTag(tag) else Modifier), bg = bg)
}

/** A button that keeps its words: "[undo]". */
@Composable
private fun TButton(label: String, color: Color, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true,
    glow: Boolean = false, onHold: (() -> Unit)? = null, fill: Color = Color.Transparent) {
    val haptic = LocalHapticFeedback.current
    Box(modifier.heightIn(min = 44.dp).chimeraGlow(glow, RectangleShape).background(fill).border(1.dp, color.copy(alpha = if (enabled) 0.7f else 0.25f))
        .combinedClickable(enabled = enabled, onClick = onClick, onLongClick = onHold?.let { h -> { haptic.performHapticFeedback(HapticFeedbackType.LongPress); h() } })
        .padding(horizontal = 4.dp, vertical = 6.dp), contentAlignment = Alignment.Center) {
        Text(label, color = if (enabled) color else color.copy(alpha = 0.4f), fontSize = (ChimeraTerminalPrefs.size - 1).coerceAtLeast(9).sp,
            fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

/** Characters per line for a width, from the real monospace glyph width at the chosen size. */
@Composable
private fun rememberCharWidth(): Float {
    val measurer = rememberTextMeasurer()
    val size = ChimeraTerminalPrefs.size
    return remember(size) {
        measurer.measure("M".repeat(40), TextStyle(fontFamily = FontFamily.Monospace, fontSize = size.sp)).size.width / 40f
    }
}

/** Columns that fit [content]'s width; content receives the column count. */
@Composable
private fun Cols(modifier: Modifier, content: @Composable ColumnScope.(Int) -> Unit) {
    val charPx = rememberCharWidth()
    val density = LocalDensity.current
    BoxWithConstraints(modifier) {
        val cols = max(16, floor(with(density) { maxWidth.toPx() } / charPx).toInt())
        Column(Modifier.fillMaxWidth()) { content(cols) }
    }
}

private class TermCtx(val ui: SkinUi, val pal: ChimeraTerminalPrefs.Palette) {
    val g get() = ui.g
    fun colorOf(c: GameCard, mine: Boolean): Color = when {
        mine && c.id in g.playable && !g.busy -> pal.hot
        c.tapped -> pal.dim
        mine -> pal.text
        else -> pal.alt
    }
    fun mana(): String = listOf("white" to "W", "blue" to "U", "black" to "B", "red" to "R", "green" to "G", "colorless" to "C")
        .joinToString(" ") { (k, s) -> "$s${g.me?.manaPool?.get(k) ?: 0}" }
}

// ---------------------------------------------------------------- the shared blocks

@Composable
private fun ColumnScope.StatusBlock(c: TermCtx, cols: Int, upper: Boolean = false) {
    val g = c.g
    fun u(s: String) = if (upper) s.uppercase() else s
    val turn = "turn ${g.snap.turnNumber} · ${g.step.lowercase()} · " + if (g.myTurn) "YOUR TURN" else "their turn"
    TLine(fit(u(turn), cols), if (g.myTurn) c.pal.hot else c.pal.text, bold = true)
    val nameW = (cols - 6) / 2
    TLine(fit(u("you"), 8) + fit("${g.me?.life ?: 0} life", 9) + fit(u("hand ${g.hand.size}  lib ${g.me?.library?.size ?: 0}"), cols - 17), c.pal.text)
    g.opponents.forEach { o ->
        val low = o.life <= 5
        TLine(fit(u(o.name.lowercase()), 8.coerceAtMost(nameW)) + fit("${o.life} life", 9) + fit(u("hand ${o.hand.size}  lib ${o.library.size}"), cols - 17),
            if (low) c.pal.warn else c.pal.alt)
    }
    TLine(fit(u("mana  ") + c.mana() + if (g.stackCount > 0) u("   stack ${g.stackCount}") else "", cols), c.pal.text)
}

@Composable
private fun PhaseRow(c: TermCtx) {
    val g = c.g
    FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        ChimeraPhaseStops.PHASES.forEach { p ->
            val on = p == g.phase
            TLine("[${p.lowercase()}]", if (on) c.pal.bg else c.pal.dim, Modifier.chimeraPhaseStop(p).padding(vertical = 4.dp), bold = on,
                bg = if (on) c.pal.text else Color.Transparent)
        }
    }
}

@Composable
private fun ColumnScope.Legend(c: TermCtx, cols: Int) {
    val pal = c.pal
    FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        TLine("■ can act", pal.hot); TLine("■ yours", pal.text); TLine("■ theirs", pal.alt); TLine("■ tapped", pal.dim)
    }
    TLine(fit("TAP tapped · ATK/BLK in combat · NEW summoning sick · hold any line to look · hold PASS = auto-pass", cols), pal.dim)
}

/** The board of one player, as a table: name | power/toughness | state. */
@Composable
private fun ColumnScope.Board(c: TermCtx, cols: Int, title: String, cards: List<GameCard>, mine: Boolean, upper: Boolean = false) {
    val g = c.g
    TLine(rule(if (upper) title.uppercase() else title, cols), c.pal.dim, bold = true)
    if (cards.isEmpty()) { TLine(fit("  (nothing)", cols), c.pal.dim); return }
    val nameW = cols - 6 - 4 - 4
    val sorted = cards.sortedWith(compareBy({ it.type.contains("Land", true) }, { it.name }))
    sorted.forEach { card ->
        val counters = card.counters.filterValues { it > 0 }.entries.joinToString(" ") { "+${it.value}" }
        val name = card.name + if (counters.isNotEmpty()) " $counters" else ""
        val line = "  " + fit(if (upper) name.uppercase() else name, nameW) + " " + fit(pt(card), 6, right = true) + " " + fit(state(card), 3)
        TTap(line, c.colorOf(card, mine), { g.tapPermanent(card, mine) }, { g.preview(card) })
    }
}

@Composable
private fun ColumnScope.Hand(c: TermCtx, cols: Int, upper: Boolean = false) {
    val g = c.g
    TLine(rule(if (upper) "YOUR HAND · ${g.hand.size}" else "your hand · ${g.hand.size}", cols), c.pal.dim, bold = true)
    if (g.hand.isEmpty()) TLine(fit("  (empty)", cols), c.pal.dim)
    g.hand.forEachIndexed { i, card ->
        val can = g.castable(card)
        val sel = card.id == c.ui.selected?.id
        val cost = card.manaCost.orEmpty()
        val head = "${if (sel) ">" else " "}${(i + 1).toString().padStart(2)} ${if (can) "*" else " "} "
        val line = head + fit(if (upper) card.name.uppercase() else card.name, cols - head.length - cost.length.coerceAtMost(14) - 1) + " " + fit(cost, cost.length.coerceAtMost(14), right = true)
        TTap(line, if (sel) c.pal.bg else if (can) c.pal.hot else c.pal.text, { c.ui.pick(card) }, { g.preview(card) },
            bg = if (sel) c.pal.text else Color.Transparent, tag = "terminal-hand-$i")
    }
    val s = c.ui.selected
    TLine(fit(if (s != null) "selected: ${s.name} ${s.manaCost.orEmpty()} — tap [cast]" else "selected: none · tap a card above, * = you can cast it now", cols),
        if (s != null) c.pal.hot else c.pal.dim)
}

@Composable
private fun ColumnScope.Log(c: TermCtx, cols: Int, lines: Int, upper: Boolean = false) {
    TLine(rule(if (upper) "LOG" else "log", cols), c.pal.dim, bold = true)
    val shown = c.g.log.takeLast(lines)
    if (shown.isEmpty()) TLine(fit("  (quiet)", cols), c.pal.dim)
    shown.forEach { e ->
        TTap(fit(if (upper) e.description.uppercase() else e.description, cols), if (e.isYours == true) c.pal.text else if (e.isYours == false) c.pal.alt else c.pal.dim,
            { c.g.log() }, { c.g.log() })
    }
}

@Composable
private fun Buttons(c: TermCtx, upper: Boolean = false, fkeys: Boolean = false) {
    val g = c.g; val pal = c.pal
    fun l(key: String, s: String) = if (fkeys) "$key ${s.uppercase()}" else if (upper) "[${s.uppercase()}]" else "[$s]"
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            TButton(l("F1", "undo"), pal.text, { g.undo() }, Modifier.weight(1f), enabled = g.canUndo)
            TButton(l("F2", "actions ${g.actions.size}"), pal.text, { g.openActions() }, Modifier.weight(1.3f), enabled = g.actions.isNotEmpty())
            TButton(l("F3", "cast"), pal.hot, { g.cast(c.ui.selected) }, Modifier.weight(1f), enabled = !g.busy)
            TButton(l("F4", "commander"), pal.hot, c.ui.openCommander, Modifier.weight(1.4f), glow = g.commanderGlow)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            TButton(l("F5", "library ${g.me?.library?.size ?: 0}"), pal.text, {}, Modifier.weight(1.1f))
            TButton(l("F6", "grave ${g.me?.graveyard?.size ?: 0}"), pal.text, { g.graveyard() }, Modifier.weight(1f))
            TButton(l("F7", "exile ${g.me?.exile?.size ?: 0}"), pal.text, { g.exile() }, Modifier.weight(1f))
            TButton(l("F8", "stack ${g.stackCount}"), pal.text, { g.stack() }, Modifier.weight(1f))
        }
        val pass = g.passLabel.lowercase()
        TButton(if (fkeys) "F10 ${pass.uppercase()}  (HOLD: AUTO-PASS)" else "$ ${if (upper) pass.uppercase() else pass}  ▌  (hold: auto-pass)", pal.bg,
            { g.pass() }, Modifier.fillMaxWidth().heightIn(min = 52.dp).testTag("terminal-pass"), enabled = g.canPass, glow = g.t.argentumCanRespond,
            onHold = { g.holdPass() }, fill = pal.text)
    }
}

@Composable
private fun TopBar(c: TermCtx, prompt: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        TLine(prompt, c.pal.dim, Modifier.weight(1f))
        TermMenu(c)
    }
}

/** The menu: everything every layout's menu has, plus the terminal settings. */
@Composable
private fun TermMenu(c: TermCtx) {
    val g = c.g
    var open by remember { mutableStateOf(false) }
    Box {
        TButton("[menu]", c.pal.text, { open = true }, Modifier.widthIn(min = 64.dp))
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(text = { Text("Terminal settings…") }, onClick = { open = false; ChimeraTerminalPrefs.settingsOpen = true })
            DropdownMenuItem(text = { Text("Stack · ${g.stackCount}") }, onClick = { open = false; g.stack() })
            DropdownMenuItem(text = { Text("Game log") }, onClick = { open = false; g.log() })
            DropdownMenuItem(text = { Text(if (g.autoPassing) "Stop auto-pass" else "Pass to my turn") }, onClick = { open = false; g.holdPass() })
            DropdownMenuItem(text = { Text("Classic screen") }, onClick = { open = false; ChimeraSkins.set("") })
            DropdownMenuItem(text = { Text("Options") }, onClick = { open = false; g.t.optionsOpen = true })
            DropdownMenuItem(text = { Text("Concede", color = Color(0xFFE9978B)) }, onClick = { open = false; ChimeraConcede.asking = true })
            DropdownMenuItem(text = { Text("Leave game…") }, onClick = { open = false; g.t.argentumLeaveConfirm = true })
        }
    }
}

// ---------------------------------------------------------------- the screen

@Composable
internal fun ChimeraTerminal(ui: SkinUi) {
    val pal = ChimeraTerminalPrefs.palette()
    val c = TermCtx(ui, pal)
    val retro = ChimeraTerminalPrefs.layout == "retro"
    Box(Modifier.fillMaxSize().background(if (retro) pal.dim else pal.bg).windowInsetsPadding(WindowInsets.safeDrawing).testTag("terminal-${ChimeraTerminalPrefs.layout}")) {
        Box(Modifier.fillMaxSize().then(if (retro) Modifier.padding(10.dp).background(pal.bg) else Modifier).padding(horizontal = 8.dp, vertical = 6.dp)) {
            when (ChimeraTerminalPrefs.layout) {
                "panes" -> TermPanes(c)
                "monitor" -> TermMonitor(c)
                "tail" -> TermTail(c)
                "retro" -> TermShell(c, retro = true)
                else -> TermShell(c, retro = false)
            }
        }
        if (ChimeraTerminalPrefs.scanlines) Canvas(Modifier.fillMaxSize()) {
            val step = 3.dp.toPx()
            var y = 0f
            while (y < size.height) { drawLine(Color.Black.copy(alpha = 0.22f), Offset(0f, y), Offset(size.width, y), 1f); y += step }
        }
    }
    if (ChimeraTerminalPrefs.settingsOpen) ChimeraTerminalSettings { ChimeraTerminalPrefs.settingsOpen = false }
}

/** Shell (the original, cleaned up) and Retro (the same in capitals, framed, "READY."). */
@Composable
private fun TermShell(c: TermCtx, retro: Boolean) {
    val g = c.g
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        TopBar(c, if (retro) "**** ARCANA BASIC · FORGE ****" else "arcana@forge:~/game$")
        Cols(Modifier.fillMaxWidth()) { cols ->
            if (retro) TLine(fit("READY.", cols), c.pal.text)
            StatusBlock(c, cols, retro)
        }
        PhaseRow(c)
        Cols(Modifier.weight(1f).verticalScroll(rememberScrollState())) { cols ->
            g.opponents.forEach { o -> Board(c, cols, "${o.name} · board", o.battlefield, mine = false, upper = retro) }
            Board(c, cols, "your board", g.me?.battlefield.orEmpty(), mine = true, upper = retro)
            Hand(c, cols, retro)
            Log(c, cols, 5, retro)
            Legend(c, cols)
            if (retro) TLine("READY.█", c.pal.text)
        }
        Buttons(c, upper = retro)
    }
}

@Composable
private fun Pane(c: TermCtx, title: String, modifier: Modifier, body: @Composable ColumnScope.(Int) -> Unit) {
    Column(modifier.border(1.dp, c.pal.dim).padding(4.dp)) {
        TLine(" $title ", c.pal.bg, bold = true, bg = c.pal.dim)
        Cols(Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState())) { cols -> body(cols) }
    }
}

/** Panes: tmux-style boxes; side by side when the screen is wide. */
@Composable
private fun TermPanes(c: TermCtx) {
    val g = c.g
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val wide = maxWidth > maxHeight
        Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            TopBar(c, "[0] arcana  1:board  2:hand  3:log")
            Cols(Modifier.fillMaxWidth()) { cols -> StatusBlock(c, cols) }
            PhaseRow(c)
            val them: @Composable ColumnScope.(Int) -> Unit = { cols -> g.opponents.forEach { o -> Board(c, cols, o.name, o.battlefield, mine = false) } }
            val mine: @Composable ColumnScope.(Int) -> Unit = { cols -> Board(c, cols, "you", g.me?.battlefield.orEmpty(), mine = true) }
            val hand: @Composable ColumnScope.(Int) -> Unit = { cols -> Hand(c, cols) }
            val log: @Composable ColumnScope.(Int) -> Unit = { cols -> Log(c, cols, 12); Legend(c, cols) }
            if (wide) Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Pane(c, "1 · them", Modifier.weight(1f).fillMaxWidth(), them); Pane(c, "1 · you", Modifier.weight(1f).fillMaxWidth(), mine)
                }
                Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Pane(c, "2 · hand", Modifier.weight(1.2f).fillMaxWidth(), hand); Pane(c, "3 · log", Modifier.weight(0.8f).fillMaxWidth(), log)
                }
            } else Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Pane(c, "1 · them", Modifier.weight(1f).fillMaxWidth(), them)
                Pane(c, "1 · you", Modifier.weight(1f).fillMaxWidth(), mine)
                Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    Pane(c, "2 · hand", Modifier.weight(1.3f).fillMaxHeight(), hand); Pane(c, "3 · log", Modifier.weight(1f).fillMaxHeight(), log)
                }
            }
            Buttons(c)
        }
    }
}

/** Monitor: an htop-style view: life and hand bars, a process table of permanents, function keys. */
@Composable
private fun TermMonitor(c: TermCtx) {
    val g = c.g
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        TopBar(c, "arcana-top · turn ${g.snap.turnNumber} · ${g.step.lowercase()}")
        Cols(Modifier.fillMaxWidth()) { cols ->
            val labelW = 9; val numW = 8
            val barW = (cols - labelW - numW).coerceAtLeast(6)
            TLine(fit("you", labelW) + bar(g.me?.life ?: 0, 40, barW) + fit("${g.me?.life ?: 0} hp", numW, right = true), if ((g.me?.life ?: 0) <= 5) c.pal.warn else c.pal.text)
            g.opponents.forEach { o -> TLine(fit(o.name.lowercase(), labelW) + bar(o.life, 40, barW) + fit("${o.life} hp", numW, right = true), if (o.life <= 5) c.pal.warn else c.pal.alt) }
            TLine(fit("hand", labelW) + bar(g.hand.size, 7, barW) + fit("${g.hand.size}", numW, right = true), c.pal.text)
            TLine(fit("mana   " + c.mana(), cols), c.pal.text)
            TLine(fit("tasks: ${g.me?.battlefield?.size ?: 0} yours, ${g.opponents.sumOf { it.battlefield.size }} theirs · stack ${g.stackCount} · " + if (g.myTurn) "YOUR TURN" else "their turn", cols),
                if (g.myTurn) c.pal.hot else c.pal.dim)
        }
        PhaseRow(c)
        Cols(Modifier.weight(1f).verticalScroll(rememberScrollState())) { cols ->
            val nameW = cols - 4 - 6 - 7 - 4 - 3
            TLine(fit(" PID", 4) + " " + fit("OWNER", 6) + " " + fit("NAME", nameW) + " " + fit("P/T", 6, right = true) + " " + fit("ST", 3), c.pal.bg, bold = true, bg = c.pal.text)
            var pid = 1
            val rows = g.opponents.flatMap { o -> o.battlefield.map { o.name.lowercase() to it } } + g.me?.battlefield.orEmpty().map { "you" to it }
            rows.forEach { (who, card) ->
                val mine = who == "you"
                val line = fit((pid++).toString(), 4, right = true) + " " + fit(who, 6) + " " + fit(card.name, nameW) + " " + fit(pt(card), 6, right = true) + " " + fit(state(card), 3)
                TTap(line, c.colorOf(card, mine), { g.tapPermanent(card, mine) }, { g.preview(card) })
            }
            Hand(c, cols)
            Legend(c, cols)
        }
        Buttons(c, fkeys = true)
    }
}

/** Tail: the game log as a live feed (tail -f), with the status on top and the hand underneath. */
@Composable
private fun TermTail(c: TermCtx) {
    val g = c.g
    val scroll = rememberScrollState()
    LaunchedEffect(g.log.size) { scroll.scrollTo(scroll.maxValue) }
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        TopBar(c, "$ tail -f game.log")
        Cols(Modifier.fillMaxWidth()) { cols -> StatusBlock(c, cols) }
        PhaseRow(c)
        Cols(Modifier.weight(1.3f).verticalScroll(scroll)) { cols ->
            g.log.takeLast(200).forEach { e ->
                val who = when (e.isYours) { true -> "you  "; false -> "them "; else -> "game " }
                TTap(fit(who + e.description, cols), if (e.isYours == true) c.pal.text else if (e.isYours == false) c.pal.alt else c.pal.dim, { g.log() }, { g.log() })
            }
            TLine("█", c.pal.text)
        }
        Cols(Modifier.weight(1f).verticalScroll(rememberScrollState())) { cols ->
            Board(c, cols, "your board", g.me?.battlefield.orEmpty(), mine = true)
            g.opponents.forEach { o -> Board(c, cols, "${o.name} · board", o.battlefield, mine = false) }
            Hand(c, cols)
            Legend(c, cols)
        }
        Buttons(c)
    }
}

// ---------------------------------------------------------------- settings (terminal menu and Studio)

@Composable
private fun TLabel(s: String) = Text(s, color = Color(0xFF9DB0AA), fontSize = 12.sp, fontWeight = FontWeight.Bold)

@Composable
private fun TChip(s: String, on: Boolean, tag: String, click: () -> Unit) = Text(s, color = if (on) Color(0xFF0B0F0E) else Color.White, fontSize = 13.sp,
    fontWeight = FontWeight.Bold, modifier = Modifier.clip(RoundedCornerShape(20.dp)).background(if (on) Color(0xFF89E3B0) else Color(0xFF26302D))
        .clickable(onClick = click).heightIn(min = 40.dp).padding(horizontal = 14.dp, vertical = 10.dp).testTag(tag))

@Composable
internal fun ChimeraTerminalSettings(onDismiss: () -> Unit) {
    val p = ChimeraTerminalPrefs
    val pal = p.palette()
    Dialog(onDismissRequest = onDismiss) {
        Column(Modifier.clip(RoundedCornerShape(16.dp)).background(Color(0xFF151B1A)).border(1.dp, Color(0xFF33433E), RoundedCornerShape(16.dp))
            .verticalScroll(rememberScrollState()).padding(16.dp).testTag("terminal-settings"), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Terminal settings", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Black)
            TLabel("LAYOUT")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                p.LAYOUTS.forEach { (id, name) -> TChip(name, p.layout == id, "terminal-layout-$id") { p.setLayout(id) } }
            }
            TLabel("COLOURS")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                p.THEMES.forEach { (id, name) -> TChip(name, p.theme == id, "terminal-theme-$id") { p.setTheme(id) } }
            }
            if (p.theme == "custom") FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                p.SWATCHES.forEachIndexed { i, sw -> Box(Modifier.size(40.dp).clip(CircleShape).background(sw)
                    .border(if (p.custom == sw) 3.dp else 0.dp, Color.White, CircleShape).clickable { p.setCustom(sw) }.testTag("terminal-swatch-$i")) }
            }
            TLabel("TEXT SIZE")
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TChip("A−", false, "terminal-size-down") { p.setSize(p.size - 1) }
                Text("${p.size} sp", color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                TChip("A+", false, "terminal-size-up") { p.setSize(p.size + 1) }
            }
            Row(verticalAlignment = Alignment.CenterVertically) { Text("Scanlines", color = Color.White, modifier = Modifier.weight(1f)); Switch(p.scanlines, { p.setScanlines(it) }) }
            Row(verticalAlignment = Alignment.CenterVertically) { Text("Glow", color = Color.White, modifier = Modifier.weight(1f)); Switch(p.glow, { p.setGlow(it) }) }
            // A sample in the chosen look.
            Column(Modifier.fillMaxWidth().background(pal.bg).border(1.dp, pal.dim).padding(8.dp)) {
                TLine("arcana@forge:~/game$ sample", pal.dim)
                TLine("■ can act  ", pal.hot); TLine("■ yours", pal.text); TLine("■ theirs", pal.alt); TLine("■ tapped", pal.dim)
            }
            Text("Done", color = Color(0xFF89E3B0), fontWeight = FontWeight.Black, modifier = Modifier.align(Alignment.End).clickable(onClick = onDismiss)
                .heightIn(min = 44.dp).padding(horizontal = 12.dp, vertical = 12.dp))
        }
    }
}

/** Studio (Table panel): the terminal's settings, so they can be set without a game running. */
@Composable
internal fun ChimeraTerminalStudioChip() {
    var open by remember { mutableStateOf(false) }
    StudioChip("Terminal settings…", true, "chimera-terminal-settings") { open = true }
    if (open) ChimeraTerminalSettings { open = false }
}
