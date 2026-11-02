package io.github.mvoloch.voloz.android.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import io.github.mvoloch.voloz.android.R
import io.github.mvoloch.voloz.android.VolozApp

/**
 * Microphone-type foreground service held only while a dictation is being
 * recorded. It shows the "Voloz está gravando" notification and gives the
 * process the foreground status Android 14+ expects for mic capture.
 */
class RecordingService : Service() {
    companion object {
        private const val TAG = "VolozRecordingService"
        private const val CHANNEL_ID = "dictation"
        private const val NOTIFICATION_ID = 1
        const val ACTION_FINISH = "io.github.mvoloch.voloz.android.FINISH"
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val controller = (application as VolozApp).controller
        if (intent?.action == ACTION_FINISH) {
            controller.finishFromNotification()
            if (!controller.isForegroundServiceActive) stopSelf()
            return START_NOT_STICKY
        }
        val started = try {
            ensureChannel()
            val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            } else {
                0
            }
            ServiceCompat.startForeground(this, NOTIFICATION_ID, buildNotification(), type)
            true
        } catch (e: Exception) {
            // e.g. ForegroundServiceStartNotAllowedException / SecurityException on 14+.
            Log.w(TAG, "startForeground failed", e)
            false
        }
        controller.onForegroundServiceResult(started)
        if (!started) stopSelf()
        return START_NOT_STICKY
    }

    private fun ensureChannel() {
        val nm = getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL_ID) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, getString(R.string.notif_channel_name), NotificationManager.IMPORTANCE_LOW),
            )
        }
    }

    private fun buildNotification() = NotificationCompat.Builder(this, CHANNEL_ID)
        .setSmallIcon(R.drawable.ic_stat_voloz)
        .setContentTitle(getString(R.string.notif_recording_title))
        .setContentText(getString(R.string.notif_recording_text))
        .setOngoing(true)
        .setSilent(true)
        .setCategory(NotificationCompat.CATEGORY_SERVICE)
        .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
        .addAction(
            0,
            getString(R.string.notif_action_finish),
            PendingIntent.getService(
                this, 0,
                Intent(this, RecordingService::class.java).setAction(ACTION_FINISH),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            ),
        )
        .build()
}
