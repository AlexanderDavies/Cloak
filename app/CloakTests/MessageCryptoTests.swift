import Testing
import Foundation
import GRDB
import LibSignalClient
@testable import Cloak

/// Tests for `MessageCrypto` — Signal encrypt/decrypt over PreKey and normal message types.
///
/// Alice (real `SignalKeyStore`) establishes a PQXDH outbound session with Bob
/// (real `SignalKeyStore` with stored private prekeys). Both sides use `MessageCrypto`,
/// exercising the PreKey and normal Signal message code paths in a true round-trip.
@Suite struct MessageCryptoTests {

    // MARK: - Helpers

    /// Opens a fresh encrypted store and returns the store + address for `sub`.
    private func freshStore(sub: String, regId: UInt32) throws -> (SignalKeyStore, ProtocolAddress) {
        let path = NSTemporaryDirectory() + "mc-\(UUID().uuidString).sqlite"
        let queue = try EncryptedDatabase.open(path: path, passphrase: "k")
        let identity = IdentityKeyPair.generate()
        let store = try SignalKeyStore(database: queue, identity: identity, registrationId: regId)
        let address = try ProtocolAddress(name: sub, deviceId: 1)
        return (store, address)
    }

    /// Bob's store (with private prekeys stored) and the matching `RemotePreKeyBundle`
    /// that Alice will process to establish a session.
    private struct BobSetup {
        let store: SignalKeyStore
        let address: ProtocolAddress
        let bundle: RemotePreKeyBundle
    }

    /// Generates Bob's key material, stores the private prekeys in a real `SignalKeyStore`,
    /// and returns both the store and the public `RemotePreKeyBundle` Alice will consume.
    ///
    /// The private prekeys in Bob's store match exactly the public keys in the bundle, so
    /// Bob can call `signalDecryptPreKey` successfully when Alice's first message arrives.
    private func makeBob() throws -> BobSetup {
        let path = NSTemporaryDirectory() + "mc-bob-\(UUID().uuidString).sqlite"
        let queue = try EncryptedDatabase.open(path: path, passphrase: "k")
        let identity = IdentityKeyPair.generate()
        let regId = UInt32.random(in: 1...0x3FFF)
        let store = try SignalKeyStore(database: queue, identity: identity, registrationId: regId)
        let address = try ProtocolAddress(name: "bob", deviceId: 1)
        let timestamp = UInt64(Date().timeIntervalSince1970 * 1000)

        // Signed EC prekey — private key persisted in store, public key goes in bundle.
        let signedECPriv = PrivateKey.generate()
        let ecSig = identity.privateKey.generateSignature(message: signedECPriv.publicKey.serialize())
        let signedRecord = try LibSignalClient.SignedPreKeyRecord(
            id: 1, timestamp: timestamp, privateKey: signedECPriv, signature: ecSig)
        try store.storeSignedPreKey(signedRecord, id: 1, context: NullContext())

        // One-time EC prekey — private key persisted in store, public key goes in bundle.
        let oneTimePriv = PrivateKey.generate()
        let oneTimeRecord = try LibSignalClient.PreKeyRecord(id: 1, privateKey: oneTimePriv)
        try store.storePreKey(oneTimeRecord, id: 1, context: NullContext())

        // Kyber prekey — key pair persisted in store, public key goes in bundle.
        let kyberPair = KEMKeyPair.generate()
        let kyberSig = identity.privateKey.generateSignature(message: kyberPair.publicKey.serialize())
        let kyberRecord = try KyberPreKeyRecord(
            id: 1, timestamp: timestamp, keyPair: kyberPair, signature: kyberSig)
        try store.storeKyberPreKey(kyberRecord, id: 1, context: NullContext())

        let bundle = RemotePreKeyBundle(
            registrationId: regId,
            deviceId: 1,
            identityKey: identity.identityKey.serialize().base64EncodedString(),
            signedPreKey: RemotePreKeyBundle.SignedPreKey(
                keyId: 1,
                publicKey: signedECPriv.publicKey.serialize().base64EncodedString(),
                signature: ecSig.base64EncodedString()),
            oneTimePreKey: RemotePreKeyBundle.OneTimePreKey(
                keyId: 1,
                publicKey: oneTimePriv.publicKey.serialize().base64EncodedString()),
            kyberPreKey: RemotePreKeyBundle.KyberPreKey(
                keyId: 1,
                publicKey: kyberPair.publicKey.serialize().base64EncodedString(),
                signature: kyberSig.base64EncodedString()))

        return BobSetup(store: store, address: address, bundle: bundle)
    }

    // MARK: - Tests

