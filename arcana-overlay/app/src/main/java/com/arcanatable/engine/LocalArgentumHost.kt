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
    // Owner, Nov 21: Forge runs in its own process (ForgeProcessService) with its own memory; this class keeps the
    // same name, constructor and calls as before and passes messages across. If that process can't be started, Forge
    // runs in this process exactly as it used to (ForgeEngineCore either way).
    private val guests = java.util.concurrent.ConcurrentHashMap<String, (JSONObject) -> Unit>()
    // Owner, Nov 23: table chat in multiplayer games. Forge never sees it; this host passes it between the players.
    private val humanName = human.name
    private val guestNames = java.util.concurrent.ConcurrentHashMap<String, String>()
    private val chatOut = java.util.concurrent.Executors.newSingleThreadExecutor()
    @Volatile private var quiet = false
    @Volatile private var ownStack = false
    private val stopped = java.util.concurrent.atomic.AtomicBoolean()
    private val remote: ForgeRemote?
    private val local: ForgeEngineCore?

    var quietOpponentTurns: Boolean
        get() = quiet
        set(value) { quiet = value; local?.quietOpponentTurns = value; remote?.flags(quiet, ownStack) }
    /** Arcana option "respond to my own spells": off (default) lets your own spells and abilities resolve without a stop. */
    var respondToOwnStack: Boolean
        get() = ownStack
        set(value) { ownStack = value; local?.respondToOwnStack = value; remote?.flags(quiet, ownStack) }
    /** True once a Wi-Fi or internet friend has a seat. */
    val isWifi: Boolean get() = guests.isNotEmpty()

    init {
        val seats = (listOf(human, opponent) + extraOpponents.take(2)).map { ArcanaForgeHost.Seat(it.name, it.deck, it.commander) }
        // Arcana's AI difficulty → how hard Forge's AI searches. Easy: Forge's plain heuristics; Normal: Forge's
        // phone setting; Hard/Brutal: Forge's desktop search (Forge still falls back if the phone's heap is small).
        val strength = when ((listOf(opponent) + extraOpponents).maxOf { it.difficulty }) {
            AiDifficulty.EASY -> ArcanaForgeHost.AiStrength.OFF
            AiDifficulty.NORMAL -> ArcanaForgeHost.AiStrength.LITE
            else -> ArcanaForgeHost.AiStrength.MAX
        }
        // Tutorial lessons (owner, Nov 10): the lesson's board, as a Forge game-state script (ChimeraTutorialBoards).
        val board = resumeCheckpoint?.let { runCatching { JSONObject(it).optString("forgeBoard") }.getOrNull() }?.takeIf { it.isNotBlank() }
        ChimeraLoading.reset()
        ChimeraLoading.hosting = true
        ChimeraPictures.warm(app())
        remote = if (ForgeIpc.SEPARATE_PROCESS) ForgeRemote(app(), ::deliver, ::processLost).takeIf { r ->
            r.start(JSONObject()
                .put("seats", org.json.JSONArray(seats.map { ForgeEngineCore.seatJson(it) }))
                .put("ai", strength.name)
                .put("board", board ?: "")
                .put("stops", org.json.JSONArray(com.arcanatable.ChimeraPhaseStops.stops.toList()))
                .put("art", JSONObject(com.arcanatable.ChimeraArt.snapshot()))
                .put("quiet", quiet).put("ownStack", ownStack)
                .toString())
        } else null
        local = if (remote != null) null else ForgeEngineCore(
            seats, strength, board,
            emit = ::deliver,
            progress = { f, what -> ChimeraLoading.report(f, what) },
            phaseStops = { com.arcanatable.ChimeraPhaseStops.stops },
            context = app(),
        )
        ForgeGameService.start(app())
    }

    private fun deliver(to: String?, message: JSONObject) {
        if (to == null) emit(message) else guests[to]?.invoke(message)
    }

    /** The Forge game process stopped during the game (most likely out of memory): the app stays open and says so. */
    private fun processLost(reason: String) {
        if (stopped.get()) return
        com.arcanatable.ChimeraCrashLog.record(app(), "Forge game process", null, "Stopped during a game: $reason")
        emit(JSONObject().put("type", "error").put("code", "FORGE_HOST").put("message",
            "The game engine stopped (it may have run out of memory). This game can't continue; start a new one from Home."))
    }

    /**
     * Messages from the person in seat [from]. The host's transport also sends "guestLeft" (seatId: who dropped) from
     * the host's seat; a guest may only leave their own seat.
     */
    fun receive(message: JSONObject, from: EntityId = HUMAN_ID) {
        if (message.optString("type") == "arcanaChat") { relayChat(message, from.value); return }
        local?.receive(from.value, message)
        remote?.receive(from.value, message, com.arcanatable.ChimeraPhaseStops.stops)
    }

    /**
     * A chat message from seat [from]: shown on the host's table (if a guest wrote it) and sent to every other person.
     * The name comes from the seat, not the message, and a message is at most 500 characters.
     */
    private fun relayChat(message: JSONObject, from: String) {
        val text = message.optString("text").trim().take(500)
        if (text.isEmpty()) return
        val author = if (from == HUMAN_ID.value) humanName else guestNames[from] ?: return
        val out = JSONObject().put("type", "arcanaChat").put("author", author).put("text", text).toString()
        if (from != HUMAN_ID.value) emit(JSONObject(out))
        runCatching {
            chatOut.execute { guests.forEach { (seat, send) -> if (seat != from) runCatching { send(JSONObject(out)) } } }
        }
    }

    fun shutdown() {
        chatOut.shutdown()
        if (stopped.compareAndSet(false, true)) ForgeGameService.stop(app())
        ChimeraLoading.reset()
        local?.shutdown()
        remote?.close()
    }

    /** Compatibility entry point: a legacy Wi-Fi guest occupies seat two. */
    fun setGuest(seat: LocalSeat, guest: (JSONObject) -> Unit) = setGuest(AI_ID, seat, guest)

    /**
     * Wi-Fi and internet multiplayer: a person takes seat [seatId] with their own deck before the game starts. Forge
     * gives them their own player and questions; the bridge sends them their own view of the table through [guest].
     */
    fun setGuest(seatId: EntityId, seat: LocalSeat, guest: (JSONObject) -> Unit) {
        guests[seatId.value] = guest
        guestNames[seatId.value] = seat.name
        val forgeSeat = ArcanaForgeHost.Seat(seat.name, seat.deck, seat.commander)
        local?.addGuest(seatId.value, forgeSeat)
        remote?.addGuest(seatId.value, forgeSeat)
    }

    companion object {
        val HUMAN_ID = EntityId("offline-you")
        val AI_ID = EntityId("offline-ai")
        /** Wi-Fi: the guest sits in the second seat. */
        val GUEST_ID: EntityId get() = AI_ID

        /** Unpacks the bundled Forge data (assets/forge-res.zip) once per APK build; returns the folder holding res/. */
        internal fun app() = Class.forName("android.app.ActivityThread").getMethod("currentApplication").invoke(null) as android.app.Application

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
