import AVFoundation
import AudioToolbox
import BackgroundTasks
import FirebaseAuth
import Foundation
import UIKit
import UserNotifications
import shared

/// The recorder for the whole app: what the microphone is doing now, and the
/// one place that starts, stops and uploads.
///
/// A SINGLETON FOR THE SAME REASON AS ANDROID'S SERVICE. A recording outlives
/// every screen — the person locks the phone for forty minutes — and the screen
/// that comes back must show the clock that kept running.
@MainActor
final class RecorderController: ObservableObject {

    static let shared = RecorderController()

    enum Phase { case idle, starting, recording, paused, stopping }

    struct Status {
        var phase: Phase = .idle
        var recordingId: String?
        var pausedFor: GapReason?
        var elapsedMs: Int64 = 0
        var level: Float = 0
        /// the planned end in audio ms, after any Keep recording.
        var stopAtMs: Int64 = RecorderConfig.shared.DEFAULT_PLAN_MS
        /// the 3-hour cap (shorter in the debug test mode), for the last warning's words.
        var capMs: Int64 = RecorderConfig.shared.MAX_DURATION_MS
        /// the warning before it stops, while it shows (replaces TAS's minutes left).
        var warning: RecorderWarning?
        var errorKey: String?
        /// A recording that has just stopped with audio in it, waiting to be named.
        var justStopped: String?

        var isLive: Bool { phase != .idle }
    }

    @Published private(set) var status = Status()
    /// the Live Activity's Stop opened the app; the question "Stop recording?" is showing.
    @Published var confirmingStop = false

    private var engine: RecorderEngine?
    /// the planned length's numbers for the recording running.
    private var limits = RecorderLimits.companion.STANDARD
    private var syncing = false
    private var syncAgain = false
    /// callers waiting for the recording to end, or for Keep recording to be taken (see `stopAndWait`).
    private var finishWaiters: [OneShot] = []
    private var extendWaiters: [OneShot] = []

    private init() {}

    // MARK: - Recording

    /// `planMs` is the length picked; nil for the default. Recordings carry no company.
    /// [ownerName] is kept with the recording, so another account on this phone can be told whose
    /// meetings wait for a sign-in.
    func start(planMs: Int64? = nil, ownerName: String? = nil) async {
        await begin(continuing: nil, planMs: planMs, ownerName: ownerName)
    }

    func continueRecording(_ recordingId: String) async {
        await begin(continuing: recordingId, planMs: nil, ownerName: nil)
    }

    func pause() { engine?.requestPause() }
    func resume() { engine?.requestResume() }

    /// Stop with nothing recording can only come from a Live
    /// Activity or a notification the app outlived; there is nothing to stop,
    /// so whatever is still showing goes.
    func stop() {
        guard let engine else {
            RecorderLiveActivity.endLeftovers()
            RecorderAlerts.clearWarning()
            return
        }
        engine.requestStop()
    }

    /// Keep recording; the engine ignores it outside the warning.
    func extend() { engine?.requestExtend() }

    /*
     * STOP AND KEEP FROM OUTSIDE THE APP, WAITED FOR. A button on the Live Activity or a notification
     * wakes the app for as long as its handler runs; TAS only asked the engine and returned, so iOS could suspend the
     * app before the last chunk was closed and the recording marked stopped (or before Keep recording was saved).
     * These wait for the engine's answer, under background time, for at most a little while.
     */
    func stopAndWait() async {
        guard engine != nil else {
            stop()
            return
        }
        let grant = BackgroundGrant(name: "recorder-stop")
        await wait(into: \.finishWaiters, seconds: 25) { self.stop() }
        grant.end()
    }

    func extendAndWait() async {
        guard engine != nil else { return }
        let grant = BackgroundGrant(name: "recorder-keep")
        await wait(into: \.extendWaiters, seconds: 5) { self.extend() }
        grant.end()
    }

