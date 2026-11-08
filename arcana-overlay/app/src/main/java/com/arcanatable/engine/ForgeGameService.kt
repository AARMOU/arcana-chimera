package com.arcanatable.engine

// CHIMERA OVERLAY (not Arcana source). Keeps the app process alive while a Forge game is open, so switching
// apps doesn't let Android reclaim the (large) Forge game and drop the player back to the home screen.

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder

class ForgeGameService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL, "Game in progress", NotificationManager.IMPORTANCE_LOW))
        val open = packageManager.getLaunchIntentForPackage(packageName)?.let {
            PendingIntent.getActivity(this, 0, it, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        }
        val notification = Notification.Builder(this, CHANNEL)
            .setSmallIcon(applicationInfo.icon)
            .setContentTitle("Game in progress")
            .setContentText("Your offline game keeps running while you're in other apps.")
            .setOngoing(true)
            .setContentIntent(open)
            .build()
        if (Build.VERSION.SDK_INT >= 34) startForeground(ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        else startForeground(ID, notification)
        return START_NOT_STICKY // the game lives in the app process; nothing to rebuild if it's gone
    }

    override fun onTaskRemoved(rootIntent: Intent?) { stopSelf(); super.onTaskRemoved(rootIntent) }

    companion object {
        private const val CHANNEL = "forge_game"
        private const val ID = 7301

        fun start(context: Context) = runCatching { context.startForegroundService(Intent(context, ForgeGameService::class.java)) }
        fun stop(context: Context) = runCatching { context.stopService(Intent(context, ForgeGameService::class.java)) }
    }
}
