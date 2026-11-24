import AVFoundation
import CallKit
import Foundation
import UIKit
import shared

/// What the engine reports, from whatever thread it happens to be on.
protocol RecorderEngineDelegate: AnyObject {
    func engine(phase: RecorderController.Phase, pausedFor: GapReason?, elapsedMs: Int64, recordingId: String)
    func engine(level: Float, elapsedMs: Int64)
    func engineClosedChunk()
    func engineBeat()
    /// the planned length is reached, or the cap is near (replaces TAS's minutes left).
    func engine(warning: RecorderWarning, elapsedMs: Int64)
    /// Keep recording moved the planned end; the warning is over.
    func engine(planExtended stopAtMs: Int64, elapsedMs: Int64)
    /// Keep recording was asked for with nothing to extend (no warning showing, or at the cap).
    func engineExtendIgnored()
    /// paused by a call or an interruption for about a minute, and resuming keeps failing.
    func engineResumeFailing()
    /// recording again after `engineResumeFailing`.
    func engineResumed()
    /// half a minute of exact zeros while recording (true; it goes on recording), or sound again (false).
    func engine(micSilent: Bool)
    /// [unchosen] when nobody chose the end (an error): the recording is kept INTERRUPTED, to be
    /// continued, rather than stopped.
    func engine(finished recordingId: String, reason: EndReason, hadAudio: Bool, errorKey: String?, unchosen: Bool)
}

/// The microphone, the encoder and the chunk files, for one recording — the
/// iOS half of Android's `RecorderEngine.kt`, making the same decisions
/// through the same shared use cases.
///
/// ONE LOOP OWNS EVERYTHING. The microphone tap only encodes and hands frames
/// over; interruptions, route changes, Pause and Stop arrive on the same
/// ordered stream. So nothing is ever half-closed on one thread while another
/// writes to it, and a Stop is handled after every frame captured before it.
///
/// THE AUDIO SESSION IS WHAT KEEPS THE APP ALIVE. With the `audio` background
/// mode, a running recording keeps iOS from suspending the app with the phone
/// locked. It is also why a paused recording releases the microphone: an app
/// that is not recording has no business holding it, and iOS may suspend it —
/// which recovery handles like any other ending.
final class RecorderEngine {

    private enum Event {
        case packets([Data], level: Float)
        case pause
        case resume
        /// Keep recording.
        case extend
        case stop(EndReason)
        case interruptionBegan
        /// with the background time taken when it arrived, given back after the attempt.
        case interruptionEnded(shouldResume: Bool, grant: BackgroundGrant)
        case routeChanged
        case mediaServicesReset
        /// every two seconds; the heartbeat is written every ten.
        case tick
        /// the next try at resuming after a call or an interruption; stale ones are ignored.
        case retry(generation: Int)
    }

    private enum Failure: Error {
        case microphone
    }

    weak var delegate: RecorderEngineDelegate?

    private let files: IOSRecordingFiles
    private let beginChunk: BeginChunkUseCase
    private let closeChunk: CloseChunkUseCase
    private let heartbeat: HeartbeatUseCase
    private let pause: PauseRecordingUseCase
    private let resume: ResumeRecordingUseCase
    private let stopRecording: StopRecordingUseCase
    private let interruptRecording: InterruptRecordingUseCase
    private let extendPlan: ExtendRecordingUseCase
    /// the planned length's numbers; shorter only in the debug test mode.
    private let limits: RecorderLimits

    private let events: AsyncStream<Event>
    private let continuation: AsyncStream<Event>.Continuation
    private let aacFormat: AVAudioFormat
    private let callObserver = CXCallObserver()

    /// The converter is used by the microphone's thread and, at the very end,
    /// by the loop draining it. Removing a tap does not wait for a callback
    /// already running, so the two are kept from overlapping.
    private let converterLock = NSLock()
    /// guarded by `converterLock`. Built on the tap's thread from the first buffer's own format, and
    /// built again whenever a buffer arrives in another one.
    private var converter: AVAudioConverter?

    // Touched only by the loop.
    private var audioEngine = AVAudioEngine()
    private var capturing = false
    private var recordingId = ""
    private var clock: RecordingClock!
    private var writer: IOSChunkWriter?
    private var pausedFor: GapReason?
    // Uptime, not the wall clock: somebody changing the time mid-meeting must
    // not stop the two-second sync to disk until the clock catches up.
    private var lastFlush = ProcessInfo.processInfo.systemUptime
    private var lastLevel: TimeInterval = 0
    private var observers: [NSObjectProtocol] = []
    private var heartbeatTask: Task<Void, Never>?

