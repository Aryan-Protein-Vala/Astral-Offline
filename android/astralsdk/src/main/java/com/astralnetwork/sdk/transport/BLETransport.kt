package com.astralnetwork.sdk.transport

import android.annotation.SuppressLint
import android.bluetooth.*
import android.bluetooth.le.*
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import android.util.Log
import com.astralnetwork.sdk.core.AstralConfig
import com.astralnetwork.sdk.core.AstralError
import com.astralnetwork.sdk.identity.WalletID
import java.util.LinkedList
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * BLETransport — Android BLE implementation of AstralTransport.
 *
 * Security:
 * - Carries ENCRYPTED bytes only — never sees plaintext
 * - ACK/NACK gated on SDK verification (no blind ACK)
 * - Customer waits for merchant ACK before showing success
 *
 * Mirrors iOS BLETransport.
 */
@SuppressLint("MissingPermission")
class BLETransport(
    private val context: Context,
    private val config: AstralConfig = AstralConfig.DEFAULT
) : AstralTransport {

    companion object {
        private const val TAG = "AstralSDK"
    }

    override val kind = TransportKind.BLE
    override var isAvailable: Boolean = false
        private set

    override var onRawDataReceived: ((data: ByteArray, respond: (VerificationResult) -> Unit) -> Unit)? = null
    override var onStatusChanged: ((Boolean) -> Unit)? = null

    // UUIDs — must match iOS
    private val serviceUUID = UUID.fromString(config.bleServiceUUID)
    private val txCharUUID = UUID.fromString("0000A580-0000-1000-8000-00805F9B34FB")
    private val rxCharUUID = UUID.fromString("0000A581-0000-1000-8000-00805F9B34FB")
    private val announceCharUUID = UUID.fromString("0000A582-0000-1000-8000-00805F9B34FB")

    // BLE managers
    private var bluetoothManager: BluetoothManager? = null
    private var bluetoothAdapter: BluetoothAdapter? = null
    private var advertiser: BluetoothLeAdvertiser? = null
    private var scanner: BluetoothLeScanner? = null
    private var gattServer: BluetoothGattServer? = null
    private var connectedGatt: BluetoothGatt? = null

    // State
    private var myWalletID: WalletID? = null
    private val handler = Handler(Looper.getMainLooper())
    private val fragmenter = PacketFragmenter(config.bleFragmentSize)

    // Pending ACK — customer completion closures waiting for merchant response
    private val pendingACKs = ConcurrentHashMap<Int, PendingACK>()
    private val nextSendID = AtomicInteger(0)

    data class PendingACK(
        val completion: (Result<Unit>) -> Unit,
        val timeoutRunnable: Runnable
    )

    // BT state receiver — dynamically tracks Bluetooth on/off
    private val btStateReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(ctx: android.content.Context?, intent: android.content.Intent?) {
            if (intent?.action == BluetoothAdapter.ACTION_STATE_CHANGED) {
                val state = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR)
                val wasAvailable = isAvailable
                refreshBleState()
                if (isAvailable != wasAvailable) {
                    onStatusChanged?.invoke(isAvailable)
                    Log.d(TAG, "BLE state changed → available: $isAvailable")
                }
            }
        }
    }

    override fun start() {
        bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        bluetoothAdapter = bluetoothManager?.adapter

        // Register for BT state changes so isAvailable stays current
        val filter = android.content.IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED)
        context.registerReceiver(btStateReceiver, filter)

        refreshBleState()
        onStatusChanged?.invoke(isAvailable)
        Log.d(TAG, "BLETransport started — available: $isAvailable")
    }

    /** Re-check BLE adapter and scanner/advertiser availability. */
    private fun refreshBleState() {
        bluetoothAdapter = bluetoothManager?.adapter
        advertiser = bluetoothAdapter?.bluetoothLeAdvertiser
        scanner = bluetoothAdapter?.bluetoothLeScanner
        isAvailable = bluetoothAdapter?.isEnabled == true && scanner != null
    }

    override fun stop() {
        stopListening()
        connectedGatt?.disconnect()
        connectedGatt?.close()
        connectedGatt = null
        isAvailable = false
        try { context.unregisterReceiver(btStateReceiver) } catch (_: Exception) {}
    }

    // MARK: - Merchant Mode (Peripheral — receive payments)

    override fun startListening(walletID: WalletID) {
        myWalletID = walletID
        startGattServer()
        startAdvertising()
    }

    override fun stopListening() {
        advertiser?.stopAdvertising(advertiseCallback)
        gattServer?.close()
        gattServer = null
    }

    private fun startGattServer() {
        val server = bluetoothManager?.openGattServer(context, gattServerCallback) ?: return

        // TX characteristic — customer writes encrypted payment bytes here
        val txChar = BluetoothGattCharacteristic(
            txCharUUID,
            BluetoothGattCharacteristic.PROPERTY_WRITE,
            BluetoothGattCharacteristic.PERMISSION_WRITE
        )

        // RX characteristic — merchant sends ACK/NACK here (notify)
        val rxChar = BluetoothGattCharacteristic(
            rxCharUUID,
            BluetoothGattCharacteristic.PROPERTY_NOTIFY or BluetoothGattCharacteristic.PROPERTY_READ,
            BluetoothGattCharacteristic.PERMISSION_READ
        )
        // CRITICAL: Add Client Characteristic Configuration Descriptor (CCCD).
        // Without this, iOS CBPeripheral.setNotifyValue(true) silently fails
        // and the customer NEVER receives ACK/NACK notifications.
        val cccdDescriptor = BluetoothGattDescriptor(
            UUID.fromString("00002902-0000-1000-8000-00805f9b34fb"),
            BluetoothGattDescriptor.PERMISSION_READ or BluetoothGattDescriptor.PERMISSION_WRITE
        )
        rxChar.addDescriptor(cccdDescriptor)

        // Announce characteristic — broadcasts wallet ID
        val announceChar = BluetoothGattCharacteristic(
            announceCharUUID,
            BluetoothGattCharacteristic.PROPERTY_READ,
            BluetoothGattCharacteristic.PERMISSION_READ
        )
        announceChar.setValue(myWalletID?.id ?: "unknown")

        val service = BluetoothGattService(serviceUUID, BluetoothGattService.SERVICE_TYPE_PRIMARY)
        service.addCharacteristic(txChar)
        service.addCharacteristic(rxChar)
        service.addCharacteristic(announceChar)

        server.addService(service)
        gattServer = server
        Log.d(TAG, "GATT server started with Astral service")
    }

    private fun startAdvertising() {
        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
            .setConnectable(true)
            .setTimeout(0)
            .build()

        val data = AdvertiseData.Builder()
            .setIncludeDeviceName(false)
            .addServiceUuid(ParcelUuid(serviceUUID))
            .build()

        advertiser?.startAdvertising(settings, data, advertiseCallback)
    }

    private val advertiseCallback = object : AdvertiseCallback() {
        override fun onStartSuccess(settingsInEffect: AdvertiseSettings?) {
            Log.d(TAG, "BLE advertising started ✅")
        }

        override fun onStartFailure(errorCode: Int) {
            Log.e(TAG, "BLE advertising failed: $errorCode")
        }
    }

    private val gattServerCallback = object : BluetoothGattServerCallback() {
        override fun onCharacteristicWriteRequest(
            device: BluetoothDevice, requestId: Int,
            characteristic: BluetoothGattCharacteristic,
            preparedWrite: Boolean, responseNeeded: Boolean,
            offset: Int, value: ByteArray?
        ) {
            // Accept the ATT write (required by BLE protocol)
            if (responseNeeded) {
                gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, null)
            }

            if (characteristic.uuid == txCharUUID && value != null) {
                // Reassemble fragments — iOS uses PacketFragmenter which prepends
                // a 3-byte header [type][seq][total] to every BLE write.
                // We must strip these headers and reassemble multi-fragment packets.
                val completePacket = fragmenter.reassemble(value)
                if (completePacket != null) {
                    // Got a complete packet — forward to SDK for decrypt + verify
                    onRawDataReceived?.invoke(completePacket) { result ->
                        when (result) {
                            is VerificationResult.Verified -> sendACK(device)
                            is VerificationResult.Rejected -> sendNACK(device, result.error)
                        }
                    }
                }
                // If null, more fragments expected — wait
            }
        }

        override fun onConnectionStateChange(device: BluetoothDevice, status: Int, newState: Int) {
            Log.d(TAG, "GATT server connection: ${device.address} state=$newState")
        }
    }

    /** Send ACK (0x01) to customer via rxCharUUID notify. */
    private fun sendACK(device: BluetoothDevice) {
        val rxChar = gattServer?.getService(serviceUUID)?.getCharacteristic(rxCharUUID) ?: return
        rxChar.setValue(byteArrayOf(0x01))
        gattServer?.notifyCharacteristicChanged(device, rxChar, false)
        Log.d(TAG, "ACK sent to ${device.address} ✅")
    }

    /** Send NACK (0x00 + reason) to customer via rxCharUUID notify. */
    private fun sendNACK(device: BluetoothDevice, error: AstralError) {
        val reasonByte: Byte = when (error) {
            is AstralError.VerificationFailed -> 0x01
            is AstralError.Duplicate -> 0x02
            is AstralError.PacketDecodingFailed -> 0x03
            else -> 0xFF.toByte()
        }
        val rxChar = gattServer?.getService(serviceUUID)?.getCharacteristic(rxCharUUID) ?: return
        rxChar.setValue(byteArrayOf(0x00, reasonByte))
        gattServer?.notifyCharacteristicChanged(device, rxChar, false)
        Log.d(TAG, "NACK sent to ${device.address} — reason: $error ❌")
    }

    // MARK: - Customer Mode (Central — send payments)

    override fun send(packet: ByteArray, to: WalletID, completion: (Result<Unit>) -> Unit) {
        // Re-check BLE state — catches cases where BT was enabled after start()
        refreshBleState()

        val bleScanner = scanner ?: run {
            completion(Result.failure(AstralError.BleUnavailable))
            return
        }

        // Store completion — DO NOT call it yet.
        // It fires when merchant ACK/NACK arrives on rxCharUUID.
        val sendID = nextSendID.getAndIncrement()
        val timeoutRunnable = Runnable {
            pendingACKs.remove(sendID)?.completion?.invoke(
                Result.failure(AstralError.PaymentFailed("Merchant did not respond (timeout)"))
            )
        }
        pendingACKs[sendID] = PendingACK(completion, timeoutRunnable)
        handler.postDelayed(timeoutRunnable, config.paymentTimeoutMs)

        // Scan for merchant, connect, and write
        val scanFilter = ScanFilter.Builder()
            .setServiceUuid(ParcelUuid(serviceUUID))
            .build()

        val scanSettings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()

        val scanCallback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                bleScanner.stopScan(this)
                connectAndWrite(result.device, packet)
            }

            override fun onScanFailed(errorCode: Int) {
                Log.e(TAG, "BLE scan failed: $errorCode")
                resolveOldestPending(Result.failure(AstralError.BleUnavailable))
            }
        }

        bleScanner.startScan(listOf(scanFilter), scanSettings, scanCallback)

        // Stop scanning after timeout
        handler.postDelayed({ bleScanner.stopScan(scanCallback) }, config.bleScanTimeout)
    }

    private fun connectAndWrite(device: BluetoothDevice, data: ByteArray) {
        device.connectGatt(context, false, object : BluetoothGattCallback() {
            override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
                if (newState == BluetoothProfile.STATE_CONNECTED) {
                    connectedGatt = gatt
                    // Step 1: Request higher MTU BEFORE discovering services.
                    // Default MTU is 23 bytes (20 payload) — way too small for
                    // encrypted payment packets (200-500 bytes even after fragmentation).
                    // Must negotiate MTU first, then discover services in onMtuChanged.
                    Log.d(TAG, "Connected — requesting MTU 512...")
                    gatt.requestMtu(512)
                } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                    gatt.close()
                }
            }

            override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
                // Step 2: MTU negotiated — now discover services.
                // Even if MTU negotiation "fails", the OS will give us the best
                // available MTU. Proceed with service discovery regardless.
                Log.d(TAG, "MTU changed: $mtu (status=$status) — discovering services...")
                gatt.discoverServices()
            }

            override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
                // Step 3: Services discovered — find TX/RX characteristics.
                val service = gatt.getService(serviceUUID) ?: return
                val txChar = service.getCharacteristic(txCharUUID) ?: return
                val rxChar = service.getCharacteristic(rxCharUUID)

                if (rxChar != null) {
                    // Step 3a: Enable notifications locally on Android
                    gatt.setCharacteristicNotification(rxChar, true)

                    // Step 3b: Write ENABLE_NOTIFICATION_VALUE to the CCCD descriptor.
                    // THIS IS THE CRITICAL STEP. Android's setCharacteristicNotification()
                    // only enables notifications locally — it does NOT tell the iPhone.
                    // Without this write, iOS's subscribedCentrals list stays EMPTY,
                    // and the ACK/NACK notification goes into the void.
                    val cccdUuid = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
                    val descriptor = rxChar.getDescriptor(cccdUuid)

                    if (descriptor != null) {
                        descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                        gatt.writeDescriptor(descriptor)
                        Log.d(TAG, "Writing CCCD descriptor to subscribe to ACK notifications...")
                        // STOP HERE — do NOT call writeFragmented yet!
                        // Must wait for onDescriptorWrite before sending payment data.
                        // Android BLE allows only one GATT operation at a time.
                        return
                    } else {
                        Log.w(TAG, "CCCD descriptor not found on rxChar — proceeding without subscription")
                    }
                }

                // Fallback: no RX char or no descriptor — write immediately
                writeFragmented(data, gatt, txChar)
            }

            override fun onDescriptorWrite(gatt: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
                val cccdUuid = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
                if (descriptor.uuid == cccdUuid) {
                    if (status == BluetoothGatt.GATT_SUCCESS) {
                        // Step 4: CCCD write confirmed — iPhone now knows we're subscribed.
                        // NOW it's safe to send the payment data.
                        Log.d(TAG, "CCCD subscription confirmed by merchant ✅ — sending payment...")
                        val service = gatt.getService(serviceUUID) ?: return
                        val txChar = service.getCharacteristic(txCharUUID) ?: return
                        writeFragmented(data, gatt, txChar)
                    } else {
                        Log.e(TAG, "CCCD subscription failed (status=$status) — aborting payment")
                        writeQueue.clear()
                        resolveOldestPending(Result.failure(AstralError.BleUnavailable))
                    }
                }
            }

            override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
                if (characteristic.uuid == rxCharUUID) {
                    // Merchant ACK/NACK received!
                    val value = characteristic.value ?: return
                    handleMerchantResponse(value)
                }
            }

            override fun onCharacteristicWrite(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
                if (status != BluetoothGatt.GATT_SUCCESS) {
                    writeQueue.clear()
                    resolveOldestPending(Result.failure(AstralError.BleUnavailable))
                    return
                }
                // Write succeeded — send next fragment if any remain
                if (writeQueue.isNotEmpty()) {
                    writeNextFragment()
                }
                // If queue empty, all fragments sent — wait for merchant ACK
            }
        })
    }

    /**
     * Write data using PacketFragmenter — adds 3-byte headers matching iOS.
     *
     * iOS PacketFragmenter.swift expects:
     *   [type:1][seq:1][total:1][payload]
     * where type: 0x00=single, 0x01=start, 0x02=continue, 0x03=end
     *
     * Without these headers, iOS silently drops the data.
     *
     * IMPORTANT: Android BLE allows only ONE outstanding writeCharacteristic
     * at a time. You MUST wait for onCharacteristicWrite before sending the
     * next fragment. Firing multiple writes in a loop or with timers causes
     * silent data loss.
     */

    // Queue of pending fragments for sequential BLE writes
    private val writeQueue = LinkedList<ByteArray>()
    private var writeGatt: BluetoothGatt? = null
    private var writeChar: BluetoothGattCharacteristic? = null

    private fun writeFragmented(
        data: ByteArray,
        gatt: BluetoothGatt,
        characteristic: BluetoothGattCharacteristic
    ) {
        val fragments = fragmenter.fragment(data)
        writeGatt = gatt
        writeChar = characteristic

        // Queue all fragments
        writeQueue.clear()
        writeQueue.addAll(fragments)

        // Write the first fragment — subsequent ones fire from onCharacteristicWrite
        writeNextFragment()
    }

    /** Write the next queued fragment. Called from writeFragmented and onCharacteristicWrite. */
    private fun writeNextFragment() {
        val fragment = writeQueue.poll() ?: return  // Queue empty — all sent, wait for ACK
        val gatt = writeGatt ?: return
        val char = writeChar ?: return

        // Last fragment uses WRITE_TYPE_DEFAULT (withResponse) for reliability
        char.writeType = if (writeQueue.isEmpty()) {
            BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
        } else {
            BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT  // All with response for reliability
        }
        char.setValue(fragment)
        gatt.writeCharacteristic(char)
    }

    /** Parse ACK (0x01) or NACK (0x00+reason) and resolve pending completion. */
    private fun handleMerchantResponse(data: ByteArray) {
        if (data.isEmpty()) return

        if (data[0] == 0x01.toByte()) {
            resolveOldestPending(Result.success(Unit))
        } else {
            val reason = when {
                data.size >= 2 && data[1] == 0x01.toByte() -> "Signature verification failed"
                data.size >= 2 && data[1] == 0x02.toByte() -> "Double-spend detected"
                data.size >= 2 && data[1] == 0x03.toByte() -> "Packet decoding failed"
                else -> "Payment rejected by merchant"
            }
            resolveOldestPending(Result.failure(AstralError.PaymentFailed(reason)))
        }
    }

    /** Resolve the oldest pending completion (FIFO). */
    private fun resolveOldestPending(result: Result<Unit>) {
        val oldestKey = pendingACKs.keys().toList().minOrNull() ?: return
        val pending = pendingACKs.remove(oldestKey) ?: return
        handler.removeCallbacks(pending.timeoutRunnable)
        pending.completion(result)
    }

    override fun isReachable(recipientID: WalletID): Boolean = isAvailable
}
