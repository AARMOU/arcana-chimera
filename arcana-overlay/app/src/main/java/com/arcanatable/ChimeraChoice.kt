package com.arcanatable

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
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
import org.json.JSONObject
import java.util.Collections

/**
 * Owner, Nov 9: "whenever there is an option to pick a card, I want to be able to tap the name and have it pop up."
 *
 * Every choice dialog gets a row of tappable names (the card asking, cards named in the question, abilities and
 * effects being chosen between); the order list's names are tappable too. A tap shows the card large, and for an
 * ability or effect the card it comes from with the ability's text under it.
 */
internal object ChimeraChoice {
    data class Entry(val key: String, val name: String, val image: String?, val text: String?)

    /** The card being shown (one at a time, on top of the choice). */
    var open by mutableStateOf<Entry?>(null)

    private fun clean(s: String?): String? = s?.trim()?.takeIf { it.isNotEmpty() && it != "null" }
    private fun hidden(name: String) = name == "Hidden card" || name == "Face-down card"

    fun entry(name: String, image: String?, text: String?): Entry? {
        val n = clean(name) ?: return null
        if (hidden(n)) return null
        val t = clean(text)?.takeIf { !it.equals(n, ignoreCase = true) }
        return Entry("$n|${t.orEmpty()}", n, clean(image) ?: ChimeraPictures.picture(n, null), t)
    }

    /** What the order list's line [id] shows: "Card: ability" for abilities and effects, else the card's name. */
    fun orderLabel(decision: JSONObject, id: String, fallback: String): String {
        val info = decision.optJSONObject("cardInfo")?.optJSONObject(id) ?: return fallback
        val name = clean(info.optString("name")) ?: fallback
        val text = clean(info.optString("text"))?.takeIf { !it.equals(name, ignoreCase = true) } ?: return name
        return "$name: $text"
    }

    /** The card behind one entry of a choice (order list line, option id). */
    fun forId(decision: JSONObject, id: String, pick: (String) -> PickCard?): Entry? {
        val info = (decision.optJSONObject("cardInfo") ?: decision.optJSONObject("cards"))?.optJSONObject(id)
        val p = runCatching { pick(id) }.getOrNull()
        val name = clean(info?.optString("name")) ?: p?.name ?: return null
        return entry(name, clean(info?.optString("imageUri")) ?: p?.imageUrl, info?.optString("text"))
    }

    /** The tappable names for a choice dialog. */
    fun entries(decision: JSONObject, pick: (String) -> PickCard?, known: List<GameCard>): List<Entry> {
        val out = LinkedHashMap<String, Entry>()
        fun add(e: Entry?) { if (e != null && e.key !in out) out[e.key] = e }
        val kind = decision.optString("type")
        val ctx = decision.optJSONObject("context")
        val sourceId = clean(ctx?.optString("sourceId"))
        clean(ctx?.optString("sourceName"))?.let { name ->
            add(entry(name, sourceId?.let { runCatching { pick(it) }.getOrNull()?.imageUrl }, null))
        }
        // Abilities and effects being chosen between (the order list has its own tappable names).
        if (kind != "OrderObjectsDecision" && kind != "ReorderLibraryDecision") {
            val info = decision.optJSONObject("cardInfo")
            val ids = decision.optJSONArray("options")
            if (info != null && ids != null) for (i in 0 until ids.length()) {
                val o = info.optJSONObject(ids.optString(i)) ?: continue
                if (o.optString("typeLine") == "Ability" || o.optString("typeLine") == "Replacement effect")
                    add(entry(o.optString("name"), o.optString("imageUri"), o.optString("text")))
            }
        }
        // Cards named in the question, its yes/no answers or a list of named options.
        val words = buildString {
            listOf("prompt", "hint", "yesText", "noText", "filterDescription").forEach { append(decision.optString(it)).append(' ') }
            decision.optJSONArray("options")?.let { o -> if (kind == "ChooseOptionDecision") for (i in 0 until o.length()) append(o.optString(i)).append(' ') }
        }
        known.asSequence().filter { !it.isFaceDown && it.name.length > 2 && !hidden(it.name) }.distinctBy { it.name }
            .filter { words.contains(it.name) }.forEach { add(entry(it.name, it.imageUrl, null)) }
        return out.values.take(12)
    }
}

