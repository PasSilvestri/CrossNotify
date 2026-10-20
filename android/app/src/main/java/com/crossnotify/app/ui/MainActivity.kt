package com.crossnotify.app.ui

import android.os.Build
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.appcompat.app.AppCompatActivity
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricManager.Authenticators
import androidx.biometric.BiometricPrompt
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Hub
import androidx.compose.material.icons.filled.Inbox
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.crossnotify.app.data.db.RelayChannelEntity
import com.crossnotify.app.qr.ParsedQrPayload
import com.crossnotify.app.ui.components.QrConfirmationDialog
import com.crossnotify.app.ui.components.QrScannerDialog
import com.crossnotify.app.ui.components.ShareSubscriberQrDialog
import com.crossnotify.app.ui.screens.InboxScreen
import com.crossnotify.app.ui.screens.RelayScreen
import com.crossnotify.app.ui.screens.SettingsScreen
import com.crossnotify.app.ui.screens.SubscriptionsScreen
import com.crossnotify.app.ui.theme.CrossNotifyTheme
import dagger.hilt.android.AndroidEntryPoint

sealed class BottomNavTab(val route: String, val label: String, val icon: ImageVector) {
    data object Relay : BottomNavTab("relay", "Relay", Icons.Default.NotificationsActive)
    data object Subscriptions : BottomNavTab("subscriptions", "Subscriptions", Icons.Default.Hub)
    data object Inbox : BottomNavTab("inbox", "Inbox", Icons.Default.Inbox)
    data object Settings : BottomNavTab("settings", "Settings", Icons.Default.Settings)
}

@AndroidEntryPoint
class MainActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            CrossNotifyTheme {
                val viewModel: MainViewModel = hiltViewModel()
                MainAppScaffold(
                    viewModel = viewModel,
                    onAuthenticateForSubscriberQr = { channel ->
                        authenticateAndGenerateSubscriberQr(channel, viewModel)
                    }
                )
            }
        }
    }

    /**
     * Prompts for biometric or device credential verification before generating
     * and revealing the sensitive subscriber pairing QR code.
     */
    private fun authenticateAndGenerateSubscriberQr(
        channel: RelayChannelEntity,
        viewModel: MainViewModel
    ) {
        val biometricManager = BiometricManager.from(this)
        val authenticators = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Authenticators.BIOMETRIC_STRONG or Authenticators.DEVICE_CREDENTIAL
        } else {
            Authenticators.BIOMETRIC_WEAK or Authenticators.DEVICE_CREDENTIAL
        }

        val canAuth = biometricManager.canAuthenticate(authenticators)
        if (canAuth != BiometricManager.BIOMETRIC_SUCCESS) {
            // If no lockscreen / biometrics are enrolled on this device (e.g. emulator), proceed directly
            viewModel.generateShareSubscriberQr(channel)
            return
        }

        val executor = ContextCompat.getMainExecutor(this)
        val prompt = BiometricPrompt(
            this,
            executor,
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    super.onAuthenticationSucceeded(result)
                    viewModel.generateShareSubscriberQr(channel)
                }
            }
        )

        val promptInfoBuilder = BiometricPrompt.PromptInfo.Builder()
            .setTitle("Share '${channel.name}' with Subscriber")
            .setSubtitle("Verify your identity to reveal the E2E encryption key QR code")

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            promptInfoBuilder.setAllowedAuthenticators(
                Authenticators.BIOMETRIC_STRONG or Authenticators.DEVICE_CREDENTIAL
            )
        } else {
            @Suppress("DEPRECATION")
            promptInfoBuilder.setDeviceCredentialAllowed(true)
        }

        prompt.authenticate(promptInfoBuilder.build())
    }
}

