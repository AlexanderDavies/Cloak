import Foundation
import GRDB
import Testing
@testable import Cloak

/// Tests for `ConversationListViewModel`: mapping local history into list rows and the
/// refresh-then-reload ordering against a real temp-DB `GRDBMessageRepository`.
@Suite @MainActor struct ConversationListViewModelTests {
    private func makeRepo() throws -> GRDBMessageRepository {
        let path = NSTemporaryDirectory() + UUID().uuidString + ".sqlite"
        let database = try EncryptedDatabase.open(path: path, passphrase: "test-pass")
        return try GRDBMessageRepository(database: database)
    }

    @Test func loadMapsLatestPerConversation() throws {
        let repo = try makeRepo()
        try repo.save(StoredMessage(
            messageId: "a2", conversationId: "cA", peerSub: "bob",
            isMine: false, sentAt: Date(timeIntervalSince1970: 20), body: "newest", status: .sent))
        let model = ConversationListViewModel(repository: repo, backlogSync: NoopSync())

        model.load()

        #expect(model.conversations.first?.lastMessage == "newest")
        #expect(model.conversations.first?.peerSub == "bob")
    }

    @Test func loadOrdersConversationsNewestConversationFirst() throws {
        let repo = try makeRepo()
        try repo.save(StoredMessage(
            messageId: "a1", conversationId: "cA", peerSub: "bob",
            isMine: true, sentAt: Date(timeIntervalSince1970: 10), body: "older-convo", status: .sent))
        try repo.save(StoredMessage(
            messageId: "b1", conversationId: "cB", peerSub: "carol",
            isMine: true, sentAt: Date(timeIntervalSince1970: 50), body: "newer-convo", status: .sent))
        let model = ConversationListViewModel(repository: repo, backlogSync: NoopSync())

        model.load()

        #expect(model.conversations.map(\.conversationId) == ["cB", "cA"])
    }

    @Test func loadSurfacesRepositoryErrorInsteadOfEmptyingSilently() {
        let model = ConversationListViewModel(repository: ThrowingRepository(), backlogSync: NoopSync())

        model.load()

        #expect(model.conversations.isEmpty)
        #expect(model.error != nil)
    }

    @Test func loadClearsErrorAfterASubsequentSuccessfulLoad() {
        let repository = FlakyRepository(failFirstNCalls: 1)
        let model = ConversationListViewModel(repository: repository, backlogSync: NoopSync())

        model.load()
        #expect(model.error != nil)

        model.load()

        #expect(model.error == nil)
        #expect(model.conversations.first?.conversationId == "cA")
    }

    @Test func refreshSyncsBacklogBeforeReloadingFromLocalHistory() async throws {
        let repo = try makeRepo()
        let backlogMessage = StoredMessage(
            messageId: "z1", conversationId: "cZ", peerSub: "zoe",
            isMine: false, sentAt: Date(timeIntervalSince1970: 999), body: "arrived while away", status: .sent)
        let sync = InsertingSync(repository: repo, message: backlogMessage)
        let model = ConversationListViewModel(repository: repo, backlogSync: sync)

        await model.refresh()

        #expect(sync.syncCallCount == 1)
        #expect(model.conversations.first?.conversationId == "cZ")
        #expect(model.conversations.first?.lastMessage == "arrived while away")
        #expect(model.conversations.first?.peerSub == "zoe")
    }

    /// The server withholds messages for a few seconds until they settle, so a message sent just
    /// before the list appeared would otherwise wait for the next launch. One follow-up pull after
    /// the settle window collects it.
    @Test func refreshOnAppearPullsAgainAfterTheSettleWindow() async throws {
        let repo = try makeRepo()
        let backlogMessage = StoredMessage(
            messageId: "z1", conversationId: "cZ", peerSub: "zoe",
            isMine: false, sentAt: Date(timeIntervalSince1970: 999), body: "settled late", status: .sent)
        let sync = InsertingSync(repository: repo, message: backlogMessage)
        let model = ConversationListViewModel(repository: repo, backlogSync: sync, settleDelay: .zero)

        await model.refreshOnAppear()

        #expect(sync.syncCallCount == 2)
    }

    /// A backlog that cannot get past a message is permanently stuck: nothing behind it can ever
    /// arrive. The list keeps rendering from local history, so without surfacing the outcome the
    /// user sees a plausible-looking but silently-truncated inbox and no signal at all.
    @Test func refreshSurfacesABlockedBacklogToTheUser() async throws {
        let repo = try makeRepo()
        let model = ConversationListViewModel(repository: repo, backlogSync: OutcomeSync(outcome: .blocked))

        await model.refresh()

        #expect(model.error != nil)
    }

    @Test func refreshSurfacesAnUnreachableBacklog() async throws {
        let repo = try makeRepo()
        let model = ConversationListViewModel(repository: repo, backlogSync: OutcomeSync(outcome: .unreachable))

        await model.refresh()

        #expect(model.error != nil)
    }

