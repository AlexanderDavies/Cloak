import SwiftUI

/// Start-conversation screen: the user types an exact handle (email or username), taps "Find",
/// and the app resolves it to a `ResolvedRecipient`. On success the caller navigates to the thread.
///
/// Pure view — all behaviour lives in ``StartConversationViewModel`` (verified by units);
/// excluded from coverage (guide §14.5).
struct StartConversationView: View {
    @State var model: StartConversationViewModel
    let onReady: (ResolvedRecipient) -> Void

    var body: some View {
        VStack(spacing: 20) {
            Text("New Conversation")
                .font(.title2.bold())

            TextField("Handle (email or username)", text: $model.handle)
                .textFieldStyle(.roundedBorder)
                .autocorrectionDisabled()
                .textInputAutocapitalization(.never)
                .padding(.horizontal)

            statusView

            Button("Find") { Task { await model.resolve() } }
                .buttonStyle(.borderedProminent)
                .tint(.purple)
                .disabled(model.handle.isEmpty || model.state == .lookingUp)
        }
        .padding()
        .navigationTitle("New Conversation")
    }

    @ViewBuilder
    private var statusView: some View {
        switch model.state {
        case .idle:
            EmptyView()
        case .lookingUp:
            ProgressView()
                .tint(.purple)
        case .ready(let recipient):
            Color.clear.onAppear { onReady(recipient) }
        case .notFound:
            Text("No user found with that handle.")
                .foregroundStyle(.secondary)
                .font(.footnote)
        case .failed(let message):
            Text(message)
                .foregroundStyle(.red)
                .font(.footnote)
        }
    }
}
