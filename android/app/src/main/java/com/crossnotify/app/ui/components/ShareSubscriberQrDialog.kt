package com.crossnotify.app.ui.components

import android.app.Activity
import android.view.WindowManager
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.crossnotify.app.ui.GeneratedSubscriberQrState
import kotlinx.coroutines.delay

/**
 * Displays the subscriber pairing QR code with enhanced security:
 * - Enables `FLAG_SECURE` on the window while visible to block screenshots and screen recorders.
 * - Enforces a 60-second countdown timer, automatically dismissing the dialog on expiration.
 */
@Composable
fun ShareSubscriberQrDialog(
    qrState: GeneratedSubscriberQrState,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
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
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = "Scan this QR code in person from the subscriber phone (Subscriptions → Add Subscription). It contains a dedicated subscriber key and the channel's E2E encryption key.",
                    style = MaterialTheme.typography.bodySmall,
                    textAlign = TextAlign.Center
                )
                Spacer(modifier = Modifier.height(16.dp))
                Image(
                    bitmap = qrState.bitmap.asImageBitmap(),
                    contentDescription = "Subscriber pairing QR code for ${qrState.channelName}",
                    modifier = Modifier.size(260.dp)
                )
                Spacer(modifier = Modifier.height(16.dp))
                LinearProgressIndicator(
                    progress = { remainingSeconds / 60f },
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "Auto-hiding in ${remainingSeconds}s (FLAG_SECURE active)",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(top = 2.dp)
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
