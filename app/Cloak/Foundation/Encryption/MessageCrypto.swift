import Foundation
import LibSignalClient

/// The ciphertext produced by ``MessageCrypto/encrypt(_:to:deviceId:)`` together with its
/// Signal message type. The type determines which decrypt path the recipient must take.
struct EncryptedPayload: Sendable {
    let ciphertext: Data
    let type: SignalMessageType
}

/// Encrypts and decrypts Signal messages for a single local device (architecture guide §7).
///
/// The first outbound message to a new peer is a `PreKeySignalMessage` (type == `.preKey`);
/// subsequent messages use the normal Double-Ratchet path (type == `.normal`). Recipients branch
/// on the envelope's `messageType` field (from ``SignalMessageType``) to choose the decrypt path.
///
/// - Important: A PQXDH session must be established in `store` (via ``SessionEstablisher``)
///   before `encrypt` is called. Calling `decrypt` with a `.preKey` message establishes the
///   inbound session on the recipient's side as a side-effect.
protocol MessageCrypting: Sendable {
    func encrypt(_ plaintext: Data, to recipientSub: String, deviceId: UInt32) throws -> EncryptedPayload
    func decrypt(_ ciphertext: Data, type: SignalMessageType, from senderSub: String, deviceId: UInt32) throws -> Data
}

/// Real ``MessageCrypting`` implementation backed by a ``SignalKeyStore``.
struct MessageCrypto: MessageCrypting {
    /// The Signal key store for this device — used as identity, session, prekey, and Kyber store.
    let store: SignalKeyStore
    /// This device's libsignal address: `ProtocolAddress(name: mySub, deviceId: 1)`.
    let localAddress: ProtocolAddress

    // MARK: - Encrypt

    /// Encrypts `plaintext` for the peer at `recipientSub`/`deviceId`.
    ///
    /// The first call to a new peer returns an ``EncryptedPayload`` with `type == .preKey`
    /// (a `PreKeySignalMessage`). Later calls return `type == .normal` (a `SignalMessage`).
    func encrypt(_ plaintext: Data, to recipientSub: String, deviceId: UInt32) throws -> EncryptedPayload {
        let peerAddress = try ProtocolAddress(name: recipientSub, deviceId: deviceId)
        let encrypted = try signalEncrypt(
            message: plaintext,
            for: peerAddress,
            localAddress: localAddress,
            sessionStore: store,
            identityStore: store,
            context: NullContext())
        // CiphertextMessage.MessageType is a struct (not an enum); compare against the static .preKey.
        let msgType: SignalMessageType = encrypted.messageType == .preKey ? .preKey : .normal
        return EncryptedPayload(ciphertext: encrypted.serialize(), type: msgType)
    }

    // MARK: - Decrypt

    /// Decrypts a Signal ciphertext received from `senderSub`/`deviceId`.
    ///
    /// - Parameters:
    ///   - ciphertext: Raw ciphertext bytes (the `ciphertext` field of an ``EncryptedPayload``).
    ///   - type: The message type from the wire envelope; selects the decrypt path.
    ///   - senderSub: The sender's user sub (matches the `ProtocolAddress` name).
    ///   - deviceId: The sender's device id.
    func decrypt(_ ciphertext: Data, type: SignalMessageType, from senderSub: String, deviceId: UInt32) throws -> Data {
        let peerAddress = try ProtocolAddress(name: senderSub, deviceId: deviceId)
        switch type {
        case .preKey:
            let msg = try PreKeySignalMessage(bytes: ciphertext)
            return try signalDecryptPreKey(
                message: msg,
                from: peerAddress,
                localAddress: localAddress,
                sessionStore: store,
                identityStore: store,
                preKeyStore: store,
                signedPreKeyStore: store,
                kyberPreKeyStore: store,
                context: NullContext())
        case .normal:
            let msg = try SignalMessage(bytes: ciphertext)
            return try signalDecrypt(
                message: msg,
                from: peerAddress,
                to: localAddress,
                sessionStore: store,
                identityStore: store,
                context: NullContext())
        }
    }
}
