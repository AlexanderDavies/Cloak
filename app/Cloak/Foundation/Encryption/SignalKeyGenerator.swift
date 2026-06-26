import Foundation
import LibSignalClient

/// Generates a device's Signal key material on-device (architecture guide §7).
/// Pure local computation — no network, no backend, no hosted AI.
enum SignalKeyGenerator {
    /// Generates the identity key pair, one signed prekey, and `oneTimeCount` one-time prekeys.
    ///
    /// - Parameter oneTimeCount: Number of one-time prekeys to generate (typically 100).
    /// - Returns: A complete `GeneratedDeviceKeys` bundle ready for storage and publication.
    /// - Throws: Never in practice (libsignal key generation is infallible on supported platforms),
    ///   but the signature is `throws` so callers can propagate any future errors without API churn.
    static func generate(oneTimeCount: Int) throws -> GeneratedDeviceKeys {
        let identityKeyPair = IdentityKeyPair.generate()
        let registrationId = UInt32.random(in: 1...0x3FFF)

        let signedKeyPair = PrivateKey.generate()
        let signedId: UInt32 = 1
        // Sign the serialized public key bytes with the identity private key.
        // generateSignature(message:) returns Data; verifySignature accepts ContiguousBytes.
        let signature = identityKeyPair.privateKey.generateSignature(
            message: signedKeyPair.publicKey.serialize())

        let oneTimePreKeys = (1...oneTimeCount).map { index in
            OneTimePreKeyRecord(id: UInt32(index), keyPair: PrivateKey.generate())
        }

        return GeneratedDeviceKeys(
            registrationId: registrationId,
            identityKeyPair: identityKeyPair,
            signedPreKey: SignedPreKeyRecord(id: signedId, keyPair: signedKeyPair),
            signedPreKeySignature: signature,
            oneTimePreKeys: oneTimePreKeys)
    }
}
