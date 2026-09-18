//
//  AstralWalletView.swift  (SDK-based)
//  Astral Network
//
//  Main wallet interface: Wallet (balance + QR), Pay (QR scan), Receive (merchant QR + BLE).
//  Uses AstralSDK for all crypto, transport, and payment logic.
//

import SwiftUI
import AstralSDK

// MARK: - Design Tokens (Monochrome — matches astralnetwork.in)

struct AstralColors {
    static let black = Color(red: 0.0, green: 0.0, blue: 0.0)
    static let dark = Color(red: 0.05, green: 0.05, blue: 0.05)
    static let card = Color(red: 0.08, green: 0.08, blue: 0.08)
    static let border = Color(red: 0.13, green: 0.13, blue: 0.13)
    static let accent = Color(red: 0.91, green: 0.91, blue: 0.91)
    static let gold = Color(red: 0.91, green: 0.91, blue: 0.91)
    static let white = Color(red: 0.92, green: 0.92, blue: 0.92)
    static let muted = Color(red: 0.40, green: 0.40, blue: 0.40)
    static let success = Color(red: 0.0, green: 0.784, blue: 0.325)
    static let error = Color(red: 1.0, green: 0.322, blue: 0.322)
    static let warning = Color(red: 1.0, green: 0.596, blue: 0.0)
}

// MARK: - Diamond Logo

struct DiamondLogo: View {
    var size: CGFloat = 28
    var color: Color = AstralColors.white

    var body: some View {
        ZStack {
            Rectangle()
                .stroke(color.opacity(0.3), lineWidth: 1)
                .frame(width: size, height: size)
                .rotationEffect(.degrees(45))
            Rectangle()
                .stroke(color.opacity(0.5), lineWidth: 1)
                .frame(width: size * 0.625, height: size * 0.625)
                .rotationEffect(.degrees(45))
            Rectangle()
                .fill(color.opacity(0.8))
                .frame(width: size * 0.25, height: size * 0.25)
                .rotationEffect(.degrees(45))
        }
        .frame(width: size * 1.5, height: size * 1.5)
    }
}

// MARK: - Local Transaction Model

struct WalletTransaction: Identifiable {
    let id: String
    let amount: Int           // In paisa
    let type: String          // "sent" | "received"
    let timestamp: Date
    var status: String        // "pending" | "confirmed" | "failed"
    let counterparty: String
}

// MARK: - Main Wallet View

struct AstralWalletView: View {
    let userName: String

    @EnvironmentObject var sdk: AstralSDK
    @State private var selectedTab = 0
    @State private var showScanner = false
    @State private var balance: Int = 20000           // In paisa
    @State private var transactions: [WalletTransaction] = []
    @State private var receivedTotal: Int = 0
    @State private var paymentResult: PaymentResultState? = nil

    enum PaymentResultState: Identifiable {
        case success(name: String, amount: Int)    // merchant name and amount
        case failure(String)                       // error message

        var id: String {
            switch self {
            case .success(let name, let amount): return "success-\(name)-\(amount)"
            case .failure(let msg): return "failure-\(msg)"
            }
        }
    }

    var body: some View {
        Group {
            if sdk.transportStatus == .offline && sdk.walletID == nil {
                // Not initialized yet
                VStack(spacing: 16) {
                    ProgressView()
                        .scaleEffect(1.5)
                    Text("INITIALIZING SECURE ENCLAVE...")
                        .font(.system(size: 10, weight: .light))
                        .tracking(3)
                        .foregroundColor(AstralColors.muted)
                }
                .frame(maxWidth: .infinity, maxHeight: .infinity)
                .background(AstralColors.black.ignoresSafeArea())
            } else if showScanner {
                QRScannerView(
                    onPaymentInitiated: { amount, merchantPubKey, merchantName, merchantWalletID in
                        handlePayment(amount: amount, merchantPubKey: merchantPubKey,
                                     merchantName: merchantName, merchantWalletID: merchantWalletID)
                    },
                    onClose: { showScanner = false }
                )
            } else {
                walletTabView
            }
        }
        .onAppear { setupSDKCallbacks() }
        .alert(item: $paymentResult.animation()) { result in
            switch result {
            case .success(let name, let amount):
                return Alert(title: Text("Payment Sent ✅"),
                           message: Text("₹\(amount / 100) sent to \(name)"),
                           dismissButton: .default(Text("OK")))
            case .failure(let msg):
                return Alert(title: Text("Payment Failed ❌"),
                           message: Text(msg),
                           dismissButton: .default(Text("OK")))
            }
        }
    }

