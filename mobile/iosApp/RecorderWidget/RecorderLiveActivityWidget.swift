import ActivityKit
import AppIntents
import SwiftUI
import WidgetKit

/// The recorder's Live Activity, on the lock screen and in the Dynamic Island:
/// the running clock (or still, while paused), the planned stop time, Stop,
/// and Keep recording while the warning shows. The app starts, updates and
/// ends it (`RecorderLiveActivity` in the app); Keep recording runs
/// `KeepRecordingIntent` in the app's process, Stop opens the app.
struct RecorderLiveActivityWidget: Widget {

    var body: some WidgetConfiguration {
        ActivityConfiguration(for: RecorderActivityAttributes.self) { context in
            LockScreenView(context: context)
                .activityBackgroundTint(Palette.surface)
                .activitySystemActionForegroundColor(Palette.ink)
        } dynamicIsland: { context in
            DynamicIsland {
                DynamicIslandExpandedRegion(.leading) {
                    HStack(spacing: 8) {
                        LogoMark(size: 26)
                        StatusLabel(context: context, style: .dark)
                    }
                    .padding(.leading, 4)
                }
                DynamicIslandExpandedRegion(.trailing) {
                    ClockText(context: context)
                        .font(.system(size: 24, weight: .bold).monospacedDigit())
                        .foregroundStyle(.white)
                        .multilineTextAlignment(.trailing)
                        .padding(.trailing, 4)
                }
                DynamicIslandExpandedRegion(.bottom) {
                    VStack(spacing: 10) {
                        StopsAtText(context: context, style: .dark)
                        Buttons(context: context, style: .dark)
                    }
                    .padding(.top, 4)
                }
            } compactLeading: {
                LiveDot(paused: context.state.paused)
                    .padding(.leading, 2)
            } compactTrailing: {
                ClockText(context: context)
                    .font(.caption.monospacedDigit().weight(.semibold))
                    .foregroundStyle(.white)
                    .frame(maxWidth: 52)
            } minimal: {
                LiveDot(paused: context.state.paused)
            }
            .keylineTint(Palette.record)
        }
    }
}

/// The app's colour tokens, repeated here: the extension does not link the app.
private enum Palette {
    static let surface = Color(.sRGB, red: 1, green: 1, blue: 1, opacity: 1)
    static let ink = Color(.sRGB, red: 0x0F / 255, green: 0x1B / 255, blue: 0x19 / 255, opacity: 1)
    static let inkMuted = Color(.sRGB, red: 0x5A / 255, green: 0x6B / 255, blue: 0x67 / 255, opacity: 1)
    static let line = Color(.sRGB, red: 0xE1 / 255, green: 0xE7 / 255, blue: 0xE4 / 255, opacity: 1)
    static let primary = Color(.sRGB, red: 0x0D / 255, green: 0x7A / 255, blue: 0x68 / 255, opacity: 1)
    static let record = Color(.sRGB, red: 0xE5 / 255, green: 0x48 / 255, blue: 0x4D / 255, opacity: 1)
    static let amber = Color(.sRGB, red: 0xB7 / 255, green: 0x79 / 255, blue: 0x1F / 255, opacity: 1)
    /// Amber lifted for the black Dynamic Island.
    static let amberOnDark = Color(.sRGB, red: 0xE8 / 255, green: 0xA8 / 255, blue: 0x4A / 255, opacity: 1)
}

/// Light on the lock screen, dark in the Dynamic Island.
private enum Style {
    case light, dark

    var text: Color { self == .light ? Palette.ink : .white }
    var muted: Color { self == .light ? Palette.inkMuted : .white.opacity(0.7) }
    var amber: Color { self == .light ? Palette.amber : Palette.amberOnDark }
}

private typealias Context = ActivityViewContext<RecorderActivityAttributes>

private struct LockScreenView: View {
    let context: Context

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            HStack(alignment: .center, spacing: 12) {
                LogoMark(size: 36)
                StatusLabel(context: context, style: .light)
                Spacer(minLength: 8)
                ClockText(context: context)
                    .font(.system(size: 32, weight: .bold).monospacedDigit())
                    .foregroundStyle(Palette.ink)
                    .multilineTextAlignment(.trailing)
            }
            StopsAtText(context: context, style: .light)
            Buttons(context: context, style: .light)
        }
        .padding(16)
    }
}

/// The live dot: coral while recording, amber while paused.
private struct LiveDot: View {
    let paused: Bool

    var body: some View {
        Circle()
            .fill(paused ? Palette.amberOnDark : Palette.record)
            .frame(width: 10, height: 10)
    }
}

/// "AI Notebook" over "Recording" or "Paused", with the dot.
private struct StatusLabel: View {
    let context: Context
    let style: Style

    var body: some View {
        VStack(alignment: .leading, spacing: 2) {
            Text(context.attributes.title)
                .font(.subheadline.weight(.bold))
                .foregroundStyle(style.text)
                .lineLimit(1)
            HStack(spacing: 6) {
                Circle()
                    .fill(context.state.paused ? style.amber : Palette.record)
                    .frame(width: 7, height: 7)
                Text(context.isStale
                     ? context.attributes.staleText
                     : (context.state.call ? context.attributes.callText
                        : context.state.paused ? context.attributes.pausedText : context.attributes.recordingText))
                    .font(.caption.weight(.medium))
                    .foregroundStyle(style.muted)
                    .lineLimit(1)
            }
        }
    }
}

/// The audio so far: running while recording, still while paused or stale.
private struct ClockText: View {
    let context: Context

    var body: some View {
        if context.state.paused || context.isStale {
            Text(Self.clock(context.state.elapsedSeconds))
        } else {
            Text(timerInterval: context.state.timerStart...Date.distantFuture, countsDown: false)
        }
    }

    static func clock(_ seconds: Int) -> String {
        let h = seconds / 3600, m = (seconds % 3600) / 60, s = seconds % 60
        return h > 0 ? String(format: "%d:%02d:%02d", h, m, s) : String(format: "%d:%02d", m, s)
    }
}

/// "Stops at 4:42 PM", hidden while paused (a pause moves it) and once stale.
private struct StopsAtText: View {
    let context: Context
    let style: Style

    var body: some View {
        if !context.state.paused && !context.isStale {
            (Text(context.attributes.stopsAtPrefix)
                + Text(context.state.stopsAt, style: .time)
                + Text(context.attributes.stopsAtSuffix))
                .font(.footnote.weight(context.state.warning ? .semibold : .medium))
                .foregroundStyle(context.state.warning ? style.amber : style.muted)
                .frame(maxWidth: .infinity, alignment: .leading)
        }
    }
}

/// Keep recording while the warning shows, and Stop. Keep recording is one
/// tap; Stop opens the app (which asks for the unlock on the lock screen) to a
/// "Stop recording?" question.
private struct Buttons: View {
    let context: Context
    let style: Style

    var body: some View {
        if !context.isStale {
            HStack(spacing: 8) {
                if context.state.canKeep {
                    Button(intent: KeepRecordingIntent()) {
                        pill(context.attributes.keepText, fill: Palette.primary)
                    }
                    .buttonStyle(.plain)
                }
                Link(destination: RecorderLinks.stop) {
                    pill(context.attributes.stopText, fill: Palette.record)
                }
            }
        }
    }

    private func pill(_ text: String, fill: Color) -> some View {
        Text(text)
            .font(.footnote.weight(.bold))
            .foregroundStyle(.white)
            .frame(maxWidth: .infinity)
            .padding(.vertical, 9)
            .background(fill, in: Capsule())
    }
}
