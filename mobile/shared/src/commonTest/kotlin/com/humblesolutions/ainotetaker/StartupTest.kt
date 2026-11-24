package com.humblesolutions.ainotetaker

import com.humblesolutions.ainotetaker.model.Startup
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * When the splash steps aside.
 *
 * Three properties, and each of them is a bug somebody would otherwise ship:
 * a logo that flashes, an app that rearranges itself in front of the user, and
 * an app that never opens on a dead connection.
 */
class StartupTest {

    private val everythingIn = { ms: Long -> Startup.isReady(true, true, true, ms) }

    @Test
    fun the_splash_is_never_shorter_than_the_minimum_even_when_both_answers_are_already_in() {
        assertFalse(everythingIn(0))
        assertFalse(everythingIn(Startup.MIN_SHOW_MS - 1))
        assertTrue(everythingIn(Startup.MIN_SHOW_MS))
    }

    @Test
    fun it_waits_for_the_feature_matrix_rather_than_drawing_the_defaults_first() {
        // Long past the minimum, session known, matrix still in flight: hold.
        // Drawing now is what made a tab appear and then vanish.
        assertFalse(Startup.isReady(sessionResolved = true, accessNeeded = true, accessResolved = false, elapsedMs = 2_000))
    }

    @Test
    fun IT_GIVES_UP_ON_THE_MATRIX_RATHER_THAN_TRAPPING_SOMEBODY_OFFLINE() {
        assertFalse(Startup.isReady(true, true, false, Startup.MAX_WAIT_MS - 1))
        assertTrue(Startup.isReady(true, true, false, Startup.MAX_WAIT_MS))
        assertTrue(Startup.isReady(true, true, false, Startup.MAX_WAIT_MS * 10))
    }

    @Test
    fun the_session_is_not_subject_to_the_timeout() {
        // There is no honest screen to fall back to: sign-in for somebody
        // already signed in, or a dashboard for a stranger, are both worse
        // than waiting. Auth answers from local storage, offline included.
        assertFalse(Startup.isReady(sessionResolved = false, accessNeeded = true, accessResolved = true, elapsedMs = 60_000))
    }

    @Test
    fun nothing_resolved_and_a_long_wait_still_holds() {
        assertFalse(Startup.isReady(false, true, false, 60_000))
    }

    @Test
    fun A_SIGNED_OUT_LAUNCH_DOES_NOT_WAIT_FOR_A_MATRIX_IT_CANNOT_READ() {
        // The rules require an active session, so the listener fails outright
        // when nobody is signed in. Waiting for it meant every signed-out
        // launch sat on the splash for the full five seconds before giving up
        // — on the way to a screen the matrix cannot change.
        assertTrue(
            Startup.isReady(
                sessionResolved = true,
                accessNeeded = false,
                accessResolved = false,
                elapsedMs = Startup.MIN_SHOW_MS,
            ),
        )
    }

    @Test
    fun the_minimum_is_shorter_than_the_maximum_and_the_tick_is_shorter_than_both() {
        // A guard against somebody "tuning" one of these into nonsense: a
        // minimum past the maximum would hold forever.
        assertTrue(Startup.MIN_SHOW_MS < Startup.MAX_WAIT_MS)
        assertTrue(Startup.TICK_MS < Startup.MIN_SHOW_MS)
    }
}
