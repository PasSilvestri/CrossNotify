package com.crossnotify.app.ui.screens

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DoneAll
import androidx.compose.material.icons.filled.Inbox
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
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
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.crossnotify.app.data.repository.DecryptedInboxItem
import com.crossnotify.app.ui.MainViewModel
import com.crossnotify.app.ui.theme.parseHexColor
import com.crossnotify.app.util.AppIconHelper
import java.text.DateFormat
import java.util.Date

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InboxScreen(viewModel: MainViewModel) {
    val inboxItems by viewModel.decryptedInbox.collectAsState()
    val subscriptions by viewModel.subscriptions.collectAsState()

    var selectedChannelFilter by remember { mutableStateOf<String?>(null) }
    var selectedItem by remember { mutableStateOf<DecryptedInboxItem?>(null) }

    val filteredItems = remember(inboxItems, selectedChannelFilter) {
        if (selectedChannelFilter == null) {
            inboxItems
        } else {
            inboxItems.filter { it.entity.channelId == selectedChannelFilter }
        }
    }

    val groupedByChannel = remember(filteredItems) {
        filteredItems.groupBy { it.entity.channelId }
    }

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            TopAppBar(
                expandedHeight = 56.dp,
                windowInsets = WindowInsets(0, 0, 0, 0),
                title = {
                    Column {
                        Text("Decrypted Inbox", fontWeight = FontWeight.Bold)
                        Text(
                            text = "Ciphertext stored at rest • Decrypted in memory",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                actions = {
                    IconButton(onClick = { viewModel.syncCatchUpNow() }) {
                        Icon(Icons.Default.Refresh, contentDescription = "Sync catch-up")
                    }
                    if (selectedChannelFilter != null) {
                        IconButton(
                            onClick = {
                                selectedChannelFilter?.let { viewModel.markChannelRead(it) }
                            }
                        ) {
                            Icon(Icons.Default.DoneAll, contentDescription = "Mark channel read")
                        }
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            if (subscriptions.isNotEmpty()) {
                LazyRow(
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    item {
                        FilterChip(
                            selected = selectedChannelFilter == null,
                            onClick = { selectedChannelFilter = null },
                            label = { Text("All (${inboxItems.size})") }
                        )
                    }
                    items(subscriptions, key = { it.channelId }) { sub ->
                        val count = inboxItems.count { it.entity.channelId == sub.channelId }
                        FilterChip(
                            selected = selectedChannelFilter == sub.channelId,
                            onClick = { selectedChannelFilter = sub.channelId },
                            label = { Text("${sub.name} ($count)") }
                        )
                    }
                }
            }

            if (filteredItems.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(24.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            imageVector = Icons.Default.Inbox,
                            contentDescription = null,
                            modifier = Modifier.size(48.dp),
                            tint = MaterialTheme.colorScheme.primary
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(
                            text = "Inbox is Empty",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "Encrypted notifications received from your subscriptions will appear here.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        Button(onClick = { viewModel.syncCatchUpNow() }) {
                            Icon(Icons.Default.Refresh, contentDescription = null)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Run Catch-Up Sync")
                        }
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    groupedByChannel.forEach { (channelId, itemsForChannel) ->
                        val sub = subscriptions.firstOrNull { it.channelId == channelId }
                        item(key = "header_$channelId") {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Box(
                                        modifier = Modifier
                                            .size(12.dp)
                                            .clip(CircleShape)
                                            .background(parseHexColor(sub?.color))
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(
                                        text = sub?.name ?: "Subscription",
                                        style = MaterialTheme.typography.titleSmall,
                                        fontWeight = FontWeight.Bold
                                    )
                                    if (!sub?.relayDevice.isNullOrBlank()) {
                                        Text(
                                            text = " • ${sub?.relayDevice}",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                            }
                        }

                        items(itemsForChannel, key = { it.entity.notificationId }) { item ->
                            val pt = item.plaintext
                            val isUnread = item.entity.readAt == null

                            Card(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        viewModel.markInboxItemRead(item.entity.notificationId)
                                        selectedItem = item
                                    },
                                colors = CardDefaults.cardColors(
                                    containerColor = if (isUnread) {
                                        MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f)
                                    } else {
                                        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)
                                    }
                                )
                            ) {
                                Column(modifier = Modifier.padding(14.dp)) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            modifier = Modifier.weight(1f, fill = false)
                                        ) {
                                            val iconBitmap = remember(pt?.icon) {
                                                AppIconHelper.decodeBase64ToBitmap(pt?.icon)
                                            }
                                            if (iconBitmap != null) {
                                                Image(
                                                    bitmap = iconBitmap.asImageBitmap(),
                                                    contentDescription = null,
                                                    modifier = Modifier
                                                        .size(20.dp)
                                                        .clip(RoundedCornerShape(4.dp))
                                                )
                                                Spacer(modifier = Modifier.width(6.dp))
                                            }
                                            Text(
                                                text = pt?.app?.ifBlank { item.entity.appName ?: "App" }
                                                    ?: item.entity.appName ?: "Encrypted Payload",
                                                style = MaterialTheme.typography.labelMedium,
                                                color = MaterialTheme.colorScheme.primary,
                                                fontWeight = FontWeight.SemiBold,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                        }
                                        Text(
                                            text = formatTimestamp(item.entity.relayTimestamp),
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                    Spacer(modifier = Modifier.height(4.dp))
                                    if (pt != null) {
                                        Text(
                                            text = pt.title.ifBlank { "(No title)" },
                                            style = MaterialTheme.typography.bodyLarge,
                                            fontWeight = if (isUnread) FontWeight.Bold else FontWeight.Medium
                                        )
                                        val bodyPreview = pt.text.ifBlank { pt.bigText }
                                        if (bodyPreview.isNotBlank()) {
                                            Text(
                                                text = bodyPreview,
                                                style = MaterialTheme.typography.bodyMedium,
                                                maxLines = 2,
                                                overflow = TextOverflow.Ellipsis,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
                                    } else {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Icon(
                                                imageVector = Icons.Default.Lock,
                                                contentDescription = null,
                                                modifier = Modifier.size(16.dp),
                                                tint = MaterialTheme.colorScheme.error
                                            )
                                            Spacer(modifier = Modifier.width(6.dp))
                                            Text(
                                                text = "Cannot decrypt (key wiped or revoked)",
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.error
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
    }

    selectedItem?.let { item ->
        val pt = item.plaintext
        AlertDialog(
            onDismissRequest = { selectedItem = null },
            title = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    val iconBitmap = remember(pt?.icon) {
                        AppIconHelper.decodeBase64ToBitmap(pt?.icon)
                    }
                    if (iconBitmap != null) {
                        Image(
                            bitmap = iconBitmap.asImageBitmap(),
                            contentDescription = null,
                            modifier = Modifier
                                .size(28.dp)
                                .clip(RoundedCornerShape(6.dp))
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                    }
                    Text(
                        text = pt?.title?.ifBlank { pt.app } ?: "Decrypted Notification",
                        fontWeight = FontWeight.Bold
                    )
                }
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (pt != null) {
                        Surface(
                            color = MaterialTheme.colorScheme.secondaryContainer,
                            shape = MaterialTheme.shapes.small
                        ) {
                            Text(
                                text = "${item.subscription?.name ?: "Channel"} • ${pt.app} (${pt.pkg})",
                                style = MaterialTheme.typography.labelSmall,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                            )
                        }
                        if (pt.subText.isNotBlank()) {
                            Text(
                                text = pt.subText,
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                        val fullBody = pt.bigText.ifBlank { pt.text }
                        Text(
                            text = fullBody,
                            style = MaterialTheme.typography.bodyMedium
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = "Posted: ${formatTimestamp(pt.postedAt)}\n" +
                                "Received: ${formatTimestamp(item.entity.receivedAt)}\n" +
                                "Origin ID: ${pt.origin.id}\n" +
                                "Daisy-Chain Hops: ${pt.origin.hops} (Path: ${pt.origin.path.joinToString(" → ")})",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    } else {
                        Text("This notification's E2E key is no longer available.")
                    }
                }
            },
            confirmButton = {
                Button(onClick = { selectedItem = null }) {
                    Text("Close")
                }
            }
        )
    }
}

private fun formatTimestamp(epochMs: Long): String {
    if (epochMs <= 0L) return ""
    return DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(epochMs))
}
