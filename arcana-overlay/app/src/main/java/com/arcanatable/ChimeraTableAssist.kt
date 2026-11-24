package com.arcanatable

// CHIMERA OVERLAY (not Arcana source). Owner, Nov 23 batch: auto-pass choices, pop-ups when something happens to your
// permanents, the Catalogue opening hand, following the active (and defending) player on the table, and undo's count.

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import org.json.JSONObject

/** One saved on/off setting, read by the screens as Compose state. */
internal class ChimeraFlag(val key: String, val default: Boolean) {
    var value by mutableStateOf(default)
    fun set(on: Boolean) { value = on; ChimeraAssist.save(key, on) }
}

internal object ChimeraAssist {
    private var prefs: SharedPreferences? = null

    // Auto-pass (the hand's ⋮ menu)
    val skipWhenCantRespond = ChimeraFlag("skip_cant_respond", false)
    val stopAttacked = ChimeraFlag("stop_attacked", true)
    val stopTargeted = ChimeraFlag("stop_targeted", true)
    val stopSpellCast = ChimeraFlag("stop_spell_cast", false)

    // Pop-ups (Options)
    val popups = ChimeraFlag("popups", true)
    val popTargeted = ChimeraFlag("pop_targeted", true)
    val popRemoved = ChimeraFlag("pop_removed", true)
    val popControl = ChimeraFlag("pop_control", true)
    val popDamaged = ChimeraFlag("pop_damaged", true)
    val popTapped = ChimeraFlag("pop_tapped", true)
    val popOpponents = ChimeraFlag("pop_opponents", false)

    // Table (Options)
    val expandActive = ChimeraFlag("expand_active", true)
    val expandDefender = ChimeraFlag("expand_defender", true)

    // Studio › Looks
    val catalogueOpeningHand = ChimeraFlag("opening_hand_catalogue", true)

    private val all = listOf(skipWhenCantRespond, stopAttacked, stopTargeted, stopSpellCast, popups, popTargeted, popRemoved,
        popControl, popDamaged, popTapped, popOpponents, expandActive, expandDefender, catalogueOpeningHand)

    /** The running auto-pass stops when the turn it started on ends (Pass to end of turn), not at your next turn. */
    @Volatile var toEndOfTurn = false
    /** Set by "Pass to end of turn" just before auto-pass starts; any other start clears it. */
    @Volatile var pendingEndOfTurn = false
    /** The turn auto-pass last declared "no blocks" on (once per turn, like declaring no attack). */
    @Volatile var autoBlockTurn = -1

    fun init(context: Context) {
        if (prefs != null) return
        val p = context.applicationContext.getSharedPreferences("chimera_table_assist", Context.MODE_PRIVATE)
        prefs = p
        all.forEach { it.value = p.getBoolean(it.key, it.default) }
    }

    fun save(key: String, on: Boolean) { prefs?.edit()?.putBoolean(key, on)?.apply() }

    /** True when every legal action is Pass, a mana ability, or something you can't afford. */
    fun nothingToDo(legal: List<JSONObject>): Boolean {
        var pass = false
        for (info in legal) {
            val type = info.optJSONObject("action")?.optString("type").orEmpty()
            when {
                type == "PassPriority" -> pass = true
                info.optBoolean("isManaAbility") -> Unit
                info.has("isAffordable") && !info.optBoolean("isAffordable", true) -> Unit
                else -> return false
            }
        }
        return pass
    }
}

/** Undo's label and the reason it can't be used, from Forge's latest table. */
internal object ChimeraUndoState {
    var label by mutableStateOf("UNDO")
    var why by mutableStateOf("")
    fun update(available: Boolean, steps: Int, reason: String) {
        label = if (available && steps > 0) "UNDO ($steps)" else "UNDO"
        why = if (available) "" else reason.trim()
    }
}

// ------------------------------------------------------------------ follow the active player (owner, Nov 23)

