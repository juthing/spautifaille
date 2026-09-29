package com.spautifaille.data.download

import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.work.ForegroundInfo
import androidx.work.WorkManager
import java.util.UUID

/**
 * Notification de premier plan d'un téléchargement (titre, progression, action « Annuler »).
 * Libellés en dur (français) : `:data` n'a pas de ressources.
 */
internal class DownloadNotifications(private val context: Context) {

    fun foregroundInfo(
        trackId: String,
        workId: UUID,
        title: String,
        downloadedBytes: Long,
        totalBytes: Long?,
    ): ForegroundInfo {
        val notification = build(workId, title, downloadedBytes, totalBytes)
        val id = notificationId(trackId)
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(id, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo(id, notification)
        }
    }

    fun build(workId: UUID, title: String, downloadedBytes: Long, totalBytes: Long?): Notification {
        ensureChannel()
        val percent = if (totalBytes != null && totalBytes > 0) {
            (downloadedBytes * 100 / totalBytes).toInt().coerceIn(0, 100)
        } else {
            null
        }
        val cancelIntent = WorkManager.getInstance(context).createCancelPendingIntent(workId)
        return NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(title)
            .setContentText(if (percent != null) "Téléchargement · $percent %" else "Téléchargement…")
            .setProgress(100, percent ?: 0, percent == null)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setContentIntent(launchIntent())
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Annuler", cancelIntent)
            .build()
    }

    private fun launchIntent(): PendingIntent? {
        val intent = context.packageManager.getLaunchIntentForPackage(context.packageName) ?: return null
        return PendingIntent.getActivity(context, 0, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    private fun ensureChannel() {
        val channel = NotificationChannelCompat.Builder(CHANNEL_ID, NotificationManagerCompat.IMPORTANCE_LOW)
            .setName("Téléchargements")
            .setDescription("Progression des titres téléchargés pour l'écoute hors ligne")
            .setShowBadge(false)
            .build()
        NotificationManagerCompat.from(context).createNotificationChannel(channel)
    }

    companion object {
        const val CHANNEL_ID = "downloads"
        private const val NOTIFICATION_BASE_ID = 0x0D0_0000

        fun notificationId(trackId: String): Int = NOTIFICATION_BASE_ID + (trackId.hashCode() and 0xFFFF)
    }
}
