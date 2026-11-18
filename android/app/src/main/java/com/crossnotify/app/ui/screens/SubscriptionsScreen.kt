package com.crossnotify.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.NotificationsOff
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.crossnotify.app.data.api.ConnectionTestResult
import com.crossnotify.app.data.db.RelayChannelEntity
import com.crossnotify.app.data.db.SubscriptionEntity
import com.crossnotify.app.data.prefs.SilencedAppRule
import com.crossnotify.app.ui.MainViewModel
import com.crossnotify.app.ui.theme.parseHexColor
import java.text.DateFormat
import java.util.Date

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SubscriptionsScreen(
    viewModel: MainViewModel,
    onScanSubscriberQr: () -> Unit
) {
    val subscriptions by viewModel.subscriptions.collectAsState()
    val servers by viewModel.servers.collectAsState()
    val relayChannels by viewModel.relayChannels.collectAsState()
    val inboxItems by viewModel.decryptedInbox.collectAsState()
    val silenceVersion by viewModel.silenceVersion.collectAsState()
    val retentionVersion by viewModel.retentionVersion.collectAsState()

    var selectedSubscriptionId by remember { mutableStateOf<String?>(null) }
    val selectedSubscription = subscriptions.firstOrNull { it.channelId == selectedSubscriptionId }

    // Keep unread counts and channel inbox up to date when viewing subscriptions
    LaunchedEffect(Unit) {
        viewModel.syncInboxSilently(fromBeginning = true)
    }

    LaunchedEffect(selectedSubscriptionId) {
        selectedSubscriptionId?.let { channelId ->
            viewModel.syncInboxSilently(channelId = channelId, fromBeginning = true)
            viewModel.refreshSilences(channelId)
        }
    }

    if (selectedSubscription != null) {
        val server = servers.firstOrNull { it.id == selectedSubscription.serverId }
        val knownApps = remember(inboxItems, selectedSubscription.channelId) {
            inboxItems
                .asSequence()
                .filter { it.entity.channelId == selectedSubscription.channelId }
                .mapNotNull { item ->
                    val label = item.plaintext?.app?.trim()
                        ?.ifEmpty { item.entity.appName?.trim().orEmpty() }
                        ?: item.entity.appName?.trim().orEmpty()
                    if (label.isEmpty()) null else label to label
                }
                .distinctBy { it.first.lowercase() }
                .sortedBy { it.second.lowercase() }
                .toList()
        }
        val channelSilencedUntil = remember(selectedSubscription.channelId, silenceVersion) {
            viewModel.getChannelSilenceUntil(selectedSubscription.channelId)
        }
        val silencedApps = remember(selectedSubscription.channelId, silenceVersion) {
            viewModel.getSilencedAppsForChannel(selectedSubscription.channelId)
        }
        val retentionMs = remember(selectedSubscription.channelId, retentionVersion) {
            viewModel.getChannelRetentionMs(selectedSubscription.channelId)
        }

        SubscriptionDetailContent(
            subscription = selectedSubscription,
            serverName = server?.name ?: "Server",
            serverUrl = server?.url ?: "",
            availableRelayChannels = relayChannels.filter { it.active && it.channelId != selectedSubscription.channelId },
            knownApps = knownApps,
            channelSilencedUntil = channelSilencedUntil,
            silencedApps = silencedApps,
            retentionMs = retentionMs,
            onBack = { selectedSubscriptionId = null },
            onTestConnection = { onResult ->
                viewModel.testSubscriptionConnection(selectedSubscription.channelId, onResult)
            },
            onChangeDeliveryMode = { mode ->
                viewModel.updateSubscriptionDeliveryMode(selectedSubscription.channelId, mode)
            },
            onChangeForwarding = { targetRelayId ->
                viewModel.setSubscriptionForwarding(selectedSubscription.channelId, targetRelayId)
            },
            onSilenceChannel = { durationMs ->
                viewModel.silenceChannel(selectedSubscription.channelId, durationMs)
            },
            onSilenceApp = { appId, appLabel, durationMs ->
                viewModel.silenceApp(selectedSubscription.channelId, appId, appLabel, durationMs)
            },
            onUnsilenceChannel = {
                viewModel.unsilenceChannel(selectedSubscription.channelId)
            },
            onUnsilenceApp = { appId ->
                viewModel.unsilenceApp(selectedSubscription.channelId, appId)
            },
            onSetRetentionMs = { newRetentionMs ->
                viewModel.setChannelRetentionMs(selectedSubscription.channelId, newRetentionMs)
            },
            onUnsubscribe = {
                viewModel.unsubscribe(selectedSubscription.channelId) {
                    selectedSubscriptionId = null
                }
            }
        )
        return
    }

    val serverById = remember(servers) { servers.associateBy { it.id } }

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            TopAppBar(
                expandedHeight = 56.dp,
                windowInsets = WindowInsets(0, 0, 0, 0),
                title = {
                    Column {
                        Text("Subscriptions", fontWeight = FontWeight.Bold)
                        Text(
                            text = "Receive & decrypt E2E notifications from relay phones",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = onScanSubscriberQr) {
                Row(
                    modifier = Modifier.padding(horizontal = 16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Default.Add, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Add Subscription")
                }
            }
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            if (subscriptions.isEmpty()) {
                item {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                        )
                    ) {
                        Column(
                            modifier = Modifier.padding(24.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Icon(
                                imageVector = Icons.Default.QrCodeScanner,
                                contentDescription = null,
                                modifier = Modifier.size(48.dp),
                                tint = MaterialTheme.colorScheme.primary
                            )
                            Spacer(modifier = Modifier.height(12.dp))
                            Text(
                                text = "No Subscriptions Yet",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold
                            )
                            Spacer(modifier = Modifier.height(6.dp))
                            Text(
                                text = "On the Relay phone, open a channel and tap 'Share with Subscriber'. Then scan the cn-sub QR code here in person.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(modifier = Modifier.height(16.dp))
                            Button(onClick = onScanSubscriberQr) {
                                Icon(Icons.Default.Add, contentDescription = null)
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("Scan Relay Phone QR (cn-sub)")
                            }
                        }
                    }
                }
            } else {
                items(subscriptions, key = { it.channelId }) { sub ->
                    val server = serverById[sub.serverId]
                    val unreadCount = inboxItems.count {
                        it.entity.channelId == sub.channelId && it.entity.readAt == null
                    }
                    val isChannelSilenced = remember(sub.channelId, silenceVersion) {
                        viewModel.getChannelSilenceUntil(sub.channelId) != null
                    }

                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { selectedSubscriptionId = sub.channelId }
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(16.dp)
                                    .clip(CircleShape)
                                    .background(parseHexColor(sub.color))
                            )
                            Spacer(modifier = Modifier.width(12.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        text = sub.name,
                                        style = MaterialTheme.typography.titleMedium,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Surface(
                                        color = if (sub.status == "active") {
                                            MaterialTheme.colorScheme.secondaryContainer
                                        } else {
                                            MaterialTheme.colorScheme.errorContainer
                                        },
                                        shape = MaterialTheme.shapes.small
                                    ) {
                                        Text(
                                            text = if (sub.status == "active") {
                                                if (sub.deliveryMode == "immediate_preview") "IMMEDIATE" else "BATTERY SAVER"
                                            } else {
                                                "REVOKED"
                                            },
                                            style = MaterialTheme.typography.labelSmall,
                                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                        )
                                    }
                                    if (isChannelSilenced) {
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Surface(
                                            color = MaterialTheme.colorScheme.tertiaryContainer,
                                            shape = MaterialTheme.shapes.small
                                        ) {
                                            Text(
                                                text = "SILENCED",
                                                style = MaterialTheme.typography.labelSmall,
                                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                            )
                                        }
                                    }
                                }
                                Text(
                                    text = buildString {
                                        if (sub.relayDevice.isNotBlank()) {
                                            append("Relay: ${sub.relayDevice} • ")
                                        }
                                        append(server?.name ?: "Server")
                                    },
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                if (!sub.forwardToRelayChannelId.isNullOrBlank()) {
                                    val targetRelay = relayChannels.firstOrNull { it.channelId == sub.forwardToRelayChannelId }
                                    Text(
                                        text = "Daisy-chain forwarding → ${targetRelay?.name ?: sub.forwardToRelayChannelId}",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                }
                            }
                            if (unreadCount > 0) {
                                Surface(
                                    color = MaterialTheme.colorScheme.primary,
                                    shape = CircleShape
                                ) {
                                    Text(
                                        text = unreadCount.toString(),
                                        color = MaterialTheme.colorScheme.onPrimary,
                                        style = MaterialTheme.typography.labelMedium,
                                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SubscriptionDetailContent(
    subscription: SubscriptionEntity,
    serverName: String,
    serverUrl: String,
    availableRelayChannels: List<RelayChannelEntity>,
    knownApps: List<Pair<String, String>>,
    channelSilencedUntil: Long?,
    silencedApps: List<SilencedAppRule>,
    retentionMs: Long,
    onBack: () -> Unit,
    onTestConnection: ((ConnectionTestResult) -> Unit) -> Unit,
    onChangeDeliveryMode: (String) -> Unit,
    onChangeForwarding: (String?) -> Unit,
    onSilenceChannel: (Long) -> Unit,
    onSilenceApp: (String, String, Long) -> Unit,
    onUnsilenceChannel: () -> Unit,
    onUnsilenceApp: (String) -> Unit,
    onSetRetentionMs: (Long) -> Unit,
    onUnsubscribe: () -> Unit
) {
    var showUnsubscribeConfirm by remember { mutableStateOf(false) }
    var showSilenceDialog by remember { mutableStateOf(false) }
    var showRetentionDialog by remember { mutableStateOf(false) }
    var isTestingConnection by remember { mutableStateOf(false) }
    var connectionTestResult by remember { mutableStateOf<ConnectionTestResult?>(null) }

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            TopAppBar(
                expandedHeight = 56.dp,
                windowInsets = WindowInsets(0, 0, 0, 0),
                title = { Text(subscription.name, fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            text = "$serverName ($serverUrl)",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Medium
                        )
                        if (subscription.relayDevice.isNotBlank()) {
                            Text(
                                text = "Relay Device: ${subscription.relayDevice}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Text(
                            text = "Status: ${subscription.status.uppercase()} • E2E Key ID: ${subscription.kid}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            item {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            text = "Server Connection",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "Verify network reachability and subscription authorization with $serverName.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(10.dp))
                        Button(
                            onClick = {
                                isTestingConnection = true
                                connectionTestResult = null
                                onTestConnection { result ->
                                    isTestingConnection = false
                                    connectionTestResult = result
                                }
                            },
                            enabled = !isTestingConnection
                        ) {
                            if (isTestingConnection) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(16.dp),
                                    strokeWidth = 2.dp,
                                    color = MaterialTheme.colorScheme.onPrimary
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("Testing...")
                            } else {
                                Icon(Icons.Default.Refresh, contentDescription = null)
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("Test Connection")
                            }
                        }
                        if (connectionTestResult != null) {
                            Spacer(modifier = Modifier.height(8.dp))
                            val result = connectionTestResult!!
                            Text(
                                text = result.message,
                                style = MaterialTheme.typography.bodySmall,
                                fontWeight = FontWeight.Medium,
                                color = if (result.isSuccess) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.error
                                }
                            )
                        }
                    }
                }
            }

            item {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            text = "Delivery Mode",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable(enabled = subscription.status == "active") {
                                    onChangeDeliveryMode("battery_saver")
                                }
                                .padding(vertical = 4.dp)
                        ) {
                            RadioButton(
                                selected = subscription.deliveryMode == "battery_saver",
                                onClick = { onChangeDeliveryMode("battery_saver") },
                                enabled = subscription.status == "active"
                            )
                            Column {
                                Text(
                                    text = "Battery Saver (Default)",
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.Medium
                                )
                                Text(
                                    text = "Google Play Services shows '${subscription.name} • App' without waking the app. Tap notification to fetch & decrypt.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }

                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable(enabled = subscription.status == "active") {
                                    onChangeDeliveryMode("immediate_preview")
                                }
                                .padding(vertical = 4.dp)
                        ) {
                            RadioButton(
                                selected = subscription.deliveryMode == "immediate_preview",
                                onClick = { onChangeDeliveryMode("immediate_preview") },
                                enabled = subscription.status == "active"
                            )
                            Column {
                                Text(
                                    text = "Immediate Preview",
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.Medium
                                )
                                Text(
                                    text = "Wakes briefly on arrival, decrypts in background, and posts rich local notification on dedicated channel '${subscription.name}'.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            }

            item {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            text = "Daisy-Chain Forwarding",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "Internally re-encrypts decrypted notifications with another local relay channel's E2E key (max 3 hops loop prevention). Requires Immediate Preview mode.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(8.dp))

                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable(enabled = subscription.status == "active") {
                                    onChangeForwarding(null)
                                }
                        ) {
                            RadioButton(
                                selected = subscription.forwardToRelayChannelId == null,
                                onClick = { onChangeForwarding(null) },
                                enabled = subscription.status == "active"
                            )
                            Text("Do not forward (Off)")
                        }

                        if (availableRelayChannels.isEmpty()) {
                            Text(
                                text = "Add a Relay Channel on this phone to enable daisy-chain forwarding.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(start = 12.dp, top = 4.dp)
                            )
                        } else {
                            availableRelayChannels.forEach { relay ->
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable(enabled = subscription.status == "active") {
                                            onChangeForwarding(relay.channelId)
                                        }
                                ) {
                                    RadioButton(
                                        selected = subscription.forwardToRelayChannelId == relay.channelId,
                                        onClick = { onChangeForwarding(relay.channelId) },
                                        enabled = subscription.status == "active"
                                    )
                                    Text("Forward to Relay: ${relay.name}")
                                }
                            }
                        }
                    }
                }
            }

            item {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            text = "Silence Notifications",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "Temporarily mute local alerts from this relay or a specific app on it. Messages are still saved to your inbox.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(10.dp))

                        if (channelSilencedUntil != null) {
                            Surface(
                                color = MaterialTheme.colorScheme.tertiaryContainer,
                                shape = MaterialTheme.shapes.small,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text(
                                        text = "All notifications silenced until ${formatSilenceUntil(channelSilencedUntil)}",
                                        style = MaterialTheme.typography.bodySmall,
                                        fontWeight = FontWeight.Medium,
                                        modifier = Modifier.weight(1f)
                                    )
                                    TextButton(onClick = onUnsilenceChannel) {
                                        Text("Unsilence")
                                    }
                                }
                            }
                            Spacer(modifier = Modifier.height(8.dp))
                        }

                        silencedApps.forEach { rule ->
                            Surface(
                                color = MaterialTheme.colorScheme.surfaceVariant,
                                shape = MaterialTheme.shapes.small,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(bottom = 6.dp)
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text(
                                        text = "${rule.appLabel} silenced until ${formatSilenceUntil(rule.silencedUntilMs)}",
                                        style = MaterialTheme.typography.bodySmall,
                                        modifier = Modifier.weight(1f)
                                    )
                                    IconButton(
                                        onClick = { onUnsilenceApp(rule.appIdentifier) },
                                        modifier = Modifier.size(28.dp)
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.Close,
                                            contentDescription = "Unsilence ${rule.appLabel}",
                                            modifier = Modifier.size(16.dp)
                                        )
                                    }
                                }
                            }
                        }

                        OutlinedButton(
                            onClick = { showSilenceDialog = true },
                            enabled = subscription.status == "active"
                        ) {
                            Icon(Icons.Default.NotificationsOff, contentDescription = null)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Silence for...")
                        }
                    }
                }
            }

            item {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            text = "Notification Retention",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "Automatically delete older local notifications for this channel without waking the device in the background.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(10.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = formatRetentionLabel(retentionMs),
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Medium,
                                modifier = Modifier.weight(1f)
                            )
                            OutlinedButton(onClick = { showRetentionDialog = true }) {
                                Text("Change...")
                            }
                        }
                    }
                }
            }

            item {
                Spacer(modifier = Modifier.height(12.dp))
                OutlinedButton(
                    onClick = { showUnsubscribeConfirm = true },
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = MaterialTheme.colorScheme.error
                    ),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Default.Delete, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Unsubscribe & Wipe Local E2E Keys")
                }
            }
        }
    }

    if (showSilenceDialog) {
        SilenceForDialog(
            channelName = subscription.name,
            knownApps = knownApps,
            onDismiss = { showSilenceDialog = false },
            onConfirmChannelSilence = { durationMs ->
                showSilenceDialog = false
                onSilenceChannel(durationMs)
            },
            onConfirmAppSilence = { appId, appLabel, durationMs ->
                showSilenceDialog = false
                onSilenceApp(appId, appLabel, durationMs)
            }
        )
    }

    if (showRetentionDialog) {
        RetentionDialog(
            channelName = subscription.name,
            currentRetentionMs = retentionMs,
            onDismiss = { showRetentionDialog = false },
            onConfirm = { newRetentionMs ->
                showRetentionDialog = false
                onSetRetentionMs(newRetentionMs)
            }
        )
    }

    if (showUnsubscribeConfirm) {
        AlertDialog(
            onDismissRequest = { showUnsubscribeConfirm = false },
            title = { Text("Unsubscribe from ${subscription.name}?") },
            text = {
                Text("This will revoke this device's subscriber key on the server and permanently wipe the channel's E2E decryption key and local inbox.")
            },
            confirmButton = {
                Button(
                    onClick = {
                        showUnsubscribeConfirm = false
                        onUnsubscribe()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) {
                    Text("Unsubscribe")
                }
            },
            dismissButton = {
                TextButton(onClick = { showUnsubscribeConfirm = false }) {
                    Text("Cancel")
                }
            }
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SilenceForDialog(
    channelName: String,
    knownApps: List<Pair<String, String>>,
    onDismiss: () -> Unit,
    onConfirmChannelSilence: (Long) -> Unit,
    onConfirmAppSilence: (String, String, Long) -> Unit
) {
    var silenceAll by remember { mutableStateOf(true) }
    var selectedAppId by remember { mutableStateOf(knownApps.firstOrNull()?.first.orEmpty()) }
    var customAppInput by remember { mutableStateOf("") }

    val presets = listOf(
        "1h" to 1L * 60L * 60L * 1000L,
        "3h" to 3L * 60L * 60L * 1000L,
        "5h" to 5L * 60L * 60L * 1000L,
        "8h" to 8L * 60L * 60L * 1000L
    )
    var selectedPresetIndex by remember { mutableStateOf(0) } // 0..3 presets, 4 = custom
    var customHoursText by remember { mutableStateOf("1") }
    var customMinutesText by remember { mutableStateOf("0") }

    val durationMs: Long = if (selectedPresetIndex in presets.indices) {
        presets[selectedPresetIndex].second
    } else {
        val h = customHoursText.toLongOrNull()?.coerceAtLeast(0L) ?: 0L
        val m = customMinutesText.toLongOrNull()?.coerceAtLeast(0L) ?: 0L
        (h * 60L + m) * 60L * 1000L
    }

    val resolvedAppPair: Pair<String, String>? = if (silenceAll) {
        null
    } else if (customAppInput.isNotBlank()) {
        val trimmed = customAppInput.trim()
        trimmed to trimmed
    } else if (selectedAppId.isNotBlank()) {
        val label = knownApps.firstOrNull { it.first == selectedAppId }?.second ?: selectedAppId
        selectedAppId to label
    } else {
        null
    }

    val canConfirm = durationMs > 0L && (silenceAll || resolvedAppPair != null)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Silence $channelName", fontWeight = FontWeight.Bold) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = "What to silence",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold
                )
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { silenceAll = true }
                ) {
                    RadioButton(
                        selected = silenceAll,
                        onClick = { silenceAll = true }
                    )
                    Text("All notifications from $channelName")
                }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { silenceAll = false }
                ) {
                    RadioButton(
                        selected = !silenceAll,
                        onClick = { silenceAll = false }
                    )
                    Text("Specific app from $channelName")
                }

                if (!silenceAll) {
                    if (knownApps.isNotEmpty()) {
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            knownApps.forEach { (appId, appLabel) ->
                                FilterChip(
                                    selected = customAppInput.isBlank() && selectedAppId == appId,
                                    onClick = {
                                        selectedAppId = appId
                                        customAppInput = ""
                                    },
                                    label = { Text(appLabel) }
                                )
                            }
                        }
                    }
                    OutlinedTextField(
                        value = customAppInput,
                        onValueChange = { customAppInput = it },
                        label = { Text("Or enter app / package name") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }

                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "Duration",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold
                )
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    presets.forEachIndexed { index, (label, _) ->
                        FilterChip(
                            selected = selectedPresetIndex == index,
                            onClick = { selectedPresetIndex = index },
                            label = { Text(label) }
                        )
                    }
                    FilterChip(
                        selected = selectedPresetIndex == presets.size,
                        onClick = { selectedPresetIndex = presets.size },
                        label = { Text("Custom") }
                    )
                }

                if (selectedPresetIndex == presets.size) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        OutlinedTextField(
                            value = customHoursText,
                            onValueChange = { input ->
                                customHoursText = input.filter { it.isDigit() }.take(3)
                            },
                            label = { Text("Hours") },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            singleLine = true,
                            modifier = Modifier.weight(1f)
                        )
                        OutlinedTextField(
                            value = customMinutesText,
                            onValueChange = { input ->
                                customMinutesText = input.filter { it.isDigit() }.take(3)
                            },
                            label = { Text("Minutes") },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            singleLine = true,
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    if (silenceAll) {
                        onConfirmChannelSilence(durationMs)
                    } else if (resolvedAppPair != null) {
                        onConfirmAppSilence(resolvedAppPair.first, resolvedAppPair.second, durationMs)
                    }
                },
                enabled = canConfirm
            ) {
                Text("Silence")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}

