import Foundation

/// One row of the chat thread: a centred day separator, or a message with its time and whether its
/// send status is shown beneath it.
enum ChatTimelineItem: Identifiable, Equatable {
    case dayHeader(id: String, label: String)
    case message(ChatBubble, time: String, showsStatus: Bool)

    var id: String {
        switch self {
        case .dayHeader(let id, _): id
        case .message(let bubble, _, _): bubble.id
        }
    }
}

/// Lays out an ordered thread into day-separated rows and decides which messages show a status.
enum ChatTimeline {
    /// A failed send always shows its status, so it can never pass for a sent one. Sending/Sent shows
    /// only on the latest outgoing message — even after the peer replies — so the sender can always
    /// tell whether their last message went out, without a label under every bubble.
    static func items(for bubbles: [ChatBubble], formatter: MessageDateFormatter) -> [ChatTimelineItem] {
        let latestOutgoingId = bubbles.last(where: \.isMine)?.id
        var items: [ChatTimelineItem] = []
        var currentDay: String?
        for bubble in bubbles {
            let day = formatter.dayLabel(for: bubble.sentAt)
            if day != currentDay {
                items.append(.dayHeader(id: "day-\(bubble.id)", label: day))
                currentDay = day
            }
            let showsStatus = bubble.status == .failed || bubble.id == latestOutgoingId
            items.append(.message(bubble, time: formatter.timeLabel(for: bubble.sentAt), showsStatus: showsStatus))
        }
        return items
    }
}

extension MessageStatus {
    /// The text label paired with each status glyph — colour is never the only signal (design system).
    var displayLabel: String {
        switch self {
        case .sending: "Sending…"
        case .sent: "Sent"
        case .failed: "Failed to send"
        }
    }
}
