import FirebaseAuth
import Foundation
import shared

/// Signing in with a username and a password.
///
/// NO APNs, NO SILENT PUSH, NO `canHandleNotification`. Phone sign-in on iOS
/// verified the DEVICE with a silent push, which is why this class used to
/// care about notification tokens and why sign-in could fail on a simulator,
/// on a device with notifications denied, or behind a captive portal — none of
/// which have anything to do with whether the person knows their credentials.
/// An email/password call is a plain network request.
///
/// THE SYNTHETIC ADDRESS NEVER LEAVES THIS FILE. Callers pass a username;
/// `Credentials.emailFor` turns it into what Firebase indexes on.
///
/// THE ERROR CODE NEVER LEAVES THIS FILE EITHER. This is the only place that
/// can see `AuthErrorCode`; shared code used to sniff the English message
/// instead and got the two failures that matter most wrong — see
/// `AuthFailureReason`.
final class IOSAuthRepository: AuthRepository {

    private let auth = Auth.auth()

    func currentUid() async throws -> String? { auth.currentUser?.uid }

    func claims(forceRefresh: Bool) async throws -> [String: Any] {
        guard let user = auth.currentUser else { return [:] }
        let result = try await user.getIDTokenResult(forcingRefresh: forceRefresh)
        return result.claims
    }

    func observeAuthState(onChange: @escaping (String?) -> Void) -> Cancellable {
        let handle = auth.addStateDidChangeListener { _, user in onChange(user?.uid) }
        return HandleCancellable { [weak auth] in auth?.removeStateDidChangeListener(handle) }
    }

    /// Returns the classified failure, or nil when it worked.
    ///
    /// RETURNED RATHER THAN THROWN, and the reason is this side of the bridge:
    /// a Kotlin exception thrown from Swift does not survive the trip back
    /// into Kotlin with its type intact, so a classification carried by one
    /// would arrive as an anonymous error and be read as a wrong password. An
    /// enum crosses cleanly.
    func signInWithUsername(
        username: String,
        password: String
    ) async throws -> AuthFailureReason? {
        do {
            _ = try await auth.signIn(
                withEmail: Credentials.shared.emailFor(username: username),
                password: password
            )
            return nil
        } catch {
            return Self.classify(error)
        }
    }

    func signOut() async throws {
        // the reels feature is removed, so there are no reels to clear here.
        try auth.signOut()
    }

    /// Firebase's answer, classified where the code is actually visible.
    ///
    /// THE CODE, NOT THE MESSAGE. `localizedDescription` is a sentence Google
    /// writes for developers and rewords without notice — and it had already
    /// reworded the rate limit to "We have blocked all requests from this
    /// device due to unusual activity", which matches none of the words
    /// anybody would think to look for.
    private static func classify(_ error: Error) -> AuthFailureReason {
        let ns = error as NSError
        switch ns.code {
        case AuthErrorCode.userNotFound.rawValue:
            return .noSuchUser

        case AuthErrorCode.userDisabled.rawValue:
            return .accountDisabled

        case AuthErrorCode.tooManyRequests.rawValue:
            return .tooManyAttempts

        case AuthErrorCode.networkError.rawValue:
            return .offline

        /*
         * `invalidCredential` is what modern Firebase returns for BOTH a wrong
         * password and an unknown account, when email enumeration protection
         * is on. That is the behaviour we want and say we want — see the note
         * in SignInUseCase on not telling an attacker which usernames exist.
         *
         * `invalidEmail` belongs here too: the address is built from the
         * username by one function, so a malformed one means the username was
         * never going to match an account.
         */
        case AuthErrorCode.invalidCredential.rawValue,
             AuthErrorCode.wrongPassword.rawValue,
             AuthErrorCode.invalidEmail.rawValue:
            return .wrongCredentials

        default:
            /*
             * NSURLErrorDomain, not FIRAuthErrorDomain: a request that never
             * left the phone fails below Firebase and carries none of its
             * codes. Without this, a worker with no signal was told their
             * password was wrong.
             */
            if ns.domain == NSURLErrorDomain { return .offline }

            /*
             * Everything else — the provider switched off, an expired API key,
             * an internal error — is UNKNOWN rather than a wrong password.
             * Calling it a wrong password sends a worker to re-type
             * credentials that were right, burning their attempts until the
             * rate limiter stops them for real.
             */
            return .unknown
        }
    }
}

/// Turns a Swift closure into the shared `Cancellable`.
///
/// NOT PRIVATE, and it lives here rather than in a util file because it always
/// has: every Firestore listener in the app wraps its registration in one.
final class HandleCancellable: Cancellable {
    private let onCancel: () -> Void
    init(_ onCancel: @escaping () -> Void) { self.onCancel = onCancel }
    func cancel() { onCancel() }
}
