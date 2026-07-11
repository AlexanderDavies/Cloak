import Foundation
import GRDB
import LibSignalClient

/// Stores freshly generated device private key material into the encrypted store (guide §7).
///
/// This is the seam the registration orchestration depends on, so the private keys backing the
/// published public bundle survive across launches and can be served to libsignal later.
protocol DeviceKeyVault: Sendable {
    /// Persists the identity keypair, signed prekey, and one-time prekeys into the encrypted store.
    func persist(_ keys: GeneratedDeviceKeys) throws
    /// Whether a local identity has already been persisted.
    func hasIdentity() -> Bool
}

/// GRDB/SQLCipher-backed ``DeviceKeyVault`` over the ``EncryptedDatabase`` queue.
struct GRDBDeviceKeyVault: DeviceKeyVault {
    private let database: DatabaseQueue

    init(database: DatabaseQueue) {
        self.database = database
    }

    func persist(_ keys: GeneratedDeviceKeys) throws {
        let store = try SignalKeyStore(
            database: database,
            identity: keys.identityKeyPair,
            registrationId: keys.registrationId)
        try store.saveLocalIdentity()

        for prekey in keys.oneTimePreKeys {
            let record = try LibSignalClient.PreKeyRecord(id: prekey.id, privateKey: prekey.keyPair)
            try store.storePreKey(record, id: prekey.id, context: NullContext())
        }

        let timestamp = UInt64(Date().timeIntervalSince1970 * 1000)
        let signedRecord = try LibSignalClient.SignedPreKeyRecord(
            id: keys.signedPreKey.id,
            timestamp: timestamp,
            privateKey: keys.signedPreKey.keyPair,
            signature: keys.signedPreKeySignature)
        try store.storeSignedPreKey(signedRecord, id: keys.signedPreKey.id, context: NullContext())

        // Persist the last-resort Kyber (PQXDH) prekey private material.
        let kyberRecord = try KyberPreKeyRecord(
            id: keys.kyberPreKeyId,
            timestamp: timestamp,
            keyPair: keys.kyberPreKey,
            signature: keys.kyberPreKeySignature)
        try store.storeKyberPreKey(kyberRecord, id: keys.kyberPreKeyId, context: NullContext())
    }

    func hasIdentity() -> Bool {
        // `try?` yields a double optional; flatten so a successful-but-empty load and a throw both
        // resolve to `false`.
        ((try? SignalKeyStore.loadLocalIdentity(database)) ?? nil) != nil
    }
}
