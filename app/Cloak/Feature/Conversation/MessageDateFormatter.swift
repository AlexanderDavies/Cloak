import Foundation

/// Formats message dates for the chat thread's day separators, per-message times, and the
/// conversation list. The calendar (with its locale and time zone) and the clock are injected so
/// "Today"/"Yesterday" are deterministic under test.
struct MessageDateFormatter {
    private let calendar: Calendar
    private let now: () -> Date

    init(calendar: Calendar = .autoupdatingCurrent, now: @escaping () -> Date = Date.init) {
        self.calendar = calendar
        self.now = now
    }

    /// "Today", "Yesterday", the weekday within the last six days, else a date — with the year only
    /// when it differs from the current one. Seven days back would repeat today's weekday, so it
    /// becomes a date.
    func dayLabel(for date: Date) -> String {
        let daysAgo = calendar.dateComponents(
            [.day], from: calendar.startOfDay(for: date), to: calendar.startOfDay(for: now())).day ?? 0
        switch daysAgo {
        case 0: return "Today"
        case 1: return "Yesterday"
        case 2...6: return format(date, template: "EEEE")
        default:
            let sameYear = calendar.isDate(date, equalTo: now(), toGranularity: .year)
            return format(date, template: sameYear ? "d MMM" : "d MMM yyyy")
        }
    }

    func timeLabel(for date: Date) -> String {
        let formatter = makeFormatter()
        formatter.timeStyle = .short
        return formatter.string(from: date)
    }

    /// Today's messages show their time; older ones show their day label.
    func listTimestamp(for date: Date) -> String {
        calendar.isDate(date, inSameDayAs: now()) ? timeLabel(for: date) : dayLabel(for: date)
    }

    private func format(_ date: Date, template: String) -> String {
        let formatter = makeFormatter()
        formatter.setLocalizedDateFormatFromTemplate(template)
        return formatter.string(from: date)
    }

    private func makeFormatter() -> DateFormatter {
        let formatter = DateFormatter()
        formatter.calendar = calendar
        formatter.locale = calendar.locale ?? .autoupdatingCurrent
        formatter.timeZone = calendar.timeZone
        return formatter
    }
}
