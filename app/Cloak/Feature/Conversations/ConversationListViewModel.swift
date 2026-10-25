import Foundation

/// A conversation's preview row for the conversation list: the newest message in that conversation,
/// as recorded in local encrypted history (not a live network fetch).
struct ConversationSummary: Identifiable, Equatable, Sendable {
    let conversationId: String
    let peerSub: String
    let lastMessage: String
    let sentAt: Date

    var id: String { conversationId }
}

/// Drives the conversation list screen from local encrypted history: the spec's decrypt-once
/// invariant makes local history — not the server — authoritative for what the list shows, so the
/// list renders on relaunch with no network at all (guide §7).
///
/// Guide §3: `@Observable` VM — all behaviour here; the view is a dumb pass-through.
/// Guide §4: `@MainActor` for Swift 6 strict concurrency.
/// Guide §11: A repository read failure surfaces on `self.error`; it must never be presented as an
/// empty inbox, since that would misrepresent a read failure as "no conversations yet."
@MainActor @Observable final class ConversationListViewModel {
    /// One entry per conversation, newest-message-first (order comes from `latestPerConversation()`).
    private(set) var conversations: [ConversationSummary] = []
    private(set) var error: String?

    private let repository: any MessageRepository
    private let backlogSync: any BacklogSyncing
    /// Slightly longer than the server's backlog settle window (`cloak.messages.backlog-settle-lag`).
    private let settleDelay: Duration

    init(repository: any MessageRepository, backlogSync: any BacklogSyncing, settleDelay: Duration = .seconds(4)) {
        self.repository = repository
        self.backlogSync = backlogSync
        self.settleDelay = settleDelay
    }

    /// Reads the current list from local history. Call from the view's `.task`.
    func load() {
        do {
            conversations = try repository.latestPerConversation().map {
                ConversationSummary(
                    conversationId: $0.conversationId,
                    peerSub: $0.peerSub,
                    lastMessage: $0.body,
                    sentAt: $0.sentAt)
            }
            error = nil
        } catch {
            self.error = error.localizedDescription
        }
    }

    /// Pulls any messages missed while away, then reloads from local history — the backlog sync is
    /// what keeps local history caught up after the app was closed; local history stays the single
    /// source of truth for what the list renders.
    ///
    /// A sync that could not complete surfaces on `self.error`. A stuck backlog is otherwise
    /// invisible: the list still renders (from local history) and simply stops growing, so without
    /// this the user is shown a silently-truncated inbox (root CLAUDE.md — never silently drop).
    /// A repository read failure from `load()` takes precedence: it makes the whole list untrustworthy.
    func refresh() async {
        let outcome = await backlogSync.sync()
        load()
        if error == nil, let syncError = Self.message(for: outcome) {
            error = syncError
        }
    }

    /// The list's on-appear pull. The server withholds a message until it settles, so one sent just
    /// before the list appeared is collected by a single follow-up pull rather than waiting for the
    /// next launch. Live delivery only reaches devices that are connected when a message is routed.
    func refreshOnAppear() async {
        await refresh()
        try? await Task.sleep(for: settleDelay)
        guard !Task.isCancelled else { return }
        await refresh()
    }

    /// User-facing text for a sync outcome. Deliberately generic — a peer's `sub`, a message id, or
    /// an underlying error's description must never reach the screen (guide §0.6 / §12).
    private static func message(for outcome: BacklogSyncOutcome) -> String? {
        switch outcome {
        case .completed: nil
        case .unreachable: "Couldn't check for new messages. Pull down or reopen to retry."
        case .blocked: "Couldn't save new messages on this device. Reopen to retry."
        case .skippedUndecryptable(let count):
            count == 1
                ? "1 message couldn't be opened on this device and was skipped."
                : "\(count) messages couldn't be opened on this device and were skipped."
        }
    }
}
