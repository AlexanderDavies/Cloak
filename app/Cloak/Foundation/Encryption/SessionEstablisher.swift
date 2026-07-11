import Foundation
import LibSignalClient

/// Establishes an outbound PQXDH session with a remote peer from a `RemotePreKeyBundle`.
///
/// The initiator role: decodes the bundle's public fields, constructs a libsignal `PreKeyBundle`,
/// and calls `processPreKeyBundle`, which verifies both the signed-EC-prekey and Kyber-prekey
/// signatures internally and writes the resulting `SessionRecord` into the store.
protocol SessionEstablishing: Sendable {
    /// Builds a libsignal PreKeyBundle from the remote bundle and runs PQXDH, establishing an
    /// initiator session for `recipientSub` in the store. Throws if a prekey signature is invalid.
    func establishOutbound(with bundle: RemotePreKeyBundle, recipientSub: String) throws

    /// Whether a Signal session already exists in the store for `(recipientSub, deviceId)`.
    /// Callers use this to avoid re-running X3DH for a peer they already have a session with
    /// (which would consume another one-time prekey and reset the Double-Ratchet state).
    func hasSession(recipientSub: String, deviceId: UInt32) -> Bool
}

struct SessionEstablisher: SessionEstablishing {
    let store: SignalKeyStore
    /// This device's address: `ProtocolAddress(name: mySub, deviceId: 1)`.
    let localAddress: ProtocolAddress

    func hasSession(recipientSub: String, deviceId: UInt32) -> Bool {
        guard let address = try? ProtocolAddress(name: recipientSub, deviceId: deviceId) else {
            return false
        }
        // `try?` yields SessionRecord?? — outer nil means the load threw; inner nil means no session.
        let loaded = try? store.loadSession(for: address, context: NullContext())
        return (loaded ?? nil) != nil
    }

    func establishOutbound(with bundle: RemotePreKeyBundle, recipientSub: String) throws {
        let remoteAddress = try ProtocolAddress(name: recipientSub, deviceId: bundle.deviceId)
        let libBundle = try buildPreKeyBundle(from: bundle)
        try processPreKeyBundle(
            libBundle,
            for: remoteAddress,
            ourAddress: localAddress,
            sessionStore: store,
            identityStore: store,
            context: NullContext())
    }
}

// MARK: - Private bundle construction

private func buildPreKeyBundle(from bundle: RemotePreKeyBundle) throws -> PreKeyBundle {
    let identityKey = try IdentityKey(bytes: decoded(bundle.identityKey, field: "identityKey"))
    let signedPKData = try decoded(bundle.signedPreKey.publicKey, field: "signedPreKey.publicKey")
    let signedPrekey = try LibSignalClient.PublicKey(signedPKData)
    let signedSig = try decoded(bundle.signedPreKey.signature, field: "signedPreKey.signature")
    let kyberPrekey = try KEMPublicKey(decoded(bundle.kyberPreKey.publicKey, field: "kyberPreKey.publicKey"))
    let kyberSig = try decoded(bundle.kyberPreKey.signature, field: "kyberPreKey.signature")

    if let otp = bundle.oneTimePreKey {
        let otpKey = try LibSignalClient.PublicKey(decoded(otp.publicKey, field: "oneTimePreKey.publicKey"))
        return try PreKeyBundle(
            registrationId: bundle.registrationId,
            deviceId: bundle.deviceId,
            prekeyId: otp.keyId,
            prekey: otpKey,
            signedPrekeyId: bundle.signedPreKey.keyId,
            signedPrekey: signedPrekey,
            signedPrekeySignature: signedSig,
            identity: identityKey,
            kyberPrekeyId: bundle.kyberPreKey.keyId,
            kyberPrekey: kyberPrekey,
            kyberPrekeySignature: kyberSig)
    }
    return try PreKeyBundle(
        registrationId: bundle.registrationId,
        deviceId: bundle.deviceId,
        signedPrekeyId: bundle.signedPreKey.keyId,
        signedPrekey: signedPrekey,
        signedPrekeySignature: signedSig,
        identity: identityKey,
        kyberPrekeyId: bundle.kyberPreKey.keyId,
        kyberPrekey: kyberPrekey,
        kyberPrekeySignature: kyberSig)
}

/// Decodes a base64 string, throwing `SessionEstablisherError.invalidBase64Field` on failure.
private func decoded(_ base64: String, field: String) throws -> Data {
    guard let data = Data(base64Encoded: base64) else {
        throw SessionEstablisherError.invalidBase64Field(field)
    }
    return data
}

// MARK: - Errors

enum SessionEstablisherError: Error {
    /// A base64-encoded field in the remote bundle could not be decoded.
    case invalidBase64Field(String)
}
