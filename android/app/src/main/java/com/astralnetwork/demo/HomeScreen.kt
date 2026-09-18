package com.astralnetwork.demo

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.util.Base64
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.animation.*
import androidx.compose.foundation.*
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.astralnetwork.sdk.core.AstralSDK
import com.astralnetwork.sdk.identity.WalletID
import com.astralnetwork.sdk.wallet.AstralQRPayload
import com.astralnetwork.sdk.wallet.AstralTransaction
import com.google.zxing.*
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeWriter
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

// ── ViewModel ─────────────────────────────────────────────────────────────────

class HomeViewModel(private val sdk: AstralSDK) : ViewModel() {

    private val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    data class UiState(
        val walletID: WalletID? = null,
        val balancePaisa: Long = 50000L, // ₹500.00 default
        val isHardwareBacked: Boolean = false,
        val isStrongBoxBacked: Boolean = false,
        val isListening: Boolean = false,
        val transactions: List<AstralTransaction> = emptyList(),
        val receiveQR: AstralQRPayload? = null,
        val error: String? = null
    )

    init {
        viewModelScope.launch {
            sdk.start()
            sdk.startListening()

            _uiState.value = _uiState.value.copy(
                walletID = sdk.walletID,
                isHardwareBacked = sdk.isHardwareBacked,
                isStrongBoxBacked = sdk.isStrongBoxBacked,
                isListening = true,
                receiveQR = sdk.generateReceiveQR()
            )

            sdk.onPayloadReceived = { data, sender ->
                handleIncomingPayload(data, sender)
            }
        }
    }

    fun sendPayment(amountPaisa: Long, memo: String, to: AstralQRPayload) {
        viewModelScope.launch {
            val state = _uiState.value
            if (amountPaisa > state.balancePaisa) {
                _uiState.value = state.copy(error = "Insufficient balance")
                return@launch
            }

            val txn = AstralTransaction(
                id = UUID.randomUUID().toString(),
                senderID = state.walletID ?: return@launch,
                recipientID = to.walletID,
                amountPaisa = amountPaisa,
                memo = memo,
                status = AstralTransaction.Status.PENDING
            )

            val payload = buildPaymentPayload(txn)
            sdk.sendPayload(payload, to) { result ->
                result.fold(
                    onSuccess = {
                        _uiState.value = _uiState.value.copy(
                            balancePaisa = _uiState.value.balancePaisa - amountPaisa,
                            transactions = listOf(txn.copy(status = AstralTransaction.Status.SENT)) + _uiState.value.transactions
                        )
                    },
                    onFailure = { e ->
                        _uiState.value = _uiState.value.copy(error = e.message)
                    }
                )
            }
        }
    }

    private fun handleIncomingPayload(data: ByteArray, sender: WalletID) {
        try {
            val json = JSONObject(String(data, Charsets.UTF_8))
            val txn = AstralTransaction(
                id = json.optString("id", UUID.randomUUID().toString()),
                senderID = sender,
                recipientID = _uiState.value.walletID ?: return,
                amountPaisa = json.getLong("amount"),
                memo = json.optString("memo", ""),
                status = AstralTransaction.Status.RECEIVED
            )
            _uiState.value = _uiState.value.copy(
                balancePaisa = _uiState.value.balancePaisa + txn.amountPaisa,
                transactions = listOf(txn) + _uiState.value.transactions
            )
        } catch (_: Exception) {}
    }

    private fun buildPaymentPayload(txn: AstralTransaction): ByteArray {
        return JSONObject().apply {
            put("id", txn.id)
            put("amount", txn.amountPaisa)
            put("memo", txn.memo)
        }.toString().toByteArray(Charsets.UTF_8)
    }

    fun clearError() { _uiState.value = _uiState.value.copy(error = null) }
}

// ── Theme ─────────────────────────────────────────────────────────────────────

private val Black = Color(0xFF000000)
private val SurfaceWhite = Color(0x0DFFFFFF)
private val BorderWhite = Color(0x1AFFFFFF)
private val DimWhite = Color(0x66FFFFFF)
private val TextWhite = Color(0xFFFFFFFF)
private val Green = Color(0xFF34C759)
private val Orange = Color(0xFFFF9500)

// ── Home Screen ───────────────────────────────────────────────────────────────

