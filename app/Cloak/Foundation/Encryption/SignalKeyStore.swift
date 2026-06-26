import Foundation
import GRDB
import LibSignalClient

/// GRDB-backed libsignal stores for identity + prekeys (architecture guide §7).
///
/// Persists key material into the SQLCipher-encrypted database opened by ``EncryptedDatabase``.
/// `SessionStore`/`SenderKeyStore` are deferred to Slice 2. All record blobs are stored via
/// `record.serialize()` and rehydrated with the matching `init(bytes:)` deserializer.
///
/// The libsignal store protocols are synchronous (`throws`) and take a `StoreContext`; this type
/// conforms to the exact installed signatures, including `saveIdentity` returning `IdentityChange`.
final class SignalKeyStore: IdentityKeyStore, PreKeyStore, SignedPreKeyStore {
    private let database: DatabaseQueue
    private let identity: IdentityKeyPair
    private let registrationId: UInt32

    /// - Parameters:
    ///   - database: The encrypted GRDB queue (from ``EncryptedDatabase``) holding key material.
    ///   - identity: This device's long-term identity key pair.
    ///   - registrationId: This device's libsignal registration id.
    init(database: DatabaseQueue, identity: IdentityKeyPair, registrationId: UInt32) throws {
        self.database = database
        self.identity = identity
        self.registrationId = registrationId
        try database.write { connection in
            try connection.execute(
                sql: "CREATE TABLE IF NOT EXISTS prekey(id INTEGER PRIMARY KEY, record BLOB NOT NULL)")
            try connection.execute(
                sql: """
                    CREATE TABLE IF NOT EXISTS signed_prekey(id INTEGER PRIMARY KEY, record BLOB NOT NULL)
                    """)
            try connection.execute(
                sql: "CREATE TABLE IF NOT EXISTS peer_identity(name TEXT PRIMARY KEY, key BLOB NOT NULL)")
            // Single-row table: this device's long-term identity keypair + registration id.
            try connection.execute(
                sql: """
                    CREATE TABLE IF NOT EXISTS local_identity(
                        id INTEGER PRIMARY KEY CHECK (id = 0),
                        keypair BLOB NOT NULL,
                        registration_id INTEGER NOT NULL)
                    """)
        }
    }

    // MARK: - Local identity persistence

    /// Persists this device's in-memory identity keypair + registration id into the encrypted store
    /// so a later launch can rebuild the store from disk via ``loadLocalIdentity(_:)``.
    func saveLocalIdentity() throws {
        let serialized = identity.serialize()
        let regId = registrationId
        try database.write { connection in
            try connection.execute(
                sql: """
                    INSERT OR REPLACE INTO local_identity(id, keypair, registration_id)
                    VALUES (0, ?, ?)
                    """,
                arguments: [serialized, Int(regId)])
        }
    }

    /// Reloads the persisted local identity keypair + registration id, or `nil` if none was saved.
    static func loadLocalIdentity(_ database: DatabaseQueue) throws -> (IdentityKeyPair, UInt32)? {
        let row = try database.read { connection in
            try Row.fetchOne(
                connection, sql: "SELECT keypair, registration_id FROM local_identity WHERE id = 0")
        }
        guard let row else { return nil }
        let keypair = try IdentityKeyPair(bytes: row["keypair"] as Data)
        let regId = UInt32(row["registration_id"] as Int)
        return (keypair, regId)
    }

    // MARK: - IdentityKeyStore

    func identityKeyPair(context: StoreContext) throws -> IdentityKeyPair { identity }

    func localRegistrationId(context: StoreContext) throws -> UInt32 { registrationId }

    func saveIdentity(
        _ identity: IdentityKey, for address: ProtocolAddress, context: StoreContext
    ) throws -> IdentityChange {
        let serialized = identity.serialize()
        return try database.write { connection in
            let existing = try Data.fetchOne(
                connection, sql: "SELECT key FROM peer_identity WHERE name = ?",
                arguments: [address.name])
            try connection.execute(
                sql: "INSERT OR REPLACE INTO peer_identity(name, key) VALUES (?, ?)",
                arguments: [address.name, serialized])
            if let existing, existing != serialized {
                return .replacedExisting
            }
            return .newOrUnchanged
        }
    }

    func isTrustedIdentity(
        _ identity: IdentityKey, for address: ProtocolAddress, direction: Direction,
        context: StoreContext
    ) throws -> Bool {
        // Trust-on-first-use: an unknown peer is trusted, a known peer must match its stored key.
        guard let stored = try self.identity(for: address, context: context) else { return true }
        return stored == identity
    }

    func identity(for address: ProtocolAddress, context: StoreContext) throws -> IdentityKey? {
        let data = try database.read {
            try Data.fetchOne(
                $0, sql: "SELECT key FROM peer_identity WHERE name = ?", arguments: [address.name])
        }
        return try data.map { try IdentityKey(bytes: $0) }
    }

    // MARK: - PreKeyStore

    func storePreKey(_ record: PreKeyRecord, id: UInt32, context: StoreContext) throws {
        try database.write {
            try $0.execute(
                sql: "INSERT OR REPLACE INTO prekey(id, record) VALUES (?, ?)",
                arguments: [Int(id), record.serialize()])
        }
    }

    func loadPreKey(id: UInt32, context: StoreContext) throws -> PreKeyRecord {
        guard
            let data = try database.read({
                try Data.fetchOne(
                    $0, sql: "SELECT record FROM prekey WHERE id = ?", arguments: [Int(id)])
            })
        else { throw SignalError.invalidKeyIdentifier("no prekey \(id)") }
        return try PreKeyRecord(bytes: data)
    }

    func removePreKey(id: UInt32, context: StoreContext) throws {
        try database.write {
            try $0.execute(sql: "DELETE FROM prekey WHERE id = ?", arguments: [Int(id)])
        }
    }

    // MARK: - SignedPreKeyStore

    // Fully qualified: the app defines its own value-type `SignedPreKeyRecord` (Task E3), which
    // would otherwise shadow libsignal's record type inside this module.
    func storeSignedPreKey(
        _ record: LibSignalClient.SignedPreKeyRecord, id: UInt32, context: StoreContext
    ) throws {
        try database.write {
            try $0.execute(
                sql: "INSERT OR REPLACE INTO signed_prekey(id, record) VALUES (?, ?)",
                arguments: [Int(id), record.serialize()])
        }
    }

    func loadSignedPreKey(id: UInt32, context: StoreContext) throws
        -> LibSignalClient.SignedPreKeyRecord {
        guard
            let data = try database.read({
                try Data.fetchOne(
                    $0, sql: "SELECT record FROM signed_prekey WHERE id = ?", arguments: [Int(id)])
            })
        else { throw SignalError.invalidKeyIdentifier("no signed prekey \(id)") }
        return try LibSignalClient.SignedPreKeyRecord(bytes: data)
    }
}
