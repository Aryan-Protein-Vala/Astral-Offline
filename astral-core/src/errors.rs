#[derive(Debug, thiserror::Error, uniffi::Error)]
pub enum AstralError {
    #[error("Cryptographic verification failed: {reason}")]
    CryptoFailure { reason: String },
    #[error("Packet corrupted or framing error: {reason}")]
    FramingError { reason: String },
    #[error("Double spend detected. Transaction counter {counter} invalid")]
    DoubleSpendDetected { counter: u64 },
    #[error("Packet reassembly timed out for session {session_id}")]
    ReassemblyTimeout { session_id: u32 },
    #[error("Payload exceeds maximum protocol capacity: {size} bytes")]
    PayloadTooLarge { size: u32 },
}
