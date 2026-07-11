import Testing
import Foundation
import GRDB
import LibSignalClient
@testable import Cloak

/// Tests for `SessionEstablisher` — PQXDH session-initiation from a `RemotePreKeyBundle`.
///
/// Alice is the device under test (backed by a real SQLCipher `SignalKeyStore`).
/// Bob's keys are generated in-process; his public-key material is serialised into a
/// `RemotePreKeyBundle` exactly as the server would return it.
@Suite struct SessionEstablisherTests {

    // MARK: - Helpers

    private func freshStore(registrationId: UInt32 = 42) throws -> SignalKeyStore {
        let path = NSTemporaryDirectory() + "se-\(UUID().uuidString).sqlite"
        let queue = try EncryptedDatabase.open(path: path, passphrase: "k")
        return try SignalKeyStore(
            database: queue, identity: IdentityKeyPair.generate(), registrationId: registrationId)
    }

    /// All of Bob's raw key material so tests can tamper with individual fields.
    private struct BobMaterial {
        let identity: IdentityKeyPair
        let registrationId: UInt32
        let signedECPriv: PrivateKey
        let ecSig: Data
        let oneTimePriv: PrivateKey
        let kyberPair: KEMKeyPair
        let kyberSig: Data
    }

    private func makeBobMaterial() -> BobMaterial {
        let identity = IdentityKeyPair.generate()
        let registrationId = UInt32.random(in: 1...0x3FFF)
        let signedECPriv = PrivateKey.generate()
        let ecSig = identity.privateKey.generateSignature(
            message: signedECPriv.publicKey.serialize())
        let oneTimePriv = PrivateKey.generate()
        let kyberPair = KEMKeyPair.generate()
        let kyberSig = identity.privateKey.generateSignature(
            message: kyberPair.publicKey.serialize())
        return BobMaterial(
            identity: identity,
            registrationId: registrationId,
            signedECPriv: signedECPriv,
            ecSig: ecSig,
            oneTimePriv: oneTimePriv,
            kyberPair: kyberPair,
            kyberSig: kyberSig)
    }

    /// Builds a `RemotePreKeyBundle` from Bob's raw material.
    private func makeBundle(from bob: BobMaterial, includeOTP: Bool = true) -> RemotePreKeyBundle {
        let otp: RemotePreKeyBundle.OneTimePreKey? = includeOTP
            ? RemotePreKeyBundle.OneTimePreKey(
                keyId: 1,
                publicKey: bob.oneTimePriv.publicKey.serialize().base64EncodedString())
            : nil
        return RemotePreKeyBundle(
            registrationId: bob.registrationId,
            deviceId: 1,
            identityKey: bob.identity.identityKey.serialize().base64EncodedString(),
            signedPreKey: RemotePreKeyBundle.SignedPreKey(
                keyId: 1,
                publicKey: bob.signedECPriv.publicKey.serialize().base64EncodedString(),
                signature: bob.ecSig.base64EncodedString()),
            oneTimePreKey: otp,
            kyberPreKey: RemotePreKeyBundle.KyberPreKey(
                keyId: 1,
                publicKey: bob.kyberPair.publicKey.serialize().base64EncodedString(),
                signature: bob.kyberSig.base64EncodedString()))
    }

    /// Returns a copy of `bundle` with a bit-flipped signed-prekey signature.
    private func withTamperedSignedPreKeySig(_ bundle: RemotePreKeyBundle) -> RemotePreKeyBundle {
        var sigBytes = Data(base64Encoded: bundle.signedPreKey.signature) ?? Data()
        if !sigBytes.isEmpty { sigBytes[0] ^= 0xFF }
        return RemotePreKeyBundle(
            registrationId: bundle.registrationId,
            deviceId: bundle.deviceId,
            identityKey: bundle.identityKey,
            signedPreKey: RemotePreKeyBundle.SignedPreKey(
                keyId: bundle.signedPreKey.keyId,
                publicKey: bundle.signedPreKey.publicKey,
                signature: sigBytes.base64EncodedString()),
            oneTimePreKey: bundle.oneTimePreKey,
            kyberPreKey: bundle.kyberPreKey)
    }

