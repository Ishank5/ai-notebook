import SwiftUI
import shared

/// The small pieces the Meetings screens share: tones, a titled card, a list
/// that shows the first few, and dates.

enum MeetingTones {
    static func mood(_ mood: String) -> ChipTone {
        switch mood {
        case "very_positive", "positive": return .success
        case "mixed", "tense": return .amber
        case "negative": return .record
        default: return .neutral
        }
    }

    static func moodLabel(_ mood: String) -> String {
        switch mood {
        case "positive", "very_positive": return "Positive"
        case "negative": return "Negative"
        case "mixed": return "Mixed"
        case "tense": return "Tense"
        default: return "Neutral"
        }
    }

    static func level(_ level: String) -> ChipTone {
        switch level {
        case "high": return .record
        case "medium": return .amber
        default: return .neutral
        }
    }

    static func question(_ status: String) -> ChipTone {
        status == "answered" ? .success : .amber
    }

    static func meeting(_ key: String) -> ChipTone {
        switch key {
        case "ready": return .success
        case "failed": return .record
        case "uploading", "processing": return .primary
        default: return .neutral
        }
    }
}

/// A card with a title and an icon.
struct MeetingSection<Content: View>: View {
    @EnvironmentObject private var theme: AppTheme
    let title: String
    var icon: String?
    @ViewBuilder let content: Content

    var body: some View {
        AppCard {
            VStack(alignment: .leading, spacing: 12) {
                HStack(spacing: 8) {
                    if let icon {
                        Image(systemName: icon)
                            .font(.system(size: 13, weight: .semibold))
                            .foregroundStyle(theme.colors.primary)
                            .frame(width: 26, height: 26)
                            .background(theme.colors.primaryTint, in: RoundedRectangle(cornerRadius: 8, style: .continuous))
                    }
                    Text(title)
                        .font(theme.type.subheading)
                        .foregroundStyle(theme.colors.ink)
                }
                content
            }
        }
    }
}

/// The first few items, and "Show N more" for the rest.
struct CollapsibleList<Item, Row: View>: View {
    @EnvironmentObject private var theme: AppTheme
    let items: [Item]
    var limit = 4
    @ViewBuilder let row: (Item) -> Row

    @State private var open = false

    var body: some View {
        let shown = open ? items : Array(items.prefix(limit))
        VStack(alignment: .leading, spacing: 12) {
            ForEach(Array(shown.enumerated()), id: \.offset) { _, item in
                row(item)
            }
            if items.count > limit {
                Button {
                    withAnimation(.easeOut(duration: 0.2)) { open.toggle() }
                } label: {
                    Text(open
                         ? theme.tr("meetings.detail.showLess")
                         : theme.tr("meetings.detail.showMore", ["count": KotlinInt(int: Int32(items.count - limit))]))
                        .font(theme.type.smallStrong)
                        .foregroundStyle(theme.colors.primary)
                }
                .buttonStyle(.plain)
            }
        }
    }
}

/// A bullet line, with the moment it was said.
struct BulletLine: View {
    @EnvironmentObject private var theme: AppTheme
    let text: String
    var seconds: KotlinInt?

    var body: some View {
        HStack(alignment: .firstTextBaseline, spacing: 10) {
            Circle().fill(theme.colors.primary).frame(width: 5, height: 5).offset(y: -3)
            Text(text)
                .font(theme.type.body)
                .foregroundStyle(theme.colors.ink)
                .frame(maxWidth: .infinity, alignment: .leading)
            MomentLabel(seconds: seconds)
        }
    }
}

/// When in the meeting something was said, as "4:05".
struct MomentLabel: View {
    @EnvironmentObject private var theme: AppTheme
    let seconds: KotlinInt?

    var body: some View {
        if let seconds {
            let s = Int(seconds.int32Value)
            Text(s >= 3600
                 ? String(format: "%ld:%02ld:%02ld", s / 3600, (s % 3600) / 60, s % 60)
                 : String(format: "%ld:%02ld", s / 60, s % 60))
                .font(theme.type.caption)
                .monospacedDigit()
                .foregroundStyle(theme.colors.inkFaint)
        }
    }
}

enum MeetingDates {
    /// "25 Sep, 14:02".
    static func dateTime(_ ms: KotlinLong?) -> String? {
        guard let ms else { return nil }
        let formatter = DateFormatter()
        formatter.locale = Locale(identifier: "en_GB")
        formatter.dateFormat = "d MMM, HH:mm"
        return formatter.string(from: Date(timeIntervalSince1970: TimeInterval(ms.int64Value) / 1000))
    }

    /// "42 min", "1 h 5 min".
    static func length(_ ms: KotlinLong?) -> String? {
        guard let ms, ms.int64Value > 0 else { return nil }
        let minutes = max(1, Int(ms.int64Value / 60000))
        return minutes >= 60 ? "\(minutes / 60) h \(minutes % 60) min" : "\(minutes) min"
    }
}
