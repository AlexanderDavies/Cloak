import Foundation
import GRDB
import Testing
@testable import Cloak

@Suite struct BacklogSyncTests {
    private static let mySub = "alice"

    private func makeHarness() throws -> (repository: GRDBMessageRepository, cursor: GRDBBacklogCursor) {
        let path = NSTemporaryDirectory() + UUID().uuidString + ".sqlite"
        let database = try EncryptedDatabase.open(path: path, passphrase: "test-pass")
        return (try GRDBMessageRepository(database: database), try GRDBBacklogCursor(database: database))
    }

    private func makeSync(
        client: MessageHistoryClient,
        crypto: MessageCrypting,
        repository: MessageRepository,
        cursor: BacklogCursorStore
    ) -> BacklogSync {
        BacklogSync(client: client, crypto: crypto, repository: repository, cursor: cursor,
                    mySub: Self.mySub, tokenProvider: { "t" })
    }

    /// The locally-derived key every message from `bob` must land under, regardless of what
    /// `conversationId` the server sent.
    private static let bobConversation = ConversationKey.of([mySub, "bob"])

    private func backlogMessage(id: String, ciphertext: String, cursor: String) -> BacklogMessage {
        BacklogMessage(
            messageId: id, fromSub: "bob", fromDeviceId: 1, toDeviceId: 1, messageType: .normal,
            conversationId: Self.bobConversation, ciphertext: ciphertext, cursor: cursor)
    }

    @Test func decryptsPersistsAndAdvancesCursor() async throws {
        let harness = try makeHarness()
        let content = try MessageContent(sentAt: Date(timeIntervalSince1970: 5), text: "hello").serialized()
        let backlogMessage = BacklogMessage(
            messageId: "m1", fromSub: "bob", fromDeviceId: 1, toDeviceId: 1, messageType: .normal,
            conversationId: Self.bobConversation, ciphertext: Data("ct".utf8).base64EncodedString(), cursor: "cur-1")
        let sync = makeSync(
            client: FakeMessageHistoryClient(pages: [BacklogPage(messages: [backlogMessage], hasMore: false)]),
            crypto: FakeMessageCrypto(decryptResult: content),
            repository: harness.repository, cursor: harness.cursor)

        let outcome = await sync.sync()

        #expect(outcome == .completed)
        #expect(try harness.repository.loadThread(conversationId: Self.bobConversation).first?.body == "hello")
        #expect(harness.cursor.highWaterMark() == "cur-1")
    }

    /// The regression guard for the branch's worst failure mode. Live `/ws` delivery persists a
    /// message without touching the cursor, so the server re-serves it on the next launch. Its
    /// message key is already consumed, so decrypting again always throws (`duplicatedMessage`) —
    /// which, before the pre-decrypt dedupe, stopped `consume` and left the cursor pinned forever,
    /// killing the backlog from the very first live-delivered message onward.
    @Test func skipsAlreadyStoredMessageWithoutDecryptingAndStillAdvances() async throws {
        let harness = try makeHarness()
        try harness.repository.save(StoredMessage(
            messageId: "live-1", conversationId: Self.bobConversation, peerSub: "bob",
            isMine: false, sentAt: Date(timeIntervalSince1970: 5), body: "arrived live", status: .sent))
        let alreadyDelivered = BacklogMessage(
            messageId: "live-1", fromSub: "bob", fromDeviceId: 1, toDeviceId: 1, messageType: .normal,
            conversationId: Self.bobConversation, ciphertext: Data("ct".utf8).base64EncodedString(), cursor: "cur-1")
        // Fails every decrypt, exactly like a ratchet that has already consumed the message key.
        let crypto = FakeMessageCrypto(decryptResult: Data(), failsEveryDecrypt: true)
        let sync = makeSync(
            client: FakeMessageHistoryClient(pages: [BacklogPage(messages: [alreadyDelivered], hasMore: false)]),
            crypto: crypto, repository: harness.repository, cursor: harness.cursor)

        let outcome = await sync.sync()

        #expect(crypto.decryptCallCount == 0)
        #expect(harness.cursor.highWaterMark() == "cur-1")
        #expect(outcome == .completed)
        #expect(try harness.repository.loadThread(conversationId: Self.bobConversation).count == 1)
    }

