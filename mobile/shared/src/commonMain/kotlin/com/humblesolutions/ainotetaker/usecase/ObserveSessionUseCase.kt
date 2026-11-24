package com.humblesolutions.ainotetaker.usecase

import com.humblesolutions.ainotetaker.model.Session
import com.humblesolutions.ainotetaker.model.SignOutReason
import com.humblesolutions.ainotetaker.model.UserStatus
import com.humblesolutions.ainotetaker.repository.AuthRepository
import com.humblesolutions.ainotetaker.repository.UserDoc
import com.humblesolutions.ainotetaker.repository.UserRepository
import com.humblesolutions.ainotetaker.util.Cancellable
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Who is signed in, kept true for as long as the app is open.
 *
 * THE ORDER HERE IS THE WHOLE THING, and it is not obvious:
 *
 *  1. Auth says a uid exists. That is NOT enough to let anyone in — Auth knows
 *     the person owns the credentials, nothing about whether they are on the
 *     team.
 *  2. Refresh the token. A brand-new user's FIRST token has no claims at all:
 *     the provisioning trigger mints them after the document is written. Read
 *     without forcing a refresh and the rules refuse the document, the listener
 *     reports a failure, and the app decides they have no account and signs
 *     them straight back out — the bug that makes an app look like it
 *     "randomly logs people out".
 *  3. Read the `users` document, and keep listening. It is the authority on
 *     role, status and scope, and it is what changes when somebody is
 *     suspended mid-shift.
 *
 * STEP 2 USED TO BE A COMMENT RATHER THAN A LINE OF CODE. This file described
 * the refresh, in this order, and never performed it — so the failure it warns
 * about was live the whole time.
 *
 * A FAILED READ IS NOT AN ABSENT ACCOUNT. See [UserDoc]: the listener reports
 * both as nothing, and treating the pair alike is what turned a tunnel, a cold
 * offline start, or a rules refusal one second after provisioning into a
 * forced sign-out. Only [UserDoc.Absent] ends a session now.
 *
 * A SUSPENSION ENDS THE SESSION IMMEDIATELY, with a reason that survives the
 * sign-out. Without carrying it, the auth listener fires with null and the
 * reason is lost, so somebody suspended mid-shift lands on the sign-in screen
 * with no explanation and tries their number again, and again.
 */
class ObserveSessionUseCase(
    private val authRepository: AuthRepository,
    private val userRepository: UserRepository,
) {

    fun execute(onChange: (Session) -> Unit): Cancellable {
        /*
         * OWNED HERE, NOT PASSED IN. Forcing a token refresh is a suspending
         * call and the auth listener is a plain callback, so something has to
         * bridge the two. Taking a scope as a parameter would push that choice
         * onto every platform — and iOS has no CoroutineScope to hand.
         */
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

        var userSubscription: Cancellable? = null
        var pendingReason: SignOutReason? = null

        /*
         * Which sign-in this callback belongs to.
         *
         * A TOKEN REFRESH TAKES A ROUND TRIP, and a person can sign out — or
         * sign in as somebody else on a shared handset — while one is in
         * flight. Without this, the late reply attaches a listener for the
         * PREVIOUS uid and the app shows the last person's account to the
         * current one.
         */
        var generation = 0

        val authSubscription = authRepository.observeAuthState { uid ->
            generation += 1
            val mine = generation

            userSubscription?.cancel()
            userSubscription = null

            if (uid == null) {
                onChange(Session.SignedOut(pendingReason))
                pendingReason = null
                return@observeAuthState
            }

            onChange(Session.Loading)

            scope.launch {
                /*
                 * FORCED, AND ITS FAILURE IS NOT FATAL. The refresh is what
                 * puts the freshly-minted claims in the token before the rules
                 * are asked to honour them. Offline it cannot happen at all —
                 * and offline the cached token is the best there is, so the
                 * read is still worth attempting rather than refusing to try.
                 */
                runCatching { authRepository.claims(forceRefresh = true) }

                if (mine != generation) return@launch

                userSubscription = userRepository.observeUserDocument(uid) { doc ->
                    if (mine != generation) return@observeUserDocument

                    when (doc) {
                        /*
                         * Could not ask. NOT a sign-out — see
                         * [Session.Unreachable]. The Auth session is kept, and
                         * the screen offers a retry for the refusals that do
                         * not heal on their own.
                         */
                        is UserDoc.Failed -> onChange(Session.Unreachable(uid, doc.reason))

                        /*
                         * Asked, and there is no such account. Either never
                         * registered, or removed while signed in.
                         *
                         * THE AUTH SESSION GOES WITH IT. Leaving Firebase
                         * signed in while the app shows the sign-in screen is
                         * a state the two disagree about: signing in again as
                         * the same account need not fire the auth listener,
                         * and the screen sits there having apparently done
                         * nothing.
                         */
                        is UserDoc.Absent -> {
                            pendingReason = SignOutReason.NOT_REGISTERED
                            onChange(Session.SignedOut(SignOutReason.NOT_REGISTERED))
                            scope.launch { runCatching { authRepository.signOut() } }
                        }

                        is UserDoc.Present -> {
                            val user = doc.user
                            when (user.status) {
                                UserStatus.SUSPENDED -> {
                                    pendingReason = SignOutReason.SUSPENDED
                                    onChange(Session.Blocked(user, UserStatus.SUSPENDED))
                                }

                                UserStatus.PROVISIONING_FAILED -> {
                                    pendingReason = SignOutReason.PROVISIONING_FAILED
                                    onChange(
                                        Session.Blocked(user, UserStatus.PROVISIONING_FAILED),
                                    )
                                }

                                /*
                                 * Still provisioning. NOT an error and NOT a
                                 * sign-out: the trigger is milliseconds behind
                                 * the write, and the right thing is to wait
                                 * rather than to accuse.
                                 */
                                UserStatus.PENDING_PROVISIONING ->
                                    onChange(
                                        Session.Blocked(user, UserStatus.PENDING_PROVISIONING),
                                    )

                                UserStatus.ACTIVE -> onChange(Session.Active(user))
                            }
                        }
                    }
                }
            }
        }

        return Cancellable {
            userSubscription?.cancel()
            authSubscription.cancel()
            scope.cancel()
        }
    }
}
