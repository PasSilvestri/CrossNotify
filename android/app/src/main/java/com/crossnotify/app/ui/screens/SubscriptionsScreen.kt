package com.crossnotify.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.crossnotify.app.data.db.RelayChannelEntity
import com.crossnotify.app.data.db.SubscriptionEntity
import com.crossnotify.app.ui.MainViewModel
import com.crossnotify.app.ui.theme.parseHexColor

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

    var selectedSubscriptionId by remember { mutableStateOf<String?>(null) }
    val selectedSubscription = subscriptions.firstOrNull { it.channelId == selectedSubscriptionId }

    if (selectedSubscription != null) {
        val server = servers.firstOrNull { it.id == selectedSubscription.serverId }
        SubscriptionDetailContent(
            subscription = selectedSubscription,
            serverName = server?.name ?: "Server",
            serverUrl = server?.url ?: "",
            availableRelayChannels = relayChannels.filter { it.active && it.channelId != selectedSubscription.channelId },
            onBack = { selectedSubscriptionId = null },
            onChangeDeliveryMode = { mode ->
                viewModel.updateSubscriptionDeliveryMode(selectedSubscription.channelId, mode)
            },
            onChangeForwarding = { targetRelayId ->
                viewModel.setSubscriptionForwarding(selectedSubscription.channelId, targetRelayId)
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
        topBar = {
            TopAppBar(
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
    onBack: () -> Unit,
    onChangeDeliveryMode: (String) -> Unit,
    onChangeForwarding: (String?) -> Unit,
    onUnsubscribe: () -> Unit
) {
    var showUnsubscribeConfirm by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
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
