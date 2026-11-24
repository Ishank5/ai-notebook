package com.humblesolutions.ainotetaker.repository

import com.google.firebase.FirebaseNetworkException
import com.google.firebase.FirebaseTooManyRequestsException
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseAuthException
import com.humblesolutions.ainotetaker.model.Credentials
import com.humblesolutions.ainotetaker.util.Cancellable
import kotlinx.coroutines.tasks.await

/**
 * Signing in with a username and a password.
 *
 * NO ACTIVITY, NO RECAPTCHA, NO APNs. Phone sign-in needed a live Activity to
 * hang device verification off, which is why this class used to take an
 * `activityProvider` and why signing in could fail for reasons that had
 * nothing to do with the person's credentials. An email/password call is a
 * plain network request, so all of that is gone.
 *
 * THE SYNTHETIC ADDRESS NEVER LEAVES THIS FILE. Callers pass a username;
 * `Credentials.emailFor` turns it into the thing Firebase indexes on. Keeping
 * the mapping here means no screen, view model or test has to know that the
 * identity layer thinks in email addresses.
 *
 * THE ERROR CODE NEVER LEAVES THIS FILE EITHER. This is the only place that
 * can see `FirebaseAuthException.errorCode`; shared code used to sniff the
 * English message instead and got the two failures that matter most wrong.
 * See [AuthFailureReason].
 */
class AndroidAuthRepository(
    private val auth: FirebaseAuth = FirebaseAuth.getInstance(),
) : AuthRepository {

    override suspend fun currentUid(): String? = auth.currentUser?.uid

    override suspend fun claims(forceRefresh: Boolean): Map<String, Any?> {
        val user = auth.currentUser ?: return emptyMap()
        return user.getIdToken(forceRefresh).await().claims
    }

    override fun observeAuthState(onChange: (String?) -> Unit): Cancellable {
        val listener = FirebaseAuth.AuthStateListener { onChange(it.currentUser?.uid) }
        auth.addAuthStateListener(listener)
        return Cancellable { auth.removeAuthStateListener(listener) }
    }

    override suspend fun signInWithUsername(
        username: String,
        password: String,
    ): AuthFailureReason? = try {
        auth.signInWithEmailAndPassword(Credentials.emailFor(username), password).await()
        null
    } catch (cancellation: kotlin.coroutines.cancellation.CancellationException) {
        // The screen went away mid-request. Not a failure, and not ours to
        // turn into one — see the note in SignInUseCase.
        throw cancellation
    } catch (error: Throwable) {
        error.classify()
    }

    override suspend fun signOut() {
        // the reels feature is removed, so there are no reels to clear here.
        auth.signOut()
    }
}

/**
 * Firebase's answer, classified where the code is actually visible.
 *
 * THE CODE, NOT THE MESSAGE. `FirebaseAuthException.message` is a sentence
 * Google writes for developers and rewords without notice — and it had already
 * reworded the rate limit to "We have blocked all requests from this device
 * due to unusual activity", which matches none of the words anybody would
 * think to look for. `errorCode` is the stable contract.
 */
private fun Throwable.classify(): AuthFailureReason {

    /*
     * THESE TWO ARE NOT `FirebaseAuthException`. They are thrown from the
     * layer below it, so a `when` over auth codes alone never sees them —
     * which is how a device that was rate-limited, and a phone with no signal,
     * both ended up reported as a wrong password.
     */
    if (this is FirebaseTooManyRequestsException) return AuthFailureReason.TOO_MANY_ATTEMPTS
    if (this is FirebaseNetworkException) return AuthFailureReason.OFFLINE

    return when ((this as? FirebaseAuthException)?.errorCode) {
        "ERROR_USER_NOT_FOUND" -> AuthFailureReason.NO_SUCH_USER

        "ERROR_USER_DISABLED" -> AuthFailureReason.ACCOUNT_DISABLED

        "ERROR_TOO_MANY_REQUESTS" -> AuthFailureReason.TOO_MANY_ATTEMPTS

        "ERROR_NETWORK_REQUEST_FAILED" -> AuthFailureReason.OFFLINE

        /*
         * ERROR_INVALID_CREDENTIAL is what modern Firebase returns for BOTH a
         * wrong password and an unknown account, when email enumeration
         * protection is on. That is the behaviour we want and say we want —
         * see the note in SignInUseCase on not telling an attacker which
         * usernames exist.
         *
         * ERROR_INVALID_EMAIL belongs here too: the address is built from the
         * username by one function, so a malformed one means the username was
         * never going to match an account.
         */
        "ERROR_INVALID_CREDENTIAL",
        "ERROR_INVALID_LOGIN_CREDENTIALS",
        "ERROR_WRONG_PASSWORD",
        "ERROR_INVALID_EMAIL",
        -> AuthFailureReason.WRONG_CREDENTIALS

        /*
         * Everything else — the provider switched off, an expired API key, an
         * internal error — is UNKNOWN rather than a wrong password. Calling it
         * a wrong password sends a worker to re-type credentials that were
         * right, burning their attempts until the rate limiter stops them for
         * real.
         */
        else -> AuthFailureReason.UNKNOWN
    }
}
