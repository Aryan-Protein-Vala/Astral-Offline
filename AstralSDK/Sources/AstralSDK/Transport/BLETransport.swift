import Foundation
import CoreBluetooth
import Combine

/// BLETransport — CoreBluetooth implementation of AstralTransport.
///
/// Security:
/// - Carries ENCRYPTED bytes only — never sees plaintext transactions
/// - Does NOT auto-ACK. Calls `onRawDataReceived` and waits for SDK
///   to verify before sending acknowledgment
/// - Uses `PacketFragmenter` for packets > BLE MTU
public final class BLETransport: NSObject, AstralTransport, ObservableObject {

    // MARK: - AstralTransport Protocol

    public let kind: TransportKind = .ble

    @Published public var isAvailable: Bool = false

    public var onRawDataReceived: ((_ data: Data, _ respond: @escaping (VerificationResult) -> Void) -> Void)?
    public var onStatusChanged: ((Bool) -> Void)?

    // MARK: - Constants

    private let serviceUUID: CBUUID
    private let txCharUUID = CBUUID(string: "0000A580-0000-1000-8000-00805F9B34FB")
    private let rxCharUUID = CBUUID(string: "0000A581-0000-1000-8000-00805F9B34FB")
    private let announceCharUUID = CBUUID(string: "0000A582-0000-1000-8000-00805F9B34FB")

    // MARK: - BLE Managers

    private var centralManager: CBCentralManager!
    private var peripheralManager: CBPeripheralManager!

    // MARK: - State

    private let config: AstralConfig
    private var myWalletID: WalletID?

    // Central state
    private var discoveredPeripherals: [String: DiscoveredMerchant] = [:]
    private var connectedPeripheral: CBPeripheral?
    private var txCharacteristic: CBCharacteristic?

    // Peripheral state
    private var txCharacteristicMutable: CBMutableCharacteristic?
    private var rxCharacteristicMutable: CBMutableCharacteristic?
    private var announceCharacteristicMutable: CBMutableCharacteristic?
    private var subscribedCentrals: [CBCentral] = []

    // Fragmentation
    private var fragmenter: PacketFragmenter

    // Pending sends (before connection established)
    private var pendingSends: [(Data, WalletID, (Result<Void, AstralError>) -> Void)] = []

    // Pending ACK — completion closures waiting for merchant response.
    // Key = incrementing send ID. Value = (completion handler, timeout timer).
    // Completion is ONLY called when merchant sends ACK/NACK on rxCharUUID.
    private var pendingACKs: [UInt16: (completion: (Result<Void, AstralError>) -> Void, timer: DispatchWorkItem)] = [:]
    private var nextSendID: UInt16 = 0

    // Queue
    private let bleQueue = DispatchQueue(label: "com.astral.ble", qos: .userInitiated)

    // MARK: - Types

    struct DiscoveredMerchant {
        let peripheral: CBPeripheral
        let walletID: WalletID?
        let rssi: Int
        let lastSeen: Date
    }

    // MARK: - Init

    public init(config: AstralConfig = .default) {
        self.config = config
        self.serviceUUID = CBUUID(string: config.bleServiceUUID)
        self.fragmenter = PacketFragmenter(mtu: config.bleFragmentSize)
        super.init()
    }

    // MARK: - AstralTransport Lifecycle

    public func start() {
        centralManager = CBCentralManager(delegate: self, queue: bleQueue, options: [
            CBCentralManagerOptionRestoreIdentifierKey: "com.astral.central"
        ])
    }

    public func stop() {
        centralManager?.stopScan()
        if let peripheral = connectedPeripheral {
            centralManager?.cancelPeripheralConnection(peripheral)
        }
        peripheralManager?.stopAdvertising()
        isAvailable = false
        onStatusChanged?(false)
    }

    // MARK: - Sending (encrypted bytes)

    public func send(
        packet: Data,
        to recipientID: WalletID,
        completion: @escaping (Result<Void, AstralError>) -> Void
    ) {
        if let characteristic = txCharacteristic,
           let peripheral = connectedPeripheral,
           peripheral.state == .connected {
            writeFragmented(data: packet, to: peripheral, characteristic: characteristic, completion: completion)
            return
        }

        pendingSends.append((packet, recipientID, completion))
        startScanning()
    }

