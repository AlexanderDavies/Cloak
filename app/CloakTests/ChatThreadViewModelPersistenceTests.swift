import Testing
import Foundation
import GRDB
@testable import Cloak

/// Tests for the persistence behaviour added in C8: restore history on load, persist every send/receive,
/// and insert by `sentAt` rather than append. Uses a fake crypto/session/bundle stack (identity-passthrough
/// encrypt/decrypt) since the crypto path itself is already covered by `ChatThreadViewModelTests` —
/// these tests isolate the repository/ordering behaviour with a real temp-file `GRDBMessageRepository`.
@Suite @MainActor struct ChatThreadViewModelPersistenceTests {
    private let mySub = "alice"
    private let bobSub = "bob"
    private var convoId: String { ConversationKey.of([mySub, bobSub]) }

    private func makeRepository() throws -> GRDBMessageRepository {
        let path = NSTemporaryDirectory() + "ct-persist-\(UUID().uuidString).sqlite"
        let database = try EncryptedDatabase.open(path: path, passphrase: "test-pass")
        return try GRDBMessageRepository(database: database)
    }

    private func makeViewModel(
        repository: MessageRepository,
        transport: FakeMessageTransport = FakeMessageTransport(),
        establisher: SessionEstablishing = FakePersistenceSessionEstablisher(),
        bundleClient: PreKeyBundleClient = FakePersistenceBundleClient()
    ) -> ChatThreadViewModel {
        ChatThreadViewModel(
            recipient: ResolvedRecipient(sub: bobSub, deviceId: 1),
            myDeviceId: 1,
            repository: repository,
            mySub: mySub,
            bundleClient: bundleClient,
            establisher: establisher,
            crypto: FakePersistenceCrypto(),
            transport: transport,
            tokenProvider: { "test-token" })
    }

    /// Builds an inbound envelope whose ciphertext (under the identity-passthrough fake crypto)
    /// decodes straight back to a `MessageContent` with the given `sentAt`/`text`. `conversationId`
    /// defaults to the correct canonical id; tests that need a deliberately wrong one pass it explicitly.
    private func envelope(
        id: String, sentAt: TimeInterval, text: String, conversationId: String? = nil
    ) throws -> MessageEnvelope {
        let content = MessageContent(sentAt: Date(timeIntervalSince1970: sentAt), text: text)
        return MessageEnvelope(
            messageId: id,
            toSub: mySub,
            toDeviceId: 1,
            fromDeviceId: 1,
            messageType: .normal,
            conversationId: conversationId ?? convoId,
            ciphertext: try content.serialized().base64EncodedString(),
            fromSub: bobSub)
    }

    @Test func loadsExistingThreadFromRepositoryOnLoad() throws {
        let repository = try makeRepository()
        try repository.save(StoredMessage(
            messageId: "m1", conversationId: convoId, peerSub: bobSub, isMine: false,
            sentAt: Date(timeIntervalSince1970: 1), body: "earlier", status: .sent))
        let viewModel = makeViewModel(repository: repository)

        viewModel.loadHistory()

        #expect(viewModel.bubbles.map(\.text) == ["earlier"])
    }

    @Test func sendPersistsThenMarksSent() async throws {
        let repository = try makeRepository()
        let viewModel = makeViewModel(repository: repository)

        await viewModel.send("hi")

        #expect(viewModel.bubbles.last?.text == "hi")
        #expect(viewModel.bubbles.last?.status == .sent)
        #expect(viewModel.error == nil)
        let thread = try repository.loadThread(conversationId: convoId)
        #expect(thread.contains { $0.body == "hi" && $0.isMine && $0.status == .sent })
    }

    /// A transport failure must leave a `failed` row in the DB, not just a `failed` UI bubble —
    /// the persist(sending) → send → mark step must not lose the attempt on failure.
    @Test func sendMarksFailedInBothUiAndRepositoryWhenTransportFails() async throws {
        enum TransportError: Error { case down }
        let repository = try makeRepository()
        let transport = FakeMessageTransport(sendError: TransportError.down)
        let viewModel = makeViewModel(repository: repository, transport: transport)

        await viewModel.send("hi")

        #expect(viewModel.bubbles.last?.status == .failed)
        #expect(viewModel.error != nil)
        let thread = try repository.loadThread(conversationId: convoId)
        let stored = try #require(thread.first { $0.body == "hi" })
        #expect(stored.status == .failed)
    }

