package com.arcanatable

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.arcanatable.engine.ChimeraPictures
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/*
 * Owner, Nov 10 ("Any cool features you'd like to see?"), chosen features:
 *  2. Coach: "What would the AI do?" (Settings toggle)
 *  3. Combat preview: lifelink gained and the attackers at risk, on top of Arcana's own preview
 *  4. Board summary: every opponent at a glance
 *  5. After a game: the opponents' decks and the cards that hurt you most (Settings toggle)
 *  6. Deck stats and goldfishing
 *  7. Game history and replays: wins and losses per deck and per opponent, and each game turn by turn
 */

/** The on/off settings for the chosen features (Settings › Gameplay). */
internal object ChimeraFeatureSettings {
    private val app: Context? by lazy {
        runCatching { Class.forName("android.app.ActivityThread").getMethod("currentApplication").invoke(null) as Context }.getOrNull()
    }
    val context: Context? get() = app
    private val prefs: SharedPreferences? by lazy { app?.getSharedPreferences("chimera_skin", Context.MODE_PRIVATE) }

    var coach by mutableStateOf(prefs?.getBoolean("coach", true) ?: true)
        private set
    var reveal by mutableStateOf(prefs?.getBoolean("reveal_decks", true) ?: true)
        private set
    var shortcuts by mutableStateOf(prefs?.getBoolean("table_shortcuts", true) ?: true)
        private set

    fun changeCoach(v: Boolean) { coach = v; prefs?.edit()?.putBoolean("coach", v)?.apply() }
    fun changeReveal(v: Boolean) { reveal = v; prefs?.edit()?.putBoolean("reveal_decks", v)?.apply() }
    fun changeShortcuts(v: Boolean) { shortcuts = v; prefs?.edit()?.putBoolean("table_shortcuts", v)?.apply() }
}

/** Which of the feature windows is open. */
internal object ChimeraFeatures {
    var boardOpen by mutableStateOf(false)
    var statsOpen by mutableStateOf(false)
    var historyOpen by mutableStateOf(false)
    var revealOpen by mutableStateOf(false)
}

/** Coach mode: the question goes to Forge, which plays your seat in a copy of the game and says what it would do. */
internal object ChimeraCoach {
    var open by mutableStateOf(false)
    var answer by mutableStateOf<String?>(null)
    var asked by mutableStateOf(0L)

    fun ask(t: TableController) {
        answer = null; open = true; asked = System.currentTimeMillis()
        if (!t.argentumSocket.send("coachRequest")) answer = "The coach needs a game in progress."
    }
}

/** What Forge revealed when the game ended: each AI's deck and the cards that dealt you the most damage. */
internal object ChimeraReveal {
    var data by mutableStateOf<JSONObject?>(null)

    fun decks(): List<JSONObject> = data?.optJSONArray("decks")?.let { a -> (0 until a.length()).mapNotNull { a.optJSONObject(it) } }.orEmpty()
    fun hurt(): List<Pair<String, Int>> = data?.optJSONArray("hurtYou")?.let { a ->
        (0 until a.length()).mapNotNull { a.optJSONObject(it) }.map { it.optString("source") to it.optInt("damage") }
    }.orEmpty()
}

// ---------------------------------------------------------------------------------------------------------------------
// Game history (7)

internal object ChimeraHistory {
    private var generation = -1
    private var lastTurn = -1
    private var started = 0L
    private var saved = false
    private val turns = LinkedHashMap<Int, JSONObject>()
    private var you = ""
    private var deck = ""
    private var opponents = JSONArray()
    private var lastLog: List<String> = emptyList()
    /** Bumped when a game is saved, so the history window reloads. */
    var revision by mutableIntStateOf(0)

    private fun dir(): File? = ChimeraFeatureSettings.context?.filesDir?.let { File(it, "chimera_history").apply { mkdirs() } }

    private fun cards(list: List<GameCard>) = JSONArray().apply {
        list.forEach { c -> put(if (c.isFaceDown) "Face-down card" else if (c.power != null && c.toughness != null) "${c.name} ${c.power}/${c.toughness}" else c.name) }
    }

    private fun playerJson(p: TablePlayer) = JSONObject()
        .put("name", p.name).put("commander", p.commanderName ?: JSONObject.NULL).put("life", p.life)
        .put("hand", p.hand.size).put("library", p.library.size).put("graveyard", p.graveyard.size).put("exile", p.exile.size)
        .put("lands", p.battlefield.count { it.type.contains("Land", true) })
        .put("untappedLands", p.battlefield.count { it.type.contains("Land", true) && !it.tapped })
        .put("board", cards(p.battlefield.filterNot { it.type.contains("Land", true) }))
        .put("eliminated", p.isEliminated)
        .put("commanderDamage", JSONObject(p.commanderDamageReceived.filterValues { it > 0 }))