    private func wait(
        into list: ReferenceWritableKeyPath<RecorderController, [OneShot]>,
        seconds: Double,
        then action: () -> Void
    ) async {
        await withCheckedContinuation { (continuation: CheckedContinuation<Void, Never>) in
            let shot = OneShot(continuation)
            self[keyPath: list].append(shot)
            Task { @MainActor in
                try? await Task.sleep(nanoseconds: UInt64(seconds * 1_000_000_000))
                shot.fire()
            }
            action()
        }
    }

    private func release(_ list: ReferenceWritableKeyPath<RecorderController, [OneShot]>) {
        let waiting = self[keyPath: list]
        self[keyPath: list] = []
        waiting.forEach { $0.fire() }
    }

    /// ainotetaker://recorder/stop, from the Live Activity: ask before stopping.
    func open(_ url: URL) {
        guard RecorderLinks.isStop(url) else { return }
        if engine != nil {
            confirmingStop = true
        } else {
            stop()
        }
    }

    /// the app is in front again (the person tapped "Recording paused. Tap to resume.", or just
    /// opened it). A pause the person did not make is tried again at once, from the foreground, where it works.
    func cameToForeground() {
        if status.phase == .paused, let reason = status.pausedFor, reason != GapReason.user {
            engine?.requestResume()
        }
        // a Live Activity dismissed, or ended by iOS after 8 hours, is made again (only possible with
        // the app in front).
        if engine != nil { RecorderLiveActivity.show(status) }
        // coming back to the app is when signal most often comes back too. Done here, for the whole
        // app, rather than only when the recorder screen happened to be the one showing.
        Task { await syncNow() }
    }

    /// The error or the just-stopped recording has been dealt with on screen.
    func acknowledge() {
        status.errorKey = nil
        status.justStopped = nil
    }

    private func begin(
        continuing recordingId: String?, planMs: Int64?, ownerName: String?
    ) async {
        guard engine == nil, status.phase == .idle else { return }
        status = Status(phase: .starting)
        // time to get recording if the phone is locked straight after the tap. Given back by the
        // engine once capture has started, or below if it never gets that far.
        let grant = BackgroundGrant(name: "recorder-start")

        guard await AVAudioApplication.requestRecordPermission() else {
            grant.end()
            startFailed(RecorderErrors.shared.MIC_PERMISSION)
            return
        }
        // THE SESSION FIRST, while the app is surely in front, then the database. TAS made it active
        // only in the engine, after the queue's work, and a phone locked at once in a pocket was by then in the
        // background, where iOS refuses to start recording: the meeting was never recorded, and nobody was told.
        do {
            try RecorderEngine.activateSession()
        } catch {
            NSLog("[Recorder] audio session refused at start: %@", String(describing: error))
            grant.end()
            startFailed(RecorderErrors.shared.MIC_BUSY)
            return
        }
        guard let owner = Auth.auth().currentUser?.uid else {
            grant.end()
            startFailed(RecorderErrors.shared.SIGNED_OUT)
            return
        }

        do {
            let store = try await RecordingSource.shared.store(factory: RecordingDriverFactory())
            let files = IOSRecordingFiles.shared
            let runtime = RecordingSource.shared.runtime
            let now = RecorderEngine.now()
            // read once per recording; the debug test mode can shorten them.
            let limits = RecorderTestMode.limits
            self.limits = limits
            let start: RecorderStart
            if let recordingId {
                start = try await ContinueRecordingUseCase(store: store, files: files, runtime: runtime)
                    .execute(
                        recordingId: recordingId, ownerUid: owner, now: now, limits: limits, gapReason: GapReason.killed,
                        minFreeBytes: RecorderConfig.shared.MIN_FREE_BYTES
                    )
            } else {
                start = try await StartRecordingUseCase(store: store, files: files, runtime: runtime)
                    .execute(
                        ownerUid: owner,
                        now: now,
                        company: nil,
                        planMs: planMs.map { KotlinLong(value: $0) },
                        limits: limits,
                        ownerName: ownerName
                    )
            }
            status.stopAtMs = start.stopAtMs
            status.capMs = limits.capMs
            let engine = RecorderEngine(store: store, files: files, runtime: runtime, limits: limits)
            engine.delegate = self
            self.engine = engine
            engine.start(start, grant: grant)
            NSLog("[Recorder] %@ started (prior %lld ms)", start.recordingId, start.priorMs)
        } catch {
            grant.end()
            startFailed(Self.key(of: error, fallback: RecorderErrors.shared.START_FAILED))
            return
        }
        // asked after recording has started, not before: a permission prompt must not hold the
        // meeting up.
        await RecorderAlerts.askPermission()
    }

