package com.crossnotify.app.data.repository

import android.content.Context
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationManagerCompat
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.crossnotify.app.BuildConfig
import com.crossnotify.app.crypto.CryptoManager
import com.crossnotify.app.crypto.DaisyChainPolicy
import com.crossnotify.app.crypto.E2ECryptoEngine
import com.crossnotify.app.crypto.NotificationPlaintext
import com.crossnotify.app.crypto.OriginInfo
import com.crossnotify.app.data.api.ChannelInfoResponse
import com.crossnotify.app.data.api.CrossNotifyApiClient
import com.crossnotify.app.data.api.CrossNotifyApiException
import com.crossnotify.app.data.api.PublishNotificationRequest
import com.crossnotify.app.data.api.RelayInitRequest
import com.crossnotify.app.data.api.RemoteNotificationDto
import com.crossnotify.app.data.api.SubscriberInitRequest
import com.crossnotify.app.data.api.SubscriberUpdateSelfRequest
import com.crossnotify.app.data.db.InboxDao
import com.crossnotify.app.data.db.InboxEntity
import com.crossnotify.app.data.db.OutboxDao
import com.crossnotify.app.data.db.OutboxEntity
import com.crossnotify.app.data.db.RelayAppFilterEntity
import com.crossnotify.app.data.db.RelayChannelDao
import com.crossnotify.app.data.db.RelayChannelEntity
import com.crossnotify.app.data.db.SeenOriginDao
import com.crossnotify.app.data.db.SeenOriginEntity
import com.crossnotify.app.data.db.ServerDao
import com.crossnotify.app.data.db.ServerEntity
import com.crossnotify.app.data.db.SubscriptionDao
import com.crossnotify.app.data.db.SubscriptionEntity
import com.crossnotify.app.data.prefs.AppPreferences
import com.crossnotify.app.firebase.FirebaseProjectManager
import com.crossnotify.app.qr.CnRelayQrPayload
import com.crossnotify.app.qr.CnSubQrPayload
import com.crossnotify.app.qr.QrE2EInfo
import com.crossnotify.app.qr.QrFirebaseInfo
import com.crossnotify.app.qr.QrPayloadParser
import com.crossnotify.app.qr.QrServerInfo
import com.crossnotify.app.qr.QrSubChannelInfo
import com.crossnotify.app.util.NotificationHelper
import com.crossnotify.app.worker.CatchUpSyncWorker
import com.crossnotify.app.worker.OutboxWorker
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import java.util.UUID
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

data class DecryptedInboxItem(
    val entity: InboxEntity,
    val subscription: SubscriptionEntity?,
    val plaintext: NotificationPlaintext?
)