    /** Called on every update of an Arcana-with-Forge game: keeps the latest picture of each turn. */
    fun observe(t: TableController) {
        if (!t.argentumEnabled || t.tutorialPracticeActive) return
        val snap = t.argentumSnapshot ?: return
        if (generation != t.gameGeneration || snap.turnNumber < lastTurn) {
            if (!saved && turns.size >= 2) save(null, unfinished = true)
            generation = t.gameGeneration; turns.clear(); saved = false; started = System.currentTimeMillis()
            ChimeraReveal.data = null
            val me = snap.players.getOrNull(snap.viewingPlayer)
            you = me?.name.orEmpty()
            deck = me?.commanderName ?: t.activeDeckId?.substringAfter(':').orEmpty()
            opponents = JSONArray().apply { snap.players.filter { it.id != me?.id }.forEach { put(JSONObject().put("name", it.name).put("commander", it.commanderName ?: JSONObject.NULL)) } }
        }
        lastTurn = snap.turnNumber
        if (saved) return
        val entry = turns.getOrPut(snap.turnNumber) { JSONObject().put("turn", snap.turnNumber).put("logFrom", snap.fullLog.size) }
        entry.put("active", snap.players.getOrNull(snap.activePlayer)?.name.orEmpty())
        entry.put("players", JSONArray().apply { snap.players.forEach { put(playerJson(it)) } })
        lastLog = snap.fullLog.map { it.description }
    }

    /** Called when the game ends (the game-over message has arrived). */
    fun finish(t: TableController) {
        if (saved || turns.isEmpty() || t.tutorialPracticeActive) return
        observe(t)
        save(t.argentumWon, unfinished = false, eliminated = t.argentumMatchUnfinished)
    }

    private fun save(won: Boolean?, unfinished: Boolean, eliminated: Boolean = false) {
        saved = true
        val folder = dir() ?: return
        val log = lastLog
        val list = turns.values.toList()
        val turnArray = JSONArray()
        list.forEachIndexed { i, e ->
            val from = e.optInt("logFrom").coerceIn(0, log.size)
            val to = (list.getOrNull(i + 1)?.optInt("logFrom") ?: log.size).coerceIn(from, log.size)
            turnArray.put(JSONObject(e.toString()).put("log", JSONArray(log.subList(from, to).takeLast(80))))
        }
        val result = when { unfinished -> "Unfinished"; eliminated || won == false -> "Loss"; won == true -> "Win"; else -> "Draw" }
        val record = JSONObject().put("date", started).put("seconds", (System.currentTimeMillis() - started) / 1000)
            .put("you", you).put("deck", deck).put("opponents", opponents).put("result", result)
            .put("turns", turnArray).put("reveal", ChimeraReveal.data ?: JSONObject())
        runCatching {
            File(folder, "game_$started.json").writeText(record.toString())
            folder.listFiles()?.sortedByDescending { it.name }?.drop(200)?.forEach { it.delete() }
        }
        revision++
    }

    fun load(): List<JSONObject> = dir()?.listFiles()?.filter { it.name.endsWith(".json") }?.sortedByDescending { it.name }
        ?.mapNotNull { f -> runCatching { JSONObject(f.readText()).put("file", f.name) }.getOrNull() }.orEmpty()

    fun delete(record: JSONObject) { dir()?.let { File(it, record.optString("file")).delete() }; revision++ }
}

// ---------------------------------------------------------------------------------------------------------------------
// Deck stats and goldfishing (6)

internal data class ChimeraDeckStats(
    val commander: String?,
    val total: Int,
    val lands: Int,
    val types: List<Pair<String, Int>>,
    val curve: List<Int>,
    val averageValue: Double,
    val pips: Map<Char, Int>,
    val sources: Map<Char, Int>,
    val rocks: Int,
    val landsByTurn: List<Double>,
    val commanderValue: Int?,
    val commanderTurn: Double?,
    val commanderOnCurve: Int?,
    val unknown: List<String>,
)

internal object ChimeraDeckMath {
    private val COLOURS = listOf('W', 'U', 'B', 'R', 'G')
    private val BASICS = mapOf("Plains" to 'W', "Island" to 'U', "Swamp" to 'B', "Mountain" to 'R', "Forest" to 'G')

    fun manaValue(cost: String?): Int {
        if (cost.isNullOrBlank()) return 0
        return Regex("\\{([^}]+)\\}").findAll(cost).sumOf { m ->
            val s = m.groupValues[1]
            when {
                s.toIntOrNull() != null -> s.toInt()
                s == "X" || s == "Y" || s == "Z" -> 0
                s.startsWith("2/") -> 2
                else -> 1
            }
        }
    }

    private fun pips(cost: String?): List<Char> = Regex("\\{([^}]+)\\}").findAll(cost.orEmpty())
        .flatMap { m -> m.groupValues[1].split('/').mapNotNull { p -> p.singleOrNull()?.takeIf { it in COLOURS } }.distinct() }.toList()

    private fun landColours(c: SupportedCard): Set<Char> {
        val out = HashSet<Char>()
        BASICS.forEach { (type, colour) -> if (c.typeLine.contains(type)) out += colour }
        val text = c.oracleText.orEmpty()
        if (text.contains("any color", true) || text.contains("any one color", true)) out += COLOURS
        Regex("[Aa]dd ([^.]*)").findAll(text).forEach { m -> pips(m.groupValues[1]).forEach { out += it } }
        return out
    }

