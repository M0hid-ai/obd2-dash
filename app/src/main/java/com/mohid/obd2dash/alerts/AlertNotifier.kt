package com.mohid.obd2dash.alerts

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.net.Uri
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.mohid.obd2dash.MainActivity
import com.mohid.obd2dash.R

/**
 * The audible half of an alert. The persistent in-app banner is driven
 * separately off the same [ActiveAlert] list, because a sound alone is easy to
 * miss while driving and a notification is easy to swipe away by accident.
 *
 * The chime is a short two-tone dash bell, deliberately not speech.
 */
class AlertNotifier(private val context: Context) {

    companion object {
        // Channel sound is fixed at creation time, so the id carries a version:
        // changing the chime means creating a new channel, not editing this one.
        const val CHANNEL_CRITICAL = "alerts_critical_v1"
        const val CHANNEL_WARNING = "alerts_warning_v1"
        const val CHANNEL_SERVICE = "trip_service_v1"

        const val SERVICE_NOTIFICATION_ID = 1001
        private const val ALERT_ID_BASE = 2000
    }

    private val manager = NotificationManagerCompat.from(context)

    fun ensureChannels() {
        val system = context.getSystemService(NotificationManager::class.java) ?: return

        val alarmAttributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ALARM)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()
        val notificationAttributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_NOTIFICATION_EVENT)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()

        system.createNotificationChannel(
            NotificationChannel(
                CHANNEL_CRITICAL,
                context.getString(R.string.channel_alerts_critical_name),
                NotificationManager.IMPORTANCE_HIGH,
            ).apply {
                description = context.getString(R.string.channel_alerts_critical_desc)
                // USAGE_ALARM so it stays audible over music at road speed.
                setSound(rawUri(R.raw.alert_critical), alarmAttributes)
                enableVibration(true)
                vibrationPattern = longArrayOf(0, 180, 90, 180, 90, 320)
                setBypassDnd(true)
            },
        )

        system.createNotificationChannel(
            NotificationChannel(
                CHANNEL_WARNING,
                context.getString(R.string.channel_alerts_warning_name),
                NotificationManager.IMPORTANCE_DEFAULT,
            ).apply {
                description = context.getString(R.string.channel_alerts_warning_desc)
                setSound(rawUri(R.raw.alert_warning), notificationAttributes)
                enableVibration(true)
                vibrationPattern = longArrayOf(0, 150, 120, 150)
            },
        )

        system.createNotificationChannel(
            NotificationChannel(
                CHANNEL_SERVICE,
                context.getString(R.string.channel_service_name),
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = context.getString(R.string.channel_service_desc)
                setShowBadge(false)
            },
        )
    }

    private fun rawUri(resId: Int): Uri =
        Uri.parse("android.resource://${context.packageName}/$resId")

    /** Posts (or refreshes) the notification for one alert. */
    fun post(alert: ActiveAlert) {
        if (!manager.areNotificationsEnabled()) return
        val critical = alert.severity == AlertSeverity.CRITICAL

        val notification = NotificationCompat.Builder(
            context,
            if (critical) CHANNEL_CRITICAL else CHANNEL_WARNING,
        )
            .setSmallIcon(R.drawable.ic_alert)
            .setContentTitle(if (critical) "Critical: ${alert.label}" else alert.label)
            .setContentText(alert.message)
            .setStyle(NotificationCompat.BigTextStyle().bigText(alert.message))
            .setPriority(if (critical) NotificationCompat.PRIORITY_HIGH else NotificationCompat.PRIORITY_DEFAULT)
            .setCategory(NotificationCompat.CATEGORY_ERROR)
            .setContentIntent(openAppIntent())
            .setAutoCancel(true)
            .setOnlyAlertOnce(false)
            .build()

        runCatching { manager.notify(notificationId(alert.metricKey), notification) }
    }

    fun clear(metricKey: String) = manager.cancel(notificationId(metricKey))

    fun clearAll() {
        manager.cancel(SERVICE_NOTIFICATION_ID)
    }

    private fun notificationId(metricKey: String): Int = ALERT_ID_BASE + metricKey.hashCode().and(0xFFFF)

    /** The ongoing notification that keeps trip logging alive in the background. */
    fun buildServiceNotification(title: String, detail: String): Notification =
        NotificationCompat.Builder(context, CHANNEL_SERVICE)
            .setSmallIcon(R.drawable.ic_gauge)
            .setContentTitle(title)
            .setContentText(detail)
            .setContentIntent(openAppIntent())
            .setOngoing(true)
            .setSilent(true)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()

    private fun openAppIntent(): PendingIntent = PendingIntent.getActivity(
        context,
        0,
        Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        },
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )
}
