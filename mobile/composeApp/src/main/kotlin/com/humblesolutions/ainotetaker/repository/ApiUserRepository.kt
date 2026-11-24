package com.humblesolutions.ainotetaker.repository

import android.content.Context
import android.util.Log
import com.humblesolutions.ainotetaker.model.Role
import com.humblesolutions.ainotetaker.model.User
import com.humblesolutions.ainotetaker.model.UserStatus
import com.humblesolutions.ainotetaker.util.Cancellable
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject

/**
 * Who the signed-in person is, from the CRM: `GET /api/me`.
 *
 * TAS read a `users/{uid}` document in Firestore and kept
 * listening to it. The CRM keeps people in its own users table, and the phone
 * and the CRM website share one login, so the phone asks the API, the same
 * question the website asks. Only the signed-in person's own account is ever
 * read: everything else [UserRepository] offers belonged to TAS's team screens,
 * which this app does not have, and answers "nothing".
 *
 * WHAT THE ANSWERS MEAN, for the session ([UserDoc]):
 *  - 200: active, with the CRM role mapped by [Role.fromCrmRole].
 *  - 403 `inactive` or `no_profile`: a login turned off on the CRM, or one with
 *    no CRM user. Both show the suspended screen, which tells them to ask the
 *    CRM admin.
 *  - no answer, a 5xx, or a 401 even after a fresh token: [UserDoc.Failed],
 *    the "could not reach your account" screen with its retry.
 *
 * WHEN IT ASKS: on sign-in (the session subscribes), on that screen's retry
 * (it subscribes again), and when the app comes back to the foreground
 * ([refresh], from `MainActivity.onRestart`). A call that brought no answer is
 * tried again by itself, as a dropped Firestore listener healed itself.
 *
 * THE LAST ANSWER IS KEPT ON THE PHONE, as Firestore's cache kept the document,
 * so a meeting can still be recorded on a cold start with no signal. With
 * nothing kept for this person, no signal is the "could not reach" screen. And
 * once an answer has been given, a later call that brings none changes
 * nothing: somebody already in is not thrown out by a tunnel.
 */
class ApiUserRepository(context: Context) : UserRepository {

    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    override suspend fun getUser(uid: String): User? =
        (ask(uid) as? Asked.Answered)?.let { (it.doc as? UserDoc.Present)?.user }

    override fun observeUser(uid: String, onChange: (User?) -> Unit): Cancellable =
        observeUserDocument(uid) { doc -> onChange((doc as? UserDoc.Present)?.user) }

    override fun observeUserDocument(uid: String, onChange: (UserDoc) -> Unit): Cancellable {
        // an answer that cannot be read is logged and asked again, never a crash: this process may be
        // the one recording a meeting.
        val scope = CoroutineScope(
            SupervisorJob() + Dispatchers.IO +
                CoroutineExceptionHandler { _, e -> Log.e(TAG, "account watch failed", e) },
        )
        scope.launch {
            var told = false
            var waitMs = FIRST_RETRY_MS
            while (isActive) {
                val asked = try {
                    ask(uid)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.w(TAG, "Account answer not read: ${e.message}")
                    Asked.NoAnswer(e.message)
                }
                when (asked) {
                    is Asked.Answered -> {
                        onChange(asked.doc)
                        told = true
                        waitMs = FIRST_RETRY_MS
                        // Until the app comes back to the foreground.
                        refreshes.first()
                    }
                    is Asked.NoAnswer -> {
                        if (!told) {
                            onChange(cached(uid)?.let { UserDoc.Present(it) } ?: UserDoc.Failed(asked.reason))
                            told = true
                        }
                        withTimeoutOrNull(waitMs) { refreshes.first() }
                        waitMs = (waitMs * 2).coerceAtMost(MAX_RETRY_MS)
                    }
                }
            }
        }
        return Cancellable { scope.cancel() }
    }

    private sealed interface Asked {
        data class Answered(val doc: UserDoc) : Asked
        data class NoAnswer(val reason: String?) : Asked
    }

    private suspend fun ask(uid: String): Asked {
        val reply = try {
            CrmApi.call("GET", "/api/me")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Account not reached: ${e.message}")
            return Asked.NoAnswer(e.message)
        }
        return when {
            reply.ok -> {
                val user = userOf(uid, reply.body)
                remember(uid, reply.body)
                Asked.Answered(UserDoc.Present(user))
            }
            reply.status == 403 && reply.code in BLOCKED -> {
                remember(uid, JSONObject().put("blocked", true))
                Asked.Answered(UserDoc.Present(User.fromCrm(uid, "", null, null, UserStatus.SUSPENDED)))
            }
            else -> Asked.NoAnswer("HTTP ${reply.status}")
        }
    }

    private fun userOf(uid: String, me: JSONObject): User {
        if (me.optBoolean("blocked", false)) return User.fromCrm(uid, "", null, null, UserStatus.SUSPENDED)
        return User.fromCrm(
            uid = me.optString("uid").ifBlank { uid },
            name = me.optString("name"),
            email = me.optString("email").takeIf { it.isNotBlank() },
            role = Role.fromCrmRole(me.optString("role")),
            status = UserStatus.ACTIVE,
        )
    }

    private fun remember(uid: String, me: JSONObject) {
        prefs.edit().putString(uid, me.toString()).apply()
    }

    private fun cached(uid: String): User? =
        prefs.getString(uid, null)?.let { runCatching { userOf(uid, JSONObject(it)) }.getOrNull() }

    /* ---------------------------------- TAS's team screens: not in this app */

    override fun observeDirectReports(
        parentId: String,
        viewerUid: String,
        limit: Int,
        onChange: (List<User>) -> Unit,
    ): Cancellable {
        onChange(emptyList())
        return Cancellable {}
    }

    override suspend fun subtreeOf(uid: String, limit: Int): List<User> = emptyList()

    override fun observeCreatedBy(
        viewerUid: String,
        limit: Int,
        onChange: (List<User>?) -> Unit,
    ): Cancellable {
        onChange(emptyList())
        return Cancellable {}
    }

    override suspend fun peopleIn(localityId: String, viewerUid: String, limit: Int): List<User> = emptyList()

    override suspend fun countsByRole(uid: String): Map<Role, Int> = emptyMap()

    override suspend fun createUser(user: User, password: String): String {
        throw UnsupportedOperationException("CRM logins are made on the CRM website.")
    }

    override suspend fun updateUser(uid: String, changes: Map<String, Any?>) {
        throw UnsupportedOperationException("CRM logins are changed on the CRM website.")
    }

    override suspend fun findDuplicateByPhone(phone: String, callerUid: String): User? = null

    override suspend fun searchTeam(callerUid: String, term: String, limit: Int): List<User> = emptyList()

    companion object {
        private const val TAG = "CrmAccount"
        private const val PREFS = "crm_account"
        private val BLOCKED = setOf("inactive", "no_profile")
        private const val FIRST_RETRY_MS = 30_000L
        private const val MAX_RETRY_MS = 5 * 60_000L

        private val refreshes = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

        /** Ask again now: the app has come back to the foreground. */
        fun refresh() {
            refreshes.tryEmit(Unit)
        }
    }
}