internal object ChimeraFollow {
    /** Forge's ids of the players being attacked right now. */
    var defenderIds by mutableStateOf<Set<String>>(emptySet())
    /** Scrolling or zooming the table pauses following until the next turn. */
    var pausedTurn by mutableIntStateOf(Int.MIN_VALUE)
    @Volatile var programmatic = false
    private val COMBAT = setOf("DECLARE_ATTACKERS", "DECLARE_BLOCKERS", "FIRST_STRIKE_COMBAT_DAMAGE", "COMBAT_DAMAGE")

    fun following(turn: Int) = ChimeraAssist.expandActive.value && pausedTurn != turn
    fun pause(turn: Int) { pausedTurn = turn }

    fun defenders(snapshot: ArgentumTableSnapshot?, players: List<TablePlayer>): List<Int> =
        if (!ChimeraAssist.expandDefender.value || snapshot == null || snapshot.stepKey !in COMBAT) emptyList()
        else players.indices.filter { players[it].id in defenderIds }

    /** The boards drawn full size: the active player's, and during an attack the defenders'. Empty = everyone full size. */
    fun expanded(snapshot: ArgentumTableSnapshot?, players: List<TablePlayer>, active: Int, turn: Int): Set<Int> =
        if (players.size < 2 || !following(turn)) emptySet()
        else (listOf(active) + defenders(snapshot, players)).filter { it in players.indices }.toSet()

    fun scrollTarget(snapshot: ArgentumTableSnapshot?, players: List<TablePlayer>, active: Int): Int =
        defenders(snapshot, players).firstOrNull() ?: active
}

// ------------------------------------------------------------------ permanent pop-ups (owner, Nov 23)

internal object ChimeraPopups {
    data class Popup(val key: Long, val card: GameCard, val seat: Int, val text: String, val at: Long)
    val items = mutableStateListOf<Popup>()
    private var next = 0L

    private fun add(card: GameCard, seat: Int, text: String) {
        items.add(Popup(next++, card, seat, text, System.currentTimeMillis()))
        while (items.size > 4) items.removeAt(0)
    }

    fun dismiss(p: Popup) { items.remove(p) }
    fun expire(now: Long = System.currentTimeMillis()) { items.removeAll { now - it.at > 5_000 } }
    fun clear() { items.clear() }

