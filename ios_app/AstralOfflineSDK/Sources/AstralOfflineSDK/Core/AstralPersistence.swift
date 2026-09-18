import CoreData
import Foundation
import os.log

/**
 * AstralPersistenceController — CoreData stack for mesh packet + transaction persistence.
 *
 * Two entities:
 *  - MeshPacket: forwarding queue for Spray-and-Wait opportunistic routing
 *  - AstralTransactionRecord: payment history
 *
 * Survives app restarts — a node carrying a packet from Delhi can forward it
 * to New York days later, even if the app was killed and relaunched.
 */
public final class AstralPersistenceController {

    public static let shared = AstralPersistenceController()
    private let log = Logger(subsystem: "com.astralnetwork.sdk", category: "CoreData")

    public let container: NSPersistentContainer

    private init() {
        container = NSPersistentContainer(name: "AstralOffline", managedObjectModel: Self.buildModel())

        // Store in App Group container so extensions can read it
        let storeURL = FileManager.default
            .urls(for: .applicationSupportDirectory, in: .userDomainMask)
            .first!
            .appendingPathComponent("AstralOffline.sqlite")

        let description = NSPersistentStoreDescription(url: storeURL)
        description.setOption(FileProtectionType.completeUntilFirstUserAuthentication as NSObject,
                              forKey: NSPersistentStoreFileProtectionKey)  // Encrypted at rest
        container.persistentStoreDescriptions = [description]

        container.loadPersistentStores { _, error in
            if let error { fatalError("CoreData load failed: \(error)") }
        }
        container.viewContext.automaticallyMergesChangesFromParent = true
        log.info("CoreData stack initialized ✅")
    }

    // ── CoreData Model (built in code — no .xcdatamodeld needed) ──────────────

    private static func buildModel() -> NSManagedObjectModel {
        let model = NSManagedObjectModel()

        // MeshPacketRecord entity
        let packetEntity = NSEntityDescription()
        packetEntity.name = "MeshPacketRecord"
        packetEntity.managedObjectClassName = "MeshPacketRecord"

        let packetIdAttr = NSAttributeDescription(); packetIdAttr.name = "packetId"; packetIdAttr.attributeType = .stringAttributeType
        let senderPubKeyAttr = NSAttributeDescription(); senderPubKeyAttr.name = "senderPubKey"; senderPubKeyAttr.attributeType = .binaryDataAttributeType
        let recipientPubKeyAttr = NSAttributeDescription(); recipientPubKeyAttr.name = "recipientPubKey"; recipientPubKeyAttr.attributeType = .binaryDataAttributeType
        let ttlAttr = NSAttributeDescription(); ttlAttr.name = "ttlHops"; ttlAttr.attributeType = .integer32AttributeType
        let payloadAttr = NSAttributeDescription(); payloadAttr.name = "encryptedPayload"; payloadAttr.attributeType = .binaryDataAttributeType
        let storedAtAttr = NSAttributeDescription(); storedAtAttr.name = "storedAt"; storedAtAttr.attributeType = .dateAttributeType
        let fwdCountAttr = NSAttributeDescription(); fwdCountAttr.name = "forwardCount"; fwdCountAttr.attributeType = .integer32AttributeType

        packetEntity.properties = [packetIdAttr, senderPubKeyAttr, recipientPubKeyAttr, ttlAttr, payloadAttr, storedAtAttr, fwdCountAttr]

        // TransactionRecord entity
        let txnEntity = NSEntityDescription()
        txnEntity.name = "TransactionRecord"
        txnEntity.managedObjectClassName = "TransactionRecord"

        let txnIdAttr = NSAttributeDescription(); txnIdAttr.name = "id"; txnIdAttr.attributeType = .stringAttributeType
        let senderAttr = NSAttributeDescription(); senderAttr.name = "senderWalletId"; senderAttr.attributeType = .stringAttributeType
        let recipientAttr = NSAttributeDescription(); recipientAttr.name = "recipientWalletId"; recipientAttr.attributeType = .stringAttributeType
        let amountAttr = NSAttributeDescription(); amountAttr.name = "amountPaisa"; amountAttr.attributeType = .integer64AttributeType
        let memoAttr = NSAttributeDescription(); memoAttr.name = "memo"; memoAttr.attributeType = .stringAttributeType
        let tsAttr = NSAttributeDescription(); tsAttr.name = "timestamp"; tsAttr.attributeType = .dateAttributeType
        let statusAttr = NSAttributeDescription(); statusAttr.name = "status"; statusAttr.attributeType = .stringAttributeType

        txnEntity.properties = [txnIdAttr, senderAttr, recipientAttr, amountAttr, memoAttr, tsAttr, statusAttr]

        model.entities = [packetEntity, txnEntity]
        return model
    }

    // ── Mesh Packet Operations ─────────────────────────────────────────────────

