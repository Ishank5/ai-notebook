import ActivityKit
import AppIntents
import Foundation

// the recorder's Live Activity, as the app and the widget
// extension both know it. This one file is compiled into both targets (the
// exception set on the iosApp folder in project.pbxproj adds it to
// RecorderWidget), so it must not import the shared Kotlin module, which the
// extension does not link.

/// What the lock screen and the Dynamic Island show while a meeting records.
struct RecorderActivityAttributes: ActivityAttributes {

    struct ContentState: Codable, Hashable {
        /// Paused, by the person or a call.
        var paused: Bool
        /// paused by a call, which resumes by itself when it ends.
        var call: Bool
        /// Now minus the audio so far: where a running timer counts from.
        var timerStart: Date
        /// The audio so far, shown still while paused.
        var elapsedSeconds: Int
        /// When it stops if it carries on: the planned end, or the end of the warning.
        var stopsAt: Date
        /// The warning before the stop is showing.
        var warning: Bool
        /// Keep recording is offered: the warning is showing and the plan is below the cap.
        var canKeep: Bool
    }

    // The words, from the app's shared strings, so the extension carries no copy of them.
    var title: String
    var recordingText: String
    var pausedText: String
    /// "Paused for a call".
    var callText: String
    /// "Stops at " and what follows the time, around a time the widget formats itself.
    var stopsAtPrefix: String
    var stopsAtSuffix: String
    var keepText: String
    var stopText: String
    /// Shown once the app has not updated it for a while: it was closed, so nothing is recording.
    var staleText: String
}

/// where the Live Activity's Stop goes. Not an intent: opening
/// the app from the lock screen asks for the unlock first, and the app asks
/// "Stop recording?" before it stops, so a tap in a pocket cannot end a meeting.
enum RecorderLinks {
    static let stop = URL(string: "ainotetaker://recorder/stop")!

    static func isStop(_ url: URL) -> Bool {
        url.scheme == stop.scheme && url.host == stop.host && url.path == stop.path
    }
}

/// Stop, from the Live Activity. Runs in the app's process, which the system
/// wakes for it if it has to (a `LiveActivityIntent` always runs there).
/// `perform` returns only once the recording has ended, so the
/// app is not suspended halfway through closing it.
struct StopRecordingIntent: LiveActivityIntent {
    static let title: LocalizedStringResource = "Stop recording"
    static let isDiscoverable = false

    init() {}

    func perform() async throws -> some IntentResult {
        await RecorderIntents.run(.stop)
        return .result()
    }
}

/// Keep recording, from the Live Activity while the warning shows.
struct KeepRecordingIntent: LiveActivityIntent {
    static let title: LocalizedStringResource = "Keep recording"
    static let isDiscoverable = false

    init() {}

    func perform() async throws -> some IntentResult {
        await RecorderIntents.run(.keep)
        return .result()
    }
}

/// Where the intents hand over to the recorder.
///
/// A HOOK, NOT A CALL, because this file is also compiled into the extension,
/// which has no recorder. The app sets [handler] at launch; in the extension it
/// stays nil, and Live Activity intents never run there anyway.
///
/// the handler is awaited: it returns once the recorder has
/// done what was asked (or a short timeout passed).
enum RecorderIntents {
    enum Action: Sendable { case stop, keep }

    @MainActor static var handler: (@MainActor (Action) async -> Void)?

    static func run(_ action: Action) async {
        let handler = await MainActor.run { Self.handler }
        await handler?(action)
    }
}