    /*
     * RESUMING AFTER A CALL OR AN INTERRUPTION. TAS resumed only when iOS said `.shouldResume`, and
     * tried once. Now every end of an interruption is tried, under background time so a locked phone is not
     * suspended halfway, again after 0.5, 1, 2, 4 and 8 seconds, then on every heartbeat while not in a call (which
     * also covers a "began" that never gets its "ended"). Still failing after about a minute, the person is told.
     */
    private var restartAttempt = 0
    private var retryGeneration = 0
    /// the generation of the backoff try now waiting to fire; a retry is pending while it is current.
    private var scheduledGeneration = -1
    private var retryPending: Bool { scheduledGeneration == retryGeneration }
    /// When the current call or interruption pause began (uptime); nil while recording.
    private var faultSince: TimeInterval?
    private var resumeFailingTold = false
    /// The background time taken when an interruption ended, held until the tries are over.
    private var restartGrant: BackgroundGrant?
    private static let backoff: [TimeInterval] = [0.5, 1, 2, 4, 8]

    /*
     * RECORDING, OR SAYING SO? A capture can keep "running" with nothing coming out of it (a route
     * that went away, an engine stopped under us). The loop ticks every two seconds; no packets for five, or an
     * engine not running, is treated like a route change: one restart in place, and if that does not bring audio
     * back, a pause as an interruption, so the dead man's switch and the Live Activity tell the truth. A capture
     * restarted after a pause ends the pause on its first packets, not before.
     */
    /// When the last packets arrived (uptime).
    private var lastPacket = ProcessInfo.processInfo.systemUptime
    private var lastHeartbeat = ProcessInfo.processInfo.systemUptime
    private var lastFaultRetry: TimeInterval = 0
    /// When a silent capture was last restarted in place (uptime).
    private var lastSilentRestart: TimeInterval?
    /// Restarted after a call or an interruption, waiting for packets before the pause ends (uptime).
    private var awaitingPacketsSince: TimeInterval?
    private static let silentAfter: TimeInterval = 5

    /// when capture was last restarted (uptime); see `.routeChanged`.
    private var lastRestart: TimeInterval = 0

    /// chunk files that failed in a row; a fresh chunk is tried once before the recording ends.
    private var chunkFailures = 0

    /*
     * EXACT ZEROS. With `.mixWithOthers`, another app or a VoIP call holding the microphone can leave
     * this capture running on silence. Zeros are recorded (a quiet room can be exact zeros, and a pause would stop
     * the clock short of the planned stop); after half a minute of them the person is asked whether another app is
     * using the microphone, once, and again only after sound came back. The rule is shared with Android.
     */
    private let silence = SilenceWatch(alertAfterMs: RecorderConfig.shared.SILENT_ALERT_MS)
    private static let tickEvery: TimeInterval = 2
    private static let resumeFailingAfter: TimeInterval = 60

    init(store: RecordingStore, files: IOSRecordingFiles, runtime: RecorderRuntime, limits: RecorderLimits) {
        self.files = files
        self.limits = limits
        extendPlan = ExtendRecordingUseCase(store: store)
        beginChunk = BeginChunkUseCase(store: store, files: files, runtime: runtime)
        closeChunk = CloseChunkUseCase(store: store, files: files)
        heartbeat = HeartbeatUseCase(store: store)
        pause = PauseRecordingUseCase(store: store)
        resume = ResumeRecordingUseCase(store: store)
        stopRecording = StopRecordingUseCase(store: store, files: files, runtime: runtime)
        interruptRecording = InterruptRecordingUseCase(store: store, files: files, runtime: runtime)
        let stream = AsyncStream.makeStream(of: Event.self, bufferingPolicy: .unbounded)
        events = stream.stream
        continuation = stream.continuation
        aacFormat = AVAudioFormat(settings: [
            AVFormatIDKey: kAudioFormatMPEG4AAC,
            AVSampleRateKey: Double(RecorderConfig.shared.SAMPLE_RATE),
            AVNumberOfChannelsKey: Int(RecorderConfig.shared.CHANNELS),
        ])!
    }

    /// [grant] is the background time taken on the tap of Record, given back once capture has
    /// started (or failed to), so locking the phone at once cannot suspend the app before it records.
    func start(_ start: RecorderStart, grant: BackgroundGrant? = nil) {
        Task.detached(priority: .userInitiated) { [self] in await run(start, grant: grant) }
    }

    func requestPause() { continuation.yield(.pause) }
    func requestResume() { continuation.yield(.resume) }
    /// ignored unless the warning is showing and the plan is below the cap.
    func requestExtend() { continuation.yield(.extend) }
    func requestStop(_ reason: EndReason = .stopped) { continuation.yield(.stop(reason)) }

    // MARK: - The loop