    // MARK: - SDK Callbacks

    private func setupSDKCallbacks() {
        // When merchant receives a verified payment
        sdk.onPaymentReceived = { txn in
            receivedTotal += txn.amount
            transactions.append(WalletTransaction(
                id: txn.id, amount: txn.amount, type: "received",
                timestamp: txn.timestamp, status: "confirmed",
                counterparty: txn.senderID.short
            ))

            // Haptic
            let generator = UINotificationFeedbackGenerator()
            generator.notificationOccurred(.success)
            print("📥 Received ₹\(txn.amount / 100) from \(txn.senderID.short)")
        }

        // When a sent payment gets rejected
        sdk.onPaymentRejected = { data, error in
            print("⚠️ Payment rejected: \(error)")
        }
    }

    // MARK: - Send Payment (via SDK)

    private func handlePayment(amount: Int, merchantPubKey: Data,
                               merchantName: String, merchantWalletID: WalletID) {
        // Use SDK — it handles: sign (SE) → encrypt (Noise) → BLE → wait for ACK
        sdk.sendPayment(
            amount: amount,
            to: merchantWalletID,
            merchantPublicKey: merchantPubKey
        ) { result in
            DispatchQueue.main.async {
                showScanner = false

                switch result {
                case .success(let txn):
                    balance -= txn.amount
                    transactions.append(WalletTransaction(
                        id: txn.id, amount: txn.amount, type: "sent",
                        timestamp: Date(), status: "confirmed",
                        counterparty: merchantName
                    ))
                    paymentResult = .success(name: merchantName, amount: txn.amount)

                    let generator = UINotificationFeedbackGenerator()
                    generator.notificationOccurred(.success)
                    print("📤 Sent ₹\(txn.amount / 100) to \(merchantName)")

                case .failure(let error):
                    transactions.append(WalletTransaction(
                        id: UUID().uuidString, amount: amount, type: "sent",
                        timestamp: Date(), status: "failed",
                        counterparty: merchantName
                    ))
                    paymentResult = .failure(error.localizedDescription)
                    print("❌ Payment failed: \(error)")
                }
            }
        }
    }

    // MARK: - Tab View

    var walletTabView: some View {
        TabView(selection: $selectedTab) {
            WalletTab(balance: balance, transactions: transactions, userName: userName)
                .tabItem {
                    Image(systemName: "wallet.pass")
                    Text("WALLET")
                }
                .tag(0)

            Color.clear
                .tabItem {
                    Image(systemName: "qrcode.viewfinder")
                    Text("PAY")
                }
                .tag(1)

            MerchantTab(receivedTotal: receivedTotal,
                       transactions: transactions.filter { $0.type == "received" },
                       merchantName: userName)
                .tabItem {
                    Image(systemName: "storefront")
                    Text("RECEIVE")
                }
                .tag(2)
        }
        .accentColor(AstralColors.white)
        .onChange(of: selectedTab) { newValue in
            if newValue == 1 {
                showScanner = true
                selectedTab = 0
            }
        }
    }
}


// MARK: - Bluetooth OFF View

struct BluetoothOffView: View {
    var body: some View {
        ZStack {
            AstralColors.black.ignoresSafeArea()

            VStack(spacing: 16) {
                Image(systemName: "antenna.radiowaves.left.and.right.slash")
                    .font(.system(size: 56))
                    .foregroundColor(AstralColors.error)

                Text("BLUETOOTH IS OFF")
                    .font(.system(size: 16, weight: .bold))
                    .tracking(3)
                    .foregroundColor(AstralColors.white)

                Text("Astral Wallet requires Bluetooth\nto send and receive payments.")
                    .font(.system(size: 14))
                    .foregroundColor(AstralColors.muted)
                    .multilineTextAlignment(.center)
                    .padding(.horizontal, 40)

                HStack(spacing: 8) {
                    Image(systemName: "exclamationmark.triangle.fill")
                        .foregroundColor(AstralColors.warning)
                        .font(.system(size: 16))
                    Text("Turn on Bluetooth in Settings")
                        .font(.system(size: 13))
                        .foregroundColor(AstralColors.warning)
                }
                .padding(16)
                .background(AstralColors.warning.opacity(0.1))
                .clipShape(RoundedRectangle(cornerRadius: 12))
            }
        }
    }
}

