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
        val senderId = message.from?.trim()

        serviceScope.launch {
            if (!repository.verifyPushSenderForChannel(channelId, senderId)) {
                Log.w(TAG, "Dropping FCM message for channel $channelId: senderId mismatch ($senderId)")
                return@launch
            }

            when (type) {
                "channel_revoked" -> {
                    val incomingSubId = data["subscriberId"]?.takeIf { it.isNotBlank() }
                    Log.i(TAG, "Received channel_revoked push for channel $channelId (subscriberId=$incomingSubId)")
                    repository.handlePushChannelRevoked(channelId, incomingSubId)
                }
                "relay_name_updated", "device_name_updated" -> {
                    val newName = data["relayDeviceName"] ?: data["deviceName"]
                    if (!newName.isNullOrBlank()) {
                        Log.i(TAG, "Received relay_name_updated push for channel $channelId: $newName")
                        repository.updateRelayDeviceName(channelId, newName)
                    }
                }
                "notifications_handled" -> {
                    val itemsJson = data["items"].orEmpty()
                    if (itemsJson.isNotBlank()) {
                        Log.i(TAG, "Received notifications_handled push for channel $channelId")
                        repository.handleIncomingNotificationsHandled(channelId, itemsJson)
                    }
                }
                "execute_action" -> {
                    val notificationId = data["notificationId"]?.takeIf { it.isNotBlank() } ?: return@launch
                    val actionId = data["actionId"]?.takeIf { it.isNotBlank() } ?: return@launch
                    val iv = data["iv"]?.takeIf { it.isNotBlank() } ?: return@launch
                    val ct = data["ct"]?.takeIf { it.isNotBlank() } ?: return@launch
                    Log.i(TAG, "Received execute_action push for channel $channelId notification=$notificationId")
                    repository.handleIncomingExecuteAction(
                        channelId = channelId,
                        notificationId = notificationId,
                        actionId = actionId,
                        iv = iv,
                        ciphertext = ct
                    )
                }
                "subscriber_permissions_updated" -> {
                    val incomingSubId = data["subscriberId"]?.takeIf { it.isNotBlank() }
                    val allowActions = data["allowActions"] != "0"
                    Log.i(TAG, "Received subscriber_permissions_updated push for channel $channelId (allowActions=$allowActions)")
                    repository.handleIncomingPermissionsUpdated(channelId, incomingSubId, allowActions)
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
                            postLocalNotification = true,
                            updateSyncCursor = false
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
