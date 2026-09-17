package com.astralnetwork.poc.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import com.astralnetwork.poc.wallet.AstralWalletApp
import com.astralnetwork.sdk.core.AstralSDK
import com.astralnetwork.sdk.transport.BLETransport

/**
 * MainActivity — Entry point for the Astral Network Android app.
 *
 * Initializes the AstralSDK with BLETransport and passes it to the wallet UI.
 * All key management, encryption, BLE transport, and payment logic
 * is handled by the SDK — this Activity just wires permissions and starts it.
 */
class MainActivity : ComponentActivity() {

    private lateinit var sdk: AstralSDK
    private lateinit var bleTransport: BLETransport

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val allGranted = permissions.values.all { it }
        Log.d("ASTRAL_NET", "Permissions granted: $allGranted")
        if (allGranted) {
            initializeSDK()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 1. Create SDK and BLE transport
        sdk = AstralSDK(context = this)
        bleTransport = BLETransport(this)
        sdk.registerTransport(bleTransport)

        // 2. Request permissions, then init SDK
        if (hasPermissions()) {
            initializeSDK()
        } else {
            requestPermissions()
        }

        // 3. Set UI content — pass SDK instance to wallet
        setContent {
            AstralWalletApp(sdk = sdk)
        }
    }

    private fun initializeSDK() {
        try {
            sdk.start()
            Log.d("ASTRAL_NET", "✅ AstralSDK started — wallet: ${sdk.walletID?.short}")
            Log.d("ASTRAL_NET", "🔐 Hardware-backed: ${sdk.isHardwareBacked}")
        } catch (e: Exception) {
            Log.e("ASTRAL_NET", "❌ AstralSDK init failed: ${e.message}", e)
        }
    }

    private fun hasPermissions(): Boolean {
        val permissions = getRequiredPermissions()
        return permissions.all {
            ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
        }
    }

    private fun requestPermissions() {
        permissionLauncher.launch(getRequiredPermissions())
    }

    private fun getRequiredPermissions(): Array<String> {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_ADVERTISE,
                Manifest.permission.BLUETOOTH_CONNECT,
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.CAMERA
            )
        } else {
            arrayOf(
                Manifest.permission.BLUETOOTH,
                Manifest.permission.BLUETOOTH_ADMIN,
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.CAMERA
            )
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        sdk.stop()
    }
}