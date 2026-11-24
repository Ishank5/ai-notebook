package com.humblesolutions.ainotetaker

import com.humblesolutions.ainotetaker.i18n.Language
import com.humblesolutions.ainotetaker.i18n.Strings
import com.humblesolutions.ainotetaker.model.Credentials
import com.humblesolutions.ainotetaker.model.SignOutReason
import com.humblesolutions.ainotetaker.repository.AuthFailureReason
import com.humblesolutions.ainotetaker.repository.AuthRepository
import com.humblesolutions.ainotetaker.usecase.SignInUseCase
import com.humblesolutions.ainotetaker.util.Cancellable
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Signing in, and every way it goes wrong.
 *
 * ALL OF THESE FAILED BEFORE THEY WERE FIXED, and none of them announced
 * itself: each one reached a field worker as "that username and password do
 * not match" about a password that was correct, and the only visible symptom
 * was somebody typing it again.
 */
class SignInTest {

    /**
     * @param refuses what the platform classified the failure as, when it could.
     * @param throws what the platform threw when it could NOT — the last-resort
     *   path, for something that failed below the repository.
     */
    private class StubAuth(
        private val refuses: AuthFailureReason? = null,
        private val throws: Throwable? = null,
    ) : AuthRepository {
        var signedInAs: String? = null
        override suspend fun currentUid(): String? = null
        override suspend fun claims(forceRefresh: Boolean): Map<String, Any?> = emptyMap()
        override fun observeAuthState(onChange: (String?) -> Unit) = Cancellable {}
        override suspend fun signInWithUsername(
            username: String,
            password: String,
        ): AuthFailureReason? {
            throws?.let { throw it }
            if (refuses != null) return refuses
            signedInAs = username
            return null
        }
        override suspend fun signOut() = Unit
    }

    // ---- the classified failures -------------------------------------------

    @Test
    fun a_rate_limit_is_not_reported_as_a_wrong_password() = runTest {
        /*
         * THE ONE THAT MATTERS MOST. Firebase's own wording is "We have
         * blocked all requests from this device due to unusual activity",
         * which contains neither "too many" nor "too-many-requests" — so the
         * old matcher fell through to wrongCredentials and the worker kept
         * trying, which is the single thing that makes a rate limit worse.
         */
        val useCase = SignInUseCase(StubAuth(AuthFailureReason.TOO_MANY_ATTEMPTS))
        assertEquals("auth.tooManyAttempts", useCase.execute("first.last", "supersecret1"))
    }

    @Test
    fun a_disabled_account_is_not_reported_as_a_wrong_password() = runTest {
        // A suspended worker told their password is wrong re-types it until
        // they ring the office. Saying it plainly saves that call.
        val useCase = SignInUseCase(StubAuth(AuthFailureReason.ACCOUNT_DISABLED))
        assertEquals("auth.accountDisabled", useCase.execute("first.last", "supersecret1"))
    }

    @Test
    fun an_unknown_failure_does_not_accuse_the_password() = runTest {
        /*
         * The provider being switched off, a clock skew, an SDK error: all
         * used to read as a wrong password, burning a worker's attempts until
         * the rate limiter stopped them for real.
         */
        val useCase = SignInUseCase(StubAuth(throws = IllegalStateException("something unhelpful")))
        assertEquals("auth.cannotSignIn", useCase.execute("first.last", "supersecret1"))
    }

    @Test
    fun every_classified_reason_has_its_own_message() = runTest {
        val keys = AuthFailureReason.entries.map { reason ->
            SignInUseCase(StubAuth(reason)).execute("first.last", "supersecret1")
        }
        assertTrue(keys.none { it == null }, "a failure signed somebody in")
        // WRONG_CREDENTIALS and UNKNOWN must not collapse into one another.
        assertEquals(keys.size, keys.toSet().size, "two reasons share a message: $keys")
    }

    // ---- the fallback, for failures nothing classified ---------------------

    @Test
    fun firebases_own_wording_is_still_understood_when_nothing_classified_it() = runTest {
        /*
         * The typed reason is the mechanism; this is the safety net for a
         * throw from below the repository. The strings are the ones the two
         * SDKs actually produce.
         */
        val cases = mapOf(
            "We have blocked all requests from this device due to unusual activity. Try again later."
                to "auth.tooManyAttempts",
            "The user account has been disabled by an administrator."
                to "auth.accountDisabled",
            "There is no user record corresponding to this identifier."
                to "auth.noSuchUser",
            "A network error (such as timeout, interrupted connection or unreachable host) has occurred."
                to "auth.offline",
            "The password is invalid or the user does not have a password."
                to "auth.wrongCredentials",
        )
        for ((message, expected) in cases) {
            val useCase = SignInUseCase(StubAuth(throws = RuntimeException(message)))
            assertEquals(expected, useCase.execute("first.last", "supersecret1"), message)
        }
    }

    // ---- what reaches the identity layer -----------------------------------

    @Test
    fun a_pasted_username_signs_in_the_same_on_both_platforms() = runTest {
        /*
         * Android trimmed with Kotlin's `trim()`, iOS with `.whitespaces`,
         * which leaves a newline behind — so a username pasted out of a
         * message app worked on one platform and was refused on the other,
         * and the screen could only say "no account with that username".
         */
        val auth = StubAuth()
        val useCase = SignInUseCase(auth)
        assertNull(useCase.execute("  ANSH.Bajaj\n", "supersecret1"))
        assertEquals("first.last", auth.signedInAs)
    }

    @Test
    fun a_username_with_a_stray_space_is_not_a_missing_account() = runTest {
        val auth = StubAuth()
        assertNull(SignInUseCase(auth).execute("ansh .bajaj", "supersecret1"))
        assertEquals("first.last", auth.signedInAs)
    }

    @Test
    fun sanitising_never_invents_an_account_that_could_not_exist() {
        // Capping at the maximum is a refusal the server would reach anyway.
        assertEquals(Credentials.MAX_USERNAME, Credentials.sanitiseUsername("a".repeat(40)).length)
        assertTrue(Credentials.isValidUsername(Credentials.sanitiseUsername("Ansh.Bajaj")))
    }

    @Test
    fun a_blank_box_never_reaches_the_network() = runTest {
        val auth = StubAuth()
        assertEquals("auth.wrongCredentials", SignInUseCase(auth).execute("   ", "supersecret1"))
        assertEquals("auth.wrongCredentials", SignInUseCase(auth).execute("first.last", ""))
        assertNull(auth.signedInAs, "a blank box was sent to Firebase")
    }

    // ---- the reason the sign-in screen shows -------------------------------

    @Test
    fun every_sign_out_reason_worth_explaining_has_a_translated_message() {
        for (reason in SignOutReason.entries) {
            val key = reason.messageKey ?: continue
            for (language in Language.entries) {
                val text = Strings.get(key, language)
                assertNotEquals(key, text, "${language.code} has no text for $key")
            }
        }
    }

    @Test
    fun tapping_sign_out_says_nothing_on_the_screen_it_sends_you_to() {
        // Telling somebody who just tapped Sign out that they are signed out
        // is noise on the screen they were sent to.
        assertNull(SignOutReason.USER_REQUESTED.messageKey)
    }

    @Test
    fun a_suspension_still_explains_itself_after_the_sign_out() {
        // The whole reason SignOutReason exists. It was computed, carried,
        // and then never reached a screen.
        assertEquals("auth.signedOutSuspended", SignOutReason.SUSPENDED.messageKey)
    }
}
