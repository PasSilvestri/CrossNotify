package com.crossnotify.app.util

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.crossnotify.app.R
import com.crossnotify.app.crypto.NotificationPlaintext
import com.crossnotify.app.data.db.SubscriptionEntity
import com.crossnotify.app.ui.DecryptActivity
import com.crossnotify.app.ui.MainActivity
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class NotificationHelper @Inject constructor(
    @ApplicationContext private val context: Context
) {
    companion object {
        const val CHANNEL_BATTERY_SAVER = "crossnotify_relay"
        const val CHANNEL_ALERTS = "crossnotify_alerts"
        const val ACTION_OPEN_NOTIFICATION = "com.crossnotify.app.OPEN_NOTIFICATION"

        fun subscriptionNotificationChannelId(channelId: String): String = "sub_$channelId"
    }

    private val notificationManager: NotificationManager
        get() = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    /**
     * Creates the mandatory `crossnotify_relay` channel (used by FCM battery_saver pushes)
     * and the `crossnotify_alerts` channel on app start.
     */
    fun ensureBaseChannelsCreated() {
        val relayChannel = NotificationChannel(
            CHANNEL_BATTERY_SAVER,
            "Battery Saver Relayed Notifications",
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "Notifications delivered via FCM battery saver mode (tap to decrypt)"
        }

        val alertsChannel = NotificationChannel(
            CHANNEL_ALERTS,
            "Channel & Security Alerts",
            NotificationManager.IMPORTANCE_DEFAULT
        ).apply {
            description = "Alerts when a relay channel or subscription is revoked"
        }

        notificationManager.createNotificationChannels(listOf(relayChannel, alertsChannel))
    }

    /**
     * Ensures a dedicated Android NotificationChannel exists for [subscription],
     * named after the channel so users can customize importance and sound per subscription.
     */
    fun ensureSubscriptionChannel(subscription: SubscriptionEntity): String {
        val sysChannelId = subscriptionNotificationChannelId(subscription.channelId)
        val displayTitle = if (subscription.relayDevice.isNotBlank()) {
            "${subscription.name} (${subscription.relayDevice})"
        } else {
            subscription.name
        }
        val channel = NotificationChannel(
            sysChannelId,
            displayTitle,
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "End-to-end encrypted notifications from ${subscription.name}"
        }
        notificationManager.createNotificationChannel(channel)
        return sysChannelId
    }

    fun deleteSubscriptionChannel(channelId: String) {
        val sysChannelId = subscriptionNotificationChannelId(channelId)
        runCatching { notificationManager.deleteNotificationChannel(sysChannelId) }
    }

    /**
     * Posts a rich local notification for an `immediate_preview` decrypted message.
     */
    fun postDecryptedNotification(
        subscription: SubscriptionEntity,
        plaintext: NotificationPlaintext
    ) {
        if (!canPostNotifications()) return

        val sysChannelId = ensureSubscriptionChannel(subscription)

        val openIntent = Intent(context, DecryptActivity::class.java).apply {
            action = ACTION_OPEN_NOTIFICATION
            putExtra("channelId", subscription.channelId)
            putExtra("notificationId", plaintext.id)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }

        val pendingIntent = PendingIntent.getActivity(
            context,
            plaintext.id.hashCode(),
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val headerPrefix = buildString {
            append(subscription.name)
            if (plaintext.app.isNotBlank()) {
                append(" • ")
                append(plaintext.app)
            }
        }

        val contentTitle = plaintext.title.ifBlank { headerPrefix }
        val contentText = plaintext.text.ifBlank { plaintext.bigText }
        val expandedText = plaintext.bigText.ifBlank { plaintext.text }

        val builder = NotificationCompat.Builder(context, sysChannelId)
            .setSmallIcon(R.drawable.ic_notification)
            .setSubText(headerPrefix)
            .setContentTitle(contentTitle)
            .setContentText(contentText)
            .setStyle(NotificationCompat.BigTextStyle().bigText(expandedText))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setAutoCancel(true)
            .setWhen(plaintext.postedAt)
            .setShowWhen(true)
            .setContentIntent(pendingIntent)

        NotificationManagerCompat.from(context).notify(
            plaintext.id,
            plaintext.id.hashCode(),
            builder.build()
        )
    }

    /**
     * Posts an informational/warning notification when a relay channel or subscription is revoked.
     */
    fun postSystemAlert(title: String, body: String, tag: String) {
        if (!canPostNotifications()) return
        ensureBaseChannelsCreated()

        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            context,
            tag.hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(context, CHANNEL_ALERTS)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .build()

        NotificationManagerCompat.from(context).notify(tag, tag.hashCode(), notification)
    }

    private fun canPostNotifications(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            NotificationManagerCompat.from(context).areNotificationsEnabled()
        }
    }
}
