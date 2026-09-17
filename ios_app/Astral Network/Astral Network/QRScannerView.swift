//
//  QRScannerView.swift  (SDK-based)
//  Astral Network
//
//  Camera QR scanner → AstralQRPayload.parse() → amount entry → onPaymentInitiated
//

import SwiftUI
import AVFoundation
import AstralSDK

// MARK: - Design Tokens

private let astralBlack = Color(red: 0.04, green: 0.04, blue: 0.04)
private let astralCard = Color(red: 0.1, green: 0.1, blue: 0.1)
private let astralAccent = Color(red: 0.91, green: 0.91, blue: 0.91)
private let astralWhite = Color(red: 0.94, green: 0.94, blue: 0.94)
private let astralMuted = Color(red: 0.533, green: 0.533, blue: 0.533)
private let astralSuccess = Color(red: 0.0, green: 0.784, blue: 0.325)
private let astralBorder = Color(red: 0.165, green: 0.165, blue: 0.165)

// MARK: - QR Scanner View

struct QRScannerView: View {
    /// Callback: amount (paisa), merchant public key DATA, merchant display name, merchant WalletID
    let onPaymentInitiated: (_ amount: Int, _ merchantPubKey: Data, _ merchantName: String, _ merchantWalletID: WalletID) -> Void
    let onClose: () -> Void

    @State private var scannedPayload: AstralQRPayload? = nil
    @State private var amountText = ""
    @State private var isProcessing = false
    @FocusState private var isAmountFocused: Bool

    private var amountInPaisa: Int {
        (Int(amountText) ?? 0) * 100
    }

    var body: some View {
        ZStack {
            astralBlack.ignoresSafeArea()

            if scannedPayload == nil {
                // ── Camera Scanner ──
                // The camera keeps scanning until we get a VALID AstralQRPayload.
                // Raw strings are forwarded here; we parse and only set scannedPayload
                // if parse() returns non-nil.
                CameraScanner(onQRScanned: { rawString in
                    if let payload = AstralQRPayload.parse(from: rawString) {
                        scannedPayload = payload
                    }
                    // If parse returns nil, camera keeps scanning — no death trap.
                })
                .ignoresSafeArea()

                // Overlay
                VStack {
                    HStack {
                        Text("SCAN TO PAY")
                            .font(.system(size: 12, weight: .light))
                            .tracking(4)
                            .foregroundColor(astralWhite)

                        Spacer()

                        Button(action: onClose) {
                            Image(systemName: "xmark")
                                .foregroundColor(astralWhite)
                                .padding(8)
                        }
                    }
                    .padding(.horizontal, 20)
                    .padding(.top, 60)

                    Spacer()

                    RoundedRectangle(cornerRadius: 16)
                        .stroke(astralAccent.opacity(0.5), lineWidth: 2)
                        .frame(width: 250, height: 250)

                    Spacer()

                    Text("Point camera at merchant's QR code")
                        .font(.system(size: 14))
                        .foregroundColor(astralMuted)
                        .padding(.bottom, 40)
                }
            } else {
                // ── Amount Entry — merchant scanned ──
                let payload = scannedPayload!

                VStack(spacing: 0) {
                    Spacer().frame(height: 80)

                    Image(systemName: "checkmark.circle.fill")
                        .font(.system(size: 48))
                        .foregroundColor(astralSuccess)

                    Spacer().frame(height: 16)

                    Text("PAYING")
                        .font(.system(size: 10, weight: .regular))
                        .tracking(3)
                        .foregroundColor(astralMuted)

                    Text(payload.walletID.short)
                        .font(.system(size: 24, weight: .bold))
                        .foregroundColor(astralWhite)
                        .padding(.top, 4)

                    // Security badge
                    HStack(spacing: 4) {
                        Image(systemName: "lock.shield.fill")
                            .font(.system(size: 10))
                        Text("ENCRYPTED PAYMENT")
                            .font(.system(size: 8, weight: .bold))
                            .tracking(2)
                    }
                    .foregroundColor(astralSuccess.opacity(0.8))
                    .padding(.top, 8)

                    Spacer().frame(height: 48)

                    // Amount input
                    HStack(alignment: .bottom, spacing: 4) {
                        Text("₹")
                            .font(.system(size: 32, weight: .light))
                            .foregroundColor(astralAccent)

                        TextField("0", text: $amountText)
                            .font(.system(size: 48, weight: .bold))
                            .foregroundColor(astralWhite)
                            .keyboardType(.numberPad)
                            .multilineTextAlignment(.center)
                            .focused($isAmountFocused)
                            .frame(width: 200)
                    }

                    Spacer().frame(height: 48)

                    // Confirm button
                    Button(action: {
                        if amountInPaisa > 0 && !isProcessing {
                            isProcessing = true

                            // Decode merchant's public key from base64 string in QR
                            guard let pubKeyData = Data(base64Encoded: payload.publicKey) else {
                                isProcessing = false
                                return
                            }

                            onPaymentInitiated(
                                amountInPaisa,
                                pubKeyData,
                                payload.walletID.short,
                                payload.walletID
                            )
                        }
                    }) {
                        HStack(spacing: 8) {
                            if isProcessing {
                                ProgressView()
                                    .progressViewStyle(CircularProgressViewStyle(tint: astralBlack))
                                    .scaleEffect(0.8)
                                Text("SENDING...")
                                    .font(.system(size: 12, weight: .bold))
                                    .tracking(3)
                            } else {
                                Image(systemName: "lock.fill")
                                    .font(.system(size: 12))
                                Text("CONFIRM & PAY")
                                    .font(.system(size: 12, weight: .bold))
                                    .tracking(3)
                            }
                        }
                        .foregroundColor(astralBlack)
                        .frame(maxWidth: .infinity)
                        .padding(.vertical, 18)
                        .background(
                            RoundedRectangle(cornerRadius: 12)
                                .fill(amountInPaisa > 0 ? astralAccent : astralBorder)
                        )
                    }
                    .disabled(amountInPaisa <= 0 || isProcessing)
                    .padding(.horizontal, 20)

                    Button(action: {
                        scannedPayload = nil
                        amountText = ""
                        isProcessing = false
                    }) {
                        Text("Cancel")
                            .foregroundColor(astralMuted)
                    }
                    .padding(.top, 16)

                    Spacer()
                }
            }
        }
        .onTapGesture { isAmountFocused = false }
    }
}

