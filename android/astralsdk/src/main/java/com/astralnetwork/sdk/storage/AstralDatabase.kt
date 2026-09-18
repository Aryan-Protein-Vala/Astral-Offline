package com.astralnetwork.sdk.storage

import android.content.Context
import androidx.room.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// ── Room Entities ─────────────────────────────────────────────────────────────

/**
 * MeshPacketEntity — A packet waiting to be forwarded via opportunistic routing.
 *
 * When the Rust MeshRouter receives a packet that is NOT addressed to us,
 * it calls OfflineStorage.store_packet(). We persist it here so it survives
 * app restarts. When a new peer is discovered (BLE scan or WiFi mDNS), we
 * fetch all stored packets and re-broadcast them (Spray-and-Wait).
 */
@Entity(tableName = "mesh_packets")
data class MeshPacketEntity(
    @PrimaryKey val packetId: String,
    val senderPubKey: ByteArray,        // 65-byte X9.62
    val recipientPubKey: ByteArray,     // 65-byte X9.62
    val ttlHops: Int,                   // Remaining hop count
    val encryptedPayload: ByteArray,    // ChaCha20-Poly1305 ciphertext
    val storedAtMs: Long = System.currentTimeMillis(),
    val forwardCount: Int = 0           // How many times we've re-broadcast this
) {
    override fun equals(other: Any?) = (other as? MeshPacketEntity)?.packetId == packetId
    override fun hashCode() = packetId.hashCode()
}

/**
 * TransactionEntity — Persisted payment history.
 */
@Entity(tableName = "transactions")
data class TransactionEntity(
    @PrimaryKey val id: String,
    val senderWalletId: String,
    val recipientWalletId: String,
    val amountPaisa: Long,
    val memo: String,
    val timestamp: Long,
    val status: String  // "pending" | "sent" | "received" | "failed"
)

// ── DAOs ──────────────────────────────────────────────────────────────────────

@Dao
interface MeshPacketDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(packet: MeshPacketEntity)

    @Query("SELECT * FROM mesh_packets WHERE ttlHops > 0 ORDER BY storedAtMs ASC")
    suspend fun getAllForwardable(): List<MeshPacketEntity>

    @Query("SELECT * FROM mesh_packets WHERE packetId = :id")
    suspend fun getById(id: String): MeshPacketEntity?

    @Query("DELETE FROM mesh_packets WHERE packetId = :id")
    suspend fun delete(id: String)

    @Query("UPDATE mesh_packets SET forwardCount = forwardCount + 1, ttlHops = ttlHops - 1 WHERE packetId = :id")
    suspend fun markForwarded(id: String)

    /** Clean up packets that have been delivered (TTL exhausted or older than 7 days) */
    @Query("DELETE FROM mesh_packets WHERE ttlHops <= 0 OR storedAtMs < :cutoffMs")
    suspend fun pruneExpired(cutoffMs: Long)
}

@Dao
interface TransactionDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(txn: TransactionEntity)

    @Query("SELECT * FROM transactions ORDER BY timestamp DESC LIMIT 100")
    suspend fun getRecent(): List<TransactionEntity>

    @Query("UPDATE transactions SET status = :status WHERE id = :id")
    suspend fun updateStatus(id: String, status: String)
}

// ── Room Database ─────────────────────────────────────────────────────────────

@Database(
    entities = [MeshPacketEntity::class, TransactionEntity::class],
    version = 1,
    exportSchema = false
)
@TypeConverters(Converters::class)
abstract class AstralDatabase : RoomDatabase() {
    abstract fun meshPacketDao(): MeshPacketDao
    abstract fun transactionDao(): TransactionDao

    companion object {
        @Volatile private var INSTANCE: AstralDatabase? = null

        fun getInstance(context: Context): AstralDatabase {
            return INSTANCE ?: synchronized(this) {
                Room.databaseBuilder(
                    context.applicationContext,
                    AstralDatabase::class.java,
                    "astral_offline_db"
                )
                .fallbackToDestructiveMigration()  // Dev only — use proper migrations in prod
                .build()
                .also { INSTANCE = it }
            }
        }
    }
}

@ProvidedTypeConverter
class Converters {
    @TypeConverter fun fromByteArray(value: ByteArray): String = android.util.Base64.encodeToString(value, android.util.Base64.NO_WRAP)
    @TypeConverter fun toByteArray(value: String): ByteArray = android.util.Base64.decode(value, android.util.Base64.NO_WRAP)
}

// ── AstralStorage — implements Rust OfflineStorage trait ─────────────────────

/**
 * AstralStorage — the Room-backed implementation of the Rust `OfflineStorage` interface.
 *
 * This is what you pass to `AstralPaymentEngine.register_adapters()` via UniFFI.
 * The Rust core calls these methods when it needs to persist or retrieve packets
 * for the Spray-and-Wait mesh routing algorithm.
 *
 * Survives app restarts → enables true "carry and forward" from Delhi to New York.
 */
class AstralStorage(context: Context) {

    private val db = AstralDatabase.getInstance(context)
    private val scope = CoroutineScope(Dispatchers.IO)

    /** Store an incoming mesh packet that needs to be forwarded later. */
    fun storePacket(
        packetId: String,
        senderPubKey: ByteArray,
        recipientPubKey: ByteArray,
        ttlHops: Int,
        encryptedPayload: ByteArray
    ) {
        scope.launch {
            db.meshPacketDao().insert(
                MeshPacketEntity(packetId, senderPubKey, recipientPubKey, ttlHops, encryptedPayload)
            )
        }
    }

    /** Returns all stored packets that still have remaining TTL hops. */
    suspend fun getPacketsToForward(): List<MeshPacketEntity> = withContext(Dispatchers.IO) {
        db.meshPacketDao().getAllForwardable()
    }

    /** Mark a packet as forwarded (decrements TTL, increments forward count). */
    fun markForwarded(packetId: String) {
        scope.launch { db.meshPacketDao().markForwarded(packetId) }
    }

    /** Check if we've already seen this packet (deduplication). */
    suspend fun hasPacket(packetId: String): Boolean = withContext(Dispatchers.IO) {
        db.meshPacketDao().getById(packetId) != null
    }

    /** Housekeeping — remove expired and delivered packets. */
    fun pruneExpired() {
        val sevenDaysAgo = System.currentTimeMillis() - (7 * 24 * 60 * 60 * 1000L)
        scope.launch { db.meshPacketDao().pruneExpired(sevenDaysAgo) }
    }

    // ── Transaction history ────────────────────────────────────────────────────

    fun saveTransaction(txn: TransactionEntity) {
        scope.launch { db.transactionDao().insert(txn) }
    }

    suspend fun getRecentTransactions(): List<TransactionEntity> = withContext(Dispatchers.IO) {
        db.transactionDao().getRecent()
    }

    fun updateTransactionStatus(id: String, status: String) {
        scope.launch { db.transactionDao().updateStatus(id, status) }
    }
}
