package com.tulipskun.aixodia.term

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.tulipskun.aixodia.AixodiaApp
import com.tulipskun.aixodia.MainActivity
import com.tulipskun.aixodia.R

/**
 * Keeps the process (and every open terminal socket) alive while the user is
 * looking at a live terminal, including after switching to another app. It
 * does not own the shell: agent turns and terminals run on the engine either
 * way.
 *
 * Swiping the task away is the explicit stop: the service closes the terminal
 * sockets before it goes, so nothing is left attached behind the user's back.
 */
class TerminalLiveService : Service() {
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        ensureChannel()
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle("Terminal connected")
            .setContentText("Tabs stay attached while the app is in the background")
            .setOngoing(true)
            .setContentIntent(open)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
        startForeground(NOTIFICATION_ID, notification)
        // Not sticky: if Android kills the process, the terminal reattaches on
        // the next open. The service is here to make app switching cheap, not
        // to fight the system for permanent survival.
        return START_NOT_STICKY
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        // The task is gone from recents. Close the terminal WebSockets and the
        // chat stream now instead of leaving them to a process that may keep
        // running for a while.
        TerminalConnections.closeAll()
        (application as? AixodiaApp)?.container?.chat?.close()
        stopSelf()
        super.onTaskRemoved(rootIntent)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(CHANNEL) != null) return
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL, "Live terminal", NotificationManager.IMPORTANCE_LOW)
        )
    }

    companion object {
        private const val CHANNEL = "terminal-live"
        private const val NOTIFICATION_ID = 7101

        fun start(context: Context) {
            ContextCompat.startForegroundService(
                context,
                Intent(context, TerminalLiveService::class.java),
            )
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, TerminalLiveService::class.java))
        }
    }
}