// MARK: - Wallet Tab

struct WalletTab: View {
    let balance: Int
    let transactions: [WalletTransaction]
    let userName: String

    @EnvironmentObject var sdk: AstralSDK
    @State private var showMyQR = false

    var body: some View {
        ScrollView {
            VStack(spacing: 20) {
                // Header
                HStack {
                    HStack(spacing: 12) {
                        DiamondLogo(size: 18, color: AstralColors.white)
                        Text("ASTRAL WALLET")
                            .font(.system(size: 12, weight: .light))
                            .tracking(4)
                            .foregroundColor(AstralColors.muted)
                    }
                    Spacer()
                    Button(action: { showMyQR.toggle() }) {
                        Image(systemName: "qrcode")
                            .foregroundColor(AstralColors.white)
                            .font(.system(size: 22))
                    }
                }

                // My QR — generated via SDK (includes public key!)
                if showMyQR, let walletID = sdk.walletID {
                    VStack(spacing: 12) {
                        Text("MY PAYMENT QR")
                            .font(.system(size: 10))
                            .tracking(3)
                            .foregroundColor(AstralColors.muted)

                        let qrPayload = sdk.generatePaymentQR()
                        if let qrImage = generateQRImage(from: qrPayload.toJSONString(), size: 200) {
                            Image(uiImage: qrImage)
                                .interpolation(.none)
                                .resizable()
                                .frame(width: 200, height: 200)
                                .clipShape(RoundedRectangle(cornerRadius: 12))
                        }

                        Text(userName)
                            .font(.system(size: 16, weight: .medium))
                            .foregroundColor(AstralColors.white)
                        Text("ID: \(walletID.short)")
                            .font(.system(size: 11))
                            .foregroundColor(AstralColors.muted)
                        Text("Others scan this to pay you")
                            .font(.system(size: 12))
                            .foregroundColor(AstralColors.muted)
                    }
                    .padding(24)
                    .frame(maxWidth: .infinity)
                    .background(AstralColors.card)
                    .clipShape(RoundedRectangle(cornerRadius: 16))
                }

                // Balance Card
                VStack(alignment: .leading, spacing: 8) {
                    Text("AVAILABLE BALANCE")
                        .font(.system(size: 10))
                        .tracking(3)
                        .foregroundColor(AstralColors.muted)

                    HStack(alignment: .bottom, spacing: 4) {
                        Text("₹")
                            .font(.system(size: 24, weight: .light))
                            .foregroundColor(AstralColors.white.opacity(0.6))
                        Text("\(balance / 100)")
                            .font(.system(size: 48, weight: .bold))
                            .foregroundColor(AstralColors.white)
                    }

                    HStack(spacing: 6) {
                        Circle()
                            .fill(sdk.transportStatus != .offline ? AstralColors.success : AstralColors.error)
                            .frame(width: 6, height: 6)
                        Text(sdk.transportStatus.rawValue.uppercased())
                            .font(.system(size: 9))
                            .tracking(2)
                            .foregroundColor(sdk.transportStatus != .offline ? AstralColors.success : AstralColors.error)
                    }
                    .padding(.top, 8)

                    if sdk.isHardwareBacked {
                        HStack(spacing: 4) {
                            Image(systemName: "lock.shield.fill")
                                .font(.system(size: 9))
                            Text("SECURE ENCLAVE")
                                .font(.system(size: 8))
                                .tracking(2)
                        }
                        .foregroundColor(AstralColors.muted)
                    }
                }
                .padding(28)
                .frame(maxWidth: .infinity, alignment: .leading)
                .background(AstralColors.card)
                .clipShape(RoundedRectangle(cornerRadius: 16))

                // Recent Activity
                Text("RECENT ACTIVITY")
                    .font(.system(size: 10))
                    .tracking(3)
                    .foregroundColor(AstralColors.muted)
                    .frame(maxWidth: .infinity, alignment: .leading)

                if transactions.isEmpty {
                    VStack(spacing: 12) {
                        Image(systemName: "arrow.left.arrow.right")
                            .font(.system(size: 40))
                            .foregroundColor(AstralColors.border)
                        Text("No transactions yet")
                            .foregroundColor(AstralColors.muted)
                        Text("Scan a QR to make your first payment")
                            .font(.system(size: 12))
                            .foregroundColor(AstralColors.muted.opacity(0.6))
                    }
                    .padding(.vertical, 40)
                    .frame(maxWidth: .infinity)
                } else {
                    ForEach(transactions.reversed()) { txn in
                        TransactionRow(txn: txn)
                    }
                }
            }
            .padding(20)
        }
        .background(AstralColors.black)
    }
}

