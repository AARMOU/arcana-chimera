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
    var quietOpponentTurns: Boolean = false
    /** Arcana option "respond to my own spells": Forge's own priority handling applies; kept for the settings screen. */
    var respondToOwnStack: Boolean = false
    val isWifi: Boolean get() = false
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
        forge.prepare(seats) { forgeAssetsDir() } // unpacked on Forge's start thread, not the UI thread
        ForgeGameService.start(app())
    }

    fun receive(message: JSONObject, from: EntityId = HUMAN_ID) {
        if (from == HUMAN_ID) forge.handle(message)
    }

    fun shutdown() { ForgeGameService.stop(app()); forge.shutdown() }

    /** Wi-Fi two-person games aren't on Forge yet (later milestone): tell both sides plainly. */
    fun setGuest(seat: LocalSeat, guest: (JSONObject) -> Unit) = setGuest(AI_ID, seat, guest)

    fun setGuest(seatId: EntityId, seat: LocalSeat, guest: (JSONObject) -> Unit) {
        val note = JSONObject().put("type", "error").put("code", "WIFI").put("message", "Wi-Fi games aren't available in the Forge build yet.")
        guest(note)
        emit(JSONObject(note.toString()))
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
                ZipInputStream(app.assets.open("forge-res.zip").buffered()).use { zip ->
                    while (true) {
                        val entry = zip.nextEntry ?: break
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