@Composable
fun HomeScreen(viewModel: HomeViewModel) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    var showReceiveSheet by remember { mutableStateOf(false) }
    var showSendSheet by remember { mutableStateOf(false) }
    var pendingRecipient by remember { mutableStateOf<AstralQRPayload?>(null) }

    Surface(color = Black, modifier = Modifier.fillMaxSize()) {
        LazyColumn(
            contentPadding = PaddingValues(20.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp)
        ) {
            item {
                BalanceCard(
                    walletID = state.walletID,
                    balancePaisa = state.balancePaisa,
                    isStrongBox = state.isStrongBoxBacked,
                    isHardwareBacked = state.isHardwareBacked
                )
            }
            item {
                TransportStatusRow(isListening = state.isListening)
            }
            item {
                ActionButtonRow(
                    onReceive = { showReceiveSheet = true },
                    onSend = { showSendSheet = true }
                )
            }
            if (state.transactions.isEmpty()) {
                item { EmptyState() }
            } else {
                item {
                    Text("TRANSACTIONS",
                        fontSize = 11.sp, fontWeight = FontWeight.SemiBold,
                        color = DimWhite, letterSpacing = 2.sp,
                        modifier = Modifier.padding(start = 4.dp))
                }
                items(state.transactions) { txn -> TransactionRow(txn) }
            }
        }
    }

    // Snackbar for errors
    state.error?.let { err ->
        LaunchedEffect(err) {
            kotlinx.coroutines.delay(3000)
            viewModel.clearError()
        }
    }

    // Sheets
    if (showReceiveSheet) {
        state.receiveQR?.let { qr ->
            ReceiveSheet(qr = qr, onDismiss = { showReceiveSheet = false })
        }
    }
    if (showSendSheet) {
        QRScannerSheetWrapper(
            onScan = { scannedText ->
                showSendSheet = false
                try {
                    val json = JSONObject(scannedText)
                    val walletIDStr = json.getString("walletID")
                    pendingRecipient = AstralQRPayload(WalletID(walletIDStr))
                } catch (e: Exception) {
                    try {
                        pendingRecipient = AstralQRPayload.fromJson(scannedText)
                    } catch (_: Exception) {}
                }
            },
            onDismiss = { showSendSheet = false }
        )
    }
    if (pendingRecipient != null) {
        SendSheet(
            recipient = pendingRecipient!!,
            balance = state.balancePaisa,
            onSend = { amt, memo ->
                viewModel.sendPayment(amt, memo, pendingRecipient!!)
                pendingRecipient = null
            },
            onDismiss = { pendingRecipient = null }
        )
    }
}

// ── Balance Card ───────────────────────────────────────────────────────────────

@Composable
fun BalanceCard(walletID: WalletID?, balancePaisa: Long, isStrongBox: Boolean, isHardwareBacked: Boolean) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(SurfaceWhite)
            .border(1.dp, BorderWhite, RoundedCornerShape(20.dp))
            .padding(24.dp)
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.Top) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("BALANCE", fontSize = 11.sp, fontWeight = FontWeight.SemiBold,
                        color = DimWhite, letterSpacing = 2.sp)
                    Spacer(modifier = Modifier.height(4.dp))
                    Text("₹%.2f".format(balancePaisa / 100.0),
                        fontSize = 42.sp, fontWeight = FontWeight.Bold, color = TextWhite)
                }
                Icon(Icons.Default.Lock, "Security",
                    tint = if (isStrongBox) Green else Orange,
                    modifier = Modifier.size(24.dp))
            }
            Divider(color = BorderWhite)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(modifier = Modifier.size(7.dp).clip(CircleShape)
                    .background(if (isHardwareBacked) Green else Orange))
                Spacer(modifier = Modifier.width(6.dp))
                Text(if (isStrongBox) "StrongBox" else if (isHardwareBacked) "TEE" else "Software",
                    fontSize = 12.sp, color = DimWhite)
                Spacer(modifier = Modifier.weight(1f))
                Text(walletID?.short ?: "—", fontSize = 12.sp, color = DimWhite,
                    fontFamily = FontFamily.Monospace)
            }
        }
    }
}

// ── Transport Status ───────────────────────────────────────────────────────────

@Composable
fun TransportStatusRow(isListening: Boolean) {
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        StatusPill("BLE", isListening, Icons.Default.Bluetooth)
        StatusPill("WiFi", isListening, Icons.Default.Wifi)
    }
}

@Composable
fun StatusPill(label: String, isActive: Boolean, icon: androidx.compose.ui.graphics.vector.ImageVector) {
    Row(
        modifier = Modifier.clip(RoundedCornerShape(50)).background(SurfaceWhite).padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Icon(icon, label, tint = DimWhite, modifier = Modifier.size(14.dp))
        Text(label, fontSize = 12.sp, fontWeight = FontWeight.Medium, color = DimWhite)
        Box(modifier = Modifier.size(6.dp).clip(CircleShape).background(if (isActive) Green else Color.Gray))
    }
}

// ── Action Buttons ─────────────────────────────────────────────────────────────

@Composable
fun ActionButtonRow(onReceive: () -> Unit, onSend: () -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        ActionButton("Receive", Icons.Default.QrCode, onReceive, Modifier.weight(1f))
        ActionButton("Send", Icons.Default.CameraAlt, onSend, Modifier.weight(1f))
    }
}