    /// End-to-end round-trip covering both message type code paths:
    ///
    /// 1. Alice's first message to Bob is a `PreKeySignalMessage` (type == `.preKey`).
    ///    Bob decrypts it, which establishes his inbound session.
    /// 2. Bob's reply uses the normal Double-Ratchet path (type == `.normal`).
    ///    Alice decrypts it to confirm the full round-trip.
    @Test func roundTripPreKeyThenNormal() throws {
        let aliceRegId = UInt32.random(in: 1...0x3FFF)
        let (aliceStore, aliceAddress) = try freshStore(sub: "alice", regId: aliceRegId)
        let bob = try makeBob()

        // Alice runs PQXDH against Bob's bundle → she now has an outbound session for Bob.
        let establisher = SessionEstablisher(store: aliceStore, localAddress: aliceAddress)
        try establisher.establishOutbound(with: bob.bundle, recipientSub: "bob")

        let aliceCrypto = MessageCrypto(store: aliceStore, localAddress: aliceAddress)
        let bobCrypto = MessageCrypto(store: bob.store, localAddress: bob.address)

        // --- PreKey path ---
        let hiData = Data("hi".utf8)
        let alicePayload = try aliceCrypto.encrypt(hiData, to: "bob", deviceId: 1)
        #expect(alicePayload.type == .preKey)

        let decryptedHi = try bobCrypto.decrypt(
            alicePayload.ciphertext, type: .preKey, from: "alice", deviceId: 1)
        #expect(String(bytes: decryptedHi, encoding: .utf8) == "hi")

        // --- Normal path ---
        // Bob has an inbound session now; his reply takes the normal ratchet path.
        let yoData = Data("yo".utf8)
        let bobPayload = try bobCrypto.encrypt(yoData, to: "alice", deviceId: 1)
        #expect(bobPayload.type == .normal)

        let decryptedYo = try aliceCrypto.decrypt(
            bobPayload.ciphertext, type: .normal, from: "bob", deviceId: 1)
        #expect(String(bytes: decryptedYo, encoding: .utf8) == "yo")
    }
    /// Backlog sync, live receive and sending all touch the same peer session, possibly at once.
    /// libsignal's load-session → crypto → store-session is not atomic on its own, so unserialised
    /// calls overwrite each other's ratchet state: messages fail to decrypt, or the peer can no
    /// longer read our replies. Every concurrent decrypt and every concurrent reply must survive.
    @Test func concurrentEncryptsAndDecryptsOnOneSessionAllSucceed() throws {
        let (aliceStore, aliceAddress) = try freshStore(sub: "alice", regId: UInt32.random(in: 1...0x3FFF))
        let bob = try makeBob()
        try SessionEstablisher(store: aliceStore, localAddress: aliceAddress)
            .establishOutbound(with: bob.bundle, recipientSub: "bob")
        let aliceCrypto = MessageCrypto(store: aliceStore, localAddress: aliceAddress)
        let bobCrypto = MessageCrypto(store: bob.store, localAddress: bob.address)
        let hello = try aliceCrypto.encrypt(Data("hello".utf8), to: "bob", deviceId: 1)
        _ = try bobCrypto.decrypt(hello.ciphertext, type: hello.type, from: "alice", deviceId: 1)
        let reply = try bobCrypto.encrypt(Data("reply".utf8), to: "alice", deviceId: 1)
        _ = try aliceCrypto.decrypt(reply.ciphertext, type: reply.type, from: "bob", deviceId: 1)

        let count = 200
        let inbound = try (0..<count).map { try aliceCrypto.encrypt(Data("in\($0)".utf8), to: "bob", deviceId: 1) }
        let decrypted = ConcurrentResults<String>(count: count)
        let outbound = ConcurrentResults<EncryptedPayload>(count: count)
        DispatchQueue.concurrentPerform(iterations: count * 2) { index in
            if index.isMultiple(of: 2) {
                let payload = inbound[index / 2]
                let plaintext = try? bobCrypto.decrypt(
                    payload.ciphertext, type: payload.type, from: "alice", deviceId: 1)
                decrypted.set(index / 2, plaintext.flatMap { String(bytes: $0, encoding: .utf8) })
            } else {
                outbound.set(index / 2, try? bobCrypto.encrypt(Data("out\(index / 2)".utf8), to: "alice", deviceId: 1))
            }
        }

        #expect(decrypted.values == (0..<count).map { "in\($0)" })
        let repliesAliceCanRead = outbound.values.compactMap { $0 }.compactMap { payload in
            (try? aliceCrypto.decrypt(payload.ciphertext, type: payload.type, from: "bob", deviceId: 1))
                .flatMap { String(bytes: $0, encoding: .utf8) }
        }
        #expect(Set(repliesAliceCanRead) == Set((0..<count).map { "out\($0)" }))
    }
}

/// Thread-safe slots for results written from `concurrentPerform`.
private final class ConcurrentResults<Value>: @unchecked Sendable {
    private let lock = NSLock()
    private var storage: [Value?]

    init(count: Int) { storage = Array(repeating: nil, count: count) }

    var values: [Value?] { lock.withLock { storage } }

    func set(_ index: Int, _ value: Value?) { lock.withLock { storage[index] = value } }
}
