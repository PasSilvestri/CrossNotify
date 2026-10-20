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
import androidx.compose.material.icons.filled.QrCode2
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
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
import androidx.compose.ui.unit.dp
import com.crossnotify.app.data.db.RelayChannelEntity
import com.crossnotify.app.ui.MainViewModel
import com.crossnotify.app.ui.components.PermissionsHealthSection
import com.crossnotify.app.ui.theme.parseHexColor

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RelayScreen(
    viewModel: MainViewModel,
    onScanRelayQr: () -> Unit,
    onShareSubscriberQrWithBiometric: (RelayChannelEntity) -> Unit
) {
    val channels by viewModel.relayChannels.collectAsState()
    val servers by viewModel.servers.collectAsState()
    val outboxItems by viewModel.outboxItems.collectAsState()
    val channelInfoMap by viewModel.channelInfoMap.collectAsState()
    val installedApps by viewModel.installedApps.collectAsState()

    var selectedChannelId by remember { mutableStateOf<String?>(null) }
    val selectedChannel = channels.firstOrNull { it.channelId == selectedChannelId }

    if (selectedChannel != null) {
        val server = servers.firstOrNull { it.id == selectedChannel.serverId }
        val filterPackages by viewModel.observeFilterPackages(selectedChannel.channelId)
            .collectAsState(initial = emptyList())
        val liveInfo = channelInfoMap[selectedChannel.channelId]

        LaunchedEffect(selectedChannel.channelId) {
            viewModel.refreshRelayChannelInfo(selectedChannel.channelId)
        }

        RelayChannelDetailContent(
            channel = selectedChannel,
            serverName = server?.name ?: "Server",
            serverUrl = server?.url ?: "",
            subscriberCount = liveInfo?.subscriberCount,
            filterPackages = filterPackages.toSet(),
            installedApps = installedApps,
            onBack = { selectedChannelId = null },
            onRefreshInfo = { viewModel.refreshRelayChannelInfo(selectedChannel.channelId) },
            onShareQr = { onShareSubscriberQrWithBiometric(selectedChannel) },
            onToggleSendAppName = { viewModel.setRelaySendAppName(selectedChannel.channelId, it) },
            onChangeFilterMode = { viewModel.setRelayFilterMode(selectedChannel.channelId, it) },
            onTogglePackageFilter = { pkg, checked ->
                viewModel.toggleRelayAppFilter(selectedChannel.channelId, pkg, checked)
            },
            onRemoveChannel = {
                viewModel.removeRelayChannel(selectedChannel.channelId) {
                    selectedChannelId = null
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
                        Text("Relay Channels", fontWeight = FontWeight.Bold)
                        Text(
                            text = "Encrypt & forward notifications from this phone",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = onScanRelayQr) {
                Row(
                    modifier = Modifier.padding(horizontal = 16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Default.Add, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Add Relay Channel")
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
            if (channels.isNotEmpty()) {
                item {
                    PermissionsHealthSection(hasRelayChannels = true)
                }
            }

            if (channels.isEmpty()) {
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
                                imageVector = Icons.Default.QrCode2,
                                contentDescription = null,
                                modifier = Modifier.size(48.dp),
                                tint = MaterialTheme.colorScheme.primary
                            )
                            Spacer(modifier = Modifier.height(12.dp))
                            Text(
                                text = "No Relay Channels Configured",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold
                            )
                            Spacer(modifier = Modifier.height(6.dp))
                            Text(
                                text = "Open your CrossNotify Server Dashboard, click 'Add relay phone', and scan the cn-relay QR code here.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(modifier = Modifier.height(16.dp))
                            Button(onClick = onScanRelayQr) {
                                Icon(Icons.Default.Add, contentDescription = null)
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("Scan Dashboard QR (cn-relay)")
                            }
                        }
                    }
                }
            } else {
                items(channels, key = { it.channelId }) { channel ->
                    val server = serverById[channel.serverId]
                    val queuedCount = outboxItems.count { it.channelId == channel.channelId }
                    val todayCount = viewModel.getTodayRelayCount(channel.channelId)

                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { selectedChannelId = channel.channelId }
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
                                    .background(parseHexColor(channel.color))
                            )
                            Spacer(modifier = Modifier.width(12.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        text = channel.name,
                                        style = MaterialTheme.typography.titleMedium,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Surface(
                                        color = if (channel.active) {
                                            MaterialTheme.colorScheme.primaryContainer
                                        } else {
                                            MaterialTheme.colorScheme.errorContainer
                                        },
                                        shape = MaterialTheme.shapes.small
                                    ) {
                                        Text(
                                            text = if (channel.active) "ACTIVE" else "REVOKED",
                                            style = MaterialTheme.typography.labelSmall,
                                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                        )
                                    }
                                }
                                Text(
                                    text = "${server?.name ?: "Server"} • ${server?.url ?: ""}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Text(
                                    text = "Today relayed: $todayCount" +
                                        if (queuedCount > 0) " • Outbox queued: $queuedCount" else "",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                            IconButton(onClick = { onShareSubscriberQrWithBiometric(channel) }) {
                                Icon(
                                    imageVector = Icons.Default.QrCode2,
                                    contentDescription = "Share with subscriber"
                                )
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
private fun RelayChannelDetailContent(
    channel: RelayChannelEntity,
    serverName: String,
    serverUrl: String,
    subscriberCount: Int?,
    filterPackages: Set<String>,
    installedApps: List<com.crossnotify.app.ui.InstalledAppInfo>,
    onBack: () -> Unit,
    onRefreshInfo: () -> Unit,
    onShareQr: () -> Unit,
    onToggleSendAppName: (Boolean) -> Unit,
    onChangeFilterMode: (String) -> Unit,
    onTogglePackageFilter: (String, Boolean) -> Unit,
    onRemoveChannel: () -> Unit
) {
    var searchQuery by remember { mutableStateOf("") }
    var showRemoveConfirm by remember { mutableStateOf(false) }

    val filteredApps = remember(installedApps, searchQuery, filterPackages) {
        val q = searchQuery.trim().lowercase()
        installedApps.filter {
            q.isEmpty() || it.label.lowercase().contains(q) || it.packageName.lowercase().contains(q)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(channel.name, fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = onRefreshInfo) {
                        Icon(Icons.Default.Refresh, contentDescription = "Refresh channel info")
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
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "Active Subscribers: ${subscriberCount?.toString() ?: "…"} • E2E Key ID: ${channel.kid}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        Button(
                            onClick = onShareQr,
                            enabled = channel.active,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(Icons.Default.QrCode2, contentDescription = null)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Share with Subscriber (Biometric + QR)")
                        }
                    }
                }
            }

            item {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "Show app name in battery-saver notifications",
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.Medium
                                )
                                Text(
                                    text = "Sends the source app name (e.g. 'WhatsApp') so battery-saver notifications show '${channel.name} • WhatsApp'.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Switch(
                                checked = channel.sendAppName,
                                onCheckedChange = onToggleSendAppName
                            )
                        }
                    }
                }
            }

            item {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            text = "App Filter Mode",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            FilterChip(
                                selected = channel.filterMode == "deny_list",
                                onClick = { onChangeFilterMode("deny_list") },
                                label = { Text("Deny-List (Block Checked)") }
                            )
                            FilterChip(
                                selected = channel.filterMode == "allow_list",
                                onClick = { onChangeFilterMode("allow_list") },
                                label = { Text("Allow-List (Only Checked)") }
                            )
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                        OutlinedTextField(
                            value = searchQuery,
                            onValueChange = { searchQuery = it },
                            label = { Text("Search installed apps or package name") },
                            leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true
                        )
                    }
                }
            }

            items(filteredApps, key = { it.packageName }) { app ->
                val isSelected = app.packageName in filterPackages
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onTogglePackageFilter(app.packageName, !isSelected) }
                        .padding(vertical = 6.dp, horizontal = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Checkbox(
                        checked = isSelected,
                        onCheckedChange = { checked -> onTogglePackageFilter(app.packageName, checked) }
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = app.label,
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Medium
                        )
                        Text(
                            text = app.packageName,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                HorizontalDivider()
            }

            item {
                Spacer(modifier = Modifier.height(12.dp))
                OutlinedButton(
                    onClick = { showRemoveConfirm = true },
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = MaterialTheme.colorScheme.error
                    ),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Default.Delete, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Remove & Revoke Relay Channel")
                }
            }
        }
    }

    if (showRemoveConfirm) {
        AlertDialog(
            onDismissRequest = { showRemoveConfirm = false },
            title = { Text("Remove Relay Channel?") },
            text = {
                Text("This will revoke '${channel.name}' on the server, notify all subscribers, and delete the local E2E encryption key.")
            },
            confirmButton = {
                Button(
                    onClick = {
                        showRemoveConfirm = false
                        onRemoveChannel()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) {
                    Text("Revoke & Remove")
                }
            },
            dismissButton = {
                TextButton(onClick = { showRemoveConfirm = false }) {
                    Text("Cancel")
                }
            }
        )
    }
}