    /// A failed dedupe check must stop, not skip: skipping would risk a destructive re-decrypt of a
    /// message this device may already hold, while stopping only defers it to the next sync.
    @Test func stopsWhenTheDedupeCheckItselfFails() async throws {
        let harness = try makeHarness()
        let message = BacklogMessage(
            messageId: "m1", fromSub: "bob", fromDeviceId: 1, toDeviceId: 1, messageType: .normal,
            conversationId: Self.bobConversation, ciphertext: Data("ct".utf8).base64EncodedString(), cursor: "cur-1")
        let crypto = FakeMessageCrypto(decryptResult: Data())
        let sync = makeSync(
            client: FakeMessageHistoryClient(pages: [BacklogPage(messages: [message], hasMore: false)]),
            crypto: crypto, repository: ContainsThrowingRepository(), cursor: harness.cursor)

        let outcome = await sync.sync()

        #expect(crypto.decryptCallCount == 0)
        #expect(harness.cursor.highWaterMark() == nil)
        #expect(outcome == .blocked)
    }

    /// The server's `conversationId` is not trusted as the local history key: a wrong value would
    /// file decrypt-once plaintext under a conversation no screen can ever query. Mirrors the same
    /// guard in `ChatThreadViewModel.receive`.
    @Test func filesMessagesUnderTheLocallyDerivedConversationKey() async throws {
        let harness = try makeHarness()
        let content = try MessageContent(sentAt: Date(timeIntervalSince1970: 5), text: "hello").serialized()
        let mismatched = BacklogMessage(
            messageId: "m1", fromSub: "bob", fromDeviceId: 1, toDeviceId: 1, messageType: .normal,
            conversationId: "not-the-real-conversation-id",
            ciphertext: Data("ct".utf8).base64EncodedString(), cursor: "cur-1")
        let sync = makeSync(
            client: FakeMessageHistoryClient(pages: [BacklogPage(messages: [mismatched], hasMore: false)]),
            crypto: FakeMessageCrypto(decryptResult: content),
            repository: harness.repository, cursor: harness.cursor)

        await sync.sync()

        #expect(try harness.repository.loadThread(conversationId: Self.bobConversation).first?.body == "hello")
        #expect(try harness.repository.loadThread(conversationId: "not-the-real-conversation-id").isEmpty)
    }

    /// A message that cannot be decrypted never will be — the ratchet consumes a message key on the
    /// first attempt, and a message encrypted to a since-replaced identity has no key at all. Holding
    /// the cursor on it would wedge every later message behind it forever, so it is skipped and
    /// counted. The `bad` message sits mid-page so "skip and keep going" is distinguishable from "stop".
    @Test func skipsAnUndecryptableMessageAndKeepsGoing() async throws {
        let harness = try makeHarness()
        let content = try MessageContent(sentAt: Date(timeIntervalSince1970: 5), text: "hello").serialized()
        let badCiphertext = Data("bad-ct".utf8)
        let good = backlogMessage(id: "m1", ciphertext: Data("good-ct".utf8).base64EncodedString(), cursor: "cur-1")
        let bad = backlogMessage(id: "m2", ciphertext: badCiphertext.base64EncodedString(), cursor: "cur-2")
        let trailing = backlogMessage(
            id: "m3", ciphertext: Data("trailing-ct".utf8).base64EncodedString(), cursor: "cur-3")
        let sync = makeSync(
            client: FakeMessageHistoryClient(pages: [BacklogPage(messages: [good, bad, trailing], hasMore: false)]),
            crypto: FakeMessageCrypto(decryptResult: content, failingCiphertexts: [badCiphertext]),
            repository: harness.repository, cursor: harness.cursor)

        let outcome = await sync.sync()

        #expect(harness.cursor.highWaterMark() == "cur-3")
        let stored = try harness.repository.loadThread(conversationId: Self.bobConversation).map(\.messageId)
        #expect(stored == ["m1", "m3"])
        #expect(outcome == .skippedUndecryptable(count: 1))
    }