    /**
     * Compares two tables and reports what happened to your permanents (and, if chosen, opponents'): targeted,
     * removed, control changed, damaged or given counters, tapped or untapped. Something a player did to their own
     * permanents (their spell or ability went on or left the stack) is not reported.
     */
    fun observe(prev: ArgentumTableSnapshot?, next: ArgentumTableSnapshot, me: Int) {
        if (!ChimeraAssist.popups.value || prev == null || next.gameOver) return
        if (prev.players.size != next.players.size || prev.turnNumber == 0) return
        val nextStack = next.stack.map { it.id }.toSet()
        val prevStack = prev.stack.map { it.id }.toSet()
        val resolved = prev.stack.filter { it.id !in nextStack }
        val added = next.stack.filter { it.id !in prevStack }
        val seats = next.players.indices.filter { it == me || ChimeraAssist.popOpponents.value }
        for (i in seats) {
            val owner = next.players[i].name
            val whose = if (i == me) "Your" else "$owner's"
            val selfCaused = (resolved + added).any { it.controllerName == owner }
            val otherResolved = resolved.any { it.controllerName != owner }
            val before = prev.players[i].battlefield
            val after = next.players[i].battlefield
            val afterById = after.associateBy { it.id }
            if (ChimeraAssist.popTargeted.value) for (item in added) {
                if (item.controllerName == owner) continue
                val hit = item.targetIds.mapNotNull { tid -> next.serverCardIds.entries.firstOrNull { it.value == tid }?.key }
                    .mapNotNull { id -> afterById[id] }.distinctBy { it.id }
                hit.forEach { add(it, i, "${item.controllerName}'s ${item.name} targets $whose ${it.name}") }
            }
            for (card in before) {
                val now = afterById[card.id]
                if (now == null) {
                    val taker = next.players.indexOfFirst { p -> p.battlefield.any { it.id == card.id } }
                    if (taker >= 0 && taker != i) {
                        if (ChimeraAssist.popControl.value) add(card, i, "${next.players[taker].name} took control of $whose ${card.name}")
                        continue
                    }
                    if (!ChimeraAssist.popRemoved.value || selfCaused) continue
                    val where = when {
                        next.players.any { p -> p.graveyard.any { it.id == card.id } } -> "went to the graveyard"
                        next.players.any { p -> p.exile.any { it.id == card.id } } -> "was exiled"
                        next.players.any { p -> p.hand.any { it.id == card.id } } -> "was returned to its owner's hand"
                        else -> "left the battlefield"
                    }
                    add(card, i, "$whose ${card.name} $where")
                    continue
                }
                if (selfCaused) continue
                if (ChimeraAssist.popDamaged.value) {
                    if (now.damage > card.damage) add(now, i, "$whose ${card.name} was dealt ${now.damage - card.damage} damage")
                    val more = now.counters.filter { (k, v) -> v > (card.counters[k] ?: 0) }
                    if (more.isNotEmpty()) add(now, i, "$whose ${card.name} got " + more.entries.joinToString { (k, v) -> "${v - (card.counters[k] ?: 0)} $k" } + " counter" + if (more.values.sum() > 1) "s" else "")
                }
                if (ChimeraAssist.popTapped.value && otherResolved && now.tapped != card.tapped)
                    add(now, i, "$whose ${card.name} was ${if (now.tapped) "tapped" else "untapped"}")
            }
        }
    }
}

/** The pop-ups along the top of the table. Tap one to see the card; they fade by themselves and never stop the game. */
@Composable
internal fun TableController.ChimeraPermanentPopups() {
    val items = ChimeraPopups.items
    if (items.isEmpty()) return
    LaunchedEffect(items.size, items.firstOrNull()?.key) {
        while (ChimeraPopups.items.isNotEmpty()) { delay(500); ChimeraPopups.expire() }
    }
    Box(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).padding(top = 52.dp, start = 12.dp, end = 12.dp),
        contentAlignment = Alignment.TopCenter) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            items.toList().forEach { p ->
                Row(Modifier.widthIn(max = 440.dp).fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Panel.copy(alpha = 0.96f))
                    .border(1.dp, Mint.copy(alpha = 0.7f), RoundedCornerShape(12.dp))
                    .clickable { preview = CardPreview(p.seat, p.card, fromHand = false, canManipulate = false); ChimeraPopups.dismiss(p) }
                    .padding(horizontal = 10.dp, vertical = 6.dp).testTag("chimera-permanent-popup"),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    CardFace(p.card, Modifier.width(30.dp), onClick = { preview = CardPreview(p.seat, p.card, fromHand = false, canManipulate = false); ChimeraPopups.dismiss(p) },
                        onLongClick = {}, showCounters = false)
                    Text(p.text, color = SoftWhite, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f))
                    Text("✕", color = Muted, fontSize = 14.sp, modifier = Modifier.clickable { ChimeraPopups.dismiss(p) }.padding(6.dp))
                }
            }
        }
    }
}

// ------------------------------------------------------------------ hand menu: auto-pass (owner, Nov 23)

