package com.arcanatable

// CHIMERA OVERLAY (not Arcana source). Deck import, more forgiving (owner, Nov 8):
//  - deck links from Archidekt, Moxfield and ManaBox (and other deck pages, best effort)
//  - CSV exports from any of them (comma, semicolon or tab; any common column names)
//  - looser text: bullets, "Sol Ring x2", tabs
// Everything becomes the plain "Commander / Deck / Sideboard" text Arcana's own importer already reads.

import android.os.Handler
import android.os.Looper
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale

object ChimeraDeckImport {
    private val main = Handler(Looper.getMainLooper())

    fun isUrl(text: String): Boolean {
        val t = text.trim()
        return !t.contains('\n') && Regex("""^(https?://)?([\w-]+\.)*(archidekt\.com|moxfield\.com|manabox\.app)/\S+$|^https?://\S+$""", RegexOption.IGNORE_CASE).matches(t)
    }

    /** A deck link is fetched in the background and handed back as deck text; anything else is passed straight on. */
    fun fetchInto(text: String, onDone: (String) -> Unit, onError: (String) -> Unit) {
        if (!isUrl(text)) { onDone(text); return }
        Thread({
            val result = runCatching { fetch(text.trim()) }
            main.post { result.fold(onDone) { onError("Couldn't load that deck link: ${it.message ?: it.javaClass.simpleName}") } }
        }, "deck-link").start()
    }

    fun fetch(link: String): String {
        val url = if (link.startsWith("http", true)) link else "https://$link"
        val host = URL(url).host.lowercase(Locale.ROOT)
        val path = URL(url).path.trim('/').split('/')
        return when {
            host.endsWith("archidekt.com") -> archidekt(path.getOrNull(path.indexOf("decks") + 1)?.takeWhile(Char::isDigit) ?: error("no deck number in the link"))
            host.endsWith("moxfield.com") -> moxfield(path.getOrNull(path.indexOf("decks") + 1) ?: error("no deck id in the link"))
            host.endsWith("manabox.app") -> manabox(url)
            else -> anyPage(url) // other deck pages
        }
    }

