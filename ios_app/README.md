# iOS App - Astral Network

Native SwiftUI iOS implementation of the Astral Network node.

## 📁 File Structure

```
ios_app/
├── AstralNetworkApp.swift      # Main app entry point
├── ContentView.swift            # UI with Broadcast/Scan tabs
├── BLEManager.swift             # CoreBluetooth BLE communication
└── SecurityManager.swift        # Secure Enclave P-256 cryptography
```

## 🔐 Security Implementation

**SecurityManager.swift** handles all cryptographic operations:
- **P-256 Key Generation**: Creates private keys in the Secure Enclave
- **Keychain Persistence**: Stores key references securely
- **Signing**: Signs data using the Secure Enclave private key
- **Verification**: Verifies signatures from other nodes using public keys

Key features:
- ✅ Hardware-backed security (Secure Enclave)
- ✅ Keys never leave the secure hardware
- ✅ ANSI x9.63 public key representation
- ✅ Raw ECDSA signature format

## 📡 BLE Communication

**BLEManager.swift** implements dual-mode BLE:

### Scanner Mode (Central)
- Scans for Service UUID: `0000A57F-0000-1000-8000-00805F9B34FB`
- **Detects Android Extended Advertising**: Reads public key + signature directly from advertisement data
- **Detects iOS Legacy Advertising**: Connects and reads characteristic value
- Verifies signatures automatically
- Compatible with both Android and iOS nodes

### Broadcaster Mode (Peripheral)
- Advertises using **Legacy Mode** (iOS compatibility)
- Creates a readable characteristic with UUID: `0000A580-0000-1000-8000-00805F9B34FB`
- When read by a central, returns: `PublicKey (65 bytes) + Signature (64 bytes)`
- Total payload: 129 bytes

## 🎨 User Interface

**ContentView.swift** provides a clean, native iOS experience:

### Broadcast Tab
- Shows security status
- Start/Stop broadcasting button
- Animated broadcasting indicator
- Status messages

### Scan Tab
- Lists discovered nodes
- Shows node platform (Android Extended / iOS Legacy)
- Displays verification status
- Shows RSSI signal strength
- Public key preview

## 🔄 Android ↔ iOS Communication

### How it Works

1. **Android (Extended Advertising) → iOS (Scanner)**
   - Android broadcasts public key in advertisement data
   - iOS reads the advertisement data directly
   - iOS verifies the signature
   - Node appears in the scan list as "Android (Extended)"

2. **iOS (Legacy Advertising) → Android (Scanner)**
   - iOS advertises the service UUID
   - Android connects and reads the characteristic
   - iOS returns public key + signature
   - Node appears on Android as verified

3. **iOS ↔ iOS Communication**
   - Both use Legacy Advertising
   - Connect to read characteristics
   - Exchange public keys via GATT

## 🚀 Usage

### To Create an Xcode Project

1. Open Xcode
2. Create a new **iOS App** project:
   - Product Name: `Astral Network`
   - Interface: **SwiftUI**
   - Language: **Swift**
   - Organization Identifier: `com.astralnetwork`

3. Replace the default files with the files from this folder:
   - Replace `AstralNetworkApp.swift` (Xcode creates this by default)
   - Replace `ContentView.swift` (Xcode creates this by default)
   - Add `BLEManager.swift` (create new file)
   - Add `SecurityManager.swift` (create new file)

4. Configure Info.plist:
   ```xml
   <key>NSBluetoothAlwaysUsageDescription</key>
   <string>Astral Network uses Bluetooth to discover and communicate with nearby nodes securely.</string>
   <key>NSBluetoothPeripheralUsageDescription</key>
   <string>Astral Network needs Bluetooth to broadcast your secure identity to nearby devices.</string>
   ```

5. Enable "Background Modes" capability:
   - Uses Bluetooth LE accessories
   - Acts as a Bluetooth LE accessory

6. Build and run on a physical device (BLE requires real hardware)

### Testing with Android

1. **Start Android node** in "Extended Advertising" mode
2. **Start iOS app** and go to "Scan" tab
3. **Tap "Start Scan"**
4. Android node should appear as "Android (Extended)"
5. Verify the signature is validated ✓

### Testing iOS to iOS

1. **Device A**: Go to "Broadcast" tab → Start Broadcasting
2. **Device B**: Go to "Scan" tab → Start Scan
3. Device A should appear on Device B as "iOS (Legacy)"

## 🔧 Technical Notes

### UUID Matching
The Service UUID **MUST** match the Android implementation:
```swift
let serviceUUID = CBUUID(string: "0000A57F-0000-1000-8000-00805F9B34FB")
```

### Extended Advertising Handling
iOS can **receive** Extended Advertising packets from Android but cannot transmit them. The code handles this gracefully:
- If advertisement data contains the service data → Android node (Extended)
- If advertisement data is empty → iOS node (Legacy) → Connect and read

### Signature Format
- Public Key: 65 bytes (ANSI x9.63 uncompressed format)
- Signature: 64 bytes (raw ECDSA signature, no DER encoding)
- Total: 129 bytes

## 🔍 Debugging

Enable console output to see BLE events:
- `🔍` Scanning events
- `📡` Advertising events
- `✅` Successful operations
- `❌` Errors
- `🔗` Connection events

Check the Xcode console for detailed logs about:
- Advertisement data parsing
- Signature verification results
- BLE state changes
- Key generation/loading

## 📱 Requirements

- iOS 14.0+
- Physical device with BLE support
- Secure Enclave (iPhone 5s or newer)
- Bluetooth permissions granted

## 🎯 Next Steps

To make this a complete Xcode project:
1. Create Xcode project using these files
2. Add Info.plist permissions
3. Enable Background Modes
4. Test on physical devices
5. (Optional) Add more UI polish: node details screen, connection history, etc.
