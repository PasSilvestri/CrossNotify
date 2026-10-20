package com.crossnotify.app.ui.components

import android.app.Activity
import android.content.Intent
import android.view.WindowManager
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.crossnotify.app.ui.GeneratedSubscriberQrState
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Displays the subscriber pairing QR code with enhanced security:
 * - Enables `FLAG_SECURE` on the window while visible to block screenshots and screen recorders.
 * - Enforces a 60-second countdown timer, automatically dismissing the dialog on expiration.
 * - Provides convenient "Copy JSON" and "Send / Share" actions for manual entry on subscriber devices/emulators.
 */
@Composable
fun ShareSubscriberQrDialog(
    qrState: GeneratedSubscriberQrState,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val clipboardManager = LocalClipboardManager.current
    val scope = rememberCoroutineScope()
    var isCopied by remember { mutableStateOf(false) }
    var showRawJson by remember { mutableStateOf(false) }
    var remainingSeconds by remember(qrState.createdAtMs) { mutableIntStateOf(60) }

    // Enforce FLAG_SECURE while the E2E pairing QR code is visible on screen
    DisposableEffect(Unit) {
        val activity = context as? Activity
        activity?.window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        onDispose {
            activity?.window?.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        }
    }

    LaunchedEffect(qrState.createdAtMs) {
        while (remainingSeconds > 0) {
            delay(1000L)
            remainingSeconds--
        }
        onDismiss()
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = "Subscribe to ${qrState.channelName}",
                fontWeight = FontWeight.Bold
            )
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = "Scan this QR code in person from the subscriber phone (Subscriptions → Add Subscription). It contains a dedicated subscriber key and the channel's E2E encryption key.",
                    style = MaterialTheme.typography.bodySmall,
                    textAlign = TextAlign.Center
                )
                Spacer(modifier = Modifier.height(12.dp))
                Image(
                    bitmap = qrState.bitmap.asImageBitmap(),
                    contentDescription = "Subscriber pairing QR code for ${qrState.channelName}",
                    modifier = Modifier.size(240.dp)
                )

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 10.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedButton(
                        onClick = {
                            clipboardManager.setText(AnnotatedString(qrState.payloadJson))
                            isCopied = true
                            scope.launch {
                                delay(2000L)
                                isCopied = false
                            }
                        },
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Icon(
                            imageVector = if (isCopied) Icons.Default.Check else Icons.Default.ContentCopy,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(if (isCopied) "Copied!" else "Copy JSON")
                    }

                    OutlinedButton(
                        onClick = {
                            val sendIntent = Intent(Intent.ACTION_SEND).apply {
                                type = "text/plain"
                                putExtra(Intent.EXTRA_TEXT, qrState.payloadJson)
                                putExtra(Intent.EXTRA_SUBJECT, "CrossNotify Subscriber QR - ${qrState.channelName}")
                            }
                            context.startActivity(Intent.createChooser(sendIntent, "Send QR Payload"))
                        },
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Share,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Send / Share")
                    }
                }

                TextButton(
                    onClick = { showRawJson = !showRawJson },
                    modifier = Modifier.padding(top = 2.dp)
                ) {
                    Text(
                        text = if (showRawJson) "Hide JSON payload" else "View JSON payload",
                        style = MaterialTheme.typography.bodySmall
                    )
                }

                if (showRawJson) {
                    OutlinedTextField(
                        value = qrState.payloadJson,
                        onValueChange = {},
                        readOnly = true,
                        label = { Text("Subscriber Payload JSON") },
                        textStyle = MaterialTheme.typography.bodySmall.copy(
                            fontFamily = FontFamily.Monospace,
                            fontSize = 11.sp
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(130.dp)
                            .padding(top = 4.dp)
                    )
                }

                Spacer(modifier = Modifier.height(12.dp))
                LinearProgressIndicator(
                    progress = { remainingSeconds / 60f },
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = "Auto-hiding in ${remainingSeconds}s (FLAG_SECURE active)",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary
                )
            }
        },
        confirmButton = {
            Button(onClick = onDismiss) {
                Text("Done")
            }
        }
    )
}