@Composable
internal fun ChimeraAutoPassMenu(autoPassing: Boolean, enabled: Boolean, onAutoPass: () -> Unit, close: () -> Unit) {
    HorizontalDivider()
    Text("AUTO-PASS", color = Mint, fontSize = 10.sp, fontWeight = FontWeight.Black, letterSpacing = 1.sp,
        modifier = Modifier.padding(start = 12.dp, top = 8.dp, bottom = 2.dp))
    if (autoPassing) DropdownMenuItem(text = { Text("Stop auto-pass") }, onClick = { close(); onAutoPass() })
    else {
        DropdownMenuItem(text = { Text("Pass to end of turn") }, enabled = enabled,
            onClick = { ChimeraAssist.pendingEndOfTurn = true; close(); onAutoPass() })
        DropdownMenuItem(text = { Text("Pass to my turn") }, enabled = enabled,
            onClick = { ChimeraAssist.pendingEndOfTurn = false; close(); onAutoPass() })
    }
    ChimeraCheckItem("Skip when I can't respond", ChimeraAssist.skipWhenCantRespond)
    Text("Always stop when", color = Muted, fontSize = 11.sp, modifier = Modifier.padding(start = 12.dp, top = 6.dp))
    ChimeraCheckItem("I'm attacked", ChimeraAssist.stopAttacked)
    ChimeraCheckItem("Something targets me or my permanents", ChimeraAssist.stopTargeted)
    ChimeraCheckItem("A spell is cast", ChimeraAssist.stopSpellCast)
}

@Composable
private fun ChimeraCheckItem(label: String, flag: ChimeraFlag) {
    DropdownMenuItem(text = { Text(label, fontSize = 13.sp) }, onClick = { flag.set(!flag.value) },
        leadingIcon = { Checkbox(checked = flag.value, onCheckedChange = null) })
}

// ------------------------------------------------------------------ Options rows (owner, Nov 23)

@Composable
internal fun ChimeraAssistSettingsRows() {
    SettingsToggle("Expand the active player on the table", "The table view shows whose turn it is full size and shrinks the others to fit, " +
        "scrolling to them. Scrolling or zooming the table pauses this until the next turn.", ChimeraAssist.expandActive.value, { ChimeraAssist.expandActive.set(it) })
    if (ChimeraAssist.expandActive.value) SettingsToggle("Also expand the player being attacked", "While attackers and blockers are declared and combat damage is dealt.",
        ChimeraAssist.expandDefender.value, { ChimeraAssist.expandDefender.set(it) })
    SettingsToggle("Pop-ups for your permanents", "A short note at the top when something happens to one of your permanents. Tap it to see the card. " +
        "Things you do to your own permanents aren't shown, and pop-ups never pause the game.", ChimeraAssist.popups.value, { ChimeraAssist.popups.set(it) })
    if (ChimeraAssist.popups.value) Column(Modifier.padding(start = 12.dp)) {
        SettingsToggle("Targeted", "A spell or ability targets one of them.", ChimeraAssist.popTargeted.value, { ChimeraAssist.popTargeted.set(it) })
        SettingsToggle("Removed", "Destroyed, exiled, sacrificed or returned to hand.", ChimeraAssist.popRemoved.value, { ChimeraAssist.popRemoved.set(it) })
        SettingsToggle("Control changed", "Another player takes control of it.", ChimeraAssist.popControl.value, { ChimeraAssist.popControl.set(it) })
        SettingsToggle("Damaged or given counters", "By another player's spell, ability or creature.", ChimeraAssist.popDamaged.value, { ChimeraAssist.popDamaged.set(it) })
        SettingsToggle("Tapped or untapped", "By another player's spell or ability.", ChimeraAssist.popTapped.value, { ChimeraAssist.popTapped.set(it) })
        SettingsToggle("Opponents' permanents too", "The same pop-ups for what happens to other players' permanents.", ChimeraAssist.popOpponents.value, { ChimeraAssist.popOpponents.set(it) })
    }
}

// ------------------------------------------------------------------ Catalogue opening hand (owner, Nov 23)

/**
 * One card large in the middle with its neighbours beside it. Tap a side card (or swipe) to bring it to the middle;
 * while choosing cards to put on the bottom, tap the middle card to choose it. Hold any card to see it large.
 */