    public func isReachable(_ recipientID: WalletID) -> Bool {
        discoveredPeripherals.values.contains { merchant in
            merchant.walletID == recipientID && merchant.peripheral.state == .connected
        }
    }

    // MARK: - Merchant Listening

    public func startListening(as walletID: WalletID) {
        myWalletID = walletID
        peripheralManager = CBPeripheralManager(delegate: self, queue: bleQueue, options: [
            CBPeripheralManagerOptionRestoreIdentifierKey: "com.astral.peripheral"
        ])
    }

    public func stopListening() {
        peripheralManager?.stopAdvertising()
        peripheralManager?.removeAllServices()
    }

    // MARK: - Scanning

    private func startScanning() {
        guard centralManager?.state == .poweredOn else { return }
        centralManager.scanForPeripherals(
            withServices: [serviceUUID],
            options: [CBCentralManagerScanOptionAllowDuplicatesKey: false]
        )
    }

    // MARK: - Advertising

    private func setupPeripheralServices() {
        txCharacteristicMutable = CBMutableCharacteristic(
            type: txCharUUID,
            properties: [.write],  // .write ONLY — requires response (no writeWithoutResponse)
            value: nil,
            permissions: [.writeable]
        )

        rxCharacteristicMutable = CBMutableCharacteristic(
            type: rxCharUUID,
            properties: [.notify, .read],
            value: nil,
            permissions: [.readable]
        )

        let announceData = myWalletID?.id.data(using: .utf8)
        announceCharacteristicMutable = CBMutableCharacteristic(
            type: announceCharUUID,
            properties: [.read],
            value: announceData,
            permissions: [.readable]
        )

        let service = CBMutableService(type: serviceUUID, primary: true)
        service.characteristics = [
            txCharacteristicMutable!,
            rxCharacteristicMutable!,
            announceCharacteristicMutable!
        ]

        peripheralManager.add(service)
    }

    private func startAdvertising() {
        peripheralManager.startAdvertising([
            CBAdvertisementDataServiceUUIDsKey: [serviceUUID],
            CBAdvertisementDataLocalNameKey: "Astral"
        ])
    }

    // MARK: - Fragmentation

    private func writeFragmented(
        data: Data,
        to peripheral: CBPeripheral,
        characteristic: CBCharacteristic,
        completion: @escaping (Result<Void, AstralError>) -> Void
    ) {
        let fragments = fragmenter.fragment(data)

        // Store completion — DO NOT call it yet.
        // It will fire when merchant sends ACK/NACK on rxCharUUID.
        let sendID = nextSendID
        nextSendID &+= 1

        // Timeout: if merchant doesn't respond within 10s, fail
        let timeout = DispatchWorkItem { [weak self] in
            guard let self = self else { return }
            if let pending = self.pendingACKs.removeValue(forKey: sendID) {
                pending.completion(.failure(.paymentFailed("Merchant did not respond (timeout)")))
            }
        }
        pendingACKs[sendID] = (completion: completion, timer: timeout)
        bleQueue.asyncAfter(deadline: .now() + config.paymentTimeoutSeconds, execute: timeout)

        // Write all fragments
        if fragments.count == 1 {
            peripheral.writeValue(fragments[0], for: characteristic, type: .withResponse)
            return  // Wait for merchant ACK — DO NOT call completion here
        }

        for (index, fragment) in fragments.enumerated() {
            let delay = TimeInterval(index) * TimeInterval(config.bleFragmentSpacingMs) / 1000.0
            bleQueue.asyncAfter(deadline: .now() + delay) {
                let writeType: CBCharacteristicWriteType = (index == fragments.count - 1) ? .withResponse : .withoutResponse
                peripheral.writeValue(fragment, for: characteristic, type: writeType)
                // DO NOT call completion here — wait for merchant ACK
            }
        }
    }

    // MARK: - ACK/NACK Sending

    /// Send application-level ACK to customer via BLE notify.
    private func sendACK() {
        guard let rxChar = rxCharacteristicMutable else { return }
        let ackData = Data([0x01])  // 0x01 = payment verified + accepted
        peripheralManager?.updateValue(ackData, for: rxChar, onSubscribedCentrals: nil)
    }

