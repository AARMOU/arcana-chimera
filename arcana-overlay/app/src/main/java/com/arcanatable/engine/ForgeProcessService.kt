package com.arcanatable.engine

import android.app.Service
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Messenger
import com.arcanatable.ChimeraArt
import com.arcanatable.ChimeraCrashLog
import org.arcanachimera.forgehost.ArcanaForgeHost
import org.json.JSONObject

/**
 * Owner, Nov 21: the Forge game process (android:process=":forge"). It holds Forge's card database and the game, with
 * its own memory; the app talks to it through LocalArgentumHost. One game at a time: a new game replaces the old one.
 * If this process runs out of memory, the app stays open, records it and tells the player.
 */
class ForgeProcessService : Service() {
    private val thread = HandlerThread("forge-ipc").also { it.start() }
    private val inbox = ForgeIpc.Reassembler()
    private val sendLock = Any()
    @Volatile private var client: Messenger? = null
    @Volatile private var session = ""
    @Volatile private var stops: Set<String> = emptySet()
    private var core: ForgeEngineCore? = null

    private val incoming = Messenger(Handler(thread.looper) { m ->
        runCatching { handle(m.what, m.data ?: Bundle(), m.replyTo) }.onFailure { e ->
            emit(session, null, JSONObject().put("type", "error").put("code", "FORGE_HOST")
                .put("message", "The Forge game process couldn't handle a message: ${e.message ?: e.javaClass.simpleName}"))
        }
        true
    })

    override fun onCreate() {
        super.onCreate()
        ChimeraCrashLog.install(this, "Forge game process")
    }

    override fun onBind(intent: Intent?): IBinder = incoming.binder

    override fun onUnbind(intent: Intent?): Boolean {
        Handler(thread.looper).post { stopCore() }
        stopSelf()
        return false
    }

    override fun onDestroy() {
        Handler(thread.looper).post { stopCore(); thread.quitSafely() }
        super.onDestroy()
    }

    private fun handle(what: Int, data: Bundle, replyTo: Messenger?) {
        val sid = data.getString("s").orEmpty()
        if (what == ForgeIpc.CREATE) {
            val payload = inbox.add(data) ?: return
            stopCore()
            session = sid
            client = replyTo
            val o = JSONObject(payload)
            val seats = o.optJSONArray("seats")?.let { a -> List(a.length()) { ForgeEngineCore.seatFrom(a.getJSONObject(it)) } }.orEmpty()
            stops = o.optJSONArray("stops")?.let { a -> List(a.length()) { a.optString(it) }.toSet() }.orEmpty()
            o.optJSONObject("art")?.let { art -> ChimeraArt.useSnapshot(art.keys().asSequence().associateWith { art.optString(it) }) }
            ChimeraLoading.forward = { f, text -> progress(sid, f, text) }
            core = ForgeEngineCore(
                seats,
                runCatching { ArcanaForgeHost.AiStrength.valueOf(o.optString("ai")) }.getOrDefault(ArcanaForgeHost.AiStrength.LITE),
                o.optString("board").takeIf { it.isNotBlank() },
                emit = { to, message -> emit(sid, to, message) },
                progress = { f, text -> progress(sid, f, text) },
                phaseStops = { stops },
                context = this,
            ).also {
                it.quietOpponentTurns = o.optBoolean("quiet")
                it.respondToOwnStack = o.optBoolean("ownStack")
            }
            return
        }
        if (sid != session) return // a message for a game that was already replaced
        val payload = inbox.add(data) ?: return
        when (what) {
            ForgeIpc.RECEIVE -> {
                data.getStringArrayList("stops")?.let { stops = it.toSet() }
                core?.receive(data.getString("from").orEmpty(), JSONObject(payload))
            }
            ForgeIpc.ADD_GUEST -> {
                val o = JSONObject(payload)
                core?.addGuest(o.getString("seatId"), ForgeEngineCore.seatFrom(o.getJSONObject("seat")))
            }
            ForgeIpc.FLAGS -> {
                val o = JSONObject(payload)
                core?.quietOpponentTurns = o.optBoolean("quiet")
                core?.respondToOwnStack = o.optBoolean("ownStack")
            }
            ForgeIpc.SHUTDOWN -> stopCore()
        }
    }

    private fun stopCore() {
        core?.let { runCatching { it.shutdown() } }
        core = null
    }

    private fun emit(sid: String, to: String?, message: JSONObject) {
        val c = client ?: return
        if (sid != session) return
        synchronized(sendLock) {
            runCatching { ForgeIpc.send(c, ForgeIpc.EMIT, sid, message.toString(), Bundle().apply { putString("to", to) }) }
        }
    }

    private fun progress(sid: String, f: Float, text: String) {
        val c = client ?: return
        if (sid != session) return
        synchronized(sendLock) {
            runCatching { ForgeIpc.send(c, ForgeIpc.PROGRESS, sid, "", Bundle().apply { putFloat("f", f); putString("t", text) }) }
        }
    }
}