    private fun get(url: String, json: Boolean = true): String {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 15_000; c.readTimeout = 20_000
        c.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 15) ArcanaTable")
        c.setRequestProperty("Accept", if (json) "application/json" else "text/html,application/json;q=0.9,*/*;q=0.8")
        try {
            val code = c.responseCode
            if (code !in 200..299) error(if (code == 404) "deck not found or not public" else "the site answered $code")
            return c.inputStream.bufferedReader().use { it.readText() }
        } finally { c.disconnect() }
    }

    private class Deck { val commander = mutableListOf<Pair<Int, String>>(); val main = mutableListOf<Pair<Int, String>>(); val side = mutableListOf<Pair<Int, String>>(); val companion = mutableListOf<Pair<Int, String>>(); var name: String? = null }

    private fun Deck.text(): String = buildString {
        name?.let { append("Name ").append(it).append('\n') }
        fun section(title: String, cards: List<Pair<Int, String>>) { if (cards.isNotEmpty()) { append(title).append('\n'); cards.forEach { (n, c) -> append(n).append(' ').append(c).append('\n') }; append('\n') } }
        section("Commander", commander); section("Companion", companion); section("Deck", main); section("Sideboard", side)
    }.also { if (commander.isEmpty() && main.isEmpty()) error("no cards found in that deck") }

    /** archidekt.com/api/decks/{id}/: cards[].quantity, cards[].card.oracleCard.name, cards[].categories; categories[].includedInDeck. */
    private fun archidekt(id: String): String {
        val o = JSONObject(get("https://archidekt.com/api/decks/$id/"))
        val excluded = mutableSetOf<String>()
        o.optJSONArray("categories")?.let { for (i in 0 until it.length()) it.optJSONObject(i)?.let { c -> if (!c.optBoolean("includedInDeck", true)) excluded += c.optString("name").lowercase(Locale.ROOT) } }
        val d = Deck().apply { name = o.optString("name").ifBlank { null } }
        val cards = o.optJSONArray("cards") ?: JSONArray()
        for (i in 0 until cards.length()) {
            val e = cards.optJSONObject(i) ?: continue
            val card = e.optJSONObject("card") ?: continue
            val name = card.optJSONObject("oracleCard")?.optString("name")?.ifBlank { null } ?: card.optString("name").ifBlank { null } ?: continue
            val qty = e.optInt("quantity", 1)
            val cats = (e.optJSONArray("categories") ?: JSONArray()).let { a -> (0 until a.length()).map { a.optString(it).lowercase(Locale.ROOT) } }
            when {
                "commander" in cats -> d.commander += qty to name
                cats.any { it in excluded } || "maybeboard" in cats -> {}
                "sideboard" in cats -> d.side += qty to name
                else -> d.main += qty to name
            }
        }
        return d.text()
    }

    /** api2.moxfield.com/v3/decks/all/{id}: boards.{commanders,companions,mainboard,sideboard}.cards.*.{quantity, card.name}. */
    private fun moxfield(id: String): String {
        val o = JSONObject(runCatching { get("https://api2.moxfield.com/v3/decks/all/$id") }.getOrElse { get("https://api2.moxfield.com/v2/decks/all/$id") })
        val boards = o.optJSONObject("boards") ?: o // v2 keeps the boards at the top level, without "cards"
        val d = Deck().apply { name = o.optString("name").ifBlank { null } }
        fun board(key: String, into: MutableList<Pair<Int, String>>) {
            val b = boards.optJSONObject(key) ?: return
            val cards = b.optJSONObject("cards") ?: b
            for (k in cards.keys()) {
                val e = cards.optJSONObject(k) ?: continue
                val name = e.optJSONObject("card")?.optString("name")?.ifBlank { null } ?: continue
                into += e.optInt("quantity", 1) to name
            }
        }
        board("commanders", d.commander); board("companions", d.companion); board("mainboard", d.main); board("sideboard", d.side)
        return d.text()
    }

    /**
     * manabox.app/decks/{id} (owner's link, Nov 9): the page shows each card as separate elements, the quantity, then
     * the name, then an optional foil "F" and mana symbols, under headings (Commander, Creatures 33, ...); the whole
     * list appears twice (grid and list views). Mirrored by scripts/manabox_check.py, which the APK build runs on a
     * real deck.
     */
    private fun manabox(url: String): String {
        val body = get(url, json = false)
        manaboxText(body)?.let { return it }
        return anyPage(url) // a page that changed shape: the general reader's best effort
    }

    internal fun manaboxText(html: String): String? {
        val lines = html.replace(Regex("(?s)<(script|style|svg)[^>]*>.*?</\\1>"), "\n")
            .replace(Regex("<[^>]+>"), "\n")
            .replace("&amp;", "&").replace("&#x27;", "'").replace("&#39;", "'").replace("&quot;", "\"").replace("&nbsp;", " ")
            .lines().map { it.trim() }.filter { it.isNotEmpty() }
        val heading = Regex("""^(Commanders?|Companions?|Planeswalkers?|Creatures?|Instants?|Sorcery|Sorceries|Artifacts?|Enchantments?|Lands?|Battles?|Other|Sideboard|Maybeboard|Mainboard|Deck)(\s*\(?\d+\)?)?$""", RegexOption.IGNORE_CASE)
        val d = Deck()
        val seen = HashSet<String>()
        var section = ""
        var i = 0
        while (i < lines.size) {
            val line = lines[i]
            heading.matchEntire(line)?.let { section = it.groupValues[1].lowercase(Locale.ROOT); i++; continue }
            val qty = line.toIntOrNull()
            if (qty != null && qty in 1..99 && i + 1 < lines.size) {
                val name = lines[i + 1]
                val looksLikeName = name.length > 1 && name.toIntOrNull() == null && heading.matchEntire(name) == null && name.any { it.isLetter() }
                if (looksLikeName) {
                    if (section.startsWith("commander") && name.lowercase(Locale.ROOT) in seen && d.commander.none { it.second.equals(name, true) }) {
                        d.main.removeAll { it.second.equals(name, true) } // named earlier (page header): it's the commander
                        d.commander += qty to name
                    } else if (seen.add(name.lowercase(Locale.ROOT))) { // the second copy of the list adds nothing
                        when {
                            section.startsWith("commander") -> d.commander += qty to name
                            section.startsWith("companion") -> d.companion += qty to name
                            section == "sideboard" -> d.side += qty to name
                            section == "maybeboard" -> {}
                            else -> d.main += qty to name
                        }
                    }
                    i += 2; continue
                }
            }
            i++
        }
        return if (d.main.size + d.commander.size >= 5) d.text() else null
    }

    /**
     * Other deck pages: the page's embedded data (or a JSON reply) is searched for card entries with a
     * name and a quantity; if there is none, its text is read as a deck list.
     */
    private fun anyPage(url: String): String {
        val body = get(url, json = false)
        val jsons = mutableListOf<Any>()
        runCatching { jsons += JSONObject(body) }
        Regex("""<script[^>]*(?:id="__NEXT_DATA__"|type="application/(?:ld\+)?json")[^>]*>(.*?)</script>""", RegexOption.DOT_MATCHES_ALL)
            .findAll(body).forEach { m -> runCatching { jsons += JSONObject(m.groupValues[1]) } }
        val d = Deck()
        for (j in jsons) walk(j, null, d)
        if (d.main.isNotEmpty() || d.commander.isNotEmpty()) return d.text()
        val text = body.replace(Regex("(?s)<(script|style)[^>]*>.*?</\\1>"), " ").replace(Regex("<br\\s*/?>|</(p|div|li|tr|h\\d)>", RegexOption.IGNORE_CASE), "\n")
            .replace(Regex("<[^>]+>"), " ").replace("&amp;", "&").replace("&#x27;", "'").replace("&#39;", "'").replace("&quot;", "\"")
        val lines = text.lines().map { it.trim() }.filter { Regex("""^\d+x?\s+\S.*""").matches(it) }
        if (lines.size < 10) error("couldn't find a deck list on that page")
        return lines.joinToString("\n")
    }

    private fun walk(node: Any?, board: String?, d: Deck) {
        when (node) {
            is JSONArray -> for (i in 0 until node.length()) walk(node.opt(i), board, d)
            is JSONObject -> {
                val qty = listOf("quantity", "count", "qty", "amount").firstNotNullOfOrNull { k -> node.opt(k)?.toString()?.toIntOrNull() }
                val name = node.optString("name").ifBlank { null }
                    ?: node.optJSONObject("card")?.let { it.optJSONObject("oracleCard")?.optString("name")?.ifBlank { null } ?: it.optString("name").ifBlank { null } }
                if (qty != null && name != null && qty in 1..99) {
                    val where = (listOf("board", "zone", "category", "section").firstNotNullOfOrNull { k -> node.optString(k).ifBlank { null } } ?: board ?: "").lowercase(Locale.ROOT)
                    val target = when { "commander" in where -> d.commander; "side" in where -> d.side; "maybe" in where || "considering" in where -> null; else -> d.main }
                    target?.add(qty to name)
                    return
                }
                for (k in node.keys()) walk(node.opt(k), if (k.lowercase(Locale.ROOT).let { "commander" in it || "side" in it || "maybe" in it || "main" in it }) k else board, d)
            }
        }
    }

    // ---------------------------------------------------------------- text and CSV

    /** CSV exports (any site, any delimiter) and looser text lines → the plain text Arcana's importer reads. */
    fun normalize(raw: String): String {
        val text = raw.removePrefix("﻿")
        val first = text.lineSequence().map(String::trim).firstOrNull { it.isNotEmpty() } ?: return raw
        val delimiter = listOf('\t', ';', ',').maxByOrNull { d -> first.count { it == d } }?.takeIf { d -> first.count { it == d } >= 1 }
        if (delimiter != null) {
            val header = cells(first, delimiter).map { it.lowercase(Locale.ROOT).trim() }
            val nameCol = header.indexOfFirst { it in setOf("name", "card name", "card", "cardname", "card_name") }
            if (nameCol >= 0) return csv(text, delimiter, header, nameCol)
        }
        return text.lines().joinToString("\n") { line ->
            var l = line.trim().replace('\t', ' ').replace(Regex("""^[-*•·]\s+"""), "")
            Regex("""^(.+?)\s+[xX×]\s*(\d+)$""").matchEntire(l)?.let { m -> l = "${m.groupValues[2]} ${m.groupValues[1]}" }
            l
        }
    }

    private fun csv(text: String, delimiter: Char, header: List<String>, nameCol: Int): String {
        fun col(vararg names: String) = header.indexOfFirst { it in names }
        val qty = col("quantity", "count", "qty", "amount", "#", "number of cards")
        val zone = col("board", "zone", "section", "category", "categories", "deck", "type")
        val set = col("set code", "set", "edition", "edition code", "set_code", "setcode")
        val number = col("collector number", "collector_number", "collectornumber", "cn", "card number")
        val d = Deck()
        text.lines().drop(text.lines().indexOfFirst { it.isNotBlank() } + 1).forEach { line ->
            if (line.isBlank()) return@forEach
            val c = cells(line, delimiter)
            val name = c.getOrNull(nameCol)?.trim()?.ifBlank { null } ?: return@forEach
            val n = c.getOrNull(qty)?.trim()?.toIntOrNull() ?: 1
            val printing = listOfNotNull(c.getOrNull(set)?.trim()?.ifBlank { null }?.let { "(${it.uppercase(Locale.ROOT)})" }, c.getOrNull(number)?.trim()?.ifBlank { null })
                .takeIf { it.size == 2 }?.joinToString(" ")?.let { " $it" }.orEmpty()
            val where = c.getOrNull(zone)?.lowercase(Locale.ROOT).orEmpty()
            when {
                "commander" in where -> d.commander += n to name + printing
                "maybe" in where || "considering" in where || "token" in where -> {}
                "side" in where -> d.side += n to name + printing
                "companion" in where -> d.companion += n to name + printing
                else -> d.main += n to name + printing
            }
        }
        return d.text()
    }

    private fun cells(line: String, delimiter: Char): List<String> {
        val out = mutableListOf<String>(); val cell = StringBuilder(); var quoted = false; var i = 0
        while (i < line.length) {
            val ch = line[i]
            when {
                ch == '"' && quoted && i + 1 < line.length && line[i + 1] == '"' -> { cell.append('"'); i++ }
                ch == '"' -> quoted = !quoted
                ch == delimiter && !quoted -> { out += cell.toString().trim(); cell.clear() }
                else -> cell.append(ch)
            }
            i++
        }
        out += cell.toString().trim()
        return out
    }
}
