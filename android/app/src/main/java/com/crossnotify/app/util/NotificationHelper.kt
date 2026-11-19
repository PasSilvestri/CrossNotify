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
import androidx.core.app.RemoteInput
import androidx.core.content.ContextCompat
import com.crossnotify.app.R
import com.crossnotify.app.crypto.NotificationPlaintext
import com.crossnotify.app.data.db.SubscriptionEntity
import com.crossnotify.app.service.NotificationActionReceiver
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

        val appIconBitmap = plaintext.icon?.let { AppIconHelper.decodeBase64ToBitmap(it) }
        val pictureBitmap = plaintext.image?.let { NotificationMediaHelper.decodeBase64ToBitmap(it) }

        val style: NotificationCompat.Style = if (pictureBitmap != null) {
            NotificationCompat.BigPictureStyle()
                .bigPicture(pictureBitmap)
                .setSummaryText(expandedText)
        } else {
            NotificationCompat.BigTextStyle().bigText(expandedText)
        }

        val builder = NotificationCompat.Builder(context, sysChannelId)
            .setSmallIcon(R.drawable.ic_notification)
            .setSubText(headerPrefix)
            .setContentTitle(contentTitle)
            .setContentText(contentText)
            .setStyle(style)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setAutoCancel(true)
            .setWhen(plaintext.postedAt)
            .setShowWhen(true)
            .setContentIntent(pendingIntent)

        if (appIconBitmap != null) {
            builder.setLargeIcon(appIconBitmap)
        }

        // Attach interactive notification actions & inline RemoteInput replies if permitted
        if (subscription.allowActions && plaintext.actions.isNotEmpty()) {
            plaintext.actions.take(3).forEach { actionMeta ->
                val remoteInputKey = if (actionMeta.isReply) {
                    "cn_reply_${plaintext.id}_${actionMeta.idx}"
                } else {
                    null
                }
                val actionIntent = Intent(context, NotificationActionReceiver::class.java).apply {
                    action = NotificationActionReceiver.ACTION_EXECUTE_NOTIFICATION_ACTION
                    putExtra(NotificationActionReceiver.EXTRA_CHANNEL_ID, subscription.channelId)
                    putExtra(NotificationActionReceiver.EXTRA_NOTIFICATION_ID, plaintext.id)
                    putExtra(NotificationActionReceiver.EXTRA_ACTION_INDEX, actionMeta.idx)
                    if (remoteInputKey != null) {
                        putExtra(NotificationActionReceiver.EXTRA_REMOTE_INPUT_KEY, remoteInputKey)
                    }
                }
                val requestCode = "${plaintext.id}:act:${actionMeta.idx}".hashCode()
                val piFlags = if (actionMeta.isReply) {
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
                } else {
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                }
                val actionPendingIntent = PendingIntent.getBroadcast(
                    context,
                    requestCode,
                    actionIntent,
                    piFlags
                )

                val actionBuilder = NotificationCompat.Action.Builder(
                    0,
                    actionMeta.title,
                    actionPendingIntent
                )

                if (actionMeta.isReply && remoteInputKey != null) {
                    val remoteInputBuilder = RemoteInput.Builder(remoteInputKey)
                        .setLabel(actionMeta.replyLabel ?: actionMeta.title)
                    if (actionMeta.choices.isNotEmpty()) {
                        remoteInputBuilder.setChoices(actionMeta.choices.toTypedArray())
                    }
                    actionBuilder.addRemoteInput(remoteInputBuilder.build())
                }

                builder.addAction(actionBuilder.build())
            }
        }

        NotificationManagerCompat.from(context).notify(
            plaintext.id,
            plaintext.id.hashCode(),
            builder.build()
        )
    }

    /**
     * Dismisses a relayed notification from the subscriber's status bar across both
     * `immediate_preview` (id = hashCode) and `battery_saver` FCM-posted notifications (id = 0).
     */
    fun cancelRelayedNotification(notificationId: String) {
        val nm = NotificationManagerCompat.from(context)
        runCatching { nm.cancel(notificationId, notificationId.hashCode()) }
        runCatching { nm.cancel(notificationId, 0) }
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
