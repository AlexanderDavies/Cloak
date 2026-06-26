import Testing
import Foundation
import GRDB
import LibSignalClient
@testable import Cloak

@Suite struct SignalKeyStoreTests {
    private func freshStore(registrationId: UInt32 = 7) throws -> SignalKeyStore {
        let path = NSTemporaryDirectory() + "ks-\(UUID().uuidString).sqlite"
        let queue = try EncryptedDatabase.open(path: path, passphrase: "k")
        return try SignalKeyStore(
            database: queue, identity: IdentityKeyPair.generate(), registrationId: registrationId)
    }

    // MARK: - IdentityKeyStore

    @Test func reportsLocalRegistrationId() throws {
        let store = try freshStore(registrationId: 7)
        #expect(try store.localRegistrationId(context: NullContext()) == 7)
    }

    @Test func identityKeyPairReturnsConfiguredIdentity() throws {
        let identity = IdentityKeyPair.generate()
        let path = NSTemporaryDirectory() + "ks-\(UUID().uuidString).sqlite"
        let queue = try EncryptedDatabase.open(path: path, passphrase: "k")
        let store = try SignalKeyStore(database: queue, identity: identity, registrationId: 1)
        #expect(
            try store.identityKeyPair(context: NullContext()).serialize() == identity.serialize())
    }

    @Test func savesAndLoadsPeerIdentity() throws {
        let store = try freshStore()
        let address = try ProtocolAddress(name: "alice", deviceId: 1)
        let peerKey = IdentityKeyPair.generate().identityKey

        let change = try store.saveIdentity(peerKey, for: address, context: NullContext())
        #expect(change == .newOrUnchanged)

        let loaded = try store.identity(for: address, context: NullContext())
        #expect(loaded?.serialize() == peerKey.serialize())
    }

    @Test func saveIdentityReportsReplacementOnChange() throws {
        let store = try freshStore()
        let address = try ProtocolAddress(name: "bob", deviceId: 1)
        let first = IdentityKeyPair.generate().identityKey
        let second = IdentityKeyPair.generate().identityKey

        _ = try store.saveIdentity(first, for: address, context: NullContext())
        let change = try store.saveIdentity(second, for: address, context: NullContext())
        #expect(change == .replacedExisting)

        let loaded = try store.identity(for: address, context: NullContext())
        #expect(loaded?.serialize() == second.serialize())
    }

    @Test func reSavingSameIdentityIsUnchanged() throws {
        let store = try freshStore()
        let address = try ProtocolAddress(name: "carol", deviceId: 1)
        let key = IdentityKeyPair.generate().identityKey

        _ = try store.saveIdentity(key, for: address, context: NullContext())
        let change = try store.saveIdentity(key, for: address, context: NullContext())
        #expect(change == .newOrUnchanged)
    }

    @Test func identityForUnknownAddressIsNil() throws {
        let store = try freshStore()
        let address = try ProtocolAddress(name: "stranger", deviceId: 1)
        #expect(try store.identity(for: address, context: NullContext()) == nil)
    }

    @Test func isTrustedIdentityTrustsUnknownPeers() throws {
        let store = try freshStore()
        let address = try ProtocolAddress(name: "dave", deviceId: 1)
        let key = IdentityKeyPair.generate().identityKey
        #expect(
            try store.isTrustedIdentity(
                key, for: address, direction: .sending, context: NullContext()))
    }

    // MARK: - PreKeyStore

    @Test func storesAndLoadsPreKey() throws {
        let store = try freshStore()
        let privateKey = PrivateKey.generate()
        let record = try PreKeyRecord(id: 1, privateKey: privateKey)
        try store.storePreKey(record, id: 1, context: NullContext())
        let loaded = try store.loadPreKey(id: 1, context: NullContext())
        #expect(try loaded.publicKey().serialize() == privateKey.publicKey.serialize())
    }

    @Test func removesPreKey() throws {
        let store = try freshStore()
        let record = try PreKeyRecord(id: 2, privateKey: PrivateKey.generate())
        try store.storePreKey(record, id: 2, context: NullContext())
        try store.removePreKey(id: 2, context: NullContext())
        #expect(throws: SignalError.self) {
            try store.loadPreKey(id: 2, context: NullContext())
        }
    }

    @Test func loadingMissingPreKeyThrows() throws {
        let store = try freshStore()
        #expect(throws: SignalError.self) {
            try store.loadPreKey(id: 999, context: NullContext())
        }
    }

    // MARK: - SignedPreKeyStore

    @Test func storesAndLoadsSignedPreKey() throws {
        let store = try freshStore()
        let identity = IdentityKeyPair.generate()
        let signedKey = PrivateKey.generate()
        let signature = identity.privateKey.generateSignature(
            message: signedKey.publicKey.serialize())
        let record = try LibSignalClient.SignedPreKeyRecord(
            id: 5, timestamp: 1_700_000_000, privateKey: signedKey, signature: signature)

        try store.storeSignedPreKey(record, id: 5, context: NullContext())
        let loaded = try store.loadSignedPreKey(id: 5, context: NullContext())
        #expect(try loaded.publicKey().serialize() == signedKey.publicKey.serialize())
        #expect(loaded.id == 5)
    }

    @Test func loadingMissingSignedPreKeyThrows() throws {
        let store = try freshStore()
        #expect(throws: SignalError.self) {
            try store.loadSignedPreKey(id: 999, context: NullContext())
        }
    }

    // MARK: - Local identity persistence

    @Test func savesAndReloadsLocalIdentityAcrossStores() throws {
        let path = NSTemporaryDirectory() + "ks-\(UUID().uuidString).sqlite"
        let queue = try EncryptedDatabase.open(path: path, passphrase: "k")
        let identity = IdentityKeyPair.generate()

        let store = try SignalKeyStore(database: queue, identity: identity, registrationId: 42)
        try store.saveLocalIdentity()

        let loaded = try SignalKeyStore.loadLocalIdentity(queue)
        let reloaded = try #require(loaded)
        #expect(reloaded.0.serialize() == identity.serialize())
        #expect(reloaded.1 == 42)
    }

    @Test func loadLocalIdentityIsNilWhenNoneStored() throws {
        let path = NSTemporaryDirectory() + "ks-\(UUID().uuidString).sqlite"
        let queue = try EncryptedDatabase.open(path: path, passphrase: "k")
        // Ensure the schema exists without saving an identity.
        _ = try SignalKeyStore(database: queue, identity: IdentityKeyPair.generate(), registrationId: 1)
        #expect(try SignalKeyStore.loadLocalIdentity(queue) == nil)
    }

    @Test func storeRebuiltFromDbReportsPersistedIdentity() throws {
        let path = NSTemporaryDirectory() + "ks-\(UUID().uuidString).sqlite"
        let queue = try EncryptedDatabase.open(path: path, passphrase: "k")
        let identity = IdentityKeyPair.generate()
        let store = try SignalKeyStore(database: queue, identity: identity, registrationId: 9)
        try store.saveLocalIdentity()

        let (reloadedIdentity, regId) = try #require(try SignalKeyStore.loadLocalIdentity(queue))
        let rebuilt = try SignalKeyStore(
            database: queue, identity: reloadedIdentity, registrationId: regId)
        #expect(try rebuilt.localRegistrationId(context: NullContext()) == 9)
        #expect(
            try rebuilt.identityKeyPair(context: NullContext()).serialize() == identity.serialize())
    }

    // MARK: - DeviceKeyVault round-trip

    @Test func vaultPersistedPrivateKeysSurviveAndRehydrate() throws {
        let path = NSTemporaryDirectory() + "ks-\(UUID().uuidString).sqlite"
        let queue = try EncryptedDatabase.open(path: path, passphrase: "k")
        let keys = try SignalKeyGenerator.generate(oneTimeCount: 3)

        let vault = GRDBDeviceKeyVault(database: queue)
        #expect(vault.hasIdentity() == false)
        try vault.persist(keys)
        #expect(vault.hasIdentity())

        // Rebuild a store from the reloaded identity, then read the persisted prekeys back.
        let (reloadedIdentity, regId) = try #require(try SignalKeyStore.loadLocalIdentity(queue))
        let store = try SignalKeyStore(
            database: queue, identity: reloadedIdentity, registrationId: regId)

        for prekey in keys.oneTimePreKeys {
            let loaded = try store.loadPreKey(id: prekey.id, context: NullContext())
            #expect(try loaded.publicKey().serialize() == prekey.keyPair.publicKey.serialize())
        }

        let signed = try store.loadSignedPreKey(id: keys.signedPreKey.id, context: NullContext())
        #expect(
            try signed.publicKey().serialize() == keys.signedPreKey.keyPair.publicKey.serialize())
    }
}
