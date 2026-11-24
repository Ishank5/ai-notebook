package com.humblesolutions.ainotetaker.recording

import com.humblesolutions.ainotetaker.config.RecorderConfig
import com.humblesolutions.ainotetaker.config.RecorderLimits
import com.humblesolutions.ainotetaker.model.EndReason

/**
 * Decides, from audio actually captured, when to start a new chunk, when to
 * warn, and when to stop.
 *
 * COUNTED IN SAMPLES, NOT WALL-CLOCK TIME. A phone call pauses the wall clock's
 * relevance but not the wall clock; the planned length and the 3-hour cap are
 * promises about audio recorded, so they are measured in audio. Counting frames
 * also stays exact at any sample rate — 1024 samples at 16 kHz is 64 ms
 * precisely, but at 24 kHz it is not a whole number of milliseconds.
 *
 * SHARED so both phones rotate, warn and stop on exactly the same frame.
 *
 * TAS warned at fixed moments before a fixed 90-minute stop.
 * This clock follows a plan instead ([RecordingPlans]): one warning at the
 * planned end, then a grace window in which [extend] (Keep recording) moves the
 * end on; unanswered, it stops with [EndReason.PLANNED]. At the 3-hour cap it
 * stops with [EndReason.LIMIT], as TAS's limit did.
 */
class RecordingClock(
    /** Audio already recorded — nonzero when a recording is continued. */
    priorMs: Long = 0L,
    private val sampleRate: Int = RecorderConfig.SAMPLE_RATE,
    private val chunkMs: Long = RecorderConfig.CHUNK_MS,
    /** the planned end, in audio ms. */
    stopAtMs: Long = RecorderConfig.DEFAULT_PLAN_MS,
    private val limits: RecorderLimits = RecorderLimits.STANDARD,
) {
    private var totalSamples: Long = priorMs * sampleRate / 1000
    private var chunkSamples: Long = 0L

    /** the planned end now, after any Keep recording. */
    var stopAtMs: Long = RecordingPlans.stopAt(stopAtMs, limits)
        private set

    /**
     * When the warning for the current plan went off, or null before it has.
     * A continued recording starts with none, so one already past its planned
     * end is warned again, with a fresh grace window, on its first frame.
     */
    private var warnedAtMs: Long? = null

    val totalMs: Long get() = totalSamples * 1000 / sampleRate
    val chunkElapsedMs: Long get() = chunkSamples * 1000 / sampleRate

    /** when it stops if nobody answers, in audio ms. */
    val endAtMs: Long get() = RecordingPlans.endAt(stopAtMs, warnedAtMs, limits)

    /** Audio left until it stops by itself. */
    val remainingMs: Long get() = (endAtMs - totalMs).coerceAtLeast(0L)
    val limitReached: Boolean get() = totalMs >= endAtMs

    /** the warning, while one is showing; null before it and after Keep recording. */
    val warning: RecorderWarning?
        get() = if (warnedAtMs == null) null else RecorderWarning(
            endAtMs = endAtMs,
            extendToMs = RecordingPlans.extended(stopAtMs, limits).takeIf { RecordingPlans.canExtend(stopAtMs, limits) },
        )

    /** What the recorder should do after the frames just written. */
    data class Tick(
        val rotate: Boolean = false,
        /** the warning went off on this frame; [warning] says what it is. */
        val warning: Boolean = false,
        /** stop now, and why; null to carry on. */
        val stop: EndReason? = null,
    ) {
        val limitReached: Boolean get() = stop != null
    }

    /** Records [frames] AAC frames as written to the current chunk. */
    fun advance(frames: Int = 1): Tick {
        val samples = frames.toLong() * RecorderConfig.SAMPLES_PER_FRAME
        totalSamples += samples
        chunkSamples += samples

        val total = totalMs
        var warned = false
        if (warnedAtMs == null && total >= RecordingPlans.warnAt(stopAtMs, limits)) {
            warnedAtMs = total
            warned = true
        }
        val end = endAtMs
        val stop = when {
            total < end -> null
            end >= limits.capMs -> EndReason.LIMIT
            else -> EndReason.PLANNED
        }
        return Tick(
            rotate = chunkElapsedMs >= chunkMs,
            // A warning on the frame that stops the recording would only be noise.
            warning = warned && stop == null,
            stop = stop,
        )
    }

    /**
     * Keep recording. Only while the warning is showing and the
     * plan is below the cap; the next warning comes at the new planned end.
     * Returns false, changing nothing, otherwise.
     */
    fun extend(): Boolean {
        if (warnedAtMs == null || !RecordingPlans.canExtend(stopAtMs, limits)) return false
        stopAtMs = RecordingPlans.extended(stopAtMs, limits)
        warnedAtMs = null
        return true
    }

    /** A new chunk file has been opened. */
    fun chunkStarted() {
        chunkSamples = 0L
    }
}