    private func run(_ start: RecorderStart, grant: BackgroundGrant?) async {
        recordingId = start.recordingId
        // the clock follows the plan the recording was started (or continued) with.
        clock = RecordingClock(
            priorMs: start.priorMs,
            sampleRate: RecorderConfig.shared.SAMPLE_RATE,
            chunkMs: RecorderConfig.shared.CHUNK_MS,
            stopAtMs: start.stopAtMs,
            limits: limits
        )

        var reason = EndReason.stopped
        var errorKey: String?
        var unchosen = false
        do {
            do {
                try Self.activateSession()
            } catch {
                // A call in progress, or another app holding the microphone.
                NSLog("[Recorder] audio session refused: %@", String(describing: error))
                grant?.end()
                throw Failure.microphone
            }
            do {
                try startCapture()
            } catch {
                grant?.end()
                throw error
            }
            grant?.end()
            observe()
            startHeartbeat()
            publish(.recording)
            reason = try await consume()
            unchosen = reason == .interrupted
        } catch Failure.microphone {
            // kept for Continue (a new recording with no audio is simply not kept).
            reason = .interrupted
            unchosen = true
            errorKey = RecorderErrors.shared.MIC_BUSY
        } catch {
            NSLog("[Recorder] stopped unexpectedly: %@", String(describing: error))
            if (error as NSError).userInfo["KotlinException"] is RecorderRefused {
                // The chunk ceiling — a recording paused hundreds of times.
                // Treated like the time limit: stop, keep everything, send it.
                // `limit` to the server, but its own words on the phone (not "3 hours").
                reason = .limit
                errorKey = RecorderErrors.shared.CHUNK_LIMIT
            } else {
                // kept for Continue, not stopped.
                reason = .interrupted
                unchosen = true
                errorKey = files.freeBytes() < Self.lowSpaceBytes
                    ? RecorderErrors.shared.NO_SPACE
                    : RecorderErrors.shared.STORAGE
            }
        }
        await finish(reason: reason, errorKey: errorKey, unchosen: unchosen)
    }

