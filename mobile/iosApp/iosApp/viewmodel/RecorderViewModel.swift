import Combine
import Foundation
import Network
import shared

/// The details being typed for one recording.
struct RecordingDraft: Equatable {
    var recordingId: String
    var title = ""
    /// One of the `notetaker.kind.*` ids, or blank.
    var kind = ""
    var location = ""
    var attendees = ""
}

/// The recorder screen: the button, the clock, and the list of what is safe.
///
/// THE SCREEN DOES NOT RECORD. [RecorderController] does, and keeps doing it
/// with this view model long gone; the screen reads its live status and the
/// queue in `recordings.db`, and sends it commands.
@MainActor
final class RecorderViewModel: ObservableObject {

    @Published private(set) var live = RecorderController.Status()
    @Published private(set) var recordings: [RecordingSummary] = []
    @Published private(set) var loaded = false
    /// The queue would not open — a full disk, most likely. Nothing can record.
    @Published private(set) var unavailable = false
    @Published var draft: RecordingDraft?
    @Published private(set) var savingDraft = false
    @Published private(set) var detailsSaved = false
    @Published private(set) var online = true
    @Published private(set) var errorKey: String?
    /// Sent recordings the server has finished summarising.
    @Published private(set) var summaryReady: Set<String> = []
    /// The planned length's numbers, and the length picked for the next recording.
    @Published private(set) var limits = RecorderTestMode.limits
    @Published var planMs: Int64 = RecorderTestMode.limits.defaultMs
    /// The debug-only fast test mode is on (never in a release build).
    @Published private(set) var testMode = RecorderTestMode.isOn
    /// Other people's meetings on this phone, waiting for them to sign in here again.
    @Published private(set) var othersWaiting: [OthersWaiting] = []
    /// The name of the person signed in, kept with each recording they make.
    private var ownerName: String?

    private let controller = RecorderController.shared
    private var store: RecordingStore?
    // The shared module's handle, not Combine's protocol of the same name.
    private var watching: shared.Cancellable?
    private var ownerUid: String?
    private var subscriptions = Set<AnyCancellable>()
    private let pathMonitor = NWPathMonitor()

    private let summariesReady = SummariesReadyUseCase(statuses: IOSRecordingStatusRepository())
    private var asking = false
    private var askedAt = Date.distantPast
    private var askedAbout: Set<String> = []
    /// The server said one of them is still processing at the last look: look again in seconds, not a minute.
    private var serverWorking = false
    /// How long the screen waits before its next look for summaries.
    var summaryCheckEveryNanos: UInt64 { serverWorking ? 5_000_000_000 : 60_000_000_000 }
    /// How many of the screen's watches are running; see `onScreenShown`.
    private var shownBy = 0

    var interrupted: [RecordingSummary] {
        recordings.filter { $0.recording.state == RecordingState.interrupted }
    }

    /// Something is on the phone that the server has not confirmed.
    var waiting: Bool {
        recordings.contains { $0.recording.state == RecordingState.stopped }
    }

    init() {
        controller.$status
            .receive(on: DispatchQueue.main)
            .sink { [weak self] status in
                guard let self else { return }
                self.live = status
                // A recording that has just stopped opens its details straight
                // away: the one moment somebody knows what the meeting was
                // called and is still holding the phone.
                if self.draft == nil, let id = status.justStopped {
                    self.draft = self.draftFor(id)
                    self.detailsSaved = false
                }
            }
            .store(in: &subscriptions)

        pathMonitor.pathUpdateHandler = { [weak self] path in
            Task { @MainActor in self?.online = path.status == .satisfied }
        }
        pathMonitor.start(queue: DispatchQueue(label: "recorder.network"))
    }

    deinit {
        watching?.cancel()
        pathMonitor.cancel()
    }

    func bind(me: User) {
        guard ownerUid != me.uid else { return }
        ownerUid = me.uid
        ownerName = me.name.isEmpty ? me.email : me.name
        Task {
            do {
                let opened = try await RecordingSource.shared.store(factory: RecordingDriverFactory())
                store = opened
                watching?.cancel()
                watching = ObserveRecordingsUseCase(store: opened).execute(ownerUid: me.uid) { [weak self] list in
                    Task { @MainActor in
                        self?.recordings = list
                        self?.loaded = true
                        self?.refreshSummaries()
                        await self?.refreshOthers()
                    }
                }
                // Recovery, the tidy-up and any upload waiting — the same pass
                // the background tasks run.
                await controller.syncNow()
            } catch {
                unavailable = true
                loaded = true
            }
        }
    }

    /// The screen has come into view, or gone out of it. Summaries are looked
    /// for only while it is in view: a recording keeps the app running for
    /// hours in the background, and nobody is reading "Summary ready" then.
    ///
    /// COUNTED, NOT SET: SwiftUI can start the next phase's watch before the
    /// cancelled one has run its `defer`, and that late "gone" must not cancel
    /// the new "shown".
    func onScreenShown(_ value: Bool) {
        shownBy = max(0, shownBy + (value ? 1 : -1))
    }