/** The row of tappable names at the top of a choice dialog, and the card pop-up. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ChimeraChoiceCards(entries: List<ChimeraChoice.Entry>) {
    if (entries.isNotEmpty()) {
        Text("TAP A NAME TO SEE THE CARD", color = Muted, fontSize = 9.sp, fontWeight = FontWeight.Black, letterSpacing = 1.sp)
        FlowRow(Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            entries.forEach { e ->
                val label = e.name + (e.text?.let { t -> " · " + (if (t.length > 32) t.take(31) + "…" else t) } ?: "")
                Text(label, color = Mint, fontSize = 12.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.clip(RoundedCornerShape(14.dp)).background(PanelLight).clickable { ChimeraChoice.open = e }
                        .heightIn(min = 36.dp).padding(horizontal = 10.dp, vertical = 9.dp).testTag("chimera-choice-card"))
            }
        }
    }
    ChimeraChoice.open?.let { e -> ChimeraCardPop(e.name, e.image, e.text) { ChimeraChoice.open = null } }
}

/** One card, large, with an ability's text under it; tap anywhere to close. */
@Composable
internal fun ChimeraCardPop(name: String, image: String?, text: String?, onDismiss: () -> Unit) {
    val screen = LocalConfiguration.current
    val w = minOf(screen.screenWidthDp * 0.78f, screen.screenHeightDp * (if (text != null) 0.48f else 0.6f), 360f).dp
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(Modifier.fillMaxSize().clickable(onClick = onDismiss), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                PickFace(PickCard(name, name, image, group = "Choice", groupOrder = 0), Modifier.width(w).aspectRatio(0.716f).clip(RoundedCornerShape(14.dp)))
                if (text != null) Text(text, color = Color.White, fontSize = 13.sp,
                    modifier = Modifier.widthIn(max = w + 40.dp).padding(top = 10.dp).clip(RoundedCornerShape(10.dp)).background(Color(0xEE101615)).padding(12.dp))
                Text("Tap anywhere to close", color = Color.White.copy(alpha = 0.7f), fontSize = 11.sp, modifier = Modifier.padding(top = 8.dp))
            }
        }
    }
}

/**
 * Owner, Nov 9: "There are still cards and images that fail to load." Pictures that fail after the whole fallback
 * chain (chosen link, full card picture, lookup by name) are listed in the diagnostic report.
 */
internal object ChimeraPictureLog {
    private val failed = Collections.synchronizedMap(LinkedHashMap<String, String>())

    fun failed(name: String?, tried: List<String>) {
        val key = name?.takeIf { it.isNotBlank() } ?: tried.firstOrNull() ?: return
        synchronized(failed) {
            failed.remove(key)
            failed[key] = tried.joinToString(" → ")
            while (failed.size > 60) failed.remove(failed.keys.first())
        }
    }

    fun loaded(name: String?) { name?.let { synchronized(failed) { failed.remove(it) } } }

    fun lines(): List<String> = synchronized(failed) {
        if (failed.isEmpty()) listOf("Pictures that didn't load: none")
        else listOf("Pictures that didn't load (${failed.size}, newest last):") + failed.map { (k, v) -> "  $k — $v" }
    }
}

/** Owner, Nov 9: the home screen's commander picture: Cropped (as before), Full card or Full art; tap it to see it full size. */
internal object ChimeraHomeArt {
    const val CROPPED = "cropped"
    const val FULL_CARD = "full_card"
    const val FULL_ART = "full_art"
    val ALL = listOf(CROPPED to "Cropped", FULL_CARD to "Full card", FULL_ART to "Full art")
    private const val KEY = "chimera_home_art"
    private var loaded = false
    private var current by mutableStateOf(CROPPED)

    fun mode(prefs: android.content.SharedPreferences): String {
        if (!loaded) { loaded = true; current = prefs.getString(KEY, CROPPED) ?: CROPPED }
        return current
    }

    fun set(prefs: android.content.SharedPreferences, value: String) {
        loaded = true; current = value; prefs.edit().putString(KEY, value).apply()
    }

    /** The whole card's picture for an art-only link (art_crop -> normal). */
    fun fullCard(url: String): String =
        CardImages.atSize(url, "normal").replace("version=art_crop", "version=normal")
}

/** Small bits of in-game memory for Chimera's automatic steps. */
internal object ChimeraPlayState {
    /** The turn "Pass to my turn" last declared an attack for you on (it tries once per turn). */
    @Volatile var autoAttackTurn = -1
    /** Studio's "Start practice game": reopen Studio once the game is up and the opening hand is kept. */
    var studioAfterPractice by mutableStateOf(false)
}

/**
 * The enlarged card (owner, Nov 10: "show the counters in the enlarged view"): its counters, its power and toughness
 * now (counters and effects included) and any damage marked on it, under the picture.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ChimeraZoomCounters(card: GameCard, width: androidx.compose.ui.unit.Dp) {
    if (card.isFaceDown) return
    val counters = card.counters.filterValues { it > 0 }
    if (counters.isEmpty() && card.damage <= 0) return
    FlowRow(Modifier.width(width), horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally),
        verticalArrangement = Arrangement.spacedBy(6.dp)) {
        counters.forEach { (kind, n) -> ZoomChip(if (n == 1) kind else "$kind ×$n", CounterGold, Ink) }
        if (card.power != null && card.toughness != null && counters.isNotEmpty()) ZoomChip("Now ${card.power}/${card.toughness}", PanelLight, SoftWhite)
        if (card.damage > 0) ZoomChip("${card.damage} damage", Color(0xFF8E2F2A), Color.White)
    }
}

@Composable
private fun ZoomChip(text: String, fill: Color, ink: Color) = Text(text, color = ink, fontSize = 14.sp, fontWeight = FontWeight.Black,
    modifier = Modifier.clip(RoundedCornerShape(12.dp)).background(fill).padding(horizontal = 12.dp, vertical = 6.dp))
