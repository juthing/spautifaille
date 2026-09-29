package com.spautifaille.data.importer

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.work.ForegroundInfo
import com.spautifaille.data.R
import com.spautifaille.data.local.ImportJobEntity
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject

/** Notifications des imports : progression (premier plan du worker) et récapitulatif final. */
class ImportNotifier @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val manager: NotificationManager? get() = context.getSystemService(NotificationManager::class.java)

    private fun ensureChannel() {
        val nm = manager ?: return
        if (nm.getNotificationChannel(CHANNEL_ID) == null) {
            nm.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    context.getString(R.string.data_import_channel_name),
                    NotificationManager.IMPORTANCE_LOW,
                ).apply {
                    description = context.getString(R.string.data_import_channel_description)
                    setShowBadge(false)
                },
            )
        }
    }

    /** Informations de premier plan pour `setForeground` (obligatoires même sans permission de notification). */
    fun foregroundInfo(jobId: Long, job: ImportJobEntity?): ForegroundInfo {
        ensureChannel()
        val notification = progressNotification(job)
        val id = progressNotificationId(jobId)
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(id, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo(id, notification)
        }
    }

    /** Met à jour la notification de progression déjà affichée. */
    fun updateProgress(jobId: Long, job: ImportJobEntity) {
        if (!canNotify()) return
        manager?.notify(progressNotificationId(jobId), progressNotification(job))
    }

    /** « Import terminé : X titres, Y à vérifier, Z introuvables » (ignoré sans permission de notification). */
    fun showCompleted(job: ImportJobEntity) {
        if (!canNotify()) return
        ensureChannel()
        val text = context.getString(R.string.data_import_completed, job.matched, job.needsReview, job.notFound)
        val notification = builder()
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle(job.playlistName)
            .setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(text))
            .setContentIntent(launchIntent())
            .setAutoCancel(true)
            .build()
        manager?.notify(completedNotificationId(job.id), notification)
    }

    private fun progressNotification(job: ImportJobEntity?): Notification {
        val builder = builder()
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(job?.playlistName ?: context.getString(R.string.data_import_notification_title))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(launchIntent())
        if (job != null && job.total > 0) {
            builder.setContentText(context.getString(R.string.data_import_progress, job.processed, job.total))
                .setProgress(job.total, job.processed, false)
        } else {
            builder.setContentText(context.getString(R.string.data_import_preparing)).setProgress(0, 0, true)
        }
        return builder.build()
    }

    @Suppress("DEPRECATION")
    private fun builder(): Notification.Builder = Notification.Builder(context, CHANNEL_ID)

    private fun launchIntent(): PendingIntent? {
        val intent = context.packageManager.getLaunchIntentForPackage(context.packageName) ?: return null
        return PendingIntent.getActivity(context, 0, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    private fun canNotify(): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            return false
        }
        return manager?.areNotificationsEnabled() != false
    }

    companion object {
        const val CHANNEL_ID = "imports"
        private const val PROGRESS_BASE_ID = 0x1A00_0000
        private const val COMPLETED_BASE_ID = 0x1B00_0000
        fun progressNotificationId(jobId: Long) = PROGRESS_BASE_ID + (jobId % 0xFFFF).toInt()
        fun completedNotificationId(jobId: Long) = COMPLETED_BASE_ID + (jobId % 0xFFFF).toInt()
    }
}