    /// Live delivery and the backlog can race on one message: the live path decrypts and stores it
    /// first, so the backlog's decrypt then fails as a duplicate. That message was delivered, not
    /// lost, so it must not be counted (and reported) as undecryptable.
    @Test func aDecryptLostToTheLivePathIsNotCountedAsSkipped() async throws {
        let harness = try makeHarness()
        let message = backlogMessage(id: "m1", ciphertext: Data("ct".utf8).base64EncodedString(), cursor: "cur-1")
        let crypto = LiveRaceCrypto(repository: harness.repository, conversationId: Self.bobConversation)
        let sync = makeSync(
            client: FakeMessageHistoryClient(pages: [BacklogPage(messages: [message], hasMore: false)]),
            crypto: crypto, repository: harness.repository, cursor: harness.cursor)

        let outcome = await sync.sync()

        #expect(outcome == .completed)
        #expect(harness.cursor.highWaterMark() == "cur-1")
    }

    /// The invalid-base64 guard returns before `crypto.decrypt` is reached, so it is its own path to
    /// the same verdict: an unparsable message can never become parsable on a retry.
    @Test func skipsInvalidBase64Ciphertext() async throws {
        let harness = try makeHarness()
        let content = try MessageContent(sentAt: Date(timeIntervalSince1970: 5), text: "hello").serialized()
        let invalid = backlogMessage(id: "m1", ciphertext: "!!!not-base64!!!", cursor: "cur-1")
        let good = backlogMessage(id: "m2", ciphertext: Data("good-ct".utf8).base64EncodedString(), cursor: "cur-2")
        let sync = makeSync(
            client: FakeMessageHistoryClient(pages: [BacklogPage(messages: [invalid, good], hasMore: false)]),
            crypto: FakeMessageCrypto(decryptResult: content),
            repository: harness.repository, cursor: harness.cursor)

        let outcome = await sync.sync()

        #expect(harness.cursor.highWaterMark() == "cur-2")
        #expect(try harness.repository.loadThread(conversationId: Self.bobConversation).map(\.messageId) == ["m2"])
        #expect(outcome == .skippedUndecryptable(count: 1))
    }

    /// Decryption succeeded, so the message key is already spent: plaintext that is not a valid
    /// `MessageContent` can never be recovered by retrying.
    @Test func skipsMalformedPlaintext() async throws {
        let harness = try makeHarness()
        let message = backlogMessage(id: "m1", ciphertext: Data("ct".utf8).base64EncodedString(), cursor: "cur-1")
        let sync = makeSync(
            client: FakeMessageHistoryClient(pages: [BacklogPage(messages: [message], hasMore: false)]),
            crypto: FakeMessageCrypto(decryptResult: Data("not-message-content".utf8)),
            repository: harness.repository, cursor: harness.cursor)

        let outcome = await sync.sync()

        #expect(harness.cursor.highWaterMark() == "cur-1")
        #expect(outcome == .skippedUndecryptable(count: 1))
    }

    /// Skips accumulate across pages, so the user is told the true number.
    @Test func countsSkippedMessagesAcrossPages() async throws {
        let harness = try makeHarness()
        let first = backlogMessage(id: "m1", ciphertext: "!!!", cursor: "cur-1")
        let second = backlogMessage(id: "m2", ciphertext: "???", cursor: "cur-2")
        let sync = makeSync(
            client: FakeMessageHistoryClient(pages: [
                BacklogPage(messages: [first], hasMore: true),
                BacklogPage(messages: [second], hasMore: false)
            ]),
            crypto: FakeMessageCrypto(decryptResult: Data()),
            repository: harness.repository, cursor: harness.cursor)

        let outcome = await sync.sync()

        #expect(harness.cursor.highWaterMark() == "cur-2")
        #expect(outcome == .skippedUndecryptable(count: 2))
    }

