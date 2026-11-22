package com.arcanatable

import android.content.Context
import android.util.Log
import java.io.File
import java.io.RandomAccessFile
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Owner, Nov 21: the diagnostic report kept only the most recent crash, so earlier out-of-memory crashes (a game that
 * stopped mid-turn, then the deck editor) couldn't be told apart. Every crash, from the app and from the separate Forge
 * game process, now goes into a small file both can write (SharedPreferences are not shared between processes), with
 * the memory in use at that moment. The report lists the newest [KEEP].
 */
internal object ChimeraCrashLog {
    private const val FILE = "chimera-crashes.txt"
    private const val KEEP = 6
    private const val SEPARATOR = "\n=====\n"

    /** "App: 412 of 512 MB in use" for this process. */
    fun memoryLine(): String {
        val rt = Runtime.getRuntime()
        val used = (rt.totalMemory() - rt.freeMemory()) / (1024 * 1024)
        return "$used of ${rt.maxMemory() / (1024 * 1024)} MB in use"
    }

    /** Free room left in this process's memory, in MB. */
    fun headroomMb(): Long {
        val rt = Runtime.getRuntime()
        return (rt.maxMemory() - (rt.totalMemory() - rt.freeMemory())) / (1024 * 1024)
    }

    /** Records an uncaught crash or a stopped process. Never throws; small allocations only (it may run during OOM). */
    fun record(context: Context, where: String, error: Throwable?, note: String? = null) {
        runCatching {
            val at = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(Date())
            val trace = error?.let { Log.getStackTraceString(it).take(6_000) } ?: note.orEmpty()
            val entry = "$at · $where · ${memoryLine()}\n$trace"
            val file = File(context.filesDir, FILE)
            RandomAccessFile(file, "rw").use { raf ->
                raf.channel.lock().use {
                    val old = ByteArray(raf.length().toInt().coerceAtMost(200_000)).also { raf.readFully(it) }.toString(Charsets.UTF_8)
                    val kept = (old.split(SEPARATOR).filter { it.isNotBlank() } + entry).takeLast(KEEP)
                    val bytes = kept.joinToString(SEPARATOR).toByteArray(Charsets.UTF_8)
                    raf.setLength(0)
                    raf.write(bytes)
                }
            }
        }
    }

    /** Newest first. */
    fun entries(context: Context): List<String> = runCatching {
        File(context.filesDir, FILE).takeIf { it.exists() }?.readText()?.split(SEPARATOR)?.filter { it.isNotBlank() }?.reversed()
    }.getOrNull().orEmpty()

    @Volatile private var installed = false

    /** Chains a recorder in front of whatever handler this process already has (once per process). */
    @Synchronized
    fun install(context: Context, where: String) {
        if (installed) return
        installed = true
        val app = context.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            record(app, where, error)
            previous?.uncaughtException(thread, error)
        }
    }
}
