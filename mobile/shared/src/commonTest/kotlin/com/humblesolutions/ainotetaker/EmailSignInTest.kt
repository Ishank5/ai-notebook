package com.humblesolutions.ainotetaker

import com.humblesolutions.ainotetaker.model.Credentials
import com.humblesolutions.ainotetaker.repository.AuthFailureReason
import com.humblesolutions.ainotetaker.repository.AuthRepository
import com.humblesolutions.ainotetaker.usecase.SignInUseCase
import com.humblesolutions.ainotetaker.util.Cancellable
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Sign-in uses a real email address; usernames still work as before. */
class EmailSignInTest {

    @Test
    fun an_email_address_is_a_valid_login() {
        assertTrue(Credentials.isValidUsername("staff@example.test"))
        assertTrue(Credentials.isValidUsername("first.last@dept.example.test"))
        assertFalse(Credentials.isValidUsername("staff@"))
        assertFalse(Credentials.isValidUsername("@example.test"))
        assertFalse(Credentials.isValidUsername("staff@example"))
    }

    @Test
    fun an_email_address_is_cleaned_but_not_cut_to_a_username() {
        val long = "a.very.long.name.for.testing@department.example.test"
        assertEquals(long, Credentials.sanitiseUsername("  ${long.uppercase()} \n"))
        // Usernames are still capped as before.
        assertEquals(Credentials.MAX_USERNAME, Credentials.sanitiseUsername("x".repeat(40)).length)
    }

    @Test
    fun an_email_address_goes_to_firebase_as_typed() {
        assertEquals("staff@example.test", Credentials.emailFor("  Staff@Example.test "))
        assertEquals("worker99@tas.invalid", Credentials.emailFor("worker99"))
    }

    @Test
    fun sign_in_passes_the_email_through() = runTest {
        val auth = RecordingAuth()
        assertNull(SignInUseCase(auth).execute(" Staff@Example.test ", "supersecret1"))
        assertEquals("staff@example.test", auth.signedInAs)
    }

    private class RecordingAuth : AuthRepository {
        var signedInAs: String? = null
        override suspend fun currentUid(): String? = null
        override suspend fun claims(forceRefresh: Boolean): Map<String, Any?> = emptyMap()
        override fun observeAuthState(onChange: (uid: String?) -> Unit): Cancellable = Cancellable { }
        override suspend fun signInWithUsername(username: String, password: String): AuthFailureReason? {
            signedInAs = username
            return null
        }
        override suspend fun signOut() = Unit
    }
}
