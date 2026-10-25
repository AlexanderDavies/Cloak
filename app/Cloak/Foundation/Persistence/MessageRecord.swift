import Foundation

enum MessageStatus: String, Codable, Sendable { case sending, sent, failed }

/// A message in the local encrypted history (plaintext `body`, protected at rest by SQLCipher).
struct StoredMessage: Equatable, Sendable {
    let messageId: String
    let conversationId: String
    let peerSub: String
    let isMine: Bool
    let sentAt: Date
    let body: String
    var status: MessageStatus
}

protocol MessageRepository: Sendable {
    func save(_ message: StoredMessage) throws
    /// Whether this message is already in local history. Callers use it to skip work that must not
    /// be repeated for a message already stored — above all a Double-Ratchet decrypt, which is
    /// destructive and cannot be re-run on the same ciphertext.
    func contains(messageId: String) throws -> Bool
    func updateStatus(messageId: String, status: MessageStatus) throws
    func loadThread(conversationId: String) throws -> [StoredMessage]
    func latestPerConversation() throws -> [StoredMessage]
}

extension MessageRepository {
    /// Saves just-decrypted plaintext, retrying a transient write failure on the spot. The ratchet
    /// spent the message key on decrypt, so this plaintext is the only copy: a later retry cannot
    /// decrypt it again.
    func saveRetrying(_ message: StoredMessage, attempts: Int = 3) throws {
        for _ in 1..<attempts where (try? save(message)) != nil { return }
        try save(message)
    }
}