    @Test func receivePersistsMessageAsNotMine() async throws {
        let repository = try makeRepository()
        let viewModel = makeViewModel(repository: repository)

        await viewModel.receive(try envelope(id: "r1", sentAt: 50, text: "hello"))

        let thread = try repository.loadThread(conversationId: convoId)
        #expect(thread.first?.body == "hello")
        #expect(thread.first?.isMine == false)
        #expect(thread.first?.status == .sent)
    }

    @Test func receiveRetriesATransientSaveFailure() async throws {
        let repository = try makeRepository()
        let viewModel = makeViewModel(repository: FlakySaveRepository(wrapping: repository, failures: 1))

        await viewModel.receive(try envelope(id: "r1", sentAt: 50, text: "hello"))

        #expect(try repository.loadThread(conversationId: convoId).first?.body == "hello")
        #expect(viewModel.error == nil)
    }

    /// The decrypt already spent the message key, so the plaintext in hand is the only copy left.
    /// If it cannot be stored it is still shown for this session — with an error — never discarded.
    @Test func receiveStillShowsAMessageThatCannotBeSaved() async throws {
        let repository = try makeRepository()
        let viewModel = makeViewModel(repository: FlakySaveRepository(wrapping: repository, failures: .max))

        await viewModel.receive(try envelope(id: "r1", sentAt: 50, text: "hello"))

        #expect(viewModel.bubbles.map(\.text) == ["hello"])
        #expect(viewModel.error != nil)
    }

    @Test func lateArrivalInsertsInSentAtOrderNotAppended() async throws {
        let repository = try makeRepository()
        let viewModel = makeViewModel(repository: repository)

        await viewModel.receive(try envelope(id: "later", sentAt: 200, text: "B"))
        await viewModel.receive(try envelope(id: "earlier", sentAt: 100, text: "A"))

        #expect(viewModel.bubbles.map(\.text) == ["A", "B"])
    }

    /// A message already restored from the local backlog (same id) must not double-render if it
    /// then also arrives live — the sorted `insert` must dedupe on id, not just append blindly.
    @Test func liveArrivalOfAnAlreadyBacklogPersistedMessageDoesNotDoubleRender() async throws {
        let repository = try makeRepository()
        try repository.save(StoredMessage(
            messageId: "dup", conversationId: convoId, peerSub: bobSub, isMine: false,
            sentAt: Date(timeIntervalSince1970: 10), body: "hello", status: .sent))
        let viewModel = makeViewModel(repository: repository)
        viewModel.loadHistory()

        await viewModel.receive(try envelope(id: "dup", sentAt: 10, text: "hello"))

        #expect(viewModel.bubbles.count == 1)
    }

    /// Session establishment (the pre-key bundle fetch) is a network call, so it fails in the
    /// ordinary offline/server-down case — that is still a transport failure by the same contract as
    /// a failure after encrypt: the composed message must leave a `failed` row AND a `failed` bubble,
    /// never vanish with only a transient `error` string.
    @Test func sendMarksFailedWhenSessionEstablishmentFailsBeforeEncrypt() async throws {
        enum BundleFetchError: Error { case offline }
        let repository = try makeRepository()
        let viewModel = makeViewModel(
            repository: repository,
            establisher: FakeNeverEstablishedSessionEstablisher(),
            bundleClient: FakeFailingBundleClient(error: BundleFetchError.offline))

        await viewModel.send("hi")

        #expect(viewModel.bubbles.last?.status == .failed)
        #expect(viewModel.error != nil)
        let thread = try repository.loadThread(conversationId: convoId)
        let stored = try #require(thread.first { $0.body == "hi" })
        #expect(stored.status == .failed)
    }

    /// The server is untrusted with content: `receive` must key the persisted row on the
    /// locally-derived conversationId, not the delivery frame's `env.conversationId` — a wrong or
    /// empty server-supplied value must not orphan unrecoverable (decrypt-once) plaintext where
    /// `loadHistory` can never find it again.
    @Test func receiveIgnoresMismatchedEnvelopeConversationIdAndUsesLocalKey() async throws {
        let repository = try makeRepository()
        let viewModel = makeViewModel(repository: repository)

        await viewModel.receive(
            try envelope(id: "r2", sentAt: 60, text: "hi", conversationId: "wrong-conversation-id"))

        let thread = try repository.loadThread(conversationId: convoId)
        #expect(thread.contains { $0.body == "hi" })
    }

