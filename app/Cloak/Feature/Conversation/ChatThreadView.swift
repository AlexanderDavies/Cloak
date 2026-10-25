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
        .task {
            model.loadHistory()
            await model.start()
        }
    }

    private var messageList: some View {
        ScrollViewReader { proxy in
            ScrollView {
                LazyVStack(alignment: .leading, spacing: 8) {
                    ForEach(ChatTimeline.items(for: model.bubbles, formatter: MessageDateFormatter())) { item in
                        switch item {
                        case .dayHeader(let id, let label):
                            DayHeaderView(label: label).id(id)
                        case .message(let bubble, let time, let showsStatus):
                            ChatBubbleView(bubble: bubble, time: time, showsStatus: showsStatus)
                                .id(bubble.id)
                        }
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

// MARK: - DayHeaderView

private struct DayHeaderView: View {
    let label: String

    var body: some View {
        Text(label)
            .font(.caption)
            .foregroundStyle(.secondary)
            .frame(maxWidth: .infinity)
            .padding(.vertical, 4)
            .accessibilityAddTraits(.isHeader)
    }
}

// MARK: - ChatBubbleView

/// The time (and, where shown, the status glyph + label) sits inside the bubble on its own line
/// beneath the text, right-aligned.
private struct ChatBubbleView: View {
    let bubble: ChatBubble
    let time: String
    let showsStatus: Bool

    var body: some View {
        HStack {
            if bubble.isMine { Spacer(minLength: 48) }
            BubbleContentLayout(spacing: 2) {
                Text(bubble.text)
                metadata
            }
                .padding(.horizontal, 12)
                .padding(.vertical, 8)
                .background(bubble.isMine ? Color.purple : Color(.systemGray5))
                .foregroundStyle(bubble.isMine ? Color.white : Color.primary)
                .clipShape(RoundedRectangle(cornerRadius: 16))
                .accessibilityElement(children: .ignore)
                .accessibilityLabel(accessibilityText)
            if !bubble.isMine { Spacer(minLength: 48) }
        }
    }

    private var status: MessageStatus? { showsStatus ? bubble.status : nil }

    private var metadata: some View {
        HStack(spacing: 4) {
            Text(time)
                .foregroundStyle(bubble.isMine ? Color.white.opacity(0.75) : Color.secondary)
            if let status { MessageStatusView(status: status) }
        }
        .font(.caption2)
    }

    private var accessibilityText: String {
        [bubble.text, time, status?.displayLabel].compactMap { $0 }.joined(separator: ", ")
    }
}

// MARK: - BubbleContentLayout

/// Stacks the text above the metadata line: as wide as the wider of the two (text still wraps at the
/// offered width), text left-aligned, metadata right-aligned. A `VStack` can't do both without
/// either stretching every bubble to full width or stopping long text from wrapping.
private struct BubbleContentLayout: Layout {
    let spacing: CGFloat

    func sizeThatFits(proposal: ProposedViewSize, subviews: Subviews, cache: inout ()) -> CGSize {
        guard subviews.count == 2 else { return .zero }
        let text = subviews[0].sizeThatFits(ProposedViewSize(width: proposal.width, height: nil))
        let meta = subviews[1].sizeThatFits(.unspecified)
        return CGSize(width: max(text.width, meta.width), height: text.height + spacing + meta.height)
    }

    func placeSubviews(in bounds: CGRect, proposal: ProposedViewSize, subviews: Subviews, cache: inout ()) {
        guard subviews.count == 2 else { return }
        let text = subviews[0].sizeThatFits(ProposedViewSize(width: bounds.width, height: nil))
        subviews[0].place(at: bounds.origin, proposal: ProposedViewSize(width: bounds.width, height: text.height))
        subviews[1].place(at: CGPoint(x: bounds.maxX, y: bounds.maxY), anchor: .bottomTrailing, proposal: .unspecified)
    }
}

// MARK: - MessageStatusView

/// Glyph + text label per the design system's message-status table — colour is never the only
/// signal. Tuned for legibility on the purple outgoing bubble, where status always appears.
private struct MessageStatusView: View {
    let status: MessageStatus

    var body: some View {
        HStack(spacing: 2) {
            glyph
            Text(status.displayLabel)
        }
        .foregroundStyle(status == .failed ? Color.white : Color.white.opacity(0.75))
    }

    @ViewBuilder private var glyph: some View {
        switch status {
        case .sending:
            Image(systemName: "circle").foregroundStyle(Color.white.opacity(0.6))
        case .sent:
            Image(systemName: "circle").foregroundStyle(Color(red: 0.53, green: 0.94, blue: 0.67))
        case .failed:
            Image(systemName: "exclamationmark.circle.fill").symbolRenderingMode(.palette)
                .foregroundStyle(Color.white, Color.red)
        }
    }
}
