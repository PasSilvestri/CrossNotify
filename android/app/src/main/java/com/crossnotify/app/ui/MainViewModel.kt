package com.crossnotify.app.ui

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import androidx.core.app.NotificationManagerCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.crossnotify.app.data.api.ChannelInfoResponse
import com.crossnotify.app.data.db.OutboxEntity
import com.crossnotify.app.data.db.RelayChannelEntity
import com.crossnotify.app.data.db.ServerEntity
import com.crossnotify.app.data.db.SubscriptionEntity
import com.crossnotify.app.data.prefs.AppPreferences
import com.crossnotify.app.data.prefs.SilencedAppRule
import com.crossnotify.app.data.repository.CrossNotifyRepository
import com.crossnotify.app.data.repository.DecryptedInboxItem
import com.crossnotify.app.qr.CnRelayQrPayload
import com.crossnotify.app.qr.CnSubQrPayload
import com.crossnotify.app.qr.ParsedQrPayload
import com.crossnotify.app.qr.QrCodeGenerator
import com.crossnotify.app.qr.QrPayloadParser
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

data class InstalledAppInfo(
    val packageName: String,
    val label: String
)

data class PendingQrConfirmation(
    val parsed: ParsedQrPayload,
    val requiresInsecureHttpWarning: Boolean,
    val hasFirebaseProjectConflict: Boolean
)

data class GeneratedSubscriberQrState(
    val channelId: String,
    val channelName: String,
    val subscriberId: String,
    val qrPayload: CnSubQrPayload,
    val bitmap: Bitmap,
    val payloadJson: String,
    val expiresAtMs: Long,
    val allowActions: Boolean = true,
    val isUpdatingPermissions: Boolean = false,
    val createdAtMs: Long = System.currentTimeMillis()
)

