import ActivityKit
import Foundation
import UIKit
import shared

/// the Live Activity on the lock screen and in the Dynamic
/// Island while a meeting records: the running clock, recording or paused, the
/// planned stop time, Stop, and Keep recording while the warning shows. The
/// widget that draws it is the RecorderWidget extension.
///
/// STARTED, UPDATED AND ENDED FROM HERE ONLY, by `RecorderController`, on
/// changes (a pause, the warning, Keep recording), not on every level update.
///
/// A FORCE-QUIT CANNOT END IT. iOS gives a killed app no last word, so every
/// update carries a stale date a few minutes ahead and the heartbeat pushes it
/// on; once the app is gone the widget says it is no longer recording, and the
/// next launch ends it. The recording itself is recovered as before: offered
/// for Continue, or sent as it is.
@MainActor
enum RecorderLiveActivity {

    private static var activity: Activity<RecorderActivityAttributes>?
    private static var lastUpdate = Date.distantPast
    /// follows the activity's state, so one dismissed by the person or ended by iOS (8 hours) is let
    /// go and a new one made the next time the app is in front while recording.
    private static var watcher: Task<Void, Never>?

    /// Stale this long after the last update, unless the heartbeat pushes it on.
    private static let staleAfter: TimeInterval = 3 * 60
    /// The heartbeat pushes the stale date on at most this often.
    private static let refreshEvery: TimeInterval = 60

    /// At launch nothing can be recording, so anything still showing is left over from a killed app.
    static func endLeftovers() {
        for leftover in Activity<RecorderActivityAttributes>.activities {
            Task { await leftover.end(nil, dismissalPolicy: .immediate) }
        }
    }

    /// Starts the activity, or brings it up to date with [status].
    static func show(_ status: RecorderController.Status) {
        guard status.phase == .recording || status.phase == .paused else { return }
        // Paused, the app may be suspended while it waits (by the person, as
        // the dead man's switch allows; or by a call, which may
        // suspend it until the call ends), so silence then is not a sign it
        // died, and the activity must not say the app was closed. It says
        // "Paused" (or "Paused for a call") until the recorder says otherwise.
        let paused = status.phase == .paused
        let content = ActivityContent(
            state: state(of: status),
            staleDate: paused ? nil : Date().addingTimeInterval(staleAfter)
        )
        lastUpdate = Date()
        // one dismissed or ended is not updated; a new one is asked for below.
        if let current = activity, current.activityState == .ended || current.activityState == .dismissed {
            activity = nil
        }
        if let activity {
            Task { await activity.update(content) }
            return
        }
        // Only while the app is in front, which is when Record or Continue is
        // pressed (or the app comes back to the front).
        guard ActivityAuthorizationInfo().areActivitiesEnabled,
              UIApplication.shared.applicationState == .active
        else { return }
        do {
            let made = try Activity.request(attributes: attributes(), content: content, pushType: nil)
            activity = made
            watch(made)
        } catch {
            NSLog("[Recorder] no Live Activity: %@", String(describing: error))
        }
    }

    /// lets go of an activity once it is dismissed or ended, so the next `show` makes a new one.
    private static func watch(_ watched: Activity<RecorderActivityAttributes>) {
        watcher?.cancel()
        watcher = Task { @MainActor in
            for await state in watched.activityStateUpdates {
                guard state == .dismissed || state == .ended else { continue }
                if activity?.id == watched.id {
                    NSLog("[Recorder] Live Activity %@; a new one comes when the app is in front",
                          state == .dismissed ? "dismissed" : "ended")
                    activity = nil
                }
                break
            }
        }
    }

    /// The heartbeat: keeps the stale date ahead while the app is alive.
    static func keepFresh(_ status: RecorderController.Status) {
        guard activity != nil, Date().timeIntervalSince(lastUpdate) >= refreshEvery else { return }
        show(status)
    }

    static func end() {
        watcher?.cancel()
        watcher = nil
        guard let ending = activity else { return }
        activity = nil
        Task { await ending.end(nil, dismissalPolicy: .immediate) }
    }

    private static func state(of status: RecorderController.Status) -> RecorderActivityAttributes.ContentState {
        let elapsed = TimeInterval(status.elapsedMs) / 1000
        let endAt = status.warning?.endAtMs ?? status.stopAtMs
        let now = Date()
        // shown to the minute; during the warning rounded up, so never before the real stop.
        let stopsAtMs = RecordingPlans.shared.shownStopAt(
            nowMs: Int64(now.timeIntervalSince1970 * 1000),
            leftMs: endAt - status.elapsedMs,
            warning: status.warning != nil
        )
        return RecorderActivityAttributes.ContentState(
            paused: status.phase == .paused,
            call: status.phase == .paused && status.pausedFor == GapReason.call,
            timerStart: now.addingTimeInterval(-elapsed),
            elapsedSeconds: Int(elapsed),
            stopsAt: Date(timeIntervalSince1970: TimeInterval(stopsAtMs) / 1000),
            warning: status.warning != nil,
            canKeep: status.warning?.extendToMs != nil
        )
    }

    private static func attributes() -> RecorderActivityAttributes {
        let language = LanguageStore.load()
        func say(_ key: String) -> String { Strings.shared.get(key: key, language: language, args: [:]) }
        let marker = "\u{1}"
        let stopsAt = Strings.shared.get(key: "recorder.stopsAt", language: language, args: ["time": marker])
            .components(separatedBy: marker)
        return RecorderActivityAttributes(
            title: say("recorder.title"),
            recordingText: say("recorder.recording"),
            pausedText: say("recorder.paused"),
            callText: say("recorder.live.call"),
            stopsAtPrefix: stopsAt.first ?? "",
            stopsAtSuffix: stopsAt.count > 1 ? stopsAt[1] : "",
            keepText: say("recorder.keep"),
            stopText: say("recorder.stop"),
            staleText: say("recorder.live.stale")
        )
    }
}