    private func consume() async throws -> EndReason {
        for await event in events {
            switch event {
            case let .packets(packets, level):
                lastPacket = ProcessInfo.processInfo.systemUptime
                // a capture restarted after a call or an interruption: audio again, so the pause ends.
                if awaitingPacketsSince != nil, pausedFor == .call || pausedFor == .interruption {
                    awaitingPacketsSince = nil
                    lastSilentRestart = nil
                    await leavePause()
                    clearFault()
                }
                // Frames still in flight when capture paused belong to no chunk.
                guard pausedFor == nil else { continue }
                // a level below zero is a buffer it could not be read from; it says nothing either way.
                if level >= 0 {
                    let event = silence.onAudio(silent: level == 0, nowMs: Int64(lastPacket * 1000))
                    if event == SilenceEvent.silent {
                        NSLog("[Recorder] only digital silence for %lld ms on %@ (iOS %@); still recording",
                              silence.silentForMs(nowMs: Int64(lastPacket * 1000)), RecorderDevices.current.model,
                              UIDevice.current.systemVersion)
                        delegate?.engine(micSilent: true)
                    } else if event == SilenceEvent.back {
                        NSLog("[Recorder] sound from the microphone again")
                        delegate?.engine(micSilent: false)
                    }
                }
                for packet in packets {
                    if let end = try await write(packet) { return end }
                }
                let now = ProcessInfo.processInfo.systemUptime
                if (now - lastFlush) * 1000 >= Double(RecorderConfig.shared.FLUSH_MS) {
                    // logged, not fatal; Stop's repair seals whatever reached the disk.
                    do { try writer?.sync() } catch { NSLog("[Recorder] could not sync the chunk: %@", String(describing: error)) }
                    lastFlush = now
                }
                if now - lastLevel >= 0.25 {
                    lastLevel = now
                    delegate?.engine(level: max(level, 0), elapsedMs: clock.totalMs)
                }

            case .pause:
                if pausedFor == nil {
                    await enterPause(.user)
                    stopCapture()
                }

            case .resume:
                // Any pause can be resumed by hand here: after a call iOS does
                // not always say "resume", and the person is the better judge.
                if pausedFor != nil {
                    if restart() {
                        await leavePause()
                        clearFault()
                    } else {
                        NSLog("[Recorder] could not resume")
                    }
                }

            case .extend:
                // the clock holds the rule; the queue keeps the result for a Continue.
                if clock.extend() {
                    // the clock has it; a queue that would not take it costs a Continue the longer
                    // plan, never the meeting.
                    do {
                        try await extendPlan.execute(recordingId: recordingId, stopAtMs: clock.stopAtMs, limits: limits)
                    } catch {
                        NSLog("[Recorder] could not save Keep recording: %@", String(describing: error))
                    }
                    delegate?.engine(planExtended: clock.stopAtMs, elapsedMs: clock.totalMs)
                } else {
                    delegate?.engineExtendIgnored()
                }

            case let .stop(reason):
                return reason

            case .interruptionBegan:
                // iOS has already stopped the engine.
                capturing = false
                if pausedFor == nil {
                    await enterPause(inCall() ? .call : .interruption)
                }
                // nothing is scheduled here; iOS should say when it ends. If it never does, the
                // heartbeat tries again, counted from now, so not in the first seconds of a Siri or an alarm.
                lastFaultRetry = ProcessInfo.processInfo.systemUptime

            case let .interruptionEnded(shouldResume, grant):
                // tried whether or not iOS says `.shouldResume`, with the background time the
                // observer took, which is given back once the tries are over.
                restartGrant?.end()
                restartGrant = grant
                NSLog("[Recorder] interruption ended (shouldResume: %@)", shouldResume ? "yes" : "no")
                if pausedFor == .call || pausedFor == .interruption {
                    restartAttempt = 0
                    try await tryResumeFromFault()
                } else {
                    endRestartGrant()
                }

            case let .retry(generation):
                guard generation == retryGeneration else { continue }
                scheduledGeneration = -1
                guard pausedFor == .call || pausedFor == .interruption else { continue }
                try await tryResumeFromFault()

            case .routeChanged:
                // A headset plugged in or out changes the input format under
                // the tap. New tap, new converter, same recording.
                // through the same retry path as an interruption (TAS tried once), with a fresh
                // engine for every try. One that comes within a second of a restart is that restart settling, not a
                // new change; the silent-capture check catches anything it hides.
                let sinceRestart = ProcessInfo.processInfo.systemUptime - lastRestart
                if pausedFor == nil || pausedFor == .interruption, sinceRestart > 1 {
                    try await recoverCapture(why: "route change")
                }

            case .mediaServicesReset:
                // Everything audio was torn down by the system, including this
                // engine, which is not touched again: a new one is built.
                audioEngine = AVAudioEngine()
                capturing = false
                if pausedFor == nil || pausedFor == .interruption {
                    try await recoverCapture(why: "media services reset")
                }

            case .tick:
                let now = ProcessInfo.processInfo.systemUptime
                if (now - lastHeartbeat) * 1000 >= Double(RecorderConfig.shared.HEARTBEAT_MS) {
                    lastHeartbeat = now
                    // a heartbeat the queue would not take is logged; the next one tries again.
                    do {
                        try await heartbeat.execute(recordingId: recordingId, now: Self.now())
                    } catch {
                        NSLog("[Recorder] could not write the heartbeat: %@", String(describing: error))
                    }
                    delegate?.engineBeat()
                }
                try await checkAudio(now: now)
                // paused by a call or an interruption, no try waiting, and no call on: try again every
                // ten seconds. Covers an interruption whose "ended" never comes, which used to wait for good: this
                // ran only once the backoff was spent, and a "began" never starts one.
                if pausedFor == .call || pausedFor == .interruption, !inCall(), awaitingPacketsSince == nil,
                   !retryPending,
                   (now - lastFaultRetry) * 1000 >= Double(RecorderConfig.shared.HEARTBEAT_MS) {
                    lastFaultRetry = now
                    try await tryResumeFromFault()
                }
                if let since = faultSince, !resumeFailingTold, !inCall(), now - since >= Self.resumeFailingAfter {
                    resumeFailingTold = true
                    NSLog("[Recorder] still paused after a minute; telling the person")
                    delegate?.engineResumeFailing()
                }
            }
        }
        return .interrupted
    }

    /// One AAC frame into the current chunk. returns why to stop
    /// (the cap, or the planned length left unanswered), or nil to carry on.
    private func write(_ packet: Data) async throws -> EndReason? {
        // An ADTS header holds 1 to 8184 bytes of frame, and the Kotlin that
        // writes it refuses anything else — by throwing, which across into
        // Swift is not an error but the end of the app. An AAC frame is never
        // outside that; if one ever were, it is dropped, not fatal.
        guard (1...Self.maxFrameBytes).contains(packet.count) else { return nil }
        let header = Adts.shared.header(
            payloadBytes: Int32(packet.count),
            sampleRate: RecorderConfig.shared.SAMPLE_RATE,
            channels: RecorderConfig.shared.CHANNELS
        )
        let frame = Data(kotlin: header) + packet
        while true {
            do {
                if writer == nil {
                    let chunk = try await beginChunk.execute(recordingId: recordingId)
                    clock.chunkStarted()
                    writer = try IOSChunkWriter(seq: chunk.seq, path: chunk.path, url: files.url(chunk.path))
                    lastFlush = ProcessInfo.processInfo.systemUptime
                }
                try writer?.write(frame)
                chunkFailures = 0
                break
            } catch {
                if (error as NSError).userInfo["KotlinException"] is RecorderRefused { throw error }
                // the chunk is let go as it is (its row stays open, and the next chunk, or Stop,
                // seals it from what reached the disk, so its size always matches its file), and a fresh one is
                // tried once. TAS ended the recording on the first failed write.
                NSLog("[Recorder] chunk write failed: %@", String(describing: error))
                writer?.abandon()
                writer = nil
                chunkFailures += 1
                if chunkFailures >= 2 || files.freeBytes() < Self.lowSpaceBytes { throw error }
            }
        }

        let tick = clock.advance(frames: 1)
        if tick.warning, let warning = clock.warning {
            delegate?.engine(warning: warning, elapsedMs: clock.totalMs)
        }
        if let stop = tick.stop { return stop }
        if tick.rotate { await closeCurrentChunk() }
        return nil
    }