    /// Send application-level NACK to customer via BLE notify.
    private func sendNACK(error: AstralError) {
        guard let rxChar = rxCharacteristicMutable else { return }
        let reasonByte: UInt8
        switch error {
        case .verificationFailed: reasonByte = 0x01
        case .duplicate:          reasonByte = 0x02
        case .packetDecodingFailed: reasonByte = 0x03
        default:                  reasonByte = 0xFF
        }
        let nackData = Data([0x00, reasonByte])  // 0x00 = rejected
        peripheralManager?.updateValue(nackData, for: rxChar, onSubscribedCentrals: nil)
    }
}

// MARK: - CBCentralManagerDelegate

extension BLETransport: CBCentralManagerDelegate {

    public func centralManagerDidUpdateState(_ central: CBCentralManager) {
        let available = central.state == .poweredOn
        DispatchQueue.main.async { [weak self] in
            self?.isAvailable = available
            self?.onStatusChanged?(available)
        }

        if available && !pendingSends.isEmpty {
            startScanning()
        }
    }

    public func centralManager(
        _ central: CBCentralManager,
        didDiscover peripheral: CBPeripheral,
        advertisementData: [String: Any],
        rssi RSSI: NSNumber
    ) {
        let id = peripheral.identifier.uuidString
        discoveredPeripherals[id] = DiscoveredMerchant(
            peripheral: peripheral,
            walletID: nil,
            rssi: RSSI.intValue,
            lastSeen: Date()
        )

        if !pendingSends.isEmpty && connectedPeripheral == nil {
            connectedPeripheral = peripheral
            peripheral.delegate = self
            central.connect(peripheral, options: nil)
        }
    }

    public func centralManager(_ central: CBCentralManager, didConnect peripheral: CBPeripheral) {
        central.stopScan()
        peripheral.discoverServices([serviceUUID])
    }

    public func centralManager(_ central: CBCentralManager, didFailToConnect peripheral: CBPeripheral, error: Error?) {
        connectedPeripheral = nil
        let sends = pendingSends
        pendingSends.removeAll()
        for (_, _, completion) in sends {
            completion(.failure(.bleUnavailable))
        }
    }

    public func centralManager(_ central: CBCentralManager, didDisconnectPeripheral peripheral: CBPeripheral, error: Error?) {
        connectedPeripheral = nil
        txCharacteristic = nil
    }

    public func centralManager(_ central: CBCentralManager, willRestoreState dict: [String: Any]) {}
}

// MARK: - CBPeripheralDelegate

extension BLETransport: CBPeripheralDelegate {

    public func peripheral(_ peripheral: CBPeripheral, didDiscoverServices error: Error?) {
        guard let services = peripheral.services else { return }
        for service in services where service.uuid == serviceUUID {
            peripheral.discoverCharacteristics([txCharUUID, rxCharUUID, announceCharUUID], for: service)
        }
    }

    public func peripheral(_ peripheral: CBPeripheral, didDiscoverCharacteristicsFor service: CBService, error: Error?) {
        guard let characteristics = service.characteristics else { return }

        for characteristic in characteristics {
            switch characteristic.uuid {
            case txCharUUID:
                txCharacteristic = characteristic
                flushPendingSends(to: peripheral)
            case rxCharUUID:
                peripheral.setNotifyValue(true, for: characteristic)
            case announceCharUUID:
                peripheral.readValue(for: characteristic)
            default:
                break
            }
        }
    }

    public func peripheral(_ peripheral: CBPeripheral, didUpdateValueFor characteristic: CBCharacteristic, error: Error?) {
        guard let data = characteristic.value else { return }

        if characteristic.uuid == announceCharUUID {
            if let idStr = String(data: data, encoding: .utf8) {
                let id = peripheral.identifier.uuidString
                discoveredPeripherals[id] = DiscoveredMerchant(
                    peripheral: peripheral,
                    walletID: WalletID(id: idStr),
                    rssi: discoveredPeripherals[id]?.rssi ?? -100,
                    lastSeen: Date()
                )
            }
        } else if characteristic.uuid == rxCharUUID {
            // Merchant ACK/NACK received! Resolve the pending completion.
            //
            // Protocol:
            //   [0x01]       = ACK (payment verified + accepted)
            //   [0x00][reason] = NACK (payment rejected)
            //
            // This is the ONLY place where the customer's completion fires.
            handleMerchantResponse(data)
        }
    }

    public func peripheral(_ peripheral: CBPeripheral, didWriteValueFor characteristic: CBCharacteristic, error: Error?) {
        if let error = error {
            print("[AstralBLE] Write error: \(error.localizedDescription)")
            // If the write itself failed, resolve the oldest pending ACK as failure
            resolveOldestPending(with: .failure(.bleUnavailable))
        }
    }

