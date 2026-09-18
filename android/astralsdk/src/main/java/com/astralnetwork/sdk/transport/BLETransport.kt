package com.astralnetwork.sdk.transport

import android.Manifest
import android.bluetooth.*
import android.bluetooth.le.*
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import android.util.Log
import androidx.core.content.ContextCompat
import com.astralnetwork.sdk.core.AstralConfig
import com.astralnetwork.sdk.core.AstralError
import com.astralnetwork.sdk.identity.WalletID
import java.util.UUID

/**
 * BLETransport — CoreBluetooth/Android GATT implementation of AstralTransport.
 *
 * Implements bidirectional communication:
 *   - Merchant Mode (Peripheral / GATT Server):
 *       Advertises Service UUID. Accepts incoming writes on TX characteristic.
 *       Reassembles packet fragments using [PacketFragmenter].
 *       Sends application ACK/NACK via notifications on RX characteristic.
 *   - Customer Mode (Central / GATT Client):
 *       Scans and connects to Merchant GATT server.
 *       Fragments packet using [PacketFragmenter] and writes to TX characteristic.
 *       Awaits ACK/NACK on RX characteristic before completing.
 *       Ensures GATT client disconnection and closure to prevent Error 133.
 */
class BLETransport(
    private val context: Context,
    private val config: AstralConfig = AstralConfig.DEFAULT
) : AstralTransport {

    companion object {
        private const val TAG = "AstralSDK/BLE"

        // UUIDs matching iOS CoreBluetooth and AstralConfig
        val SERVICE_UUID: UUID = UUID.fromString(AstralConfig.DEFAULT.bleServiceUUID)
        val TX_CHAR_UUID: UUID = UUID.fromString("0000A580-0000-1000-8000-00805F9B34FB")
        val RX_CHAR_UUID: UUID = UUID.fromString("0000A581-0000-1000-8000-00805F9B34FB")
        val ANNOUNCE_CHAR_UUID: UUID = UUID.fromString("0000A582-0000-1000-8000-00805F9B34FB")
        val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805F9B34FB")

        // Backwards compatibility aliases
        val WRITE_CHAR_UUID: UUID = TX_CHAR_UUID
        val NOTIFY_CHAR_UUID: UUID = RX_CHAR_UUID

        const val BLE_MTU = 512
    }

    override val kind: TransportKind = TransportKind.BLE

    private val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
    private val bluetoothAdapter: BluetoothAdapter? get() = bluetoothManager?.adapter

    // Peripheral / Server State
    private var gattServer: BluetoothGattServer? = null
    private var advertiser: BluetoothLeAdvertiser? = null
    private var serverNotifyChar: BluetoothGattCharacteristic? = null
    private val serverFragmenter = PacketFragmenter(config.bleFragmentSize)

    // Central / Client State
    private var connectedGatt: BluetoothGatt? = null
    private val clientFragmenter = PacketFragmenter(config.bleFragmentSize)
    private var pendingSendCompletion: ((Result<Unit>) -> Unit)? = null
    private var pendingFragments: List<ByteArray> = emptyList()
    private var currentFragmentIndex = 0
    private var clientWriteChar: BluetoothGattCharacteristic? = null
    private var clientTimeoutRunnable: Runnable? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    // Discovered devices cache
    private val discoveredDevices = mutableMapOf<String, BluetoothDevice>()

    // AstralTransport Callbacks
    override var onRawDataReceived: ((data: ByteArray, respond: (VerificationResult) -> Unit) -> Unit)? = null
    override var onStatusChanged: ((Boolean) -> Unit)? = null

    // Legacy callback for direct packet listeners
    var onPacketReceived: ((ByteArray) -> Unit)? = null

    override val isAvailable: Boolean
        get() {
            val adapter = bluetoothAdapter ?: return false
            if (!adapter.isEnabled) return false
            return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED &&
                ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
            } else {
                ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH) == PackageManager.PERMISSION_GRANTED
            }
        }

    // ── AstralTransport Lifecycle ──────────────────────────────────────────────

    override fun start() {
        Log.d(TAG, "BLETransport started")
        onStatusChanged?.invoke(isAvailable)
    }

    override fun stop() {
        stopListening()
        disconnectCurrentClient()
        Log.d(TAG, "BLETransport stopped")
        onStatusChanged?.invoke(false)
    }

    override fun startListening(walletID: WalletID) {
        startAdvertising(walletID.id)
    }

    override fun stopListening() {
        stopAdvertising()
    }

    override fun isReachable(recipientID: WalletID): Boolean {
        return isAvailable
    }

    // ── Customer Mode: Sending Payments (GATT Client) ──────────────────────────

    override fun send(packet: ByteArray, to: WalletID, completion: (Result<Unit>) -> Unit) {
        if (!isAvailable) {
            completion(Result.failure(AstralError.BleUnavailable))
            return
        }

        // Prepare fragments
        pendingFragments = clientFragmenter.fragment(packet)
        currentFragmentIndex = 0
        pendingSendCompletion = completion

        // Start scanning for merchant peripheral
        startScanningForMerchant(to)
    }

    private fun startScanningForMerchant(targetWallet: WalletID) {
        val scanner = bluetoothAdapter?.bluetoothLeScanner
        if (scanner == null) {
            failPendingSend(AstralError.BleUnavailable)
            return
        }

        val scanFilter = ScanFilter.Builder()
            .setServiceUuid(ParcelUuid(SERVICE_UUID))
            .build()

        val scanSettings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()

        // Set safety timeout for scanning/connection
        scheduleTimeout(config.paymentTimeoutMs) {
            Log.w(TAG, "Scan/Payment timeout expired")
            stopScanSafely(scanner, scanCallback)
            failPendingSend(AstralError.PaymentFailed("Merchant did not respond (timeout)"))
        }

        try {
            scanner.startScan(listOf(scanFilter), scanSettings, scanCallback)
            Log.d(TAG, "Scanning for merchant with service $SERVICE_UUID...")
        } catch (e: SecurityException) {
            Log.e(TAG, "SecurityException while starting scan", e)
            failPendingSend(AstralError.BleUnavailable)
        }
    }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val device = result.device ?: return
            Log.d(TAG, "Discovered merchant device: ${device.address}")

            val scanner = bluetoothAdapter?.bluetoothLeScanner
            scanner?.let { stopScanSafely(it, this) }

            connectToMerchant(device)
        }

        override fun onScanFailed(errorCode: Int) {
            Log.e(TAG, "Scan failed with error code: $errorCode")
            failPendingSend(AstralError.BleUnavailable)
        }
    }

    private fun stopScanSafely(scanner: BluetoothLeScanner, callback: ScanCallback) {
        try {
            scanner.stopScan(callback)
        } catch (e: Exception) {
            Log.w(TAG, "Error stopping scan: ${e.message}")
        }
    }

    private fun connectToMerchant(device: BluetoothDevice) {
        Log.d(TAG, "Connecting to merchant ${device.address}...")
        try {
            connectedGatt = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                device.connectGatt(context, false, gattClientCallback, BluetoothDevice.TRANSPORT_LE)
            } else {
                device.connectGatt(context, false, gattClientCallback)
            }
        } catch (e: SecurityException) {
            Log.e(TAG, "SecurityException during connectGatt", e)
            failPendingSend(AstralError.BleUnavailable)
        }
    }

    private val gattClientCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            Log.d(TAG, "GATT client state change: status=$status, newState=$newState")
            if (status != BluetoothGatt.GATT_SUCCESS) {
                Log.w(TAG, "GATT connection failure: status=$status")
                disconnectAndClose(gatt)
                failPendingSend(AstralError.BleUnavailable)
                return
            }

            if (newState == BluetoothProfile.STATE_CONNECTED) {
                Log.d(TAG, "Connected to merchant GATT. Requesting MTU $BLE_MTU...")
                try {
                    gatt.requestMtu(BLE_MTU)
                } catch (e: SecurityException) {
                    gatt.discoverServices()
                }
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                Log.d(TAG, "Disconnected from merchant GATT")
                disconnectAndClose(gatt)
            }
        }

        override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
            Log.d(TAG, "MTU changed to $mtu, status=$status. Discovering services...")
            try {
                gatt.discoverServices()
            } catch (e: SecurityException) {
                failPendingSend(AstralError.BleUnavailable)
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                Log.e(TAG, "Service discovery failed with status $status")
                disconnectAndClose(gatt)
                failPendingSend(AstralError.BleUnavailable)
                return
            }

            val service = gatt.getService(SERVICE_UUID)
            if (service == null) {
                Log.e(TAG, "Astral service not found on device")
                disconnectAndClose(gatt)
                failPendingSend(AstralError.BleUnavailable)
                return
            }

            clientWriteChar = service.getCharacteristic(TX_CHAR_UUID)
            val rxChar = service.getCharacteristic(RX_CHAR_UUID)

            if (clientWriteChar == null || rxChar == null) {
                Log.e(TAG, "Required characteristics not found")
                disconnectAndClose(gatt)
                failPendingSend(AstralError.BleUnavailable)
                return
            }

            // Subscribe to RX notifications (ACK/NACK)
            enableNotifications(gatt, rxChar)
        }

        override fun onDescriptorWrite(gatt: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            if (descriptor.uuid == CCCD_UUID && status == BluetoothGatt.GATT_SUCCESS) {
                Log.d(TAG, "CCCD notification subscription confirmed. Starting fragment transmission...")
                writeNextFragment(gatt)
            } else {
                Log.w(TAG, "Descriptor write failed or unexpected: status=$status")
                writeNextFragment(gatt)
            }
        }

        override fun onCharacteristicWrite(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                currentFragmentIndex++
                if (currentFragmentIndex < pendingFragments.size) {
                    writeNextFragment(gatt)
                } else {
                    Log.d(TAG, "All fragments written. Awaiting merchant ACK/NACK on RX char...")
                }
            } else {
                Log.e(TAG, "Characteristic write failed: status=$status")
                disconnectAndClose(gatt)
                failPendingSend(AstralError.BleUnavailable)
            }
        }

        @Deprecated("Deprecated in Java")
        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            handleRxNotification(gatt, characteristic.value)
        }

        // Android 13+ callback
        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray
        ) {
            handleRxNotification(gatt, value)
        }
    }

    private fun enableNotifications(gatt: BluetoothGatt, char: BluetoothGattCharacteristic) {
        try {
            gatt.setCharacteristicNotification(char, true)
            val descriptor = char.getDescriptor(CCCD_UUID)
            if (descriptor != null) {
                descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                gatt.writeDescriptor(descriptor)
            } else {
                writeNextFragment(gatt)
            }
        } catch (e: SecurityException) {
            Log.e(TAG, "SecurityException while enabling notifications", e)
            failPendingSend(AstralError.BleUnavailable)
        }
    }

    private fun writeNextFragment(gatt: BluetoothGatt) {
        val char = clientWriteChar ?: return
        if (currentFragmentIndex >= pendingFragments.size) return

        val fragment = pendingFragments[currentFragmentIndex]
        char.value = fragment
        char.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT

        try {
            gatt.writeCharacteristic(char)
        } catch (e: SecurityException) {
            Log.e(TAG, "SecurityException while writing fragment", e)
            disconnectAndClose(gatt)
            failPendingSend(AstralError.BleUnavailable)
        }
    }

    private fun handleRxNotification(gatt: BluetoothGatt, data: ByteArray?) {
        if (data == null || data.isEmpty()) return
        Log.d(TAG, "Received response from merchant: 0x${data.joinToString("") { "%02x".format(it) }}")

        cancelTimeout()

        val statusByte = data[0]
        if (statusByte == 0x01.toByte()) {
            // ACK — payment verified and accepted!
            Log.d(TAG, "✅ Merchant ACK received!")
            completePendingSend(Result.success(Unit))
        } else {
            // NACK — payment rejected
            val reasonByte = data.getOrNull(1) ?: 0xFF.toByte()
            val reason = when (reasonByte.toInt()) {
                0x01 -> "Signature verification failed"
                0x02 -> "Double-spend detected"
                0x03 -> "Packet decoding failed"
                else -> "Payment rejected by merchant (code 0x%02x)".format(reasonByte)
            }
            Log.w(TAG, "❌ Merchant NACK received: $reason")
            completePendingSend(Result.failure(AstralError.PaymentFailed(reason)))
        }

        // CRITICAL FIX: Disconnect and close client GATT to avoid GATT error 133 / connection exhaustion
        disconnectAndClose(gatt)
    }

    private fun completePendingSend(result: Result<Unit>) {
        val completion = pendingSendCompletion
        pendingSendCompletion = null
        pendingFragments = emptyList()
        currentFragmentIndex = 0
        mainHandler.post { completion?.invoke(result) }
    }

    private fun failPendingSend(error: AstralError) {
        completePendingSend(Result.failure(error))
    }

    private fun disconnectAndClose(gatt: BluetoothGatt?) {
        try {
            gatt?.disconnect()
        } catch (e: Exception) {
            Log.w(TAG, "Error disconnecting GATT client: ${e.message}")
        }
        try {
            gatt?.close()
        } catch (e: Exception) {
            Log.w(TAG, "Error closing GATT client: ${e.message}")
        }
        if (connectedGatt == gatt) {
            connectedGatt = null
        }
    }

    private fun disconnectCurrentClient() {
        disconnectAndClose(connectedGatt)
    }

    private fun scheduleTimeout(millis: Long, block: () -> Unit) {
        cancelTimeout()
        val runnable = Runnable { block() }
        clientTimeoutRunnable = runnable
        mainHandler.postDelayed(runnable, millis)
    }

    private fun cancelTimeout() {
        clientTimeoutRunnable?.let { mainHandler.removeCallbacks(it) }
        clientTimeoutRunnable = null
    }

    // ── Merchant Mode: Advertising & Receiving (GATT Server) ───────────────────

    fun startAdvertising(walletID: String) {
        if (!isAvailable) {
            Log.w(TAG, "BLE not available for advertising")
            return
        }

        startGattServer(walletID)

        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
            .setConnectable(true)
            .build()

        val data = AdvertiseData.Builder()
            .addServiceUuid(ParcelUuid(SERVICE_UUID))
            .setIncludeDeviceName(false)
            .build()

        try {
            advertiser = bluetoothAdapter?.bluetoothLeAdvertiser
            advertiser?.startAdvertising(settings, data, advertiseCallback)
            Log.d(TAG, "Advertising started — wallet: ${walletID.takeLast(8).uppercase()}")
        } catch (e: SecurityException) {
            Log.e(TAG, "SecurityException starting BLE advertising", e)
        }
    }

    fun stopAdvertising() {
        try {
            advertiser?.stopAdvertising(advertiseCallback)
        } catch (e: Exception) {
            Log.w(TAG, "Error stopping advertiser: ${e.message}")
        }
        advertiser = null

        try {
            gattServer?.close()
        } catch (e: Exception) {
            Log.w(TAG, "Error closing GATT server: ${e.message}")
        }
        gattServer = null
        serverNotifyChar = null
        serverFragmenter.reset()
        Log.d(TAG, "GATT Server and advertising stopped")
    }

    private fun startGattServer(walletID: String) {
        val service = BluetoothGattService(SERVICE_UUID, BluetoothGattService.SERVICE_TYPE_PRIMARY)

        // TX Characteristic: Customer writes encrypted fragments to merchant
        val writeChar = BluetoothGattCharacteristic(
            TX_CHAR_UUID,
            BluetoothGattCharacteristic.PROPERTY_WRITE or BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE,
            BluetoothGattCharacteristic.PERMISSION_WRITE
        )

        // RX Characteristic: Merchant notifies customer with ACK/NACK
        val notifyChar = BluetoothGattCharacteristic(
            RX_CHAR_UUID,
            BluetoothGattCharacteristic.PROPERTY_NOTIFY or BluetoothGattCharacteristic.PROPERTY_READ,
            BluetoothGattCharacteristic.PERMISSION_READ
        ).apply {
            addDescriptor(BluetoothGattDescriptor(
                CCCD_UUID,
                BluetoothGattDescriptor.PERMISSION_WRITE or BluetoothGattDescriptor.PERMISSION_READ
            ))
        }

        // Announce Characteristic: Merchant's wallet identity
        val announceChar = BluetoothGattCharacteristic(
            ANNOUNCE_CHAR_UUID,
            BluetoothGattCharacteristic.PROPERTY_READ,
            BluetoothGattCharacteristic.PERMISSION_READ
        ).apply {
            value = walletID.toByteArray(Charsets.UTF_8)
        }

        service.addCharacteristic(writeChar)
        service.addCharacteristic(notifyChar)
        service.addCharacteristic(announceChar)

        serverNotifyChar = notifyChar

        try {
            gattServer = bluetoothManager?.openGattServer(context, gattServerCallback)
            gattServer?.addService(service)
            Log.d(TAG, "GATT Server registered with Astral service")
        } catch (e: SecurityException) {
            Log.e(TAG, "SecurityException opening GATT server", e)
        }
    }

    private val gattServerCallback = object : BluetoothGattServerCallback() {

        /**
         * CRITICAL FIX: Must override onDescriptorWriteRequest!
         * When customer central writes to CCCD (00002902-...), respond with GATT_SUCCESS.
         * Without this, customer connections deadlock waiting for confirmation!
         */
        override fun onDescriptorWriteRequest(
            device: BluetoothDevice,
            requestId: Int,
            descriptor: BluetoothGattDescriptor,
            preparedWrite: Boolean,
            responseNeeded: Boolean,
            offset: Int,
            value: ByteArray
        ) {
            Log.d(TAG, "onDescriptorWriteRequest for ${descriptor.uuid} from ${device.address}")
            if (descriptor.uuid == CCCD_UUID) {
                descriptor.value = value
                if (responseNeeded) {
                    try {
                        gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, value)
                    } catch (e: SecurityException) {
                        Log.e(TAG, "SecurityException sending descriptor write response", e)
                    }
                }
            } else {
                if (responseNeeded) {
                    try {
                        gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, value)
                    } catch (e: SecurityException) {
                        Log.e(TAG, "SecurityException sending descriptor response", e)
                    }
                }
            }
        }

        override fun onCharacteristicWriteRequest(
            device: BluetoothDevice,
            requestId: Int,
            characteristic: BluetoothGattCharacteristic,
            preparedWrite: Boolean,
            responseNeeded: Boolean,
            offset: Int,
            value: ByteArray
        ) {
            if (characteristic.uuid == TX_CHAR_UUID) {
                if (responseNeeded) {
                    try {
                        gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, value)
                    } catch (e: SecurityException) {
                        Log.e(TAG, "SecurityException sending write response", e)
                    }
                }

                // Reassemble packet fragments
                val assembled = serverFragmenter.reassemble(value)
                if (assembled != null) {
                    Log.d(TAG, "Complete packet reassembled (${assembled.size} bytes) from ${device.address}")

                    // Fire legacy callback if wired
                    onPacketReceived?.invoke(assembled)

                    // Fire AstralTransport verification callback
                    val callback = onRawDataReceived
                    if (callback != null) {
                        callback.invoke(assembled) { result ->
                            when (result) {
                                is VerificationResult.Verified -> sendAck(device)
                                is VerificationResult.Rejected -> sendNack(device, result.error)
                            }
                        }
                    } else {
                        // Default ACK if no verification pipeline is attached
                        sendAck(device)
                    }
                }
            } else {
                if (responseNeeded) {
                    try {
                        gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_REQUEST_NOT_SUPPORTED, offset, null)
                    } catch (e: SecurityException) {
                        Log.e(TAG, "SecurityException sending unsupported response", e)
                    }
                }
            }
        }

        override fun onCharacteristicReadRequest(
            device: BluetoothDevice,
            requestId: Int,
            offset: Int,
            characteristic: BluetoothGattCharacteristic
        ) {
            try {
                val value = characteristic.value ?: ByteArray(0)
                gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, value)
            } catch (e: SecurityException) {
                Log.e(TAG, "SecurityException in onCharacteristicReadRequest", e)
            }
        }
    }

    private fun sendAck(device: BluetoothDevice) {
        val notifyChar = serverNotifyChar ?: return
        notifyChar.value = byteArrayOf(0x01) // 0x01 = verified + accepted
        try {
            gattServer?.notifyCharacteristicChanged(device, notifyChar, false)
            Log.d(TAG, "ACK sent to ${device.address}")
        } catch (e: SecurityException) {
            Log.e(TAG, "SecurityException sending ACK", e)
        }
    }

    private fun sendNack(device: BluetoothDevice, error: AstralError) {
        val notifyChar = serverNotifyChar ?: return
        val reasonByte: Byte = when (error) {
            is AstralError.VerificationFailed -> 0x01
            is AstralError.Duplicate -> 0x02
            is AstralError.PacketDecodingFailed -> 0x03
            else -> 0xFF.toByte()
        }
        notifyChar.value = byteArrayOf(0x00, reasonByte) // 0x00 = rejected
        try {
            gattServer?.notifyCharacteristicChanged(device, notifyChar, false)
            Log.d(TAG, "NACK (code 0x%02x) sent to ${device.address}".format(reasonByte))
        } catch (e: SecurityException) {
            Log.e(TAG, "SecurityException sending NACK", e)
        }
    }

    private val advertiseCallback = object : AdvertiseCallback() {
        override fun onStartSuccess(settingsInEffect: AdvertiseSettings) {
            Log.d(TAG, "BLE Advertising active ✅")
        }
        override fun onStartFailure(errorCode: Int) {
            Log.e(TAG, "BLE Advertising failed: $errorCode")
        }
    }

    // Legacy method for direct peripheral sending
    fun sendPacket(device: BluetoothDevice, encryptedPayload: ByteArray) {
        Log.d(TAG, "sendPacket called directly for ${device.address}")
        connectToMerchant(device)
    }
}