    /// never throws. A file that will not close, or a row that will not take it, is left open, and
    /// the next chunk (or Stop, or recovery) seals it from what reached the disk.
    private func closeCurrentChunk() async {
        guard let current = writer else { return }
        writer = nil
        let closed: IOSChunkWriter.Closed
        do {
            closed = try current.close()
        } catch {
            NSLog("[Recorder] could not close chunk %d: %@", current.seq, String(describing: error))
            current.abandon()
            return
        }
        do {
            try await closeChunk.execute(
                recordingId: recordingId,
                seq: current.seq,
                path: current.path,
                bytes: closed.bytes,
                frames: closed.frames,
                md5: closed.md5,
                now: Self.now()
            )
        } catch {
            NSLog("[Recorder] could not record chunk %d closed: %@", current.seq, String(describing: error))
            return
        }
        delegate?.engineClosedChunk()
    }

    /// never throws: a queue that would not take the pause costs its gap's label, not the meeting.
    private func enterPause(_ reason: GapReason) async {
        // a run of zeros counts again once it records again.
        silence.reset()
        await closeCurrentChunk()
        do {
            try await pause.execute(recordingId: recordingId, reason: reason, now: Self.now())
        } catch {
            NSLog("[Recorder] could not record the pause: %@", String(describing: error))
        }
        pausedFor = reason
        // the clock for "still paused after a minute".
        if reason != .user, faultSince == nil { faultSince = ProcessInfo.processInfo.systemUptime }
        publish(.paused)
    }

    private func leavePause() async {
        do {
            try await resume.execute(recordingId: recordingId, now: Self.now())
        } catch {
            NSLog("[Recorder] could not record the resume: %@", String(describing: error))
        }
        pausedFor = nil
        publish(.recording)
    }

    /// is anything coming out of the microphone? See the note on `lastPacket`.
    private func checkAudio(now: TimeInterval) async throws {
        if let since = awaitingPacketsSince, now - since > Self.silentAfter {
            // Restarted after a pause, and still nothing: not back yet.
            NSLog("[Recorder] restarted capture gives no audio; trying again")
            awaitingPacketsSince = nil
            stopCapture()
            scheduleRetry()
            return
        }
        guard pausedFor == nil, capturing,
              now - lastPacket > Self.silentAfter || !audioEngine.isRunning
        else { return }
        if let last = lastSilentRestart, now - last < Self.silentAfter * 4 {
            // A restart in place did not bring audio back: pause, so nothing claims to record, and keep trying.
            NSLog("[Recorder] still no audio after a restart; paused as an interruption")
            lastSilentRestart = nil
            stopCapture()
            await enterPause(.interruption)
            restartAttempt = 0
            scheduleRetry()
            return
        }
        NSLog("[Recorder] no audio for %.1f s (engine running: %@); restarting",
              now - lastPacket, audioEngine.isRunning ? "yes" : "no")
        lastSilentRestart = now
        try await recoverCapture(why: "silent capture")
    }

    /// one try at recording again after a call or an interruption, and the next one scheduled if it
    /// fails. Not while a call is on: the microphone is the call's.
    private func tryResumeFromFault() async throws {
        if inCall() {
            scheduleRetry()
            return
        }
        if restart() {
            // The pause ends on the first packets (`.packets`); none within five seconds is another try.
            NSLog("[Recorder] capture restarted after a pause for %@", pausedFor?.id ?? "-")
            awaitingPacketsSince = ProcessInfo.processInfo.systemUptime
        } else {
            scheduleRetry()
        }
    }

    /// the session and the capture again, from nothing: a fresh `AVAudioEngine` for every try, since
    /// one that went through a route change or a reset can refuse to start again.
    private func restart() -> Bool {
        stopCapture()
        audioEngine = AVAudioEngine()
        lastRestart = ProcessInfo.processInfo.systemUptime
        do {
            try Self.activateSession()
            try startCapture()
            return true
        } catch {
            NSLog("[Recorder] could not restart capture: %@", String(describing: error))
            return false
        }
    }

