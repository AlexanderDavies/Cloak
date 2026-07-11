import Foundation

/// Signal message type carried in the wire envelope.
/// Raw values match the libsignal integer wire representation.
enum SignalMessageType: Int, Codable, Sendable {
    case normal = 2
    case preKey = 3
}

/// Wire envelope (matches docs/contracts/fixtures/slice2-message-envelope.json).
/// `ciphertext` is base64 of opaque bytes; the client base64-decodes for display.
///
/// `fromSub` is present only on the **delivery** frame (server→client), server-stamped from the
/// sender's validated JWT `sub`; it is `nil` on the **inbound** frame the client sends (the server
/// derives the sender itself). Being optional, it is omitted from the outbound JSON when `nil`
/// (synthesized `encodeIfPresent`), so the server's inbound DTO is unaffected.
struct MessageEnvelope: Codable, Equatable, Sendable {
    let messageId: String
    let toSub: String
    let toDeviceId: UInt32
    let fromDeviceId: UInt32
    let messageType: SignalMessageType
    let ciphertext: String
    let fromSub: String?

    init(
        messageId: String,
        toSub: String,
        toDeviceId: UInt32,
        fromDeviceId: UInt32,
        messageType: SignalMessageType,
        ciphertext: String,
        fromSub: String? = nil
    ) {
        self.messageId = messageId
        self.toSub = toSub
        self.toDeviceId = toDeviceId
        self.fromDeviceId = fromDeviceId
        self.messageType = messageType
        self.ciphertext = ciphertext
        self.fromSub = fromSub
    }
}

extension MessageEnvelope {
    enum CodecError: Error { case encoding }

    /// JSON text for the WebSocket wire. Pure + testable (lives here, not in the URLSession adapter,
    /// so the coverage gate exercises the contract — not platform glue).
    func jsonText() throws -> String {
        guard let text = String(bytes: try JSONEncoder().encode(self), encoding: .utf8) else {
            throw CodecError.encoding
        }
        return text
    }

    /// Parse a wire JSON string into an envelope, or `nil` if it isn't a valid envelope.
    static func decode(text: String) -> MessageEnvelope? {
        guard let data = text.data(using: .utf8) else { return nil }
        return try? JSONDecoder().decode(MessageEnvelope.self, from: data)
    }
}

/// The network/WebSocket boundary (guide §8.1). Tests mock this; the app never talks
/// to a live backend in tests (guide §0.5 / §14).
protocol MessageTransport: Sendable {
    func connect(accessToken: String) async throws
    func send(_ envelope: MessageEnvelope) async throws
    /// Stream of envelopes delivered by the server. **Single-subscriber:** call once before `connect`;
    /// a second call supersedes the first (multi-subscriber fan-out is a later-slice concern).
    /// The stream **finishes throwing** when the underlying transport fails, so the consumer can
    /// surface the error instead of hanging forever (never silently drop messages — root CLAUDE.md).
    func inbound() async -> AsyncThrowingStream<MessageEnvelope, Error>
    func disconnect() async
}
