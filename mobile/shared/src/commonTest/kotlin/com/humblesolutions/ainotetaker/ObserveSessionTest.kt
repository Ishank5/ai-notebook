package com.humblesolutions.ainotetaker

import com.humblesolutions.ainotetaker.model.Role
import com.humblesolutions.ainotetaker.model.Session
import com.humblesolutions.ainotetaker.model.SignOutReason
import com.humblesolutions.ainotetaker.model.User
import com.humblesolutions.ainotetaker.model.UserStatus
import com.humblesolutions.ainotetaker.repository.AuthRepository
import com.humblesolutions.ainotetaker.repository.UserDoc
import com.humblesolutions.ainotetaker.repository.UserRepository
import com.humblesolutions.ainotetaker.usecase.ObserveSessionUseCase
import com.humblesolutions.ainotetaker.util.Cancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The session, and the three ways it used to end for no reason.
 *
 * THE BUG THIS FILE EXISTS FOR: the account listener reports "there is no such
 * document" and "I could not read it" through the same null, and the session
 * treated both as "you were never registered" — so a tunnel, a cold offline
 * start, or a rules refusal one second after provisioning all threw a worker
 * back to the sign-in screen and asked for a password they were standing at a
 * doorstep without.
 */
class ObserveSessionTest {

    // ---- doubles ------------------------------------------------------------

    private class FakeAuth : AuthRepository {
        var listener: ((String?) -> Unit)? = null
        var forcedRefreshes = 0
        var signedOut = false

        override suspend fun currentUid(): String? = null
        override suspend fun claims(forceRefresh: Boolean): Map<String, Any?> {
            if (forceRefresh) forcedRefreshes += 1
            return emptyMap()
        }
        override fun observeAuthState(onChange: (String?) -> Unit): Cancellable {
            listener = onChange
            return Cancellable { listener = null }
        }
        override suspend fun signInWithUsername(
            username: String,
            password: String,
        ): com.humblesolutions.ainotetaker.repository.AuthFailureReason? = null
        override suspend fun signOut() { signedOut = true }

        fun emit(uid: String?) = listener?.invoke(uid)
    }

    private class FakeUsers : UserRepository by Unimplemented() {
        var emit: ((UserDoc) -> Unit)? = null
        override fun observeUserDocument(uid: String, onChange: (UserDoc) -> Unit): Cancellable {
            emit = onChange
            return Cancellable { emit = null }
        }
    }

    /**
     * Everything on [UserRepository] this test does not touch.
     *
     * Delegation rather than eighteen stubbed methods, so adding one to the
     * interface does not edit this file.
     */
    private class Unimplemented : UserRepository {
        private fun no(): Nothing = throw NotImplementedError("not used by this test")
        override suspend fun getUser(uid: String): User? = no()
        override fun observeUser(uid: String, onChange: (User?) -> Unit) = no()
        override fun observeUserDocument(uid: String, onChange: (UserDoc) -> Unit) = no()
        override fun observeDirectReports(
            parentId: String, viewerUid: String, limit: Int, onChange: (List<User>) -> Unit,
        ) = no()
        override suspend fun subtreeOf(uid: String, limit: Int): List<User> = no()
        override fun observeCreatedBy(
            viewerUid: String, limit: Int, onChange: (List<User>?) -> Unit,
        ): Cancellable = no()
        override suspend fun countsByRole(uid: String): Map<Role, Int> = no()
        override suspend fun createUser(user: User, password: String): String = no()
        override suspend fun updateUser(uid: String, changes: Map<String, Any?>) = no()
        override suspend fun findDuplicateByPhone(phone: String, callerUid: String): User? = no()
        override suspend fun searchTeam(callerUid: String, term: String, limit: Int): List<User> = no()
        override suspend fun peopleIn(
            localityId: String, viewerUid: String, limit: Int,
        ): List<User> = no()
    }

    private fun activeUser() = User(
        uid = "u1", role = Role.BOOTH_LEADER, status = UserStatus.ACTIVE, name = "Baani",
    )

    /** Collects what the use case emits, from whichever thread it emits on. */
    private class Sink {
        val channel = Channel<Session>(Channel.UNLIMITED)
        val onChange: (Session) -> Unit = { channel.trySend(it) }
        suspend fun next(): Session = withTimeout(5_000) { channel.receive() }
        suspend fun settle(predicate: (Session) -> Boolean): Session {
            // Loading is emitted on the way to every answer; skip past it.
            while (true) {
                val next = next()
                if (predicate(next)) return next
            }
        }
    }

