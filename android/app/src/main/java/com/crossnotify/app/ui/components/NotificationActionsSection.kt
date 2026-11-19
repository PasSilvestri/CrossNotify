package com.crossnotify.app.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Reply
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.CheckCircleOutline
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.crossnotify.app.crypto.NotificationActionMeta
import com.crossnotify.app.util.NotificationMediaHelper

/**
 * Displays a notification's attached media image (if present) inside detail views.
 */
@Composable
fun NotificationMediaImage(
    imageBase64: String?,
    modifier: Modifier = Modifier
) {
    val bitmap = remember(imageBase64) {
        NotificationMediaHelper.decodeBase64ToBitmap(imageBase64)
    } ?: return

    Image(
        bitmap = bitmap.asImageBitmap(),
        contentDescription = "Notification image",
        contentScale = ContentScale.Fit,
        modifier = modifier
            .fillMaxWidth()
            .heightIn(max = 260.dp)
            .clip(RoundedCornerShape(10.dp))
    )
}

/**
 * Displays a concise status banner when a notification has already been dismissed,
 * opened, or actioned on the relay phone or another subscriber.
 */
@Composable
fun NotificationHandledBanner(
    handledReason: String?,
    modifier: Modifier = Modifier
) {
    Surface(
        color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.7f),
        shape = RoundedCornerShape(8.dp),
        modifier = modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Default.CheckCircleOutline,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSecondaryContainer,
                modifier = Modifier.size(18.dp)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = NotificationMediaHelper.formatHandledStatus(handledReason),
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSecondaryContainer
            )
        }
    }
}

/**
 * Renders interactive notification actions (quick replies with inline text input / smart reply
 * choices, plus background action buttons like "Mark as read") when the notification is still
 * unhandled and the subscriber has permission to execute actions.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun NotificationActionsPanel(
    actions: List<NotificationActionMeta>,
    allowActions: Boolean,
    handledAt: Long?,
    handledReason: String?,
    isExecuting: Boolean,
    feedbackMessage: String? = null,
    onExecuteAction: (actionIndex: Int, replyText: String?) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        if (handledAt != null) {
            NotificationHandledBanner(handledReason = handledReason)
            if (!feedbackMessage.isNullOrBlank()) {
                Text(
                    text = feedbackMessage,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            return
        }

        if (actions.isEmpty()) return

        if (!allowActions) {
            Surface(
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = "Actions are disabled for this subscription (read-only).",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp)
                )
            }
            return
        }

        var activeReplyAction by remember(actions) {
            mutableStateOf<NotificationActionMeta?>(null)
        }
        var replyText by remember(activeReplyAction?.idx) { mutableStateOf("") }

        Text(
            text = "Actions",
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.primary
        )

        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            actions.forEach { action ->
                FilledTonalButton(
                    enabled = !isExecuting,
                    onClick = {
                        if (action.isReply) {
                            activeReplyAction = if (activeReplyAction?.idx == action.idx) null else action
                        } else {
                            onExecuteAction(action.idx, null)
                        }
                    }
                ) {
                    if (action.isReply) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.Reply,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                    }
                    Text(action.title)
                }
            }
        }

        activeReplyAction?.let { replyAction ->
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                if (replyAction.choices.isNotEmpty()) {
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        replyAction.choices.forEach { choice ->
                            SuggestionChip(
                                onClick = {
                                    if (!isExecuting) {
                                        onExecuteAction(replyAction.idx, choice)
                                    }
                                },
                                label = { Text(choice) }
                            )
                        }
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedTextField(
                        value = replyText,
                        onValueChange = { replyText = it },
                        enabled = !isExecuting,
                        placeholder = { Text(replyAction.replyLabel ?: "Type a reply…") },
                        singleLine = false,
                        maxLines = 3,
                        modifier = Modifier.weight(1f)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    if (isExecuting) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(28.dp),
                            strokeWidth = 2.5.dp
                        )
                    } else {
                        IconButton(
                            enabled = replyText.isNotBlank(),
                            onClick = {
                                val textToSend = replyText.trim()
                                if (textToSend.isNotEmpty()) {
                                    onExecuteAction(replyAction.idx, textToSend)
                                }
                            }
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.Send,
                                contentDescription = "Send reply",
                                tint = if (replyText.isNotBlank()) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
                                }
                            )
                        }
                    }
                }
            }
        }

        if (isExecuting && activeReplyAction == null) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "Sending action to relay device…",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        if (!feedbackMessage.isNullOrBlank()) {
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = feedbackMessage,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error
            )
        }
    }
}