@Singleton
class CrossNotifyRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val serverDao: ServerDao,
    private val relayChannelDao: RelayChannelDao,
    private val subscriptionDao: SubscriptionDao,
    private val inboxDao: InboxDao,
    private val outboxDao: OutboxDao,
    private val seenOriginDao: SeenOriginDao,
    private val cryptoManager: CryptoManager,
    private val e2eCryptoEngine: E2ECryptoEngine,
    private val apiClient: CrossNotifyApiClient,
    private val firebaseProjectManager: FirebaseProjectManager,
    private val notificationHelper: NotificationHelper,
    private val appPreferences: AppPreferences
) {
    companion object {
        private const val TAG = "CrossNotifyRepository"
        private const val SEVEN_DAYS_MS = 7L * 24L * 60L * 60L * 1000L
    }

    fun observeServers(): Flow<List<ServerEntity>> = serverDao.observeAll()
    fun observeRelayChannels(): Flow<List<RelayChannelEntity>> = relayChannelDao.observeAll()
    fun observeRelayChannel(channelId: String): Flow<RelayChannelEntity?> = relayChannelDao.observeById(channelId)
    fun observeFilterPackages(channelId: String): Flow<List<String>> = relayChannelDao.observeFilterPackages(channelId)
    fun observeSubscriptions(): Flow<List<SubscriptionEntity>> = subscriptionDao.observeAll()
    fun observeSubscription(channelId: String): Flow<SubscriptionEntity?> = subscriptionDao.observeById(channelId)
    suspend fun getSubscription(channelId: String): SubscriptionEntity? = subscriptionDao.getById(channelId)
    fun observeInbox(): Flow<List<InboxEntity>> = inboxDao.observeAll()
    fun observeOutbox(): Flow<List<OutboxEntity>> = outboxDao.observeAll()

    /**
     * Checks whether an existing server entry has conflicting Firebase parameters,
     * indicating that the server reconfigured or migrated its Firebase project.
     */
    suspend fun hasFirebaseParameterConflict(serverUrl: String, fb: QrFirebaseInfo): Boolean {
        val normalizedUrl = QrPayloadParser.normalizeServerUrl(serverUrl)
        val existing = serverDao.getByUrl(normalizedUrl) ?: return false
        return existing.fbProjectId != fb.projectId ||
            existing.fbAppId != fb.appId ||
            existing.fbApiKey != fb.apiKey ||
            existing.fbSenderId != fb.senderId
    }

    private suspend fun upsertServerFromQr(
        serverInfo: QrServerInfo,
        fb: QrFirebaseInfo,
        insecureHttpAccepted: Boolean
    ): ServerEntity {
        val normalizedUrl = QrPayloadParser.normalizeServerUrl(serverInfo.url)
        val existing = serverDao.getByUrl(normalizedUrl)
        val fbAppName = FirebaseProjectManager.appNameForProject(fb.projectId)

        return if (existing != null) {
            val updated = existing.copy(
                name = serverInfo.name.ifBlank { existing.name },
                fbProjectId = fb.projectId,
                fbAppId = fb.appId,
                fbApiKey = fb.apiKey,
                fbSenderId = fb.senderId,
                fbAppName = fbAppName,
                insecureHttpAccepted = existing.insecureHttpAccepted || insecureHttpAccepted
            )
            serverDao.update(updated)
            updated
        } else {
            val entity = ServerEntity(
                url = normalizedUrl,
                name = serverInfo.name.ifBlank { "CrossNotify Server" },
                fbProjectId = fb.projectId,
                fbAppId = fb.appId,
                fbApiKey = fb.apiKey,
                fbSenderId = fb.senderId,
                fbAppName = fbAppName,
                insecureHttpAccepted = insecureHttpAccepted
            )
            val id = serverDao.insert(entity)
            entity.copy(id = id)
        }
    }

    /**
     * Completes initial channel provisioning after scanning a relay QR code:
     * - Validates token expiration.
     * - Dynamically connects the associated Firebase project.
     * - Registers the device as the active relay with the server.
     * - Generates a fresh 256-bit AES encryption key on device.
     * - Encrypts secrets with the hardware Keystore before persisting to the local database.
     */
    suspend fun provisionRelayChannel(
        payload: CnRelayQrPayload,
        insecureHttpAccepted: Boolean
    ): RelayChannelEntity {
        val listenerPackages = NotificationManagerCompat.getEnabledListenerPackages(context)
        if (!listenerPackages.contains(context.packageName)) {
            throw IllegalStateException("Notification Access has not been granted on this device. Please grant it in system settings.")
        }

        val now = System.currentTimeMillis()
        if (payload.exp > 0 && now > payload.exp) {
            throw IllegalStateException("This relay provisioning QR code has expired. Generate a new QR on the dashboard.")
        }

        val normalizedUrl = QrPayloadParser.normalizeServerUrl(payload.server.url)
        val allowHttp = insecureHttpAccepted || appPreferences.allowInsecureHttpByDefault.value

        // 1. Initialize FirebaseApp for this project (with Multiple Firebase Fallback)
        firebaseProjectManager.ensureProjectInitialized(payload.firebase)

        // 2. Call POST /api/v1/relay/init
        val service = apiClient.serviceFor(normalizedUrl, allowHttp)
        val initResponse = apiClient.unwrapResponse(
            service.initRelay(
                authHeader = apiClient.bearerHeader(payload.apiKey),
                body = RelayInitRequest(
                    deviceName = appPreferences.deviceName.value,
                    deviceModel = "${Build.MANUFACTURER} ${Build.MODEL}".trim(),
                    osVersion = "Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})",
                    appVersion = BuildConfig.VERSION_NAME
                )
            )
        )

        // 3. Upsert ServerEntity
        val server = upsertServerFromQr(
            serverInfo = payload.server.copy(
                name = initResponse.serverName.ifBlank { payload.server.name }
            ),
            fb = payload.firebase,
            insecureHttpAccepted = allowHttp
        )

        // 4. Generate local 32-byte AES-256 E2E key (or preserve existing if re-initializing same channel)
        val existingChannel = relayChannelDao.getById(initResponse.channelId)
        val e2eKeyBytes = if (existingChannel != null && existingChannel.e2eKeyEnc.isNotBlank()) {
            runCatching { cryptoManager.unwrapBytes(existingChannel.e2eKeyEnc) }
                .getOrElse { e2eCryptoEngine.generateE2EKey() }
        } else {
            e2eCryptoEngine.generateE2EKey()
        }

        val apiKeyEnc = cryptoManager.wrapString(payload.apiKey)
        val e2eKeyEnc = cryptoManager.wrapBytes(e2eKeyBytes)

        val channelEntity = RelayChannelEntity(
            channelId = initResponse.channelId,
            serverId = server.id,
            name = initResponse.channelName.ifBlank { payload.channel.name },
            color = initResponse.color ?: payload.channel.color ?: "#0077b6",
            apiKeyEnc = apiKeyEnc,
            e2eKeyEnc = e2eKeyEnc,
            kid = "k1",
            filterMode = existingChannel?.filterMode ?: "deny_list",
            sendAppName = existingChannel?.sendAppName ?: true,
            active = true,
            createdAt = existingChannel?.createdAt ?: now
        )
        relayChannelDao.upsert(channelEntity)
        return channelEntity
    }

    /**
     * Requests a unique subscriber API key from the server and generates a pairing QR code payload
     * containing the channel encryption key and server connection details for in-person sharing.
     */
    suspend fun createSubscriberPairingQrPayload(channelId: String): Pair<CnSubQrPayload, Long> {
        val channel = relayChannelDao.getById(channelId)
            ?: throw IllegalStateException("Relay channel not found")
        val server = serverDao.getById(channel.serverId)
            ?: throw IllegalStateException("Server configuration not found")

        val relayApiKey = cryptoManager.unwrapString(channel.apiKeyEnc)
        val e2eKeyBytes = cryptoManager.unwrapBytes(channel.e2eKeyEnc)
        val e2eKeyBase64Url = E2ECryptoEngine.encodeBase64Url(e2eKeyBytes)

        val service = apiClient.serviceFor(server.url, server.insecureHttpAccepted)
        val mintResponse = apiClient.unwrapResponse(
            service.mintSubscriberKey(
                authHeader = apiClient.bearerHeader(relayApiKey),
                channelId = channel.channelId
            )
        )

        val qrPayload = CnSubQrPayload(
            t = "cn-sub",
            v = 1,
            server = QrServerInfo(url = server.url, name = server.name),
            channel = QrSubChannelInfo(
                id = channel.channelId,
                name = channel.name,
                color = channel.color,
                relayDevice = appPreferences.deviceName.value
            ),
            apiKey = mintResponse.apiKey,
            firebase = QrFirebaseInfo(
                projectId = server.fbProjectId,
                appId = server.fbAppId,
                apiKey = server.fbApiKey,
                senderId = server.fbSenderId
            ),
            e2e = QrE2EInfo(
                alg = "A256GCM",
                kid = channel.kid,
                key = e2eKeyBase64Url
            )
        )

        return qrPayload to mintResponse.expiresAt
    }

    /**
     * Fetches live channel info (including subscriber count) from `GET /api/v1/channels/:id`.
     */
    suspend fun fetchRelayChannelInfo(channelId: String): Result<ChannelInfoResponse> = runCatching {
        val channel = relayChannelDao.getById(channelId)
            ?: throw IllegalStateException("Relay channel not found")
        val server = serverDao.getById(channel.serverId)
            ?: throw IllegalStateException("Server not found")
        val relayApiKey = cryptoManager.unwrapString(channel.apiKeyEnc)
        val service = apiClient.serviceFor(server.url, server.insecureHttpAccepted)
        val info = apiClient.unwrapResponse(
            service.getChannelInfo(apiClient.bearerHeader(relayApiKey), channelId)
        )
        if (info.status == "revoked") {
            relayChannelDao.setActive(channelId, false)
        }
        info
    }

    /**
     * Removes a relay channel, calling `DELETE /api/v1/channels/:id` and cleaning up local storage.
     */
    suspend fun removeRelayChannel(channelId: String) {
        val channel = relayChannelDao.getById(channelId) ?: return
        val server = serverDao.getById(channel.serverId)
        if (server != null && channel.apiKeyEnc.isNotBlank()) {
            runCatching {
                val relayApiKey = cryptoManager.unwrapString(channel.apiKeyEnc)
                val service = apiClient.serviceFor(server.url, server.insecureHttpAccepted)
                service.deleteChannel(apiClient.bearerHeader(relayApiKey), channelId)
            }.onFailure { err ->
                Log.w(TAG, "Best-effort remote channel close failed: ${err.message}")
            }
        }
        outboxDao.deleteByChannel(channelId)
        relayChannelDao.deleteById(channelId)
        serverDao.deleteOrphanedServers()
        firebaseProjectManager.reconcileAfterServerRemoval()
    }

    suspend fun setRelayFilterMode(channelId: String, filterMode: String) {
        relayChannelDao.setFilterMode(channelId, filterMode)
    }

    suspend fun setRelaySendAppName(channelId: String, sendAppName: Boolean) {
        relayChannelDao.setSendAppName(channelId, sendAppName)
    }

    suspend fun toggleRelayAppFilter(channelId: String, packageName: String, include: Boolean) {
        if (include) {
            relayChannelDao.insertFilter(RelayAppFilterEntity(channelId = channelId, packageName = packageName))
        } else {
            relayChannelDao.deleteFilter(channelId = channelId, packageName = packageName)
        }
    }

    /**
     * Encrypts and enqueues an intercepted system notification across all active channels
     * whose app filter rules accept the sender package.
     */
    suspend fun captureAndQueueNotification(
        packageName: String,
        appLabel: String,
        appIconBase64: String?,
        title: String,
        text: String,
        subText: String,
        bigText: String,
        category: String,
        postedAt: Long,
        shouldSendForChannel: (channelId: String) -> Boolean
    ) {
        val activeChannels = relayChannelDao.getActive()
        if (activeChannels.isEmpty()) return

        for (channel in activeChannels) {
            val filterPackages = relayChannelDao.getFilterPackages(channel.channelId).toSet()
            val accepted = when (channel.filterMode) {
                "allow_list" -> packageName in filterPackages
                else -> packageName !in filterPackages // deny_list default
            }
            if (!accepted) continue
            if (!shouldSendForChannel(channel.channelId)) continue

            val notificationId = UUID.randomUUID().toString()
            val plaintext = NotificationPlaintext(
                v = 1,
                id = notificationId,
                pkg = packageName,
                app = if (channel.sendAppName) appLabel else "",
                icon = if (channel.sendAppName) appIconBase64 else null,
                title = title,
                text = text,
                subText = subText,
                bigText = bigText,
                category = category,
                postedAt = postedAt,
                origin = OriginInfo(
                    id = notificationId,
                    hops = 0,
                    path = listOf(channel.channelId)
                )
            )

            enqueueEncryptedToOutbox(channel, plaintext)
        }
    }

    private suspend fun enqueueEncryptedToOutbox(
        channel: RelayChannelEntity,
        plaintext: NotificationPlaintext
    ) {
        runCatching {
            val e2eKey = cryptoManager.unwrapBytes(channel.e2eKeyEnc)
            val blob = e2eCryptoEngine.encrypt(
                e2eKey = e2eKey,
                channelId = channel.channelId,
                payload = plaintext
            )
            val outboxItem = OutboxEntity(
                notificationId = plaintext.id,
                channelId = channel.channelId,
                iv = blob.iv,
                ciphertext = blob.ciphertext,
                appName = if (channel.sendAppName) plaintext.app.take(64) else null,
                createdAt = System.currentTimeMillis(),
                attempts = 0,
                lastError = null
            )
            outboxDao.insert(outboxItem)
            seenOriginDao.markSeen(
                SeenOriginEntity(
                    originId = plaintext.origin.id,
                    seenAt = System.currentTimeMillis()
                )
            )
            appPreferences.incrementTodayRelayCount(channel.channelId)
            scheduleOutboxWorker(channel.channelId)
        }.onFailure { err ->
            Log.e(TAG, "Failed to encrypt and queue notification for channel ${channel.channelId}", err)
        }
    }

    fun scheduleOutboxWorker(channelId: String) {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        val request = OneTimeWorkRequestBuilder<OutboxWorker>()
            .setConstraints(constraints)
            .setInputData(workDataOf(OutboxWorker.KEY_CHANNEL_ID to channelId))
            .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 10, TimeUnit.SECONDS)
            .build()

        WorkManager.getInstance(context).enqueueUniqueWork(
            "outbox_$channelId",
            ExistingWorkPolicy.APPEND_OR_REPLACE,
            request
        )
    }

    /**
     * Processes pending outbox items for [channelId] (called by [OutboxWorker]).
     * Returns true if all items succeeded or the channel is inactive/revoked; false if retry is needed.
     */
    suspend fun processOutboxForChannel(channelId: String): Boolean {
        val channel = relayChannelDao.getById(channelId) ?: return true
        if (!channel.active) return true
        val server = serverDao.getById(channel.serverId) ?: return true

        val items = outboxDao.getByChannel(channelId)
        if (items.isEmpty()) return true

        val relayApiKey = runCatching { cryptoManager.unwrapString(channel.apiKeyEnc) }.getOrElse {
            Log.e(TAG, "Cannot unwrap relay API key for channel $channelId", it)
            return true
        }

        val service = apiClient.serviceFor(server.url, server.insecureHttpAccepted)
        val authHeader = apiClient.bearerHeader(relayApiKey)

        for (item in items) {
            try {
                val response = service.publishNotification(
                    authHeader = authHeader,
                    channelId = channelId,
                    body = PublishNotificationRequest(
                        notificationId = item.notificationId,
                        iv = item.iv,
                        ciphertext = item.ciphertext,
                        appName = item.appName,
                        timestamp = item.createdAt
                    )
                )
                if (response.code() == 200 || response.code() == 201) {
                    outboxDao.deleteById(item.notificationId)
                } else {
                    apiClient.unwrapResponse(response)
                }
            } catch (apiErr: CrossNotifyApiException) {
                if (apiErr.isRevokedOrUnauthorized) {
                    relayChannelDao.setActive(channelId, false)
                    notificationHelper.postSystemAlert(
                        title = "Relay channel revoked: ${channel.name}",
                        body = "The server rejected this channel's key (${apiErr.errorCode}). Relay has been paused.",
                        tag = "revoked_relay_$channelId"
                    )
                    return true
                }
                outboxDao.recordFailure(item.notificationId, apiErr.message)
                return false
            } catch (err: Exception) {
                outboxDao.recordFailure(item.notificationId, err.message ?: "Network error")
                return false
            }
        }
        return true
    }

    /**
     * Pairs this device with a relay channel using the scanned subscriber QR payload:
     * - Validates and unpacks the 32-byte channel encryption key.
     * - Initializes the runtime Firebase project and acquires a push registration token.
     * - Registers the subscriber with the server using its dedicated subscriber API key.
     * - Persists the subscription and Keystore-wrapped credentials.
     * - Configures a distinct Android system notification channel for customizable alerts.
     */
    suspend fun pairSubscription(
        payload: CnSubQrPayload,
        deliveryMode: String,
        insecureHttpAccepted: Boolean
    ): SubscriptionEntity {
        val existing = subscriptionDao.getById(payload.channel.id)
        if (existing != null && existing.status == "active") {
            throw IllegalStateException("Device is already subscribed to '${existing.name}'.")
        }

        val e2eKeyBytes = E2ECryptoEngine.decodeBase64Url(payload.e2e.key)
        require(e2eKeyBytes.size == 32) {
            "Invalid E2E key length in QR code (expected 32 bytes, got ${e2eKeyBytes.size})"
        }

        val normalizedUrl = QrPayloadParser.normalizeServerUrl(payload.server.url)
        val allowHttp = insecureHttpAccepted || appPreferences.allowInsecureHttpByDefault.value

        // 1. Initialize FirebaseApp and fetch FCM token
        val fcmToken = try {
            firebaseProjectManager.getFcmToken(payload.firebase)
        } catch (e: Exception) {
            Log.w(TAG, "FCM token retrieval failed (${e.message}), using fallback registration token for local/offline setup")
            "fcm_pending_${UUID.randomUUID()}"
        }

        // 2. Call POST /api/v1/channels/:id/subscribers/init
        val service = apiClient.serviceFor(normalizedUrl, allowHttp)
        val initResponse = apiClient.unwrapResponse(
            service.initSubscriber(
                authHeader = apiClient.bearerHeader(payload.apiKey),
                channelId = payload.channel.id,
                body = SubscriberInitRequest(
                    fcmToken = fcmToken,
                    deviceName = appPreferences.deviceName.value,
                    deviceModel = "${Build.MANUFACTURER} ${Build.MODEL}".trim(),
                    deliveryMode = deliveryMode
                )
            )
        )

        // 3. Upsert ServerEntity
        val server = upsertServerFromQr(
            serverInfo = payload.server,
            fb = payload.firebase,
            insecureHttpAccepted = allowHttp
        )

        // 4. Wrap secrets with Android Keystore `cn_master`
        val apiKeyEnc = cryptoManager.wrapString(payload.apiKey)
        val e2eKeyEnc = cryptoManager.wrapBytes(e2eKeyBytes)

        val subscription = SubscriptionEntity(
            channelId = initResponse.channelId,
            serverId = server.id,
            subscriberId = initResponse.subscriberId,
            name = initResponse.channelName.ifBlank { payload.channel.name },
            color = payload.channel.color ?: "#0077b6",
            relayDevice = payload.channel.relayDevice ?: "",
            apiKeyEnc = apiKeyEnc,
            e2eKeyEnc = e2eKeyEnc,
            kid = payload.e2e.kid,
            deliveryMode = initResponse.deliveryMode,
            lastReceivedAt = 0L,
            forwardToRelayChannelId = null,
            lastRegisteredFcmToken = fcmToken,
            status = "active"
        )
        subscriptionDao.upsert(subscription)
        notificationHelper.ensureSubscriptionChannel(subscription)

        // Trigger initial catch-up sync
        scheduleCatchUpSync(subscription.channelId)
        return subscription
    }

    /**
     * Updates a subscription's delivery mode (`battery_saver` vs `immediate_preview`) on both the server and Room.
     */
    suspend fun updateSubscriptionDeliveryMode(channelId: String, deliveryMode: String) {
        val sub = subscriptionDao.getById(channelId)
            ?: throw IllegalStateException("Subscription not found")
        if (sub.status != "active") {
            throw IllegalStateException("Subscription is revoked")
        }
        val server = serverDao.getById(sub.serverId)
            ?: throw IllegalStateException("Server not found")

        val subApiKey = cryptoManager.unwrapString(sub.apiKeyEnc)
        val service = apiClient.serviceFor(server.url, server.insecureHttpAccepted)
        val response = apiClient.unwrapResponse(
            service.updateSubscriberSelf(
                authHeader = apiClient.bearerHeader(subApiKey),
                channelId = channelId,
                body = SubscriberUpdateSelfRequest(
                    deviceName = appPreferences.deviceName.value,
                    deliveryMode = deliveryMode
                )
            )
        )
        subscriptionDao.updateDeliveryMode(channelId, response.deliveryMode)
    }

    /**
     * Enables automatic forwarding from an incoming subscription to a local relay channel.
     * Note: Automatically switches the subscription to immediate preview mode so incoming
     * payloads can be decrypted and re-encrypted on arrival.
     */
    suspend fun setSubscriptionForwarding(channelId: String, targetRelayChannelId: String?) {
        val sub = subscriptionDao.getById(channelId)
            ?: throw IllegalStateException("Subscription not found")
        if (targetRelayChannelId != null && sub.deliveryMode != "immediate_preview") {
            updateSubscriptionDeliveryMode(channelId, "immediate_preview")
        }
        subscriptionDao.updateForwardToRelayChannel(channelId, targetRelayChannelId)
    }

    /**
     * Unsubscribes from a channel (`DELETE /api/v1/channels/:id/subscribers/self`), wipes keys and inbox.
     */
    suspend fun unsubscribe(channelId: String) {
        val sub = subscriptionDao.getById(channelId) ?: return
        val server = serverDao.getById(sub.serverId)
        if (server != null && sub.status == "active" && sub.apiKeyEnc.isNotBlank()) {
            runCatching {
                val subApiKey = cryptoManager.unwrapString(sub.apiKeyEnc)
                val service = apiClient.serviceFor(server.url, server.insecureHttpAccepted)
                service.unsubscribeSelf(apiClient.bearerHeader(subApiKey), channelId)
            }.onFailure { err ->
                Log.w(TAG, "Best-effort remote unsubscribe failed: ${err.message}")
            }
        }
        notificationHelper.deleteSubscriptionChannel(channelId)
        inboxDao.deleteByChannel(channelId)
        subscriptionDao.deleteById(channelId)
        serverDao.deleteOrphanedServers()
        firebaseProjectManager.reconcileAfterServerRemoval()
    }

    /**
     * Handles revocation events when the server or relay closes a channel or revokes this subscriber.
     * Marks the local record as revoked, purges stored credentials, and alerts the user.
     * If [targetSubscriberId] is provided, only revokes if this device's subscriber ID matches.
     */
    suspend fun handleSubscriptionRevoked(channelId: String, targetSubscriberId: String? = null) {
        val sub = subscriptionDao.getById(channelId) ?: return
        if (sub.status == "revoked") return
        if (targetSubscriberId != null && sub.subscriberId != targetSubscriberId) {
            Log.d(TAG, "Ignoring revocation for channel $channelId: target subscriberId $targetSubscriberId does not match device subscriberId ${sub.subscriberId}")
            return
        }
        subscriptionDao.markRevokedAndWipeKeys(channelId)
        notificationHelper.postSystemAlert(
            title = "Subscription revoked: ${sub.name}",
            body = "Access to channel '${sub.name}' was revoked by the server or relay device.",
            tag = "revoked_sub_$channelId"
        )
    }

    /**
     * Processes an incoming encrypted notification from push delivery or catch-up sync:
     * - Verifies authenticated data (AAD) and decrypts using the subscription's key.
     * - Deduplicates against known message IDs and original message origins.
     * - Persists the raw ciphertext in the local inbox.
     * - Displays a rich system notification when immediate display is requested.
     * - Executes daisy-chain forwarding if configured for this subscription.
     */
    suspend fun processIncomingEncryptedNotification(
        channelId: String,
        notificationId: String,
        iv: String,
        ciphertext: String,
        appName: String?,
        relayTimestamp: Long,
        receivedAt: Long,
        postLocalNotification: Boolean
    ): NotificationPlaintext? {
        val sub = subscriptionDao.getById(channelId) ?: return null
        if (sub.status != "active" || sub.e2eKeyEnc.isBlank()) return null

        val e2eKey = runCatching { cryptoManager.unwrapBytes(sub.e2eKeyEnc) }.getOrNull() ?: return null
        val plaintext = runCatching {
            e2eCryptoEngine.decrypt(
                e2eKey = e2eKey,
                channelId = channelId,
                notificationId = notificationId,
                ivBase64Url = iv,
                ciphertextBase64Url = ciphertext
            )
        }.onFailure { err ->
            Log.e(TAG, "Failed to decrypt notification $notificationId on channel $channelId", err)
        }.getOrNull() ?: return null

        val originId = plaintext.origin.id.ifBlank { notificationId }
        val alreadyInInbox = inboxDao.getById(notificationId) != null
        val alreadySeenOrigin = seenOriginDao.hasSeen(originId)

        val inboxEntity = InboxEntity(
            notificationId = notificationId,
            channelId = channelId,
            originId = originId,
            iv = iv,
            ciphertext = ciphertext,
            appName = appName?.ifBlank { plaintext.app } ?: plaintext.app,
            relayTimestamp = if (relayTimestamp > 0) relayTimestamp else plaintext.postedAt,
            receivedAt = receivedAt,
            readAt = null
        )

        val insertedRowId = inboxDao.insertIgnore(inboxEntity)
        subscriptionDao.updateLastReceivedAt(channelId, receivedAt)
        seenOriginDao.markSeen(SeenOriginEntity(originId = originId, seenAt = System.currentTimeMillis()))

        val isNewMessage = insertedRowId != -1L && !alreadyInInbox && !alreadySeenOrigin
        if (isNewMessage) {
            if (postLocalNotification) {
                notificationHelper.postDecryptedNotification(sub, plaintext)
            }
            // Forward to another relay channel if daisy-chaining is configured
            val targetRelayId = sub.forwardToRelayChannelId
            if (!targetRelayId.isNullOrBlank() && sub.deliveryMode == "immediate_preview") {
                forwardToRelayChannel(plaintext, targetRelayId)
            }
        }

        return plaintext
    }

    /**
     * Forwards an incoming notification to a local relay channel by updating the origin hop metadata,
     * re-encrypting with the target channel's key, and enqueuing in the outbox.
     */
    private suspend fun forwardToRelayChannel(
        incoming: NotificationPlaintext,
        targetRelayChannelId: String
    ) {
        val targetChannel = relayChannelDao.getById(targetRelayChannelId) ?: return
        if (!targetChannel.active) return

        val forwarded = DaisyChainPolicy.prepareForwardedPayload(
            incoming = incoming,
            targetRelayChannelId = targetRelayChannelId
        ) ?: run {
            Log.i(TAG, "Dropped daisy-chain forwarding to $targetRelayChannelId (hop limit or loop detected)")
            return
        }

        enqueueEncryptedToOutbox(targetChannel, forwarded)
    }

    /**
     * Fetches a single notification by ID from the server (`GET /api/v1/channels/:id/notifications/:notificationId`),
     * stores it in `inbox`, and returns its decrypted plaintext.
     * Used by `DecryptActivity` (battery_saver tap) and `CrossNotifyMessagingService` (`fetch == "1"`).
     */
    suspend fun fetchAndDecryptSingleNotification(
        channelId: String,
        notificationId: String,
        postLocalNotification: Boolean,
        markAsRead: Boolean
    ): Result<Pair<SubscriptionEntity, NotificationPlaintext>> = runCatching {
        val sub = subscriptionDao.getById(channelId)
            ?: throw IllegalStateException("Subscription not found for channel $channelId")

        // Check if already in local inbox
        val existingInbox = inboxDao.getById(notificationId)
        if (existingInbox != null && sub.e2eKeyEnc.isNotBlank()) {
            val e2eKey = cryptoManager.unwrapBytes(sub.e2eKeyEnc)
            val plaintext = e2eCryptoEngine.decrypt(
                e2eKey = e2eKey,
                channelId = channelId,
                notificationId = notificationId,
                ivBase64Url = existingInbox.iv,
                ciphertextBase64Url = existingInbox.ciphertext
            )
            if (markAsRead) {
                inboxDao.markRead(notificationId, System.currentTimeMillis())
            }
            return@runCatching sub to plaintext
        }

        if (sub.status != "active") {
            throw IllegalStateException("Subscription is revoked")
        }
        val server = serverDao.getById(sub.serverId)
            ?: throw IllegalStateException("Server not found")
        val subApiKey = cryptoManager.unwrapString(sub.apiKeyEnc)
        val service = apiClient.serviceFor(server.url, server.insecureHttpAccepted)

        val remoteDto: RemoteNotificationDto = try {
            apiClient.unwrapResponse(
                service.getNotification(
                    authHeader = apiClient.bearerHeader(subApiKey),
                    channelId = channelId,
                    notificationId = notificationId
                )
            )
        } catch (apiErr: CrossNotifyApiException) {
            if (apiErr.isRevokedOrUnauthorized) {
                handleSubscriptionRevoked(channelId)
            }
            throw apiErr
        }

        val plaintext = processIncomingEncryptedNotification(
            channelId = channelId,
            notificationId = remoteDto.notificationId,
            iv = remoteDto.iv,
            ciphertext = remoteDto.ciphertext,
            appName = remoteDto.appName,
            relayTimestamp = remoteDto.timestamp ?: remoteDto.receivedAt,
            receivedAt = remoteDto.receivedAt,
            postLocalNotification = postLocalNotification
        ) ?: throw IllegalStateException("Failed to decrypt notification payload")

        if (markAsRead) {
            inboxDao.markRead(notificationId, System.currentTimeMillis())
        }
        sub to plaintext
    }

    /**
     * Decrypts an [InboxEntity] in memory for UI presentation without storing plaintext on disk.
     */
    fun decryptInboxEntity(entity: InboxEntity, subscription: SubscriptionEntity?): NotificationPlaintext? {
        val sub = subscription ?: return null
        if (sub.e2eKeyEnc.isBlank()) return null
        return runCatching {
            val e2eKey = cryptoManager.unwrapBytes(sub.e2eKeyEnc)
            e2eCryptoEngine.decrypt(
                e2eKey = e2eKey,
                channelId = entity.channelId,
                notificationId = entity.notificationId,
                ivBase64Url = entity.iv,
                ciphertextBase64Url = entity.ciphertext
            )
        }.getOrNull()
    }

    suspend fun markInboxItemRead(notificationId: String) {
        inboxDao.markRead(notificationId, System.currentTimeMillis())
    }

    suspend fun markChannelRead(channelId: String) {
        inboxDao.markChannelRead(channelId, System.currentTimeMillis())
    }

    /**
     * Synchronizes missed notifications for all active subscriptions by paging through
     * recent server records since the last known timestamp.
     */
    suspend fun syncCatchUpAllSubscriptions(): Boolean {
        val activeSubs = subscriptionDao.getActive()
        var allSucceeded = true
        for (sub in activeSubs) {
            val ok = syncCatchUpForSubscription(sub.channelId)
            if (!ok) allSucceeded = false
        }
        // Prune deduplication records older than 7 days
        seenOriginDao.pruneOlderThan(System.currentTimeMillis() - SEVEN_DAYS_MS)
        return allSucceeded
    }

    suspend fun syncCatchUpForSubscription(channelId: String): Boolean {
        val sub = subscriptionDao.getById(channelId) ?: return true
        if (sub.status != "active" || sub.apiKeyEnc.isBlank()) return true
        val server = serverDao.getById(sub.serverId) ?: return true

        val subApiKey = runCatching { cryptoManager.unwrapString(sub.apiKeyEnc) }.getOrNull() ?: return true
        val service = apiClient.serviceFor(server.url, server.insecureHttpAccepted)
        val authHeader = apiClient.bearerHeader(subApiKey)

        var since = sub.lastReceivedAt
        var safetyPages = 0

        while (safetyPages < 20) {
            safetyPages++
            try {
                val response = apiClient.unwrapResponse(
                    service.catchUpNotifications(
                        authHeader = authHeader,
                        channelId = channelId,
                        since = since,
                        limit = 50
                    )
                )
                for (item in response.notifications) {
                    processIncomingEncryptedNotification(
                        channelId = channelId,
                        notificationId = item.notificationId,
                        iv = item.iv,
                        ciphertext = item.ciphertext,
                        appName = item.appName,
                        relayTimestamp = item.timestamp ?: item.receivedAt,
                        receivedAt = item.receivedAt,
                        postLocalNotification = false
                    )
                    if (item.receivedAt > since) {
                        since = item.receivedAt
                    }
                }
                if (!response.hasMore || response.notifications.isEmpty()) {
                    break
                }
            } catch (apiErr: CrossNotifyApiException) {
                if (apiErr.isRevokedOrUnauthorized) {
                    handleSubscriptionRevoked(channelId)
                    return true
                }
                Log.w(TAG, "Catch-up sync error for $channelId: ${apiErr.message}")
                return false
            } catch (err: Exception) {
                Log.w(TAG, "Catch-up sync network error for $channelId: ${err.message}")
                return false
            }
        }
        return true
    }

    /**
     * Verifies FCM registration tokens across all active subscriptions and notifies the
     * respective servers whenever a token has been rotated.
     */
    suspend fun refreshFcmTokensForAllSubscriptions() {
        val activeSubs = subscriptionDao.getActive()
        for (sub in activeSubs) {
            val server = serverDao.getById(sub.serverId) ?: continue
            runCatching {
                val currentToken = firebaseProjectManager.getFcmToken(server)
                if (currentToken.isNotBlank() && currentToken != sub.lastRegisteredFcmToken) {
                    val subApiKey = cryptoManager.unwrapString(sub.apiKeyEnc)
                    val service = apiClient.serviceFor(server.url, server.insecureHttpAccepted)
                    apiClient.unwrapResponse(
                        service.updateSubscriberSelf(
                            authHeader = apiClient.bearerHeader(subApiKey),
                            channelId = sub.channelId,
                            body = SubscriberUpdateSelfRequest(
                                fcmToken = currentToken,
                                deviceName = appPreferences.deviceName.value
                            )
                        )
                    )
                    subscriptionDao.updateLastRegisteredFcmToken(sub.channelId, currentToken)
                }
            }.onFailure { err ->
                if (err is CrossNotifyApiException && err.isRevokedOrUnauthorized) {
                    handleSubscriptionRevoked(sub.channelId)
                } else {
                    Log.w(TAG, "Token refresh check failed for ${sub.channelId}: ${err.message}")
                }
            }
        }
    }

    fun scheduleCatchUpSync(channelId: String? = null) {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        val request = OneTimeWorkRequestBuilder<CatchUpSyncWorker>()
            .setConstraints(constraints)
            .setInputData(workDataOf(CatchUpSyncWorker.KEY_CHANNEL_ID to channelId))
            .build()

        val workName = if (channelId != null) "catchup_$channelId" else "catchup_all"
        WorkManager.getInstance(context).enqueueUniqueWork(
            workName,
            ExistingWorkPolicy.REPLACE,
            request
        )
    }
}