    /// the start failed. Shown on screen; and, when the app is no longer in front (Record pressed and
    /// the phone locked), as a time-sensitive notification, so nobody walks into a meeting thinking it records.
    private func startFailed(_ key: String) {
        status = Status(errorKey: key)
        try? AVAudioSession.sharedInstance().setActive(false, options: .notifyOthersOnDeactivation)
        if UIApplication.shared.applicationState != .active {
            RecorderAlerts.post(key, vibrate: true)
        }
    }

    // MARK: - Uploading

    /// Recover, tidy, upload — now, and with time to finish if the app goes
    /// to the background halfway through.
    ///
    /// ONE AT A TIME, NEVER LOST: a call that arrives during a run makes the
    /// run go round once more rather than start a second one.
    func syncNow() async {
        if syncing {
            syncAgain = true
            return
        }
        syncing = true
        let finishInBackground = BackgroundTime(name: "recorder-sync")
        var outcome: SyncStatus?
        repeat {
            syncAgain = false
            outcome = await Self.runSync()
        } while syncAgain
        syncing = false
        finishInBackground.end()

        if outcome == SyncStatus.retry { RecorderBackground.schedule() }
    }

    /// One pass over the queue. Also what the background tasks run.
    nonisolated static func runSync() async -> SyncStatus? {
        do {
            let store = try await RecordingSource.shared.store(factory: RecordingDriverFactory())
            let files = IOSRecordingFiles.shared
            let runtime = RecordingSource.shared.runtime
            let now = RecorderEngine.now()
            // recovery counts on the monotonic clock, so a changed time cannot cut its hour short.
            _ = try await RecoverRecordingsUseCase(store: store, files: files, runtime: runtime)
                .execute(uptimeMs: RecorderEngine.uptimeMs())
            _ = try await PurgeUploadedRecordingsUseCase(store: store, files: files).execute(now: now)

            guard let owner = Auth.auth().currentUser?.uid else { return nil }
            let outcome = try await SyncRecordingsUseCase(
                store: store,
                uploads: IOSRecordingUploadRepository(),
                transfer: IOSChunkTransfer(),
                files: files,
                runtime: runtime
            ).execute(ownerUid: owner, device: RecorderDevices.current, now: now)
            NSLog("[Recorder] sync: %@, %d chunks, %d confirmed",
                  outcome.status.name, outcome.uploaded, outcome.finalized)
            return outcome.status
        } catch {
            NSLog("[Recorder] sync failed: %@", String(describing: error))
            return SyncStatus.retry
        }
    }

    private static func key(of error: Error, fallback: String) -> String {
        let message = (error as NSError).localizedDescription
        return message.hasPrefix("recorder.err.") ? message : fallback
    }
}

// MARK: - The engine's reports

extension RecorderController: RecorderEngineDelegate {

