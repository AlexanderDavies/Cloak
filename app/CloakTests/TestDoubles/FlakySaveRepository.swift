import Foundation
@testable import Cloak

/// Fails the first `failures` saves, then delegates — a transient local write fault.
final class FlakySaveRepository: MessageRepository, @unchecked Sendable {
    private struct TransientWriteFailure: Error {}
    private let wrapped: MessageRepository
    private var failuresRemaining: Int

    init(wrapping wrapped: MessageRepository, failures: Int) {
        self.wrapped = wrapped
        self.failuresRemaining = failures
    }

    func save(_ message: StoredMessage) throws {
        if failuresRemaining > 0 {
            failuresRemaining -= 1
            throw TransientWriteFailure()
        }
        try wrapped.save(message)
    }
    func contains(messageId: String) throws -> Bool { try wrapped.contains(messageId: messageId) }
    func updateStatus(messageId: String, status: MessageStatus) throws {
        try wrapped.updateStatus(messageId: messageId, status: status)
    }
    func loadThread(conversationId: String) throws -> [StoredMessage] {
        try wrapped.loadThread(conversationId: conversationId)
    }
    func latestPerConversation() throws -> [StoredMessage] { try wrapped.latestPerConversation() }
}
