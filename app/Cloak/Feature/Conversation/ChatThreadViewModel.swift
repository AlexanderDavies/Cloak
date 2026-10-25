import Foundation

/// A single message bubble rendered in the chat thread.
struct ChatBubble: Identifiable, Equatable, Sendable {
    let id: String
    let text: String
    let isMine: Bool
    let sentAt: Date
    var status: MessageStatus
}

/// Drives the chat thread screen: compose, encrypt, send, receive, and decrypt messages, backed by
/// the local encrypted history (`MessageRepository`) as the authoritative source of truth.
///
/// On first `send`, the view model lazily establishes a PQXDH session with the recipient
/// (fetching their pre-key bundle and calling the `SessionEstablisher`). The first encrypted
/// envelope is a `PreKeySignalMessage` (type `.preKey`); subsequent messages use the normal
/// Double-Ratchet path (type `.normal`).
///
/// Guide §3: `@Observable` VM — all behaviour here; the view is a dumb pass-through.
/// Guide §4: `@MainActor` for Swift 6 strict concurrency.
/// Guide §7: Encryption/decryption happen here; plaintext never crosses the transport boundary.
/// Guide §11: Every error path surfaces on `self.error`; nothing is silently dropped.
@MainActor @Observable final class ChatThreadViewModel {
    /// Ordered (by `sentAt`) list of message bubbles in this thread.
    private(set) var bubbles: [ChatBubble] = []
    /// Non-nil when a transport, encrypt, or decrypt operation fails.
    private(set) var error: String?

    private let recipient: ResolvedRecipient
    private let myDeviceId: UInt32
    private let repository: MessageRepository
    private let mySub: String
    private let bundleClient: PreKeyBundleClient
    private let establisher: SessionEstablishing
    private let crypto: MessageCrypting
    private let transport: MessageTransport
    private let tokenProvider: @Sendable () async throws -> String

    /// Canonical id shared with the server (`ConversationKey.of`), computed once from the two
    /// participant subs — cheap and immutable for the lifetime of this thread.
    private let conversationId: String

    /// Whether a PQXDH session is already established with `recipient`.
    /// Excluded from observation — this is an internal implementation flag, not view state.
    @ObservationIgnored private var sessionEstablished = false

    /// In-flight session establishment, memoized so concurrent `send`s share one establishment
    /// (rather than each fetching a bundle + re-running X3DH, consuming extra one-time prekeys).
    @ObservationIgnored private var establishTask: Task<Void, Error>?

    init(
        recipient: ResolvedRecipient,
        myDeviceId: UInt32,
        repository: MessageRepository,
        mySub: String,
        bundleClient: PreKeyBundleClient,
        establisher: SessionEstablishing,
        crypto: MessageCrypting,
        transport: MessageTransport,
        tokenProvider: @escaping @Sendable () async throws -> String
    ) {
        self.recipient = recipient
        self.myDeviceId = myDeviceId
        self.repository = repository
        self.mySub = mySub
        self.bundleClient = bundleClient
        self.establisher = establisher
        self.crypto = crypto
        self.transport = transport
        self.tokenProvider = tokenProvider
        self.conversationId = ConversationKey.of([mySub, recipient.sub])
    }

    /// Restores this thread from local encrypted history (call from the view's `.task`, before `start`).
    ///
    /// A read failure surfaces on `self.error` and leaves `bubbles` untouched — local history is
    /// authoritative (decrypt-once means the server cannot re-serve it), so a failed read must never
    /// be presented as "this conversation is empty," and must never clobber a bubble already on
    /// screen from a live message that arrived first.
    func loadHistory() {
        do {
            bubbles = try repository.loadThread(conversationId: conversationId).map {
                ChatBubble(id: $0.messageId, text: $0.body, isMine: $0.isMine, sentAt: $0.sentAt, status: $0.status)
            }
        } catch {
            self.error = error.localizedDescription
        }
    }

    /// Connects the transport, then subscribes to the inbound stream and routes each envelope to
    /// `receive(_:)`.
    ///
    /// Lifecycle (called once from the view's `.task` modifier):
    /// 1. Acquires a token from `tokenProvider` and calls `transport.connect`.
    /// 2. Opens the inbound stream and iterates until the transport fails or the task is cancelled.
    ///
    /// Maps a terminal stream error to `self.error` (guide §11: surfaced-error discipline —
    /// never silently drop). `CancellationError` is swallowed because it indicates the view was
    /// dismissed, not a messaging failure.
    func start() async {
        do {
            let token = try await tokenProvider()
            try await transport.connect(accessToken: token)
            let stream = await transport.inbound()
            for try await envelope in stream {
                await receive(envelope)
            }
        } catch is CancellationError {
            // View dismissed — not an error to surface.
        } catch {
            self.error = error.localizedDescription
        }
    }

