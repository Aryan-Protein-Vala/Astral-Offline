use astral_core::errors::AstralError;
use astral_core::state::vault::HardwareVault;

#[test]
fn test_vault_initialization_and_monotonic_spending() {
    let vault = HardwareVault::new("wallet_alice_123".into(), 10_000);
    assert_eq!(vault.vault_id, "wallet_alice_123");

    // Spend 1,000 -> counter becomes 1
    let c1 = vault.spend_offline(1_000).expect("spend 1 failed");
    assert_eq!(c1, 1);

    // Spend 2,500 -> counter becomes 2
    let c2 = vault.spend_offline(2_500).expect("spend 2 failed");
    assert_eq!(c2, 2);

    // Spend 6,500 -> counter becomes 3 (balance 0)
    let c3 = vault.spend_offline(6_500).expect("spend 3 failed");
    assert_eq!(c3, 3);

    // Spend 1 more -> should fail with Insufficient funds
    let err = vault.spend_offline(1).unwrap_err();
    match err {
        AstralError::CryptoFailure { reason } => {
            assert!(
                reason.contains("Insufficient funds"),
                "Expected insufficient funds message, got: {}",
                reason
            );
        }
        other => panic!("Unexpected error: {:?}", other),
    }
}

#[test]
fn test_vault_inbound_double_spend_detection() {
    let vault = HardwareVault::new("wallet_bob_456".into(), 5_000);

    let tx_hash = "0xdeadbeefcafebabe1234567890abcdef";
    let remote_counter = 10;
    let expected_counter = 10;

    // First verification should pass
    vault
        .verify_inbound_tx(tx_hash, remote_counter, expected_counter)
        .expect("initial tx verify should succeed");

    // Second verification of identical tx_hash should fail with DoubleSpendDetected
    let err = vault
        .verify_inbound_tx(tx_hash, remote_counter, expected_counter)
        .unwrap_err();
    match err {
        AstralError::DoubleSpendDetected { counter } => {
            assert_eq!(counter, remote_counter);
        }
        other => panic!("Expected DoubleSpendDetected error, got {:?}", other),
    }

    // Counter mismatch should fail with DoubleSpendDetected
    let err_counter = vault
        .verify_inbound_tx("0xnewtx123", 15, 16)
        .unwrap_err();
    assert!(matches!(err_counter, AstralError::DoubleSpendDetected { .. }));
}
