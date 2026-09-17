package com.astralnetwork.poc.wallet

import android.bluetooth.BluetoothManager
import android.content.Context
import android.os.VibrationEffect
import android.os.Vibrator
import android.util.Base64
import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.Canvas
import com.astralnetwork.sdk.core.AstralSDK
import com.astralnetwork.sdk.identity.WalletID
import com.astralnetwork.sdk.wallet.AstralQRPayload
import java.text.SimpleDateFormat
import java.util.*

// ── Design Tokens (Monochrome — matches astralnetwork.in) ──────────────

private val AstralBlack = Color(0xFF000000)
private val AstralDark = Color(0xFF0D0D0D)
private val AstralCard = Color(0xFF141414)
private val AstralBorder = Color(0xFF222222)
private val AstralAccent = Color(0xFFE8E8E8)
private val AstralGold = Color(0xFFE8E8E8)
private val AstralWhite = Color(0xFFEBEBEB)
private val AstralMuted = Color(0xFF666666)
private val AstralSuccess = Color(0xFF00C853)
private val AstralError = Color(0xFFFF5252)
private val AstralWarning = Color(0xFFFF9800)

// ── Diamond Logo (matches astralnetwork.in) ──────────────────

@Composable
fun DiamondLogo(size: Float = 28f, color: Color = AstralWhite) {
    Canvas(modifier = Modifier.size(size.dp * 1.5f)) {
        val cx = this.size.width / 2f
        val cy = this.size.height / 2f
        val outerHalf = size / 2f * density
        val midHalf = size * 0.625f / 2f * density
        val innerHalf = size * 0.25f / 2f * density

        rotate(45f, pivot = androidx.compose.ui.geometry.Offset(cx, cy)) {
            drawRect(color = color.copy(alpha = 0.3f),
                topLeft = androidx.compose.ui.geometry.Offset(cx - outerHalf, cy - outerHalf),
                size = androidx.compose.ui.geometry.Size(outerHalf * 2, outerHalf * 2),
                style = Stroke(width = 1f * density))
        }
        rotate(45f, pivot = androidx.compose.ui.geometry.Offset(cx, cy)) {
            drawRect(color = color.copy(alpha = 0.5f),
                topLeft = androidx.compose.ui.geometry.Offset(cx - midHalf, cy - midHalf),
                size = androidx.compose.ui.geometry.Size(midHalf * 2, midHalf * 2),
                style = Stroke(width = 1f * density))
        }
        rotate(45f, pivot = androidx.compose.ui.geometry.Offset(cx, cy)) {
            drawRect(color = color.copy(alpha = 0.8f),
                topLeft = androidx.compose.ui.geometry.Offset(cx - innerHalf, cy - innerHalf),
                size = androidx.compose.ui.geometry.Size(innerHalf * 2, innerHalf * 2))
        }
    }
}

// ── Data ─────────────────────────────────────────────────────

data class TransactionItem(
    val id: String,
    val amount: Int,
    val type: String,
    val timestamp: Long,
    val status: String,
    val counterparty: String = ""
)

// ── Bluetooth State Check ────────────────────────────────────

@Composable
fun rememberBluetoothState(): Boolean {
    val context = LocalContext.current
    val btManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
    val adapter = btManager?.adapter

    var isEnabled by remember { mutableStateOf(adapter?.isEnabled == true) }

    LaunchedEffect(Unit) {
        while (true) {
            isEnabled = adapter?.isEnabled == true
            kotlinx.coroutines.delay(2000)
        }
    }

    return isEnabled
}