    /*
     * EVERY REPORT CHECKS THE ENGINE IS STILL THERE. Reports hop to the main
     * actor as separate tasks, and one from just before Stop can land just
     * after it; without the check a late one would re-open a finished
     * recording's status, or re-arm the "recording was cut off" alert for a
     * meeting that ended normally.
     */
    nonisolated func engine(phase: Phase, pausedFor: GapReason?, elapsedMs: Int64, recordingId: String) {
        Task { @MainActor in
            guard engine != nil else { return }
            status.phase = phase
            status.pausedFor = pausedFor
            status.elapsedMs = elapsedMs
            status.recordingId = recordingId
            switch phase {
            case .recording: RecorderAlerts.armDeadMan()
            // a call or an interruption may suspend the app; then the switch says it is paused.
            case .paused where pausedFor != GapReason.user: RecorderAlerts.armDeadMan(paused: true)
            // Paused by the person: the app may be suspended while it waits,
            // and that is not a crash worth waking anybody for.
            case .paused where pausedFor == GapReason.user: RecorderAlerts.disarmDeadMan()
            default: break
            }
            // the lock screen follows every change of phase.
            RecorderLiveActivity.show(status)
        }
    }

    nonisolated func engine(level: Float, elapsedMs: Int64) {
        Task { @MainActor in
            guard engine != nil else { return }
            status.level = level
            status.elapsedMs = elapsedMs
        }
    }

    nonisolated func engineClosedChunk() {
        Task { @MainActor in await syncNow() }
    }

    nonisolated func engineBeat() {
        Task { @MainActor in
            guard engine != nil else { return }
            // paused by a call or an interruption, the switch says so instead, a minute after the app
            // was last alive: "Recording paused. Tap to resume."
            if status.phase == .recording {
                RecorderAlerts.armDeadMan()
            } else if status.phase == .paused && status.pausedFor != GapReason.user {
                RecorderAlerts.armDeadMan(paused: true)
            }
            RecorderLiveActivity.keepFresh(status)
        }
    }

    /// the planned length is reached, or the cap is near. A beep
    /// through the recording's own session and a vibration (heard and felt with
    /// the phone locked), a notification with Keep recording and Stop now, the
    /// Live Activity's buttons, and a dialog if the app is open.
    nonisolated func engine(warning: RecorderWarning, elapsedMs: Int64) {
        Task { @MainActor in
            guard engine != nil else { return }
            status.warning = warning
            status.elapsedMs = elapsedMs
            RecorderAlerts.warn(warning, elapsedMs: elapsedMs, capMs: status.capMs)
            RecorderLiveActivity.show(status)
        }
    }

    /// paused for a minute by a call or an interruption, and resuming keeps failing.
    nonisolated func engineResumeFailing() {
        Task { @MainActor in
            guard engine != nil else { return }
            RecorderAlerts.postPaused()
        }
    }

    /// recording again; the "paused" notice is no longer true.
    nonisolated func engineResumed() {
        Task { @MainActor in RecorderAlerts.clearPaused() }
    }

    /// half a minute of exact zeros (still recording): felt and shown, once. Cleared on sound again.
    nonisolated func engine(micSilent: Bool) {
        Task { @MainActor in
            if micSilent {
                guard engine != nil else { return }
                RecorderAlerts.postSilent()
            } else {
                RecorderAlerts.clearSilent()
            }
        }
    }

    /// Keep recording moved the planned end; the warning is answered.
    nonisolated func engine(planExtended stopAtMs: Int64, elapsedMs: Int64) {
        Task { @MainActor in
            guard engine != nil else { return }
            status.stopAtMs = stopAtMs
            status.elapsedMs = elapsedMs
            status.warning = nil
            RecorderAlerts.clearWarning()
            RecorderLiveActivity.show(status)
            release(\.extendWaiters)
        }
    }

    /// nothing to extend; whoever waits for Keep recording is let go.
    nonisolated func engineExtendIgnored() {
        Task { @MainActor in release(\.extendWaiters) }
    }

