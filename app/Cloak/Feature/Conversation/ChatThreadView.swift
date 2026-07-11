import SwiftUI

/// Chat thread screen: a scrollable list of message bubbles and a compose bar.
///
/// Pure view — all behaviour lives in ``ChatThreadViewModel`` (verified by units);
/// excluded from coverage (guide §14.5).
struct ChatThreadView: View {
    @State var model: ChatThreadViewModel
    @State private var draft = ""

    var body: some View {
        VStack(spacing: 0) {
            messageList
            if let errorText = model.error {
                Text(errorText)
                    .font(.caption)
                    .foregroundStyle(.red)
                    .padding(.horizontal)
                    .padding(.vertical, 4)
            }
            composeBar
        }
        .navigationTitle("Chat")
        .navigationBarTitleDisplayMode(.inline)
        .task { await model.start() }
    }

    private var messageList: some View {
        ScrollViewReader { proxy in
            ScrollView {
                LazyVStack(alignment: .leading, spacing: 8) {
                    ForEach(model.bubbles) { bubble in
                        ChatBubbleView(bubble: bubble)
                            .id(bubble.id)
                    }
                }
                .padding()
            }
            .onChange(of: model.bubbles.count) { _, _ in
                if let last = model.bubbles.last {
                    withAnimation { proxy.scrollTo(last.id, anchor: .bottom) }
                }
            }
        }
    }

    private var composeBar: some View {
        HStack(spacing: 8) {
            TextField("Message", text: $draft)
                .textFieldStyle(.roundedBorder)
                .onSubmit { sendDraft() }
            Button(action: sendDraft) {
                Image(systemName: "arrow.up.circle.fill")
                    .font(.title2)
                    .foregroundStyle(.purple)
            }
            .disabled(draft.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)
        }
        .padding()
        .background(.regularMaterial)
    }

    private func sendDraft() {
        let text = draft.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !text.isEmpty else { return }
        draft = ""
        Task { await model.send(text) }
    }
}

// MARK: - ChatBubbleView

private struct ChatBubbleView: View {
    let bubble: ChatBubble

    var body: some View {
        HStack {
            if bubble.isMine { Spacer(minLength: 48) }
            Text(bubble.text)
                .padding(.horizontal, 12)
                .padding(.vertical, 8)
                .background(bubble.isMine ? Color.purple : Color(.systemGray5))
                .foregroundStyle(bubble.isMine ? Color.white : Color.primary)
                .clipShape(RoundedRectangle(cornerRadius: 16))
            if !bubble.isMine { Spacer(minLength: 48) }
        }
    }
}
