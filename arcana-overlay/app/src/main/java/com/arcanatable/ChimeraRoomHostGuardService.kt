package com.arcanatable

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager

/**
 * User-started multiplayer HOST ONLY. Holds the CPU and Wi-Fi connection while
 * the Fold is closed, without depending on a Compose screen or Activity.
 *
 * This is not a persistence engine: Android Doze, user force-stop and process
 * death can still interrupt the game. The directed relay owns reconnection.
 */
internal class ChimeraRoomHostGuardService : Service() {
    private var cpuLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null

    override fun onCreate() {
        super.onCreate()
        val channel = NotificationChannel(CHANNEL, "Commander room hosting", NotificationManager.IMPORTANCE_LOW)
        (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
            .createNotificationChannel(channel)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val openApp = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setContentTitle("Arcana Table: hosting Commander")
            .setContentText("Room hosting is active while your screen is locked. Uses battery.")
            .setCategory(Notification.CATEGORY_SERVICE)
            .setContentIntent(openApp)
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        } else startForeground(ID, notification)
        acquireLocks()
        return START_NOT_STICKY // If Android kills the game, sockets and Forge state aren't restored.
    }

    private fun acquireLocks() {
        if (cpuLock?.isHeld != true) {
            cpuLock = (getSystemService(Context.POWER_SERVICE) as PowerManager)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "ArcanaChimera:CommanderHost")
                .apply { setReferenceCounted(false); acquire() }
        }
        if (wifiLock?.isHeld != true) {
            wifiLock = (applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager)
                .createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "ArcanaChimera:CommanderHost")
                .apply { setReferenceCounted(false); acquire() }
        }
    }

    override fun onDestroy() {
        if (cpuLock?.isHeld == true) cpuLock?.release()
        if (wifiLock?.isHeld == true) wifiLock?.release()
        cpuLock = null
        wifiLock = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val CHANNEL = "chimera_commander_host"
        private const val ID = 7302

        /** Must be called from foreground when the owner explicitly opens a host room. */
        fun start(context: Context): Boolean = runCatching {
            val intent = Intent(context, ChimeraRoomHostGuardService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(intent)
            else context.startService(intent)
        }.isSuccess

        /** Explicit host-room shutdown; never called for a transient Compose disposal. */
        fun stop(context: Context) {
            context.stopService(Intent(context, ChimeraRoomHostGuardService::class.java))
        }
    }
}