    // ---- the fixes ----------------------------------------------------------

    @Test
    fun a_read_that_failed_does_not_sign_anybody_out() = runTest {
        /*
         * Offline with a cold cache, or a listener the backend dropped. The
         * Auth session is kept and the screen offers a retry — signing out
         * here costs somebody their credentials at the one moment they cannot
         * re-type them.
         */
        val auth = FakeAuth()
        val users = FakeUsers()
        val sink = Sink()
        val subscription = ObserveSessionUseCase(auth, users).execute(sink.onChange)

        auth.emit("u1")
        withTimeout(5_000) { while (users.emit == null) kotlinx.coroutines.yield() }
        users.emit!!(UserDoc.Failed("unavailable"))

        val session = sink.settle { it !is Session.Loading }
        assertTrue(session is Session.Unreachable, "a failed read produced $session")
        assertEquals("u1", session.uid)
        assertTrue(!auth.signedOut, "a failed read signed the person out of Auth")
        subscription.cancel()
    }

    @Test
    fun an_account_that_is_genuinely_gone_still_ends_the_session() = runTest {
        // The distinction only earns its keep if the other branch still works.
        val auth = FakeAuth()
        val users = FakeUsers()
        val sink = Sink()
        val subscription = ObserveSessionUseCase(auth, users).execute(sink.onChange)

        auth.emit("u1")
        withTimeout(5_000) { while (users.emit == null) kotlinx.coroutines.yield() }
        users.emit!!(UserDoc.Absent)

        val session = sink.settle { it !is Session.Loading }
        assertTrue(session is Session.SignedOut, "an absent account produced $session")
        assertEquals(SignOutReason.NOT_REGISTERED, session.reason)
        subscription.cancel()
    }

    @Test
    fun the_token_is_refreshed_before_the_account_is_read() = runTest {
        /*
         * A brand-new user's FIRST token carries no claims — provisioning
         * mints them after the document is written — so the rules refuse the
         * read and the app concluded the person had no account. This file
         * described the refresh in a comment and never performed it.
         */
        val auth = FakeAuth()
        val users = FakeUsers()
        val subscription = ObserveSessionUseCase(auth, users).execute { }

        auth.emit("u1")
        withTimeout(5_000) { while (users.emit == null) kotlinx.coroutines.yield() }

        assertEquals(1, auth.forcedRefreshes, "the token was not force-refreshed")
        subscription.cancel()
    }

    @Test
    fun a_working_account_still_signs_in() = runTest {
        val auth = FakeAuth()
        val users = FakeUsers()
        val sink = Sink()
        val subscription = ObserveSessionUseCase(auth, users).execute(sink.onChange)

        auth.emit("u1")
        withTimeout(5_000) { while (users.emit == null) kotlinx.coroutines.yield() }
        users.emit!!(UserDoc.Present(activeUser()))

        val session = sink.settle { it !is Session.Loading }
        assertTrue(session is Session.Active, "an active account produced $session")
        subscription.cancel()
    }

    @Test
    fun a_suspension_blocks_rather_than_bounces() = runTest {
        val auth = FakeAuth()
        val users = FakeUsers()
        val sink = Sink()
        val subscription = ObserveSessionUseCase(auth, users).execute(sink.onChange)

        auth.emit("u1")
        withTimeout(5_000) { while (users.emit == null) kotlinx.coroutines.yield() }
        users.emit!!(UserDoc.Present(activeUser().copy(status = UserStatus.SUSPENDED)))

        val session = sink.settle { it !is Session.Loading }
        assertTrue(session is Session.Blocked, "a suspension produced $session")
        assertEquals(UserStatus.SUSPENDED, session.status)
        subscription.cancel()
    }

    @Test
    fun a_suspension_still_explains_itself_once_the_sign_out_lands() = runTest {
        val auth = FakeAuth()
        val users = FakeUsers()
        val sink = Sink()
        val subscription = ObserveSessionUseCase(auth, users).execute(sink.onChange)

        auth.emit("u1")
        withTimeout(5_000) { while (users.emit == null) kotlinx.coroutines.yield() }
        users.emit!!(UserDoc.Present(activeUser().copy(status = UserStatus.SUSPENDED)))
        sink.settle { it is Session.Blocked }

        auth.emit(null)
        val session = sink.settle { it is Session.SignedOut } as Session.SignedOut
        assertEquals(SignOutReason.SUSPENDED, session.reason)
        subscription.cancel()
    }
}
