import SwiftUI

enum ButtonTone { case primary, outline, record, ghost }

/// The pill button: 52 pt tall, full width unless asked otherwise.
struct AppButton: View {
    @EnvironmentObject private var theme: AppTheme

    let title: String
    var tone: ButtonTone = .primary
    var enabled: Bool = true
    var busy: Bool = false
    var fillWidth: Bool = true
    var systemImage: String?
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            HStack(spacing: 8) {
                if busy {
                    ProgressView()
                        .progressViewStyle(.circular)
                        .tint(foreground)
                        .controlSize(.small)
                } else if let systemImage {
                    Image(systemName: systemImage)
                        .font(.system(size: 14, weight: .bold))
                }
                Text(title)
                    .font(theme.type.button)
                    .multilineTextAlignment(.center)
            }
            .foregroundStyle(foreground)
            .frame(maxWidth: fillWidth ? .infinity : nil, minHeight: AppShape.button)
            .padding(.horizontal, 22)
            .contentShape(Capsule())
        }
        .buttonStyle(PillStyle(fill: fill, pressedFill: pressedFill, border: tone == .outline ? theme.colors.line : .clear))
        .opacity(enabled && !busy ? 1 : 0.5)
        .disabled(!enabled || busy)
    }

    private var fill: Color {
        switch tone {
        case .primary: return theme.colors.primary
        case .record: return theme.colors.record
        case .outline: return theme.colors.surface
        case .ghost: return .clear
        }
    }

    private var pressedFill: Color {
        switch tone {
        case .primary: return theme.colors.primaryPressed
        case .record: return theme.colors.record.opacity(0.85)
        case .outline, .ghost: return theme.colors.surfaceAlt
        }
    }

    private var foreground: Color {
        switch tone {
        case .primary, .record: return theme.colors.onPrimary
        case .outline: return theme.colors.ink
        case .ghost: return theme.colors.primary
        }
    }
}

private struct PillStyle: ButtonStyle {
    let fill: Color
    let pressedFill: Color
    let border: Color

    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .background(configuration.isPressed ? pressedFill : fill, in: Capsule())
            .overlay(Capsule().stroke(border, lineWidth: 1))
            .animation(.easeOut(duration: 0.12), value: configuration.isPressed)
    }
}

/// A labelled text box on the quiet fill, with a teal edge while typing.
struct AppField: View {
    @EnvironmentObject private var theme: AppTheme
    @FocusState private var focused: Bool

    let label: String
    @Binding var value: String
    var placeholder: String = ""
    var multiline: Bool = false

    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            Text(label)
                .font(theme.type.smallStrong)
                .foregroundStyle(theme.colors.inkMuted)
            Group {
                if multiline {
                    TextField("", text: $value, prompt: prompt, axis: .vertical)
                        .lineLimit(2...5)
                        .padding(.vertical, 14)
                } else {
                    TextField("", text: $value, prompt: prompt)
                }
            }
            .focused($focused)
            .font(theme.type.bodyMedium)
            .foregroundStyle(theme.colors.ink)
            .tint(theme.colors.primary)
            .textInputAutocapitalization(.sentences)
            .fieldBox(focused: focused)
        }
    }

    private var prompt: Text {
        Text(placeholder).foregroundColor(theme.colors.inkFaint)
    }
}

extension View {
    /// The box every input sits in: the quiet fill, 14 pt corners, a teal edge when focused.
    func fieldBox(focused: Bool) -> some View {
        modifier(FieldBox(focused: focused))
    }
}

private struct FieldBox: ViewModifier {
    @EnvironmentObject private var theme: AppTheme
    let focused: Bool

    func body(content: Content) -> some View {
        content
            .padding(.horizontal, 16)
            .frame(maxWidth: .infinity, minHeight: AppShape.button, alignment: .leading)
            .background(focused ? theme.colors.surface : theme.colors.surfaceAlt)
            .clipShape(RoundedRectangle(cornerRadius: AppShape.field, style: .continuous))
            .overlay(
                RoundedRectangle(cornerRadius: AppShape.field, style: .continuous)
                    .stroke(focused ? theme.colors.primary : .clear, lineWidth: 1.5)
            )
            .animation(.easeOut(duration: 0.15), value: focused)
    }
}