    /// Handle merchant's ACK/NACK response received on rxCharUUID.
    ///
    /// This is the ONLY place where the customer's completion fires.
    /// Protocol: [0x01] = verified+accepted, [0x00][reason] = rejected.
    private func handleMerchantResponse(_ data: Data) {
        guard !data.isEmpty else { return }

        let statusByte = data[0]

        if statusByte == 0x01 {
            // ACK — merchant verified and accepted the payment
            resolveOldestPending(with: .success(()))
        } else {
            // NACK — merchant rejected (bad sig, double-spend, etc.)
            let reason: String
            if data.count >= 2 {
                switch data[1] {
                case 0x01: reason = "Signature verification failed"
                case 0x02: reason = "Double-spend detected"
                case 0x03: reason = "Packet decoding failed"
                default:   reason = "Unknown rejection (0x\(String(format: "%02x", data[1])))"
                }
            } else {
                reason = "Payment rejected by merchant"
            }
            resolveOldestPending(with: .failure(.paymentFailed(reason)))
        }
    }

    /// Resolve the oldest pending ACK completion (FIFO order).
    private func resolveOldestPending(with result: Result<Void, AstralError>) {
        // Find the oldest (lowest sendID) pending ACK
        guard let oldestKey = pendingACKs.keys.sorted().first,
              let pending = pendingACKs.removeValue(forKey: oldestKey) else {
            return
        }
        // Cancel the timeout timer
        pending.timer.cancel()
        // Fire the completion — THIS is when the customer's UI updates
        pending.completion(result)
    }

    private func flushPendingSends(to peripheral: CBPeripheral) {
        guard let characteristic = txCharacteristic else { return }
        let sends = pendingSends
        pendingSends.removeAll()

        for (packet, _, completion) in sends {
            writeFragmented(data: packet, to: peripheral, characteristic: characteristic, completion: completion)
        }
    }
}

// MARK: - CBPeripheralManagerDelegate (Merchant — Receive Side)

extension BLETransport: CBPeripheralManagerDelegate {

    public func peripheralManagerDidUpdateState(_ peripheral: CBPeripheralManager) {
        if peripheral.state == .poweredOn {
            setupPeripheralServices()
        }
    }

    public func peripheralManager(_ peripheral: CBPeripheralManager, didAdd service: CBService, error: Error?) {
        if error == nil {
            startAdvertising()
        }
    }

    /// ⚠️ CRITICAL: This is where the old code blind-ACK'd.
    /// NOW: We accept the write (ATT level), forward raw bytes to SDK for
    /// verification, and only send application-level ACK/NACK after SDK responds.
    public func peripheralManager(_ peripheral: CBPeripheralManager, didReceiveWrite requests: [CBATTRequest]) {
        for request in requests {
            if request.characteristic.uuid == txCharUUID, let data = request.value {
                // Accept the ATT write (required by CoreBluetooth protocol)
                peripheral.respond(to: request, withResult: .success)

                // Reassemble fragments if needed
                if let reassembled = fragmenter.reassemble(data) {
                    // Forward raw ENCRYPTED bytes to SDK WITH a respond callback.
                    // SDK runs: decrypt → verify sig → dedup check.
                    // Then calls respond(.verified) or respond(.rejected(error)).
                    // We use that to send ACK or NACK back to the customer's phone.
                    onRawDataReceived?(reassembled) { [weak self] result in
                        switch result {
                        case .verified:
                            self?.sendACK()
                        case .rejected(let error):
                            self?.sendNACK(error: error)
                        }
                    }
                }
            } else {
                peripheral.respond(to: request, withResult: .requestNotSupported)
            }
        }
    }

    public func peripheralManager(_ peripheral: CBPeripheralManager, central: CBCentral, didSubscribeTo characteristic: CBCharacteristic) {
        subscribedCentrals.append(central)
    }

    public func peripheralManager(_ peripheral: CBPeripheralManager, central: CBCentral, didUnsubscribeFrom characteristic: CBCharacteristic) {
        subscribedCentrals.removeAll { $0.identifier == central.identifier }
    }

    public func peripheralManager(_ peripheral: CBPeripheralManager, willRestoreState dict: [String: Any]) {}
}
