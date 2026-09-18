import SwiftUI
import CoreImage.CIFilterBuiltins

/**
 * HomeView — Main offline payment UI for the Astral Offline demo app.
 *
 * Demonstrates the full SDK in a clean, minimal interface:
 *   - Wallet balance display
 *   - QR code generation (for receiving payments)
 *   - QR scanner (for sending payments)
 *   - Live transaction feed
 *   - BLE + WiFi status indicator
 */
@MainActor
struct HomeView: View {
    @StateObject private var sdk = AstralSDK()
    @StateObject private var viewModel = HomeViewModel()

    var body: some View {
        NavigationStack {
            ZStack {
                Color.black.ignoresSafeArea()

                ScrollView {
                    VStack(spacing: 24) {
                        // ── Balance Card ──────────────────────────────────────
                        BalanceCard(
                            walletID: sdk.walletID,
                            balance: viewModel.balancePaisa,
                            isSecureEnclave: sdk.isSecureEnclaveAvailable
                        )

                        // ── Transport Status ──────────────────────────────────
                        TransportStatusRow(isListening: sdk.isListening)

                        // ── Action Buttons ────────────────────────────────────
                        HStack(spacing: 16) {
                            ActionButton(title: "Receive", icon: "qrcode") {
                                viewModel.showReceiveQR = true
                            }
                            ActionButton(title: "Send", icon: "camera.viewfinder") {
                                viewModel.showScanner = true
                            }
                        }

                        // ── Transaction Feed ──────────────────────────────────
                        if viewModel.transactions.isEmpty {
                            EmptyTransactionView()
                        } else {
                            TransactionList(transactions: viewModel.transactions)
                        }
                    }
                    .padding(20)
                }
            }
            .navigationTitle("")
            .sheet(isPresented: $viewModel.showReceiveQR) {
                ReceiveQRSheet(qrPayload: sdk.generateReceiveQR())
            }
            .sheet(isPresented: $viewModel.showScanner) {
                QRScannerSheet { scannedString in
                    viewModel.handleScannedQR(scannedString, sdk: sdk)
                }
            }
            .sheet(isPresented: $viewModel.showSendConfirm) {
                if let qr = viewModel.pendingRecipientQR {
                    SendConfirmSheet(
                        recipientQR: qr,
                        balance: viewModel.balancePaisa
                    ) { amount, memo in
                        viewModel.sendPayment(amount: amount, memo: memo, to: qr, sdk: sdk)
                    }
                }
            }
        }
        .task {
            try? await sdk.start()
            sdk.startListening()
            sdk.onPayloadReceived = { data, sender in
                viewModel.handleIncomingPayload(data, from: sender)
            }
        }
    }
}

// ── Balance Card ─────────────────────────────────────────────────────────────

struct BalanceCard: View {
    let walletID: WalletID?
    let balance: Int64
    let isSecureEnclave: Bool

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            HStack {
                VStack(alignment: .leading, spacing: 4) {
                    Text("BALANCE")
                        .font(.caption2)
                        .fontWeight(.semibold)
                        .foregroundStyle(.white.opacity(0.5))
                        .tracking(2)

                    Text(String(format: "₹%.2f", Double(balance) / 100.0))
                        .font(.system(size: 42, weight: .bold, design: .rounded))
                        .foregroundStyle(.white)
                }
                Spacer()
                Image(systemName: "lock.shield.fill")
                    .font(.title2)
                    .foregroundStyle(isSecureEnclave ? .green : .yellow)
            }

            Divider().background(.white.opacity(0.1))

            HStack(spacing: 6) {
                Circle().fill(isSecureEnclave ? .green : .orange).frame(width: 7, height: 7)
                Text(isSecureEnclave ? "Secure Enclave" : "Software Key")
                    .font(.caption)
                    .foregroundStyle(.white.opacity(0.6))
                Spacer()
                Text(walletID?.short ?? "—")
                    .font(.caption.monospaced())
                    .foregroundStyle(.white.opacity(0.4))
            }
        }
        .padding(24)
        .background(
            RoundedRectangle(cornerRadius: 20)
                .fill(.white.opacity(0.05))
                .overlay(RoundedRectangle(cornerRadius: 20).stroke(.white.opacity(0.1)))
        )
    }
}

// ── Action Button ─────────────────────────────────────────────────────────────

