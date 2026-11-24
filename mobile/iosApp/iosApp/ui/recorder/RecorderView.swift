import SwiftUI
import shared

/// The kinds offered as chips in the details card, sent as the recording's `meetingType`.
private let meetingKinds: [(id: String, label: String)] = [
    ("meeting", "Meeting"), ("lecture", "Lecture"), ("voice_note", "Voice note"),
    ("journal", "Journal"), ("interview", "Interview"), ("other", "Other"),
]

/// The recorder: one big round button and a large clock, then the list of
/// what is on the phone and whether it is safe.
struct RecorderView: View {
    @EnvironmentObject private var theme: AppTheme
    @StateObject private var viewModel = RecorderViewModel()
    @Environment(\.scenePhase) private var scenePhase

    let me: User
    let onProfile: () -> Void

    private var errorKey: String? {
        viewModel.errorKey ?? viewModel.live.errorKey
            ?? (viewModel.unavailable ? RecorderErrors.shared.STORAGE : nil)
    }

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 16) {
                ScreenHeader(initials: me.initials, onProfile: onProfile) {
                    // A long press on the name switches the fast test mode, in debug builds only.
                    Wordmark()
                        .onLongPressGesture {
                            guard RecorderTestMode.available, !viewModel.live.isLive else { return }
                            _ = viewModel.onToggleTestMode()
                        }
                    if viewModel.testMode {
                        AppChip(text: "Fast test", tone: .amber)
                    }
                }

                if !viewModel.live.isLive {
                    ForEach(viewModel.interrupted, id: \.recording.id) { summary in
                        interruptedCard(summary)
                    }
                }

                recorderCard

                if let errorKey {
                    Notice(text: theme.tr(errorKey)) {
                        if errorKey == RecorderErrors.shared.MIC_PERMISSION {
                            Button(theme.tr("recorder.openSettings")) {
                                if let url = URL(string: UIApplication.openSettingsURLString) {
                                    UIApplication.shared.open(url)
                                }
                            }
                            .font(theme.type.smallStrong)
                            .foregroundStyle(theme.colors.ink)
                        }
                    }
                    .onTapGesture { if !viewModel.unavailable { viewModel.onDismissError() } }
                }

                if viewModel.draft != nil {
                    detailsCard
                }

                if viewModel.detailsSaved {
                    Notice(text: theme.tr("recorder.details.saved"), good: true)
                }

                recordingsList
            }
            .padding(.horizontal, 16)
            .padding(.bottom, 24)
        }
        .background(theme.colors.bg.ignoresSafeArea())
        .task { viewModel.bind(me: me) }
        // "Summary ready" arrives minutes after "Sent". Looked for only while
        // the screen is in front; a new phase cancels this and starts it again.
        .task(id: scenePhase) {
            guard scenePhase == .active else { return }
            viewModel.onScreenShown(true)
            defer { viewModel.onScreenShown(false) }
            while !Task.isCancelled {
                viewModel.refreshSummaries()
                try? await Task.sleep(nanoseconds: viewModel.summaryCheckEveryNanos)
            }
        }
    }

    // MARK: - The button and the clock

    private var recorderCard: some View {
        let live = viewModel.live
        return AppCard(padding: 24) {
            VStack(spacing: 18) {
                statusLine(live)

                Text(clock(live.elapsedMs))
                    .font(theme.type.timer)
                    .monospacedDigit()
                    .tracking(-1)
                    .foregroundStyle(live.phase == .recording || live.phase == .paused ? theme.colors.ink : theme.colors.inkFaint)
                    .contentTransition(.numericText())

                recordButton(live)

                switch live.phase {
                case .idle:
                    lengthRow
                    Text(theme.tr("recorder.limitNote"))
                        .font(theme.type.caption)
                        .foregroundStyle(theme.colors.inkFaint)
                        .multilineTextAlignment(.center)

                case .starting, .stopping:
                    EmptyView()

                case .recording, .paused:
                    liveControls(live)
                }
            }
            .frame(maxWidth: .infinity)
        }
    }

    @ViewBuilder
    private func statusLine(_ live: RecorderController.Status) -> some View {
        switch live.phase {
        case .idle:
            AppChip(text: "Ready", tone: .neutral)
        case .starting:
            AppChip(text: theme.tr("recorder.starting"), tone: .neutral)
        case .stopping:
            AppChip(text: theme.tr("recorder.stopping"), tone: .neutral)
        case .recording:
            AppChip(text: theme.tr("recorder.recording"), tone: .record, dot: true)
        case .paused:
            AppChip(text: theme.tr("recorder.paused"), tone: .amber, dot: true)
        }
    }

    /// Idle: the round coral button that starts a recording. Live: the same
    /// button stops it, with a thin ring that follows the room's level.
    private func recordButton(_ live: RecorderController.Status) -> some View {
        let isLive = live.phase == .recording || live.phase == .paused
        let busy = live.phase == .starting || live.phase == .stopping
        let enabled = live.phase == .idle ? (viewModel.loaded && !viewModel.unavailable) : isLive
        // Speech peaks well below full scale; this makes a normal voice move the ring clearly.
        let level = live.phase == .recording ? CGFloat(min(max(live.level * 2.5, 0), 1)) : 0

        return Button {
            if live.phase == .idle { viewModel.onRecord() } else if isLive { viewModel.onStop() }
        } label: {
            ZStack {
                Circle()
                    .stroke(live.phase == .recording ? theme.colors.record.opacity(0.35) : theme.colors.line, lineWidth: 2)
                    .frame(width: 120 + 34 * level, height: 120 + 34 * level)
                    .animation(.easeOut(duration: 0.18), value: level)
                Circle()
                    .fill(theme.colors.record)
                    .frame(width: 100, height: 100)
                if busy {
                    ProgressView().tint(.white)
                } else if isLive {
                    RoundedRectangle(cornerRadius: 7, style: .continuous)
                        .fill(.white)
                        .frame(width: 30, height: 30)
                } else {
                    Circle().fill(.white).frame(width: 32, height: 32)
                }
            }
            .frame(width: 160, height: 160)
            .contentShape(Circle())
        }
        .buttonStyle(.plain)
        .disabled(!enabled)
        .opacity(enabled || busy ? 1 : 0.5)
        .accessibilityLabel(theme.tr(isLive ? "recorder.stop" : "recorder.record"))
    }

    @ViewBuilder
    private func liveControls(_ live: RecorderController.Status) -> some View {
        let paused = live.phase == .paused

        AppButton(
            title: theme.tr(paused ? "recorder.resume" : "recorder.pause"),
            tone: .outline,
            fillWidth: false,
            systemImage: paused ? "play.fill" : "pause.fill",
            action: paused ? viewModel.onResume : viewModel.onPause
        )

        if live.pausedFor == GapReason.call {
            caption(theme.tr("recorder.pausedCall"))
        } else if live.pausedFor == GapReason.interruption {
            caption(theme.tr("recorder.pausedInterruption"))
        }

        // When it stops, and while the warning shows, Keep recording.
        if let warning = live.warning {
            AppChip(
                text: theme.tr("recorder.stopsIn", ["time": clock(warning.endAtMs - live.elapsedMs)]),
                tone: .amber
            )
            if warning.extendToMs != nil {
                AppButton(title: theme.tr("recorder.keep"), action: viewModel.onExtend)
            }
        } else if !paused {
            AppChip(text: theme.tr("recorder.stopsAt", [
                "time": Date().addingTimeInterval(TimeInterval(live.stopAtMs - live.elapsedMs) / 1000)
                    .formatted(date: .omitted, time: .shortened),
            ]))
        }

        caption(theme.tr("recorder.lockedOk"))
    }

    /// The planned length, picked before Record: one row of pill chips.
    private var lengthRow: some View {
        VStack(spacing: 10) {
            Text(theme.tr("recorder.length"))
                .font(theme.type.label)
                .tracking(0.8)
                .textCase(.uppercase)
                .foregroundStyle(theme.colors.inkFaint)
            ScrollView(.horizontal, showsIndicators: false) {
                HStack(spacing: 8) {
                    ForEach(viewModel.limits.choicesMs.map { $0.int64Value }, id: \.self) { choice in
                        AppChip(
                            text: RecordingPlans.shared.label(ms: choice),
                            selected: choice == viewModel.planMs
                        ) {
                            viewModel.onPlanPicked(choice)
                        }
                    }
                }
                .padding(.horizontal, 1)
            }
            .fixedSize(horizontal: true, vertical: false)
            .frame(maxWidth: .infinity)
        }
    }

    // MARK: - Cut off

    private func interruptedCard(_ summary: RecordingSummary) -> some View {
        let canContinue = viewModel.canContinue(summary)
        return AppCard {
            VStack(alignment: .leading, spacing: 10) {
                HStack(spacing: 8) {
                    Image(systemName: "exclamationmark.circle.fill")
                        .foregroundStyle(theme.colors.amber)
                    Text(theme.tr("recorder.interrupted.title"))
                        .font(theme.type.subheading)
                        .foregroundStyle(theme.colors.ink)
                }
                Text("\(time(summary.recording.startedAt)) · \(clock(summary.durationMs)) saved")
                    .font(theme.type.small)
                    .foregroundStyle(theme.colors.inkMuted)
                if canContinue {
                    AppButton(title: theme.tr("recorder.interrupted.continue")) {
                        viewModel.onContinue(summary.recording.id)
                    }
                }
                AppButton(title: theme.tr("recorder.interrupted.finish"), tone: .outline) {
                    viewModel.onFinish(summary.recording.id)
                }
            }
        }
    }

    // MARK: - Details

    private var detailsCard: some View {
        AppCard {
            VStack(alignment: .leading, spacing: 14) {
                HStack(alignment: .firstTextBaseline) {
                    Text(theme.tr("recorder.details.title"))
                        .font(theme.type.subheading)
                        .foregroundStyle(theme.colors.ink)
                    Spacer()
                    Text("Optional")
                        .font(theme.type.caption)
                        .foregroundStyle(theme.colors.inkFaint)
                }
                AppField(label: theme.tr("recorder.details.fieldTitle"), value: draftBinding(\.title))
                VStack(alignment: .leading, spacing: 8) {
                    Text(theme.tr("recorder.details.fieldKind"))
                        .font(theme.type.smallStrong)
                        .foregroundStyle(theme.colors.inkMuted)
                    ScrollView(.horizontal, showsIndicators: false) {
                        HStack(spacing: 8) {
                            ForEach(meetingKinds, id: \.id) { kind in
                                AppChip(text: kind.label, selected: viewModel.draft?.kind == kind.id) {
                                    viewModel.draft?.kind = viewModel.draft?.kind == kind.id ? "" : kind.id
                                }
                            }
                        }
                        .padding(.horizontal, 1)
                    }
                }
                AppField(label: theme.tr("recorder.details.fieldWhere"), value: draftBinding(\.location))
                AppField(label: theme.tr("recorder.details.fieldWho"), value: draftBinding(\.attendees), multiline: true)
                VStack(spacing: 4) {
                    AppButton(title: theme.tr("recorder.details.save"), busy: viewModel.savingDraft) {
                        viewModel.onSaveDetails()
                    }
                    AppButton(title: theme.tr("recorder.details.skip"), tone: .ghost) {
                        viewModel.onSkipDetails()
                    }
                }
                .padding(.top, 2)
            }
        }
    }

    private func draftBinding(_ field: WritableKeyPath<RecordingDraft, String>) -> Binding<String> {
        Binding(
            get: { viewModel.draft?[keyPath: field] ?? "" },
            set: { viewModel.draft?[keyPath: field] = $0 }
        )
    }

    // MARK: - The list

    @ViewBuilder
    private var recordingsList: some View {
        HStack {
            SectionLabel(text: theme.tr("recorder.list.title"))
            Spacer()
            if viewModel.waiting && viewModel.online {
                AppChip(text: theme.tr("recorder.sendNow"), tone: .primary) { viewModel.onSendNow() }
            }
        }
        .padding(.top, 8)

        // Other people's meetings on this phone; they go when that person signs in here again.
        ForEach(viewModel.othersWaiting, id: \.ownerUid) { others in
            Text(theme.tr("recorder.othersWaiting", [
                "count": KotlinInt(int: others.count),
                "name": others.ownerName ?? theme.tr("recorder.othersWaiting.someone"),
            ]))
            .font(theme.type.caption)
            .foregroundStyle(theme.colors.inkMuted)
        }

        let listed = viewModel.recordings.filter {
            !(viewModel.live.isLive && $0.recording.id == viewModel.live.recordingId)
        }
        if viewModel.loaded && listed.isEmpty {
            Text(theme.tr("recorder.list.empty"))
                .font(theme.type.small)
                .foregroundStyle(theme.colors.inkFaint)
                .frame(maxWidth: .infinity)
                .padding(.vertical, 20)
        }
        ForEach(listed, id: \.recording.id) { summary in
            row(summary)
        }
    }

    private func row(_ summary: RecordingSummary) -> some View {
        let recording = summary.recording
        let (label, tone) = status(of: summary)
        return Button {
            viewModel.onEditDetails(summary)
        } label: {
            AppCard(padding: 16) {
                HStack(alignment: .center, spacing: 12) {
                    Image(systemName: "waveform")
                        .font(.system(size: 15, weight: .semibold))
                        .foregroundStyle(theme.colors.primary)
                        .frame(width: 40, height: 40)
                        .background(theme.colors.primaryTint, in: RoundedRectangle(cornerRadius: 12, style: .continuous))
                    VStack(alignment: .leading, spacing: 3) {
                        Text(recording.title ?? theme.tr("recorder.list.untitled", ["date": time(recording.startedAt)]))
                            .font(theme.type.bodyStrong)
                            .foregroundStyle(theme.colors.ink)
                            .lineLimit(2)
                            .multilineTextAlignment(.leading)
                        Text([recording.title != nil ? time(recording.startedAt) : nil, clock(summary.durationMs)]
                            .compactMap { $0 }
                            .joined(separator: " · "))
                            .font(theme.type.caption)
                            .foregroundStyle(theme.colors.inkMuted)
                        if let error = recording.lastError, recording.state != RecordingState.uploaded {
                            Text(theme.tr(error))
                                .font(theme.type.caption)
                                .foregroundStyle(theme.colors.inkMuted)
                                .multilineTextAlignment(.leading)
                        }
                        if recording.title == nil, recording.state != RecordingState.interrupted {
                            Text(theme.tr("recorder.details.edit"))
                                .font(theme.type.caption)
                                .fontWeight(.semibold)
                                .foregroundStyle(theme.colors.primary)
                        }
                    }
                    Spacer(minLength: 0)
                    AppChip(text: label, tone: tone)
                }
            }
        }
        .buttonStyle(.plain)
        .disabled(recording.state == RecordingState.interrupted)
    }

    private func status(of summary: RecordingSummary) -> (String, ChipTone) {
        let recording = summary.recording
        let progress: [String: Any] = [
            "sent": KotlinInt(int: summary.uploadedChunks),
            "total": KotlinInt(int: summary.chunks),
        ]
        switch recording.state {
        case RecordingState.recording, RecordingState.paused:
            return (theme.tr("recorder.status.live"), .record)
        case RecordingState.interrupted:
            return (theme.tr("recorder.status.interrupted"), .amber)
        case RecordingState.uploaded:
            // The server took nothing more because an admin deleted the recording; not "Sent".
            if recording.lastError == RecorderErrors.shared.ALREADY_FINISHED {
                return (theme.tr("recorder.status.deleted"), .neutral)
            }
            let ready = viewModel.summaryReady.contains(recording.id)
            return (theme.tr(ready ? "recorder.status.summaryReady" : "recorder.status.sent"), .success)
        default:
            let permanent = [
                RecorderErrors.shared.BAD_REQUEST,
                RecorderErrors.shared.NOT_YOURS,
                RecorderErrors.shared.TOO_LARGE,
            ]
            if let error = recording.lastError, permanent.contains(error) {
                return (theme.tr("recorder.status.failed"), .record)
            }
            if summary.fullyUploaded { return (theme.tr("recorder.status.checking"), .primary) }
            if !viewModel.online { return (theme.tr("recorder.status.waiting", progress), .neutral) }
            return (theme.tr("recorder.status.sending", progress), .primary)
        }
    }

    // MARK: - Small parts

    private func caption(_ text: String) -> some View {
        Text(text)
            .font(theme.type.caption)
            .foregroundStyle(theme.colors.inkFaint)
            .multilineTextAlignment(.center)
    }

    /// 4:05, 1:04:05.
    private func clock(_ ms: Int64) -> String {
        let seconds = Int(max(ms, 0) / 1000)
        let h = seconds / 3600, m = (seconds % 3600) / 60, s = seconds % 60
        return h > 0
            ? String(format: "%ld:%02ld:%02ld", h, m, s)
            : String(format: "%ld:%02ld", m, s)
    }

    /// "25 Sep, 14:02".
    private func time(_ epochMs: Int64) -> String {
        let formatter = DateFormatter()
        formatter.locale = Locale(identifier: "en_GB")
        formatter.dateFormat = "d MMM, HH:mm"
        return formatter.string(from: Date(timeIntervalSince1970: TimeInterval(epochMs) / 1000))
    }
}
