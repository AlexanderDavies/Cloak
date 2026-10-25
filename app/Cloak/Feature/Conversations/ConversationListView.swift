import SwiftUI

/// Home screen: the conversation list, populated from local encrypted history. Routes the user to
/// start a new conversation (→ start-conversation → chat thread) or into an existing one. Navigation
/// is owned here so the three screens form a single self-contained unit inside a `NavigationStack`.
/// Pure UI wiring — excluded from coverage; behaviour lives in `ConversationListViewModel`.
struct ConversationListView: View {
    // Messaging dependencies injected from the composition root (guide §5).
    let lookup: any UserLookupClient
    let bundleClient: any PreKeyBundleClient
    let establisher: any SessionEstablishing
    let crypto: any MessageCrypting
    let repository: any MessageRepository
    let mySub: String
    let wsURL: URL
    /// Supplies a fresh access token for each network call. `@Sendable` so it can be forwarded
    /// into async view-model token-provider closures (guide §4 / §10).
    let tokenProvider: @Sendable () async throws -> String

    @State private var model: ConversationListViewModel
    @State private var path: [ConversationRoute] = []

    init(
        lookup: any UserLookupClient,
        bundleClient: any PreKeyBundleClient,
        establisher: any SessionEstablishing,
        crypto: any MessageCrypting,
        repository: any MessageRepository,
        mySub: String,
        wsURL: URL,
        backlogSync: any BacklogSyncing,
        tokenProvider: @escaping @Sendable () async throws -> String
    ) {
        self.lookup = lookup
        self.bundleClient = bundleClient
        self.establisher = establisher
        self.crypto = crypto
        self.repository = repository
        self.mySub = mySub
        self.wsURL = wsURL
        self.tokenProvider = tokenProvider
        _model = State(initialValue: ConversationListViewModel(repository: repository, backlogSync: backlogSync))
    }

    var body: some View {
        NavigationStack(path: $path) {
            conversationList
                .navigationDestination(for: ConversationRoute.self) { route in
                    switch route {
                    case .startConversation:
                        StartConversationView(
                            model: StartConversationViewModel(
                                lookup: lookup,
                                tokenProvider: tokenProvider),
                            onReady: { recipient in
                                // Replace the start-conversation screen with the thread so the user
                                // lands directly at the thread when pressing back.
                                path = [.thread(recipient)]
                            })
                    case .thread(let recipient):
                        ChatThreadView(
                            model: ChatThreadViewModel(
                                recipient: recipient,
                                myDeviceId: 1,
                                repository: repository,
                                mySub: mySub,
                                bundleClient: bundleClient,
                                establisher: establisher,
                                crypto: crypto,
                                transport: WebSocketMessageTransport(url: wsURL),
                                tokenProvider: tokenProvider))
                    }
                }
        }
    }

    // MARK: - List content

    private var conversationList: some View {
        VStack(spacing: 0) {
            if model.conversations.isEmpty {
                emptyState
            } else {
                List(model.conversations) { conversation in
                    NavigationLink(
                        value: ConversationRoute.thread(ResolvedRecipient(sub: conversation.peerSub, deviceId: 1))
                    ) {
                        ConversationRowView(conversation: conversation)
                    }
                }
            }
            if let errorText = model.error {
                Text(errorText)
                    .font(.caption)
                    .foregroundStyle(.red)
                    .padding(.horizontal)
                    .padding(.vertical, 4)
            }
        }
        // Attached to the list content, not to the NavigationStack: the stack never disappears, so
        // a modifier there runs once per app launch and the list would still read "No conversations
        // yet" after coming back from a thread. The content does re-appear on pop.
        .onAppear { model.load() }
        .task { await model.refreshOnAppear() }
        .navigationTitle("Chats")
        .toolbar {
            ToolbarItem(placement: .primaryAction) {
                Button {
                    path.append(.startConversation)
                } label: {
                    Image(systemName: "square.and.pencil")
                }
                .accessibilityLabel("New conversation")
            }
        }
    }

    private var emptyState: some View {
        VStack(spacing: 12) {
            Image(systemName: "lock.shield")
                .font(.largeTitle)
                .foregroundStyle(.purple)
            Text("No conversations yet")
                .font(.headline)
            Text("Tap + to start a secure conversation.")
                .font(.subheadline)
                .foregroundStyle(.secondary)
        }
    }
}

// MARK: - ConversationRowView

/// A single row: peer identity, last-message time, and preview. There is no contact-lookup mechanism in
/// this MVP (out of scope), so the row shows the peer's raw `sub` rather than a resolved handle.
private struct ConversationRowView: View {
    let conversation: ConversationSummary

    var body: some View {
        VStack(alignment: .leading, spacing: 4) {
            HStack(alignment: .firstTextBaseline) {
                Text(conversation.peerSub)
                    .font(.headline)
                    .lineLimit(1)
                Spacer()
                Text(MessageDateFormatter().listTimestamp(for: conversation.sentAt))
                    .font(.footnote)
                    .foregroundStyle(.secondary)
            }
            Text(conversation.lastMessage)
                .font(.subheadline)
                .foregroundStyle(.secondary)
                .lineLimit(1)
        }
    }
}

// MARK: - Navigation Route

/// Typed navigation destinations for the conversation feature. `Hashable` is required by
/// `NavigationStack(path:)` and `navigationDestination(for:)`.
private enum ConversationRoute: Hashable {
    case startConversation
    case thread(ResolvedRecipient)
}
