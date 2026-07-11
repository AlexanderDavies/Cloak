import Testing
import Foundation
import GRDB
import LibSignalClient
@testable import Cloak

/// Privacy gate: proves that data sent to the server contains only public key material (Slice 1)
/// and that encrypted message envelopes carry opaque ciphertext — never plaintext (Slice 2).
///
/// These are guard tests — they should always pass given correct implementation. Their value is as
/// regression barriers: if a private field is accidentally added, or if the encryption path becomes
/// a no-op, these tests catch it before anything reaches the wire.
///
/// Root CLAUDE.md principles 2 + 6: "E2EE is the client's job" and "minimal cleartext metadata."
@Suite struct PrivacyGateTests {

    // MARK: - Field-name assertions (Slice 1)

    @Test func uploadedBundleContainsOnlyPublicFields() throws {
        let keys = try SignalKeyGenerator.generate(oneTimeCount: 2)
        let data = try JSONEncoder().encode(PublicKeyBundle(from: keys, deviceId: 1))
        let json = String(bytes: data, encoding: .utf8) ?? ""

        // The contract's public fields are present.
        #expect(json.contains("identityKey") && json.contains("signedPreKey"))

        // No field named "private" or "secret" (case-insensitive) appears in the upload JSON.
        #expect(!json.lowercased().contains("private"))
        #expect(!json.lowercased().contains("secret"))
    }

    // MARK: - Strengthened assertion: private key bytes are absent from the wire payload (Slice 1)

