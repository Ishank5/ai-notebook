package com.humblesolutions.ainotetaker.usecase

import com.humblesolutions.ainotetaker.model.Credentials
import com.humblesolutions.ainotetaker.repository.AuthFailureReason
import com.humblesolutions.ainotetaker.repository.AuthRepository
import kotlin.coroutines.cancellation.CancellationException

/**
 * Getting in.
 *
 * ONE STEP NOW. This used to be four use cases — start a phone verification,
 * confirm a code, sign in with an email, send a reset link — because there
 * were two doors and one of them was a two-stage handshake. A username and a
 * password is a single call, so the shape collapses to match.
 */
class SignInUseCase(private val authRepository: AuthRepository) {

    /**
     * Returns the key of a message to show, or null on success.
     *
     * A FAILURE IS TOLD IN THE APP'S OWN WORDS. Firebase reports
     * `ERROR_INVALID_CREDENTIAL` and similar, which is English, technical, and
     * addressed to a developer — three things a booth worker reading Punjabi
     * on a doorstep is not. Every branch here maps to a key the string table
     * translates.
     *
     * "WRONG USERNAME" AND "WRONG PASSWORD" ARE THE SAME MESSAGE on purpose
     * where Firebase lets them be: distinguishing them tells an attacker which
     * usernames exist. The one exception is a genuinely absent account, which
     * Firebase reports distinctly whatever we do, so pretending otherwise buys
     * nothing and costs a real worker the chance to spot a typo.
     *
     * AN UNRECOGNISED FAILURE IS NO LONGER CALLED A WRONG PASSWORD. It used to
     * be, and that is a lie with a cost: every unknown cause — the sign-in
     * provider switched off, a clock skew, an SDK error — sent a worker back
     * to re-type a password that was right, burning attempts until the rate
     * limiter stopped them for real.
     */
    suspend fun execute(username: String, password: String): String? {
        val cleaned = Credentials.sanitiseUsername(username)

        /*
         * BOTH BLANKS ARE ONE MESSAGE, and it is checked before the round trip.
         * The screen already refuses to submit an empty box; this is the
         * belt-and-braces for a caller that does not.
         */
        if (cleaned.isBlank() || password.isBlank()) return "auth.wrongCredentials"

        /*
         * A username that cannot exist is answered without asking. It is not
         * a leak — the rule is compiled into every copy of the app — and it
         * saves a round trip that would be spent to reach the same answer.
         */
        if (!Credentials.isValidUsername(cleaned)) return "auth.noSuchUser"

        return try {
            authRepository.signInWithUsername(cleaned, password)?.let { keyFor(it) }
        } catch (cancellation: CancellationException) {
            /*
             * NOT A FAILURE, AND RETHROWN RATHER THAN MAPPED. Catching
             * Throwable caught this too, so a screen that went away mid-request
             * came back showing "that username and password do not match"
             * about credentials Firebase never rejected.
             */
            throw cancellation
        } catch (error: Throwable) {
            keyFor(error)
        }
    }

    /**
     * A last-resort read of the message, for a throw nothing classified.
     *
     * THE STRING MATCHING IS A LAST RESORT, NOT THE MECHANISM — see
     * [AuthFailureReason], which the repository returns. This path is only
     * reached when something BELOW the repository threw: a transport error, a
     * coroutine adapter, an SDK that failed before it could be asked what went
     * wrong. A network blip should still read as one.
     */
    private fun keyFor(error: Throwable): String {
        val text = ((error.message ?: "") + " " + (error.cause?.message ?: "")).lowercase()
        return when {
            "no user record" in text || "user-not-found" in text ||
                "user_not_found" in text -> "auth.noSuchUser"

            "disabled" in text -> "auth.accountDisabled"

            // Firebase's own wording for a rate limit names neither.
            "too many" in text || "too-many-requests" in text ||
                "unusual activity" in text || "blocked all requests" in text ->
                "auth.tooManyAttempts"

            "network" in text || "unable to resolve host" in text ||
                "timeout" in text || "timed out" in text ||
                "internet connection" in text || "offline" in text -> "auth.offline"

            "password is invalid" in text || "invalid-login-credentials" in text ||
                "invalid_login_credentials" in text || "wrong-password" in text ||
                "malformed or has expired" in text -> "auth.wrongCredentials"

            else -> "auth.cannotSignIn"
        }
    }

    private fun keyFor(reason: AuthFailureReason): String = when (reason) {
        AuthFailureReason.WRONG_CREDENTIALS -> "auth.wrongCredentials"
        AuthFailureReason.NO_SUCH_USER -> "auth.noSuchUser"
        AuthFailureReason.ACCOUNT_DISABLED -> "auth.accountDisabled"
        AuthFailureReason.TOO_MANY_ATTEMPTS -> "auth.tooManyAttempts"
        AuthFailureReason.OFFLINE -> "auth.offline"
        AuthFailureReason.UNKNOWN -> "auth.cannotSignIn"
    }
}

/** Getting out. Unchanged by the move to usernames. */
class SignOutUseCase(private val authRepository: AuthRepository) {
    suspend fun execute() = authRepository.signOut()
}
