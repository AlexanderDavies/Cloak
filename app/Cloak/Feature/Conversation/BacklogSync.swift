import Foundation
import OSLog

/// What one backlog pull achieved, so the caller can tell the user when history is not up to date.
///
/// Deliberately carries no message id, `sub`, or error text from the failure: a sync outcome is
/// shown in the UI, and nothing that identifies a peer or a message may travel there (root
/// CLAUDE.md principle 6 / guide §0.6).
enum BacklogSyncOutcome: Equatable, Sendable {
    /// Every available message was consumed (or there was nothing to consume).
    case completed
    /// The backlog could not be fetched at all — no token, or the request failed. Transient.
    case unreachable
    /// A local failure: the dedupe check could not be read (retried on the next sync), or a decrypted
    /// message could not be stored even after retrying (its key is spent, so the next sync reports it
    /// as skipped). The high-water mark holds until then, which is why this must reach the user.
    case blocked
    /// Every available message was consumed, but `count` of them could never be opened and were
    /// skipped. Undecryptable is permanent — the ratchet spends a message key on the first attempt,
    /// and a message sealed to a since-replaced identity has no key at all — so holding the mark on
    /// one would wedge every later message forever. The user is told, since they are gone for good.
    case skippedUndecryptable(count: Int)
}

protocol BacklogSyncing: Sendable {
    @discardableResult
    func sync() async -> BacklogSyncOutcome
}

/// Pulls not-yet-decrypted ciphertext addressed to this device, decrypts-once, and persists it to
/// local history (spec: decrypt-once → local plaintext is authoritative). Advances the high-water
/// mark only past messages this device has actually consumed.
struct BacklogSync: BacklogSyncing {
    private static let log = Logger(subsystem: "com.cloak", category: "backlog")

    let client: MessageHistoryClient
    let crypto: MessageCrypting
    let repository: MessageRepository
    let cursor: BacklogCursorStore
    /// This device's own `sub`, used to derive the local conversation key. See `consume`.
    let mySub: String
    let tokenProvider: @Sendable () async throws -> String

    @discardableResult
    func sync() async -> BacklogSyncOutcome {
        var skipped = 0
        do {
            let token = try await tokenProvider()
            var hasMore = true
            while hasMore {
                let before = cursor.highWaterMark()
                let page = try await client.fetchHistory(cursor: before, limit: 50, accessToken: token)
                guard let pageSkipped = consume(page.messages) else { return .blocked }
                skipped += pageSkipped
                // A cursor write that silently fails (or an empty hasMore:true page) would otherwise
                // re-fetch the same page forever, since `advance` is fail-safe and never throws.
                guard cursor.highWaterMark() != before else { break }
                hasMore = page.hasMore
            }
        } catch {
            Self.log.error("Backlog sync aborted before any page could be fetched")
            return .unreachable
        }
        return skipped == 0 ? .completed : .skippedUndecryptable(count: skipped)
    }

    /// Decrypts, persists, and advances past each message in order. Returns how many messages were
    /// skipped as permanently undecryptable, or `nil` on a transient failure — which stops the page
    /// so the mark never moves past a message this device could still consume on a later sync.
    private func consume(_ messages: [BacklogMessage]) -> Int? {
        var skipped = 0
        for message in messages {
            switch storedState(of: message.messageId) {
            case .stored:
                // Already decrypted — live delivery over /ws stores messages without touching the
                // cursor, so the backlog re-serves them on the next launch. Decrypting again is
                // impossible (the ratchet consumed the message key), so skip straight to advancing;
                // without this the very first live-delivered message wedges the cursor forever.
                cursor.advance(to: message.cursor)
                continue
            case .unknown:
                // The existence check itself failed. Skipping would risk a destructive re-decrypt of
                // a message we may already hold; stopping only delays it to the next sync.
                Self.log.error("Backlog dedupe check failed; will retry next sync")
                return nil
            case .absent:
                break
            }
            guard let content = open(message) else {
                // Live delivery may have won the race for this message: it decrypted and stored it
                // first, spending the key, so our decrypt failed. That message was delivered, not lost.
                if storedState(of: message.messageId) != .stored {
                    Self.log.error("Skipping undecryptable backlog message")
                    skipped += 1
                }
                cursor.advance(to: message.cursor)
                continue
            }
            do {
                // Keyed on the locally-derived conversation id, not the server-supplied
                // `message.conversationId`: the server is untrusted with content, and a wrong or
                // empty value would file unrecoverable (decrypt-once) plaintext under a conversation
                // no screen can query. Mirrors `ChatThreadViewModel.receive`.
                try repository.saveRetrying(StoredMessage(
                    messageId: message.messageId, conversationId: ConversationKey.of([mySub, message.fromSub]),
                    peerSub: message.fromSub, isMine: false, sentAt: content.sentAt,
                    body: content.text, status: .sent))
                cursor.advance(to: message.cursor)
            } catch {
                // The key is spent, so the next sync cannot decrypt this message again: it will be
                // counted as undecryptable and the user told. Holding the mark here only defers that.
                Self.log.error("Backlog persist failed after decrypt; message cannot be recovered")
                return nil
            }
        }
        return skipped
    }

    /// The decrypted content, or `nil` if the message can never be opened on this device.
    private func open(_ message: BacklogMessage) -> MessageContent? {
        guard let ciphertext = Data(base64Encoded: message.ciphertext) else { return nil }
        return try? MessageContent.deserialize(crypto.decrypt(
            ciphertext, type: message.messageType, from: message.fromSub, deviceId: message.fromDeviceId))
    }

    private enum StoredState { case stored, absent, unknown }

    private func storedState(of messageId: String) -> StoredState {
        do {
            return try repository.contains(messageId: messageId) ? .stored : .absent
        } catch {
            return .unknown
        }
    }
}
