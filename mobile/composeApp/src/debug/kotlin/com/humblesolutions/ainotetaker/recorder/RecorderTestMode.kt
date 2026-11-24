package com.humblesolutions.ainotetaker.recorder

import android.content.Context
import com.humblesolutions.ainotetaker.config.RecorderLimits

/**
 * fast test mode, DEBUG BUILDS ONLY. This file is in
 * `src/debug`; release builds compile the one in `src/release`, which has no
 * switch and always answers [RecorderLimits.STANDARD].
 *
 * Shrinks the planned length's times so the warning, Keep recording and the
 * stop by itself can be tried on a real phone in minutes:
 * 1 h 30 m becomes 2 min, 2 h 2 min 40 s, 2 h 30 m 3 min 20 s, 3 h 4 min,
 * Keep recording adds 2 min, and the grace window is 30 s.
 *
 * Switched by a long press on the "AI Notebook" title on the recorder screen.
 * Read when a recording starts or is continued, so switching it mid-recording
 * changes the next one, not the one running.
 *
 * IN MEMORY ONLY: off again after any restart of the app, so a real meeting
 * recorded the next day on this debug build cannot stop after four minutes.
 * The "Fast test" chip shows while it is on.
 */
object RecorderTestMode {

    const val available = true

    @Volatile private var fast = false

    private val fastLimits = RecorderLimits(
        choicesMs = listOf(120_000L, 160_000L, 200_000L, 240_000L),
        defaultMs = 120_000L,
        capMs = 240_000L,
        extendByMs = 120_000L,
        graceMs = 30_000L,
    )

    @Suppress("UNUSED_PARAMETER")
    fun isOn(context: Context): Boolean = fast

    /** Flips it and returns the new state. */
    @Suppress("UNUSED_PARAMETER")
    fun toggle(context: Context): Boolean {
        fast = !fast
        return fast
    }

    fun limits(context: Context): RecorderLimits = if (isOn(context)) fastLimits else RecorderLimits.STANDARD
}