    /// capture broke under a recording (a route change, a reset): try again at once; if that fails,
    /// pause as an interruption and go on trying, as after a call.
    private func recoverCapture(why: String) async throws {
        NSLog("[Recorder] restarting capture after a %@", why)
        if pausedFor == .interruption {
            restartAttempt = 0
            try await tryResumeFromFault()
            return
        }
        if restart() { return }
        await enterPause(.interruption)
        restartAttempt = 0
        scheduleRetry()
    }

    /// the next try after the backoff; once the backoff is spent, the heartbeat tries instead.
    private func scheduleRetry() {
        retryGeneration += 1
        guard restartAttempt < Self.backoff.count else {
            endRestartGrant()
            return
        }
        let generation = retryGeneration
        scheduledGeneration = generation
        let delay = Self.backoff[restartAttempt]
        restartAttempt += 1
        Task { [weak self] in
            try? await Task.sleep(nanoseconds: UInt64(delay * 1_000_000_000))
            self?.continuation.yield(.retry(generation: generation))
        }
    }

    /// recording again; the tries, the background time and any "still paused" notice are over.
    private func clearFault() {
        faultSince = nil
        awaitingPacketsSince = nil
        restartAttempt = 0
        retryGeneration += 1
        endRestartGrant()
        if resumeFailingTold {
            resumeFailingTold = false
            delegate?.engineResumed()
        }
    }

    private func endRestartGrant() {
        restartGrant?.end()
        restartGrant = nil
    }

    /// The end, however it came: the encoder drained, the last chunk closed,
    /// the microphone released, and the recording marked stopped to upload.
    private func finish(reason: EndReason, errorKey: String?, unchosen: Bool) async {
        publish(.stopping)
        heartbeatTask?.cancel()
        endRestartGrant()
        observers.forEach { NotificationCenter.default.removeObserver($0) }
        observers = []

        let wasCapturing = capturing
        stopCapture()
        // AAC runs two frames behind: the last words of the meeting are still
        // inside the converter. Not after a pause, nor past the end the clock
        // called (the cap, or the planned length).
        if wasCapturing, pausedFor == nil, errorKey == nil, reason != .limit, reason != .planned {
            for packet in drain() {
                // Stop at the first frame that ends it, or fails to write.
                do {
                    if try await write(packet) != nil { break }
                } catch {
                    break
                }
            }
        }
        await closeCurrentChunk()
        dropConverter()
        try? AVAudioSession.sharedInstance().setActive(false, options: .notifyOthersOnDeactivation)

        var hadAudio = true
        do {
            if unchosen {
                // nobody chose this end: kept for Continue, not stopped.
                hadAudio = try await interruptRecording.execute(
                    recordingId: recordingId, now: Self.now(), uptimeMs: Self.uptimeMs()
                ).boolValue
            } else {
                hadAudio = try await stopRecording.execute(
                    recordingId: recordingId, reason: reason, now: Self.now()
                ).boolValue
            }
        } catch {
            // The queue could not be written; it still says "recording", so
            // the next launch interrupts it and offers Continue.
            NSLog("[Recorder] could not mark %@ stopped: %@", recordingId, String(describing: error))
        }
        continuation.finish()
        // background time taken for events nobody will read now is given back at once. iOS ends an
        // app that holds a task past its time.
        for await event in events {
            if case let .interruptionEnded(_, grant) = event { grant.end() }
        }
        NSLog("[Recorder] %@ ended: %@ (audio: %@)", recordingId, reason.id, hadAudio ? "yes" : "no")
        delegate?.engine(finished: recordingId, reason: reason, hadAudio: hadAudio, errorKey: errorKey, unchosen: unchosen)
    }

    // MARK: - Audio

    /// static, so the controller can make the session active on the tap of Record (I6).
    static func activateSession() throws {
        let session = AVAudioSession.sharedInstance()
        // play and record (TAS used record only), so the warning
        // at the planned length can beep through this same session with the
        // phone locked. Out of the loudspeaker, not the earpiece. No voice
        // processing: the default mode records the room as `.record` did.
        // Mixed with others: another app's sound (a video, a navigation
        // prompt) no longer interrupts the recording, and a mixable session
        // may be made active again from the background after a call.
        try session.setCategory(.playAndRecord, mode: .default, options: [.defaultToSpeaker, .mixWithOthers])
        // The warning is also a vibration; iOS mutes haptics while recording
        // unless asked not to.
        try? session.setAllowHapticsAndSystemSoundsDuringRecording(true)
        try session.setActive(true)
    }