    private fun isRamp(c: SupportedCard): Boolean {
        val text = c.oracleText.orEmpty()
        return !c.typeLine.contains("Land") && manaValue(c.manaCost) <= 3 && Regex("\\{T\\}[^:]*: Add").containsMatchIn(text) &&
            (c.typeLine.contains("Artifact") || c.typeLine.contains("Creature"))
    }

    fun compute(names: List<String>, commander: String?, catalog: SupportedCardCatalog?, games: Int = 1000): ChimeraDeckStats {
        val library = names.toMutableList().apply { commander?.let { cmd -> indexOfFirst { it.equals(cmd, true) }.takeIf { it >= 0 }?.let { removeAt(it) } } }
        val known = library.map { it to catalog?.find(it) }
        val unknown = known.filter { it.second == null }.map { it.first }.distinct()
        val cards = known.mapNotNull { it.second }
        val lands = cards.filter { it.typeLine.contains("Land") }
        val spells = cards.filterNot { it.typeLine.contains("Land") }
        val typeOrder = listOf("Creature", "Instant", "Sorcery", "Artifact", "Enchantment", "Planeswalker", "Battle", "Land")
        val types = typeOrder.map { type -> type to cards.count { it.typeLine.contains(type) } }.filter { it.second > 0 }
        val curve = MutableList(8) { 0 }
        spells.forEach { curve[manaValue(it.manaCost).coerceAtMost(7)]++ }
        val pipCount = COLOURS.associateWith { colour -> spells.sumOf { s -> pips(s.manaCost).count { it == colour } } }.filterValues { it > 0 }
        val sourceCount = COLOURS.associateWith { colour -> lands.count { colour in landColours(it) } }
        val commanderCard = commander?.let { catalog?.find(it) }
        val commanderValue = commanderCard?.let { manaValue(it.manaCost) }

        // Goldfish: shuffle, keep 7, draw each turn (not on turn 1), play a land a turn, cast cheap mana rocks and dorks.
        val maxTurns = 12
        val landTotals = DoubleArray(maxTurns)
        var castTurnSum = 0.0
        var castCount = 0
        var onCurve = 0
        val deckKinds = library.map { n -> catalog?.find(n).let { c -> when { c == null -> 0; c.typeLine.contains("Land") -> 1; isRamp(c) -> 2 + manaValue(c.manaCost); else -> 0 } } }
        val random = java.util.Random(42)
        repeat(games) {
            val order = deckKinds.shuffled(random)
            var next = 7
            val hand = order.take(7).toMutableList()
            var landsInPlay = 0
            var rocksInPlay = 0
            var cast: Int? = null
            for (turn in 1..maxTurns) {
                if (turn > 1 && next < order.size) hand += order[next++]
                hand.indexOf(1).takeIf { it >= 0 }?.let { hand.removeAt(it); landsInPlay++ }
                var mana = landsInPlay + rocksInPlay
                if (commanderValue != null && cast == null && mana >= commanderValue) cast = turn
                // Spend what's left on ramp, cheapest first; it's online from next turn.
                hand.filter { it >= 2 }.sorted().forEach { k ->
                    val cost = k - 2
                    if (cost <= mana) { mana -= cost; hand.remove(k); rocksInPlay++ }
                }
                landTotals[turn - 1] += landsInPlay.toDouble()
            }
            if (cast != null) { castTurnSum += cast; castCount++; if (commanderValue != null && cast <= commanderValue) onCurve++ }
        }
        return ChimeraDeckStats(
            commander = commander, total = names.size, lands = lands.size, types = types, curve = curve,
            averageValue = spells.map { manaValue(it.manaCost) }.average().takeIf { !it.isNaN() } ?: 0.0,
            pips = pipCount, sources = sourceCount, rocks = spells.count { isRamp(it) },
            landsByTurn = landTotals.map { it / games },
            commanderValue = commanderValue,
            commanderTurn = if (castCount > 0) castTurnSum / castCount else null,
            commanderOnCurve = if (commanderValue != null) (onCurve * 100 / games) else null,
            unknown = unknown,
        )
    }
}

// ---------------------------------------------------------------------------------------------------------------------
// The windows

@Composable
private fun ChimeraWindow(title: String, onDismiss: () -> Unit, tag: String, content: @Composable ColumnScope.() -> Unit) {
    val screen = LocalConfiguration.current
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(Modifier.widthIn(max = 620.dp).fillMaxWidth(0.94f).heightIn(max = (screen.screenHeightDp * 0.88f).dp)
            .clip(RoundedCornerShape(18.dp)).background(Panel).border(1.dp, PanelLight, RoundedCornerShape(18.dp)).testTag(tag)) {
            Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 6.dp, top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(title, color = SoftWhite, fontSize = 17.sp, fontWeight = FontWeight.Black, modifier = Modifier.weight(1f))
                Text("CLOSE", color = Mint, fontSize = 13.sp, fontWeight = FontWeight.Black,
                    modifier = Modifier.clip(RoundedCornerShape(10.dp)).clickable(onClick = onDismiss).padding(horizontal = 12.dp, vertical = 12.dp))
            }
            Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(start = 16.dp, end = 16.dp, bottom = 16.dp), content = content)
        }
    }
}

