import Foundation
import shared

/// fast test mode, DEBUG BUILDS ONLY. In a release build
/// everything inside `#if DEBUG` is compiled out: no switch, and every
/// recording uses `RecorderLimits.STANDARD`.
///
/// Shrinks the planned length's times so the warning, Keep recording and the
/// stop by itself can be tried in minutes: 1 h 30 m becomes 2 min, 2 h 2 min
/// 40 s, 2 h 30 m 3 min 20 s, 3 h 4 min, Keep recording adds 2 min, and the
/// grace window is 30 s. Switched by a long press on the "AI Notebook" title,
/// as on Android. Read when a recording starts or is continued.
///
/// IN MEMORY ONLY, as on Android: off again after any restart of the app, so a
/// real meeting on this debug build cannot stop after four minutes.
enum RecorderTestMode {

    #if DEBUG
    static let available = true
    nonisolated(unsafe) private static var fast = false

    static var isOn: Bool { fast }

    /// Flips it and returns the new state.
    @discardableResult
    static func toggle() -> Bool {
        fast.toggle()
        return fast
    }

    static var limits: RecorderLimits {
        guard isOn else { return RecorderLimits.companion.STANDARD }
        return RecorderLimits(
            choicesMs: [120_000, 160_000, 200_000, 240_000].map { KotlinLong(value: $0) },
            defaultMs: 120_000,
            capMs: 240_000,
            extendByMs: 120_000,
            graceMs: 30_000
        )
    }
    #else
    static let available = false
    static var isOn: Bool { false }
    @discardableResult
    static func toggle() -> Bool { false }
    static var limits: RecorderLimits { RecorderLimits.companion.STANDARD }
    #endif
}