    /// The message key is already spent once decrypt succeeds, so a transient write failure is
    /// retried on the spot rather than deferred to a sync that could no longer decrypt it.
    @Test func retriesATransientSaveFailureInPlace() async throws {
        let harness = try makeHarness()
        let content = try MessageContent(sentAt: Date(timeIntervalSince1970: 5), text: "hello").serialized()
        let message = backlogMessage(id: "m1", ciphertext: Data("ct".utf8).base64EncodedString(), cursor: "cur-1")
        let sync = makeSync(
            client: FakeMessageHistoryClient(pages: [BacklogPage(messages: [message], hasMore: false)]),
            crypto: FakeMessageCrypto(decryptResult: content),
            repository: FlakySaveRepository(wrapping: harness.repository, failures: 1), cursor: harness.cursor)

        let outcome = await sync.sync()

        #expect(outcome == .completed)
        #expect(try harness.repository.loadThread(conversationId: Self.bobConversation).first?.body == "hello")
    }

    /// A local write failure is transient, unlike a decrypt failure, so it must stop without
    /// advancing — the next sync retries it rather than skipping a message the device could store.
    @Test func stopsAndDoesNotAdvanceWhenPersistingFails() async throws {
        let harness = try makeHarness()
        let content = try MessageContent(sentAt: Date(timeIntervalSince1970: 5), text: "hello").serialized()
        let message = backlogMessage(id: "m1", ciphertext: Data("ct".utf8).base64EncodedString(), cursor: "cur-1")
        let sync = makeSync(
            client: FakeMessageHistoryClient(pages: [BacklogPage(messages: [message], hasMore: false)]),
            crypto: FakeMessageCrypto(decryptResult: content),
            repository: SaveThrowingRepository(), cursor: harness.cursor)

        let outcome = await sync.sync()

        #expect(harness.cursor.highWaterMark() == nil)
        #expect(outcome == .blocked)
    }

    /// If the cursor write silently fails (`GRDBBacklogCursor.advance` swallows write errors by
    /// design) or the server sends an empty `hasMore: true` page, the mark never moves — without a
    /// no-progress guard the loop would re-fetch the identical page forever. This asserts the sync
    /// stops after exactly one fetch instead of spinning.
    @Test func stopsAfterOneFetchWhenPageMakesNoProgress() async throws {
        let harness = try makeHarness()
        let client = FakeMessageHistoryClient(pages: [BacklogPage(messages: [], hasMore: true)])
        let sync = makeSync(
            client: client, crypto: FakeMessageCrypto(decryptResult: Data()),
            repository: harness.repository, cursor: harness.cursor)

        await sync.sync()

        #expect(client.requestedCursors.count == 1)
        #expect(harness.cursor.highWaterMark() == nil)
    }

    /// Without draining `hasMore` pages, a device with more than one page of backlog would silently
    /// stop after the first page and never see the rest of its history.
    @Test func continuesToNextPageWhenHasMoreAndCarriesAdvancedCursor() async throws {
        let harness = try makeHarness()
        let content = try MessageContent(sentAt: Date(timeIntervalSince1970: 5), text: "hello").serialized()
        let first = BacklogMessage(
            messageId: "m1", fromSub: "bob", fromDeviceId: 1, toDeviceId: 1, messageType: .normal,
            conversationId: Self.bobConversation, ciphertext: Data("ct1".utf8).base64EncodedString(), cursor: "cur-1")
        let second = BacklogMessage(
            messageId: "m2", fromSub: "bob", fromDeviceId: 1, toDeviceId: 1, messageType: .normal,
            conversationId: Self.bobConversation, ciphertext: Data("ct2".utf8).base64EncodedString(), cursor: "cur-2")
        let client = FakeMessageHistoryClient(pages: [
            BacklogPage(messages: [first], hasMore: true),
            BacklogPage(messages: [second], hasMore: false)
        ])
        let sync = makeSync(
            client: client, crypto: FakeMessageCrypto(decryptResult: content),
            repository: harness.repository, cursor: harness.cursor)

        await sync.sync()

        #expect(harness.cursor.highWaterMark() == "cur-2")
        #expect(try harness.repository.loadThread(conversationId: Self.bobConversation).count == 2)
        #expect(client.requestedCursors == [nil, "cur-1"])
    }

