package com.humblesolutions.ainotetaker.repository

import com.humblesolutions.ainotetaker.util.Cancellable

/**
 * Signing in, and knowing who is signed in.
 *
 * ONE DOOR. Every role — Super Admin down to Booth Member — signs in with a
 * username and a password. There used to be two: email for office roles, a
 * phone OTP for field roles. The OTP door is gone because it cost money per
 * sign-in, required the handset in the worker's own hand at that moment, and
 * failed in the places this app is actually used. The email door is gone
 * because a booth member has no email and never did.
 *
 * THE PHONE NUMBER SURVIVES AS CONTACT DETAIL, not as a credential, and is
 * still unique across the organisation — see `Credentials` for why those are
 * now two different jobs.
 *
 * WHAT FIREBASE SEES is `username@tas.invalid` and a password. The client
 * never handles that address: callers pass a username, and the mapping lives
 * in one place on each side.
 */
interface AuthRepository {

    /** The signed-in uid, or null. Cheap and synchronous — claims come separately. */
    suspend fun currentUid(): String?

    /**
     * The caller's custom claims: role, level, status, scope and ancestors.
     *
     * `forceRefresh` matters more than it looks. A brand-new user's first token
     * has NO claims — provisioning mints them after the account is made — so
     * the first read after sign-in must force a refresh or the app decides the
     * person has no role and bounces them out.
     */
    suspend fun claims(forceRefresh: Boolean = false): Map<String, Any?>

    /** Fires on sign-in, sign-out, and token refresh. */
    fun observeAuthState(onChange: (uid: String?) -> Unit): Cancellable

    /**
     * The only way in.
     *
     * Takes a USERNAME, not an address. Implementations map it through
     * `Credentials.emailFor` so that the synthetic domain stays an
     * implementation detail of the identity layer rather than something the
     * sign-in screen has to know about.
     *
     * RETURNS THE FAILURE RATHER THAN THROWING IT, and null when it worked.
     * A wrong password is an ORDINARY OUTCOME of a sign-in screen, not an
     * exceptional one — but the deciding reason is that only the platform can
     * see Firebase's error code, and a Kotlin exception thrown from the Swift
     * side does not survive the trip back into Kotlin with its type intact.
     * A returned enum does, on both platforms, with nothing to get wrong.
     *
     * Implementations still MAY throw for anything they could not classify;
     * `SignInUseCase` reads the message as a last resort.
     */
    suspend fun signInWithUsername(
        username: String,
        password: String,
    ): AuthFailureReason?

    suspend fun signOut()
}

/**
 * Changing somebody's password.
 *
 * A SEPARATE INTERFACE BECAUSE IT IS A SEPARATE TRUST BOUNDARY. Signing in is
 * something a client does for itself; setting a password is something done TO
 * another account, and the rules about who may do it to whom are enforced
 * server-side inside the callable. Nothing here decides anything — it asks.
 */
interface PasswordAdminRepository {

    /**
     * Set another user's password. Super Admin may do this to anyone; Admin to
     * anyone except a Super Admin; nobody else at all.
     *
     * The UI hides the option for people who cannot use it, but the decision
     * that matters is the one the function makes from the caller's token.
     */
    suspend fun setPassword(uid: String, newPassword: String)
}

/**
 * Asking for this account to be deleted.
 *
 * REQUIRED BY APP REVIEW, not merely a courtesy. Guideline 5.1.1(v): an app
 * that supports account creation must let a person START deletion from inside
 * the app. Pointing them at the form on the website does not satisfy it.
 *
 * IT SENDS NOTHING BUT A REASON, and that is the whole interface. The server
 * reads name, phone, username, role and scope from the caller's own `users`
 * document, so the row that reaches the queue names the right account by
 * construction — there is no field here a tampered client could fill in to
 * point an operator at somebody else's record.
 *
 * IT REQUESTS; IT DOES NOT DELETE. Unpicking an account means rewriting
 * `ancestors` on everybody below it, releasing the `phoneIndex` claim and
 * correcting the counters. Doing that from a phone tap with no way back is how
 * a ward loses its records — so a person works the queue, which is what Apple
 * asks for: deletion *initiated* in the app, allowed to take time.
 */
interface AccountDeletionRepository {

    /**
     * Files the request, or folds it into the one already open.
     *
     * Returns true when a request for this account was already waiting — the
     * screen says so rather than claiming a second one was filed, because
     * somebody who taps twice should not be told two different things.
     */
    suspend fun requestDeletion(reason: String?): Boolean
}

/**
 * Why a sign-in did not work, decided where the answer is actually known.
 *
 * THE PLATFORM CLASSIFIES; THE USE CASE TRANSLATES. Firebase reports failures
 * as an error CODE and a developer-facing English sentence, and only the
 * platform SDK sees the code. Shared code used to sniff the sentence, which
 * fails the moment Google rewords one — and Google had already reworded the
 * two that matter most:
 *
 *  - Rate limiting says "We have blocked all requests from this device due to
 *    unusual activity", which contains neither "too many" nor
 *    "too-many-requests". It was being reported to workers as a wrong
 *    password, so they kept trying, which is the one thing that makes a rate
 *    limit worse.
 *  - A disabled account says "The user account has been disabled by an
 *    administrator" and was reported the same way, so a suspended worker was
 *    told their password was wrong and re-typed it until they rang the office.
 *
 * Matching on this enum instead means a reworded sentence changes nothing.
 */
enum class AuthFailureReason {
    /** The username and password do not go together. Never says which. */
    WRONG_CREDENTIALS,

    /** There is no account with that username. */
    NO_SUCH_USER,

    /** The Auth account is disabled — suspended, or shut down by the office. */
    ACCOUNT_DISABLED,

    /** Firebase is rate-limiting this device. Trying again immediately is worse. */
    TOO_MANY_ATTEMPTS,

    /** The request never reached Firebase. */
    OFFLINE,

    /**
     * Something else. NOT folded into [WRONG_CREDENTIALS], because telling
     * somebody their password is wrong when the project's sign-in provider is
     * switched off costs them every retry they have before the rate limit.
     */
    UNKNOWN,
}

