import SwiftUI
import shared

/// The Meetings tab: the person's own recordings, newest first. A summarised
/// one opens its summary.
struct MeetingsView: View {
    @EnvironmentObject private var theme: AppTheme
    @Environment(\.scenePhase) private var scenePhase
    @StateObject private var viewModel = MeetingsViewModel()

    let me: User
    let onOpen: (String) -> Void
    let onProfile: () -> Void

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 12) {
                ScreenHeader(initials: me.initials, onProfile: onProfile) {
                    Text(theme.tr("meetings.title"))
                        .font(theme.type.title)
                        .tracking(-0.5)
                        .foregroundStyle(theme.colors.ink)
                }
                .padding(.bottom, 4)

                if let problem = viewModel.problem, viewModel.rows.isEmpty {
                    AppCard {
                        VStack(alignment: .leading, spacing: 12) {
                            Text(theme.tr("meetings.loadFailed"))
                                .font(theme.type.subheading)
                                .foregroundStyle(theme.colors.ink)
                            Text(Meetings.message(problem, theme: theme))
                                .font(theme.type.small)
                                .foregroundStyle(theme.colors.inkMuted)
                            AppButton(title: theme.tr("meetings.retry"), tone: .outline, busy: viewModel.loading) {
                                Task { await viewModel.refresh() }
                            }
                        }
                    }
                } else if !viewModel.loadedOnce {
                    ProgressView()
                        .tint(theme.colors.primary)
                        .frame(maxWidth: .infinity)
                        .padding(.top, 60)
                } else if viewModel.rows.isEmpty {
                    emptyState
                }

                ForEach(viewModel.rows, id: \.id) { row in
                    rowView(row)
                }
            }
            .padding(.horizontal, 16)
            .padding(.bottom, 24)
        }
        .background(theme.colors.bg.ignoresSafeArea())
        .refreshable { await viewModel.refresh() }
        .task { await viewModel.refresh() }
        // While a meeting is uploading or processing, read the list again every 5 s.
        .task(id: viewModel.working && scenePhase == .active) {
            guard viewModel.working, scenePhase == .active else { return }
            while !Task.isCancelled {
                try? await Task.sleep(nanoseconds: MeetingsViewModel.workingRefreshNanos)
                if Task.isCancelled { break }
                await viewModel.refresh()
            }
        }
    }

    private var emptyState: some View {
        VStack(spacing: 14) {
            LogoMark(size: 52, fill: theme.colors.primaryTint, mark: theme.colors.primary)
            Text(theme.tr("meetings.empty"))
                .font(theme.type.bodyStrong)
                .foregroundStyle(theme.colors.inkMuted)
        }
        .frame(maxWidth: .infinity)
        .padding(.top, 80)
    }

    private func rowView(_ row: MeetingRow) -> some View {
        let key = row.statusKey
        let meta = [MeetingDates.dateTime(row.meetingAt), MeetingDates.length(row.durationMs)]
            .compactMap { $0 }
            .joined(separator: " · ")
        return Button {
            onOpen(row.id)
        } label: {
            AppCard {
                VStack(alignment: .leading, spacing: 8) {
                    HStack(alignment: .top, spacing: 10) {
                        Text(row.title.isEmpty ? theme.tr("meetings.untitled") : row.title)
                            .font(theme.type.subheading)
                            .foregroundStyle(theme.colors.ink)
                            .lineLimit(2)
                            .multilineTextAlignment(.leading)
                            .frame(maxWidth: .infinity, alignment: .leading)
                        AppChip(text: theme.tr("meetings.status.\(key)"), tone: MeetingTones.meeting(key))
                    }
                    if !row.summaryText.isEmpty {
                        Text(row.summaryText)
                            .font(theme.type.small)
                            .foregroundStyle(theme.colors.inkMuted)
                            .lineLimit(1)
                    }
                    if !meta.isEmpty {
                        HStack(spacing: 6) {
                            Image(systemName: "calendar")
                                .font(.system(size: 11, weight: .semibold))
                            Text(meta)
                                .font(theme.type.caption)
                                .monospacedDigit()
                        }
                        .foregroundStyle(theme.colors.inkFaint)
                    }
                }
            }
        }
        .buttonStyle(.plain)
        .disabled(!row.hasSummary)
    }
}
