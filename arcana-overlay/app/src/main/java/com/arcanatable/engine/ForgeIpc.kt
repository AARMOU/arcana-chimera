package com.arcanatable.engine

import android.os.Bundle
import android.os.Message
import android.os.Messenger
import java.util.concurrent.atomic.AtomicLong

/**
 * Owner, Nov 21: Forge in its own process, with its own memory. Android gives each process of the app its own 512 MB
 * (largeHeap), so Forge's card database no longer shares the app's memory with the table, the deck editor and pictures,
 * and if Forge does run out, the app stays up and says so instead of closing.
 *
 * The two processes exchange the same JSON messages Arcana and Forge always did, as text. Binder limits one
 * transaction to about 1 MB shared by everything in flight, and a busy four-player table is a few hundred KB, so long
 * messages travel in pieces and are put back together on the other side.
 */
internal object ForgeIpc {
    /** Forge in its own process. False: in the app's process, as before (also the automatic fallback). */
    const val SEPARATE_PROCESS = true

    // app -> Forge process
    const val CREATE = 1
    const val RECEIVE = 2
    const val ADD_GUEST = 3
    const val FLAGS = 4
    const val SHUTDOWN = 5
    // Forge process -> app
    const val EMIT = 10
    const val PROGRESS = 11

    private const val CHUNK = 60_000 // characters; about 120 KB in a Parcel
    private val ids = AtomicLong()

    /** Sends [payload] as one or more messages. Throws RemoteException if the other process is gone. */
    fun send(target: Messenger, what: Int, session: String, payload: String, extras: Bundle? = null, replyTo: Messenger? = null) {
        val id = ids.incrementAndGet()
        val parts = maxOf(1, (payload.length + CHUNK - 1) / CHUNK)
        for (i in 0 until parts) {
            val m = Message.obtain(null, what)
            m.data = (if (extras != null) Bundle(extras) else Bundle()).apply {
                putString("s", session)
                putLong("id", id)
                putInt("i", i)
                putInt("n", parts)
                putString("p", payload.substring(i * CHUNK, minOf(payload.length, (i + 1) * CHUNK)))
            }
            if (replyTo != null) m.replyTo = replyTo
            target.send(m)
        }
    }

    /** Puts pieces back together. Use from one thread only (each side reads on its own handler thread). */
    class Reassembler {
        private val pending = HashMap<Long, Array<String?>>()

        /** The whole payload once its last piece is in, else null. */
        fun add(data: Bundle): String? {
            val parts = data.getInt("n", 1)
            val piece = data.getString("p").orEmpty()
            if (parts <= 1) return piece
            val id = data.getLong("id")
            val slots = pending.getOrPut(id) { arrayOfNulls(parts) }
            slots[data.getInt("i").coerceIn(0, parts - 1)] = piece
            if (slots.any { it == null }) return null
            pending.remove(id)
            return slots.joinToString("")
        }
    }
}
