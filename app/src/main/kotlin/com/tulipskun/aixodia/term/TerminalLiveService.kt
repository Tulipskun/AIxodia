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
import com.tulipskun.aixodia.MainActivity
import com.tulipskun.aixodia.R

/**
 * Keeps the process (and the native terminal socket) alive while the user is
 * looking at a live terminal, including with the screen folded. It does not
 * own the shell: agent turns and terminals run on the engine either way.
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
            .setContentTitle("Terminal live")
            .setContentText("The shell keeps running on your machine")
            .setOngoing(true)
            .setContentIntent(open)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
        startForeground(NOTIFICATION_ID, notification)
        return START_STICKY
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
