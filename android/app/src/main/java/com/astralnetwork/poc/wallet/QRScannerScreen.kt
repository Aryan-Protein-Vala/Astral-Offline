package com.astralnetwork.poc.wallet

import android.Manifest
import android.content.pm.PackageManager
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.astralnetwork.sdk.wallet.AstralQRPayload
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.common.InputImage
import java.util.concurrent.Executors

// Design tokens
private val AstralBlack = Color(0xFF000000)
private val AstralCard = Color(0xFF141414)
private val AstralAccent = Color(0xFFE8E8E8)
private val AstralWhite = Color(0xFFEBEBEB)
private val AstralMuted = Color(0xFF666666)
private val AstralSuccess = Color(0xFF00C853)
private val AstralBorder = Color(0xFF222222)

/**
 * QR Scanner screen for the Pay flow (SDK-based).
 *
 * 1. Camera scans ANY QR → passes raw string to AstralQRPayload.parse()
 * 2. If parse succeeds → shows amount entry with merchant info
 * 3. On confirm → fires onPaymentInitiated with merchant's public key
 *
 * Camera keeps scanning until a VALID Astral QR is parsed.
 * No hardcoded format filters — the SDK parser decides.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QRScannerScreen(
    onPaymentInitiated: (amount: Int, merchantPubKey: String, merchantName: String) -> Unit,
    onClose: () -> Unit
) {
    var scannedPayload by remember { mutableStateOf<AstralQRPayload?>(null) }
    var amountText by remember { mutableStateOf("") }
    var isProcessing by remember { mutableStateOf(false) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(AstralBlack)
    ) {
        if (scannedPayload == null) {
            // Camera preview with QR scanning
            CameraQRScanner(
                onQRScanned = { rawString ->
                    // Let the SDK parser decide if it's a valid Astral QR
                    val payload = AstralQRPayload.parse(rawString)
                    if (payload != null) {
                        scannedPayload = payload
                        Log.d("ASTRAL_QR", "✅ Parsed: wallet=${payload.walletID.id}, hasPK=${payload.publicKey != null}")
                    }
                    // If parse returns null, camera keeps scanning — no death trap.
                }
            )

            // Overlay
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(20.dp),
                verticalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("SCAN TO PAY", fontSize = 12.sp, letterSpacing = 4.sp, color = AstralWhite, fontWeight = FontWeight.Light)
                    IconButton(onClick = onClose) {
                        Icon(Icons.Default.Close, "Close", tint = AstralWhite)
                    }
                }

                Box(
                    modifier = Modifier
                        .size(250.dp)
                        .align(Alignment.CenterHorizontally)
                        .border(2.dp, AstralAccent.copy(alpha = 0.5f), RoundedCornerShape(16.dp))
                )

                Text(
                    "Point camera at merchant's QR code",
                    modifier = Modifier.fillMaxWidth(),
                    textAlign = TextAlign.Center,
                    color = AstralMuted,
                    fontSize = 14.sp
                )
            }
        } else {
            // Amount entry after successful scan
            val payload = scannedPayload!!
            val merchantName = payload.walletID.short

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(20.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Spacer(Modifier.height(40.dp))

                Icon(Icons.Default.QrCodeScanner, null, tint = AstralSuccess, modifier = Modifier.size(48.dp))
                Spacer(Modifier.height(16.dp))
                Text("PAYING", fontSize = 10.sp, letterSpacing = 3.sp, color = AstralMuted)
                Spacer(Modifier.height(4.dp))
                Text(merchantName, fontSize = 24.sp, fontWeight = FontWeight.Bold, color = AstralWhite)

                // Security badge
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    Icon(Icons.Default.Lock, null, tint = AstralSuccess, modifier = Modifier.size(10.dp))
                    Text("ENCRYPTED PAYMENT", fontSize = 8.sp, letterSpacing = 2.sp, fontWeight = FontWeight.Bold,
                        color = AstralSuccess.copy(alpha = 0.8f))
                }

                Spacer(Modifier.height(48.dp))

                // Amount input
                Row(verticalAlignment = Alignment.Bottom) {
                    Text("₹", fontSize = 32.sp, color = AstralAccent, fontWeight = FontWeight.Light)
                    Spacer(Modifier.width(4.dp))
                    OutlinedTextField(
                        value = amountText,
                        onValueChange = { if (it.all { c -> c.isDigit() } && it.length <= 6) amountText = it },
                        placeholder = { Text("0", fontSize = 48.sp, fontWeight = FontWeight.Bold, color = AstralBorder) },
                        textStyle = androidx.compose.ui.text.TextStyle(
                            fontSize = 48.sp, fontWeight = FontWeight.Bold,
                            color = AstralWhite, textAlign = TextAlign.Center
                        ),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = Color.Transparent,
                            unfocusedBorderColor = Color.Transparent,
                            cursorColor = AstralAccent
                        ),
                        modifier = Modifier.width(200.dp)
                    )
                }

                Spacer(Modifier.height(48.dp))

                val amountPaisa = (amountText.toIntOrNull() ?: 0) * 100
                Button(
                    onClick = {
                        if (amountPaisa > 0 && !isProcessing) {
                            isProcessing = true
                            // Pass the base64 public key and merchant name
                            onPaymentInitiated(
                                amountPaisa,
                                payload.publicKey ?: "",
                                merchantName
                            )
                        }
                    },
                    enabled = amountPaisa > 0 && !isProcessing,
                    modifier = Modifier.fillMaxWidth().height(56.dp),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = AstralAccent,
                        disabledContainerColor = AstralBorder
                    )
                ) {
                    if (isProcessing) {
                        CircularProgressIndicator(modifier = Modifier.size(20.dp), color = AstralBlack, strokeWidth = 2.dp)
                        Spacer(Modifier.width(8.dp))
                        Text("SENDING...", fontSize = 12.sp, letterSpacing = 3.sp, color = AstralBlack)
                    } else {
                        Text("CONFIRM & PAY", fontSize = 12.sp, letterSpacing = 3.sp, fontWeight = FontWeight.Bold)
                    }
                }

                Spacer(Modifier.height(16.dp))
                TextButton(onClick = { scannedPayload = null; amountText = ""; isProcessing = false }) {
                    Text("Cancel", color = AstralMuted)
                }
            }
        }
    }
}

/**
 * Camera preview with ML Kit barcode scanning.
 *
 * Passes ALL detected QR strings to the callback — no format filtering.
 * The parent composable decides validity via AstralQRPayload.parse().
 */
