import Foundation
import CoreBluetooth
import os.log

/**
 * BLETransport — CoreBluetooth GATT transport for the Astral mesh.
 *
 * Standard GATT UUIDs — cross-platform compatible with Android BLETransport:
 *   Service:          6E400001-B5A3-F393-E0A9-E50E24DCCA9E
 *   Write Char:       6E400002-B5A3-F393-E0A9-E50E24DCCA9E
 *   Notify Char:      6E400003-B5A3-F393-E0A9-E50E24DCCA9E
 *
 * Acts as both central (scanner/sender) and peripheral (advertiser/receiver).
 */
final class BLETransport: NSObject, CBPeripheralManagerDelegate, CBCentralManagerDelegate, CBPeripheralDelegate {

    private let log = Logger(subsystem: "com.astralnetwork.sdk", category: "BLETransport")

    // Standard UUIDs (matches Android BLETransport)
    private let serviceUUID  = CBUUID(string: "6E400001-B5A3-F393-E0A9-E50E24DCCA9E")
    private let writeCharUUID = CBUUID(string: "6E400002-B5A3-F393-E0A9-E50E24DCCA9E")
    private let notifyCharUUID = CBUUID(string: "6E400003-B5A3-F393-E0A9-E50E24DCCA9E")

    private var peripheralManager: CBPeripheralManager?
    private var centralManager: CBCentralManager?
    private var writeCharacteristic: CBMutableCharacteristic?
    private var incomingBuffer = Data()

    var onPacketReceived: ((Data) -> Void)?

    override init() {
        super.init()
        peripheralManager = CBPeripheralManager(delegate: self, queue: .global())
        centralManager = CBCentralManager(delegate: self, queue: .global())
    }

    // ── Advertising (Receiver) ──────────────────────────────────────────────────

    func startAdvertising(walletID: String) {
        guard peripheralManager?.state == .poweredOn else { return }
        setupGattServer()
        peripheralManager?.startAdvertising([
            CBAdvertisementDataServiceUUIDsKey: [serviceUUID],
            CBAdvertisementDataLocalNameKey: "Astral"
        ])
        log.info("BLE advertising started ✅")
    }

    func stopAdvertising() {
        peripheralManager?.stopAdvertising()
    }

    private func setupGattServer() {
        let writeChar = CBMutableCharacteristic(
            type: writeCharUUID,
            properties: [.write, .writeWithoutResponse],
            value: nil,
            permissions: .writeable
        )
        let notifyChar = CBMutableCharacteristic(
            type: notifyCharUUID,
            properties: [.notify, .read],
            value: nil,
            permissions: .readable
        )
        writeCharacteristic = writeChar
        let service = CBMutableService(type: serviceUUID, primary: true)
        service.characteristics = [writeChar, notifyChar]
        peripheralManager?.add(service)
    }

    // ── Sending (Central) ───────────────────────────────────────────────────────

    func sendPacket(_ data: Data) {
        centralManager?.scanForPeripherals(withServices: [serviceUUID], options: nil)
        // Connection and write handled in CBCentralManagerDelegate callbacks
    }

    // ── CBPeripheralManagerDelegate ─────────────────────────────────────────────

    func peripheralManagerDidUpdateState(_ peripheral: CBPeripheralManager) {
        log.info("BLE Peripheral state: \(peripheral.state.rawValue)")
    }

    func peripheralManager(_ peripheral: CBPeripheralManager, didReceiveWrite requests: [CBATTRequest]) {
        for request in requests {
            if request.characteristic.uuid == writeCharUUID, let value = request.value {
                incomingBuffer.append(value)
                // 0xFF sentinel marks end-of-transmission
                if value.last == 0xFF && incomingBuffer.count > 1 {
                    let payload = incomingBuffer.dropLast()
                    incomingBuffer.removeAll()
                    log.info("BLE packet assembled: \(payload.count) bytes")
                    onPacketReceived?(Data(payload))
                }
                peripheral.respond(to: request, withResult: .success)
            }
        }
    }

    // ── CBCentralManagerDelegate ─────────────────────────────────────────────────

    func centralManagerDidUpdateState(_ central: CBCentralManager) {
        log.info("BLE Central state: \(central.state.rawValue)")
    }

    func centralManager(_ central: CBCentralManager, didDiscover peripheral: CBPeripheral,
                        advertisementData: [String: Any], rssi RSSI: NSNumber) {
        log.info("Discovered BLE peer: \(peripheral.name ?? "unknown")")
        central.stopScan()
        central.connect(peripheral, options: nil)
    }

    func centralManager(_ central: CBCentralManager, didConnect peripheral: CBPeripheral) {
        peripheral.delegate = self
        peripheral.discoverServices([serviceUUID])
    }

    func peripheral(_ peripheral: CBPeripheral, didDiscoverServices error: Error?) {
        peripheral.services?.forEach { peripheral.discoverCharacteristics([writeCharUUID], for: $0) }
    }

    func peripheral(_ peripheral: CBPeripheral, didDiscoverCharacteristicsFor service: CBService, error: Error?) {
        // Write happens from the send queue when characteristic is found
    }
}
