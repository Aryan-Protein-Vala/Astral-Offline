package com.astralnetwork.demo

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import java.util.concurrent.Executors

/**
 * QRScannerScreen — Real camera-based QR scanner using CameraX + ML Kit.
 *
 * Handles:
 *  - Runtime camera permission request (Compose-friendly)
 *  - CameraX preview bound to the lifecycle
 *  - ML Kit Barcode scanning on every frame (ImageAnalysis use case)
 *  - Fires [onScan] exactly once when a valid QR is detected (debounced)
 */
@Composable
fun QRScannerScreen(
    onScan: (String) -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    var hasCameraPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        )
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted -> hasCameraPermission = granted }

    LaunchedEffect(Unit) {
        if (!hasCameraPermission) permissionLauncher.launch(Manifest.permission.CAMERA)
    }

    Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
        if (hasCameraPermission) {
            CameraPreviewWithScanner(onScan = onScan)
        } else {
            PermissionDeniedView(onDismiss = onDismiss)
        }

        // ── Overlay UI ──────────────────────────────────────────────────────────
        Column(
            modifier = Modifier.fillMaxSize().padding(24.dp),
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            // Top bar
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Scan QR to Pay", color = Color.White, fontSize = 18.sp,
                    fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold)
                IconButton(onClick = onDismiss) {
                    Icon(Icons.Default.Close, "Close", tint = Color.White)
                }
            }

            // Center viewfinder cutout
            Box(modifier = Modifier.size(260.dp).align(Alignment.CenterHorizontally)) {
                // Four corner brackets
                CornerBracket(Alignment.TopStart)
                CornerBracket(Alignment.TopEnd)
                CornerBracket(Alignment.BottomStart)
                CornerBracket(Alignment.BottomEnd)
            }

            // Bottom hint
            Text(
                "Point at the recipient's QR code",
                color = Color.White.copy(alpha = 0.6f),
                fontSize = 13.sp,
                modifier = Modifier.align(Alignment.CenterHorizontally)
            )
        }
    }
}

// ── Camera Preview + ML Kit Analyser ──────────────────────────────────────────

@Composable
private fun CameraPreviewWithScanner(onScan: (String) -> Unit) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var scanned by remember { mutableStateOf(false) }   // debounce — only fire once

    AndroidView(
        factory = { ctx ->
            val previewView = PreviewView(ctx)
            val executor = Executors.newSingleThreadExecutor()
            val cameraProviderFuture = ProcessCameraProvider.getInstance(ctx)

            cameraProviderFuture.addListener({
                val cameraProvider = cameraProviderFuture.get()

                val preview = Preview.Builder().build().also {
                    it.setSurfaceProvider(previewView.surfaceProvider)
                }

                val barcodeScanner = BarcodeScanning.getClient()

                val imageAnalysis = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()

                imageAnalysis.setAnalyzer(executor) { imageProxy ->
                    if (!scanned) {
                        processImageProxy(imageProxy, barcodeScanner) { result ->
                            if (result != null && !scanned) {
                                scanned = true
                                onScan(result)
                            }
                        }
                    } else {
                        imageProxy.close()
                    }
                }

                try {
                    cameraProvider.unbindAll()
                    cameraProvider.bindToLifecycle(
                        lifecycleOwner,
                        CameraSelector.DEFAULT_BACK_CAMERA,
                        preview,
                        imageAnalysis
                    )
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }, ContextCompat.getMainExecutor(ctx))

            previewView
        },
        modifier = Modifier.fillMaxSize()
    )
}

@androidx.camera.core.ExperimentalGetImage
private fun processImageProxy(
    imageProxy: ImageProxy,
    scanner: com.google.mlkit.vision.barcode.BarcodeScanner,
    onResult: (String?) -> Unit
) {
    val mediaImage = imageProxy.image ?: run { imageProxy.close(); return }
    val image = InputImage.fromMediaImage(mediaImage, imageProxy.imageInfo.rotationDegrees)

    scanner.process(image)
        .addOnSuccessListener { barcodes ->
            val qrCode = barcodes.firstOrNull { it.format == Barcode.FORMAT_QR_CODE }
            onResult(qrCode?.rawValue)
        }
        .addOnFailureListener { onResult(null) }
        .addOnCompleteListener { imageProxy.close() }
}

// ── UI Helpers ─────────────────────────────────────────────────────────────────

@Composable
private fun BoxScope.CornerBracket(alignment: Alignment) {
    val size = 28.dp
    val thickness = 3.dp
    val color = Color.White

    Box(
        modifier = Modifier
            .size(size)
            .align(alignment)
            .then(
                when (alignment) {
                    Alignment.TopStart -> Modifier.border(
                        width = thickness, color = color,
                        shape = RoundedCornerShape(topStart = 6.dp)
                    )
                    Alignment.TopEnd -> Modifier.border(
                        width = thickness, color = color,
                        shape = RoundedCornerShape(topEnd = 6.dp)
                    )
                    Alignment.BottomStart -> Modifier.border(
                        width = thickness, color = color,
                        shape = RoundedCornerShape(bottomStart = 6.dp)
                    )
                    else -> Modifier.border(
                        width = thickness, color = color,
                        shape = RoundedCornerShape(bottomEnd = 6.dp)
                    )
                }
            )
    )
}

@Composable
private fun PermissionDeniedView(onDismiss: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text("Camera permission needed to scan QR codes.",
            color = Color.White.copy(alpha = 0.7f), fontSize = 15.sp,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            modifier = Modifier.padding(32.dp))
        Button(onClick = onDismiss) { Text("Go Back") }
    }
}
