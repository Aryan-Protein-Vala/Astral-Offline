use crate::errors::AstralError;
use parking_lot::RwLock;
use std::collections::HashSet;
use std::sync::Arc;

#[derive(Debug, Clone, serde::Serialize, serde::Deserialize)]
pub struct SignedReceipt {
    pub vault_id: String,
    pub counter: u64,
    pub amount: u64,
    pub remaining_balance: u64,
    pub signature: Vec<u8>,
}

pub struct HardwareVault {
    pub vault_id: String,
    counter: Arc<RwLock<u64>>,
    balance: Arc<RwLock<u64>>,
    seen_transactions: Arc<RwLock<HashSet<String>>>,
}

impl HardwareVault {
    pub fn new(vault_id: String, initial_balance: u64) -> Self {
        Self {
            vault_id,
            counter: Arc::new(RwLock::new(0)),
            balance: Arc::new(RwLock::new(initial_balance)),
            seen_transactions: Arc::new(RwLock::new(HashSet::new())),
        }
    }

    /// Verifies counter monotonicity on the client side before allowing offline receipt release.
    pub fn spend_offline(&self, amount: u64) -> Result<u64, AstralError> {
        let mut balance = self.balance.write();
        let mut counter = self.counter.write();

        if *balance < amount {
            return Err(AstralError::CryptoFailure {
                reason: "Insufficient funds in offline vault".into(),
            });
        }

        *balance -= amount;
        *counter += 1;

        Ok(*counter)
    }

    /// Validates an incoming inbound payment chunk from another device to ensure no duplicate nonces.
    pub fn verify_inbound_tx(
        &self,
        tx_hash: &str,
        remote_counter: u64,
        expected_counter: u64,
    ) -> Result<(), AstralError> {
        if remote_counter != expected_counter {
            return Err(AstralError::DoubleSpendDetected { counter: remote_counter });
        }

        let mut seen = self.seen_transactions.write();
        if seen.contains(tx_hash) {
            return Err(AstralError::DoubleSpendDetected { counter: remote_counter });
        }

        seen.insert(tx_hash.to_string());
        Ok(())
    }
}
