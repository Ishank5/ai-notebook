import SwiftUI
import shared

/// The root, and the gate.
///
/// FOUR STATES, NOT TWO. Loading is a real state — the app cannot tell a signed
/// out person from one whose token is still being read, and guessing either way
/// is wrong. Blocked is real too: somebody suspended, or still being
/// provisioned, is told what happened rather than bounced to the sign-in
/// screen, which reads like the app losing the tap.
struct ContentView: View {
    @StateObject private var sessionViewModel = SessionViewModel()
    @StateObject private var theme = AppTheme()

    /*
     * THE SPLASH IS THE GATE, and it holds until BOTH answers are in: who this
     * is, and what they are allowed to see. Drawing before the matrix lands is
     * what made a tab appear and then vanish — and worse, briefly showed
     * somebody a feature the office had deliberately switched off.
     *
     * `Startup` owns the timing so both platforms hold for the same reasons
     * and the same lengths of time.
     */
    @State private var elapsed: Int64 = 0

    private var ready: Bool {
        Startup.shared.isReady(
            sessionResolved: !(sessionViewModel.session is SessionLoading),
            // No feature matrix, so the splash waits for the session alone.
            accessNeeded: false,
            accessResolved: true,
            elapsedMs: elapsed
        )
    }

    var body: some View {
        Group {
                if !ready {
                    SplashView()
                } else {
                switch sessionViewModel.session {
                case is SessionLoading:
                    // Unreachable once `ready` is true — kept because the switch
                    // must stay exhaustive over every session state.
                    SplashView()

                case let signedOut as SessionSignedOut:
                    SignInView(
                        /*
                         * COMPUTED, CARRIED, AND — UNTIL NOW — DROPPED HERE.
                         * `SignOutReason` exists so that somebody suspended
                         * mid-shift is told why, and it was threaded all the way
                         * through the sign-out and then never passed to a screen.
                         * The one case it was built for looked exactly like a
                         * session that had quietly expired.
                         */
                        signedOutReason: signedOut.reason
                    )

                case let blocked as SessionBlocked:
                    BlockedGate(
                        status: blocked.status,
                        reason: blocked.user.provisioningError,
                        onSignOut: sessionViewModel.onSignOut
                    )

                /*
                 * Signed in, and the account could not be read. NOT a sign-out —
                 * see Session.Unreachable. A transient failure heals itself and
                 * this screen disappears on its own; a rules refusal does not,
                 * which is what the retry is for.
                 */
                case is SessionUnreachable:
                    UnreachableGate(
                        onRetry: sessionViewModel.onRetrySession,
                        onSignOut: sessionViewModel.onSignOut
                    )

                case let active as SessionActive:
                    MainTabView(
                        user: active.user,
                        onSignOut: sessionViewModel.onSignOut
                    )
                    // One shell per person: somebody else signing in starts afresh.
                    .id(active.user.uid)

                default:
                    SplashView()
                }
                }
        }
        .environmentObject(theme)
        /*
         * Ticks rather than waiting on one fixed delay, because both answers
         * can arrive at any point and the hand-off should land within a frame
         * or two of the later one.
         */
        .task {
            while !ready && !Task.isCancelled {
                try? await Task.sleep(nanoseconds: UInt64(Startup.shared.TICK_MS) * 1_000_000)
                elapsed += Startup.shared.TICK_MS
            }
        }
        .background(theme.colors.bg.ignoresSafeArea())
        .tint(theme.colors.primary)
        // The look is light only, and the system chrome follows it too: the
        // navigation bar, the keyboard, alerts.
        .preferredColorScheme(.light)
    }
}

/// Signed in, and we could not find out whether the account is usable.
///
/// NOT THE SIGN-IN SCREEN, which is where this used to land somebody. Reading
/// the account document can fail for reasons that have nothing to do with the
/// account — a cold start underground, a token whose claims are a second late
/// — and the old code read every one of them as "you were never registered"
/// and asked a worker at a doorstep for a password they did not have on them.
///
/// TWO WAYS OUT, because the two failures differ. A dropped listener comes
/// back by itself and this screen vanishes; one the rules refused never
/// retries, so the retry rebuilds the whole chain — including the forced token
/// refresh.
private struct UnreachableGate: View {
    @EnvironmentObject private var theme: AppTheme
    let onRetry: () -> Void
    let onSignOut: () -> Void

    var body: some View {
        GateLayout(title: theme.tr("auth.unreachable"), detail: nil, waiting: false) {
            AppButton(title: theme.tr("common.action.retry"), action: onRetry)
            AppButton(title: theme.tr("common.nav.logOut"), tone: .outline, action: onSignOut)
        }
    }
}

/// Signed in, but not usable yet, or not any more. Said plainly.
private struct BlockedGate: View {
    @EnvironmentObject private var theme: AppTheme
    let status: UserStatus
    let reason: String?
    let onSignOut: () -> Void

    var body: some View {
        GateLayout(title: title, detail: reason, waiting: status == UserStatus.pendingProvisioning) {
            AppButton(title: theme.tr("common.nav.logOut"), tone: .outline, action: onSignOut)
        }
    }

    private var title: String {
        if status == UserStatus.suspended { return theme.tr("auth.err.suspended") }
        if status == UserStatus.provisioningFailed { return theme.tr("common.state.errorTitle") }
        return theme.tr("common.state.loadingPage")
    }
}

/// The logo, one sentence, and the way out.
private struct GateLayout<Actions: View>: View {
    @EnvironmentObject private var theme: AppTheme
    let title: String
    let detail: String?
    let waiting: Bool
    @ViewBuilder let actions: Actions

    var body: some View {
        VStack(spacing: 20) {
            LogoMark(size: 56)
            Text(title)
                .font(theme.type.subheading)
                .foregroundStyle(theme.colors.ink)
                .multilineTextAlignment(.center)
            if waiting {
                ProgressView().tint(theme.colors.primary)
            }
            if let detail {
                Text(detail)
                    .font(theme.type.small)
                    .foregroundStyle(theme.colors.inkMuted)
                    .multilineTextAlignment(.center)
            }
            VStack(spacing: 10) { actions }
                .frame(maxWidth: 320)
                .padding(.top, 4)
        }
        .padding(28)
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .background(theme.colors.bg.ignoresSafeArea())
    }
}
