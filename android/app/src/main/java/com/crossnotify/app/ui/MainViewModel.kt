package com.crossnotify.app.ui

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.crossnotify.app.data.api.ChannelInfoResponse
import com.crossnotify.app.data.db.OutboxEntity
import com.crossnotify.app.data.db.RelayChannelEntity
import com.crossnotify.app.data.db.ServerEntity
import com.crossnotify.app.data.db.SubscriptionEntity
import com.crossnotify.app.data.prefs.AppPreferences
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
    val channelName: String,
    val bitmap: Bitmap,
    val payloadJson: String,
    val expiresAtMs: Long,
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
        repository.observeSubscriptions()
    ) { inboxList, subList ->
        val subById = subList.associateBy { it.channelId }
        withContext(Dispatchers.Default) {
            inboxList.map { entity ->
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

    private val _statusMessage = MutableStateFlow<String?>(null)
    val statusMessage: StateFlow<String?> = _statusMessage.asStateFlow()

    init {
        loadInstalledApps()
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

    fun generateShareSubscriberQr(channel: RelayChannelEntity) {
        viewModelScope.launch {
            _isBusy.value = true
            runCatching {
                withContext(Dispatchers.IO) {
                    val (qrPayload, expiresAt) = repository.createSubscriberPairingQrPayload(channel.channelId)
                    val encoded = QrPayloadParser.encodeSubscriberQr(qrPayload)
                    val bitmap = QrCodeGenerator.generateBitmap(encoded, 768)
                    GeneratedSubscriberQrState(
                        channelName = channel.name,
                        bitmap = bitmap,
                        payloadJson = encoded,
                        expiresAtMs = expiresAt
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
            val ok = repository.syncCatchUpAllSubscriptions()
            _isBusy.value = false
            _statusMessage.value = if (ok) "Inbox synchronized." else "Sync completed with warnings."
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
        appPreferences.setDeviceName(name)
        _statusMessage.value = "Device display name updated."
    }

    fun updateAllowInsecureHttpByDefault(allow: Boolean) {
        appPreferences.setAllowInsecureHttpByDefault(allow)
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
