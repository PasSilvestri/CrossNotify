package com.crossnotify.app.ui.components

import android.Manifest
import android.content.pm.PackageManager
import android.util.Size
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.OptIn
import androidx.camera.core.CameraSelector
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * CameraX + Google ML Kit Barcode Scanning dialog for `cn-relay` and `cn-sub` QR codes.
 * Also includes a manual JSON paste dialog so developers/testers on emulators can input payloads.
 */
@OptIn(ExperimentalGetImage::class)
@Composable
fun QrScannerDialog(
    title: String,
    subtitle: String,
    onQrDetected: (String) -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    var hasCameraPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED
        )
    }
    var showManualPasteDialog by remember { mutableStateOf(false) }
    var manualJsonInput by remember { mutableStateOf("") }
    val scanDispatched = remember { AtomicBoolean(false) }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        hasCameraPermission = granted
    }

    LaunchedEffect(Unit) {
        if (!hasCameraPermission) {
            permissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = Color.Black
        ) {
            Box(modifier = Modifier.fillMaxSize()) {
                if (hasCameraPermission) {
                    val cameraExecutor = remember { Executors.newSingleThreadExecutor() }
                    val barcodeScanner = remember {
                        val options = BarcodeScannerOptions.Builder()
                            .setBarcodeFormats(Barcode.FORMAT_QR_CODE)
                            .build()
                        BarcodeScanning.getClient(options)
                    }

                    DisposableEffect(Unit) {
                        onDispose {
                            runCatching { barcodeScanner.close() }
                            runCatching { cameraExecutor.shutdown() }
                        }
                    }

                    AndroidView(
                        modifier = Modifier.fillMaxSize(),
                        factory = { ctx ->
                            val previewView = PreviewView(ctx)
                            val cameraProviderFuture = ProcessCameraProvider.getInstance(ctx)
                            cameraProviderFuture.addListener({
                                val cameraProvider = runCatching { cameraProviderFuture.get() }.getOrNull()
                                    ?: return@addListener

                                val preview = Preview.Builder().build().also {
                                    it.surfaceProvider = previewView.surfaceProvider
                                }

                                val imageAnalysis = ImageAnalysis.Builder()
                                    .setTargetResolution(Size(1280, 720))
                                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                                    .build()

                                imageAnalysis.setAnalyzer(cameraExecutor) { imageProxy ->
                                    val mediaImage = imageProxy.image
                                    if (mediaImage != null && !scanDispatched.get()) {
                                        val inputImage = InputImage.fromMediaImage(
                                            mediaImage,
                                            imageProxy.imageInfo.rotationDegrees
                                        )
                                        barcodeScanner.process(inputImage)
                                            .addOnSuccessListener { barcodes ->
                                                for (barcode in barcodes) {
                                                    val raw = barcode.rawValue
                                                    if (!raw.isNullOrBlank() && raw.trim().startsWith("{")) {
                                                        if (scanDispatched.compareAndSet(false, true)) {
                                                            onQrDetected(raw)
                                                            break
                                                        }
                                                    }
                                                }
                                            }
                                            .addOnCompleteListener {
                                                imageProxy.close()
                                            }
                                    } else {
                                        imageProxy.close()
                                    }
                                }

                                runCatching {
                                    cameraProvider.unbindAll()
                                    cameraProvider.bindToLifecycle(
                                        lifecycleOwner,
                                        CameraSelector.DEFAULT_BACK_CAMERA,
                                        preview,
                                        imageAnalysis
                                    )
                                }
                            }, ContextCompat.getMainExecutor(ctx))
                            previewView
                        }
                    )
                } else {
                    Column(
                        modifier = Modifier
                            .align(Alignment.Center)
                            .padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            text = "Camera permission is required to scan QR codes.",
                            color = Color.White,
                            style = MaterialTheme.typography.bodyLarge
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        Button(onClick = { permissionLauncher.launch(Manifest.permission.CAMERA) }) {
                            Text("Grant Camera Permission")
                        }
                    }
                }

                // Top Overlay
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .align(Alignment.TopCenter)
                        .background(Color.Black.copy(alpha = 0.65f))
                        .padding(16.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = title,
                                color = Color.White,
                                style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = subtitle,
                                color = Color.White.copy(alpha = 0.8f),
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                        IconButton(onClick = onDismiss) {
                            Icon(
                                imageVector = Icons.Default.Close,
                                contentDescription = "Close scanner",
                                tint = Color.White
                            )
                        }
                    }
                }

                // Bottom Overlay with Manual Paste fallback
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .align(Alignment.BottomCenter)
                        .background(Color.Black.copy(alpha = 0.65f))
                        .padding(16.dp),
                    contentAlignment = Alignment.Center
                ) {
                    OutlinedButton(
                        onClick = { showManualPasteDialog = true },
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.ContentPaste,
                            contentDescription = null,
                            tint = Color.White
                        )
                        Text(
                            text = "  Enter QR JSON Manually",
                            color = Color.White
                        )
                    }
                }
            }
        }
    }

    if (showManualPasteDialog) {
        AlertDialog(
            onDismissRequest = { showManualPasteDialog = false },
            title = { Text("Paste QR Payload JSON") },
            text = {
                OutlinedTextField(
                    value = manualJsonInput,
                    onValueChange = { manualJsonInput = it },
                    label = { Text("UTF-8 JSON payload") },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(180.dp)
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        val trimmed = manualJsonInput.trim()
                        if (trimmed.isNotEmpty()) {
                            showManualPasteDialog = false
                            onQrDetected(trimmed)
                        }
                    }
                ) {
                    Text("Continue")
                }
            },
            dismissButton = {
                TextButton(onClick = { showManualPasteDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }
}
