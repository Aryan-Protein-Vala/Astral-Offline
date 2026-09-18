package com.astralnetwork.sdk.transport

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.p2p.WifiP2pManager
import android.util.Log
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket

/**
 * WiFiTransport — Dual-mode local WiFi transport.
 *
 * Mode 1: WiFi Direct (P2P) — for devices that are not on the same network.
 *         Two devices form their own ad-hoc WiFi group. No router needed.
 *
 * Mode 2: mDNS / NSD (Bonjour) — for devices on the SAME local WiFi network
 *         (e.g., Android ↔ MacBook, Android ↔ iPhone on same LAN).
 *         Uses Android NSD (Network Service Discovery) — cross-platform with
 *         Apple Bonjour and Linux Avahi.
 *
 * This enables Android ↔ iOS ↔ macOS ↔ Linux on the same WiFi network.
 * Combined with BLE (short range), this gives full cross-platform mesh coverage.
 */
class WiFiTransport(private val context: Context) {

    companion object {
        private const val TAG = "AstralSDK/WiFi"
        private const val SERVICE_TYPE = "_astral._tcp."    // mDNS service type
        private const val SERVICE_NAME = "AstralMeshNode"
        private const val PORT = 47823                       // Arbitrary fixed port
        const val WIFI_MTU = 65535                           // Full TCP stream — no fragmentation needed
    }

    private val nsdManager: NsdManager = context.getSystemService(Context.NSD_SERVICE) as NsdManager
    private var serverSocket: ServerSocket? = null
    private var serverThread: Thread? = null

    var onPacketReceived: ((ByteArray, InetAddress) -> Unit)? = null

    // ── mDNS Service Advertisement (same-WiFi discovery) ──────────────────────

    fun startNsdAdvertising() {
        val serviceInfo = NsdServiceInfo().apply {
            serviceName = SERVICE_NAME
            serviceType = SERVICE_TYPE
            port = PORT
        }

        nsdManager.registerService(serviceInfo, NsdManager.PROTOCOL_DNS_SD, registrationListener)
        startTcpServer()
        Log.d(TAG, "mDNS advertising started on port $PORT")
    }

    fun startNsdDiscovery() {
        nsdManager.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, discoveryListener)
        Log.d(TAG, "mDNS discovery started")
    }

    fun stopNsd() {
        try { nsdManager.unregisterService(registrationListener) } catch (_: Exception) {}
        try { nsdManager.stopServiceDiscovery(discoveryListener) } catch (_: Exception) {}
        serverSocket?.close()
        serverThread?.interrupt()
        Log.d(TAG, "mDNS stopped")
    }

    // ── TCP Server (receives packets from any platform on local WiFi) ──────────

    private fun startTcpServer() {
        serverSocket = ServerSocket(PORT)
        serverThread = Thread {
            Log.d(TAG, "TCP server listening on port $PORT")
            while (!Thread.currentThread().isInterrupted) {
                try {
                    val client = serverSocket?.accept() ?: break
                    Thread {
                        try {
                            val input = DataInputStream(client.getInputStream())
                            val length = input.readInt()            // 4-byte length prefix
                            val payload = ByteArray(length)
                            input.readFully(payload)
                            client.close()
                            Log.d(TAG, "Received $length bytes from ${client.inetAddress}")
                            onPacketReceived?.invoke(payload, client.inetAddress)
                        } catch (e: Exception) {
                            Log.w(TAG, "Client read error: ${e.message}")
                        }
                    }.start()
                } catch (e: Exception) {
                    if (!Thread.currentThread().isInterrupted) Log.w(TAG, "Accept error: ${e.message}")
                }
            }
        }.also { it.isDaemon = true; it.start() }
    }

    // ── TCP Client (sends packet to a discovered peer) ─────────────────────────

    fun sendPacket(host: InetAddress, payload: ByteArray, onResult: (Boolean) -> Unit) {
        Thread {
            try {
                val socket = Socket(host, PORT)
                val output = DataOutputStream(socket.getOutputStream())
                output.writeInt(payload.size)     // 4-byte length prefix
                output.write(payload)
                output.flush()
                socket.close()
                Log.d(TAG, "Sent ${payload.size} bytes to $host ✅")
                onResult(true)
            } catch (e: Exception) {
                Log.e(TAG, "Send failed to $host: ${e.message}")
                onResult(false)
            }
        }.start()
    }

    // ── mDNS Listeners ────────────────────────────────────────────────────────

    var onPeerDiscovered: ((NsdServiceInfo) -> Unit)? = null

    private val registrationListener = object : NsdManager.RegistrationListener {
        override fun onServiceRegistered(info: NsdServiceInfo) = Log.d(TAG, "mDNS registered: ${info.serviceName}")
        override fun onRegistrationFailed(info: NsdServiceInfo, error: Int) = Log.e(TAG, "mDNS registration failed: $error")
        override fun onServiceUnregistered(info: NsdServiceInfo) = Log.d(TAG, "mDNS unregistered")
        override fun onUnregistrationFailed(info: NsdServiceInfo, error: Int) = Log.e(TAG, "mDNS unregistration failed: $error")
    }

    private val discoveryListener = object : NsdManager.DiscoveryListener {
        override fun onDiscoveryStarted(regType: String) = Log.d(TAG, "mDNS discovery started")
        override fun onServiceFound(service: NsdServiceInfo) {
            if (service.serviceName.contains(SERVICE_NAME)) {
                nsdManager.resolveService(service, resolveListener)
            }
        }
        override fun onServiceLost(service: NsdServiceInfo) = Log.d(TAG, "mDNS service lost: ${service.serviceName}")
        override fun onDiscoveryStopped(serviceType: String) = Log.d(TAG, "mDNS discovery stopped")
        override fun onStartDiscoveryFailed(serviceType: String, error: Int) = Log.e(TAG, "mDNS discovery start failed: $error")
        override fun onStopDiscoveryFailed(serviceType: String, error: Int) = Log.e(TAG, "mDNS discovery stop failed: $error")
    }

    private val resolveListener = object : NsdManager.ResolveListener {
        override fun onResolveFailed(serviceInfo: NsdServiceInfo, error: Int) = Log.e(TAG, "Resolve failed: $error")
        override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
            Log.d(TAG, "Peer resolved: ${serviceInfo.host}:${serviceInfo.port}")
            onPeerDiscovered?.invoke(serviceInfo)
        }
    }
}
