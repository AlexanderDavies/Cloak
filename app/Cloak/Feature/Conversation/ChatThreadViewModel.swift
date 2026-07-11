import Foundation

/// A single message bubble rendered in the chat thread.
struct ChatBubble: Identifiable, Equatable, Sendable {
    let id: String
    let text: String
    let isMine: Bool
}

/// Drives the chat thread screen: compose, encrypt, send, receive, and decrypt messages.
///
/// Ephemeral: bubbles are held in memory only — persisted history arrives in Slice 3.
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
    /// Ordered list of message bubbles in this thread. In-memory only (history = Slice 3).
    private(set) var bubbles: [ChatBubble] = []
    /// Non-nil when a transport, encrypt, or decrypt operation fails.
    private(set) var error: String?

    private let recipient: ResolvedRecipient
    private let myDeviceId: UInt32
    private let bundleClient: PreKeyBundleClient
    private let establisher: SessionEstablishing
    private let crypto: MessageCrypting
    private let transport: MessageTransport
    private let tokenProvider: @Sendable () async throws -> String

    /// Whether a PQXDH session is already established with `recipient`.
    /// Excluded from observation — this is an internal implementation flag, not view state.
    @ObservationIgnored private var sessionEstablished = false

    /// In-flight session establishment, memoized so concurrent `send`s share one establishment
    /// (rather than each fetching a bundle + re-running X3DH, consuming extra one-time prekeys).
    @ObservationIgnored private var establishTask: Task<Void, Error>?

    init(
        recipient: ResolvedRecipient,
        myDeviceId: UInt32,
        bundleClient: PreKeyBundleClient,
        establisher: SessionEstablishing,
        crypto: MessageCrypting,
        transport: MessageTransport,
        tokenProvider: @escaping @Sendable () async throws -> String
    ) {
        self.recipient = recipient
        self.myDeviceId = myDeviceId
        self.bundleClient = bundleClient
        self.establisher = establisher
        self.crypto = crypto
        self.transport = transport
        self.tokenProvider = tokenProvider
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
    /// Any failure surfaces on `self.error` (never silently dropped — root CLAUDE.md §key-principles).
    func send(_ text: String) async {
        do {
            try await ensureSession()
            let payload = try crypto.encrypt(
                Data(text.utf8), to: recipient.sub, deviceId: recipient.deviceId)
            let messageId = UUID().uuidString
            let envelope = MessageEnvelope(
                messageId: messageId,
                toSub: recipient.sub,
                toDeviceId: recipient.deviceId,
                fromDeviceId: myDeviceId,
                messageType: payload.type,
                ciphertext: payload.ciphertext.base64EncodedString())
            try await transport.send(envelope)
            bubbles.append(ChatBubble(id: messageId, text: text, isMine: true))
        } catch {
            self.error = error.localizedDescription
        }
    }

    /// Ensures a PQXDH session with `recipient` exists, establishing one only if needed.
    ///
    /// - Reuses a session already in the encrypted store (e.g. a re-entered thread), so we don't
    ///   re-run X3DH — which would burn another one-time prekey and reset the Double-Ratchet.
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

    /// Decrypts an inbound envelope and appends a non-mine bubble.
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
            let plaintextData = try crypto.decrypt(
                ciphertextData,
                type: env.messageType,
                from: senderSub,
                deviceId: env.fromDeviceId)
            guard let text = String(bytes: plaintextData, encoding: .utf8) else {
                error = "Invalid UTF-8 plaintext in envelope \(env.messageId)"
                return
            }
            bubbles.append(ChatBubble(id: env.messageId, text: text, isMine: false))
        } catch {
            self.error = error.localizedDescription
        }
    }
}