    nonisolated func engine(
        finished recordingId: String, reason: EndReason, hadAudio: Bool, errorKey: String?, unchosen: Bool
    ) {
        Task { @MainActor in
            engine = nil
            RecorderAlerts.disarmDeadMan()
            RecorderAlerts.clearPaused()
            RecorderAlerts.clearSilent()
            // the lock screen and any unanswered warning go with the recording.
            RecorderLiveActivity.end()
            RecorderAlerts.clearWarning()
            let cap = status.capMs
            // "Could not start" is the wrong sentence for a recording that ran
            // for forty minutes; the alert below says it is safe.
            let shown = hadAudio && errorKey == RecorderErrors.shared.START_FAILED ? nil : errorKey
            // one nobody chose to end waits on the screen for Continue, not for its details.
            status = Status(errorKey: shown, justStopped: hadAudio && !unchosen ? recordingId : nil)
            if reason == EndReason.limit, errorKey == RecorderErrors.shared.CHUNK_LIMIT {
                // the chunk ceiling says so; only the cap says "3 hours".
                RecorderAlerts.post("recorder.notif.chunkLimit", vibrate: true)
            } else if reason == EndReason.limit {
                RecorderAlerts.post(
                    "recorder.notif.limit",
                    args: ["cap": RecordingPlans.shared.words(ms: cap)],
                    vibrate: true
                )
            } else if reason == EndReason.planned {
                // nobody answered the warning; it stopped, and says it is uploading.
                RecorderAlerts.post("recorder.notif.planned", vibrate: true)
            } else if unchosen, hadAudio {
                // "Recording stopped at HH:MM. Tap to continue.", felt and shown.
                RecorderAlerts.post(
                    "recorder.notif.cutOff",
                    args: ["time": Date().formatted(date: .omitted, time: .shortened)],
                    vibrate: true
                )
            } else if hadAudio, errorKey != nil {
                RecorderAlerts.post("recorder.notif.stoppedError", vibrate: true)
            } else if let errorKey, UIApplication.shared.applicationState != .active {
                // it never recorded (the microphone would not start): said even with the phone locked.
                RecorderAlerts.post(errorKey, vibrate: true)
            }
            // a Stop from the Live Activity or a notification waited for this.
            release(\.finishWaiters)
            release(\.extendWaiters)
            if hadAudio {
                // If the app is suspended before this finishes, the background
                // tasks pick it up.
                RecorderBackground.schedule()
                await syncNow()
            }
        }
    }
}

/// a continuation resumed exactly once, by the answer or by the timeout, whichever comes first.
@MainActor
private final class OneShot {
    private var continuation: CheckedContinuation<Void, Never>?

    init(_ continuation: CheckedContinuation<Void, Never>) {
        self.continuation = continuation
    }

    func fire() {
        continuation?.resume()
        continuation = nil
    }
}

/// Extra time to finish an upload after the app leaves the screen.
@MainActor
private final class BackgroundTime {
    private var identifier: UIBackgroundTaskIdentifier = .invalid

    init(name: String) {
        identifier = UIApplication.shared.beginBackgroundTask(withName: name) { [weak self] in
            // iOS calls this on the main thread, as the time runs out.
            MainActor.assumeIsolated { self?.end() }
        }
    }

    func end() {
        guard identifier != .invalid else { return }
        UIApplication.shared.endBackgroundTask(identifier)
        identifier = .invalid
    }
}

/// The recorder's local notifications.
///
/// THE DEAD MAN'S SWITCH IS THE ONE THAT MATTERS. iOS gives an app no way to
/// run anything when it is killed, so a notification is kept scheduled a
/// little into the future and pushed back on every heartbeat. While the
/// recorder is alive it never fires; the moment the app dies — a crash, a
/// force-quit, a flat battery on the way back — it goes off, and the person
/// learns the meeting stopped recording while they can still do something
/// about it.
@MainActor
enum RecorderAlerts {

    private static let deadManId = "recorder-deadman"
    private static let alertId = "recorder-alert"
    private static let deadManDelay: TimeInterval = 45
    /// "Recording paused. Tap to resume.", posted now or a minute after the last sign of life.
    private static let pausedId = "recorder-paused"
    private static let pausedDelay: TimeInterval = 60
    /// "Microphone is silent. Is another app using it?"
    private static let silentId = "recorder-silent"

