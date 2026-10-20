package com.crossnotify.app.ui.components

import android.Manifest
import android.content.pm.PackageManager
import android.view.OrientationEventListener
import android.view.Surface
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
import androidx.compose.material.icons.filled.Cameraswitch
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
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
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
 * Supports screen rotation, live camera orientation matching, and toggling between front/back camera.
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

    var hasCameraPermission by rememberSaveable {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED
        )
    }
    var showManualPasteDialog by rememberSaveable { mutableStateOf(false) }
    var manualJsonInput by rememberSaveable { mutableStateOf("") }
    val scanDispatched = remember { AtomicBoolean(false) }

    var lensFacing by rememberSaveable { mutableIntStateOf(CameraSelector.LENS_FACING_BACK) }
    var canSwitchCamera by remember { mutableStateOf(false) }

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

                    var cameraProvider by remember { mutableStateOf<ProcessCameraProvider?>(null) }
                    var previewViewRef by remember { mutableStateOf<PreviewView?>(null) }
                    var previewUseCase by remember { mutableStateOf<Preview?>(null) }
                    var analysisUseCase by remember { mutableStateOf<ImageAnalysis?>(null) }

                    // OrientationEventListener dynamically updates targetRotation for Preview & Analysis
                    DisposableEffect(context) {
                        val orientationEventListener = object : OrientationEventListener(context) {
                            override fun onOrientationChanged(orientation: Int) {
                                if (orientation == ORIENTATION_UNKNOWN) return
                                val rotation = when (orientation) {
                                    in 45..134 -> Surface.ROTATION_270
                                    in 135..224 -> Surface.ROTATION_180
                                    in 225..314 -> Surface.ROTATION_90
                                    else -> Surface.ROTATION_0
                                }
                                previewUseCase?.targetRotation = rotation
                                analysisUseCase?.targetRotation = rotation
                            }
                        }
                        if (orientationEventListener.canDetectOrientation()) {
                            orientationEventListener.enable()
                        }
                        onDispose {
                            orientationEventListener.disable()
                            runCatching { barcodeScanner.close() }
                            runCatching { cameraExecutor.shutdown() }
                        }
                    }

                    // (Re)bind camera when provider, previewView, or lens facing changes
                    LaunchedEffect(cameraProvider, previewViewRef, lensFacing) {
                        val provider = cameraProvider ?: return@LaunchedEffect
                        val previewView = previewViewRef ?: return@LaunchedEffect

                        val displayRotation = previewView.display?.rotation ?: Surface.ROTATION_0

                        val preview = Preview.Builder()
                            .setTargetRotation(displayRotation)
                            .build()
                            .also {
                                it.surfaceProvider = previewView.surfaceProvider
                            }

                        val imageAnalysis = ImageAnalysis.Builder()
                            .setTargetRotation(displayRotation)
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

                        previewUseCase = preview
                        analysisUseCase = imageAnalysis

                        val selector = CameraSelector.Builder()
                            .requireLensFacing(lensFacing)
                            .build()

                        runCatching {
                            provider.unbindAll()
                            provider.bindToLifecycle(
                                lifecycleOwner,
                                selector,
                                preview,
                                imageAnalysis
                            )
                        }
                    }

                    AndroidView(
                        modifier = Modifier.fillMaxSize(),
                        factory = { ctx ->
                            PreviewView(ctx).apply {
                                scaleType = PreviewView.ScaleType.FILL_CENTER
                                addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
                                    val rot = display?.rotation ?: Surface.ROTATION_0
                                    previewUseCase?.targetRotation = rot
                                    analysisUseCase?.targetRotation = rot
                                }
                                val future = ProcessCameraProvider.getInstance(ctx)
                                future.addListener({
                                    val provider = runCatching { future.get() }.getOrNull() ?: return@addListener
                                    val hasBack = provider.hasCamera(CameraSelector.DEFAULT_BACK_CAMERA)
                                    val hasFront = provider.hasCamera(CameraSelector.DEFAULT_FRONT_CAMERA)
                                    canSwitchCamera = hasBack && hasFront
                                    if (!hasBack && hasFront && lensFacing == CameraSelector.LENS_FACING_BACK) {
                                        lensFacing = CameraSelector.LENS_FACING_FRONT
                                    }
                                    cameraProvider = provider
                                }, ContextCompat.getMainExecutor(ctx))
                                previewViewRef = this
                            }
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

                // Top Overlay with title, camera flip button, and close button
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
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            if (canSwitchCamera) {
                                IconButton(
                                    onClick = {
                                        lensFacing = if (lensFacing == CameraSelector.LENS_FACING_BACK) {
                                            CameraSelector.LENS_FACING_FRONT
                                        } else {
                                            CameraSelector.LENS_FACING_BACK
                                        }
                                    }
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Cameraswitch,
                                        contentDescription = "Rotate/Switch Camera",
                                        tint = Color.White
                                    )
                                }
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