// MARK: - Merchant Tab

struct MerchantTab: View {
    let receivedTotal: Int
    let transactions: [WalletTransaction]
    let merchantName: String

    @EnvironmentObject var sdk: AstralSDK

    var body: some View {
        ScrollView {
            VStack(spacing: 20) {
                // Header
                HStack {
                    HStack(spacing: 12) {
                        DiamondLogo(size: 18, color: AstralColors.white)
                        Text("RECEIVE")
                            .font(.system(size: 12, weight: .light))
                            .tracking(4)
                            .foregroundColor(AstralColors.muted)
                    }
                    Spacer()
                    HStack(spacing: 6) {
                        Circle()
                            .fill(sdk.isListening ? AstralColors.success : AstralColors.muted.opacity(0.3))
                            .frame(width: 6, height: 6)
                        Text(sdk.isListening ? "LISTENING" : "OFFLINE")
                            .font(.system(size: 9))
                            .tracking(2)
                            .foregroundColor(sdk.isListening ? AstralColors.success : AstralColors.muted)
                    }
                }

                // Merchant QR — auto-generated by SDK with public key embedded
                if sdk.walletID != nil {
                    VStack(spacing: 16) {
                        Text("SCAN TO PAY")
                            .font(.system(size: 10))
                            .tracking(3)
                            .foregroundColor(AstralColors.muted)

                        let qrPayload = sdk.generatePaymentQR()
                        if let qrImage = generateQRImage(from: qrPayload.toJSONString(), size: 220) {
                            Image(uiImage: qrImage)
                                .interpolation(.none)
                                .resizable()
                                .frame(width: 220, height: 220)
                                .clipShape(RoundedRectangle(cornerRadius: 12))
                        }

                        Text(merchantName)
                            .font(.system(size: 16, weight: .medium))
                            .foregroundColor(AstralColors.white)

                        if sdk.isListening {
                            HStack(spacing: 8) {
                                ProgressView()
                                    .progressViewStyle(CircularProgressViewStyle(tint: AstralColors.white))
                                    .scaleEffect(0.6)
                                Text("Waiting for payments...")
                                    .font(.system(size: 12))
                                    .foregroundColor(AstralColors.muted)
                            }
                        }
                    }
                    .padding(28)
                    .frame(maxWidth: .infinity)
                    .background(AstralColors.card)
                    .clipShape(RoundedRectangle(cornerRadius: 16))
                    .overlay(
                        RoundedRectangle(cornerRadius: 16)
                            .stroke(AstralColors.border, lineWidth: 1)
                    )
                }

                // Earnings
                VStack(alignment: .leading, spacing: 8) {
                    Text("TOTAL EARNED")
                        .font(.system(size: 10))
                        .tracking(3)
                        .foregroundColor(AstralColors.muted)

                    HStack(alignment: .bottom) {
                        Text("₹")
                            .font(.system(size: 20, weight: .light))
                            .foregroundColor(AstralColors.white.opacity(0.6))
                        Text("\(receivedTotal / 100)")
                            .font(.system(size: 36, weight: .bold))
                            .foregroundColor(AstralColors.white)
                    }
                    Text("\(transactions.count) payment(s)")
                        .font(.system(size: 12))
                        .foregroundColor(AstralColors.muted)
                }
                .padding(24)
                .frame(maxWidth: .infinity, alignment: .leading)
                .background(AstralColors.card)
                .clipShape(RoundedRectangle(cornerRadius: 16))
                .overlay(
                    RoundedRectangle(cornerRadius: 16)
                        .stroke(AstralColors.border, lineWidth: 1)
                )

                // Received transactions
                Text("RECEIVED")
                    .font(.system(size: 10))
                    .tracking(3)
                    .foregroundColor(AstralColors.muted)
                    .frame(maxWidth: .infinity, alignment: .leading)

                if transactions.isEmpty {
                    VStack(spacing: 12) {
                        Image(systemName: "arrow.down.circle")
                            .font(.system(size: 40))
                            .foregroundColor(AstralColors.border)
                        Text("No payments received yet")
                            .foregroundColor(AstralColors.muted)
                        Text("Share your QR code to receive payments")
                            .font(.system(size: 12))
                            .foregroundColor(AstralColors.muted.opacity(0.6))
                    }
                    .padding(.vertical, 40)
                    .frame(maxWidth: .infinity)
                } else {
                    ForEach(transactions.reversed()) { txn in
                        TransactionRow(txn: txn)
                    }
                }
            }
            .padding(20)
        }
        .background(AstralColors.black)
        // Auto-start listening via SDK when merchant tab appears
        .onAppear {
            if !sdk.isListening {
                sdk.startListening()
                print("📡 Merchant mode: SDK listening started")
            }
        }
        .onDisappear {
            if sdk.isListening {
                sdk.stopListening()
                print("⏸️ Merchant mode: SDK listening stopped")
            }
        }
    }
}

