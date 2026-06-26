import SwiftUI

/// Home screen: the conversation list. Empty state for Slice 1 (no conversations yet); real list
/// rendering arrives with the messaging slice. Pure view — excluded from coverage.
struct ConversationListView: View {
    var body: some View {
        VStack(spacing: 12) {
            Image(systemName: "lock.shield")
                .font(.largeTitle)
                .foregroundStyle(.purple)
            Text("No conversations yet")
                .font(.headline)
            Text("Start a secure chat in a later update.")
                .font(.subheadline)
                .foregroundStyle(.secondary)
        }
        .navigationTitle("Chats")
    }
}