@Composable
fun ActionButton(label: String, icon: androidx.compose.ui.graphics.vector.ImageVector,
                 onClick: () -> Unit, modifier: Modifier = Modifier) {
    Button(onClick = onClick, modifier = modifier,
        colors = ButtonDefaults.buttonColors(containerColor = SurfaceWhite),
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(1.dp, BorderWhite)
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.padding(vertical = 12.dp)) {
            Icon(icon, label, tint = TextWhite, modifier = Modifier.size(24.dp))
            Text(label, color = TextWhite, fontWeight = FontWeight.SemiBold)
        }
    }
}

// ── Transaction Row ────────────────────────────────────────────────────────────

@Composable
fun TransactionRow(txn: AstralTransaction) {
    val isIncoming = txn.status == AstralTransaction.Status.RECEIVED
    Row(
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp))
            .background(SurfaceWhite).padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Icon(
            if (isIncoming) Icons.Default.ArrowDownward else Icons.Default.ArrowUpward,
            "Direction", tint = if (isIncoming) Green else TextWhite,
            modifier = Modifier.size(22.dp)
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(if (isIncoming) txn.senderID.short else txn.recipientID.short,
                color = TextWhite, fontWeight = FontWeight.Medium, fontSize = 14.sp,
                fontFamily = FontFamily.Monospace)
            if (txn.memo.isNotEmpty()) {
                Text(txn.memo, color = DimWhite, fontSize = 12.sp)
            }
        }
        Text("${if (isIncoming) "+" else "-"}₹%.2f".format(txn.amountPaisa / 100.0),
            color = if (isIncoming) Green else TextWhite,
            fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
    }
}

@Composable
fun EmptyState() {
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 40.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Icon(Icons.Default.Waves, "Empty", tint = DimWhite, modifier = Modifier.size(48.dp))
        Text("No transactions yet.\nScan a QR to send your first payment.",
            color = DimWhite, textAlign = TextAlign.Center, fontSize = 14.sp)
    }
}

// ── Sheets ────────────────────────────────────────────────────────────────────

@Composable
fun ReceiveSheet(qr: AstralQRPayload, onDismiss: () -> Unit) {
    val qrBitmap = remember(qr) { generateQRBitmap(qr.toJson(), 512) }
    Dialog(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .clip(RoundedCornerShape(24.dp))
                .background(Color(0xFF111111))
                .padding(28.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text("Receive Payment", color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 18.sp)
            qrBitmap?.let {
                Image(
                    bitmap = it.asImageBitmap(),
                    contentDescription = "Payment QR",
                    modifier = Modifier
                        .size(220.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(Color.White)
                        .padding(12.dp)
                )
            }
            Text(qr.walletID.short, color = Color.White.copy(alpha = 0.5f),
                fontFamily = FontFamily.Monospace, fontSize = 14.sp)
            Text("Share this QR with the sender", color = Color.White.copy(alpha = 0.4f), fontSize = 12.sp)
            TextButton(onClick = onDismiss) { Text("Done", color = Color.White) }
        }
    }
}

private fun generateQRBitmap(content: String, size: Int): Bitmap? = try {
    val writer = QRCodeWriter()
    val hints = mapOf(EncodeHintType.MARGIN to 1)
    val matrix = writer.encode(content, BarcodeFormat.QR_CODE, size, size, hints)
    val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.RGB_565)
    for (x in 0 until size) for (y in 0 until size) {
        bmp.setPixel(x, y, if (matrix[x, y]) android.graphics.Color.BLACK else android.graphics.Color.WHITE)
    }
    bmp
} catch (e: Exception) { null }

@Composable
fun SendSheet(recipient: AstralQRPayload, balance: Long,
              onSend: (Long, String) -> Unit, onDismiss: () -> Unit) {
    var amountText by remember { mutableStateOf("") }
    var memo by remember { mutableStateOf("") }
    val amount = (amountText.toLongOrNull() ?: 0L) * 100L
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Color(0xFF111111),
        title = { Text("Send to ${recipient.walletID.short}", color = TextWhite) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(value = amountText, onValueChange = { amountText = it },
                    label = { Text("Amount (₹)") }, singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(focusedTextColor = TextWhite))
                OutlinedTextField(value = memo, onValueChange = { memo = it },
                    label = { Text("Memo") }, singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(focusedTextColor = TextWhite))
            }
        },
        confirmButton = {
            TextButton(onClick = { onSend(amount, memo) },
                enabled = amount > 0 && amount <= balance) {
                Text("Send Offline", color = if (amount > 0 && amount <= balance) TextWhite else DimWhite)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel", color = DimWhite) } }
    )
}