// MARK: - Camera Scanner (AVFoundation)

struct CameraScanner: UIViewControllerRepresentable {
    let onQRScanned: (String) -> Void

    func makeUIViewController(context: Context) -> CameraScannerVC {
        let vc = CameraScannerVC()
        vc.onQRScanned = onQRScanned
        return vc
    }

    func updateUIViewController(_ uiViewController: CameraScannerVC, context: Context) {}
}

class CameraScannerVC: UIViewController, AVCaptureMetadataOutputObjectsDelegate {
    var onQRScanned: ((String) -> Void)?
    private var captureSession: AVCaptureSession?
    private var previewLayer: AVCaptureVideoPreviewLayer?

    // Cooldown to avoid firing the same QR 60 times per second
    private var lastScannedValue: String?
    private var lastScannedTime: Date = .distantPast

    override func viewDidLoad() {
        super.viewDidLoad()
        setupCamera()
    }

    override func viewDidLayoutSubviews() {
        super.viewDidLayoutSubviews()
        previewLayer?.frame = view.bounds
    }

    private func setupCamera() {
        let session = AVCaptureSession()

        guard let device = AVCaptureDevice.default(for: .video),
              let input = try? AVCaptureDeviceInput(device: device) else { return }

        if session.canAddInput(input) { session.addInput(input) }

        let output = AVCaptureMetadataOutput()
        if session.canAddOutput(output) {
            session.addOutput(output)
            output.setMetadataObjectsDelegate(self, queue: .main)
            output.metadataObjectTypes = [.qr]
        }

        let preview = AVCaptureVideoPreviewLayer(session: session)
        preview.videoGravity = .resizeAspectFill
        preview.frame = view.bounds
        view.layer.addSublayer(preview)

        previewLayer = preview
        captureSession = session

        DispatchQueue.global(qos: .background).async {
            session.startRunning()
        }
    }

    func metadataOutput(_ output: AVCaptureMetadataOutput, didOutput metadataObjects: [AVMetadataObject], from connection: AVCaptureConnection) {
        guard let object = metadataObjects.first as? AVMetadataMachineReadableCodeObject,
              let value = object.stringValue else { return }

        // Cooldown: don't fire the same QR value more than once per second
        let now = Date()
        if value == lastScannedValue && now.timeIntervalSince(lastScannedTime) < 1.0 {
            return
        }

        lastScannedValue = value
        lastScannedTime = now

        // Haptic feedback
        let generator = UIImpactFeedbackGenerator(style: .medium)
        generator.impactOccurred()

        // Pass raw string to parent — parent decides if it's valid via AstralQRPayload.parse().
        // Camera does NOT stop here. It keeps scanning.
        // The parent SwiftUI view will set scannedPayload, which removes CameraScanner from
        // the view hierarchy, which triggers viewWillDisappear → session.stopRunning().
        onQRScanned?(value)
    }

    override func viewWillDisappear(_ animated: Bool) {
        super.viewWillDisappear(animated)
        captureSession?.stopRunning()
    }
}
