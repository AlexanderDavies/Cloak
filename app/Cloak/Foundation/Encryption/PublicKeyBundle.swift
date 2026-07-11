import Foundation
import LibSignalClient

/// The public prekey bundle uploaded to the server (matches docs/contracts/slice1-device-key-bundle.md).
/// Contains only public key material — private keys never leave the device.
struct PublicKeyBundle: Codable, Equatable {

    // MARK: - Nested DTOs (named distinctly from the app's own SignedPreKeyRecord/OneTimePreKeyRecord)

    /// Wire shape for the signed prekey sub-object.
    struct SignedPreKey: Codable, Equatable {
        let keyId: UInt32
        let publicKey: String      // base64-encoded 33-byte Curve25519 public key
        let signature: String      // base64-encoded 64-byte Ed25519 signature
    }

    /// Wire shape for a one-time prekey sub-object.
    struct OneTimePreKey: Codable, Equatable {
        let keyId: UInt32
        let publicKey: String      // base64-encoded 33-byte Curve25519 public key
    }

    /// Wire shape for the last-resort Kyber (ML-KEM-1024) prekey sub-object.
    struct KyberPreKey: Codable, Equatable {
        let keyId: UInt32
        let publicKey: String      // base64-encoded ML-KEM-1024 encapsulation public key
        let signature: String      // base64-encoded 64-byte XEdDSA identity-key signature
    }

    // MARK: - Top-level contract fields (names match the JSON contract exactly)

    let registrationId: UInt32
    let deviceId: UInt32
    let identityKey: String        // base64-encoded 33-byte Curve25519 identity public key
    let signedPreKey: SignedPreKey
    let oneTimePreKeys: [OneTimePreKey]
    let kyberPreKey: KyberPreKey

    // MARK: - Init from generated keys

    /// Builds a `PublicKeyBundle` from freshly generated device keys by base64-encoding every
    /// serialized public key and the signed-prekey signature.
    ///
    /// - Parameters:
    ///   - keys: The device's generated key material (from `SignalKeyGenerator.generate`).
    ///   - deviceId: The libsignal device number (1 for the primary device).
    init(from keys: GeneratedDeviceKeys, deviceId: UInt32) throws {
        // LibSignalClient 0.96.2: PublicKey.serialize() returns Data directly.
        func b64(_ data: Data) -> String { data.base64EncodedString() }

        self.registrationId = keys.registrationId
        self.deviceId = deviceId
        self.identityKey = b64(keys.identityKeyPair.identityKey.publicKey.serialize())
        self.signedPreKey = SignedPreKey(
            keyId: keys.signedPreKey.id,
            publicKey: b64(keys.signedPreKey.keyPair.publicKey.serialize()),
            signature: b64(keys.signedPreKeySignature)
        )
        self.oneTimePreKeys = keys.oneTimePreKeys.map { record in
            OneTimePreKey(
                keyId: record.id,
                publicKey: b64(record.keyPair.publicKey.serialize())
            )
        }
        self.kyberPreKey = KyberPreKey(
            keyId: keys.kyberPreKeyId,
            publicKey: b64(keys.kyberPreKey.publicKey.serialize()),
            signature: b64(keys.kyberPreKeySignature)
        )
    }

    // MARK: - Helpers

    /// Returns the bundle as a JSON object (`[String: Any]`) for tests and inspection.
    /// Uses `JSONEncoder` + `JSONSerialization` so the output reflects the exact Codable wire shape.
    func jsonObject() throws -> [String: Any] {
        let data = try JSONEncoder().encode(self)
        guard let obj = try JSONSerialization.jsonObject(with: data) as? [String: Any] else {
            throw EncodingError.invalidValue(
                self,
                .init(codingPath: [], debugDescription: "Expected [String: Any] from JSON"))
        }
        return obj
    }
}
