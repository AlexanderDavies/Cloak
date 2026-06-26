import Foundation
import LibSignalClient

/// One one-time prekey: a libsignal key id + key pair.
struct OneTimePreKeyRecord {
    let id: UInt32
    let keyPair: PrivateKey
}

/// A signed prekey: id and key pair. The identity-key signature is held in `GeneratedDeviceKeys`.
struct SignedPreKeyRecord {
    let id: UInt32
    let keyPair: PrivateKey
}

/// All freshly generated device key material (private + public). Private keys are persisted by
/// the Signal key stores; the public bundle is uploaded to the server (see `PublicKeyBundle`).
struct GeneratedDeviceKeys {
    let registrationId: UInt32
    let identityKeyPair: IdentityKeyPair
    let signedPreKey: SignedPreKeyRecord
    /// Signature of `signedPreKey.keyPair.publicKey` by the identity private key. (`Data`)
    let signedPreKeySignature: Data
    let oneTimePreKeys: [OneTimePreKeyRecord]
}
