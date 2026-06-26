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
}
