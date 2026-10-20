package com.crossnotify.app.service

import android.util.Log
import com.crossnotify.app.data.repository.CrossNotifyRepository
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Handles incoming push notifications across all dynamically initialized Firebase projects:
 * - Validates payload headers and routes messages by `channelId`.
 * - For incoming notification payloads:
 *   - In immediate preview mode (`iv` and `ct` included in push data): decrypts immediately,
 *     stores in the inbox, displays a local notification on the subscription channel,
 *     and forwards to chained relay channels if configured.
 *   - In fetch mode (`fetch == "1"` or large payload): fetches ciphertext from the server on demand.
 * - For channel revocation events: cleans up keys, marks the subscription revoked, and alerts the user.
 */
@AndroidEntryPoint
class CrossNotifyMessagingService : FirebaseMessagingService() {

    @Inject
    lateinit var repository: CrossNotifyRepository

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    companion object {
        private const val TAG = "CrossNotifyMsgService"
    }

    override fun onMessageReceived(message: RemoteMessage) {
        val data = message.data
        if (data["cn"] != "1") return

        val channelId = data["channelId"]?.takeIf { it.isNotBlank() } ?: return
        val type = data["type"].orEmpty()

        serviceScope.launch {
            when (type) {
                "channel_revoked" -> {
                    Log.i(TAG, "Received channel_revoked push for channel $channelId")
                    repository.handleSubscriptionRevoked(channelId)
                }
                "notification" -> {
                    val notificationId = data["notificationId"]?.takeIf { it.isNotBlank() } ?: return@launch
                    val iv = data["iv"].orEmpty()
                    val ct = data["ct"].orEmpty()
                    val fetchFlag = data["fetch"] == "1"
                    val ts = data["ts"]?.toLongOrNull() ?: System.currentTimeMillis()
                    val appName = data["app"]

                    if (iv.isNotBlank() && ct.isNotBlank() && !fetchFlag) {
                        repository.processIncomingEncryptedNotification(
                            channelId = channelId,
                            notificationId = notificationId,
                            iv = iv,
                            ciphertext = ct,
                            appName = appName,
                            relayTimestamp = ts,
                            receivedAt = ts,
                            postLocalNotification = true
                        )
                    } else {
                        // Either oversize payload (`fetch == "1"`) or foreground battery_saver notification message
                        repository.fetchAndDecryptSingleNotification(
                            channelId = channelId,
                            notificationId = notificationId,
                            postLocalNotification = true,
                            markAsRead = false
                        )
                    }
                }
            }
        }
    }

    override fun onNewToken(token: String) {
        serviceScope.launch {
            repository.refreshFcmTokensForAllSubscriptions()
        }
    }

    override fun onDestroy() {
        serviceScope.cancel()
        super.onDestroy()
    }
}
