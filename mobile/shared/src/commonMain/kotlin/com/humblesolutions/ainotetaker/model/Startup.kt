package com.humblesolutions.ainotetaker.model

/**
 * When the splash may step aside.
 *
 * THE POINT IS THAT NOBODY SEES THE APP REARRANGE ITSELF. Before this, the
 * first frame drew the compiled-in defaults and the real matrix arrived a
 * moment later — so a tab could appear and then vanish, or a feature the
 * office had switched off could flash on screen before being withdrawn. Both
 * are worse than waiting: one looks broken, the other leaks a decision.
 *
 * So the splash holds until BOTH answers are in — who this is, and what they
 * are allowed to see — and the app's first frame is its only frame.
 *
 * Shared because the two platforms must hold for the same reasons and the same
 * lengths of time. A splash that lingers on one and flashes on the other is two
 * bugs wearing one name.
 */
object Startup {

    /**
     * The splash is shown for at least this long.
     *
     * Not decoration. Signed in, warm cache, on wifi, both answers land in
     * well under a tenth of a second — and a logo that appears and disappears
     * inside 80ms reads as a glitch rather than as a brand. Holding it briefly
     * turns a flicker into an intention.
     */
    const val MIN_SHOW_MS: Long = 700

    /**
     * And for no longer than this, whatever is still missing.
     *
     * A FIELD WORKER WITH NO SIGNAL MUST STILL GET IN. Firestore answers from
     * its cache offline, but a first run on a dead connection has no cache to
     * answer from — and an app that waits forever for permission to draw
     * itself is an app that is broken in exactly the place it can least afford
     * to be. Past this, the compiled-in defaults are the answer and the live
     * matrix corrects them whenever it turns up.
     */
    const val MAX_WAIT_MS: Long = 5_000

    /**
     * @param sessionResolved whether the session is anything but Loading.
     * @param accessNeeded whether this session will actually draw the matrix —
     *   true only for a signed-in, active user.
     * @param accessResolved whether the live feature matrix has arrived.
     * @param elapsedMs how long the splash has been up.
     *
     * The session is NOT subject to the timeout. Without it there is no honest
     * screen to fall back to — showing sign-in to somebody already signed in,
     * or a dashboard to a stranger, are both worse than waiting. Auth resolves
     * from local storage and does so offline, so the wait is bounded in
     * practice by something that does not need the network.
     *
     * [accessNeeded] IS NOT AN OPTIMISATION. The matrix is unreadable until
     * somebody is signed in — the rules require an active session — so waiting
     * for it on the way to the SIGN-IN screen meant every signed-out launch
     * sat on the splash for the full timeout before giving up. There is
     * nothing on that screen the matrix could change.
     */
    fun isReady(
        sessionResolved: Boolean,
        accessNeeded: Boolean,
        accessResolved: Boolean,
        elapsedMs: Long,
    ): Boolean {
        if (elapsedMs < MIN_SHOW_MS) return false
        if (!sessionResolved) return false
        if (!accessNeeded) return true
        return accessResolved || elapsedMs >= MAX_WAIT_MS
    }

    /**
     * How often the splash re-asks. Small enough that the hand-off lands
     * within a frame or two of the data, large enough not to spin a timer for
     * nothing.
     */
    const val TICK_MS: Long = 50
}
