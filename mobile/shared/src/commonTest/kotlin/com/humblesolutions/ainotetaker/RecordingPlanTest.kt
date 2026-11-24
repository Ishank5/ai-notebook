package com.humblesolutions.ainotetaker

import com.humblesolutions.ainotetaker.config.RecorderConfig
import com.humblesolutions.ainotetaker.config.RecorderLimits
import com.humblesolutions.ainotetaker.recording.RecordingPlans
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** the planned length's rules and the words both phones show for them. */
class RecordingPlanTest {

    private val minute = 60_000L
    private val limits = RecorderLimits.STANDARD

    @Test
    fun `the choices are 1 h 30 m - 2 h - 2 h 30 m and 3 h - with 1 h 30 m first`() {
        assertEquals(listOf(90, 120, 150, 180).map { it * minute }, limits.choicesMs)
        assertEquals(90 * minute, limits.defaultMs)
        assertEquals(180 * minute, limits.capMs)
        assertEquals(RecorderConfig.MAX_DURATION_MS, limits.capMs)
        assertEquals(listOf("1 h 30 m", "2 h", "2 h 30 m", "3 h"), limits.choicesMs.map(RecordingPlans::label))
    }

    @Test
    fun `anything but a choice starts on the default`() {
        assertEquals(120 * minute, limits.planOrDefault(120 * minute))
        assertEquals(90 * minute, limits.planOrDefault(null))
        assertEquals(90 * minute, limits.planOrDefault(100 * minute))
        assertEquals(90 * minute, limits.planOrDefault(240 * minute))
    }

    @Test
    fun `Keep recording adds 1 h 30 m and never passes 3 h`() {
        assertEquals(180 * minute, RecordingPlans.extended(90 * minute, limits))
        assertEquals(180 * minute, RecordingPlans.extended(120 * minute, limits))
        assertEquals(180 * minute, RecordingPlans.extended(150 * minute, limits))
        assertTrue(RecordingPlans.canExtend(150 * minute, limits))
        assertFalse(RecordingPlans.canExtend(180 * minute, limits))
    }

    @Test
    fun `the warning is at the planned end - or five minutes before the cap`() {
        assertEquals(90 * minute, RecordingPlans.warnAt(90 * minute, limits))
        assertEquals(150 * minute, RecordingPlans.warnAt(150 * minute, limits))
        assertEquals(175 * minute, RecordingPlans.warnAt(180 * minute, limits))
    }

    @Test
    fun `it stops five minutes after the warning - or at the cap`() {
        assertEquals(95 * minute, RecordingPlans.endAt(90 * minute, null, limits))
        assertEquals(98 * minute, RecordingPlans.endAt(90 * minute, 93 * minute, limits), "warned late, after a Continue")
        assertEquals(180 * minute, RecordingPlans.endAt(180 * minute, 175 * minute, limits))
        assertEquals(180 * minute, RecordingPlans.endAt(178 * minute, null, limits), "never past the cap")
    }

    @Test
    fun `a row from before plans gets the default and nothing is past the cap`() {
        assertEquals(90 * minute, RecordingPlans.stopAt(null, limits))
        assertEquals(150 * minute, RecordingPlans.stopAt(150 * minute, limits))
        assertEquals(180 * minute, RecordingPlans.stopAt(400 * minute, limits))
    }

    @Test
    fun `Continue is offered while there is room under the cap`() {
        assertTrue(RecordingPlans.canContinue(170 * minute, limits))
        assertTrue(RecordingPlans.canContinue(180 * minute - 5_000, limits))
        assertFalse(RecordingPlans.canContinue(180 * minute - 4_999, limits))
    }

    @Test
    fun `lengths read the same on both phones`() {
        assertEquals("5 m", RecordingPlans.label(5 * minute))
        assertEquals("2 m 40 s", RecordingPlans.label(160_000))
        assertEquals("30 s", RecordingPlans.label(30_000))
        assertEquals("5 minutes", RecordingPlans.words(5 * minute))
        assertEquals("1 minute", RecordingPlans.words(minute))
        assertEquals("3 hours", RecordingPlans.words(180 * minute))
        assertEquals("1 hour 30 minutes", RecordingPlans.words(90 * minute))
        assertEquals("30 seconds", RecordingPlans.words(30_000))
        assertEquals("4 minutes 12 seconds", RecordingPlans.words(252_400))
        assertEquals("5 minutes", RecordingPlans.wordsLeft(5 * minute))
        assertEquals("5 minutes", RecordingPlans.wordsLeft(4 * minute + 1))
        assertEquals("1 minute", RecordingPlans.wordsLeft(60_000))
        assertEquals("30 seconds", RecordingPlans.wordsLeft(29_400))
    }

    // D2. Shown to the minute, the warning's stop is never earlier than the real one.
    @Test
    fun `during the warning the stop time shown is rounded up to the minute`() {
        val fiveThirtyFour = 1_763_986_440_000L // a whole minute
        val stop = fiveThirtyFour + 5 * minute + 57_000 // 5:39:57
        assertEquals(fiveThirtyFour + 6 * minute, RecordingPlans.shownStopAt(fiveThirtyFour, stop - fiveThirtyFour, true))
        assertEquals(stop, RecordingPlans.shownStopAt(fiveThirtyFour, stop - fiveThirtyFour, false), "as it is otherwise")
        assertEquals(
            fiveThirtyFour + 5 * minute,
            RecordingPlans.shownStopAt(fiveThirtyFour, 5 * minute, true),
            "a whole minute stays",
        )
        assertEquals(fiveThirtyFour + minute, RecordingPlans.shownStopAt(fiveThirtyFour, 1, true))
        assertEquals(fiveThirtyFour, RecordingPlans.shownStopAt(fiveThirtyFour, -5_000, true), "never in the past")
    }

    @Test
    fun `limits that make no sense are refused`() {
        assertFailsWith<IllegalArgumentException> { RecorderLimits(defaultMs = 100 * minute) }
        assertFailsWith<IllegalArgumentException> { RecorderLimits(choicesMs = listOf(240 * minute), defaultMs = 240 * minute) }
        assertFailsWith<IllegalArgumentException> { RecorderLimits(graceMs = 0) }
    }
}
