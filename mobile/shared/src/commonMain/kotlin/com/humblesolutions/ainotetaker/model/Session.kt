package com.humblesolutions.ainotetaker.model

/**
 * Who is using the app, and whether we know yet.
 *
 * LOADING IS A REAL STATE, not an absence of one. The app cannot tell a signed
 * out person from one whose token is still being read, and guessing either way
 * is wrong: guess signed-out and a returning user is thrown to the sign-in
 * screen every launch; guess signed-in and a stranger sees a dashboard skeleton
 * that never fills.
 */
sealed interface Session {

    /** Reading the token. Every screen shows its skeleton. */
    data object Loading : Session

    /** Nobody is signed in. [reason] survives the sign-out that caused it. */
    data class SignedOut(val reason: SignOutReason? = null) : Session

    /** Signed in, provisioned, and permitted. */
    data class Active(val user: User) : Session {
        val role: Role? get() = user.role
    }

    /**
     * Signed in, but the account is not usable — suspended, still provisioning,
     * or provisioning failed. Said plainly rather than bounced to sign-in,
     * which reads like the app losing the tap.
     */
    data class Blocked(val user: User, val status: UserStatus) : Session

    /**
     * Signed in, and we COULD NOT FIND OUT whether the account is usable.
     *
     * A SEPARATE STATE FROM "NOT REGISTERED", and keeping them apart is the
     * whole reason this exists. The account document listener reports a read
     * that failed and a document that is genuinely absent through the same
     * channel — a null — and the app used to treat both as "you were never
     * registered" and throw the person out.
     *
     * The failures are ordinary: a cold start on a dead connection with
     * nothing in the Firestore cache, a token whose claims have not been
     * minted yet, a rules refusal a second after provisioning. None of them
     * mean the account is gone, and signing somebody out over one costs them
     * their credentials at a doorstep — the exact moment they cannot re-type
     * a password.
     *
     * So: say the account could not be reached, keep the Auth session, and
     * offer a retry. A listener that failed transiently heals itself; one
     * refused by the rules needs the resubscribe that [Session.Unreachable]'s
     * screen offers.
     */
    data class Unreachable(val uid: String, val reason: String? = null) : Session
}

/**
 * Why a session ended, carried across the sign-out that follows.
 *
 * Without it the auth listener fires with null and the reason is lost, so
 * somebody suspended mid-shift is bounced to the sign-in screen with no
 * explanation and tries their number again, and again.
 */
enum class SignOutReason {
    /** They asked. */
    USER_REQUESTED,

    /** The account was suspended while they were using it. */
    SUSPENDED,

    /** Signed in to Auth, but no `users` document — never registered, or deleted. */
    NOT_REGISTERED,

    /** The provisioning trigger failed; the reason is on the document. */
    PROVISIONING_FAILED;

    /**
     * What the sign-in screen says about it, or null when there is nothing to
     * say.
     *
     * THE REASON USED TO BE COMPUTED AND THEN DROPPED. It was carried through
     * the sign-out with some care and never reached a screen, so the one case
     * it exists for — somebody suspended mid-shift landing back on sign-in —
     * looked exactly like a session that had quietly expired.
     *
     * USER_REQUESTED has no message on purpose: telling somebody who just
     * tapped Sign out that they are signed out is noise on the screen they
     * were sent to.
     */
    val messageKey: String?
        get() = when (this) {
            USER_REQUESTED -> null
            SUSPENDED -> "auth.signedOutSuspended"
            NOT_REGISTERED -> "auth.signedOutNotRegistered"
            PROVISIONING_FAILED -> "auth.signedOutProvisioningFailed"
        }
}