@Composable
private fun Heading(text: String) = Text(text.uppercase(), color = Muted, fontSize = 10.sp, fontWeight = FontWeight.Black, letterSpacing = 1.sp,
    modifier = Modifier.padding(top = 12.dp, bottom = 4.dp))

@Composable
private fun Line(text: String, color: Color = SoftWhite, size: Int = 13, bold: Boolean = false) =
    Text(text, color = color, fontSize = size.sp, fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal)

@Composable
private fun Chip(text: String, on: Boolean = false, onClick: () -> Unit) = Text(text, color = if (on) Ink else SoftWhite, fontSize = 12.sp, fontWeight = FontWeight.Bold,
    maxLines = 1, overflow = TextOverflow.Ellipsis,
    modifier = Modifier.clip(RoundedCornerShape(12.dp)).background(if (on) Mint else PanelLight).clickable(onClick = onClick)
        .heightIn(min = 36.dp).padding(horizontal = 12.dp, vertical = 9.dp))

/** Everything this file draws on the table: the shortcut buttons, the windows and the game history recorder. */
@Composable
internal fun TableController.ChimeraFeatureLayer() {
    val t = this
    LaunchedEffect(argentumSnapshot, gameGeneration) { ChimeraHistory.observe(t) }
    LaunchedEffect(gameEndedOnline) { if (gameEndedOnline) ChimeraHistory.finish(t) }

    val playing = argentumEnabled && gameInProgress && !homeOpen && !gameEndedOnline
    if (playing && ChimeraFeatureSettings.shortcuts && argentumPendingDecision == null && argentumBlockPlan == null &&
        argentumAttackPlan == null && !argentumMulliganPrompt && cardZoom.card == null) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.CenterStart) {
            Column(Modifier.padding(start = 4.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                ShortcutPill("BOARD", "chimera-board-pill") { ChimeraFeatures.boardOpen = true }
                if (ChimeraFeatureSettings.coach) ShortcutPill("COACH", "chimera-coach-pill") { ChimeraCoach.ask(t) }
            }
        }
    }
    if (ChimeraFeatures.boardOpen) BoardSummaryWindow()
    if (ChimeraCoach.open) CoachWindow()
    if (ChimeraFeatures.revealOpen) RevealWindow()
    if (ChimeraFeatures.statsOpen) DeckStatsWindow()
    if (ChimeraFeatures.historyOpen) HistoryWindow()
    if (ChimeraLegal.open) ChimeraLegalDialog()
}

@Composable
private fun ShortcutPill(label: String, tag: String, onClick: () -> Unit) = Text(label, color = SoftWhite, fontSize = 10.sp, fontWeight = FontWeight.Black,
    letterSpacing = 1.sp, modifier = Modifier.clip(RoundedCornerShape(12.dp)).background(Panel.copy(alpha = 0.82f))
        .border(1.dp, PanelLight, RoundedCornerShape(12.dp)).clickable(onClick = onClick).heightIn(min = 40.dp)
        .padding(horizontal = 9.dp, vertical = 12.dp).testTag(tag))