// ── Main App (SDK-based) ─────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AstralWalletApp(
    sdk: AstralSDK
) {
    val context = LocalContext.current
    var selectedTab by remember { mutableStateOf(0) }
    var showScanner by remember { mutableStateOf(false) }
    val btEnabled = rememberBluetoothState()

    // Wallet state
    var balance by remember { mutableStateOf(20000) }
    var transactions by remember { mutableStateOf(listOf<TransactionItem>()) }
    var receivedTotal by remember { mutableStateOf(0) }

    // Wire up SDK payment received callback
    LaunchedEffect(sdk) {
        sdk.onPaymentReceived = { txn ->
            receivedTotal += txn.amount
            transactions = transactions + TransactionItem(
                id = txn.id,
                amount = txn.amount, type = "received",
                timestamp = txn.timestamp,
                status = "confirmed",
                counterparty = txn.senderID.short
            )

            // Vibrate
            val vibrator = context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
            vibrator?.vibrate(VibrationEffect.createOneShot(300, VibrationEffect.DEFAULT_AMPLITUDE))

            // Toast
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                Toast.makeText(context, "₹${txn.amount / 100} received! ✅", Toast.LENGTH_LONG).show()
            }
        }
    }

    MaterialTheme(
        colorScheme = darkColorScheme(
            background = AstralBlack, surface = AstralDark,
            primary = AstralWhite, onBackground = AstralWhite,
            onSurface = AstralWhite, onPrimary = AstralBlack
        )
    ) {
        if (!btEnabled) {
            BluetoothOffScreen()
            return@MaterialTheme
        }

        if (showScanner) {
            QRScannerScreen(
                onPaymentInitiated = { amount: Int, merchantPubKey: String, merchantName: String ->
                    if (amount <= balance) {
                        // Decode merchant's public key from base64
                        val pubKeyBytes = Base64.decode(merchantPubKey, Base64.NO_WRAP)

                        // Use SDK to send encrypted, signed payment
                        val merchantWalletID = WalletID.fromPublicKey(pubKeyBytes)
                        sdk.sendPayment(amount, merchantWalletID, pubKeyBytes) { result ->
                            result.fold(
                                onSuccess = { txn ->
                                    balance -= amount
                                    transactions = transactions + TransactionItem(
                                        id = txn.id, amount = amount, type = "sent",
                                        timestamp = System.currentTimeMillis(),
                                        status = "confirmed", counterparty = merchantName
                                    )
                                    android.util.Log.d("ASTRAL_PAY", "📤 Payment sent via SDK: ₹${amount / 100} → $merchantName")
                                },
                                onFailure = { error ->
                                    android.util.Log.e("ASTRAL_PAY", "❌ Payment failed: ${error.message}")
                                    android.os.Handler(android.os.Looper.getMainLooper()).post {
                                        Toast.makeText(context, "Payment failed: ${error.message}", Toast.LENGTH_LONG).show()
                                    }
                                }
                            )
                        }
                    }
                    showScanner = false
                },
                onClose = { showScanner = false }
            )
        } else {
            Scaffold(
                containerColor = AstralBlack,
                bottomBar = {
                    NavigationBar(containerColor = AstralDark, contentColor = AstralWhite, tonalElevation = 0.dp) {
                        NavigationBarItem(
                            selected = selectedTab == 0, onClick = { selectedTab = 0 },
                            icon = { Icon(Icons.Default.AccountBalanceWallet, "Wallet") },
                            label = { Text("WALLET", fontSize = 10.sp, letterSpacing = 2.sp) },
                            colors = NavigationBarItemDefaults.colors(
                                selectedIconColor = AstralWhite, selectedTextColor = AstralWhite,
                                unselectedIconColor = AstralMuted, unselectedTextColor = AstralMuted,
                                indicatorColor = AstralWhite.copy(alpha = 0.08f)
                            )
                        )
                        NavigationBarItem(
                            selected = selectedTab == 1, onClick = { showScanner = true },
                            icon = {
                                Box(modifier = Modifier.size(48.dp).clip(CircleShape).background(AstralWhite),
                                    contentAlignment = Alignment.Center) {
                                    Icon(Icons.Default.QrCodeScanner, "Scan & Pay",
                                        tint = AstralBlack, modifier = Modifier.size(24.dp))
                                }
                            },
                            label = { Text("PAY", fontSize = 10.sp, letterSpacing = 2.sp) },
                            colors = NavigationBarItemDefaults.colors(
                                selectedIconColor = AstralWhite, selectedTextColor = AstralWhite,
                                unselectedIconColor = AstralMuted, unselectedTextColor = AstralMuted,
                                indicatorColor = Color.Transparent
                            )
                        )
                        NavigationBarItem(
                            selected = selectedTab == 2, onClick = { selectedTab = 2 },
                            icon = { Icon(Icons.Default.Storefront, "Merchant") },
                            label = { Text("RECEIVE", fontSize = 10.sp, letterSpacing = 2.sp) },
                            colors = NavigationBarItemDefaults.colors(
                                selectedIconColor = AstralWhite, selectedTextColor = AstralWhite,
                                unselectedIconColor = AstralMuted, unselectedTextColor = AstralMuted,
                                indicatorColor = AstralWhite.copy(alpha = 0.08f)
                            )
                        )
                    }
                }
            ) { padding ->
                Box(modifier = Modifier.padding(padding)) {
                    when (selectedTab) {
                        0 -> WalletScreen(balance, transactions, sdk)
                        2 -> MerchantScreen(
                            receivedTotal = receivedTotal,
                            transactions = transactions.filter { it.type == "received" },
                            sdk = sdk
                        )
                    }
                }
            }
        }
    }
}

// ── Bluetooth OFF Screen ─────────────────────────────────────

