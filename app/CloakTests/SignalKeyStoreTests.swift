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

    // MARK: - SessionStore

    /// Builds a real `SessionRecord` by running Alice through Bob's `PreKeyBundle`.
    ///
    /// - Returns: A tuple of the established session and the address (Bob's) it was stored under.
    private func makeSession() throws -> (SessionRecord, ProtocolAddress) {
        let bobAddress = try ProtocolAddress(name: "bob", deviceId: 1)
        let aliceAddress = try ProtocolAddress(name: "alice", deviceId: 1)

        // Bob's long-term identity
        let bobIdentity = IdentityKeyPair.generate()
        let bobRegId = UInt32.random(in: 1...0x3FFF)

        // Bob's signed EC prekey
        let signedECKeyPair = PrivateKey.generate()
        let ecSig = bobIdentity.privateKey.generateSignature(
            message: signedECKeyPair.publicKey.serialize())
        let signedPreKeyRecord = try LibSignalClient.SignedPreKeyRecord(
            id: 1, timestamp: 1_700_000_000, privateKey: signedECKeyPair, signature: ecSig)

        // Bob's one-time EC prekey
        let oneTimeKeyPair = PrivateKey.generate()
        let oneTimePreKeyRecord = try PreKeyRecord(id: 1, privateKey: oneTimeKeyPair)

        // Bob's Kyber prekey (PQXDH mandatory in 0.96.2)
        let kyberPair = KEMKeyPair.generate()
        let kyberSig = bobIdentity.privateKey.generateSignature(
            message: kyberPair.publicKey.serialize())
        let kyberPreKeyRecord = try KyberPreKeyRecord(
            id: 1, timestamp: 1_700_000_000, keyPair: kyberPair, signature: kyberSig)

        // Bob's in-memory store (needed so processPreKeyBundle can call back into it)
        let bobStore = InMemorySignalProtocolStore(identity: bobIdentity, registrationId: bobRegId)
        try bobStore.storeSignedPreKey(signedPreKeyRecord, id: 1, context: NullContext())
        try bobStore.storePreKey(oneTimePreKeyRecord, id: 1, context: NullContext())
        try bobStore.storeKyberPreKey(kyberPreKeyRecord, id: 1, context: NullContext())

        // Assemble Bob's PreKeyBundle
        let bundle = try PreKeyBundle(
            registrationId: bobRegId,
            deviceId: 1,
            prekeyId: 1,
            prekey: oneTimeKeyPair.publicKey,
            signedPrekeyId: 1,
            signedPrekey: signedECKeyPair.publicKey,
            signedPrekeySignature: ecSig,
            identity: bobIdentity.identityKey,
            kyberPrekeyId: 1,
            kyberPrekey: kyberPair.publicKey,
            kyberPrekeySignature: kyberSig)

        // Alice processes the bundle — this writes a SessionRecord into aliceStore
        let aliceStore = InMemorySignalProtocolStore()
        try processPreKeyBundle(
            bundle,
            for: bobAddress,
            ourAddress: aliceAddress,
            sessionStore: aliceStore,
            identityStore: aliceStore,
            context: NullContext())

        let session = try #require(try aliceStore.loadSession(for: bobAddress, context: NullContext()))
        return (session, bobAddress)
    }

    @Test func storesAndLoadsSession() throws {
        let store = try freshStore()
        let (session, address) = try makeSession()

        try store.storeSession(session, for: address, context: NullContext())
        let loaded = try store.loadSession(for: address, context: NullContext())
        let result = try #require(loaded)
        #expect(result.serialize() == session.serialize())
    }

    @Test func loadSessionForUnknownAddressReturnsNil() throws {
        let store = try freshStore()
        let address = try ProtocolAddress(name: "stranger-session", deviceId: 1)
        #expect(try store.loadSession(for: address, context: NullContext()) == nil)
    }

    @Test func loadExistingSessionsThrowsWhenAddressMissing() throws {
        let store = try freshStore()
        let address = try ProtocolAddress(name: "ghost-session", deviceId: 1)
        #expect(throws: SignalError.self) {
            try store.loadExistingSessions(for: [address], context: NullContext())
        }
    }

    @Test func loadExistingSessionsReturnsAllWhenPresent() throws {
        let store = try freshStore()
        let (session, address) = try makeSession()

        try store.storeSession(session, for: address, context: NullContext())
        let results = try store.loadExistingSessions(for: [address], context: NullContext())
        #expect(results.count == 1)
        #expect(results[0].serialize() == session.serialize())
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
