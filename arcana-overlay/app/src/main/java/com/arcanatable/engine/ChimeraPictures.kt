package com.arcanatable.engine

import android.content.Context
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/**
 * Card pictures for Forge games (owner, Nov 9: blank cards on the mulligan screen).
 *
 * The build writes `argentum/chimera-pictures.tsv` (name -> picture link for every bundled card, Arcana's and Forge's),
 * small enough to load before Forge deals the opening hand. Until it has loaded, and for a card it lacks, the link is
 * Scryfall's lookup by name, which Arcana's picture loader already resolves. A deck's chosen art (Change art) wins.
 */
object ChimeraPictures {
    private const val HOST_SEAT = "offline-you"
    @Volatile private var map: Map<String, String>? = null
    @Volatile private var loading = false

    /** Per seat: the art chosen in the deck builder, card name (lower case) -> picture link. */
    private val chosen = ConcurrentHashMap<String, Map<String, String>>()

    fun warm(context: Context) {
        if (map != null || loading) return
        loading = true
        val app = context.applicationContext
        Thread({
            map = runCatching {
                val m = HashMap<String, String>(40_000)
                app.assets.open("argentum/chimera-pictures.tsv").bufferedReader().useLines { lines ->
                    lines.forEach { line ->
                        val tab = line.indexOf('\t')
                        if (tab > 0) m[line.substring(0, tab)] = line.substring(tab + 1)
                    }
                }
                m
            }.getOrDefault(emptyMap())
            loading = false
        }, "chimera-pictures").start()
    }

    /** The art a seat chose for its deck (empty map: the default printing). */
    fun setChosen(seatId: String, art: Map<String, String>) {
        chosen[seatId] = art.mapKeys { it.key.lowercase(Locale.ROOT) }
    }

    fun clearChosen() = chosen.clear()

    fun picture(rawName: String, seatId: String?): String? {
        // Owner, Nov 9 diagnostics: Forge names like "Lasting Fayth (134)'s Adventure" become the card's own name.
        val name = rawName.replace(Regex(" \\(\\d+\\)"), "").removeSuffix("'s Adventure").trim()
        val key = name.lowercase(Locale.ROOT)
        seatId?.let { chosen[it]?.get(key)?.let { url -> return url } }
        if (seatId == HOST_SEAT) com.arcanatable.ChimeraArt.get(name)?.let { return it } // your Change art choice
        val m = map
        return m?.get(key) ?: m?.get(key.substringBefore(" // ")) ?: token(name) ?: com.arcanatable.artByName(name, false)
    }

    /**
     * Tokens ("Plant Token", "Kor Ally Token"): Scryfall's lookup by name only finds cards, so tokens use a search for a
     * token of that name (the app takes the first result's picture).
     */
    private fun token(name: String): String? {
        if (!name.endsWith(" Token")) return null
        val base = name.removeSuffix(" Token").trim().takeIf { it.isNotEmpty() } ?: return null
        val q = java.net.URLEncoder.encode("!\"$base\" t:token", "UTF-8")
        return "https://api.scryfall.com/cards/search?q=$q&unique=art&format=image&version=normal"
    }
}