@Composable
private fun MainAppScaffold(
    viewModel: MainViewModel,
    onAuthenticateForSubscriberQr: (RelayChannelEntity) -> Unit
) {
    val navController = rememberNavController()
    val snackbarHostState = remember { SnackbarHostState() }

    val statusMessage by viewModel.statusMessage.collectAsState()
    val pendingConfirmation by viewModel.pendingQrConfirmation.collectAsState()
    val generatedSubQr by viewModel.generatedSubQr.collectAsState()
    val isBusy by viewModel.isBusy.collectAsState()
    val inboxItems by viewModel.decryptedInbox.collectAsState()

    var activeScannerMode by remember { mutableStateOf<String?>(null) } // "cn-relay" or "cn-sub"

    val unreadTotal = remember(inboxItems) {
        inboxItems.count { it.entity.readAt == null }
    }

    LaunchedEffect(statusMessage) {
        val msg = statusMessage
        if (!msg.isNullOrBlank()) {
            snackbarHostState.showSnackbar(msg)
            viewModel.clearStatusMessage()
        }
    }

    val tabs = listOf(
        BottomNavTab.Relay,
        BottomNavTab.Subscriptions,
        BottomNavTab.Inbox,
        BottomNavTab.Settings
    )

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        bottomBar = {
            val navBackStackEntry by navController.currentBackStackEntryAsState()
            val currentRoute = navBackStackEntry?.destination?.route

            NavigationBar {
                tabs.forEach { tab ->
                    NavigationBarItem(
                        selected = currentRoute == tab.route,
                        onClick = {
                            navController.navigate(tab.route) {
                                popUpTo(navController.graph.findStartDestination().id) {
                                    saveState = true
                                }
                                launchSingleTop = true
                                restoreState = true
                            }
                        },
                        icon = {
                            if (tab == BottomNavTab.Inbox && unreadTotal > 0) {
                                BadgedBox(
                                    badge = { Badge { Text(unreadTotal.toString()) } }
                                ) {
                                    Icon(tab.icon, contentDescription = tab.label)
                                }
                            } else {
                                Icon(tab.icon, contentDescription = tab.label)
                            }
                        },
                        label = { Text(tab.label) }
                    )
                }
            }
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            NavHost(
                navController = navController,
                startDestination = BottomNavTab.Relay.route
            ) {
                composable(BottomNavTab.Relay.route) {
                    RelayScreen(
                        viewModel = viewModel,
                        onScanRelayQr = { activeScannerMode = "cn-relay" },
                        onShareSubscriberQrWithBiometric = onAuthenticateForSubscriberQr
                    )
                }
                composable(BottomNavTab.Subscriptions.route) {
                    SubscriptionsScreen(
                        viewModel = viewModel,
                        onScanSubscriberQr = { activeScannerMode = "cn-sub" }
                    )
                }
                composable(BottomNavTab.Inbox.route) {
                    InboxScreen(viewModel = viewModel)
                }
                composable(BottomNavTab.Settings.route) {
                    SettingsScreen(viewModel = viewModel)
                }
            }

            if (isBusy) {
                CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
            }
        }
    }

    activeScannerMode?.let { mode ->
        val title = if (mode == "cn-relay") "Scan Relay Provisioning QR" else "Scan Subscriber Pairing QR"
        val subtitle = if (mode == "cn-relay") {
            "Point camera at the cn-relay QR code shown on your server dashboard"
        } else {
            "Point camera at the cn-sub QR code shown on the relay phone"
        }
        QrScannerDialog(
            title = title,
            subtitle = subtitle,
            onQrDetected = { rawJson ->
                activeScannerMode = null
                viewModel.onQrCodeScanned(rawJson, expectedType = mode)
            },
            onDismiss = { activeScannerMode = null }
        )
    }

    pendingConfirmation?.let { confirmation ->
        QrConfirmationDialog(
            confirmation = confirmation,
            onConfirmRelay = { acceptHttp ->
                val relayPayload = (confirmation.parsed as ParsedQrPayload.RelayProvisioning).payload
                viewModel.confirmRelayProvisioning(relayPayload, acceptHttp)
            },
            onConfirmSubscriber = { deliveryMode, acceptHttp ->
                val subPayload = (confirmation.parsed as ParsedQrPayload.SubscriberPairing).payload
                viewModel.confirmSubscriberPairing(subPayload, deliveryMode, acceptHttp)
            },
            onDismiss = { viewModel.dismissPendingQrConfirmation() }
        )
    }

    generatedSubQr?.let { qrState ->
        ShareSubscriberQrDialog(
            qrState = qrState,
            onDismiss = { viewModel.dismissGeneratedSubQr() }
        )
    }
}
