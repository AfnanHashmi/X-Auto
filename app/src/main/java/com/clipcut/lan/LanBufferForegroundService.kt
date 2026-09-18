package com.clipcut.lan

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner

private const val CHANNEL_ID = "lan_buffer"
private const val NOTIFICATION_ID = 1001
const val ACTION_START_LAN_BUFFER = "com.clipcut.lan.action.START"
const val ACTION_STOP_LAN_BUFFER = "com.clipcut.lan.action.STOP"

/**
 * Keeps [LanBufferSession] alive while the app is backgrounded, behind a persistent "running in
 * background" notification -- purely user-started/-stopped (the "Start Buffer" / "Kill background
 * processes" buttons in [LanBufferActivity], or the notification's own Stop action), never
 * auto-started or auto-restarted. A foreground service is the standard Android mechanism for this:
 * it raises process priority so the OS doesn't casually kill the buffer, and the ongoing
 * notification is mandatory (and non-swipeable) by design while a foreground service is running,
 * which doubles as the "this is still recording" indicator the user asked for.
 *
 * Also owns muting the live player while the whole app (not just this screen) is backgrounded --
 * registered against [ProcessLifecycleOwner] rather than any one Activity's lifecycle, so leaving
 * the app for the home screen or another app silences the stream, but navigating between screens
 * inside ClipCut does not. This is independent of (and composes with) Preview's own mute -- see
 * [LanBufferSession.setBackgroundMuted] / `setPreviewMuted`.
 */
class LanBufferForegroundService : Service() {
    private val processLifecycleObserver = object : DefaultLifecycleObserver {
        override fun onStart(owner: LifecycleOwner) = LanBufferSession.setBackgroundMuted(false)
        override fun onStop(owner: LifecycleOwner) = LanBufferSession.setBackgroundMuted(true)
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        ProcessLifecycleOwner.get().lifecycle.addObserver(processLifecycleObserver)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP_LAN_BUFFER -> {
                stopSelf()
                return START_NOT_STICKY
            }
            else -> {
                LanBufferSession.start(applicationContext)
                startForeground(NOTIFICATION_ID, buildNotification())
            }
        }
        // Not START_STICKY: if the OS kills this process under memory pressure, the buffer and its
        // recorded chunks are gone anyway -- restarting an empty service isn't useful, and it would
        // violate "purely user controlled" by resurrecting something the user didn't ask for again.
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        ProcessLifecycleOwner.get().lifecycle.removeObserver(processLifecycleObserver)
        LanBufferSession.stop()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun buildNotification(): Notification {
        val stopIntent = Intent(this, LanBufferForegroundService::class.java).setAction(ACTION_STOP_LAN_BUFFER)
        val stopPendingIntent = PendingIntent.getService(
            this,
            0,
            stopIntent,
            PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("ClipCut LAN buffer running")
            .setContentText("Recording the LAN feed in the background for scrubbing/export.")
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .addAction(0, "Stop", stopPendingIntent)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(NotificationManager::class.java)
        val channel = NotificationChannel(
            CHANNEL_ID,
            "LAN buffer",
            NotificationManager.IMPORTANCE_LOW, // status only -- no sound/heads-up for this.
        )
        manager.createNotificationChannel(channel)
    }

    companion object {
        fun start(context: Context) {
            val intent = Intent(context, LanBufferForegroundService::class.java).setAction(ACTION_START_LAN_BUFFER)
            context.startForegroundService(intent)
        }

        fun stop(context: Context) {
            context.startService(Intent(context, LanBufferForegroundService::class.java).setAction(ACTION_STOP_LAN_BUFFER))
        }
    }
}