@Composable
internal fun ChimeraOpeningCatalogue(modifier: Modifier, cards: List<GameCard>, selectable: Boolean, selection: Set<Int>,
                                     onToggle: (GameCard) -> Unit, needed: Int) {
    var center by remember(cards.map { it.id }) { mutableIntStateOf(0) }
    val c = center.coerceIn(0, (cards.size - 1).coerceAtLeast(0))
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        if (selectable) Text("${selection.size} of ${if (needed > 0) needed else selection.size} chosen", color = Mint, fontSize = 14.sp,
            fontWeight = FontWeight.Black, modifier = Modifier.padding(bottom = 6.dp).testTag("opening-hand-chosen"))
        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth().pointerInput(cards.size) {
            var drag = 0f
            detectHorizontalDragGestures(onDragStart = { drag = 0f }, onDragEnd = {
                if (drag < -60f) center = (center + 1).coerceAtMost(cards.size - 1)
                else if (drag > 60f) center = (center - 1).coerceAtLeast(0)
            }) { change, amount -> drag += amount; change.consume() }
        }) {
            if (cards.isEmpty()) return@BoxWithConstraints
            val big = minOf(maxWidth * 0.58f, maxHeight / 1.42f)
            val side = big * 0.66f
            val shift = big / 2 + side * 0.18f
            listOf(-1 to c - 1, 1 to c + 1).forEach { (dir, i) ->
                cards.getOrNull(i)?.let { card ->
                    Box(Modifier.align(Alignment.Center).offset(x = shift * dir).width(side).graphicsLayer { alpha = 0.72f }) {
                        CardFace(card, Modifier.width(side).testTag("opening-hand-side"), onClick = { center = i }, onLongClick = {},
                            showCounters = false, glow = card.id in selection)
                        if (card.id in selection) ChimeraBottomBadge(Modifier.align(Alignment.TopCenter))
                    }
                }
            }
            val card = cards[c]
            val chosen = card.id in selection
            Box(Modifier.align(Alignment.Center).width(big)) {
                CardFace(card, Modifier.width(big).testTag("opening-hand-card").tutorialTarget("opening-hand-card"),
                    onClick = { if (selectable) onToggle(card) }, onLongClick = {}, showCounters = false, glow = chosen)
                if (chosen) ChimeraBottomBadge(Modifier.align(Alignment.TopCenter))
            }
        }
        Row(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            cards.forEachIndexed { i, card ->
                Box(Modifier.size(if (i == c) 12.dp else 8.dp).clip(CircleShape)
                    .background(when { card.id in selection -> Mint; i == c -> SoftWhite; else -> Muted.copy(alpha = 0.6f) })
                    .clickable { center = i })
            }
        }
        Text("${c + 1} of ${cards.size} · tap a side card to bring it to the middle" + if (selectable) " · tap the middle card to put it on the bottom" else "",
            color = Muted, fontSize = 11.sp, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 6.dp).fillMaxWidth())
    }
}

@Composable
private fun ChimeraBottomBadge(modifier: Modifier) {
    Text("BOTTOM", color = Ink, fontSize = 10.sp, fontWeight = FontWeight.Black,
        modifier = modifier.padding(6.dp).clip(RoundedCornerShape(5.dp)).background(Mint).padding(horizontal = 7.dp, vertical = 2.dp))
}

/** Studio › Looks: the opening hand's style. */
@Composable
internal fun ChimeraOpeningHandLook() {
    Text("Opening hand", color = darkTableColors.text, fontSize = 13.sp, modifier = Modifier.padding(top = 6.dp))
    Row(verticalAlignment = Alignment.CenterVertically) {
        listOf(true to "Catalogue", false to "Grid").forEach { (catalogue, label) ->
            StudioChip(label, true, "chimera-opening-hand-${label.lowercase()}", selected = ChimeraAssist.catalogueOpeningHand.value == catalogue) {
                ChimeraAssist.catalogueOpeningHand.set(catalogue)
            }
        }
    }
}