    private func startCapture() throws {
        let input = audioEngine.inputNode
        let format = input.outputFormat(forBus: 0)
        guard format.sampleRate > 0, format.channelCount > 0 else { throw Failure.microphone }

        // no format named for the tap. Naming one that differs from what the hardware delivers (a
        // route that changed between the read and the install) raises an exception that no `catch` can hold, and
        // the app is gone. The converter is built from the first buffer instead (`encode`).
        dropConverter()
        input.removeTap(onBus: 0)
        input.installTap(onBus: 0, bufferSize: 4096, format: nil) { [weak self] buffer, _ in
            self?.encode(buffer)
        }
        audioEngine.prepare()
        do {
            try audioEngine.start()
        } catch {
            input.removeTap(onBus: 0)
            throw Failure.microphone
        }
        capturing = true
        // the silent-capture check counts from here.
        lastPacket = ProcessInfo.processInfo.systemUptime
    }

    /// the next buffer builds a new converter.
    private func dropConverter() {
        converterLock.withLock { converter = nil }
    }

    /// a converter from [format] to the recording's AAC, or nil for a format it cannot take.
    private func makeConverter(from format: AVAudioFormat) -> AVAudioConverter? {
        guard format.sampleRate > 0, format.channelCount > 0,
              let converter = AVAudioConverter(from: format, to: aacFormat)
        else { return nil }
        // The encoder offers a fixed set of rates at 16 kHz mono; take the
        // nearest to the one the plan and the storage estimate assume.
        let target = Int(RecorderConfig.shared.BIT_RATE)
        if let rates = converter.applicableEncodeBitRates?.map(\.intValue), !rates.isEmpty {
            converter.bitRate = rates.min { abs($0 - target) < abs($1 - target) } ?? target
        }
        if format.channelCount > 1 { converter.downmix = true }
        return converter
    }

    private func stopCapture() {
        audioEngine.inputNode.removeTap(onBus: 0)
        audioEngine.stop()
        capturing = false
    }

    /// On the tap's thread: microphone samples to AAC frames, handed to the loop.
    private func encode(_ buffer: AVAudioPCMBuffer) {
        converterLock.lock()
        defer { converterLock.unlock() }
        // built from this buffer's format the first time, and again when it changes; a buffer no
        // converter can take is skipped, not fatal.
        if converter == nil || converter?.inputFormat.isEqual(buffer.format) == false {
            guard let fresh = makeConverter(from: buffer.format) else {
                NSLog("[Recorder] no converter for %@; buffer skipped", buffer.format.description)
                return
            }
            converter = fresh
        }
        guard let converter else { return }
        let output = AVAudioCompressedBuffer(
            format: aacFormat,
            packetCapacity: 16,
            maximumPacketSize: max(converter.maximumOutputPacketSize, 1536)
        )
        var supplied = false
        var packets: [Data] = []
        while true {
            var error: NSError?
            let status = converter.convert(to: output, error: &error) { _, inputStatus in
                if supplied {
                    inputStatus.pointee = .noDataNow
                    return nil
                }
                supplied = true
                inputStatus.pointee = .haveData
                return buffer
            }
            packets += Self.packets(in: output)
            output.packetCount = 0
            output.byteLength = 0
            if status == .error {
                // TAS dropped this silently. A fresh converter for the next buffer.
                NSLog("[Recorder] converter failed: %@", String(describing: error))
                self.converter = nil
                break
            }
            if status != .haveData { break }
        }
        if !packets.isEmpty {
            continuation.yield(.packets(packets, level: Self.level(of: buffer)))
        }
    }

    /// What the converter still holds, at the very end.
    private func drain() -> [Data] {
        converterLock.lock()
        defer { converterLock.unlock() }
        guard let converter else { return [] }
        let output = AVAudioCompressedBuffer(
            format: aacFormat,
            packetCapacity: 16,
            maximumPacketSize: max(converter.maximumOutputPacketSize, 1536)
        )
        var packets: [Data] = []
        for _ in 0..<8 {
            var error: NSError?
            let status = converter.convert(to: output, error: &error) { _, inputStatus in
                inputStatus.pointee = .endOfStream
                return nil
            }
            packets += Self.packets(in: output)
            output.packetCount = 0
            output.byteLength = 0
            if status != .haveData { break }
        }
        return packets
    }

    private static func packets(in buffer: AVAudioCompressedBuffer) -> [Data] {
        guard let descriptions = buffer.packetDescriptions else { return [] }
        return (0..<Int(buffer.packetCount)).map { index in
            let description = descriptions[index]
            return Data(
                bytes: buffer.data.advanced(by: Int(description.mStartOffset)),
                count: Int(description.mDataByteSize)
            )
        }
    }