struct ActionButton: View {
    let title: String
    let icon: String
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            VStack(spacing: 10) {
                Image(systemName: icon)
                    .font(.title2)
                Text(title)
                    .font(.callout.weight(.semibold))
            }
            .frame(maxWidth: .infinity)
            .padding(.vertical, 20)
            .foregroundStyle(.white)
            .background(RoundedRectangle(cornerRadius: 16).fill(.white.opacity(0.08)))
            .overlay(RoundedRectangle(cornerRadius: 16).stroke(.white.opacity(0.12)))
        }
    }
}

// ── Transport Status ──────────────────────────────────────────────────────────

struct TransportStatusRow: View {
    let isListening: Bool

    var body: some View {
        HStack(spacing: 12) {
            StatusPill(label: "BLE", isActive: isListening, icon: "antenna.radiowaves.left.and.right")
            StatusPill(label: "WiFi", isActive: isListening, icon: "wifi")
        }
    }
}

struct StatusPill: View {
    let label: String
    let isActive: Bool
    let icon: String

    var body: some View {
        HStack(spacing: 6) {
            Image(systemName: icon).font(.caption)
            Text(label).font(.caption.weight(.medium))
            Circle()
                .fill(isActive ? .green : .gray)
                .frame(width: 6, height: 6)
        }
        .padding(.horizontal, 14)
        .padding(.vertical, 8)
        .background(Capsule().fill(.white.opacity(0.07)))
        .foregroundStyle(.white.opacity(0.8))
    }
}

// ── Transaction List ──────────────────────────────────────────────────────────

struct TransactionList: View {
    let transactions: [AstralTransaction]

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            Text("TRANSACTIONS")
                .font(.caption2.weight(.semibold))
                .foregroundStyle(.white.opacity(0.4))
                .tracking(2)
                .padding(.leading, 4)

            ForEach(transactions) { txn in
                TransactionRow(txn: txn)
            }
        }
    }
}

struct TransactionRow: View {
    let txn: AstralTransaction

    var isIncoming: Bool { txn.status == .received }

    var body: some View {
        HStack {
            Image(systemName: isIncoming ? "arrow.down.left.circle.fill" : "arrow.up.right.circle.fill")
                .font(.title3)
                .foregroundStyle(isIncoming ? .green : .white.opacity(0.8))

            VStack(alignment: .leading, spacing: 2) {
                Text(isIncoming ? txn.senderID.short : txn.recipientID.short)
                    .font(.subheadline.weight(.medium))
                    .foregroundStyle(.white)
                if !txn.memo.isEmpty {
                    Text(txn.memo)
                        .font(.caption)
                        .foregroundStyle(.white.opacity(0.5))
                }
            }

            Spacer()

            Text((isIncoming ? "+" : "-") + txn.displayAmount)
                .font(.subheadline.weight(.semibold))
                .foregroundStyle(isIncoming ? .green : .white)
        }
        .padding(16)
        .background(RoundedRectangle(cornerRadius: 14).fill(.white.opacity(0.05)))
    }
}

struct EmptyTransactionView: View {
    var body: some View {
        VStack(spacing: 12) {
            Image(systemName: "waveform.path.ecg")
                .font(.largeTitle)
                .foregroundStyle(.white.opacity(0.2))
            Text("No transactions yet.\nScan a QR to send your first payment.")
                .font(.subheadline)
                .multilineTextAlignment(.center)
                .foregroundStyle(.white.opacity(0.3))
        }
        .padding(.vertical, 40)
    }
}

// ── Receive QR Sheet ──────────────────────────────────────────────────────────

struct ReceiveQRSheet: View {
    let qrPayload: AstralQRPayload

    private var qrImage: Image {
        let filter = CIFilter.qrCodeGenerator()
        filter.message = Data(qrPayload.toQRString().utf8)
        filter.correctionLevel = "H"
        let output = filter.outputImage!
        let scaled = output.transformed(by: CGAffineTransform(scaleX: 10, y: 10))
        return Image(uiImage: UIImage(ciImage: scaled))
    }

    var body: some View {
        VStack(spacing: 28) {
            Text("Scan to Pay Me")
                .font(.title3.weight(.semibold))
                .foregroundStyle(.white)

            qrImage
                .interpolation(.none)
                .resizable()
                .scaledToFit()
                .frame(width: 240, height: 240)
                .padding(16)
                .background(Color.white)
                .cornerRadius(16)

            Text(qrPayload.walletID.short)
                .font(.title3.monospaced())
                .foregroundStyle(.white.opacity(0.6))

            Text("BLE + WiFi Active")
                .font(.caption)
                .foregroundStyle(.green.opacity(0.8))
        }
        .padding(32)
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .background(Color.black)
    }
}

