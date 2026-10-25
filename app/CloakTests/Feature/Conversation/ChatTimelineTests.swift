import Foundation
import Testing
@testable import Cloak

@Suite struct ChatTimelineTests {
    /// Sunday 25 Oct 2026, 14:30 UTC.
    private static let now = Date(timeIntervalSince1970: 1_792_938_600)

    private static var calendar: Calendar {
        var calendar = Calendar(identifier: .gregorian)
        calendar.timeZone = .gmt
        calendar.locale = Locale(identifier: "en_GB")
        return calendar
    }

    private let formatter = MessageDateFormatter(calendar: Self.calendar, now: { Self.now })

    private func bubble(
        _ id: String, mine: Bool, status: MessageStatus = .sent, daysAgo: Int = 0, hour: Int = 9
    ) -> ChatBubble {
        // UTC has no DST, so whole-day and whole-hour offsets are exact.
        let sentAt = Self.calendar.startOfDay(for: Self.now)
            .addingTimeInterval(TimeInterval(-daysAgo * 86_400 + hour * 3_600))
        return ChatBubble(id: id, text: id, isMine: mine, sentAt: sentAt, status: status)
    }

    private func headers(_ items: [ChatTimelineItem]) -> [String] {
        items.compactMap { if case .dayHeader(_, let label) = $0 { label } else { nil } }
    }

    private func messagesShowingStatus(_ items: [ChatTimelineItem]) -> [String] {
        items.compactMap {
            if case .message(let bubble, _, let showsStatus) = $0, showsStatus { bubble.id } else { nil }
        }
    }

    @Test func insertsADayHeaderBeforeTheFirstMessageOfEachDay() {
        let items = ChatTimeline.items(for: [
            bubble("a", mine: false, daysAgo: 1, hour: 9),
            bubble("b", mine: true, daysAgo: 1, hour: 10),
            bubble("c", mine: false, daysAgo: 0, hour: 9)
        ], formatter: formatter)

        #expect(headers(items) == ["Yesterday", "Today"])
        #expect(items.map(\.id).count == Set(items.map(\.id)).count)
        guard case .dayHeader = items[0], case .dayHeader = items[3] else {
            Issue.record("headers must precede the first message of each day")
            return
        }
    }

    @Test func carriesEachMessagesTimeLabel() {
        let items = ChatTimeline.items(for: [bubble("a", mine: false, hour: 14)], formatter: formatter)

        guard case .message(_, let time, _) = items.last else {
            Issue.record("expected a message item")
            return
        }
        #expect(time == "14:00")
    }

    /// The status follows the latest outgoing message even after the peer has replied, so the
    /// sender can always see whether their last message went out.
    @Test func showsSendingOrSentOnlyOnTheLatestOutgoingMessage() {
        let items = ChatTimeline.items(for: [
            bubble("mine-1", mine: true, status: .sent, hour: 9),
            bubble("mine-2", mine: true, status: .sending, hour: 10),
            bubble("theirs", mine: false, hour: 11)
        ], formatter: formatter)

        #expect(messagesShowingStatus(items) == ["mine-2"])
    }

    /// A failed message is never silently indistinguishable from a sent one, wherever it sits.
    @Test func alwaysShowsTheStatusOfAFailedMessage() {
        let items = ChatTimeline.items(for: [
            bubble("failed-early", mine: true, status: .failed, hour: 9),
            bubble("sent", mine: true, status: .sent, hour: 10),
            bubble("failed-latest", mine: true, status: .failed, hour: 11)
        ], formatter: formatter)

        #expect(messagesShowingStatus(items) == ["failed-early", "failed-latest"])
    }

    @Test func showsNoStatusOnIncomingMessages() {
        let items = ChatTimeline.items(for: [bubble("theirs", mine: false)], formatter: formatter)

        #expect(messagesShowingStatus(items).isEmpty)
    }

    @Test func emptyThreadHasNoItems() {
        #expect(ChatTimeline.items(for: [], formatter: formatter).isEmpty)
    }

    @Test func statusLabelsMatchTheDesignSystem() {
        #expect(MessageStatus.sending.displayLabel == "Sending…")
        #expect(MessageStatus.sent.displayLabel == "Sent")
        #expect(MessageStatus.failed.displayLabel == "Failed to send")
    }
}