    /// The loudest sample, 0–1: enough for a meter, and exactly 0 only for digital silence.
    /// 16-bit buffers too, and -1 for a format it cannot read, so that is never taken for silence.
    private static func level(of buffer: AVAudioPCMBuffer) -> Float {
        let count = Int(buffer.frameLength)
        if let samples = buffer.floatChannelData?[0] {
            var peak: Float = 0
            for index in 0..<count {
                peak = max(peak, abs(samples[index]))
            }
            return min(peak, 1)
        }
        if let samples = buffer.int16ChannelData?[0] {
            var peak: Int32 = 0
            for index in 0..<count {
                peak = max(peak, abs(Int32(samples[index])))
            }
            return min(Float(peak) / 32_768, 1)
        }
        return -1
    }

    private func inCall() -> Bool {
        callObserver.calls.contains { !$0.hasEnded }
    }

    // MARK: - Signals from the system

    private func observe() {
        let center = NotificationCenter.default
        observers.append(center.addObserver(
            forName: AVAudioSession.interruptionNotification, object: nil, queue: nil
        ) { [weak self] note in
            guard let raw = note.userInfo?[AVAudioSessionInterruptionTypeKey] as? UInt,
                  let type = AVAudioSession.InterruptionType(rawValue: raw)
            else { return }
            switch type {
            case .began:
                self?.continuation.yield(.interruptionBegan)
            case .ended:
                // no grant for an engine that is gone.
                guard let self else { return }
                let options = AVAudioSession.InterruptionOptions(
                    rawValue: note.userInfo?[AVAudioSessionInterruptionOptionKey] as? UInt ?? 0
                )
                // time to resume in, before a locked phone suspends the app. A stream that has
                // finished drops the event, and the grant with it, which gives the time back (see `deinit`).
                let grant = BackgroundGrant(name: "recorder-resume")
                let result = self.continuation.yield(.interruptionEnded(
                    shouldResume: options.contains(.shouldResume), grant: grant
                ))
                if case .terminated = result { grant.end() }
            @unknown default:
                break
            }
        })
        observers.append(center.addObserver(
            forName: .AVAudioEngineConfigurationChange, object: nil, queue: nil
        ) { [weak self] _ in
            self?.continuation.yield(.routeChanged)
        })
        observers.append(center.addObserver(
            forName: AVAudioSession.mediaServicesWereResetNotification, object: nil, queue: nil
        ) { [weak self] _ in
            self?.continuation.yield(.mediaServicesReset)
        })
    }

    /// a tick every two seconds (TAS: a heartbeat every ten); the heartbeat is written every ten.
    private func startHeartbeat() {
        let interval = UInt64(Self.tickEvery * 1_000_000_000)
        heartbeatTask = Task { [weak self] in
            while !Task.isCancelled {
                try? await Task.sleep(nanoseconds: interval)
                self?.continuation.yield(.tick)
            }
        }
    }

    private func publish(_ phase: RecorderController.Phase) {
        delegate?.engine(
            phase: phase, pausedFor: pausedFor, elapsedMs: clock?.totalMs ?? 0, recordingId: recordingId
        )
    }

    static func now() -> Int64 {
        Int64(Date().timeIntervalSince1970 * 1000)
    }

    /// ms since boot, counting sleep (`CLOCK_MONOTONIC` does on Darwin; `systemUptime` does not).
    /// What recovery counts a cut-off recording's hour on, so changing the time cannot shorten it.
    static func uptimeMs() -> Int64 {
        Int64(clock_gettime_nsec_np(CLOCK_MONOTONIC) / 1_000_000)
    }

    /// below this a failed write ends the recording rather than trying a fresh chunk.
    private static let lowSpaceBytes: Int64 = 20 * 1024 * 1024

    /// The largest payload an ADTS frame length field (13 bits, less the header) can describe.
    private static let maxFrameBytes = 0x1FFF - Int(Adts.shared.HEADER_BYTES)
}

/// background time from any thread, given back exactly once (or by iOS when it runs out).
/// and when it is let go without `end()` (an event dropped by a finished stream, say), since its
/// expiry handler holds it weakly and could not give the time back, and iOS kills an app that overruns.
final class BackgroundGrant: @unchecked Sendable {
    private let lock = NSLock()
    private var identifier: UIBackgroundTaskIdentifier = .invalid

    init(name: String) {
        let taken = UIApplication.shared.beginBackgroundTask(withName: name) { [weak self] in self?.end() }
        lock.lock()
        identifier = taken
        lock.unlock()
    }

    deinit { end() }

    func end() {
        lock.lock()
        let current = identifier
        identifier = .invalid
        lock.unlock()
        if current != .invalid { UIApplication.shared.endBackgroundTask(current) }
    }
}
