import Testing
import Foundation
import LibSignalClient
@testable import Cloak

@Suite struct SignalKeyGeneratorTests {
    @Test func generates_identitySignedAnd100OneTimePreKeys() throws {
        let keys = try SignalKeyGenerator.generate(oneTimeCount: 100)
        #expect(keys.oneTimePreKeys.count == 100)
        #expect(Set(keys.oneTimePreKeys.map(\.id)).count == 100) // unique ids
        #expect(keys.registrationId >= 1 && keys.registrationId <= 0x3FFF)

        // The signed prekey's signature must verify against the identity public key.
        // IdentityKeyPair.identityKey -> IdentityKey; IdentityKey.publicKey -> PublicKey
        // PublicKey.verifySignature(message:signature:) takes ContiguousBytes — Data satisfies this.
        let identityPub = keys.identityKeyPair.identityKey.publicKey
        let signatureValid = try identityPub.verifySignature(
            message: keys.signedPreKey.keyPair.publicKey.serialize(),
            signature: keys.signedPreKeySignature)
        #expect(signatureValid)
    }

    // MARK: - Kyber (PQXDH) prekey

    @Test func kyberPreKey_nonZeroIdGenerated() throws {
        let keys = try SignalKeyGenerator.generate(oneTimeCount: 1)
        #expect(keys.kyberPreKeyId != 0)
    }

    @Test func kyberPreKey_signatureVerifies() throws {
        let keys = try SignalKeyGenerator.generate(oneTimeCount: 1)
        let identityPub = keys.identityKeyPair.identityKey.publicKey
        // kyberPreKeySignature must be a valid identity-key signature over the serialized kyber pubkey.
        let valid = try identityPub.verifySignature(
            message: keys.kyberPreKey.publicKey.serialize(),
            signature: keys.kyberPreKeySignature)
        #expect(valid)
    }

    @Test func kyberPublicKey_byteLength() throws {
        // Pin the real serialised byte length of ML-KEM-1024 from libsignal.
        // Confirmed: libsignal prepends a 1-byte type tag → 1568 (raw ML-KEM-1024) + 1 = 1569 bytes.
        // Server constant KyberPreKey.KEM_PUBLIC_KEY_LENGTH has been reconciled to 1569 to match.
        let keys = try SignalKeyGenerator.generate(oneTimeCount: 1)
        let byteLength = keys.kyberPreKey.publicKey.serialize().count
        #expect(byteLength == 1569, "Actual kyber public key length is \(byteLength) bytes")
    }
}