@Composable
fun BluetoothOffScreen() {
    Box(
        modifier = Modifier.fillMaxSize().background(AstralBlack),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Icon(Icons.Default.BluetoothDisabled, null, tint = AstralError, modifier = Modifier.size(64.dp))
            Text("BLUETOOTH IS OFF", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = AstralWhite, letterSpacing = 3.sp)
            Text("Astral Wallet requires Bluetooth\nto send and receive payments.", fontSize = 14.sp, color = AstralMuted,
                modifier = Modifier.padding(horizontal = 40.dp), lineHeight = 22.sp)
            Spacer(Modifier.height(8.dp))
            Card(colors = CardDefaults.cardColors(containerColor = AstralWarning.copy(alpha = 0.1f)), shape = RoundedCornerShape(12.dp)) {
                Row(modifier = Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Warning, null, tint = AstralWarning, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Turn on Bluetooth in Settings to continue", color = AstralWarning, fontSize = 13.sp)
                }
            }
        }
    }
}

// ── Wallet Screen ────────────────────────────────────────────

@Composable
fun WalletScreen(balance: Int, transactions: List<TransactionItem>, sdk: AstralSDK) {
    val walletShort = sdk.walletID?.short ?: "..."
    val hwBacked = sdk.isHardwareBacked

    LazyColumn(modifier = Modifier.fillMaxSize().padding(20.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
        item {
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    DiamondLogo(size = 28f, color = AstralWhite)
                    Text("ASTRAL WALLET", fontSize = 12.sp, fontWeight = FontWeight.Light, letterSpacing = 4.sp, color = AstralMuted)
                }
                // Hardware security badge
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    Icon(
                        if (hwBacked) Icons.Default.Shield else Icons.Default.Warning,
                        null,
                        tint = if (hwBacked) AstralSuccess else AstralWarning,
                        modifier = Modifier.size(14.dp)
                    )
                    Text(
                        if (hwBacked) "TEE" else "SW",
                        fontSize = 9.sp, letterSpacing = 1.sp,
                        color = if (hwBacked) AstralSuccess else AstralWarning
                    )
                }
            }
        }

        // Wallet ID card
        item {
            Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(containerColor = AstralCard)) {
                Row(modifier = Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Fingerprint, null, tint = AstralMuted, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Wallet: $walletShort", fontSize = 12.sp, color = AstralMuted,
                        fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace)
                }
            }
        }

        item {
            Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = AstralCard)) {
                Column(modifier = Modifier.padding(28.dp)) {
                    Text("AVAILABLE BALANCE", fontSize = 10.sp, letterSpacing = 3.sp, color = AstralMuted)
                    Spacer(Modifier.height(8.dp))
                    Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("₹", fontSize = 24.sp, color = AstralWhite.copy(alpha = 0.6f), fontWeight = FontWeight.Light)
                        Text("${balance / 100}", fontSize = 48.sp, fontWeight = FontWeight.Bold, color = AstralWhite)
                    }
                    Spacer(Modifier.height(16.dp))
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Box(modifier = Modifier.size(6.dp).clip(CircleShape).background(AstralSuccess))
                        Text("OFFLINE READY", fontSize = 9.sp, letterSpacing = 2.sp, color = AstralSuccess)
                    }
                }
            }
        }

        item { Text("RECENT ACTIVITY", fontSize = 10.sp, letterSpacing = 3.sp, color = AstralMuted, modifier = Modifier.padding(top = 8.dp)) }

        if (transactions.isEmpty()) {
            item {
                Box(modifier = Modifier.fillMaxWidth().padding(vertical = 40.dp), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(Icons.Default.SwapHoriz, null, tint = AstralBorder, modifier = Modifier.size(48.dp))
                        Spacer(Modifier.height(12.dp))
                        Text("No transactions yet", color = AstralMuted, fontSize = 14.sp)
                        Text("Scan a QR to make your first payment", color = AstralMuted.copy(alpha = 0.6f), fontSize = 12.sp)
                    }
                }
            }
        }

        items(transactions.reversed()) { txn -> TxnRow(txn) }
    }
}

// ── Merchant Screen (SDK-based) ──────────────────────────────