    /// Encodes the private key for each key type as base64 and verifies none of those byte
    /// sequences appear in the serialised bundle. This catches the case where a private key's
    /// *value* leaks even if the field name looks innocuous (e.g. a field named "key" that
    /// accidentally carries the private bytes).
    @Test func privateKeyBytesAreAbsentFromUploadJSON() throws {
        let keys = try SignalKeyGenerator.generate(oneTimeCount: 3)
        let data = try JSONEncoder().encode(PublicKeyBundle(from: keys, deviceId: 1))
        let json = String(bytes: data, encoding: .utf8) ?? ""

        // Collect base64 representations of every private key in the bundle.
        var privateKeyB64s: [String] = []

        // Identity private key.
        privateKeyB64s.append(keys.identityKeyPair.privateKey.serialize().base64EncodedString())

        // Signed prekey private key.
        privateKeyB64s.append(keys.signedPreKey.keyPair.serialize().base64EncodedString())

        // One-time prekey private keys.
        for otp in keys.oneTimePreKeys {
            privateKeyB64s.append(otp.keyPair.serialize().base64EncodedString())
        }

        // None of the private key byte sequences appear in the upload JSON.
        for b64 in privateKeyB64s {
            #expect(
                !json.contains(b64),
                "Private key bytes (\(b64.prefix(12))…) found in upload JSON"
            )
        }
    }

    // MARK: - Slice 2: encrypted envelope privacy

    // MARK: Helpers

    /// Snapshot of one participant's key store + protocol address + long-term identity.
    private struct ParticipantSetup {
        let store: SignalKeyStore
        let address: ProtocolAddress
        let identity: IdentityKeyPair
    }

    /// Opens a fresh encrypted store for `sub` and returns its key material.
    private func makeParticipant(sub: String) throws -> ParticipantSetup {
        let path = NSTemporaryDirectory() + "pg2-\(sub)-\(UUID().uuidString).sqlite"
        let queue = try EncryptedDatabase.open(path: path, passphrase: "k")
        let identity = IdentityKeyPair.generate()
        let regId = UInt32.random(in: 1...0x3FFF)
        let store = try SignalKeyStore(database: queue, identity: identity, registrationId: regId)
        let address = try ProtocolAddress(name: sub, deviceId: 1)
        return ParticipantSetup(store: store, address: address, identity: identity)
    }

    /// Generates a full set of prekeys for `participant`, stores the private halves in its store,
    /// and returns the matching public `RemotePreKeyBundle` for an initiator to consume.
    private func makeBundle(for participant: ParticipantSetup) throws -> RemotePreKeyBundle {
        let store = participant.store
        let identity = participant.identity
        let timestamp = UInt64(Date().timeIntervalSince1970 * 1000)
        let regId = UInt32.random(in: 1...0x3FFF)

        // Signed EC prekey.
        let ecPriv = PrivateKey.generate()
        let ecSig = identity.privateKey.generateSignature(message: ecPriv.publicKey.serialize())
        try store.storeSignedPreKey(
            try LibSignalClient.SignedPreKeyRecord(
                id: 1, timestamp: timestamp, privateKey: ecPriv, signature: ecSig),
            id: 1, context: NullContext())

        // One-time EC prekey.
        let otPriv = PrivateKey.generate()
        try store.storePreKey(
            try LibSignalClient.PreKeyRecord(id: 1, privateKey: otPriv),
            id: 1, context: NullContext())

        // Kyber (ML-KEM-1024) prekey for PQXDH.
        let kyberPair = KEMKeyPair.generate()
        let kyberSig = identity.privateKey.generateSignature(
            message: kyberPair.publicKey.serialize())
        try store.storeKyberPreKey(
            try KyberPreKeyRecord(
                id: 1, timestamp: timestamp, keyPair: kyberPair, signature: kyberSig),
            id: 1, context: NullContext())

        return RemotePreKeyBundle(
            registrationId: regId,
            deviceId: 1,
            identityKey: identity.identityKey.serialize().base64EncodedString(),
            signedPreKey: RemotePreKeyBundle.SignedPreKey(
                keyId: 1,
                publicKey: ecPriv.publicKey.serialize().base64EncodedString(),
                signature: ecSig.base64EncodedString()),
            oneTimePreKey: RemotePreKeyBundle.OneTimePreKey(
                keyId: 1,
                publicKey: otPriv.publicKey.serialize().base64EncodedString()),
            kyberPreKey: RemotePreKeyBundle.KyberPreKey(
                keyId: 1,
                publicKey: kyberPair.publicKey.serialize().base64EncodedString(),
                signature: kyberSig.base64EncodedString()))
    }

    /// Asserts that a `MessageEnvelope` produced by a real `MessageCrypto.encrypt(...)` over a
    /// real PQXDH session carries opaque ciphertext — the serialised JSON must not contain the
    /// plaintext string, and base64-decoding the `ciphertext` field must yield bytes distinct from
    /// the raw plaintext bytes.
    ///
    /// Mirrors the Slice 1 upload-body assertions above. Establishes a real PQXDH session
    /// (Alice → Bob) using temp `SignalKeyStore`s, as the C6/C8 tests do.
    @Test func encryptedEnvelope_ciphertextNotPlaintext() throws {
        let alice = try makeParticipant(sub: "alice")
        let bob = try makeParticipant(sub: "bob")
        let bobBundle = try makeBundle(for: bob)

        // Alice runs PQXDH against Bob's bundle to establish an outbound session.
        let establisher = SessionEstablisher(store: alice.store, localAddress: alice.address)
        try establisher.establishOutbound(with: bobBundle, recipientSub: "bob")

        // Encrypt a known plaintext.
        let plaintext = "secret-plaintext-123"
        let plaintextData = Data(plaintext.utf8)
        let crypto = MessageCrypto(store: alice.store, localAddress: alice.address)
        let payload = try crypto.encrypt(plaintextData, to: "bob", deviceId: 1)

        // Build the wire envelope (exactly as ChatThreadViewModel does on send).
        let envelope = MessageEnvelope(
            messageId: UUID().uuidString,
            toSub: "bob",
            toDeviceId: 1,
            fromDeviceId: 1,
            messageType: payload.type,
            ciphertext: payload.ciphertext.base64EncodedString())

        // Serialize to JSON — this is what goes to the server.
        let json = try envelope.jsonText()

        // 1. The plaintext must not appear anywhere in the wire JSON.
        #expect(
            !json.contains(plaintext),
            "Plaintext appears verbatim in the wire envelope JSON")

        // 2. The raw ciphertext bytes differ from the plaintext bytes (encryption is not a no-op).
        let ciphertextBytes = try #require(Data(base64Encoded: envelope.ciphertext))
        #expect(
            ciphertextBytes != plaintextData,
            "Ciphertext bytes are identical to plaintext bytes — encryption is a no-op")
    }
}
