package com.arcanatable

import org.json.JSONObject
import java.io.InputStream

/** Chimera-only official Commander products; old Arcana premade lists are not displayed. */
internal object ChimeraPrecons {
    data class Meta(val setName: String, val releaseDate: String, val colors: String)
    private var metadata: Map<String, Meta> = emptyMap()

    fun meta(choiceId: String): Meta? = metadata[choiceId.removePrefix("precon:")]

    fun load(open: (String) -> InputStream): List<AppPreconDeck> {
        val json = open("precons/wotc-commander-precons.json")
            .bufferedReader(Charsets.UTF_8).use { it.readText() }
        val root = JSONObject(json)
        require(root.getInt("schemaVersion") == 1) { "Unsupported official-precon catalog format" }
        val items = root.getJSONArray("decks")
        val decks = ArrayList<AppPreconDeck>(items.length())
        val found = HashMap<String, Meta>()
        for (i in 0 until items.length()) {
            val item = items.getJSONObject(i)
            val id = item.getString("id")
            require(id !in found) { "Duplicate official deck identifier: $id" }
            val name = item.getString("name")
            val commander = item.getString("commander")
            val list = item.getString("decklist")
            val setName = item.getString("setName")
            require(name.isNotBlank() && commander.isNotBlank() && list.isNotBlank() && setName.isNotBlank())
            found[id] = Meta(setName, item.optString("releaseDate"), item.optString("colors"))
            decks += AppPreconDeck(id, name, commander, list)
        }
        require(decks.isNotEmpty()) { "WotC Commander catalog is empty" }
        metadata = found
        return decks
    }
}