    /// Returns a copy of `bundle` with a bit-flipped kyber-prekey signature.
    private func withTamperedKyberSig(_ bundle: RemotePreKeyBundle) -> RemotePreKeyBundle {
        var sigBytes = Data(base64Encoded: bundle.kyberPreKey.signature) ?? Data()
        if !sigBytes.isEmpty { sigBytes[0] ^= 0xFF }
        return RemotePreKeyBundle(
            registrationId: bundle.registrationId,
            deviceId: bundle.deviceId,
            identityKey: bundle.identityKey,
            signedPreKey: bundle.signedPreKey,
            oneTimePreKey: bundle.oneTimePreKey,
            kyberPreKey: RemotePreKeyBundle.KyberPreKey(
                keyId: bundle.kyberPreKey.keyId,
                publicKey: bundle.kyberPreKey.publicKey,
                signature: sigBytes.base64EncodedString()))
    }

    // MARK: - Tests

    /// Happy path: a valid bundle (with OTP) establishes a session for Bob in Alice's store.
    @Test func establishOutboundWithOTPCreatesSession() throws {
        let aliceStore = try freshStore()
        let aliceAddress = try ProtocolAddress(name: "alice-sub", deviceId: 1)
        let establisher = SessionEstablisher(store: aliceStore, localAddress: aliceAddress)

        let bob = makeBobMaterial()
        let bundle = makeBundle(from: bob, includeOTP: true)

        try establisher.establishOutbound(with: bundle, recipientSub: "bob-sub")

        let bobAddress = try ProtocolAddress(name: "bob-sub", deviceId: 1)
        let session = try aliceStore.loadSession(for: bobAddress, context: NullContext())
        #expect(session != nil)
    }

    /// A tampered signed-prekey signature must cause `establishOutbound` to throw.
    @Test func tamperedSignedPreKeySignatureIsRejected() throws {
        let aliceStore = try freshStore()
        let aliceAddress = try ProtocolAddress(name: "alice-sub", deviceId: 1)
        let establisher = SessionEstablisher(store: aliceStore, localAddress: aliceAddress)

        let bob = makeBobMaterial()
        let goodBundle = makeBundle(from: bob, includeOTP: true)
        let badBundle = withTamperedSignedPreKeySig(goodBundle)

        #expect(throws: (any Error).self) {
            try establisher.establishOutbound(with: badBundle, recipientSub: "bob-sub")
        }
    }

    /// A tampered kyber-prekey signature must cause `establishOutbound` to throw.
    @Test func tamperedKyberSignatureIsRejected() throws {
        let aliceStore = try freshStore()
        let aliceAddress = try ProtocolAddress(name: "alice-sub", deviceId: 1)
        let establisher = SessionEstablisher(store: aliceStore, localAddress: aliceAddress)

        let bob = makeBobMaterial()
        let goodBundle = makeBundle(from: bob, includeOTP: true)
        let badBundle = withTamperedKyberSig(goodBundle)

        #expect(throws: (any Error).self) {
            try establisher.establishOutbound(with: badBundle, recipientSub: "bob-sub")
        }
    }

    /// No-OTP path: a bundle with `oneTimePreKey == nil` still establishes a session.
    @Test func establishOutboundWithoutOTPCreatesSession() throws {
        let aliceStore = try freshStore()
        let aliceAddress = try ProtocolAddress(name: "alice-sub", deviceId: 1)
        let establisher = SessionEstablisher(store: aliceStore, localAddress: aliceAddress)

        let bob = makeBobMaterial()
        let bundle = makeBundle(from: bob, includeOTP: false)

        try establisher.establishOutbound(with: bundle, recipientSub: "bob-sub")

        let bobAddress = try ProtocolAddress(name: "bob-sub", deviceId: 1)
        let session = try aliceStore.loadSession(for: bobAddress, context: NullContext())
        #expect(session != nil)
    }
}
