package com.humblesolutions.ainotetaker

import com.humblesolutions.ainotetaker.config.RecorderLimits
import com.humblesolutions.ainotetaker.model.EndReason
import com.humblesolutions.ainotetaker.recording.RecorderWarning
import com.humblesolutions.ainotetaker.recording.RecordingClock
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * When a chunk ends, when the recorder warns, and when it stops — all counted
 * in audio, one 64 ms frame at a time.
 *
 * the warning and the stop follow the planned length, Keep
 * recording and the 3-hour cap, not TAS's fixed 90 minutes.
 */
class RecordingClockTest {

    private val minute = 60_000L

    /** What happened on the way: when it warned, and when and why it stopped. */
    private data class Run(val warnedAt: List<Long>, val stoppedAt: Long, val reason: EndReason, val frames: Long)

    /**
     * Frames until the clock says stop. [onWarning] runs on each warning and
     * may press Keep recording.
     */
    private fun RecordingClock.runToStop(onWarning: (RecordingClock) -> Unit = {}): Run {
        val warned = mutableListOf<Long>()
        var frames = 0L
        while (true) {
            val tick = advance()
            frames++
            if (tick.rotate) chunkStarted()
            if (tick.warning) {
                warned += totalMs
                onWarning(this)
            }
            tick.stop?.let { return Run(warned, totalMs, it, frames) }
        }
    }

    @Test
    fun `a new chunk starts on the first frame past a minute`() {
        val clock = RecordingClock()
        repeat(937) { assertFalse(clock.advance().rotate, "frame ${it + 1} is inside the minute") }
        // 938 frames is 60.032 s.
        assertTrue(clock.advance().rotate)

        clock.chunkStarted()
        assertEquals(0L, clock.chunkElapsedMs)
        assertFalse(clock.advance().rotate)
    }

    @Test
    fun `the default plan warns at 1 h 30 m and stops itself five minutes later if nobody answers`() {
        val run = RecordingClock().runToStop()

        assertEquals(1, run.warnedAt.size)
        val warned = run.warnedAt.single()
        assertTrue(warned >= 90 * minute && warned - 90 * minute < 64, "on the first frame at or past 1 h 30 m")
        assertEquals(EndReason.PLANNED, run.reason)
        assertTrue(run.stoppedAt >= warned + 5 * minute && run.stoppedAt - (warned + 5 * minute) < 64)
    }

    @Test
    fun `the warning offers Keep recording up to 3 h`() {
        val clock = RecordingClock()
        while (!clock.advance().warning) Unit

        val warning = clock.warning!!
        assertFalse(warning.isFinal)
        assertEquals(180 * minute, warning.extendToMs)
        assertEquals(clock.totalMs + 5 * minute, warning.endAtMs)
        assertEquals(5 * minute, clock.remainingMs)
    }

    @Test
    fun `Keep recording at 1 h 30 m runs to the cap with a last warning at 2 h 55 m`() {
        val run = RecordingClock().runToStop { clock ->
            if (clock.warning?.isFinal == false) assertTrue(clock.extend())
        }

        assertEquals(2, run.warnedAt.size)
        assertTrue(run.warnedAt[1] >= 175 * minute && run.warnedAt[1] - 175 * minute < 64)
        assertEquals(EndReason.LIMIT, run.reason)
        assertEquals(180 * minute, run.stoppedAt, "3 h is 168,750 frames exactly")
        assertEquals(168_750L, run.frames)
    }

    @Test
    fun `a 2 h plan extends to 3 h - not to 3 h 30 m`() {
        val clock = RecordingClock(stopAtMs = 120 * minute)
        while (!clock.advance().warning) Unit
        assertTrue(clock.totalMs >= 120 * minute)
        assertEquals(180 * minute, clock.warning!!.extendToMs)

        assertTrue(clock.extend())

        assertEquals(180 * minute, clock.stopAtMs)
        assertNull(clock.warning, "the next warning comes at 2 h 55 m")
    }

    @Test
    fun `a 3 h plan has nothing to extend - warns at 2 h 55 m and stops at 3 h`() {
        val clock = RecordingClock(stopAtMs = 180 * minute)
        val run = clock.runToStop { c ->
            assertEquals(RecorderWarning(endAtMs = 180 * minute, extendToMs = null), c.warning)
            assertTrue(c.warning!!.isFinal)
            assertFalse(c.extend())
        }

        assertEquals(1, run.warnedAt.size)
        assertTrue(run.warnedAt.single() >= 175 * minute && run.warnedAt.single() - 175 * minute < 64)
        assertEquals(EndReason.LIMIT, run.reason)
        assertEquals(180 * minute, run.stoppedAt)
    }

    @Test
    fun `Keep recording does nothing before the warning`() {
        val clock = RecordingClock()
        clock.advance()
        assertFalse(clock.extend())
        assertEquals(90 * minute, clock.stopAtMs)
    }

    @Test
    fun `a pause does not use up the grace window - because only audio counts`() {
        // There is no wall clock here at all: frames not written are time not spent.
        val clock = RecordingClock(priorMs = 90 * minute - 64)
        assertTrue(clock.advance().warning)
        val left = clock.remainingMs
        // A long pause writes nothing; the window is exactly where it was.
        assertEquals(left, clock.remainingMs)
        assertEquals(5 * minute, left)
    }

    @Test
    fun `a recording continued past its planned end is warned at once - with a fresh grace window`() {
        // Cut off three minutes into the grace window, continued later.
        val clock = RecordingClock(priorMs = 93 * minute, stopAtMs = 90 * minute)
        val tick = clock.advance()
        assertTrue(tick.warning)
        assertNull(tick.stop)
        assertEquals(5 * minute, clock.remainingMs)
        assertTrue(clock.extend())
        assertEquals(180 * minute, clock.stopAtMs)
    }

    @Test
    fun `a recording continued a frame short of the cap stops on its next frame - without a warning`() {
        val clock = RecordingClock(priorMs = 180 * minute - 64, stopAtMs = 180 * minute)
        assertFalse(clock.limitReached)
        val tick = clock.advance()
        assertEquals(EndReason.LIMIT, tick.stop)
        assertFalse(tick.warning)
    }

    @Test
    fun `a stored plan past the cap is held to the cap`() {
        val clock = RecordingClock(stopAtMs = 240 * minute)
        assertEquals(180 * minute, clock.stopAtMs)
    }

    @Test
    fun `shorter limits work the same way - as the debug test mode uses them`() {
        val fast = RecorderLimits(
            choicesMs = listOf(120_000L, 160_000L, 200_000L, 240_000L),
            defaultMs = 120_000L,
            capMs = 240_000L,
            extendByMs = 120_000L,
            graceMs = 30_000L,
        )
        val unanswered = RecordingClock(stopAtMs = fast.defaultMs, limits = fast).runToStop()
        assertEquals(EndReason.PLANNED, unanswered.reason)
        assertTrue(unanswered.stoppedAt in 150_000L..150_064L, "2 min plus a 30 s grace window")

        val kept = RecordingClock(stopAtMs = fast.defaultMs, limits = fast).runToStop { c ->
            if (c.warning?.isFinal == false) c.extend()
        }
        assertEquals(2, kept.warnedAt.size)
        assertTrue(kept.warnedAt[1] in 210_000L..210_064L, "the last warning 30 s before the 4-minute cap")
        assertEquals(EndReason.LIMIT, kept.reason)
        assertEquals(240_000L, kept.stoppedAt)
    }
}
