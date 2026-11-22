package com.arcanatable.engine

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Messenger
import org.arcanachimera.forgehost.ArcanaForgeHost
import org.json.JSONObject

/**
 * The app's side of the Forge game process (owner, Nov 21). Messages sent before the process is ready wait in order;
 * replies arrive on their own thread, as Forge's messages always did. If the process ends during a game, [lost] is
 * told once.
 */
internal class ForgeRemote(
    private val app: Context,
    private val deliver: (to: String?, message: JSONObject) -> Unit,
    private val lost: (reason: String) -> Unit,
) {
    private val session = java.util.UUID.randomUUID().toString()
    private val thread = HandlerThread("forge-ipc-app").also { it.start() }
    private val handler = Handler(thread.looper)
    private val inbox = ForgeIpc.Reassembler()
    private val lock = Any()
    private var service: Messenger? = null
    private val waiting = ArrayList<Triple<Int, String, Bundle?>>()
    private var bound = false
    @Volatile private var closed = false

    private val reply = Messenger(Handler(thread.looper) { m ->
        val data = m.data
        if (data != null && data.getString("s") == session) runCatching {
            when (m.what) {
                ForgeIpc.EMIT -> inbox.add(data)?.let { deliver(data.getString("to"), JSONObject(it)) }
                ForgeIpc.PROGRESS -> ChimeraLoading.report(data.getFloat("f"), data.getString("t").orEmpty())
            }
        }
        true
    })

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            if (binder == null) { gone("the game process didn't answer"); return }
            runCatching { binder.linkToDeath({ gone("the game process ended") }, 0) }
            synchronized(lock) {
                if (closed) return
                service = Messenger(binder)
                val queued = waiting.toList()
                waiting.clear()
                queued.forEach { (what, payload, extras) -> post(what, payload, extras) }
            }
        }
        override fun onServiceDisconnected(name: ComponentName?) = gone("the game process ended")
        override fun onBindingDied(name: ComponentName?) = gone("the game process ended")
        override fun onNullBinding(name: ComponentName?) = gone("the game process didn't answer")
    }

    /** Starts the game process and sends it the new game. False: it couldn't be started (use this process instead). */
    fun start(createPayload: String): Boolean {
        bound = runCatching {
            app.bindService(Intent(app, ForgeProcessService::class.java), connection, Context.BIND_AUTO_CREATE or Context.BIND_IMPORTANT)
        }.getOrDefault(false)
        if (!bound) { runCatching { app.unbindService(connection) }; thread.quitSafely(); return false }
        send(ForgeIpc.CREATE, createPayload)
        return true
    }

    fun receive(from: String, message: JSONObject, stops: Set<String>) =
        send(ForgeIpc.RECEIVE, message.toString(), Bundle().apply { putString("from", from); putStringArrayList("stops", ArrayList(stops)) })

    fun addGuest(seatId: String, seat: ArcanaForgeHost.Seat) =
        send(ForgeIpc.ADD_GUEST, JSONObject().put("seatId", seatId).put("seat", ForgeEngineCore.seatJson(seat)).toString())

    fun flags(quiet: Boolean, ownStack: Boolean) =
        send(ForgeIpc.FLAGS, JSONObject().put("quiet", quiet).put("ownStack", ownStack).toString())

    /** The game is over or abandoned: tell the process and let it go. */
    fun close() {
        synchronized(lock) {
            if (closed) return
            post(ForgeIpc.SHUTDOWN, "", null)
            closed = true
        }
        if (bound) runCatching { app.unbindService(connection) }
        handler.postDelayed({ thread.quitSafely() }, 2_000)
    }

    private fun send(what: Int, payload: String, extras: Bundle? = null) {
        synchronized(lock) {
            if (closed) return
            if (service == null) waiting += Triple(what, payload, extras) else post(what, payload, extras)
        }
    }

    /** Caller holds [lock]. */
    private fun post(what: Int, payload: String, extras: Bundle?) {
        val target = service ?: return
        try {
            ForgeIpc.send(target, what, session, payload, extras, reply)
        } catch (e: android.os.RemoteException) {
            handler.post { gone("the game process ended") }
        }
    }

    private fun gone(reason: String) {
        synchronized(lock) {
            if (closed) return
            closed = true
            service = null
        }
        if (bound) runCatching { app.unbindService(connection) }
        handler.post { lost(reason) }
        handler.postDelayed({ thread.quitSafely() }, 2_000)
    }
}