@HiltViewModel
class MainViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val repository: CrossNotifyRepository,
    private val appPreferences: AppPreferences
) : ViewModel() {

    val servers: StateFlow<List<ServerEntity>> = repository.observeServers()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val relayChannels: StateFlow<List<RelayChannelEntity>> = repository.observeRelayChannels()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val subscriptions: StateFlow<List<SubscriptionEntity>> = repository.observeSubscriptions()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val outboxItems: StateFlow<List<OutboxEntity>> = repository.observeOutbox()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val decryptedInbox: StateFlow<List<DecryptedInboxItem>> = combine(
        repository.observeInbox(),
        repository.observeSubscriptions(),
        appPreferences.retentionVersion
    ) { inboxList, subList, _ ->
        val subById = subList.associateBy { it.channelId }
        val now = System.currentTimeMillis()
        withContext(Dispatchers.Default) {
            inboxList
                .filter { entity ->
                    val retentionMs = appPreferences.getChannelRetentionMs(entity.channelId)
                    retentionMs <= 0L || entity.receivedAt >= now - retentionMs
                }
                .map { entity ->
                    val sub = subById[entity.channelId]
                    val plaintext = repository.decryptInboxEntity(entity, sub)
                    DecryptedInboxItem(
                        entity = entity,
                        subscription = sub,
                        plaintext = plaintext
                    )
                }
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val deviceName: StateFlow<String> = appPreferences.deviceName
    val allowInsecureHttpByDefault: StateFlow<Boolean> = appPreferences.allowInsecureHttpByDefault
    val silenceVersion: StateFlow<Int> = appPreferences.silenceVersion
    val retentionVersion: StateFlow<Int> = appPreferences.retentionVersion

    private val _installedApps = MutableStateFlow<List<InstalledAppInfo>>(emptyList())
    val installedApps: StateFlow<List<InstalledAppInfo>> = _installedApps.asStateFlow()

    private val _pendingQrConfirmation = MutableStateFlow<PendingQrConfirmation?>(null)
    val pendingQrConfirmation: StateFlow<PendingQrConfirmation?> = _pendingQrConfirmation.asStateFlow()

    private val _generatedSubQr = MutableStateFlow<GeneratedSubscriberQrState?>(null)
    val generatedSubQr: StateFlow<GeneratedSubscriberQrState?> = _generatedSubQr.asStateFlow()

    private val _channelInfoMap = MutableStateFlow<Map<String, ChannelInfoResponse>>(emptyMap())
    val channelInfoMap: StateFlow<Map<String, ChannelInfoResponse>> = _channelInfoMap.asStateFlow()

    private val _isBusy = MutableStateFlow(false)
    val isBusy: StateFlow<Boolean> = _isBusy.asStateFlow()

    private val _isSyncingInbox = MutableStateFlow(false)
    val isSyncingInbox: StateFlow<Boolean> = _isSyncingInbox.asStateFlow()

    private val _statusMessage = MutableStateFlow<String?>(null)
    val statusMessage: StateFlow<String?> = _statusMessage.asStateFlow()

    init {
        loadInstalledApps()
        syncRelayChannels()
        viewModelScope.launch {
            repository.pruneExpiredInboxNotifications()
        }
    }

    fun syncRelayChannels() {
        viewModelScope.launch {
            repository.syncRelayChannelsStatus()
        }
    }

    fun clearStatusMessage() {
        _statusMessage.value = null
    }

    fun dismissPendingQrConfirmation() {
        _pendingQrConfirmation.value = null
    }

    fun dismissGeneratedSubQr() {
        _generatedSubQr.value = null
    }

    fun observeFilterPackages(channelId: String) = repository.observeFilterPackages(channelId)

    fun getTodayRelayCount(channelId: String): Int = appPreferences.getTodayRelayCount(channelId)

    fun loadInstalledApps() {
        viewModelScope.launch(Dispatchers.IO) {
            val pm = context.packageManager
            val mainIntent = Intent(Intent.ACTION_MAIN, null).apply {
                addCategory(Intent.CATEGORY_LAUNCHER)
            }
            val resolveInfos = pm.queryIntentActivities(mainIntent, 0)
            val apps = resolveInfos
                .mapNotNull { info ->
                    val pkg = info.activityInfo?.packageName ?: return@mapNotNull null
                    if (pkg == context.packageName) return@mapNotNull null
                    val label = info.loadLabel(pm)?.toString()?.trim().orEmpty().ifEmpty { pkg }
                    InstalledAppInfo(packageName = pkg, label = label)
                }
                .distinctBy { it.packageName }
                .sortedBy { it.label.lowercase() }

            _installedApps.value = apps
        }
    }

    /**
     * Parses a scanned QR string and prepares a confirmation dialog for the user.
     * [expectedType] can be `"cn-relay"` or `"cn-sub"`.
     */
    fun onQrCodeScanned(rawJson: String, expectedType: String) {
        viewModelScope.launch {
            val result = QrPayloadParser.parse(rawJson)
            result.onFailure { err ->
                _statusMessage.value = "Invalid QR code: ${err.message}"
            }.onSuccess { parsed ->
                when (parsed) {
                    is ParsedQrPayload.RelayProvisioning -> {
                        if (expectedType != "cn-relay") {
                            _statusMessage.value = "Expected a Subscriber QR (cn-sub), but scanned a Relay QR (cn-relay)."
                            return@launch
                        }
                        val listenerPackages = NotificationManagerCompat.getEnabledListenerPackages(context)
                        if (!listenerPackages.contains(context.packageName)) {
                            _statusMessage.value = "Notification Access required: Please grant Notification Access in Settings before activating a relay channel."
                            return@launch
                        }
                        val isHttp = QrPayloadParser.isInsecureHttp(parsed.payload.server.url) &&
                            !allowInsecureHttpByDefault.value
                        val hasConflict = repository.hasFirebaseParameterConflict(
                            parsed.payload.server.url,
                            parsed.payload.firebase
                        )
                        _pendingQrConfirmation.value = PendingQrConfirmation(
                            parsed = parsed,
                            requiresInsecureHttpWarning = isHttp,
                            hasFirebaseProjectConflict = hasConflict
                        )
                    }
                    is ParsedQrPayload.SubscriberPairing -> {
                        if (expectedType != "cn-sub") {
                            _statusMessage.value = "Expected a Relay QR (cn-relay), but scanned a Subscriber QR (cn-sub)."
                            return@launch
                        }
                        val existing = repository.getSubscription(parsed.payload.channel.id)
                        if (existing != null && existing.status == "active") {
                            _statusMessage.value = "Already subscribed: You already have an active subscription to '${existing.name}' on this device."
                            return@launch
                        }
                        val isHttp = QrPayloadParser.isInsecureHttp(parsed.payload.server.url) &&
                            !allowInsecureHttpByDefault.value
                        val hasConflict = repository.hasFirebaseParameterConflict(
                            parsed.payload.server.url,
                            parsed.payload.firebase
                        )
                        _pendingQrConfirmation.value = PendingQrConfirmation(
                            parsed = parsed,
                            requiresInsecureHttpWarning = isHttp,
                            hasFirebaseProjectConflict = hasConflict
                        )
                    }
                }
            }
        }
    }

    fun confirmRelayProvisioning(payload: CnRelayQrPayload, acceptInsecureHttp: Boolean) {
        val listenerPackages = NotificationManagerCompat.getEnabledListenerPackages(context)
        if (!listenerPackages.contains(context.packageName)) {
            _statusMessage.value = "Cannot create relay channel: Notification Access has not been granted."
            return
        }
        viewModelScope.launch {
            _isBusy.value = true
            _pendingQrConfirmation.value = null
            runCatching {
                repository.provisionRelayChannel(payload, acceptInsecureHttp)
            }.onSuccess { channel ->
                _statusMessage.value = "Relay channel '${channel.name}' activated with fresh E2E key!"
                refreshRelayChannelInfo(channel.channelId)
            }.onFailure { err ->
                _statusMessage.value = "Relay initialization failed: ${err.message}"
            }
            _isBusy.value = false
        }
    }

    fun confirmSubscriberPairing(
        payload: CnSubQrPayload,
        deliveryMode: String,
        acceptInsecureHttp: Boolean
    ) {
        viewModelScope.launch {
            _isBusy.value = true
            _pendingQrConfirmation.value = null
            runCatching {
                repository.pairSubscription(payload, deliveryMode, acceptInsecureHttp)
            }.onSuccess { sub ->
                _statusMessage.value = "Subscribed to '${sub.name}' (${sub.deliveryMode})!"
            }.onFailure { err ->
                _statusMessage.value = "Subscription pairing failed: ${err.message}"
            }
            _isBusy.value = false
        }
    }

    fun refreshRelayChannelInfo(channelId: String) {
        viewModelScope.launch {
            repository.fetchRelayChannelInfo(channelId).onSuccess { info ->
                _channelInfoMap.value = _channelInfoMap.value + (channelId to info)
            }
        }
    }

    fun testRelayConnection(channelId: String, onResult: (com.crossnotify.app.data.api.ConnectionTestResult) -> Unit) {
        viewModelScope.launch {
            val result = repository.testRelayConnection(channelId)
            if (result.isSuccess) {
                refreshRelayChannelInfo(channelId)
            }
            onResult(result)
        }
    }

    fun testSubscriptionConnection(channelId: String, onResult: (com.crossnotify.app.data.api.ConnectionTestResult) -> Unit) {
        viewModelScope.launch {
            val result = repository.testSubscriptionConnection(channelId)
            onResult(result)
        }
    }

    fun generateShareSubscriberQr(channel: RelayChannelEntity) {
        viewModelScope.launch {
            _isBusy.value = true
            runCatching {
                withContext(Dispatchers.IO) {
                    val minted = repository.createSubscriberPairingQrPayload(
                        channelId = channel.channelId,
                        allowActions = true
                    )
                    val encoded = QrPayloadParser.encodeSubscriberQr(minted.qrPayload)
                    val bitmap = QrCodeGenerator.generateBitmap(encoded, 768)
                    GeneratedSubscriberQrState(
                        channelId = channel.channelId,
                        channelName = channel.name,
                        subscriberId = minted.subscriberId,
                        qrPayload = minted.qrPayload,
                        bitmap = bitmap,
                        payloadJson = encoded,
                        expiresAtMs = minted.expiresAt,
                        allowActions = minted.qrPayload.channel.allowActions
                    )
                }
            }.onSuccess { state ->
                _generatedSubQr.value = state
            }.onFailure { err ->
                _statusMessage.value = "Could not mint subscriber key: ${err.message}"
            }
            _isBusy.value = false
        }
    }

    fun toggleGeneratedQrAllowActions(allowActions: Boolean) {
        val current = _generatedSubQr.value ?: return
        if (current.isUpdatingPermissions || current.allowActions == allowActions) return

        _generatedSubQr.value = current.copy(isUpdatingPermissions = true)
        viewModelScope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    val confirmed = repository.updateSubscriberPermissions(
                        channelId = current.channelId,
                        subscriberId = current.subscriberId,
                        allowActions = allowActions
                    )
                    val updatedPayload = current.qrPayload.copy(
                        channel = current.qrPayload.channel.copy(allowActions = confirmed)
                    )
                    val encoded = QrPayloadParser.encodeSubscriberQr(updatedPayload)
                    val bitmap = QrCodeGenerator.generateBitmap(encoded, 768)
                    current.copy(
                        qrPayload = updatedPayload,
                        bitmap = bitmap,
                        payloadJson = encoded,
                        allowActions = confirmed,
                        isUpdatingPermissions = false
                    )
                }
            }.onSuccess { updated ->
                if (_generatedSubQr.value?.subscriberId == updated.subscriberId) {
                    _generatedSubQr.value = updated
                }
            }.onFailure { err ->
                if (_generatedSubQr.value?.subscriberId == current.subscriberId) {
                    _generatedSubQr.value = current.copy(isUpdatingPermissions = false)
                }
                _statusMessage.value = "Failed to update action permission: ${err.message}"
            }
        }
    }

    fun updateRelaySubscriberAllowActions(
        channelId: String,
        subscriberId: String,
        allowActions: Boolean,
        onComplete: () -> Unit
    ) {
        viewModelScope.launch {
            runCatching {
                repository.updateSubscriberPermissions(channelId, subscriberId, allowActions)
            }.onSuccess { confirmed ->
                _statusMessage.value = if (confirmed) {
                    "Notification actions enabled for subscriber."
                } else {
                    "Notification actions disabled for subscriber."
                }
                onComplete()
            }.onFailure { err ->
                _statusMessage.value = "Failed to update subscriber permissions: ${err.message}"
            }
        }
    }

    fun executeRemoteAction(
        channelId: String,
        notificationId: String,
        actionIndex: Int,
        replyText: String? = null,
        onResult: (Result<CrossNotifyRepository.RemoteActionOutcome>) -> Unit = {}
    ) {
        viewModelScope.launch {
            val result = repository.executeRemoteAction(
                channelId = channelId,
                notificationId = notificationId,
                actionIndex = actionIndex,
                replyText = replyText
            )
            result.onSuccess { outcome ->
                _statusMessage.value = when (outcome) {
                    is CrossNotifyRepository.RemoteActionOutcome.Executed ->
                        if (replyText != null) "Reply sent to relay device." else "Action sent to relay device."
                    is CrossNotifyRepository.RemoteActionOutcome.AlreadyHandled ->
                        "Notification was already handled."
                }
            }.onFailure { err ->
                _statusMessage.value = err.message ?: "Failed to execute action."
            }
            onResult(result)
        }
    }

    fun setRelayFilterMode(channelId: String, filterMode: String) {
        viewModelScope.launch {
            repository.setRelayFilterMode(channelId, filterMode)
        }
    }

    fun setRelaySendAppName(channelId: String, sendAppName: Boolean) {
        viewModelScope.launch {
            repository.setRelaySendAppName(channelId, sendAppName)
        }
    }

    fun toggleRelayAppFilter(channelId: String, packageName: String, include: Boolean) {
        viewModelScope.launch {
            repository.toggleRelayAppFilter(channelId, packageName, include)
        }
    }

    fun removeRelayChannel(channelId: String, onRemoved: () -> Unit) {
        viewModelScope.launch {
            _isBusy.value = true
            repository.removeRelayChannel(channelId)
            _isBusy.value = false
            _statusMessage.value = "Relay channel removed."
            onRemoved()
        }
    }

    fun updateSubscriptionDeliveryMode(channelId: String, deliveryMode: String) {
        viewModelScope.launch {
            _isBusy.value = true
            runCatching {
                repository.updateSubscriptionDeliveryMode(channelId, deliveryMode)
            }.onSuccess {
                _statusMessage.value = "Delivery mode updated to $deliveryMode."
            }.onFailure { err ->
                _statusMessage.value = "Failed to update delivery mode: ${err.message}"
            }
            _isBusy.value = false
        }
    }

    fun setSubscriptionForwarding(channelId: String, targetRelayChannelId: String?) {
        viewModelScope.launch {
            _isBusy.value = true
            runCatching {
                repository.setSubscriptionForwarding(channelId, targetRelayChannelId)
            }.onSuccess {
                _statusMessage.value = if (targetRelayChannelId != null) {
                    "Daisy-chain forwarding enabled (Immediate Preview enforced)."
                } else {
                    "Daisy-chain forwarding disabled."
                }
            }.onFailure { err ->
                _statusMessage.value = "Failed to update forwarding rule: ${err.message}"
            }
            _isBusy.value = false
        }
    }

    fun unsubscribe(channelId: String, onRemoved: () -> Unit) {
        viewModelScope.launch {
            _isBusy.value = true
            repository.unsubscribe(channelId)
            _isBusy.value = false
            _statusMessage.value = "Unsubscribed and local E2E keys wiped."
            onRemoved()
        }
    }

    fun syncCatchUpNow() {
        viewModelScope.launch {
            _isBusy.value = true
            _isSyncingInbox.value = true
            val ok = repository.syncCatchUpAllSubscriptions(fromBeginning = true)
            _isSyncingInbox.value = false
            _isBusy.value = false
            _statusMessage.value = if (ok) "Inbox synchronized." else "Sync completed with warnings."
        }
    }

    /**
     * Silently downloads and decrypts notifications for [channelId] (or all active subscriptions
     * when [channelId] is null) without blocking the UI or showing a status snackbar.
     */
    fun syncInboxSilently(channelId: String? = null, fromBeginning: Boolean = true) {
        viewModelScope.launch {
            _isSyncingInbox.value = true
            try {
                if (channelId != null) {
                    repository.syncCatchUpForSubscription(channelId, fromBeginning = fromBeginning)
                } else {
                    repository.syncCatchUpAllSubscriptions(fromBeginning = fromBeginning)
                }
            } finally {
                _isSyncingInbox.value = false
            }
        }
    }

    fun markInboxItemRead(notificationId: String) {
        viewModelScope.launch {
            repository.markInboxItemRead(notificationId)
        }
    }

    fun markChannelRead(channelId: String) {
        viewModelScope.launch {
            repository.markChannelRead(channelId)
        }
    }

    fun updateDeviceName(name: String) {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return
        appPreferences.setDeviceName(trimmed)
        viewModelScope.launch {
            _isBusy.value = true
            val result = repository.dispatchDeviceNameUpdate(trimmed)
            _isBusy.value = false
            _statusMessage.value = if (result.isSuccess) {
                "Device display name updated and dispatched."
            } else {
                "Device name saved locally; dispatch will retry on next connection."
            }
        }
    }

    fun updateAllowInsecureHttpByDefault(allow: Boolean) {
        appPreferences.setAllowInsecureHttpByDefault(allow)
    }

    fun getChannelSilenceUntil(channelId: String): Long? =
        appPreferences.getChannelSilenceUntil(channelId)

    fun getSilencedAppsForChannel(channelId: String): List<SilencedAppRule> =
        appPreferences.getSilencedAppsForChannel(channelId)

    fun silenceChannel(channelId: String, durationMs: Long) {
        viewModelScope.launch {
            _isBusy.value = true
            runCatching {
                repository.setChannelSilence(channelId, durationMs)
            }.onSuccess {
                _statusMessage.value = "Channel notifications silenced."
            }.onFailure { err ->
                _statusMessage.value = "Failed to silence channel: ${err.message}"
            }
            _isBusy.value = false
        }
    }

    fun unsilenceChannel(channelId: String) {
        viewModelScope.launch {
            _isBusy.value = true
            runCatching {
                repository.clearChannelSilence(channelId)
            }.onSuccess {
                _statusMessage.value = "Channel notifications unsilenced."
            }.onFailure { err ->
                _statusMessage.value = "Failed to unsilence channel: ${err.message}"
            }
            _isBusy.value = false
        }
    }

    fun silenceApp(channelId: String, appIdentifier: String, appLabel: String, durationMs: Long) {
        viewModelScope.launch {
            _isBusy.value = true
            runCatching {
                repository.setAppSilence(channelId, appLabel.ifBlank { appIdentifier }, durationMs)
            }.onSuccess {
                _statusMessage.value = "Silenced notifications from $appLabel."
            }.onFailure { err ->
                _statusMessage.value = "Failed to silence app: ${err.message}"
            }
            _isBusy.value = false
        }
    }

    fun unsilenceApp(channelId: String, appIdentifier: String) {
        viewModelScope.launch {
            _isBusy.value = true
            runCatching {
                repository.clearAppSilence(channelId, appIdentifier)
            }.onSuccess {
                _statusMessage.value = "Unsilenced app notifications."
            }.onFailure { err ->
                _statusMessage.value = "Failed to unsilence app: ${err.message}"
            }
            _isBusy.value = false
        }
    }

    fun refreshSilences(channelId: String) {
        viewModelScope.launch {
            repository.refreshSubscriberSilences(channelId)
        }
    }

    fun getChannelRetentionMs(channelId: String): Long =
        appPreferences.getChannelRetentionMs(channelId)

    fun setChannelRetentionMs(channelId: String, retentionMs: Long) {
        appPreferences.setChannelRetentionMs(channelId, retentionMs)
        viewModelScope.launch {
            repository.pruneExpiredInboxNotifications(channelId)
        }
        _statusMessage.value = if (retentionMs <= 0L) {
            "Notifications will be kept indefinitely."
        } else {
            "Notification retention updated."
        }
    }

    fun refreshFcmTokensNow() {
        viewModelScope.launch {
            _isBusy.value = true
            repository.refreshFcmTokensForAllSubscriptions()
            _isBusy.value = false
            _statusMessage.value = "FCM registration tokens verified."
        }
    }
}
