import SwiftUI

/// Home screen: the conversation list. Routes the user to start a new conversation (→
/// start-conversation → chat thread). Navigation is owned here so the three screens form a single
/// self-contained unit inside a `NavigationStack`. Pure UI wiring — excluded from coverage.
struct ConversationListView: View {
    // Messaging dependencies injected from the composition root (guide §5).
    let lookup: any UserLookupClient
    let bundleClient: any PreKeyBundleClient
    let establisher: any SessionEstablishing
    let crypto: any MessageCrypting
    let wsURL: URL
    /// Supplies a fresh access token for each network call. `@Sendable` so it can be forwarded
    /// into async view-model token-provider closures (guide §4 / §10).
    let tokenProvider: @Sendable () async throws -> String

    @State private var path: [ConversationRoute] = []

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
                                bundleClient: bundleClient,
                                establisher: establisher,
                                crypto: crypto,
                                transport: WebSocketMessageTransport(url: wsURL),
                                tokenProvider: tokenProvider))
                    }
                }
        }
    }

    // MARK: - List content (empty state for Slice 2; real list rendering arrives with Slice 3)

    private var conversationList: some View {
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
}

// MARK: - Navigation Route

/// Typed navigation destinations for the conversation feature. `Hashable` is required by
/// `NavigationStack(path:)` and `navigationDestination(for:)`.
private enum ConversationRoute: Hashable {
    case startConversation
    case thread(ResolvedRecipient)
}
