package com.lucid.appstore.download.events

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.pm.ServiceInfo
import androidx.work.ForegroundInfo
import com.lucid.appstore.download.model.Config
import com.lucid.appstore.download.model.NetworkMode
import com.lucid.appstore.download.model.Progress
import com.lucid.appstore.download.policy.NetworkClassifier
import com.lucid.appstore.download.run.CancelReceiver

/**
 * Owns the `download_status` channel and two notifications: the foreground progress notification and the
 * status (waiting, paused, complete) notification. Distinct IDs keep them independent (FR-21).
 */
class NotificationController(private val context: Context) : EventSink {
    private val nm = context.getSystemService(NotificationManager::class.java)

    init {
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_STATUS, "Download status", NotificationManager.IMPORTANCE_LOW).apply {
                setSound(null, null)
            },
        )
    }

    /** Foreground info for the `dataSync` worker. Called only when the engine is about to run (FR-20). */
    fun foregroundInfo(progress: Progress?): ForegroundInfo =
        ForegroundInfo(ID_PROGRESS, progressNotification(progress), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)

    override fun onEvent(event: ServiceEvent) {
        when (event.type) {
            EventType.BLOCKED_DRIVING -> status("Download waiting: vehicle must be parked")
            EventType.BLOCKED_BATTERY -> status("Download waiting: charge or battery above ${event.config?.batteryThresholdPercent ?: Config().batteryThresholdPercent}%")
            EventType.BLOCKED_NETWORK -> status(networkWaitingText(event))
            EventType.PAUSED_DRIVING -> status("Download paused: vehicle moving")
            EventType.PAUSED_BATTERY -> status("Download paused: low battery")
            EventType.PAUSED_NETWORK -> status("Download paused: network not allowed")
            EventType.DOWNLOAD_STARTED, EventType.RESUMED -> nm.cancel(ID_STATUS)
            EventType.DOWNLOAD_PROGRESS -> nm.notify(ID_PROGRESS, progressNotification(event.progress))
            EventType.DOWNLOAD_COMPLETE -> { nm.cancel(ID_PROGRESS); status("Download complete", cancellable = false) }
            EventType.CANCELLED, EventType.SERVICE_DISABLED -> { nm.cancel(ID_PROGRESS); nm.cancel(ID_STATUS) }
            else -> Unit
        }
    }

    private fun networkWaitingText(e: ServiceEvent): String {
        val cls = e.device?.let { NetworkClassifier.classify(it.network) }
        return when {
            cls != null && NetworkClassifier.isTethered(cls) -> "Download waiting: tethering not allowed"
            e.config?.networkMode == NetworkMode.WIFI_OR_CELLULAR && e.device?.cellularSubscribed == true -> "Download waiting for Wi-Fi or cellular"
            else -> "Download waiting for unmetered Wi-Fi"
        }
    }

    private fun status(text: String, cancellable: Boolean = true) {
        val b = builder().setContentTitle(text).setOngoing(cancellable)
        if (cancellable) b.addAction(cancelAction())
        nm.notify(ID_STATUS, b.build())
    }

    private fun progressNotification(p: Progress?): Notification {
        val total = p?.total
        val percent = if (p != null && total != null && total > 0) (p.downloaded * 100 / total).toInt() else 0
        return builder()
            .setContentTitle("Downloading update")
            .setContentText(if (total != null) "$percent%" else "")
            .setProgress(100, percent, total == null)
            .setOngoing(true)
            .addAction(cancelAction())
            .build()
    }

    private fun builder() = Notification.Builder(context, CHANNEL_STATUS)
        .setSmallIcon(android.R.drawable.stat_sys_download)
        .setOnlyAlertOnce(true)

    private fun cancelAction(): Notification.Action {
        val pi = PendingIntent.getBroadcast(context, 0, CancelReceiver.intent(context), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        return Notification.Action.Builder(null, "Cancel", pi).build()
    }

    companion object {
        const val CHANNEL_STATUS = "download_status"
        const val ID_PROGRESS = 1001
        const val ID_STATUS = 1002
    }
}
