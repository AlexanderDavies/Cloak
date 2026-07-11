import Testing
import Foundation
import GRDB
import LibSignalClient
@testable import Cloak

/// Tests for the `KyberPreKeyStore` conformance on `SignalKeyStore`.
///
/// Kept in a separate file because `SignalKeyStoreTests` is at the 250-line limit.
@Suite struct KyberPreKeyStoreTests {
    private func freshStore(registrationId: UInt32 = 7) throws -> SignalKeyStore {
        let path = NSTemporaryDirectory() + "ks-kyber-\(UUID().uuidString).sqlite"
        let queue = try EncryptedDatabase.open(path: path, passphrase: "k")
        return try SignalKeyStore(
            database: queue, identity: IdentityKeyPair.generate(), registrationId: registrationId)
    }

    @Test func storesAndLoadsKyberPreKey() throws {
        let store = try freshStore()
        let kyberPair = KEMKeyPair.generate()
        let identity = IdentityKeyPair.generate()
        let kyberSig = identity.privateKey.generateSignature(
            message: kyberPair.publicKey.serialize())
        let record = try KyberPreKeyRecord(
            id: 77, timestamp: 1_700_000_000, keyPair: kyberPair, signature: kyberSig)

        try store.storeKyberPreKey(record, id: 77, context: NullContext())
        let loaded = try store.loadKyberPreKey(id: 77, context: NullContext())
        #expect(loaded.id == 77)
        #expect(try loaded.publicKey().serialize() == kyberPair.publicKey.serialize())
    }

    @Test func loadingMissingKyberPreKeyThrows() throws {
        let store = try freshStore()
        #expect(throws: SignalError.self) {
            try store.loadKyberPreKey(id: 999, context: NullContext())
        }
    }

    @Test func markKyberPreKeyUsedIsNoOp() throws {
        // markKyberPreKeyUsed is a no-op for last-resort keys (they are reusable).
        // Assert the key is still loadable after the call.
        let store = try freshStore()
        let kyberPair = KEMKeyPair.generate()
        let identity = IdentityKeyPair.generate()
        let kyberSig = identity.privateKey.generateSignature(
            message: kyberPair.publicKey.serialize())
        let record = try KyberPreKeyRecord(
            id: 88, timestamp: 1_700_000_000, keyPair: kyberPair, signature: kyberSig)
        try store.storeKyberPreKey(record, id: 88, context: NullContext())
        // Exact signature from the installed LibSignalClient 0.96.2 pod:
        // markKyberPreKeyUsed(id:signedPreKeyId:baseKey:context:)
        let dummyKey = PrivateKey.generate().publicKey
        try store.markKyberPreKeyUsed(
            id: 88, signedPreKeyId: 1, baseKey: dummyKey, context: NullContext())
        // Key must still be available — it is last-resort (reusable).
        let loaded = try store.loadKyberPreKey(id: 88, context: NullContext())
        #expect(loaded.id == 88)
    }

    // MARK: - DeviceKeyVault kyber round-trip

    @Test func vaultPersistsKyberPreKey() throws {
        let path = NSTemporaryDirectory() + "ks-kyber-vault-\(UUID().uuidString).sqlite"
        let queue = try EncryptedDatabase.open(path: path, passphrase: "k")
        let keys = try SignalKeyGenerator.generate(oneTimeCount: 1)

        let vault = GRDBDeviceKeyVault(database: queue)
        try vault.persist(keys)

        let (reloadedIdentity, regId) = try #require(try SignalKeyStore.loadLocalIdentity(queue))
        let store = try SignalKeyStore(
            database: queue, identity: reloadedIdentity, registrationId: regId)

        let loaded = try store.loadKyberPreKey(id: keys.kyberPreKeyId, context: NullContext())
        #expect(try loaded.publicKey().serialize() == keys.kyberPreKey.publicKey.serialize())
    }
}
