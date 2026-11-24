import Foundation
import shared

/// Who is signed in, for the whole app.
///
/// ONE INSTANCE, held by the root view, because every screen asks the same
/// question and a second subscription would ask the API twice for no gain.
@MainActor
final class SessionViewModel: ObservableObject {

    @Published private(set) var session: Session = SessionLoading()
    // The app is English only.
    let language: Language = LanguageStore.load()
    @Published var appearance: Appearance = AppearanceStore.load()

    // Manual DI — each view model builds its own chain, no framework.
    private let authRepository = IOSAuthRepository()
    // Who someone is comes from the API (GET /api/me).
    private let userRepository = ApiUserRepository()
    private let signOutUseCase: SignOutUseCase
    private var subscription: Cancellable?

    init() {
        signOutUseCase = SignOutUseCase(authRepository: authRepository)
        subscribe()
    }

    private func subscribe() {
        let observe = ObserveSessionUseCase(
            authRepository: authRepository,
            userRepository: userRepository
        )
        subscription = observe.execute { [weak self] next in
            Task { @MainActor in
                guard let self else { return }
                self.session = next
            }
        }
    }

    /// Ask again, after the account could not be read.
    ///
    /// A RESUBSCRIBE, which asks the API again at once rather than waiting for
    /// the repository's own next try. Tearing the chain down and rebuilding it
    /// also re-runs the forced token refresh, which is the thing most likely
    /// to have been missing.
    func onRetrySession() {
        subscription?.cancel()
        session = SessionLoading()
        subscribe()
    }

    deinit {
        subscription?.cancel()
    }

    func onAppearanceChosen(_ next: Appearance) {
        appearance = next
        AppearanceStore.save(next)
    }

    func onSignOut() {
        Task {
            // A tap belongs to the session it was made in; see Pending.
            await Notifications.Pending.shared.clear()
            try? await signOutUseCase.execute()
        }
    }
}