private fun formatSilenceUntil(timestampMs: Long): String {
    return DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(timestampMs))
}

private const val HOUR_MS = 60L * 60L * 1000L
private const val DAY_MS = 24L * HOUR_MS
private const val MONTH_MS = 30L * DAY_MS

private fun formatRetentionLabel(retentionMs: Long): String {
    if (retentionMs <= 0L) return "Keep indefinitely"
    return when {
        retentionMs % MONTH_MS == 0L -> {
            val months = retentionMs / MONTH_MS
            "Delete after $months ${if (months == 1L) "month" else "months"}"
        }
        retentionMs % DAY_MS == 0L -> {
            val days = retentionMs / DAY_MS
            "Delete after $days ${if (days == 1L) "day" else "days"}"
        }
        else -> {
            val hours = (retentionMs / HOUR_MS).coerceAtLeast(1L)
            "Delete after $hours ${if (hours == 1L) "hour" else "hours"}"
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun RetentionDialog(
    channelName: String,
    currentRetentionMs: Long,
    onDismiss: () -> Unit,
    onConfirm: (Long) -> Unit
) {
    val initialUnit = when {
        currentRetentionMs <= 0L -> 1 // Days default
        currentRetentionMs % MONTH_MS == 0L -> 2
        currentRetentionMs % DAY_MS == 0L -> 1
        else -> 0
    }
    val initialAmount = when {
        currentRetentionMs <= 0L -> "7"
        initialUnit == 2 -> (currentRetentionMs / MONTH_MS).toString()
        initialUnit == 1 -> (currentRetentionMs / DAY_MS).toString()
        else -> (currentRetentionMs / HOUR_MS).coerceAtLeast(1L).toString()
    }

    var keepIndefinitely by remember { mutableStateOf(currentRetentionMs <= 0L) }
    var selectedUnit by remember { mutableStateOf(initialUnit) } // 0=Hours, 1=Days, 2=Months
    var amountText by remember { mutableStateOf(initialAmount) }

    val unitMultipliers = listOf(HOUR_MS, DAY_MS, MONTH_MS)
    val unitLabels = listOf("Hours", "Days", "Months")

    val parsedAmount = amountText.toLongOrNull() ?: 0L
    val canConfirm = keepIndefinitely || parsedAmount > 0L

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Retention for $channelName", fontWeight = FontWeight.Bold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { keepIndefinitely = true }
                ) {
                    RadioButton(
                        selected = keepIndefinitely,
                        onClick = { keepIndefinitely = true }
                    )
                    Text("Keep indefinitely")
                }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { keepIndefinitely = false }
                ) {
                    RadioButton(
                        selected = !keepIndefinitely,
                        onClick = { keepIndefinitely = false }
                    )
                    Text("Delete after custom duration")
                }

                if (!keepIndefinitely) {
                    OutlinedTextField(
                        value = amountText,
                        onValueChange = { input ->
                            amountText = input.filter { it.isDigit() }.take(4)
                        },
                        label = { Text("Duration") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        unitLabels.forEachIndexed { index, label ->
                            FilterChip(
                                selected = selectedUnit == index,
                                onClick = { selectedUnit = index },
                                label = { Text(label) }
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val resultMs = if (keepIndefinitely) {
                        0L
                    } else {
                        parsedAmount * unitMultipliers[selectedUnit]
                    }
                    onConfirm(resultMs)
                },
                enabled = canConfirm
            ) {
                Text("Save")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}