/** (4) Every opponent at a glance. */
@Composable
private fun TableController.BoardSummaryWindow() {
    val snap = argentumSnapshot
    ChimeraWindow("Board summary", { ChimeraFeatures.boardOpen = false }, "chimera-board-summary") {
        if (snap == null) { Line("No game in progress."); return@ChimeraWindow }
        val me = snap.players.getOrNull(snap.viewingPlayer)
        Line("Turn ${snap.turnNumber} · ${snap.players.getOrNull(snap.activePlayer)?.name.orEmpty()}'s ${snap.stepName.ifBlank { snap.phaseName }.lowercase()}", Muted, 12)
        (listOfNotNull(me) + snap.players.filter { it.id != me?.id }).forEach { p ->
            val mine = p.id == me?.id
            val creatures = p.battlefield.filter { it.type.contains("Creature", true) }
            val lands = p.battlefield.filter { it.type.contains("Land", true) }
            val power = creatures.sumOf { combatPower(it) }
            val biggest = creatures.maxByOrNull { combatPower(it) * 100 + (it.toughness?.toIntOrNull() ?: 0) }
            val others = p.battlefield.count { !it.type.contains("Creature", true) && !it.type.contains("Land", true) }
            Column(Modifier.fillMaxWidth().padding(top = 10.dp).clip(RoundedCornerShape(12.dp)).background(PanelLight.copy(alpha = 0.55f)).padding(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(if (mine) "${p.name} (you)" else p.name, color = if (mine) Mint else SoftWhite, fontSize = 15.sp, fontWeight = FontWeight.Black, modifier = Modifier.weight(1f))
                    Text(if (p.isEliminated) "OUT" else "${p.life} life", color = if (p.isEliminated) Muted else if (p.life <= 10) Color(0xFFFFB4A8) else SoftWhite,
                        fontSize = 15.sp, fontWeight = FontWeight.Black)
                }
                p.commanderName?.let { Line("Commander: $it", Muted, 11) }
                Line("Hand ${p.hand.size} · Library ${p.library.size} · Graveyard ${p.graveyard.size}", SoftWhite, 12)
                Line("Lands ${lands.size} (${lands.count { !it.tapped }} untapped) · Other permanents $others", SoftWhite, 12)
                Line(if (creatures.isEmpty()) "No creatures" else "${creatures.size} creatures · ${power} total power · ${creatures.count { !it.tapped }} untapped", SoftWhite, 12)
                biggest?.let { b -> Line("Biggest: ${b.name}${if (b.power != null) " ${b.power}/${b.toughness}" else ""}", Muted, 12) }
                if (!mine && me != null) {
                    val dealt = p.commanderName?.let { me.commanderDamageReceived[it] } ?: 0
                    if (dealt > 0) Line("Their commander has dealt you $dealt of 21", if (dealt >= 15) Color(0xFFFFB4A8) else CounterGold, 12, true)
                }
                if (mine) {
                    val worst = p.commanderDamageReceived.filterValues { it > 0 }
                    worst.forEach { (name, n) -> Line("$name has dealt you $n commander damage", if (n >= 15) Color(0xFFFFB4A8) else CounterGold, 12) }
                }
            }
        }
    }
}

/** (2) Coach mode. */
@Composable
private fun CoachWindow() {
    ChimeraWindow("What would the AI do?", { ChimeraCoach.open = false }, "chimera-coach") {
        val answer = ChimeraCoach.answer
        if (answer == null) {
            Line("Forge is playing your seat in a copy of the game…", Muted)
            LaunchedEffect(ChimeraCoach.asked) {
                kotlinx.coroutines.delay(20_000)
                if (ChimeraCoach.answer == null) ChimeraCoach.answer = "The coach took too long. Try again in a moment."
            }
        } else {
            answer.lines().filter { it.isNotBlank() }.forEach { line ->
                Text("• $line", color = SoftWhite, fontSize = 15.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(vertical = 3.dp))
            }
            Line("This is what Forge's AI would do from your seat right now. It doesn't know your plans, and it sees only what you can see.", Muted, 11)
        }
    }
}

