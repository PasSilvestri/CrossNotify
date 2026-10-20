package com.crossnotify.app.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.crossnotify.app.qr.ParsedQrPayload
import com.crossnotify.app.ui.PendingQrConfirmation

@Composable
fun QrConfirmationDialog(
    confirmation: PendingQrConfirmation,
    onConfirmRelay: (acceptInsecureHttp: Boolean) -> Unit,
    onConfirmSubscriber: (deliveryMode: String, acceptInsecureHttp: Boolean) -> Unit,
    onDismiss: () -> Unit
) {
    var acceptInsecureHttp by remember { mutableStateOf(!confirmation.requiresInsecureHttpWarning) }
    var acceptFirebaseChange by remember { mutableStateOf(!confirmation.hasFirebaseProjectConflict) }
    var selectedDeliveryMode by remember { mutableStateOf("battery_saver") }

    val canSubmit = acceptInsecureHttp && acceptFirebaseChange

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            val titleText = when (confirmation.parsed) {
                is ParsedQrPayload.RelayProvisioning -> "Activate Relay Channel?"
                is ParsedQrPayload.SubscriberPairing -> "Subscribe to Channel?"
            }
            Text(text = titleText, fontWeight = FontWeight.Bold)
        },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                when (val parsed = confirmation.parsed) {
                    is ParsedQrPayload.RelayProvisioning -> {
                        val p = parsed.payload
                        InfoLine("Channel", p.channel.name)
                        InfoLine("Server", "${p.server.name} (${p.server.url})")
                        InfoLine("Firebase Project", p.firebase.projectId)
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "This phone will generate a new 256-bit AES-GCM E2E key stored in the Android Keystore and never shared with the server.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    is ParsedQrPayload.SubscriberPairing -> {
                        val p = parsed.payload
                        InfoLine("Channel", p.channel.name)
                        if (!p.channel.relayDevice.isNullOrBlank()) {
                            InfoLine("Relay Device", p.channel.relayDevice)
                        }
                        InfoLine("Server", "${p.server.name} (${p.server.url})")
                        InfoLine("Firebase Project", p.firebase.projectId)

                        Spacer(modifier = Modifier.height(12.dp))
                        Text(
                            text = "Delivery Mode",
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.SemiBold
                        )
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            RadioButton(
                                selected = selectedDeliveryMode == "battery_saver",
                                onClick = { selectedDeliveryMode = "battery_saver" }
                            )
                            Column {
                                Text("Battery Saver (Default)", style = MaterialTheme.typography.bodyMedium)
                                Text(
                                    "Tap notification to fetch & decrypt. App is not woken in background.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            RadioButton(
                                selected = selectedDeliveryMode == "immediate_preview",
                                onClick = { selectedDeliveryMode = "immediate_preview" }
                            )
                            Column {
                                Text("Immediate Preview", style = MaterialTheme.typography.bodyMedium)
                                Text(
                                    "Decrypts immediately on arrival and shows full content & sender.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }

                if (confirmation.requiresInsecureHttpWarning) {
                    Spacer(modifier = Modifier.height(12.dp))
                    Surface(
                        color = MaterialTheme.colorScheme.errorContainer,
                        shape = MaterialTheme.shapes.small
                    ) {
                        Row(
                            modifier = Modifier.padding(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Checkbox(
                                checked = acceptInsecureHttp,
                                onCheckedChange = { acceptInsecureHttp = it }
                            )
                            Text(
                                text = "Warning: Server uses unencrypted HTTP (local network / insecure). Allow cleartext HTTP for this server.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onErrorContainer
                            )
                        }
                    }
                }

                if (confirmation.hasFirebaseProjectConflict) {
                    Spacer(modifier = Modifier.height(12.dp))
                    Surface(
                        color = MaterialTheme.colorScheme.tertiaryContainer,
                        shape = MaterialTheme.shapes.small
                    ) {
                        Row(
                            modifier = Modifier.padding(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Checkbox(
                                checked = acceptFirebaseChange,
                                onCheckedChange = { acceptFirebaseChange = it }
                            )
                            Text(
                                text = "This server URL has new Firebase project parameters. Confirm updating stored Firebase configuration.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onTertiaryContainer
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                enabled = canSubmit,
                onClick = {
                    when (confirmation.parsed) {
                        is ParsedQrPayload.RelayProvisioning -> onConfirmRelay(acceptInsecureHttp)
                        is ParsedQrPayload.SubscriberPairing -> onConfirmSubscriber(
                            selectedDeliveryMode,
                            acceptInsecureHttp
                        )
                    }
                }
            ) {
                Text("Confirm")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}

@Composable
private fun InfoLine(label: String, value: String) {
    Column(modifier = Modifier.padding(vertical = 2.dp)) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.primary
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium
        )
    }
}