    /// Asks the server which sent recordings now have a summary.
    ///
    /// Called whenever the list changes and every minute while the screen is
    /// in view (every 5 s while the server is still processing one), so it
    /// asks at most that often unless a newly sent recording appears — the
    /// list changes with every chunk while a meeting uploads.
    func refreshSummaries() {
        guard shownBy > 0, !asking else { return }
        let unsummarised = Set(
            recordings
                .filter { $0.recording.state == RecordingState.uploaded && !summaryReady.contains($0.recording.id) }
                .map { $0.recording.id }
        )
        guard !unsummarised.isEmpty else {
            serverWorking = false
            return
        }
        let now = Date()
        let every: TimeInterval = serverWorking ? 4.5 : 55
        if unsummarised.isSubset(of: askedAbout) && now.timeIntervalSince(askedAt) < every { return }
        askedAt = now
        askedAbout = unsummarised
        asking = true
        let list = recordings
        let known = summaryReady
        Task {
            defer { asking = false }
            let check = try? await summariesReady.checkStatuses(
                recordings: list,
                known: known,
                now: Int64(now.timeIntervalSince1970 * 1000)
            )
            serverWorking = check?.working ?? false
            let ready = check?.ready ?? []
            if !ready.isEmpty { summaryReady.formUnion(ready) }
        }
    }

    /// Whose meetings are waiting for another sign-in on this phone.
    private func refreshOthers() async {
        guard let store, let ownerUid else { return }
        if let others = try? await OthersWaitingUseCase(store: store).execute(ownerUid: ownerUid) {
            othersWaiting = others
        }
    }

    /// Every login records, with no company, for the length picked in the row.
    func onRecord() {
        errorKey = nil
        detailsSaved = false
        controller.acknowledge()
        let planMs = planMs
        let ownerName = ownerName
        Task { await controller.start(planMs: planMs, ownerName: ownerName) }
    }

    /// A length picked in the row before Record.
    func onPlanPicked(_ ms: Int64) {
        planMs = limits.planOrDefault(planMs: KotlinLong(value: ms))
    }

    /// Keep recording, from the screen.
    func onExtend() { controller.extend() }

    /// Debug builds only, the hidden switch for the fast test mode.
    func onToggleTestMode() -> Bool {
        guard RecorderTestMode.available, !live.isLive else { return testMode }
        RecorderTestMode.toggle()
        testMode = RecorderTestMode.isOn
        limits = RecorderTestMode.limits
        planMs = limits.defaultMs
        return testMode
    }

    /// Room under the 3-hour cap, by the shared rule.
    func canContinue(_ summary: RecordingSummary) -> Bool {
        RecordingPlans.shared.canContinue(audioMs: summary.durationMs, limits: limits)
    }

    func onPause() { controller.pause() }
    func onResume() { controller.resume() }
    func onStop() { controller.stop() }

    func onContinue(_ recordingId: String) {
        errorKey = nil
        controller.acknowledge()
        Task { await controller.continueRecording(recordingId) }
    }

    func onFinish(_ recordingId: String) {
        guard let store else { return }
        Task {
            try? await FinishInterruptedUseCase(
                store: store, files: IOSRecordingFiles.shared, runtime: RecordingSource.shared.runtime
            ).execute(recordingId: recordingId, reason: EndReason.interrupted)
            await controller.syncNow()
        }
    }

    func onDismissError() {
        errorKey = nil
        controller.acknowledge()
    }

    func onEditDetails(_ summary: RecordingSummary) {
        draft = draftFor(summary.recording.id)
        detailsSaved = false
    }

    func onSaveDetails() {
        guard let store, let draft else { return }
        savingDraft = true
        Task {
            try? await SetRecordingDetailsUseCase(store: store).execute(
                recordingId: draft.recordingId,
                title: draft.title,
                meetingType: draft.kind,
                location: draft.location,
                attendees: draft.attendees
            )
            controller.acknowledge()
            self.draft = nil
            savingDraft = false
            detailsSaved = true
            await controller.syncNow()
        }
    }

    func onSkipDetails() {
        controller.acknowledge()
        draft = nil
    }

    func onSendNow() {
        Task { await controller.syncNow() }
    }

    private func draftFor(_ recordingId: String) -> RecordingDraft {
        let recording = recordings.first { $0.recording.id == recordingId }?.recording
        return RecordingDraft(
            recordingId: recordingId,
            title: recording?.title ?? "",
            kind: recording?.meetingType ?? "",
            location: recording?.location ?? "",
            attendees: recording?.attendees ?? ""
        )
    }
}