    // the warning before the stop, its two kinds and their buttons.
    private static let warningId = "recorder-warning"
    static let warningCategory = "recorder.warning"
    static let finalWarningCategory = "recorder.warning.final"
    static let keepAction = "recorder.keep"
    static let stopAction = "recorder.stop"

    /// the warning's buttons. Must be registered before one is posted; done at launch.
    static func registerCategories() {
        let language = LanguageStore.load()
        func say(_ key: String) -> String { Strings.shared.get(key: key, language: language, args: [:]) }
        // Neither opens the app: the recording is running and only needs telling.
        let keep = UNNotificationAction(identifier: keepAction, title: say("recorder.keep"), options: [])
        // Stop asks for the unlock first on the lock screen; Keep recording stays one tap.
        let stop = UNNotificationAction(
            identifier: stopAction, title: say("recorder.stopNow"), options: [.destructive, .authenticationRequired]
        )
        UNUserNotificationCenter.current().setNotificationCategories([
            UNNotificationCategory(identifier: warningCategory, actions: [keep, stop], intentIdentifiers: []),
            UNNotificationCategory(identifier: finalWarningCategory, actions: [stop], intentIdentifiers: []),
        ])
    }

    static func askPermission() async {
        let center = UNUserNotificationCenter.current()
        let settings = await center.notificationSettings()
        guard settings.authorizationStatus == .notDetermined else { return }
        _ = try? await center.requestAuthorization(options: [.alert, .sound])
    }

    /// [paused]: the recording is paused by a call or an interruption, which may suspend the app, so
    /// the switch says "Recording paused. Tap to resume." a minute after the last sign of life.
    static func armDeadMan(paused: Bool = false) {
        let language = LanguageStore.load()
        let content = UNMutableNotificationContent()
        if paused {
            content.title = Strings.shared.get(key: "recorder.title", language: language, args: [:])
            content.body = Strings.shared.get(key: "recorder.notif.pausedResume", language: language, args: [:])
        } else {
            content.title = Strings.shared.get(key: "recorder.interrupted.title", language: language, args: [:])
            content.body = Strings.shared.get(key: "recorder.notif.interrupted", language: language, args: [:])
        }
        content.sound = .default
        // through Focus and Do Not Disturb; a meeting that stopped recording is exactly that urgent.
        content.interruptionLevel = .timeSensitive
        content.userInfo = ["kind": "recorder"]
        let trigger = UNTimeIntervalNotificationTrigger(
            timeInterval: paused ? pausedDelay : deadManDelay, repeats: false
        )
        // Same identifier: each heartbeat replaces the last, pushing it back.
        UNUserNotificationCenter.current().add(
            UNNotificationRequest(identifier: deadManId, content: content, trigger: trigger)
        )
    }

    static func disarmDeadMan() {
        UNUserNotificationCenter.current().removePendingNotificationRequests(withIdentifiers: [deadManId])
    }

    /// paused by a call or an interruption for a minute and still not recording. Tapping it brings
    /// the app to the front, which tries again (`RecorderController.cameToForeground`).
    static func postPaused() {
        let language = LanguageStore.load()
        let content = UNMutableNotificationContent()
        content.title = Strings.shared.get(key: "recorder.title", language: language, args: [:])
        content.body = Strings.shared.get(key: "recorder.notif.pausedResume", language: language, args: [:])
        content.sound = .default
        content.interruptionLevel = .timeSensitive
        content.userInfo = ["kind": "recorder"]
        UNUserNotificationCenter.current().add(UNNotificationRequest(identifier: pausedId, content: content, trigger: nil))
        vibrate()
    }

    static func clearPaused() {
        let center = UNUserNotificationCenter.current()
        center.removeDeliveredNotifications(withIdentifiers: [pausedId])
    }

