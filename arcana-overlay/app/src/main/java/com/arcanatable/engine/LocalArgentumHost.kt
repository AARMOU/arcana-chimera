package com.arcanatable.engine

// CHIMERA OVERLAY (not Arcana source). Replaces Arcana's Argentum-backed LocalArgentumHost with Forge,
// keeping the same name, constructor and messages so the rest of Arcana runs unchanged.
// Forge (GPL-3.0) runs through org.arcanachimera.forgehost.ArcanaForgeHost.

import com.arcanatable.engine.ai.AiDifficulty
import com.wingedsheep.engine.registry.CardRegistry
import com.wingedsheep.sdk.model.EntityId
import org.arcanachimera.forgehost.ArcanaForgeHost
import org.json.JSONObject
import java.io.File
import java.util.zip.ZipInputStream

data class LocalSeat(
    val name: String,
    val deck: List<String>,
    val commander: String?,
    val difficulty: AiDifficulty = AiDifficulty.DEFAULT, // Arcana's own AI setting; Forge uses its AI profile
)

@Suppress("UNUSED_PARAMETER")
class LocalArgentumHost(
    registry: CardRegistry,
    human: LocalSeat,
    opponent: LocalSeat,
    paceMs: Long = 600,
    extraOpponents: List<LocalSeat> = emptyList(),
    resumeCheckpoint: String? = null,
    gameSeed: Long? = null,
    tutorialPassiveOpponent: Boolean = false,
    private val emit: (JSONObject) -> Unit,
) {
    var quietOpponentTurns: Boolean
        get() = forge.quietOpponentTurns
        set(value) { forge.quietOpponentTurns = value }
    /** Arcana option "respond to my own spells": off (default) lets your own spells and abilities resolve without a stop. */
    var respondToOwnStack: Boolean
        get() = forge.respondToOwnStack
        set(value) { forge.respondToOwnStack = value }
    /** True once a Wi-Fi or internet friend has a seat. */
    val isWifi: Boolean get() = forge.hasGuests()
    private val forge = ArcanaForgeHost { emit(it) }

    init {
        val seats = (listOf(human, opponent) + extraOpponents.take(2)).map { ArcanaForgeHost.Seat(it.name, it.deck, it.commander) }
        ArcanaForgeHost.lazyCards = true
        // Arcana's AI difficulty → how hard Forge's AI searches. Easy: Forge's plain heuristics; Normal: Forge's
        // phone setting; Hard/Brutal: Forge's desktop search (Forge still falls back if the phone's heap is small).
        forge.aiStrength = when ((listOf(opponent) + extraOpponents).maxOf { it.difficulty }) {
            AiDifficulty.EASY -> ArcanaForgeHost.AiStrength.OFF
            AiDifficulty.NORMAL -> ArcanaForgeHost.AiStrength.LITE
            else -> ArcanaForgeHost.AiStrength.MAX
        }
        // Card pictures for the table and the mulligan screen (ChimeraPictures: a small build-time file, loaded in the
        // background; Scryfall's lookup by name until then).
        ChimeraPictures.warm(app())
        ChimeraLoading.reset()
        ChimeraLoading.hosting = true
        ArcanaForgeHost.progress = java.util.function.BiConsumer { f, what -> ChimeraLoading.report(f, what) }
        ArcanaForgeHost.pictures = java.util.function.BiFunction { name, seat -> ChimeraPictures.picture(name, seat) }
        forge.prepare(seats) { forgeAssetsDir() } // unpacked on Forge's start thread, not the UI thread
        ForgeGameService.start(app())
    }

    /**
     * Messages from the person in seat [from]. The host's transport also sends "guestLeft" (seatId: who dropped) from
     * the host's seat; a guest may only leave their own seat.
     */
    fun receive(message: JSONObject, from: EntityId = HUMAN_ID) {
        if (from != HUMAN_ID && message.optString("type") == "guestLeft") { forge.guestLeft(from.value); return }
        forge.handleFrom(from.value, message)
    }

    private val stopped = java.util.concurrent.atomic.AtomicBoolean()
    fun shutdown() { if (stopped.compareAndSet(false, true)) ForgeGameService.stop(app()); ChimeraLoading.reset(); forge.shutdown() }

    /** Compatibility entry point: a legacy Wi-Fi guest occupies seat two. */
    fun setGuest(seat: LocalSeat, guest: (JSONObject) -> Unit) = setGuest(AI_ID, seat, guest)

    /**
     * Wi-Fi and internet multiplayer: a person takes seat [seatId] with their own deck before the game starts. Forge
     * gives them their own player and questions; the bridge sends them their own view of the table through [guest].
     */
    fun setGuest(seatId: EntityId, seat: LocalSeat, guest: (JSONObject) -> Unit) {
        forge.addGuest(seatId.value, ArcanaForgeHost.Seat(seat.name, seat.deck, seat.commander)) { guest(it) }
    }

    companion object {
        val HUMAN_ID = EntityId("offline-you")
        val AI_ID = EntityId("offline-ai")
        /** Wi-Fi: the guest sits in the second seat. */
        val GUEST_ID: EntityId get() = AI_ID

        /** Unpacks the bundled Forge data (assets/forge-res.zip) once per APK build; returns the folder holding res/. */
        private fun app() = Class.forName("android.app.ActivityThread").getMethod("currentApplication").invoke(null) as android.app.Application

        @Synchronized
        fun forgeAssetsDir(): String {
            val app = app()
            val dir = File(app.filesDir, "forge")
            val marker = File(dir, ".unpacked")
            val stamp = app.packageManager.getPackageInfo(app.packageName, 0).lastUpdateTime.toString()
            if (!marker.exists() || marker.readText() != stamp) {
                dir.deleteRecursively()
                dir.mkdirs()
                ChimeraLoading.report(0.05f, "Unpacking Forge (first start after an install)…")
                var unpacked = 0
                ZipInputStream(app.assets.open("forge-res.zip").buffered()).use { zip ->
                    while (true) {
                        val entry = zip.nextEntry ?: break
                        if (++unpacked % 250 == 0) // ~30k files: creeps toward 34% without knowing the total
                            ChimeraLoading.report(0.05f + 0.29f * (1f - kotlin.math.exp(-unpacked / 9000f)), "Unpacking Forge (first start after an install)…")
                        val out = File(dir, entry.name)
                        if (!out.canonicalPath.startsWith(dir.canonicalPath)) continue
                        if (entry.isDirectory) out.mkdirs() else { out.parentFile?.mkdirs(); out.outputStream().use { zip.copyTo(it) } }
                    }
                }
                marker.writeText(stamp)
            }
            return dir.absolutePath + "/"
        }
    }
}
