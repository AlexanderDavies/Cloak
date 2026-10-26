import Foundation

/// The sender-authoritative message payload encrypted as the Signal plaintext (spec: "decrypt once"
/// stores this locally). `sentAt` drives display order — it never travels as cleartext metadata.
struct MessageContent: Codable, Equatable, Sendable {
    let sentAt: Date
    let text: String

    private static func encoder() -> JSONEncoder {
        let encoder = JSONEncoder()
        encoder.dateEncodingStrategy = .millisecondsSince1970
        return encoder
    }

    private static func decoder() -> JSONDecoder {
        let decoder = JSONDecoder()
        decoder.dateDecodingStrategy = .millisecondsSince1970
        return decoder
    }

    func serialized() throws -> Data { try Self.encoder().encode(self) }

    static func deserialize(_ data: Data) throws -> MessageContent {
        try decoder().decode(MessageContent.self, from: data)
    }
}
