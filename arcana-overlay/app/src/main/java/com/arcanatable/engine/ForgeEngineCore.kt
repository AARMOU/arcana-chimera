package com.arcanatable.engine

// CHIMERA OVERLAY (not Arcana source). The Forge game itself: Arcana's seats in, Forge's messages out. It runs in the
// separate Forge game process (ForgeProcessService), or in the app's own process if that process can't be started.
// Forge (GPL-3.0) runs through org.arcanachimera.forgehost.ArcanaForgeHost.

import org.arcanachimera.forgehost.ArcanaForgeHost
import org.json.JSONArray
import org.json.JSONObject

internal class ForgeEngineCore(
    seats: List<ArcanaForgeHost.Seat>,
    aiStrength: ArcanaForgeHost.AiStrength,
    practiceBoard: String?,
    /** [to] null: the host's own view; otherwise the guest seat the message is for. */
    private val emit: (to: String?, message: JSONObject) -> Unit,
    progress: (Float, String) -> Unit,
    phaseStops: () -> Set<String>,
    context: android.content.Context,
) {
    private val forge = ArcanaForgeHost { emit(null, it) }

    var quietOpponentTurns: Boolean
        get() = forge.quietOpponentTurns
        set(value) { forge.quietOpponentTurns = value }
    var respondToOwnStack: Boolean
        get() = forge.respondToOwnStack
        set(value) { forge.respondToOwnStack = value }

    init {
        ArcanaForgeHost.lazyCards = true
        forge.aiStrength = aiStrength
        // Card pictures for the table and the mulligan screen (ChimeraPictures: a small build-time file, loaded in the
        // background; Scryfall's lookup by name until then).
        ChimeraPictures.warm(context)
        // Owner, Nov 9: phase-bar stops make Forge give this phone priority in those phases.
        ArcanaForgeHost.phaseStops = java.util.function.Supplier { phaseStops() }
        ArcanaForgeHost.progress = java.util.function.BiConsumer { f, what -> progress(f, what) }
        ArcanaForgeHost.pictures = java.util.function.BiFunction { name, seat -> ChimeraPictures.picture(name, seat) }
        practiceBoard?.takeIf { it.isNotBlank() }?.let { forge.setPracticeBoard(it) }
        forge.prepare(seats) { LocalArgentumHost.forgeAssetsDir() } // unpacked on Forge's start thread
    }

    /** Messages from the person in seat [from] ("offline-you" is this phone's player). */
    fun receive(from: String, message: JSONObject) {
        if (from != LocalArgentumHost.HUMAN_ID.value && message.optString("type") == "guestLeft") { forge.guestLeft(from); return }
        forge.handleFrom(from, message)
    }

    fun addGuest(seatId: String, seat: ArcanaForgeHost.Seat) = forge.addGuest(seatId, seat) { emit(seatId, it) }

    fun shutdown() = forge.shutdown()

    companion object {
        fun seatJson(seat: ArcanaForgeHost.Seat): JSONObject = JSONObject().put("name", seat.name())
            .put("deck", JSONArray(seat.deck())).put("commander", seat.commander() ?: JSONObject.NULL)

        fun seatFrom(o: JSONObject): ArcanaForgeHost.Seat {
            val deck = o.optJSONArray("deck") ?: JSONArray()
            return ArcanaForgeHost.Seat(o.optString("name"), List(deck.length()) { deck.optString(it) },
                o.optString("commander").takeIf { !o.isNull("commander") && it.isNotBlank() })
        }
    }
}