// MARK: - Transaction Row

struct TransactionRow: View {
    let txn: WalletTransaction

    private let dateFormatter: DateFormatter = {
        let f = DateFormatter()
        f.dateFormat = "HH:mm · MMM dd"
        return f
    }()

    var body: some View {
        HStack {
            HStack(spacing: 12) {
                Circle()
                    .fill(rowColor.opacity(0.1))
                    .frame(width: 36, height: 36)
                    .overlay(
                        Image(systemName: rowIcon)
                            .font(.system(size: 14))
                            .foregroundColor(rowColor)
                    )

                VStack(alignment: .leading, spacing: 2) {
                    Text(txn.counterparty.isEmpty ? (txn.type == "sent" ? "Sent" : "Received") : txn.counterparty)
                        .font(.system(size: 14, weight: .medium))
                        .foregroundColor(AstralColors.white)
                    HStack(spacing: 4) {
                        Text(dateFormatter.string(from: txn.timestamp))
                            .font(.system(size: 11))
                            .foregroundColor(AstralColors.muted)
                        if txn.status == "failed" {
                            Text("FAILED")
                                .font(.system(size: 8, weight: .bold))
                                .padding(.horizontal, 4)
                                .padding(.vertical, 2)
                                .background(AstralColors.error.opacity(0.2))
                                .foregroundColor(AstralColors.error)
                                .cornerRadius(4)
                        }
                    }
                }
            }
            Spacer()
            Text("\(txn.type == "sent" ? "-" : "+")₹\(txn.amount / 100)")
                .font(.system(size: 16, weight: .bold))
                .foregroundColor(rowColor)
        }
        .padding(16)
        .background(AstralColors.card)
        .clipShape(RoundedRectangle(cornerRadius: 12))
    }

    private var rowColor: Color {
        if txn.status == "failed" { return AstralColors.error }
        return txn.type == "sent" ? AstralColors.error : AstralColors.success
    }

    private var rowIcon: String {
        if txn.status == "failed" { return "xmark" }
        return txn.type == "sent" ? "arrow.up" : "arrow.down"
    }
}

// MARK: - QR Code Generator

func generateQRImage(from string: String, size: CGFloat = 200) -> UIImage? {
    guard let data = string.data(using: .utf8),
          let filter = CIFilter(name: "CIQRCodeGenerator") else { return nil }

    filter.setValue(data, forKey: "inputMessage")
    filter.setValue("H", forKey: "inputCorrectionLevel")

    guard let ciImage = filter.outputImage else { return nil }

    let transform = CGAffineTransform(scaleX: size / ciImage.extent.width,
                                       y: size / ciImage.extent.height)
    let scaledImage = ciImage.transformed(by: transform)

    // Monochrome tint
    let colorFilter = CIFilter(name: "CIFalseColor")
    colorFilter?.setValue(scaledImage, forKey: "inputImage")
    colorFilter?.setValue(CIColor(red: 0.92, green: 0.92, blue: 0.92), forKey: "inputColor0")
    colorFilter?.setValue(CIColor(red: 0.0, green: 0.0, blue: 0.0), forKey: "inputColor1")

    guard let tinted = colorFilter?.outputImage else { return nil }

    let context = CIContext()
    guard let cgImage = context.createCGImage(tinted, from: tinted.extent) else { return nil }
    return UIImage(cgImage: cgImage)
}
