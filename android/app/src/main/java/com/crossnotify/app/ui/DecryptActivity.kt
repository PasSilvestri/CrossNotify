package com.crossnotify.app.ui

import android.content.Intent
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.crossnotify.app.crypto.NotificationPlaintext
import com.crossnotify.app.data.db.SubscriptionEntity
import com.crossnotify.app.data.repository.CrossNotifyRepository
import com.crossnotify.app.ui.theme.CrossNotifyTheme
import com.crossnotify.app.util.AppIconHelper
import dagger.hilt.android.AndroidEntryPoint
import java.text.DateFormat
import java.util.Date
import javax.inject.Inject

/**
 * Intent receiver and UI for decrypting and viewing notifications on demand.
 * Launched when the user taps a battery-saver notification or opens an inbox item.
 * Extracts `channelId` and `notificationId` from the intent, fetches/decrypts the
 * payload using the channel's local key, and renders the message content.
 */
@AndroidEntryPoint
class DecryptActivity : AppCompatActivity() {

    @Inject
    lateinit var repository: CrossNotifyRepository

    private var channelIdState by mutableStateOf<String?>(null)
    private var notificationIdState by mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        extractIntentExtras(intent)

        setContent {
            CrossNotifyTheme {
                val channelId = channelIdState
                val notificationId = notificationIdState

                var isLoading by remember(channelId, notificationId) { mutableStateOf(true) }
                var errorMessage by remember(channelId, notificationId) { mutableStateOf<String?>(null) }
                var decryptedResult by remember(channelId, notificationId) {
                    mutableStateOf<Pair<SubscriptionEntity, NotificationPlaintext>?>(null)
                }

                LaunchedEffect(channelId, notificationId) {
                    if (channelId.isNullOrBlank() || notificationId.isNullOrBlank()) {
                        isLoading = false
                        errorMessage = "Missing channelId or notificationId in notification intent."
                        return@LaunchedEffect
                    }
                    isLoading = true
                    errorMessage = null
                    val res = repository.fetchAndDecryptSingleNotification(
                        channelId = channelId,
                        notificationId = notificationId,
                        postLocalNotification = false,
                        markAsRead = true
                    )
                    res.onSuccess { pair ->
                        decryptedResult = pair
                    }.onFailure { err ->
                        errorMessage = err.message ?: "Failed to fetch and decrypt notification."
                    }
                    isLoading = false
                }

                @OptIn(ExperimentalMaterial3Api::class)
                Scaffold(
                    topBar = {
                        TopAppBar(
                            expandedHeight = 56.dp,
                            title = { Text("Decrypted Notification", fontWeight = FontWeight.Bold) }
                        )
                    }
                ) { padding ->
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(padding)
                            .padding(16.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        when {
                            isLoading -> {
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    CircularProgressIndicator()
                                    Spacer(modifier = Modifier.height(12.dp))
                                    Text("Fetching ciphertext & decrypting with E2E key…")
                                }
                            }
                            errorMessage != null -> {
                                Card(modifier = Modifier.fillMaxWidth()) {
                                    Column(
                                        modifier = Modifier.padding(20.dp),
                                        horizontalAlignment = Alignment.CenterHorizontally
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.ErrorOutline,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.error,
                                            modifier = Modifier.size(44.dp)
                                        )
                                        Spacer(modifier = Modifier.height(12.dp))
                                        Text(
                                            text = "Decryption / Fetch Failed",
                                            style = MaterialTheme.typography.titleMedium,
                                            fontWeight = FontWeight.Bold
                                        )
                                        Spacer(modifier = Modifier.height(6.dp))
                                        Text(
                                            text = errorMessage ?: "",
                                            style = MaterialTheme.typography.bodyMedium,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                        Spacer(modifier = Modifier.height(16.dp))
                                        Button(onClick = { finish() }) {
                                            Text("Close")
                                        }
                                    }
                                }
                            }
                            decryptedResult != null -> {
                                val (sub, pt) = decryptedResult!!
                                Card(modifier = Modifier.fillMaxWidth()) {
                                    Column(
                                        modifier = Modifier
                                            .padding(20.dp)
                                            .verticalScroll(rememberScrollState()),
                                        verticalArrangement = Arrangement.spacedBy(10.dp)
                                    ) {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            val iconBitmap = remember(pt.icon) {
                                                AppIconHelper.decodeBase64ToBitmap(pt.icon)
                                            }
                                            if (iconBitmap != null) {
                                                Image(
                                                    bitmap = iconBitmap.asImageBitmap(),
                                                    contentDescription = null,
                                                    modifier = Modifier
                                                        .size(24.dp)
                                                        .clip(RoundedCornerShape(4.dp))
                                                )
                                                Spacer(modifier = Modifier.width(8.dp))
                                            } else {
                                                Icon(
                                                    imageVector = Icons.Default.LockOpen,
                                                    contentDescription = null,
                                                    tint = MaterialTheme.colorScheme.primary
                                                )
                                                Spacer(modifier = Modifier.width(8.dp))
                                            }
                                            Surface(
                                                color = MaterialTheme.colorScheme.primaryContainer,
                                                shape = MaterialTheme.shapes.small
                                            ) {
                                                Text(
                                                    text = "${sub.name} • ${pt.app.ifBlank { pt.pkg }}",
                                                    style = MaterialTheme.typography.labelMedium,
                                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                                                )
                                            }
                                        }

                                        Text(
                                            text = pt.title.ifBlank { "(No title)" },
                                            style = MaterialTheme.typography.headlineSmall,
                                            fontWeight = FontWeight.Bold
                                        )

                                        if (pt.subText.isNotBlank()) {
                                            Text(
                                                text = pt.subText,
                                                style = MaterialTheme.typography.labelLarge,
                                                color = MaterialTheme.colorScheme.primary
                                            )
                                        }

                                        val body = pt.bigText.ifBlank { pt.text }
                                        Text(
                                            text = body,
                                            style = MaterialTheme.typography.bodyLarge
                                        )

                                        val formattedTime = DateFormat.getDateTimeInstance(
                                            DateFormat.MEDIUM,
                                            DateFormat.MEDIUM
                                        ).format(Date(pt.postedAt))

                                        Text(
                                            text = "Package: ${pt.pkg}\nPosted at: $formattedTime\nNotification ID: ${pt.id}",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )

                                        Spacer(modifier = Modifier.height(8.dp))
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.End
                                        ) {
                                            OutlinedButton(
                                                onClick = {
                                                    startActivity(
                                                        Intent(this@DecryptActivity, MainActivity::class.java).apply {
                                                            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
                                                        }
                                                    )
                                                    finish()
                                                }
                                            ) {
                                                Text("Open App")
                                            }
                                            Spacer(modifier = Modifier.width(8.dp))
                                            Button(onClick = { finish() }) {
                                                Text("Done")
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
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        extractIntentExtras(intent)
    }

    private fun extractIntentExtras(intent: Intent?) {
        val extras = intent?.extras ?: return
        channelIdState = extras.getString("channelId")
        notificationIdState = extras.getString("notificationId")
    }
}
