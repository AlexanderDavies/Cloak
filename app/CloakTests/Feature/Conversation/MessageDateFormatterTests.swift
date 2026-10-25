import Foundation
import Testing
@testable import Cloak

@Suite struct MessageDateFormatterTests {
    /// Sunday 25 Oct 2026, 14:30 UTC.
    private static let now = Date(timeIntervalSince1970: 1_792_938_600)

    private static var calendar: Calendar {
        var calendar = Calendar(identifier: .gregorian)
        calendar.timeZone = .gmt
        calendar.locale = Locale(identifier: "en_GB")
        return calendar
    }

    private let formatter = MessageDateFormatter(calendar: Self.calendar, now: { Self.now })

    /// UTC has no DST, so whole-day and whole-hour offsets are exact.
    private func daysAgo(_ days: Int, hour: Int = 9) -> Date {
        Self.calendar.startOfDay(for: Self.now)
            .addingTimeInterval(TimeInterval(-days * 86_400 + hour * 3_600 + 5 * 60))
    }

    @Test func dayLabelIsTodayForTheCurrentDay() {
        #expect(formatter.dayLabel(for: daysAgo(0, hour: 1)) == "Today")
    }

    @Test func dayLabelIsYesterdayForThePreviousDay() {
        #expect(formatter.dayLabel(for: daysAgo(1, hour: 23)) == "Yesterday")
    }

    @Test func dayLabelIsTheWeekdayWithinTheLastWeek() {
        #expect(formatter.dayLabel(for: daysAgo(2)) == "Friday")
        #expect(formatter.dayLabel(for: daysAgo(6)) == "Monday")
    }

    /// A week ago is the same weekday as today, so a bare weekday would be ambiguous.
    @Test func dayLabelIsADateFromAWeekAgo() {
        #expect(formatter.dayLabel(for: daysAgo(7)) == "18 Oct")
    }

    @Test func dayLabelIncludesTheYearForAPreviousYear() {
        #expect(formatter.dayLabel(for: daysAgo(400)) == "20 Sep 2025")
    }

    @Test func timeLabelUsesTheLocaleShortTime() {
        #expect(formatter.timeLabel(for: daysAgo(0, hour: 14)) == "14:05")
    }

    @Test func listTimestampIsTheTimeForToday() {
        #expect(formatter.listTimestamp(for: daysAgo(0, hour: 8)) == "08:05")
    }

    @Test func listTimestampFallsBackToTheDayLabelForEarlierDays() {
        #expect(formatter.listTimestamp(for: daysAgo(1)) == "Yesterday")
        #expect(formatter.listTimestamp(for: daysAgo(3)) == "Thursday")
        #expect(formatter.listTimestamp(for: daysAgo(10)) == "15 Oct")
    }
}
