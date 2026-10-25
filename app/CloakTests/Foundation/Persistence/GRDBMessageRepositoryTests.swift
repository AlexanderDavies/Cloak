import Foundation
import GRDB
import Testing
@testable import Cloak

@Suite struct GRDBMessageRepositoryTests {
    private func makeRepo() throws -> GRDBMessageRepository {
        let path = NSTemporaryDirectory() + UUID().uuidString + ".sqlite"
        let database = try EncryptedDatabase.open(path: path, passphrase: "test-pass")
        return try GRDBMessageRepository(database: database)
    }

    @Test func savesAndLoadsThreadInSentAtOrder() throws {
        let repo = try makeRepo()
        let convo = "c1"
        try repo.save(StoredMessage(messageId: "m2", conversationId: convo, peerSub: "bob",
            isMine: false, sentAt: Date(timeIntervalSince1970: 200), body: "second", status: .sent))
        try repo.save(StoredMessage(messageId: "m1", conversationId: convo, peerSub: "bob",
            isMine: true, sentAt: Date(timeIntervalSince1970: 100), body: "first", status: .sent))
        let thread = try repo.loadThread(conversationId: convo)
        #expect(thread.map(\.messageId) == ["m1", "m2"])
    }

    /// A send interrupted by the app being killed leaves its row at `sending` with nothing left to
    /// finish it, so it would show "Sending…" forever. At launch no send is in flight, so every
    /// `sending` row is failed — and only those rows.
    @Test func failInterruptedSendsMarksOnlySendingRowsFailed() throws {
        let repo = try makeRepo()
        for (id, status) in [("stuck", MessageStatus.sending), ("done", .sent), ("broken", .failed)] {
            try repo.save(StoredMessage(messageId: id, conversationId: "c1", peerSub: "bob",
                isMine: true, sentAt: Date(timeIntervalSince1970: 1), body: id, status: status))
        }

        try repo.failInterruptedSends()

        let statuses = Dictionary(uniqueKeysWithValues: try repo.loadThread(conversationId: "c1")
            .map { ($0.messageId, $0.status) })
        #expect(statuses == ["stuck": .failed, "done": .sent, "broken": .failed])
    }

    @Test func saveIsIdempotentOnMessageId() throws {
        let repo = try makeRepo()
        let msg = StoredMessage(messageId: "dup", conversationId: "c1", peerSub: "bob",
            isMine: false, sentAt: Date(timeIntervalSince1970: 1), body: "x", status: .sent)
        try repo.save(msg)
        try repo.save(msg)
        #expect(try repo.loadThread(conversationId: "c1").count == 1)
    }

    /// `contains` is what lets the backlog sync skip a message it already holds *before* attempting
    /// the destructive Double-Ratchet decrypt, so it must answer on `message_id` alone.
    @Test func containsReportsWhetherAMessageIsAlreadyStored() throws {
        let repo = try makeRepo()
        try repo.save(StoredMessage(messageId: "m1", conversationId: "c1", peerSub: "bob",
            isMine: false, sentAt: Date(timeIntervalSince1970: 1), body: "x", status: .sent))
        #expect(try repo.contains(messageId: "m1"))
        #expect(try repo.contains(messageId: "never-seen") == false)
    }

    @Test func updateStatusChangesStoredStatus() throws {
        let repo = try makeRepo()
        try repo.save(StoredMessage(messageId: "m1", conversationId: "c1", peerSub: "bob",
            isMine: true, sentAt: Date(timeIntervalSince1970: 1), body: "x", status: .sending))
        try repo.updateStatus(messageId: "m1", status: .sent)
        #expect(try repo.loadThread(conversationId: "c1").first?.status == .sent)
    }

    @Test func latestPerConversationReturnsNewestEach() throws {
        let repo = try makeRepo()
        try repo.save(StoredMessage(messageId: "a1", conversationId: "cA", peerSub: "bob",
            isMine: true, sentAt: Date(timeIntervalSince1970: 10), body: "old", status: .sent))
        try repo.save(StoredMessage(messageId: "a2", conversationId: "cA", peerSub: "bob",
            isMine: false, sentAt: Date(timeIntervalSince1970: 20), body: "new", status: .sent))
        try repo.save(StoredMessage(messageId: "b1", conversationId: "cB", peerSub: "carol",
            isMine: true, sentAt: Date(timeIntervalSince1970: 15), body: "hey", status: .sent))
        let latest = try repo.latestPerConversation()
        #expect(latest.count == 2)
        #expect(latest.first(where: { $0.conversationId == "cA" })?.body == "new")
    }

    @Test func latestPerConversationBreaksTiesOnMessageId() throws {
        let repo = try makeRepo()
        let tied = Date(timeIntervalSince1970: 42)
        try repo.save(StoredMessage(messageId: "c1-a", conversationId: "cA", peerSub: "bob",
            isMine: true, sentAt: tied, body: "first-in-tie", status: .sent))
        try repo.save(StoredMessage(messageId: "c1-b", conversationId: "cA", peerSub: "bob",
            isMine: false, sentAt: tied, body: "second-in-tie", status: .sent))
        let latest = try repo.latestPerConversation()
        #expect(latest.count == 1)
        #expect(latest.first?.messageId == "c1-b")
    }

    @Test func loadThreadBreaksTiesOnMessageId() throws {
        let repo = try makeRepo()
        let convo = "c1"
        let tied = Date(timeIntervalSince1970: 42)
        try repo.save(StoredMessage(messageId: "z", conversationId: convo, peerSub: "bob",
            isMine: true, sentAt: tied, body: "z", status: .sent))
        try repo.save(StoredMessage(messageId: "a", conversationId: convo, peerSub: "bob",
            isMine: false, sentAt: tied, body: "a", status: .sent))
        let thread = try repo.loadThread(conversationId: convo)
        #expect(thread.map(\.messageId) == ["a", "z"])
    }
}
