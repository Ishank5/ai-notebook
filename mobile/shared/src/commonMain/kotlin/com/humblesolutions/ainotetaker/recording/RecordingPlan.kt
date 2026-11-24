package com.humblesolutions.ainotetaker.recording

import com.humblesolutions.ainotetaker.config.RecorderLimits

/**
 * the planned length's rules, written once for both phones.
 *
 * A recording is planned for a length picked before Record (1 h 30 m unless
 * changed). When the audio reaches it the phone beeps and asks; the recording
 * carries on for a grace window, and stops by itself if nobody answers. Keep
 * recording moves the planned end on by [RecorderLimits.extendByMs], never past
 * the cap. A plan already at the cap gets its warning a grace window early and
 * stops at the cap, with nothing to extend.
 *
 * The live recording's side of this is [RecordingClock]; these are the rules it
 * and the screens share.
 */
object RecordingPlans {

    /** Continuing for a few seconds before the cap stops it again helps nobody. */
    const val MIN_CONTINUE_MS = 5_000L

    /**
     * Where a recording stops, from what its row says. A row from before plans
     * existed has none and gets the default; nothing is ever past the cap.
     */
    fun stopAt(storedMs: Long?, limits: RecorderLimits): Long =
        (storedMs ?: limits.defaultMs).coerceIn(1L, limits.capMs)

    /** The plan is below the cap, so Keep recording has somewhere to go. */
    fun canExtend(stopAtMs: Long, limits: RecorderLimits): Boolean = stopAtMs < limits.capMs

    /** Where Keep recording moves the planned end. */
    fun extended(stopAtMs: Long, limits: RecorderLimits): Long =
        minOf(stopAtMs + limits.extendByMs, limits.capMs)

    /** When the warning goes off, in audio: at the planned end, or a grace window before the cap. */
    fun warnAt(stopAtMs: Long, limits: RecorderLimits): Long =
        if (canExtend(stopAtMs, limits)) stopAtMs else limits.capMs - limits.graceMs

    /**
     * When the recording stops if nobody answers: a grace window after the
     * warning went off, or the cap. [warnedAtMs] is when it went off, which is
     * the planned end unless the recording was continued past it.
     */
    fun endAt(stopAtMs: Long, warnedAtMs: Long?, limits: RecorderLimits): Long =
        if (!canExtend(stopAtMs, limits)) {
            limits.capMs
        } else {
            minOf((warnedAtMs ?: stopAtMs) + limits.graceMs, limits.capMs)
        }

    /**
     * the stop to show as a time of day (epoch ms), [leftMs] of audio after [nowMs]. Times show to the
     * minute, so during the warning, when it stops at the end of the grace window, it is rounded UP to the next whole
     * minute: a stop at 5:39:57 shows 5:40, never a time before the real stop. Otherwise as it is.
     */
    fun shownStopAt(nowMs: Long, leftMs: Long, warning: Boolean): Long {
        val at = nowMs + leftMs.coerceAtLeast(0L)
        if (!warning) return at
        return (at + MINUTE_MS - 1) / MINUTE_MS * MINUTE_MS
    }

    private const val MINUTE_MS = 60_000L

    /** Continue is offered while there is still room under the cap. */
    fun canContinue(audioMs: Long, limits: RecorderLimits): Boolean =
        limits.capMs - audioMs >= MIN_CONTINUE_MS

    /** "1 h 30 m", "2 h", "45 m", "2 m 40 s": a length on a button or a chip. */
    fun label(ms: Long): String {
        val (h, m, s) = parts(ms)
        return listOfNotNull(
            "$h h".takeIf { h > 0 },
            "$m m".takeIf { m > 0 },
            "$s s".takeIf { s > 0 },
        ).joinToString(" ").ifEmpty { "0 m" }
    }

    /** "5 minutes", "1 hour 30 minutes", "30 seconds": a length in a sentence. */
    fun words(ms: Long): String {
        val (h, m, s) = parts(ms)
        fun unit(n: Long, one: String) = if (n == 1L) "1 $one" else "$n ${one}s"
        return listOfNotNull(
            unit(h, "hour").takeIf { h > 0 },
            unit(m, "minute").takeIf { m > 0 },
            unit(s, "second").takeIf { s > 0 },
        ).joinToString(" ").ifEmpty { "0 minutes" }
    }

    /**
     * Time left before the stop, in words for the warning: whole minutes,
     * rounded up ("5 minutes", then "4 minutes"), so a dialog that stays open
     * does not tick every second; under a minute, whole seconds.
     */
    fun wordsLeft(ms: Long): String {
        val left = ms.coerceAtLeast(0L)
        return if (left >= 60_000L) words((left + 59_999L) / 60_000L * 60_000L) else words((left + 999L) / 1000L * 1000L)
    }

    /** Hours, minutes and seconds, rounded to the nearest second. */
    private fun parts(ms: Long): Triple<Long, Long, Long> {
        val seconds = (ms.coerceAtLeast(0L) + 500) / 1000
        return Triple(seconds / 3600, (seconds % 3600) / 60, seconds % 60)
    }
}

/**
 * the warning before a recording stops, as both phones show it:
 * a dialog if the app is open, a notification, and on iOS the Live Activity.
 */
data class RecorderWarning(
    /** When it stops if nobody answers, in audio ms. */
    val endAtMs: Long = 0L,
    /** Where Keep recording would move the planned end; null when it is already at the cap. */
    val extendToMs: Long? = null,
) {
    /** The last warning: it stops at the cap, and there is nothing to extend. */
    val isFinal: Boolean get() = extendToMs == null
}
