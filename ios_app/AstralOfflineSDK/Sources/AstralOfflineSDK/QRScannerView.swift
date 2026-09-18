import SwiftUI
import AVFoundation

/**
 * QRScannerView — Real camera-based QR scanner using AVFoundation.
 *
 * Full pipeline:
 *  - Requests camera permission using Info.plist `NSCameraUsageDescription`
 *  - AVCaptureSession with AVCaptureMetadataOutput for QR detection
 *  - Fires `onScan` exactly once on the main thread when a valid QR is found
 *  - Animated viewfinder overlay with corner brackets
 */
struct QRScannerView: View {
    let onScan: (String) -> Void
    let onDismiss: () -> Void

    @StateObject private var coordinator = QRScannerCoordinator()
    @State private var permissionGranted = false
    @State private var scanLineOffset: CGFloat = -120

    var body: some View {
        ZStack {
            Color.black.ignoresSafeArea()

            if permissionGranted {
                CameraPreviewRepresentable(coordinator: coordinator)
                    .ignoresSafeArea()
            } else {
                PermissionView(onDismiss: onDismiss)
            }

            // ── Overlay ──────────────────────────────────────────────────────
            VStack {
                // Top bar
                HStack {
                    Text("Scan QR to Pay")
                        .font(.headline)
                        .foregroundStyle(.white)
                    Spacer()
                    Button(action: onDismiss) {
                        Image(systemName: "xmark.circle.fill")
                            .font(.title2)
                            .foregroundStyle(.white.opacity(0.8))
                    }
                }
                .padding(.horizontal, 24)
                .padding(.top, 60)

                Spacer()

                // Viewfinder
                ZStack {
                    // Dimmed surround
                    Color.black.opacity(0.55)
                        .mask(
                            Rectangle()
                                .overlay(
                                    RoundedRectangle(cornerRadius: 16)
                                        .frame(width: 260, height: 260)
                                        .blendMode(.destinationOut)
                                )
                        )

                    // Corner brackets
                    ViewfinderBrackets()
                        .frame(width: 260, height: 260)

                    // Animated scan line
                    Rectangle()
                        .fill(LinearGradient(
                            colors: [.clear, .white.opacity(0.6), .clear],
                            startPoint: .leading, endPoint: .trailing
                        ))
                        .frame(width: 240, height: 2)
                        .offset(y: scanLineOffset)
                        .animation(.easeInOut(duration: 2).repeatForever(autoreverses: true), value: scanLineOffset)
                        .onAppear { scanLineOffset = 120 }
                }
                .frame(width: 260, height: 260)

                Spacer()

                Text("Point at the recipient's QR code")
                    .font(.subheadline)
                    .foregroundStyle(.white.opacity(0.6))
                    .padding(.bottom, 50)
            }
        }
        .onAppear {
            coordinator.onScan = onScan
            requestCameraPermission()
        }
    }

    private func requestCameraPermission() {
        switch AVCaptureDevice.authorizationStatus(for: .video) {
        case .authorized:
            permissionGranted = true
        case .notDetermined:
            AVCaptureDevice.requestAccess(for: .video) { granted in
                DispatchQueue.main.async { permissionGranted = granted }
            }
        default:
            permissionGranted = false
        }
    }
}

// ── Camera Session Coordinator ─────────────────────────────────────────────────

final class QRScannerCoordinator: NSObject, ObservableObject, AVCaptureMetadataOutputObjectsDelegate {
    var session = AVCaptureSession()
    var onScan: ((String) -> Void)?
    private var hasScanned = false   // debounce

    override init() {
        super.init()
        setupSession()
    }

    private func setupSession() {
        session.beginConfiguration()

        guard let device = AVCaptureDevice.default(for: .video),
              let input = try? AVCaptureDeviceInput(device: device),
              session.canAddInput(input) else {
            session.commitConfiguration()
            return
        }
        session.addInput(input)

        let output = AVCaptureMetadataOutput()
        if session.canAddOutput(output) {
            session.addOutput(output)
            output.setMetadataObjectsDelegate(self, queue: .main)
            output.metadataObjectTypes = [.qr]
        }

        session.commitConfiguration()
    }

    func start() {
        if !session.isRunning {
            DispatchQueue.global(qos: .userInitiated).async { [weak self] in
                self?.session.startRunning()
            }
        }
    }

    func stop() {
        if session.isRunning { session.stopRunning() }
    }

    func metadataOutput(_ output: AVCaptureMetadataOutput,
                        didOutput metadataObjects: [AVMetadataObject],
                        from connection: AVCaptureConnection) {
        guard !hasScanned,
              let object = metadataObjects.first as? AVMetadataMachineReadableCodeObject,
              let value = object.stringValue else { return }
        hasScanned = true
        stop()
        onScan?(value)
    }
}

// ── Camera Preview (UIViewRepresentable) ───────────────────────────────────────

struct CameraPreviewRepresentable: UIViewRepresentable {
    let coordinator: QRScannerCoordinator

    func makeUIView(context: Context) -> UIView {
        let view = UIView(frame: .zero)
        let layer = AVCaptureVideoPreviewLayer(session: coordinator.session)
        layer.videoGravity = .resizeAspectFill
        view.layer.addSublayer(layer)
        coordinator.start()

        DispatchQueue.main.async {
            layer.frame = view.bounds
        }
        return view
    }

    func updateUIView(_ uiView: UIView, context: Context) {
        if let layer = uiView.layer.sublayers?.first as? AVCaptureVideoPreviewLayer {
            layer.frame = uiView.bounds
        }
    }
}

// ── Viewfinder Brackets ────────────────────────────────────────────────────────

struct ViewfinderBrackets: View {
    var body: some View {
        ZStack {
            // Top-left
            BracketCorner().frame(width: 30, height: 30)
                .offset(x: -115, y: -115)
            // Top-right
            BracketCorner().rotationEffect(.degrees(90)).frame(width: 30, height: 30)
                .offset(x: 115, y: -115)
            // Bottom-left
            BracketCorner().rotationEffect(.degrees(270)).frame(width: 30, height: 30)
                .offset(x: -115, y: 115)
            // Bottom-right
            BracketCorner().rotationEffect(.degrees(180)).frame(width: 30, height: 30)
                .offset(x: 115, y: 115)
        }
    }
}

struct BracketCorner: View {
    var body: some View {
        Path { path in
            path.move(to: CGPoint(x: 0, y: 30))
            path.addLine(to: CGPoint(x: 0, y: 0))
            path.addLine(to: CGPoint(x: 30, y: 0))
        }
        .stroke(Color.white, style: StrokeStyle(lineWidth: 3, lineCap: .round))
    }
}

// ── Permission Denied ──────────────────────────────────────────────────────────

struct PermissionView: View {
    let onDismiss: () -> Void

    var body: some View {
        VStack(spacing: 16) {
            Image(systemName: "camera.fill").font(.largeTitle).foregroundStyle(.white.opacity(0.4))
            Text("Camera access needed to scan QR codes.")
                .multilineTextAlignment(.center)
                .foregroundStyle(.white.opacity(0.6))
                .padding(.horizontal, 32)
            Button("Go Back", action: onDismiss)
                .buttonStyle(.bordered).tint(.white)
        }
    }
}
