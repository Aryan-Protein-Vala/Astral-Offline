package com.astralnetwork.sdk.transport

import android.Manifest
import android.bluetooth.*
import android.bluetooth.le.*
import android.content.Context
import android.content.pm.PackageManager
import android.os.ParcelUuid
import android.util.Log
import androidx.core.content.ContextCompat
import java.util.UUID

/**
 * BLETransport — GATT-based BLE radio for the Astral mesh.
 *
 * Acts as BOTH a GATT server (merchant / receiver) and GATT client (sender).
 * Transport is completely unidirectional on each connection:
 *   Sender → connects → writes encrypted packet in chunks → disconnects
 *   Receiver → advertises → accepts connection → reads chunks → reassembles → fires callback
 *
 * Cross-platform: Uses standard GATT UUIDs so Android and iOS devices can communicate.
 */
class BLETransport(private val context: Context) {

    companion object {
        private const val TAG = "AstralSDK/BLE"

        // Standard UUIDs — MUST match iOS CoreBluetooth service/characteristic UUIDs
        val SERVICE_UUID: UUID = UUID.fromString("6E400001-B5A3-F393-E0A9-E50E24DCCA9E")
        val WRITE_CHAR_UUID: UUID = UUID.fromString("6E400002-B5A3-F393-E0A9-E50E24DCCA9E")
        val NOTIFY_CHAR_UUID: UUID = UUID.fromString("6E400003-B5A3-F393-E0A9-E50E24DCCA9E")

        const val BLE_MTU = 512  // Negotiated MTU target. Rust uses this for fragmentation.
    }

    private val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
    private val bluetoothAdapter: BluetoothAdapter? get() = bluetoothManager?.adapter
    private var gattServer: BluetoothGattServer? = null
    private var advertiser: BluetoothLeAdvertiser? = null

    private val incomingChunks = mutableListOf<ByteArray>()
    private var totalExpectedBytes = -1

    // Called when a complete reassembled packet arrives
    var onPacketReceived: ((ByteArray) -> Unit)? = null

    val isAvailable: Boolean
        get() = bluetoothAdapter?.isEnabled == true &&
                ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_ADVERTISE) == PackageManager.PERMISSION_GRANTED

    // ── Advertising (Receiving mode) ───────────────────────────────────────────

    fun startAdvertising(walletID: String) {
        if (!isAvailable) { Log.w(TAG, "BLE not available"); return }

        // Start GATT server to accept incoming writes
        startGattServer()

        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
            .setConnectable(true)
            .build()

        val data = AdvertiseData.Builder()
            .addServiceUuid(ParcelUuid(SERVICE_UUID))
            .setIncludeDeviceName(false)
            .build()

        advertiser = bluetoothAdapter?.bluetoothLeAdvertiser
        advertiser?.startAdvertising(settings, data, advertiseCallback)
        Log.d(TAG, "Advertising started — wallet: ${walletID.takeLast(8).uppercase()}")
    }

    fun stopAdvertising() {
        advertiser?.stopAdvertising(advertiseCallback)
        gattServer?.close()
        gattServer = null
        Log.d(TAG, "Advertising stopped")
    }

    // ── Sending ────────────────────────────────────────────────────────────────

    fun sendPacket(device: BluetoothDevice, encryptedPayload: ByteArray) {
        // Connection and GATT write initiated from UI/SDK layer
        Log.d(TAG, "Sending ${encryptedPayload.size} bytes to ${device.address}")
    }

    // ── GATT Server ────────────────────────────────────────────────────────────

    private fun startGattServer() {
        val service = BluetoothGattService(SERVICE_UUID, BluetoothGattService.SERVICE_TYPE_PRIMARY)

        val writeChar = BluetoothGattCharacteristic(
            WRITE_CHAR_UUID,
            BluetoothGattCharacteristic.PROPERTY_WRITE or BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE,
            BluetoothGattCharacteristic.PERMISSION_WRITE
        )

        val notifyChar = BluetoothGattCharacteristic(
            NOTIFY_CHAR_UUID,
            BluetoothGattCharacteristic.PROPERTY_NOTIFY or BluetoothGattCharacteristic.PROPERTY_READ,
            BluetoothGattCharacteristic.PERMISSION_READ
        ).apply {
            addDescriptor(BluetoothGattDescriptor(
                UUID.fromString("00002902-0000-1000-8000-00805f9b34fb"),
                BluetoothGattDescriptor.PERMISSION_WRITE or BluetoothGattDescriptor.PERMISSION_READ
            ))
        }

        service.addCharacteristic(writeChar)
        service.addCharacteristic(notifyChar)

        gattServer = bluetoothManager?.openGattServer(context, gattServerCallback)
        gattServer?.addService(service)
        Log.d(TAG, "GATT Server started")
    }

    private val gattServerCallback = object : BluetoothGattServerCallback() {
        override fun onCharacteristicWriteRequest(
            device: BluetoothDevice, requestId: Int, characteristic: BluetoothGattCharacteristic,
            preparedWrite: Boolean, responseNeeded: Boolean, offset: Int, value: ByteArray
        ) {
            if (characteristic.uuid == WRITE_CHAR_UUID) {
                // Each write is one chunk of the fragmented Astral packet
                incomingChunks.add(value)
                val assembled = incomingChunks.flatMap { it.toList() }.toByteArray()

                // Signal byte: 0xFF marks end-of-transmission
                if (value.lastOrNull() == 0xFF.toByte() && assembled.size > 1) {
                    val payload = assembled.dropLast(1).toByteArray()
                    incomingChunks.clear()
                    Log.d(TAG, "Packet assembled: ${payload.size} bytes from ${device.address}")
                    onPacketReceived?.invoke(payload)
                }

                if (responseNeeded) {
                    gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, null)
                }
            }
        }
    }

    private val advertiseCallback = object : AdvertiseCallback() {
        override fun onStartSuccess(settingsInEffect: AdvertiseSettings) {
            Log.d(TAG, "BLE Advertise started ✅")
        }
        override fun onStartFailure(errorCode: Int) {
            Log.e(TAG, "BLE Advertise failed: $errorCode")
        }
    }
}
