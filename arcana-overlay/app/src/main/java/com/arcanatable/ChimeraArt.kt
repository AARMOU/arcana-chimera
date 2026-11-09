package com.arcanatable

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Locale

/**
 * Change art (owner, Nov 9): your choice of printing per card, kept on this phone for every deck. Your cards show it on
 * the table, in your hand and on the mulligan screen; when you host, everyone at the table sees it (the host sends the
 * picture link with each card), and as a guest you see your own choices on your phone.
 * Imports from Moxfield and Archidekt keep the printing the deck was built with (ChimeraDeckImport).
 */
object ChimeraArt {
    private val prefs: SharedPreferences? by lazy {
        runCatching {
            val app = Class.forName("android.app.ActivityThread").getMethod("currentApplication").invoke(null) as Context
            app.getSharedPreferences("chimera_art", Context.MODE_PRIVATE)
        }.getOrNull()
    }

    private fun key(name: String) = name.substringBefore(" // ").trim().lowercase(Locale.ROOT)

    /** The picture you chose for this card, or null for the default printing. */
    fun get(name: String?): String? = name?.takeIf { it.isNotBlank() }?.let { prefs?.getString(key(it), null) }

    fun set(name: String, url: String?) {
        prefs?.edit()?.apply { if (url == null) remove(key(name)) else putString(key(name), url) }?.apply()
    }

    /** A printing by set and collector number (deck imports), as a link Arcana's picture loader resolves. */
    fun printingLink(set: String, number: String): String =
        "https://api.scryfall.com/cards/${set.lowercase(Locale.ROOT)}/${URLEncoder.encode(number, "UTF-8")}?format=image&version=normal"

    data class Printing(val image: String, val setName: String, val year: String, val artist: String)

    /** Every distinct artwork of the card, newest first (Scryfall; needs the internet). */
    fun printings(name: String): List<Printing> {
        val out = ArrayList<Printing>()
        var next: String? = "https://api.scryfall.com/cards/search?unique=art&order=released&dir=desc&q=" +
            URLEncoder.encode("!\"${name.substringBefore(" // ").trim()}\"", "UTF-8")
        var pages = 0
        while (next != null && pages++ < 4) {
            val c = URL(next).openConnection() as HttpURLConnection
            c.connectTimeout = 15_000; c.readTimeout = 20_000
            c.setRequestProperty("User-Agent", "ArcanaTable/1.0 (Chimera)"); c.setRequestProperty("Accept", "application/json")
            val body = try {
                if (c.responseCode == 404) return out
                if (c.responseCode !in 200..299) error("Scryfall answered ${c.responseCode}")
                c.inputStream.bufferedReader().use { it.readText() }
            } finally { c.disconnect() }
            val o = JSONObject(body)
            val data = o.optJSONArray("data") ?: break
            for (i in 0 until data.length()) {
                val card = data.optJSONObject(i) ?: continue
                val img = card.optJSONObject("image_uris")?.optString("normal")?.ifBlank { null }
                    ?: card.optJSONArray("card_faces")?.optJSONObject(0)?.optJSONObject("image_uris")?.optString("normal")?.ifBlank { null }
                    ?: continue
                out += Printing(img, card.optString("set_name"), card.optString("released_at").take(4), card.optString("artist"))
            }
            next = if (o.optBoolean("has_more")) o.optString("next_page").ifBlank { null } else null
        }
        return out
    }
}

/** The Change art picker: every artwork of [name] as a grid; tap one to use it, or go back to the default. */
@Composable
fun ChimeraArtPicker(name: String, onDismiss: () -> Unit) {
    var printings by remember(name) { mutableStateOf<List<ChimeraArt.Printing>?>(null) }
    var problem by remember(name) { mutableStateOf<String?>(null) }
    var current by remember(name) { mutableStateOf(ChimeraArt.get(name)) }
    LaunchedEffect(name) {
        runCatching { withContext(Dispatchers.IO) { ChimeraArt.printings(name) } }
            .onSuccess { printings = it; if (it.isEmpty()) problem = "Scryfall has no pictures for $name." }
            .onFailure { problem = "Couldn't load $name's art. Check your internet connection and try again." }
    }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxWidth(0.94f).heightIn(max = 760.dp), shape = RoundedCornerShape(20.dp), color = Panel) {
            Column(Modifier.padding(16.dp)) {
                Text("Change art", color = Mint, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                Text(name, color = SoftWhite, fontSize = 18.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text("Your choice is used for this card in all your decks.", color = Muted, fontSize = 12.sp, modifier = Modifier.padding(top = 2.dp, bottom = 10.dp))
                val list = printings
                when {
                    problem != null -> Text(problem!!, color = SoftWhite, fontSize = 14.sp, modifier = Modifier.padding(vertical = 24.dp))
                    list == null -> Text("Loading every printing…", color = Muted, fontSize = 14.sp, modifier = Modifier.padding(vertical = 24.dp))
                    else -> LazyVerticalGrid(GridCells.Adaptive(118.dp), Modifier.weight(1f, fill = false),
                        horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(list, key = { it.image }) { p ->
                            val chosen = current == p.image
                            Column(Modifier.clip(RoundedCornerShape(8.dp))
                                .border(if (chosen) 3.dp else 0.dp, if (chosen) Mint else Color.Transparent, RoundedCornerShape(8.dp))
                                .clickable { ChimeraArt.set(name, p.image); current = p.image; onDismiss() }) {
                                CardArt(p.image, Modifier.fillMaxWidth().aspectRatio(0.716f), ContentScale.Fit, fallbackName = name)
                                Text(listOf(p.setName, p.year).filter { it.isNotBlank() }.joinToString(" · "), color = SoftWhite, fontSize = 10.sp,
                                    maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp))
                                if (p.artist.isNotBlank()) Text(p.artist, color = Muted, fontSize = 9.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.padding(start = 4.dp, end = 4.dp, bottom = 4.dp))
                            }
                        }
                    }
                }
                Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = { ChimeraArt.set(name, null); current = null; onDismiss() }, enabled = current != null) { Text("Use default art") }
                    TextButton(onClick = onDismiss) { Text("Close") }
                }
            }
        }
    }
}
