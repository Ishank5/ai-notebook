import SwiftUI
import shared

/// Where in the app somebody is, once past the recorder.
///
/// A `Hashable` enum rather than strings: SwiftUI's `navigationDestination`
/// matches on type, and a typo in a route string would fail silently at
/// runtime instead of loudly at compile time.
enum AppDestination: Hashable {
    case profile
    /// One meeting from the Meetings tab.
    case meeting(String)
}

/// The two tabs, the recorder and the person's meetings.
enum AppTab: Hashable {
    case record, meetings
}

/// The app proper, once somebody is in: the recorder, the summarised meetings,
/// and a small profile reached from the avatar at the top of each tab.
struct MainTabView: View {
    @EnvironmentObject private var theme: AppTheme

    let user: User
    let onSignOut: () -> Void

    @State private var path = NavigationPath()
    @State private var meetingsPath = NavigationPath()
    @State private var tab: AppTab = .record

    /// A tapped notification, handed over by the app delegate.
    @ObservedObject private var pendingNotification = Notifications.Pending.shared

    /// The recorder, for the warning before it stops, on whichever tab is open.
    @ObservedObject private var recorder = RecorderController.shared
    /// The warning already closed, by when it ends; a later warning shows again.
    @State private var dismissedWarning: Int64?

    /// A recorder notification — the recording was cut off, or reached its
    /// limit — opens the recorder.
    private func openRecorderFromNotification() {
        pendingNotification.consumeRecorder()
        tab = .record
        path = NavigationPath()
    }

    var body: some View {
        TabView(selection: $tab) {
            NavigationStack(path: $path) {
                shell(
                    RecorderView(me: user) { path.append(AppDestination.profile) }
                )
            }
            .tabItem { Label("Record", systemImage: "mic.fill") }
            .tag(AppTab.record)

            NavigationStack(path: $meetingsPath) {
                shell(
                    MeetingsView(
                        me: user,
                        onOpen: { id in meetingsPath.append(AppDestination.meeting(id)) },
                        onProfile: { meetingsPath.append(AppDestination.profile) }
                    )
                )
            }
            .tabItem { Label(theme.tr("meetings.tab"), systemImage: "doc.text") }
            .tag(AppTab.meetings)
        }
        .tint(theme.colors.primary)
        // Keep recording (while below the cap) or Stop now; closing it changes nothing.
        .alert(
            theme.tr("recorder.warn.title"),
            isPresented: warningShown,
            presenting: recorder.status.warning
        ) { warning in
            if warning.extendToMs != nil {
                Button(theme.tr("recorder.keep")) {
                    dismissedWarning = warning.endAtMs
                    recorder.extend()
                }
            } else {
                Button(theme.tr("recorder.warn.ok"), role: .cancel) { dismissedWarning = warning.endAtMs }
            }
            Button(theme.tr("recorder.stopNow"), role: .destructive) {
                dismissedWarning = warning.endAtMs
                recorder.stop()
            }
        } message: { warning in
            Text(warningText(warning))
        }
        // Stop from the Live Activity, asked once more now the phone is unlocked.
        .alert(theme.tr("recorder.confirmStop.title"), isPresented: $recorder.confirmingStop) {
            Button(theme.tr("recorder.stop"), role: .destructive) { recorder.stop() }
            Button(theme.tr("recorder.confirmStop.cancel"), role: .cancel) {}
        }
        .onChange(of: recorder.confirmingStop) { _, asking in
            if asking { tab = .record }
        }
        .onChange(of: pendingNotification.openRecorder) { _, open in
            if open { openRecorderFromNotification() }
        }
        /*
         * Also checked on appear, for the case the tap LAUNCHED the app: the
         * delegate records it before this view exists, so there is no change
         * for onChange to observe — the value is simply already true.
         */
        .onAppear {
            if pendingNotification.openRecorder { openRecorderFromNotification() }
        }
    }

    /// Shown while a warning is up and not yet closed.
    private var warningShown: Binding<Bool> {
        Binding(
            get: {
                guard let warning = recorder.status.warning else { return false }
                return dismissedWarning != warning.endAtMs
            },
            set: { shown in
                if !shown, let warning = recorder.status.warning { dismissedWarning = warning.endAtMs }
            }
        )
    }

    private func warningText(_ warning: RecorderWarning) -> String {
        let left = RecordingPlans.shared.wordsLeft(ms: warning.endAtMs - recorder.status.elapsedMs)
        if let to = warning.extendToMs {
            return theme.tr("recorder.warn.body", ["left": left]) + " "
                + theme.tr("recorder.warn.extend", ["to": RecordingPlans.shared.words(ms: to.int64Value)])
        }
        return theme.tr("recorder.warn.final", ["left": left, "cap": RecordingPlans.shared.words(ms: recorder.status.capMs)])
    }

    /// Each tab's root: its own header instead of a navigation bar, and where its links go.
    private func shell<Content: View>(_ content: Content) -> some View {
        content
            .toolbar(.hidden, for: .navigationBar)
            .navigationDestination(for: AppDestination.self) { destination in
                switch destination {
                case .profile:
                    ProfileView(user: user, onSignOut: onSignOut)
                case .meeting(let id):
                    MeetingDetailView(meetingId: id, me: user)
                }
            }
    }
}