    /// A fetch that never lands is a different story to tell the user than a message that cannot be
    /// opened, and neither may be swallowed.
    @Test func reportsUnreachableWhenTheBacklogCannotBeFetched() async throws {
        let harness = try makeHarness()
        let sync = BacklogSync(
            client: FailingMessageHistoryClient(), crypto: FakeMessageCrypto(decryptResult: Data()),
            repository: harness.repository, cursor: harness.cursor, mySub: Self.mySub,
            tokenProvider: { "t" })

        let outcome = await sync.sync()

        #expect(outcome == .unreachable)
    }
}

// MARK: - Test helpers

private final class FakeMessageHistoryClient: MessageHistoryClient, @unchecked Sendable {
    private var pages: [BacklogPage]
    private(set) var requestedCursors: [String?] = []

    init(pages: [BacklogPage]) {
        self.pages = pages
    }

    func fetchHistory(cursor: String?, limit: Int, accessToken: String) async throws -> BacklogPage {
        requestedCursors.append(cursor)
        guard !pages.isEmpty else { return BacklogPage(messages: [], hasMore: false) }
        return pages.removeFirst()
    }
}

private struct FailingMessageHistoryClient: MessageHistoryClient {
    private struct Offline: Error {}

    func fetchHistory(cursor: String?, limit: Int, accessToken: String) async throws -> BacklogPage {
        throw Offline()
    }
}

/// `contains` always fails, standing in for a database read fault during the dedupe check.
private struct ContainsThrowingRepository: MessageRepository {
    private struct ReadFailure: Error {}

    func save(_ message: StoredMessage) throws {}
    func contains(messageId: String) throws -> Bool { throw ReadFailure() }
    func updateStatus(messageId: String, status: MessageStatus) throws {}
    func loadThread(conversationId: String) throws -> [StoredMessage] { [] }
    func latestPerConversation() throws -> [StoredMessage] { [] }
}

/// `save` always fails, standing in for a local database write fault after a successful decrypt.
private struct SaveThrowingRepository: MessageRepository {
    private struct WriteFailure: Error {}

    func save(_ message: StoredMessage) throws { throw WriteFailure() }
    func contains(messageId: String) throws -> Bool { false }
    func updateStatus(messageId: String, status: MessageStatus) throws {}
    func loadThread(conversationId: String) throws -> [StoredMessage] { [] }
    func latestPerConversation() throws -> [StoredMessage] { [] }
}

/// Stands in for the live path winning the race: by the time the backlog decrypts, the message has
/// been stored by live delivery and its key is spent, so the decrypt throws.
private struct LiveRaceCrypto: MessageCrypting {
    let repository: MessageRepository
    let conversationId: String

    func encrypt(_ plaintext: Data, to recipientSub: String, deviceId: UInt32) throws -> EncryptedPayload {
        EncryptedPayload(ciphertext: plaintext, type: .normal)
    }

    func decrypt(_ ciphertext: Data, type: SignalMessageType, from senderSub: String, deviceId: UInt32) throws
        -> Data {
        try repository.save(StoredMessage(
            messageId: "m1", conversationId: conversationId, peerSub: senderSub, isMine: false,
            sentAt: Date(timeIntervalSince1970: 5), body: "arrived live", status: .sent))
        throw FakeCryptoError.decryptFailed
    }
}

private enum FakeCryptoError: Error {
    case decryptFailed
}

private final class FakeMessageCrypto: MessageCrypting, @unchecked Sendable {
    private let decryptResult: Data
    private let failingCiphertexts: Set<Data>
    private let failsEveryDecrypt: Bool
    private(set) var decryptCallCount = 0

    init(decryptResult: Data, failingCiphertexts: Set<Data> = [], failsEveryDecrypt: Bool = false) {
        self.decryptResult = decryptResult
        self.failingCiphertexts = failingCiphertexts
        self.failsEveryDecrypt = failsEveryDecrypt
    }

    func encrypt(_ plaintext: Data, to recipientSub: String, deviceId: UInt32) throws -> EncryptedPayload {
        EncryptedPayload(ciphertext: plaintext, type: .normal)
    }

    func decrypt(_ ciphertext: Data, type: SignalMessageType, from senderSub: String, deviceId: UInt32) throws
        -> Data {
        decryptCallCount += 1
        if failsEveryDecrypt || failingCiphertexts.contains(ciphertext) { throw FakeCryptoError.decryptFailed }
        return decryptResult
    }
}
