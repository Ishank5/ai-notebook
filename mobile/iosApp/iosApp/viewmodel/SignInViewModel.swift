import Foundation
import shared

/// One screen, two boxes.
///
/// WHAT THIS USED TO BE: a two-door state machine — pick email or phone, send
/// a code, run a resend countdown, then redeem it. A username and a password
/// is a single request, so the whole machine collapses to a flag and an error
/// key.
@MainActor
final class SignInViewModel: ObservableObject {

    @Published private(set) var username = ""
    @Published private(set) var password = ""
    @Published private(set) var busy = false
    /// A string key, never a Firebase message — see `SignInUseCase`.
    @Published private(set) var errorKey: String?
    /// Whether this person has typed anything yet. The screen may arrive
    /// carrying a reason it was shown; that notice is about the LAST session,
    /// so it stops being true the moment somebody starts on the next one.
    @Published private(set) var touched = false
    /// True while the password is shown in the clear. Never survives a failure.
    @Published private(set) var passwordVisible = false

    var canSubmit: Bool { !username.isEmpty && !password.isEmpty && !busy }

    private let signIn = SignInUseCase(authRepository: IOSAuthRepository())

    /// The request in flight, so it can be cancelled when the screen goes.
    private var task: Task<Void, Never>?

    deinit { task?.cancel() }

    /// A METHOD, NOT A `didSet` ON A `@Published`.
    ///
    /// The sanitising used to live in a property observer that assigned back
    /// to the property it was observing, while SwiftUI held a binding to it.
    /// That publishes twice for one keystroke and rewrites the text field's
    /// contents from underneath the caret mid-edit, which is how characters
    /// get dropped under fast typing. The view binds a plain `Binding` to this
    /// instead, so exactly one value is ever published per keystroke.
    ///
    /// `Credentials` owns the rule so the two platforms cannot disagree about
    /// what a pasted username becomes — they did, and only one of them
    /// stripped a trailing newline.
    func setUsername(_ value: String) {
        username = Credentials.shared.sanitiseUsername(raw: value)
        errorKey = nil
        touched = true
    }

    func setPassword(_ value: String) {
        password = value
        errorKey = nil
        touched = true
    }

    func togglePasswordVisible() { passwordVisible.toggle() }

    func onSubmit() {
        /*
         * BUSY IS RAISED HERE, NOT INSIDE THE TASK.
         *
         * `canSubmit` was the only guard and `busy` was set in the first line
         * of the `Task` body, which does not run until the next turn of the
         * main actor — so two taps a frame apart both passed the guard and the
         * app made two sign-in requests. On a rate-limited endpoint that is
         * not merely wasteful: it is half of everybody's allowance, spent on a
         * double tap.
         */
        guard canSubmit else { return }
        busy = true
        errorKey = nil

        task?.cancel()
        task = Task { [weak self] in
            guard let self else { return }
            let key: String?
            do {
                key = try await self.signIn.execute(
                    username: self.username,
                    password: self.password
                )
            } catch is CancellationError {
                /*
                 * The screen went away. NOT a failure, and the flag must still
                 * come down — see below.
                 */
                self.busy = false
                return
            } catch {
                /*
                 * `try?` USED TO SWALLOW THIS, and the result was the worst
                 * outcome a sign-in screen has: the spinner stopped, no
                 * message appeared, and nothing happened. Somebody tapping a
                 * button that visibly does nothing taps it again, and again.
                 */
                key = "auth.cannotSignIn"
            }

            /*
             * THE FLAG COMES DOWN ON EVERY PATH. It used to be lowered by the
             * last line of the Task, which a cancellation or a throw never
             * reached — leaving `busy` true, the button disabled, and the
             * screen permanently unusable until the app was force-quit.
             */
            self.busy = false
            self.errorKey = key
            /*
             * THE PASSWORD NEVER SURVIVES AN ATTEMPT, and on success neither
             * does the username.
             *
             * SwiftUI rebuilds this object when the sign-in screen comes back,
             * so iOS does not have Android's shared-handset leak — where an
             * Activity-scoped view model handed the next person the last
             * person's credentials. Clearing here anyway, because two
             * platforms that forget a password at different moments is a
             * difference somebody will eventually rely on.
             *
             * On a FAILURE the username stays: a wrong password re-typed is
             * the ordinary case, and making somebody re-enter a username they
             * got right is a tax on the commonest mistake.
             */
            self.password = ""
            self.passwordVisible = false
            if key == nil {
                self.username = ""
                self.touched = false
            }
        }
    }
}