@Composable
fun MerchantScreen(
    receivedTotal: Int,
    transactions: List<TransactionItem>,
    sdk: AstralSDK
) {
    var isListening by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val merchantName = sdk.walletID?.short ?: "Merchant"

    // Auto-start SDK listening when merchant screen appears
    DisposableEffect(Unit) {
        isListening = true
        sdk.startListening()
        android.util.Log.d("ASTRAL_PAY", "📡 Merchant mode: SDK listening started")
        onDispose {
            isListening = false
            sdk.stopListening()
            android.util.Log.d("ASTRAL_PAY", "⏸️ Merchant mode: SDK listening stopped")
        }
    }

    // Generate QR using SDK
    val qrPayload = remember { sdk.generatePaymentQR() }
    val qrBitmap = remember { AstralQR.generateQR(qrPayload.toJSONString()) }

    LazyColumn(modifier = Modifier.fillMaxSize().padding(20.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
        item {
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    DiamondLogo(size = 28f, color = AstralWhite)
                    Text("RECEIVE", fontSize = 12.sp, fontWeight = FontWeight.Light, letterSpacing = 4.sp, color = AstralMuted)
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Box(modifier = Modifier.size(6.dp).clip(CircleShape)
                        .background(if (isListening) AstralSuccess else AstralMuted.copy(alpha = 0.3f)))
                    Text(if (isListening) "LISTENING" else "OFFLINE", fontSize = 9.sp, letterSpacing = 2.sp,
                        color = if (isListening) AstralSuccess else AstralMuted)
                }
            }
        }

        item {
            Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = AstralCard),
                border = androidx.compose.foundation.BorderStroke(1.dp, AstralBorder)) {
                Column(modifier = Modifier.fillMaxWidth().padding(28.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("SCAN TO PAY", fontSize = 10.sp, letterSpacing = 3.sp, color = AstralMuted)
                    Spacer(Modifier.height(16.dp))
                    Image(bitmap = qrBitmap.asImageBitmap(), contentDescription = "Merchant QR",
                        modifier = Modifier.size(220.dp).clip(RoundedCornerShape(12.dp)))
                    Spacer(Modifier.height(16.dp))
                    Text(merchantName, fontSize = 16.sp, fontWeight = FontWeight.Medium, color = AstralWhite)
                    if (isListening) {
                        Spacer(Modifier.height(12.dp))
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 1.5.dp, color = AstralWhite)
                            Text("Waiting for payments...", color = AstralMuted, fontSize = 12.sp)
                        }
                    }
                }
            }
        }

        item {
            Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = AstralCard),
                border = androidx.compose.foundation.BorderStroke(1.dp, AstralBorder)) {
                Column(modifier = Modifier.padding(24.dp)) {
                    Text("TOTAL EARNED", fontSize = 10.sp, letterSpacing = 3.sp, color = AstralMuted)
                    Spacer(Modifier.height(8.dp))
                    Row(verticalAlignment = Alignment.Bottom) {
                        Text("₹", fontSize = 20.sp, color = AstralWhite.copy(alpha = 0.6f), fontWeight = FontWeight.Light)
                        Text("${receivedTotal / 100}", fontSize = 36.sp, fontWeight = FontWeight.Bold, color = AstralWhite)
                    }
                    Text("${transactions.size} payment(s)", fontSize = 12.sp, color = AstralMuted)
                }
            }
        }

        item { Text("RECEIVED", fontSize = 10.sp, letterSpacing = 3.sp, color = AstralMuted) }

        if (transactions.isEmpty()) {
            item {
                Box(modifier = Modifier.fillMaxWidth().padding(vertical = 40.dp), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(Icons.Default.ArrowDownward, null, tint = AstralBorder, modifier = Modifier.size(48.dp))
                        Spacer(Modifier.height(12.dp))
                        Text("No payments received yet", color = AstralMuted, fontSize = 14.sp)
                        Text("Share your QR code to receive payments", color = AstralMuted.copy(alpha = 0.6f), fontSize = 12.sp)
                    }
                }
            }
        }

        items(transactions.reversed()) { txn -> TxnRow(txn) }
    }
}

// ── Transaction Row ──────────────────────────────────────────

@Composable
fun TxnRow(txn: TransactionItem) {
    val dateFormat = SimpleDateFormat("HH:mm · MMM dd", Locale.getDefault())
    Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = AstralCard)) {
        Row(modifier = Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Box(modifier = Modifier.size(36.dp).clip(CircleShape)
                    .background(if (txn.type == "sent") AstralError.copy(alpha = 0.1f) else AstralSuccess.copy(alpha = 0.1f)),
                    contentAlignment = Alignment.Center) {
                    Icon(if (txn.type == "sent") Icons.Default.ArrowUpward else Icons.Default.ArrowDownward,
                        null, tint = if (txn.type == "sent") AstralError else AstralSuccess, modifier = Modifier.size(18.dp))
                }
                Column {
                    Text(if (txn.counterparty.isNotEmpty()) txn.counterparty else if (txn.type == "sent") "Sent" else "Received",
                        fontWeight = FontWeight.Medium, fontSize = 14.sp)
                    Text(dateFormat.format(Date(txn.timestamp)), fontSize = 11.sp, color = AstralMuted)
                }
            }
            Text("${if (txn.type == "sent") "-" else "+"}₹${txn.amount / 100}",
                fontSize = 16.sp, fontWeight = FontWeight.Bold,
                color = if (txn.type == "sent") AstralError else AstralSuccess)
        }
    }
}