    /// "Microphone is silent. Is another app using it?": half a minute of exact zeros while recording.
    static func postSilent() {
        let language = LanguageStore.load()
        let content = UNMutableNotificationContent()
        content.title = Strings.shared.get(key: "recorder.title", language: language, args: [:])
        content.body = Strings.shared.get(key: "recorder.notif.micSilent", language: language, args: [:])
        content.sound = .default
        content.interruptionLevel = .timeSensitive
        content.userInfo = ["kind": "recorder"]
        UNUserNotificationCenter.current().add(UNNotificationRequest(identifier: silentId, content: content, trigger: nil))
        vibrate()
    }

    static func clearSilent() {
        UNUserNotificationCenter.current().removeDeliveredNotifications(withIdentifiers: [silentId])
    }

    /// the warning: a beep and a vibration now, and a
    /// time-sensitive notification with its sound, Keep recording (while the
    /// plan is below the cap) and Stop now.
    static func warn(_ warning: RecorderWarning, elapsedMs: Int64, capMs: Int64) {
        let language = LanguageStore.load()
        let left = RecordingPlans.shared.wordsLeft(ms: warning.endAtMs - elapsedMs)
        let text: String
        if let to = warning.extendToMs {
            text = Strings.shared.get(key: "recorder.warn.body", language: language, args: ["left": left]) + " "
                + Strings.shared.get(key: "recorder.warn.extend", language: language, args: ["to": RecordingPlans.shared.words(ms: to.int64Value)])
        } else {
            text = Strings.shared.get(key: "recorder.warn.final", language: language, args: [
                "left": left, "cap": RecordingPlans.shared.words(ms: capMs),
            ])
        }
        let content = UNMutableNotificationContent()
        content.title = Strings.shared.get(key: "recorder.warn.title", language: language, args: [:])
        content.body = text
        content.sound = .default
        content.interruptionLevel = .timeSensitive
        content.categoryIdentifier = warning.isFinal ? finalWarningCategory : warningCategory
        content.userInfo = ["kind": "recorder"]
        UNUserNotificationCenter.current().add(
            UNNotificationRequest(identifier: warningId, content: content, trigger: nil)
        )
        RecorderBeep.play()
        vibrate()
    }

    /// the warning was answered, or the recording ended.
    static func clearWarning() {
        let center = UNUserNotificationCenter.current()
        center.removeDeliveredNotifications(withIdentifiers: [warningId])
        center.removePendingNotificationRequests(withIdentifiers: [warningId])
    }

    static func post(_ key: String, args: [String: Any] = [:], vibrate shouldVibrate: Bool) {
        post(text: Strings.shared.get(key: key, language: LanguageStore.load(), args: args))
        if shouldVibrate { vibrate() }
    }

    private static func post(text: String) {
        let content = UNMutableNotificationContent()
        content.title = Strings.shared.get(key: "recorder.title", language: LanguageStore.load(), args: [:])
        content.body = text
        content.sound = .default
        // the recording stopped, or will not start: through Focus and Do Not Disturb.
        content.interruptionLevel = .timeSensitive
        content.userInfo = ["kind": "recorder"]
        UNUserNotificationCenter.current().add(
            UNNotificationRequest(identifier: alertId, content: content, trigger: nil)
        )
    }

    /// Felt through a pocket, which a chime in a noisy hall is not.
    private static func vibrate() {
        AudioServicesPlaySystemSound(kSystemSoundID_Vibrate)
    }
}

/// the warning's beep: three short pips through the recording's
/// own audio session (play and record, see `RecorderEngine.activateSession`),
/// so it sounds with the phone locked. Made in memory once; no sound file.
@MainActor
enum RecorderBeep {

    /// Held until it has played; a player let go of stops at once.
    private static var player: AVAudioPlayer?