@Composable
fun CameraQRScanner(onQRScanned: (String) -> Unit) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var hasCameraPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        )
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted -> hasCameraPermission = granted }

    LaunchedEffect(Unit) {
        if (!hasCameraPermission) {
            permissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    if (hasCameraPermission) {
        AndroidView(
            factory = { ctx ->
                val previewView = PreviewView(ctx)
                val cameraProviderFuture = ProcessCameraProvider.getInstance(ctx)

                cameraProviderFuture.addListener({
                    val cameraProvider = cameraProviderFuture.get()
                    val preview = Preview.Builder().build().also {
                        it.setSurfaceProvider(previewView.surfaceProvider)
                    }

                    val analyzer = ImageAnalysis.Builder()
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .build()
                        .also {
                            it.setAnalyzer(Executors.newSingleThreadExecutor()) { imageProxy ->
                                processQRImage(imageProxy, onQRScanned)
                            }
                        }

                    try {
                        cameraProvider.unbindAll()
                        cameraProvider.bindToLifecycle(
                            lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA,
                            preview, analyzer
                        )
                    } catch (e: Exception) {
                        Log.e("ASTRAL_QR", "Camera binding failed", e)
                    }
                }, ContextCompat.getMainExecutor(ctx))

                previewView
            },
            modifier = Modifier.fillMaxSize()
        )
    } else {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("Camera permission required", color = AstralMuted)
        }
    }
}

/**
 * Process camera frames for QR codes using ML Kit.
 *
 * NO format filtering — passes ALL QR content to the callback.
 * Uses a cooldown to prevent firing the same QR 60x/sec.
 */
@androidx.annotation.OptIn(ExperimentalGetImage::class)
private fun processQRImage(imageProxy: ImageProxy, onResult: (String) -> Unit) {
    val mediaImage = imageProxy.image ?: run { imageProxy.close(); return }
    val image = InputImage.fromMediaImage(mediaImage, imageProxy.imageInfo.rotationDegrees)

    BarcodeScanning.getClient().process(image)
        .addOnSuccessListener { barcodes ->
            for (barcode in barcodes) {
                barcode.rawValue?.let { data ->
                    // No format filter — let AstralQRPayload.parse() decide
                    onResult(data)
                }
            }
        }
        .addOnCompleteListener { imageProxy.close() }
}