/** (5) After the game: the opponents' decks and the cards that hurt you most, in the game-over window. */
@Composable
internal fun ChimeraGameOverExtras() {
    if (!ChimeraFeatureSettings.reveal) return
    val hurt = ChimeraReveal.hurt()
    if (hurt.isNotEmpty()) {
        Heading("Hurt you most")
        hurt.forEach { (source, n) -> Line("$source · $n damage", SoftWhite, 12) }
    }
    if (ChimeraReveal.decks().isNotEmpty()) Text("SEE THEIR DECKS", color = Mint, fontSize = 13.sp, fontWeight = FontWeight.Black,
        modifier = Modifier.padding(top = 10.dp).clip(RoundedCornerShape(10.dp)).clickable { ChimeraFeatures.revealOpen = true }
            .padding(horizontal = 4.dp, vertical = 10.dp).testTag("chimera-see-decks"))
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TableController.RevealWindow() {
    var catalog by remember { mutableStateOf<SupportedCardCatalog?>(null) }
    LaunchedEffect(Unit) { catalog = runCatching { loadSupportCatalog() }.getOrNull() }
    var pop by remember { mutableStateOf<String?>(null) }
    ChimeraWindow("Their decks", { ChimeraFeatures.revealOpen = false }, "chimera-reveal") {
        ChimeraReveal.decks().forEach { d ->
            val commander = d.optString("commander").takeIf { it.isNotBlank() && it != "null" }
            Heading(d.optString("player") + (commander?.let { " · $it" } ?: ""))
            val cards = d.optJSONArray("cards")?.let { a -> (0 until a.length()).mapNotNull { a.optJSONObject(it) } }.orEmpty()
                .map { it.optString("name") to it.optInt("count", 1) }
            val groups = cards.groupBy { (name, _) ->
                val type = catalog?.find(name)?.typeLine.orEmpty()
                listOf("Creature", "Planeswalker", "Instant", "Sorcery", "Artifact", "Enchantment", "Battle", "Land").firstOrNull { type.contains(it) } ?: "Other"
            }
            groups.toSortedMap(compareBy<String> { listOf("Creature", "Planeswalker", "Instant", "Sorcery", "Artifact", "Enchantment", "Battle", "Land", "Other").indexOf(it) })
                .forEach { (type, list) ->
                    Line("$type (${list.sumOf { it.second }})", Muted, 11, true)
                    FlowRow(Modifier.fillMaxWidth().padding(vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        list.sortedBy { it.first }.forEach { (name, n) -> Chip(if (n > 1) "$n × $name" else name) { pop = name } }
                    }
                }
        }
    }
    pop?.let { name -> ChimeraCardPop(name, ChimeraPictures.picture(name, null), null) { pop = null } }
}

/** (6) Deck stats and goldfishing for the deck chosen on Home. */
@Composable
private fun TableController.DeckStatsWindow() {
    var stats by remember { mutableStateOf<ChimeraDeckStats?>(null) }
    var failed by remember { mutableStateOf(false) }
    LaunchedEffect(activeDeckId) {
        stats = null
        val (names, commander) = activeDeckCards()
        if (names.isEmpty()) { failed = true; return@LaunchedEffect }
        val catalog = runCatching { loadSupportCatalog() }.getOrNull()
        stats = withContext(Dispatchers.Default) { ChimeraDeckMath.compute(names, commander, catalog) }
    }
    ChimeraWindow("Deck stats", { ChimeraFeatures.statsOpen = false }, "chimera-deck-stats") {
        val s = stats
        when {
            failed -> Line("Choose a deck on Home first.")
            s == null -> Line("Shuffling your deck 1,000 times…", Muted)
            else -> DeckStatsBody(s)
        }
    }
}

@Composable
private fun DeckStatsBody(s: ChimeraDeckStats) {
    s.commander?.let { Line(it, Mint, 15, true) }
    Line("${s.total} cards · ${s.lands} lands · ${s.rocks} mana rocks and dorks · average mana value ${"%.2f".format(Locale.US, s.averageValue)} (spells)", SoftWhite, 12)
    Heading("Card types")
    Line(s.types.joinToString(" · ") { (type, n) -> "$type $n" }, SoftWhite, 12)
    Heading("Mana curve (spells)")
    val top = (s.curve.maxOrNull() ?: 1).coerceAtLeast(1)
    Row(Modifier.fillMaxWidth().height(110.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.Bottom) {
        s.curve.forEachIndexed { i, n ->
            Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Bottom) {
                Text("$n", color = SoftWhite, fontSize = 10.sp)
                Box(Modifier.fillMaxWidth().height((70f * n / top).dp.coerceAtLeast(2.dp)).clip(RoundedCornerShape(topStart = 4.dp, topEnd = 4.dp)).background(Mint))
                Text(if (i == 7) "7+" else "$i", color = Muted, fontSize = 10.sp)
            }
        }
    }
    if (s.pips.isNotEmpty()) {
        Heading("Colours: symbols in costs vs lands that make them")
        val names = mapOf('W' to "White", 'U' to "Blue", 'B' to "Black", 'R' to "Red", 'G' to "Green")
        val totalPips = s.pips.values.sum().coerceAtLeast(1)
        s.pips.forEach { (c, n) ->
            val src = s.sources[c] ?: 0
            Line("${names[c]}: $n symbols (${n * 100 / totalPips}%) · $src lands", if (src * 100 / s.lands.coerceAtLeast(1) + 10 < n * 100 / totalPips) CounterGold else SoftWhite, 12)
        }
    }
    Heading("Goldfish: 1,000 shuffled games, no opponent")
    Line("Lands in play by turn (keeping any 7, playing one land a turn):", Muted, 11)
    Row(Modifier.fillMaxWidth().padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        s.landsByTurn.take(8).forEachIndexed { i, v ->
            Column(Modifier.weight(1f).clip(RoundedCornerShape(8.dp)).background(PanelLight).padding(vertical = 6.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text("T${i + 1}", color = Muted, fontSize = 10.sp)
                Text("%.1f".format(Locale.US, v), color = SoftWhite, fontSize = 12.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
    if (s.commanderValue != null) {
        Spacer(Modifier.height(8.dp))
        Line(s.commanderTurn?.let { "Commander (mana value ${s.commanderValue}) castable on turn ${"%.1f".format(Locale.US, it)} on average; on curve (turn ${s.commanderValue} or sooner) in ${s.commanderOnCurve}% of games." }
            ?: "Commander not castable within 12 turns in these games.", SoftWhite, 12)
    }
    Line("Counts mana only, not colours; mana rocks and dorks costing 3 or less are cast as soon as they're affordable.", Muted, 10)
    if (s.unknown.isNotEmpty()) Line("Not in Arcana's card list, so left out: ${s.unknown.take(8).joinToString()}${if (s.unknown.size > 8) "…" else ""}", Muted, 10)
}

/** (7) Game history: results per deck and opponent, and each game turn by turn. */
@Composable
private fun HistoryWindow() {
    var games by remember { mutableStateOf<List<JSONObject>?>(null) }
    LaunchedEffect(ChimeraHistory.revision) { games = withContext(Dispatchers.IO) { ChimeraHistory.load() } }
    var replay by remember { mutableStateOf<JSONObject?>(null) }
    ChimeraWindow(if (replay == null) "Game history" else "Replay", { if (replay != null) replay = null else ChimeraFeatures.historyOpen = false }, "chimera-history") {
        val list = games
        val r = replay
        when {
            r != null -> ReplayBody(r) { replay = null }
            list == null -> Line("Loading…", Muted)
            list.isEmpty() -> Line("No games yet. Games against Forge are recorded here when they end.", Muted)
            else -> HistoryBody(list) { replay = it }
        }
    }
}

@Composable
private fun HistoryBody(games: List<JSONObject>, onOpen: (JSONObject) -> Unit) {
    fun tally(list: List<JSONObject>) = "${list.count { it.optString("result") == "Win" }}–${list.count { it.optString("result") == "Loss" }}"
    val finished = games.filter { it.optString("result") != "Unfinished" }
    Line("${finished.size} games · ${tally(finished)} (wins–losses)", SoftWhite, 13, true)
    Heading("By your deck")
    finished.groupBy { it.optString("deck").ifBlank { "Unknown deck" } }.toList().sortedByDescending { it.second.size }.forEach { (deck, list) ->
        Line("$deck · ${tally(list)}", SoftWhite, 12)
    }
    Heading("By opponent's commander")
    finished.flatMap { g -> g.optJSONArray("opponents")?.let { a -> (0 until a.length()).mapNotNull { a.optJSONObject(it)?.optString("commander")?.takeIf { c -> c.isNotBlank() && c != "null" } } }.orEmpty().distinct().map { it to g } }
        .groupBy({ it.first }, { it.second }).toList().sortedByDescending { it.second.size }.take(20).forEach { (cmd, list) ->
            Line("$cmd · ${tally(list)}", SoftWhite, 12)
        }
    Heading("Games (tap one to replay it)")
    val format = SimpleDateFormat("MMM d, HH:mm", Locale.getDefault())
    games.forEach { g ->
        val result = g.optString("result")
        val opponents = g.optJSONArray("opponents")?.let { a -> (0 until a.length()).mapNotNull { a.optJSONObject(it) }.map { it.optString("commander").takeIf { c -> c.isNotBlank() && c != "null" } ?: it.optString("name") } }.orEmpty()
        Column(Modifier.fillMaxWidth().padding(top = 6.dp).clip(RoundedCornerShape(10.dp)).background(PanelLight.copy(alpha = 0.6f)).clickable { onOpen(g) }.padding(10.dp)) {
            Row {
                Text(result.uppercase(), color = when (result) { "Win" -> Mint; "Loss" -> Color(0xFFFFB4A8); else -> Muted }, fontSize = 12.sp, fontWeight = FontWeight.Black, modifier = Modifier.width(96.dp))
                Text(format.format(Date(g.optLong("date"))), color = Muted, fontSize = 11.sp)
            }
            Line("${g.optString("deck")} vs ${opponents.joinToString()}", SoftWhite, 12)
            Line("${g.optJSONArray("turns")?.length() ?: 0} turns · ${g.optLong("seconds") / 60} min", Muted, 11)
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ReplayBody(game: JSONObject, onBack: () -> Unit) {
    val turns = game.optJSONArray("turns")?.let { a -> (0 until a.length()).mapNotNull { a.optJSONObject(it) } }.orEmpty()
    var index by remember(game) { mutableIntStateOf((turns.size - 1).coerceAtLeast(0)) }
    var askDelete by remember { mutableStateOf(false) }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("‹ ALL GAMES", color = Mint, fontSize = 12.sp, fontWeight = FontWeight.Black, modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable(onClick = onBack).padding(vertical = 10.dp, horizontal = 4.dp))
        Spacer(Modifier.weight(1f))
        Text(if (askDelete) "TAP AGAIN TO DELETE" else "DELETE", color = Color(0xFFE9978B), fontSize = 11.sp, fontWeight = FontWeight.Black,
            modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable { if (askDelete) { ChimeraHistory.delete(game); onBack() } else askDelete = true }.padding(10.dp))
    }
    Line("${game.optString("result")} · ${game.optString("deck")}", SoftWhite, 14, true)
    if (turns.isEmpty()) { Line("No turns were recorded.", Muted); return }
    val turn = turns[index.coerceIn(0, turns.size - 1)]
    Line("Turn ${turn.optInt("turn")} · ${turn.optString("active")}'s turn (end of turn)", Mint, 13, true)
    if (turns.size > 1) Slider(value = index.toFloat(), onValueChange = { index = it.toInt() }, valueRange = 0f..(turns.size - 1).toFloat(),
        steps = (turns.size - 2).coerceAtLeast(0), colors = SliderDefaults.colors(thumbColor = Mint, activeTrackColor = Mint), modifier = Modifier.testTag("chimera-replay-slider"))
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Chip("‹ Previous") { index = (index - 1).coerceAtLeast(0) }
        Chip("Next ›") { index = (index + 1).coerceAtMost(turns.size - 1) }
    }
    val players = turn.optJSONArray("players")?.let { a -> (0 until a.length()).mapNotNull { a.optJSONObject(it) } }.orEmpty()
    players.forEach { p ->
        Column(Modifier.fillMaxWidth().padding(top = 8.dp).clip(RoundedCornerShape(10.dp)).background(PanelLight.copy(alpha = 0.55f)).padding(10.dp)) {
            Row {
                Text(p.optString("name"), color = SoftWhite, fontSize = 13.sp, fontWeight = FontWeight.Black, modifier = Modifier.weight(1f))
                Text(if (p.optBoolean("eliminated")) "OUT" else "${p.optInt("life")} life", color = SoftWhite, fontSize = 13.sp, fontWeight = FontWeight.Black)
            }
            Line("Hand ${p.optInt("hand")} · Library ${p.optInt("library")} · Graveyard ${p.optInt("graveyard")} · Lands ${p.optInt("lands")}", Muted, 11)
            val board = p.optJSONArray("board")?.let { a -> (0 until a.length()).map { a.optString(it) } }.orEmpty()
            Line(if (board.isEmpty()) "No other permanents" else board.groupingBy { it }.eachCount().entries.joinToString { (n, c) -> if (c > 1) "$c × $n" else n }, SoftWhite, 12)
            p.optJSONObject("commanderDamage")?.let { cd -> cd.keys().asSequence().forEach { k -> Line("Commander damage from $k: ${cd.optInt(k)}", CounterGold, 11) } }
        }
    }
    val log = turn.optJSONArray("log")?.let { a -> (0 until a.length()).map { a.optString(it) } }.orEmpty().filter { it.isNotBlank() }
    if (log.isNotEmpty()) {
        Heading("What happened")
        log.forEach { Line(it, SoftWhite, 11) }
    }
    val reveal = game.optJSONObject("reveal")
    val hurt = reveal?.optJSONArray("hurtYou")
    if (index == turns.size - 1 && hurt != null && hurt.length() > 0) {
        Heading("Hurt you most")
        (0 until hurt.length()).mapNotNull { hurt.optJSONObject(it) }.forEach { Line("${it.optString("source")} · ${it.optInt("damage")} damage", SoftWhite, 12) }
    }
}

/** (3) Extra lines in the attack tray: life you'd gain from lifelink, and the attackers that could die. */
@Composable
internal fun ChimeraCombatExtras(attackers: List<Pair<GameCard, Boolean>>) {
    if (attackers.isEmpty()) return
    val lifelink = attackers.filter { (c, _) -> combatKeywords(c).contains("LIFELINK") }
        .sumOf { (c, _) -> combatPower(c) * if (combatKeywords(c).contains("DOUBLE_STRIKE")) 2 else 1 }
    if (lifelink > 0) Text("Lifelink: you gain about $lifelink if unblocked", color = Mint, fontSize = 11.sp, fontWeight = FontWeight.Bold)
    val risky = attackers.filter { it.second }.map { it.first.name }
    if (risky.isNotEmpty()) Text("At risk if blocked: ${risky.groupingBy { it }.eachCount().entries.joinToString { (n, c) -> if (c > 1) "$c × $n" else n }}",
        color = CounterGold, fontSize = 11.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
}

/** Settings › Gameplay: the feature switches. */
@Composable
internal fun ChimeraFeatureSettingsRows() {
    SettingsToggle("Coach", "Adds COACH: Forge's AI says what it would do from your seat right now.", ChimeraFeatureSettings.coach, { ChimeraFeatureSettings.changeCoach(it) })
    SettingsToggle("Show their decks after a game", "The game-over window shows the cards that hurt you most and each AI's whole deck.", ChimeraFeatureSettings.reveal, { ChimeraFeatureSettings.changeReveal(it) })
    SettingsToggle("Table shortcuts", "BOARD and COACH buttons at the left edge of the table. Both are also in ⋮ › Game tools.", ChimeraFeatureSettings.shortcuts, { ChimeraFeatureSettings.changeShortcuts(it) })
    Text("Licenses & source code", color = SoftWhite, fontSize = 13.sp,
        modifier = Modifier.fillMaxWidth().clickable { ChimeraLegal.open = true }
            .padding(vertical = 12.dp).testTag("chimera-license-settings"))
}

/** Home: Deck stats and History, under the deck card. */
@Composable
internal fun ChimeraHomeButtons(enabled: Boolean) {
    Row(Modifier.fillMaxWidth().padding(start = 10.dp, end = 10.dp, bottom = 10.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        listOf("DECK STATS" to { ChimeraFeatures.statsOpen = true }, "HISTORY" to { ChimeraFeatures.historyOpen = true },
            "LICENSES" to { ChimeraLegal.open = true }).forEach { (label, open) ->
            Text(label, color = if (enabled) SoftWhite else Muted, fontSize = 13.sp, fontWeight = FontWeight.Bold,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                modifier = Modifier.weight(1f).clip(RoundedCornerShape(24.dp)).border(1.dp, PanelLight, RoundedCornerShape(24.dp))
                    .clickable(enabled = enabled) { open() }.heightIn(min = 44.dp).padding(vertical = 12.dp)
                    .testTag("home-" + label.lowercase().replace(' ', '-')))
        }
    }
}