    /// Lazily ensures a PQXDH session, then encrypts and sends `text`.
    ///
    /// On the first call to a new peer:
    /// 1. Fetches the recipient's pre-key bundle via `bundleClient`.
    /// 2. Runs PQXDH via `establisher` to write the outbound session into the store.
    /// 3. Encrypts with Signal's encrypt (first result is a `PreKeySignalMessage`).
    ///
    /// Persists a `sending` row and inserts the bubble **before** `ensureSession()` — session
    /// establishment fetches the recipient's pre-key bundle over the network, so it fails in the
    /// ordinary offline/server-down case, and that is still a transport failure by the same "leave a
    /// `failed` row, never silently lose the message" contract as a failure after the send. Any
    /// failure surfaces on `self.error` (never silently dropped — root CLAUDE.md §key-principles).
    func send(_ text: String) async {
        let messageId = UUID().uuidString
        let content = MessageContent(sentAt: Date(), text: text)
        let stored = StoredMessage(
            messageId: messageId, conversationId: conversationId, peerSub: recipient.sub,
            isMine: true, sentAt: content.sentAt, body: text, status: .sending)
        do {
            try repository.save(stored)
            insert(ChatBubble(id: messageId, text: text, isMine: true, sentAt: content.sentAt, status: .sending))
            try await ensureSession()
            let payload = try crypto.encrypt(try content.serialized(), to: recipient.sub, deviceId: recipient.deviceId)
            let envelope = MessageEnvelope(
                messageId: messageId,
                toSub: recipient.sub,
                toDeviceId: recipient.deviceId,
                fromDeviceId: myDeviceId,
                messageType: payload.type,
                conversationId: conversationId,
                ciphertext: payload.ciphertext.base64EncodedString())
            try await transport.send(envelope)
            try? repository.updateStatus(messageId: messageId, status: .sent)
            setStatus(messageId, .sent)
        } catch {
            try? repository.updateStatus(messageId: messageId, status: .failed)
            setStatus(messageId, .failed)
            self.error = error.localizedDescription
        }
    }

    /// Ensures a PQXDH session with `recipient` exists, establishing one only if needed.
    ///
    /// - Reuses a session already in the encrypted store (e.g. a re-entered thread), so we don't
    ///   re-run X3DH — which would burn another one-time prekey and reset the Double-Ratchet state.
    /// - Memoizes the in-flight establishment so concurrent `send`s await one shared attempt
    ///   instead of each fetching a bundle and establishing in parallel.
    private func ensureSession() async throws {
        if sessionEstablished { return }
        if establisher.hasSession(recipientSub: recipient.sub, deviceId: recipient.deviceId) {
            sessionEstablished = true
            return
        }
        if let existing = establishTask {
            try await existing.value
            return
        }
        let task = Task { [bundleClient, establisher, tokenProvider, recipient] in
            let token = try await tokenProvider()
            let bundle = try await bundleClient.fetchBundle(sub: recipient.sub, accessToken: token)
            try establisher.establishOutbound(with: bundle, recipientSub: recipient.sub)
        }
        establishTask = task
        do {
            try await task.value
            sessionEstablished = true
        } catch {
            establishTask = nil  // allow a later send to retry establishment
            throw error
        }
    }

    /// Decrypts an inbound envelope, persists it, and inserts a non-mine bubble at its sentAt-sorted
    /// position.
    ///
    /// The delivery frame's server-stamped `fromSub` identifies the real sender. All of a user's
    /// inbound traffic arrives on one stream, so frames from anyone other than this thread's peer
    /// are ignored here rather than mis-decrypted against the wrong session (multi-thread routing
    /// is a later-slice concern). Decrypt failures surface on `self.error` and do NOT crash the
    /// inbound stream (surfaced-error discipline, guide §11).
    func receive(_ env: MessageEnvelope) async {
        // `fromSub` is nil only on frames that predate the delivery contract; fall back to the peer.
        let senderSub = env.fromSub ?? recipient.sub
        guard senderSub == recipient.sub else { return }
        guard let ciphertextData = Data(base64Encoded: env.ciphertext) else {
            error = "Invalid base64 ciphertext in envelope \(env.messageId)"
            return
        }
        do {
            let plaintext = try crypto.decrypt(
                ciphertextData, type: env.messageType, from: senderSub, deviceId: env.fromDeviceId)
            let content = try MessageContent.deserialize(plaintext)
            // Keyed on the locally-derived `conversationId`, not `env.conversationId`: the server is
            // untrusted with content, and `senderSub == recipient.sub` above already proves the local
            // value is correct for this thread — a wrong/empty server-supplied id here would write
            // unrecoverable (decrypt-once) plaintext into a conversation no screen can ever query.
            let bubble = ChatBubble(
                id: env.messageId, text: content.text, isMine: false, sentAt: content.sentAt, status: .sent)
            do {
                try repository.saveRetrying(StoredMessage(
                    messageId: env.messageId, conversationId: conversationId, peerSub: senderSub,
                    isMine: false, sentAt: content.sentAt, body: content.text, status: .sent))
            } catch {
                // The key is spent, so this plaintext is the only copy: show it rather than drop it.
                self.error = "A message couldn't be saved on this device and won't be kept after you leave."
            }
            insert(bubble)
        } catch {
            self.error = error.localizedDescription
        }
    }

    /// Inserts a bubble at its sentAt-sorted position (late arrivals slot in, not appended).
    ///
    /// Guards against a duplicate id: the same message can reach this thread twice — once via the
    /// live stream and once via the backlog pull (or `loadHistory`) — and the repository's `save`
    /// dedupes at the storage layer, but the in-memory bubble list needs its own guard so the two
    /// paths can never double-render.
    private func insert(_ bubble: ChatBubble) {
        guard !bubbles.contains(where: { $0.id == bubble.id }) else { return }
        let insertIndex = bubbles.firstIndex { $0.sentAt > bubble.sentAt } ?? bubbles.endIndex
        bubbles.insert(bubble, at: insertIndex)
    }

    private func setStatus(_ id: String, _ status: MessageStatus) {
        guard let bubbleIndex = bubbles.firstIndex(where: { $0.id == id }) else { return }
        bubbles[bubbleIndex].status = status
    }
}
