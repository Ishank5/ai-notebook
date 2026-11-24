import SwiftUI
import shared

/// One meeting after Claude has summarised it: the title, a meta line, then
/// the summary's sections as cards. Empty sections are left out.
struct MeetingDetailView: View {
    @EnvironmentObject private var theme: AppTheme
    @Environment(\.scenePhase) private var scenePhase
    @StateObject private var viewModel: MeetingDetailViewModel

    let me: User

    init(meetingId: String, me: User) {
        _viewModel = StateObject(wrappedValue: MeetingDetailViewModel(meetingId: meetingId))
        self.me = me
    }

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 12) {
                if let detail = viewModel.detail {
                    header(detail.meeting, analysis: detail.analysis)
                    if let a = detail.analysis {
                        sections(a)
                    } else {
                        AppCard {
                            Text(theme.tr("meetings.detail.notReady"))
                                .font(theme.type.small)
                                .foregroundStyle(theme.colors.inkMuted)
                        }
                    }
                } else if let problem = viewModel.problem {
                    AppCard {
                        VStack(alignment: .leading, spacing: 12) {
                            Text(Meetings.message(problem, theme: theme))
                                .font(theme.type.small)
                                .foregroundStyle(theme.colors.inkMuted)
                            AppButton(title: theme.tr("meetings.retry"), tone: .outline, busy: viewModel.loading) {
                                Task { await viewModel.load() }
                            }
                        }
                    }
                } else {
                    ProgressView()
                        .tint(theme.colors.primary)
                        .frame(maxWidth: .infinity)
                        .padding(.top, 60)
                }
            }
            .padding(.horizontal, 16)
            .padding(.top, 4)
            .padding(.bottom, 24)
        }
        .background(theme.colors.bg.ignoresSafeArea())
        .navigationBarTitleDisplayMode(.inline)
        .refreshable { await viewModel.load() }
        .task { await viewModel.load() }
        // Still processing: look again every 5 s while the screen is in front.
        .task(id: viewModel.working && scenePhase == .active) {
            guard viewModel.working, scenePhase == .active else { return }
            while !Task.isCancelled {
                try? await Task.sleep(nanoseconds: MeetingsViewModel.workingRefreshNanos)
                if Task.isCancelled { break }
                await viewModel.load()
            }
        }
    }

    // MARK: - Header

    private func header(_ row: MeetingRow, analysis: MeetingAnalysis?) -> some View {
        let title = !row.title.isEmpty ? row.title : (analysis?.title ?? "")
        let meta = [MeetingDates.dateTime(row.meetingAt), MeetingDates.length(row.durationMs)]
            .compactMap { $0 }
            .joined(separator: " · ")
        return VStack(alignment: .leading, spacing: 8) {
            Text(title.isEmpty ? theme.tr("meetings.untitled") : title)
                .font(theme.type.heading)
                .tracking(-0.3)
                .foregroundStyle(theme.colors.ink)
            HStack(spacing: 8) {
                if !meta.isEmpty {
                    Text(meta)
                        .font(theme.type.small)
                        .monospacedDigit()
                        .foregroundStyle(theme.colors.inkMuted)
                }
                if let mood = analysis?.mood, !mood.isEmpty {
                    AppChip(text: MeetingTones.moodLabel(mood), tone: MeetingTones.mood(mood), dot: true)
                }
            }
        }
        .padding(.bottom, 6)
    }

    // MARK: - The summary

    @ViewBuilder
    private func sections(_ a: MeetingAnalysis) -> some View {
        if !a.summary.isEmpty || !a.howItWent.isEmpty {
            MeetingSection(title: theme.tr("meetings.detail.summary"), icon: "text.alignleft") {
                if !a.summary.isEmpty {
                    Text(a.summary)
                        .font(theme.type.body)
                        .foregroundStyle(theme.colors.ink)
                        .lineSpacing(3)
                }
                if !a.howItWent.isEmpty {
                    Text(a.howItWent)
                        .font(theme.type.small)
                        .foregroundStyle(theme.colors.inkMuted)
                        .lineSpacing(2)
                }
                if !a.moodReason.isEmpty {
                    Text(a.moodReason)
                        .font(theme.type.caption)
                        .foregroundStyle(theme.colors.inkFaint)
                }
            }
        }

        if !a.keyPoints.isEmpty {
            MeetingSection(title: theme.tr("meetings.detail.keyPoints"), icon: "list.bullet") {
                CollapsibleList(items: a.keyPoints, limit: 5) { point in
                    BulletLine(text: point.text, seconds: point.atSeconds)
                }
            }
        }

        if !a.decisions.isEmpty {
            MeetingSection(title: theme.tr("meetings.detail.decisions"), icon: "checkmark.seal") {
                CollapsibleList(items: a.decisions) { d in BulletLine(text: d.text, seconds: d.atSeconds) }
            }
        }

        if !a.actionItems.isEmpty {
            MeetingSection(title: theme.tr("meetings.detail.actions"), icon: "checklist") {
                CollapsibleList(items: a.actionItems) { item in
                    VStack(alignment: .leading, spacing: 4) {
                        HStack(alignment: .top, spacing: 8) {
                            Text(item.task)
                                .font(theme.type.bodyStrong)
                                .foregroundStyle(theme.colors.ink)
                                .frame(maxWidth: .infinity, alignment: .leading)
                            AppChip(text: theme.tr("meetings.level.\(item.priority)"), tone: MeetingTones.level(item.priority))
                        }
                        let meta = [
                            item.owner.isEmpty ? nil : item.owner,
                            item.due.isEmpty ? nil : "\(theme.tr("meetings.detail.due")) \(item.due)",
                        ].compactMap { $0 }.joined(separator: " · ")
                        if !meta.isEmpty {
                            Text(meta).font(theme.type.caption).foregroundStyle(theme.colors.inkMuted)
                        }
                    }
                }
            }
        }

        if !a.questions.isEmpty {
            MeetingSection(title: theme.tr("meetings.detail.questions"), icon: "questionmark.bubble") {
                CollapsibleList(items: a.questions) { q in
                    VStack(alignment: .leading, spacing: 4) {
                        HStack(alignment: .top, spacing: 8) {
                            Text(q.question)
                                .font(theme.type.bodyStrong)
                                .foregroundStyle(theme.colors.ink)
                                .frame(maxWidth: .infinity, alignment: .leading)
                            AppChip(text: theme.tr("meetings.question.\(q.status)"), tone: MeetingTones.question(q.status))
                        }
                        if !q.answer.isEmpty {
                            Text(q.answer).font(theme.type.small).foregroundStyle(theme.colors.inkMuted)
                        }
                    }
                }
            }
        }

        bullets("meetings.detail.risks", "exclamationmark.triangle", a.risks)
        prose("meetings.detail.nextMeeting", "calendar", a.nextMeeting)

        if !a.participants.isEmpty {
            MeetingSection(title: theme.tr("meetings.detail.people"), icon: "person.2") {
                CollapsibleList(items: a.participants) { p in
                    VStack(alignment: .leading, spacing: 2) {
                        Text([p.name, p.role].filter { !$0.isEmpty }.joined(separator: " · "))
                            .font(theme.type.bodyStrong)
                            .foregroundStyle(theme.colors.ink)
                        let note = [p.stance, p.note].filter { !$0.isEmpty }.joined(separator: " ")
                        if !note.isEmpty {
                            Text(note).font(theme.type.small).foregroundStyle(theme.colors.inkMuted)
                        }
                    }
                }
            }
        }

        if !a.issues.isEmpty {
            MeetingSection(title: theme.tr("meetings.detail.issues"), icon: "flag") {
                CollapsibleList(items: a.issues) { i in
                    VStack(alignment: .leading, spacing: 3) {
                        HStack(alignment: .top, spacing: 8) {
                            Text([i.topic, i.area].filter { !$0.isEmpty }.joined(separator: " · "))
                                .font(theme.type.bodyStrong)
                                .foregroundStyle(theme.colors.ink)
                                .frame(maxWidth: .infinity, alignment: .leading)
                            AppChip(text: theme.tr("meetings.level.\(i.severity)"), tone: MeetingTones.level(i.severity))
                        }
                        if !i.detail.isEmpty {
                            Text(i.detail).font(theme.type.small).foregroundStyle(theme.colors.inkMuted)
                        }
                    }
                }
            }
        }

        if !a.commitments.isEmpty {
            MeetingSection(title: theme.tr("meetings.detail.commitments"), icon: "hand.raised") {
                CollapsibleList(items: a.commitments) { c in
                    BulletLine(text: c.by.isEmpty ? c.text : "\(c.text) (\(c.by))", seconds: c.atSeconds)
                }
            }
        }

        bullets("meetings.detail.opportunities", "arrow.up.right", a.opportunities)
        prose("meetings.detail.futureScope", "scope", a.futureScope)
        prose("meetings.detail.conclusion", "text.quote", a.conclusion)
    }

    @ViewBuilder
    private func prose(_ key: String, _ icon: String, _ text: String) -> some View {
        if !text.isEmpty {
            MeetingSection(title: theme.tr(key), icon: icon) {
                Text(text)
                    .font(theme.type.body)
                    .foregroundStyle(theme.colors.ink)
                    .lineSpacing(3)
            }
        }
    }

    @ViewBuilder
    private func bullets(_ key: String, _ icon: String, _ items: [String]) -> some View {
        if !items.isEmpty {
            MeetingSection(title: theme.tr(key), icon: icon) {
                CollapsibleList(items: items) { text in BulletLine(text: text) }
            }
        }
    }
}
