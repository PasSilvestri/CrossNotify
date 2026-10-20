package com.crossnotify.app.ui.components

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BatteryChargingFull
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner

data class PermissionHealthState(
    val notificationListenerGranted: Boolean,
    val postNotificationsGranted: Boolean,
    val cameraGranted: Boolean,
    val batteryOptimizationIgnored: Boolean
)

fun queryPermissionHealth(context: Context): PermissionHealthState {
    val listenerPackages = NotificationManagerCompat.getEnabledListenerPackages(context)
    val notificationListenerGranted = listenerPackages.contains(context.packageName)

    val postNotificationsGranted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.POST_NOTIFICATIONS
        ) == PackageManager.PERMISSION_GRANTED
    } else {
        NotificationManagerCompat.from(context).areNotificationsEnabled()
    }

    val cameraGranted = ContextCompat.checkSelfPermission(
        context,
        Manifest.permission.CAMERA
    ) == PackageManager.PERMISSION_GRANTED

    val powerManager = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
    val batteryOptimizationIgnored = powerManager?.isIgnoringBatteryOptimizations(context.packageName) == true

    return PermissionHealthState(
        notificationListenerGranted = notificationListenerGranted,
        postNotificationsGranted = postNotificationsGranted,
        cameraGranted = cameraGranted,
        batteryOptimizationIgnored = batteryOptimizationIgnored
    )
}

@Composable
fun PermissionsHealthSection(
    hasRelayChannels: Boolean,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var refreshTrigger by remember { mutableIntStateOf(0) }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                refreshTrigger++
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val health = remember(refreshTrigger, hasRelayChannels) {
        queryPermissionHealth(context)
    }

    val postNotificationLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { refreshTrigger++ }

    val cameraPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { refreshTrigger++ }

    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)
        )
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = "Permissions & Background Health",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "Status of system permissions required for relay capture, QR scanning, and push delivery.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(12.dp))

            // 1. Notification Access (Relay)
            PermissionRow(
                icon = Icons.Default.NotificationsActive,
                title = if (hasRelayChannels) "Notification Access (Relay)" else "Notification Access (Relay only)",
                description = "Required on Relay phones to intercept and encrypt incoming notifications.",
                granted = health.notificationListenerGranted,
                required = hasRelayChannels,
                actionLabel = "Grant",
                onAction = {
                    val intent = Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    runCatching { context.startActivity(intent) }
                }
            )

            Spacer(modifier = Modifier.height(8.dp))

            // 2. POST_NOTIFICATIONS (Android 13+)
            PermissionRow(
                icon = Icons.Default.Notifications,
                title = "Post Notifications",
                description = "Required on Subscriber phones to display decrypted alerts.",
                granted = health.postNotificationsGranted,
                required = true,
                actionLabel = "Enable",
                onAction = {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        postNotificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                    } else {
                        val intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
                            putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                        }
                        runCatching { context.startActivity(intent) }
                    }
                }
            )

            Spacer(modifier = Modifier.height(8.dp))

            // 3. Camera Permission
            PermissionRow(
                icon = Icons.Default.CameraAlt,
                title = "Camera (QR Scanner)",
                description = "Used to scan cn-relay and cn-sub pairing QR codes in person.",
                granted = health.cameraGranted,
                required = true,
                actionLabel = "Allow",
                onAction = {
                    cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
                }
            )

            Spacer(modifier = Modifier.height(8.dp))

            // 4. Battery Optimization Exemption (Relay)
            PermissionRow(
                icon = Icons.Default.BatteryChargingFull,
                title = "Unrestricted Battery (Relay)",
                description = "Prevents OEM background killers from delaying relay outbox delivery (see dontkillmyapp.com).",
                granted = health.batteryOptimizationIgnored,
                required = hasRelayChannels,
                actionLabel = "Exempt",
                onAction = {
                    val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                        data = Uri.parse("package:${context.packageName}")
                    }
                    runCatching { context.startActivity(intent) }.onFailure {
                        val fallback = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                        runCatching { context.startActivity(fallback) }
                    }
                }
            )
        }
    }
}

@Composable
private fun PermissionRow(
    icon: ImageVector,
    title: String,
    description: String,
    granted: Boolean,
    required: Boolean,
    actionLabel: String,
    onAction: () -> Unit
) {
    val statusColor = when {
        granted -> Color(0xFF16A34A)
        required -> Color(0xFFDC2626)
        else -> Color(0xFFD97706)
    }

    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Row(
            modifier = Modifier.weight(1f),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(22.dp)
            )
            Spacer(modifier = Modifier.width(10.dp))
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Icon(
                        imageVector = if (granted) Icons.Default.CheckCircle else Icons.Default.Error,
                        contentDescription = if (granted) "Granted" else "Missing",
                        tint = statusColor,
                        modifier = Modifier.size(16.dp)
                    )
                }
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        if (!granted) {
            Spacer(modifier = Modifier.width(8.dp))
            if (required) {
                Button(onClick = onAction) {
                    Text(actionLabel)
                }
            } else {
                OutlinedButton(onClick = onAction) {
                    Text(actionLabel)
                }
            }
        }
    }
}