    /// Store an incoming mesh packet for later forwarding.
    public func storeMeshPacket(
        packetId: String, senderPubKey: Data, recipientPubKey: Data,
        ttlHops: Int, encryptedPayload: Data
    ) {
        let ctx = container.newBackgroundContext()
        ctx.perform {
            // Dedup check — don't store the same packet twice
            let fetch = NSFetchRequest<NSManagedObject>(entityName: "MeshPacketRecord")
            fetch.predicate = NSPredicate(format: "packetId == %@", packetId)
            if (try? ctx.count(for: fetch)) ?? 0 > 0 { return }

            let record = NSEntityDescription.insertNewObject(forEntityName: "MeshPacketRecord", into: ctx)
            record.setValue(packetId, forKey: "packetId")
            record.setValue(senderPubKey, forKey: "senderPubKey")
            record.setValue(recipientPubKey, forKey: "recipientPubKey")
            record.setValue(ttlHops, forKey: "ttlHops")
            record.setValue(encryptedPayload, forKey: "encryptedPayload")
            record.setValue(Date(), forKey: "storedAt")
            record.setValue(0, forKey: "forwardCount")
            try? ctx.save()
        }
    }

    /// Fetch all packets with remaining TTL (to broadcast when a peer is found).
    public func getPacketsToForward() async -> [(id: String, payload: Data, ttl: Int)] {
        await withCheckedContinuation { continuation in
            let ctx = container.newBackgroundContext()
            ctx.perform {
                let fetch = NSFetchRequest<NSManagedObject>(entityName: "MeshPacketRecord")
                fetch.predicate = NSPredicate(format: "ttlHops > 0")
                fetch.sortDescriptors = [NSSortDescriptor(key: "storedAt", ascending: true)]

                let results = (try? ctx.fetch(fetch)) ?? []
                let packets = results.compactMap { record -> (String, Data, Int)? in
                    guard let id = record.value(forKey: "packetId") as? String,
                          let payload = record.value(forKey: "encryptedPayload") as? Data,
                          let ttl = record.value(forKey: "ttlHops") as? Int else { return nil }
                    return (id, payload, ttl)
                }
                continuation.resume(returning: packets)
            }
        }
    }

    /// Decrement TTL and increment forward count after broadcasting a packet.
    public func markForwarded(packetId: String) {
        let ctx = container.newBackgroundContext()
        ctx.perform {
            let fetch = NSFetchRequest<NSManagedObject>(entityName: "MeshPacketRecord")
            fetch.predicate = NSPredicate(format: "packetId == %@", packetId)
            if let record = try? ctx.fetch(fetch).first {
                let ttl = (record.value(forKey: "ttlHops") as? Int ?? 0)
                let count = (record.value(forKey: "forwardCount") as? Int ?? 0)
                record.setValue(max(0, ttl - 1), forKey: "ttlHops")
                record.setValue(count + 1, forKey: "forwardCount")
                try? ctx.save()
            }
        }
    }

    /// Remove packets that have expired (TTL = 0 or older than 7 days).
    public func pruneExpired() {
        let ctx = container.newBackgroundContext()
        ctx.perform {
            let cutoff = Date().addingTimeInterval(-7 * 24 * 60 * 60)
            let fetch = NSFetchRequest<NSFetchRequestResult>(entityName: "MeshPacketRecord")
            fetch.predicate = NSPredicate(format: "ttlHops <= 0 OR storedAt < %@", cutoff as NSDate)
            let delete = NSBatchDeleteRequest(fetchRequest: fetch)
            try? ctx.execute(delete)
            try? ctx.save()
        }
    }

    // ── Transaction History ────────────────────────────────────────────────────

    public func saveTransaction(_ txn: AstralTransaction) {
        let ctx = container.newBackgroundContext()
        ctx.perform {
            let record = NSEntityDescription.insertNewObject(forEntityName: "TransactionRecord", into: ctx)
            record.setValue(txn.id, forKey: "id")
            record.setValue(txn.senderID.id, forKey: "senderWalletId")
            record.setValue(txn.recipientID.id, forKey: "recipientWalletId")
            record.setValue(txn.amountPaisa, forKey: "amountPaisa")
            record.setValue(txn.memo, forKey: "memo")
            record.setValue(txn.timestamp, forKey: "timestamp")
            record.setValue(txn.status.rawValue, forKey: "status")
            try? ctx.save()
        }
    }

    public func getRecentTransactions(limit: Int = 50) async -> [AstralTransaction] {
        await withCheckedContinuation { continuation in
            let ctx = container.newBackgroundContext()
            ctx.perform {
                let fetch = NSFetchRequest<NSManagedObject>(entityName: "TransactionRecord")
                fetch.sortDescriptors = [NSSortDescriptor(key: "timestamp", ascending: false)]
                fetch.fetchLimit = limit

                let results = (try? ctx.fetch(fetch)) ?? []
                let txns = results.compactMap { record -> AstralTransaction? in
                    guard let id = record.value(forKey: "id") as? String,
                          let senderId = record.value(forKey: "senderWalletId") as? String,
                          let recipientId = record.value(forKey: "recipientWalletId") as? String,
                          let amount = record.value(forKey: "amountPaisa") as? Int64,
                          let ts = record.value(forKey: "timestamp") as? Date,
                          let statusStr = record.value(forKey: "status") as? String else { return nil }
                    return AstralTransaction(
                        id: id,
                        senderID: WalletID(id: senderId),
                        recipientID: WalletID(id: recipientId),
                        amountPaisa: amount,
                        timestamp: ts,
                        memo: (record.value(forKey: "memo") as? String) ?? "",
                        status: AstralTransaction.Status(rawValue: statusStr) ?? .pending
                    )
                }
                continuation.resume(returning: txns)
            }
        }
    }
}
