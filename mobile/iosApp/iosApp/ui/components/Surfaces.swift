import SwiftUI

/// The card: white, 20 pt corners, a hairline border, no shadow.
struct AppCard<Content: View>: View {
    @EnvironmentObject private var theme: AppTheme
    var padding: CGFloat = 18
    @ViewBuilder let content: Content

    var body: some View {
        VStack(alignment: .leading, spacing: 0) { content }
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding(padding)
            .background(theme.colors.surface)
            .clipShape(RoundedRectangle(cornerRadius: AppShape.card, style: .continuous))
            .overlay(
                RoundedRectangle(cornerRadius: AppShape.card, style: .continuous)
                    .stroke(theme.colors.line, lineWidth: 1)
            )
    }
}

/// The small uppercase label over a section.
struct SectionLabel: View {
    @EnvironmentObject private var theme: AppTheme
    let text: String

    var body: some View {
        Text(text.uppercased())
            .font(theme.type.label)
            .tracking(0.8)
            .foregroundStyle(theme.colors.inkFaint)
    }
}

enum ChipTone { case neutral, primary, record, amber, success }

/// A pill: a status, or a choice when it has an action.
struct AppChip: View {
    @EnvironmentObject private var theme: AppTheme
    let text: String
    var tone: ChipTone = .neutral
    var selected: Bool = false
    /// A small dot before the text, in the tone's colour.
    var dot: Bool = false
    var onTap: (() -> Void)?

    private var background: Color {
        if selected { return theme.colors.primary }
        switch tone {
        case .primary: return theme.colors.primaryTint
        case .record: return theme.colors.recordTint
        case .amber: return theme.colors.amberTint
        case .success: return theme.colors.successTint
        case .neutral: return onTap == nil ? theme.colors.surfaceAlt : theme.colors.surface
        }
    }

    private var foreground: Color {
        if selected { return theme.colors.onPrimary }
        switch tone {
        case .primary: return theme.colors.primary
        case .record: return theme.colors.record
        case .amber: return theme.colors.amber
        case .success: return theme.colors.success
        case .neutral: return theme.colors.inkMuted
        }
    }

    var body: some View {
        let label = HStack(spacing: 6) {
            if dot {
                Circle().fill(foreground).frame(width: 6, height: 6)
            }
            Text(text)
                .font(theme.type.caption)
                .fontWeight(.semibold)
                .foregroundStyle(foreground)
                .lineLimit(1)
        }
        .padding(.horizontal, onTap == nil ? 10 : 14)
        .padding(.vertical, onTap == nil ? 5 : 8)
        .background(background)
        .clipShape(Capsule())
        .overlay(
            Capsule().stroke(
                !selected && tone == .neutral && onTap != nil ? theme.colors.line : .clear,
                lineWidth: 1
            )
        )

        if let onTap {
            Button(action: onTap) { label }.buttonStyle(.plain)
        } else {
            label
        }
    }
}

/// A circle of initials.
struct Avatar: View {
    @EnvironmentObject private var theme: AppTheme
    let initials: String
    var size: CGFloat = 36

    var body: some View {
        Text(initials)
            .font(Manrope.font(.bold, size * 0.38))
            .foregroundStyle(theme.colors.primary)
            .frame(width: size, height: size)
            .background(theme.colors.primaryTint)
            .clipShape(Circle())
    }
}

/// One short line on a tinted block: an error, or a good outcome.
struct Notice<Action: View>: View {
    @EnvironmentObject private var theme: AppTheme
    let text: String
    var good: Bool = false
    @ViewBuilder var action: Action

    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            Text(text)
                .font(theme.type.smallStrong)
                .foregroundStyle(good ? theme.colors.success : theme.colors.record)
                .frame(maxWidth: .infinity, alignment: .leading)
            action
        }
        .padding(.horizontal, 14)
        .padding(.vertical, 11)
        .background(good ? theme.colors.successTint : theme.colors.recordTint)
        .clipShape(RoundedRectangle(cornerRadius: AppShape.field, style: .continuous))
    }
}

extension Notice where Action == EmptyView {
    init(text: String, good: Bool = false) {
        self.text = text
        self.good = good
        self.action = EmptyView()
    }
}

/// The row at the top of each tab: a title, and the profile button.
struct ScreenHeader<Leading: View>: View {
    @EnvironmentObject private var theme: AppTheme
    let initials: String
    let onProfile: () -> Void
    @ViewBuilder let leading: Leading

    var body: some View {
        HStack(spacing: 10) {
            leading
            Spacer(minLength: 8)
            Button(action: onProfile) {
                Avatar(initials: initials, size: 38)
            }
            .buttonStyle(.plain)
            .accessibilityLabel(theme.tr("profile.title"))
        }
        .padding(.top, 4)
    }
}