// ── Send Confirm Sheet ────────────────────────────────────────────────────────

struct SendConfirmSheet: View {
    let recipientQR: AstralQRPayload
    let balance: Int64
    let onConfirm: (Int64, String) -> Void

    @State private var amountText = ""
    @State private var memo = ""
    @Environment(\.dismiss) private var dismiss

    var amountPaisa: Int64 { (Int64(amountText) ?? 0) * 100 }
    var isValid: Bool { amountPaisa > 0 && amountPaisa <= balance }

    var body: some View {
        VStack(spacing: 24) {
            Text("Send to \(recipientQR.walletID.short)")
                .font(.title3.weight(.semibold))
                .foregroundStyle(.white)

            VStack(alignment: .leading, spacing: 8) {
                Text("AMOUNT (₹)").font(.caption2).foregroundStyle(.white.opacity(0.4)).tracking(2)
                TextField("0", text: $amountText)
                    .keyboardType(.numberPad)
                    .font(.title.weight(.bold))
                    .foregroundStyle(.white)
                    .padding(16)
                    .background(RoundedRectangle(cornerRadius: 12).fill(.white.opacity(0.07)))
            }

            VStack(alignment: .leading, spacing: 8) {
                Text("MEMO (optional)").font(.caption2).foregroundStyle(.white.opacity(0.4)).tracking(2)
                TextField("What's this for?", text: $memo)
                    .foregroundStyle(.white)
                    .padding(16)
                    .background(RoundedRectangle(cornerRadius: 12).fill(.white.opacity(0.07)))
            }

            Button {
                onConfirm(amountPaisa, memo)
                dismiss()
            } label: {
                Text("Send Offline")
                    .font(.headline)
                    .frame(maxWidth: .infinity)
                    .padding(.vertical, 18)
                    .background(isValid ? Color.white : Color.white.opacity(0.15))
                    .foregroundStyle(isValid ? .black : .white.opacity(0.3))
                    .cornerRadius(14)
            }
            .disabled(!isValid)
        }
        .padding(28)
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .background(Color.black)
    }
}

// ── Placeholder Sheets ────────────────────────────────────────────────────────

struct QRScannerSheet: View {
    let onScan: (String) -> Void
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        VStack {
            Text("QR Scanner")
                .font(.title3.weight(.semibold))
                .foregroundStyle(.white)
            Text("(Camera permission + AVFoundation\nintegration goes here)")
                .multilineTextAlignment(.center)
                .foregroundStyle(.white.opacity(0.5))
                .padding()
            Button("Demo: Use Test QR") {
                // Simulate a scan for development testing
                onScan("{\"v\":2,\"id\":\"aabbccdd11223344\",\"pk\":\"BAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA==\"}")
                dismiss()
            }
            .padding()
            .background(Color.white.opacity(0.1))
            .cornerRadius(12)
            .foregroundStyle(.white)
        }
        .padding()
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .background(Color.black)
    }
}

// ── ViewModel ─────────────────────────────────────────────────────────────────

@MainActor
final class HomeViewModel: ObservableObject {
    @Published var balancePaisa: Int64 = 50000  // ₹500.00 default
    @Published var transactions: [AstralTransaction] = []
    @Published var showReceiveQR = false
    @Published var showScanner = false
    @Published var showSendConfirm = false
    @Published var pendingRecipientQR: AstralQRPayload?

    func handleScannedQR(_ string: String, sdk: AstralSDK) {
        guard let qr = sdk.parseQR(string) else { return }
        pendingRecipientQR = qr
        showScanner = false
        showSendConfirm = true
    }

    func sendPayment(amount: Int64, memo: String, to qr: AstralQRPayload, sdk: AstralSDK) {
        guard amount <= balancePaisa else { return }
        let txn = AstralTransaction(
            id: UUID().uuidString,
            senderID: sdk.walletID ?? WalletID(id: "self"),
            recipientID: qr.walletID,
            amountPaisa: amount,
            timestamp: .now,
            memo: memo,
            status: .pending
        )

        Task {
            guard let payload = try? txn.toPayload() else { return }
            try? await sdk.sendPayload(payload, to: qr)
            await MainActor.run {
                balancePaisa -= amount
                var sent = txn
                sent.status = .sent
                transactions.insert(sent, at: 0)
            }
        }
    }

    func handleIncomingPayload(_ data: Data, from sender: WalletID) {
        if let txn = try? AstralTransaction.from(payload: data) {
            balancePaisa += txn.amountPaisa
            transactions.insert(txn, at: 0)
        }
    }
}