    static func play() {
        guard let player = try? AVAudioPlayer(data: tone) else { return }
        player.volume = 1
        self.player = player
        player.play()
    }

    /// 16-bit mono PCM in a WAV wrapper: three 120 ms pips at 880 Hz, 80 ms apart.
    private static let tone: Data = {
        let rate = 22_050
        var samples: [Int16] = []
        for pip in 0..<3 {
            let on = rate * 120 / 1000, gap = rate * 80 / 1000, fade = rate * 5 / 1000
            for i in 0..<on {
                let edge = min(i, on - 1 - i)
                let gain = edge < fade ? Double(edge) / Double(fade) : 1
                let value = sin(2 * Double.pi * 880 * Double(i) / Double(rate)) * 0.6 * gain
                samples.append(Int16(value * Double(Int16.max)))
            }
            if pip < 2 { samples += [Int16](repeating: 0, count: gap) }
        }
        var data = Data()
        func put<T: FixedWidthInteger>(_ value: T) { withUnsafeBytes(of: value.littleEndian) { data.append(contentsOf: $0) } }
        let bytes = samples.count * 2
        data.append(contentsOf: Array("RIFF".utf8)); put(UInt32(36 + bytes))
        data.append(contentsOf: Array("WAVE".utf8))
        data.append(contentsOf: Array("fmt ".utf8)); put(UInt32(16)); put(UInt16(1)); put(UInt16(1))
        put(UInt32(rate)); put(UInt32(rate * 2)); put(UInt16(2)); put(UInt16(16))
        data.append(contentsOf: Array("data".utf8)); put(UInt32(bytes))
        for sample in samples { put(sample) }
        return data
    }()
}

/// Uploads when the app is not running: the second line behind uploading
/// while recording, which is the first.
///
/// iOS DECIDES WHEN THESE RUN — usually within the hour for a refresh, and
/// overnight on a charger for processing — so they are what catches a
/// recording made with no signal, not what sends one the moment Stop is
/// pressed.
enum RecorderBackground {

    static let refreshId = "com.humblesolutions.ainotetaker.recorder.refresh"
    static let uploadId = "com.humblesolutions.ainotetaker.recorder.upload"

    /// Must run before launch finishes; iOS refuses registrations after.
    static func register() {
        for id in [refreshId, uploadId] {
            _ = BGTaskScheduler.shared.register(forTaskWithIdentifier: id, using: nil) { task in
                handle(task)
            }
        }
    }

    static func schedule() {
        let refresh = BGAppRefreshTaskRequest(identifier: refreshId)
        refresh.earliestBeginDate = Date(timeIntervalSinceNow: 15 * 60)
        try? BGTaskScheduler.shared.submit(refresh)

        let upload = BGProcessingTaskRequest(identifier: uploadId)
        upload.requiresNetworkConnectivity = true
        try? BGTaskScheduler.shared.submit(upload)
    }

    /*
     * COMPLETED EXACTLY ONCE, AND ON TIME. iOS terminates an app whose
     * background task is still open when its time runs out, so expiry
     * completes it at once — the upload may carry on until the app is
     * suspended, and whatever does not finish is simply sent next time.
     */
    private static func handle(_ task: BGTask) {
        let completion = TaskCompletion(task)
        let work = Task {
            let outcome = await RecorderController.runSync()
            if outcome == SyncStatus.retry { schedule() }
            completion.complete(success: outcome != SyncStatus.retry)
        }
        task.expirationHandler = {
            work.cancel()
            schedule()
            completion.complete(success: false)
        }
    }
}

/// A background task finished by whichever of the work or the clock gets there first.
private final class TaskCompletion: @unchecked Sendable {
    private let task: BGTask
    private let lock = NSLock()
    private var done = false

    init(_ task: BGTask) {
        self.task = task
    }

    func complete(success: Bool) {
        lock.lock()
        defer { lock.unlock() }
        guard !done else { return }
        done = true
        task.setTaskCompleted(success: success)
    }
}