    /// Skipped messages are gone for good, so the user must be told how many — the count is the only
    /// detail that may travel to the screen.
    @Test func refreshTellsTheUserHowManyMessagesWereSkipped() async throws {
        let repo = try makeRepo()
        let model = ConversationListViewModel(
            repository: repo, backlogSync: OutcomeSync(outcome: .skippedUndecryptable(count: 3)))

        await model.refresh()

        let surfaced = try #require(model.error)
        #expect(surfaced.contains("3"))
    }

    /// The surfaced text is shown on screen, so it must never carry a peer `sub`, a message id, or
    /// an underlying error description (root CLAUDE.md principle 6).
    @Test func surfacedSyncErrorCarriesNoIdentifiers() async throws {
        let repo = try makeRepo()
        try repo.save(StoredMessage(
            messageId: "secret-message-id", conversationId: "secret-conversation", peerSub: "secret-peer",
            isMine: false, sentAt: Date(timeIntervalSince1970: 1), body: "secret-body", status: .sent))
        let model = ConversationListViewModel(repository: repo, backlogSync: OutcomeSync(outcome: .blocked))

        await model.refresh()

        let surfaced = try #require(model.error)
        for leak in ["secret-message-id", "secret-conversation", "secret-peer", "secret-body"] {
            #expect(!surfaced.contains(leak))
        }
    }

    @Test func refreshClearsASyncErrorOnceTheBacklogRecovers() async throws {
        let repo = try makeRepo()
        let sync = OutcomeSync(outcome: .blocked)
        let model = ConversationListViewModel(repository: repo, backlogSync: sync)

        await model.refresh()
        #expect(model.error != nil)

        sync.outcome = .completed
        await model.refresh()

        #expect(model.error == nil)
    }

    /// A repository read failure makes the whole list untrustworthy, so it must not be masked by a
    /// sync message about missing *new* messages.
    @Test func repositoryReadFailureTakesPrecedenceOverASyncOutcome() async throws {
        let model = ConversationListViewModel(
            repository: ThrowingRepository(), backlogSync: OutcomeSync(outcome: .unreachable))

        await model.refresh()

        let surfaced = try #require(model.error)
        #expect(surfaced == ThrowingRepository.ReadFailure().localizedDescription)
    }
}

// MARK: - Test doubles

private struct NoopSync: BacklogSyncing {
    func sync() async -> BacklogSyncOutcome { .completed }
}

/// Reports a fixed (mutable) sync outcome without touching storage — for asserting how the view
/// model surfaces each outcome.
private final class OutcomeSync: BacklogSyncing, @unchecked Sendable {
    var outcome: BacklogSyncOutcome

    init(outcome: BacklogSyncOutcome) {
        self.outcome = outcome
    }

    func sync() async -> BacklogSyncOutcome { outcome }
}

/// Always fails `latestPerConversation()` — proves a read failure surfaces on `error` rather than
/// silently rendering as an empty conversation list.
private struct ThrowingRepository: MessageRepository {
    struct ReadFailure: Error {}

    func save(_ message: StoredMessage) throws {}
    func contains(messageId: String) throws -> Bool { false }
    func updateStatus(messageId: String, status: MessageStatus) throws {}
    func loadThread(conversationId: String) throws -> [StoredMessage] { [] }
    func latestPerConversation() throws -> [StoredMessage] { throw ReadFailure() }
}

/// Throws `latestPerConversation()` for the first `failFirstNCalls` calls, then succeeds — stands in
/// for a transient failure that recovers, proving `error` gets cleared on the subsequent success.
private final class FlakyRepository: MessageRepository, @unchecked Sendable {
    private struct ReadFailure: Error {}

    private let failFirstNCalls: Int
    private var callCount = 0

    init(failFirstNCalls: Int) {
        self.failFirstNCalls = failFirstNCalls
    }

    func save(_ message: StoredMessage) throws {}
    func contains(messageId: String) throws -> Bool { false }
    func updateStatus(messageId: String, status: MessageStatus) throws {}
    func loadThread(conversationId: String) throws -> [StoredMessage] { [] }

    func latestPerConversation() throws -> [StoredMessage] {
        callCount += 1
        if callCount <= failFirstNCalls {
            throw ReadFailure()
        }
        return [StoredMessage(
            messageId: "a1", conversationId: "cA", peerSub: "bob",
            isMine: false, sentAt: Date(timeIntervalSince1970: 10), body: "recovered", status: .sent)]
    }
}

/// Records invocation count and, on `sync()`, writes straight into the shared repository — standing
/// in for a real `BacklogSync` decrypting-and-persisting an inbound message.
private final class InsertingSync: BacklogSyncing, @unchecked Sendable {
    private let repository: any MessageRepository
    private let message: StoredMessage
    private(set) var syncCallCount = 0

    init(repository: any MessageRepository, message: StoredMessage) {
        self.repository = repository
        self.message = message
    }

    func sync() async -> BacklogSyncOutcome {
        syncCallCount += 1
        try? repository.save(message)
        return .completed
    }
}