    /// A repository read failure must surface on `self.error` and must not clobber a bubble already
    /// on screen (e.g. from a live message that arrived before the failed load) — local history is
    /// authoritative, so an empty result on failure would be indistinguishable from "no history."
    @Test func loadHistoryFailureSurfacesErrorAndLeavesBubblesUntouched() async throws {
        let repository = LoadThreadThrowingRepository()
        let viewModel = makeViewModel(repository: repository)
        await viewModel.receive(try envelope(id: "already-shown", sentAt: 5, text: "already on screen"))
        #expect(viewModel.bubbles.count == 1)

        viewModel.loadHistory()

        #expect(viewModel.error != nil)
        #expect(viewModel.bubbles.count == 1)
        #expect(viewModel.bubbles.first?.text == "already on screen")
    }
}

// MARK: - Persistence-test fakes

/// Deterministic fake transport: captures sent envelopes and can be told to fail `send` once
/// (mirrors the intent of `ChatThreadViewModelTests`'s own `FakeMessageTransport`, kept separate
/// here to avoid coupling this file's line count to that suite's).
private final class FakeMessageTransport: MessageTransport, @unchecked Sendable {
    private(set) var sentEnvelopes: [MessageEnvelope] = []
    private let sendError: Error?

    init(sendError: Error? = nil) {
        self.sendError = sendError
    }

    func connect(accessToken: String) async throws {}

    func send(_ envelope: MessageEnvelope) async throws {
        if let sendError { throw sendError }
        sentEnvelopes.append(envelope)
    }

    func inbound() async -> AsyncThrowingStream<MessageEnvelope, Error> {
        AsyncThrowingStream { $0.finish() }
    }

    func disconnect() async {}
}

/// Identity-passthrough crypto: `encrypt` returns the plaintext unchanged as "ciphertext" and
/// `decrypt` returns the ciphertext unchanged as "plaintext". Sufficient for exercising the
/// persistence/ordering behaviour, which does not depend on real Signal wire bytes (that path is
/// covered by `ChatThreadViewModelTests`).
private struct FakePersistenceCrypto: MessageCrypting {
    func encrypt(_ plaintext: Data, to recipientSub: String, deviceId: UInt32) throws -> EncryptedPayload {
        EncryptedPayload(ciphertext: plaintext, type: .normal)
    }

    func decrypt(_ ciphertext: Data, type: SignalMessageType, from senderSub: String, deviceId: UInt32) throws
        -> Data {
        ciphertext
    }
}

/// Reports a session as always already established, so `send` never needs a real bundle fetch.
private struct FakePersistenceSessionEstablisher: SessionEstablishing {
    func establishOutbound(with bundle: RemotePreKeyBundle, recipientSub: String) throws {}
    func hasSession(recipientSub: String, deviceId: UInt32) -> Bool { true }
}

/// Unused by these tests (`FakePersistenceSessionEstablisher.hasSession` always returns true), but
/// required to satisfy `ChatThreadViewModel`'s `bundleClient` dependency.
private struct FakePersistenceBundleClient: PreKeyBundleClient {
    func fetchBundle(sub: String, accessToken: String) async throws -> RemotePreKeyBundle {
        RemotePreKeyBundle(
            registrationId: 1, deviceId: 1, identityKey: "",
            signedPreKey: .init(keyId: 1, publicKey: "", signature: ""),
            oneTimePreKey: nil,
            kyberPreKey: .init(keyId: 1, publicKey: "", signature: ""))
    }
}

/// Reports no existing session, forcing `send` to attempt establishment via the bundle client.
private struct FakeNeverEstablishedSessionEstablisher: SessionEstablishing {
    func establishOutbound(with bundle: RemotePreKeyBundle, recipientSub: String) throws {}
    func hasSession(recipientSub: String, deviceId: UInt32) -> Bool { false }
}

/// Always fails the bundle fetch — simulates the ordinary offline/server-down case.
private struct FakeFailingBundleClient: PreKeyBundleClient {
    let error: Error
    func fetchBundle(sub: String, accessToken: String) async throws -> RemotePreKeyBundle {
        throw error
    }
}

/// `save`/`updateStatus` behave normally (so `receive`/`send` can populate state ahead of a test);
/// `loadThread` always fails, to exercise `loadHistory`'s error path in isolation.
private final class LoadThreadThrowingRepository: MessageRepository, @unchecked Sendable {
    enum RepositoryError: Error { case readFailed }

    func save(_ message: StoredMessage) throws {}
    func contains(messageId: String) throws -> Bool { false }
    func updateStatus(messageId: String, status: MessageStatus) throws {}
    func loadThread(conversationId: String) throws -> [StoredMessage] {
        throw RepositoryError.readFailed
    }
    func latestPerConversation() throws -> [StoredMessage] { [] }
}
