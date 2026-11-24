package com.humblesolutions.ainotetaker.recording

import com.humblesolutions.ainotetaker.config.RecorderConfig

/** what [SilenceWatch] has to say about the microphone. */
enum class SilenceEvent {
    /** Nothing but exact zeros for a while: tell the person once. */
    SILENT,

    /** Sound again after [SILENT]: the notice is no longer true. */
    BACK,
}

/**
 * EXACT DIGITAL SILENCE, WATCHED THE SAME WAY ON BOTH PHONES.
 *
 * A microphone another app or a call has taken can deliver zeros. So can a quiet room: some phones' noise gates hand
 * over true zeros between words. Pausing on zeros alone stopped real meetings, and since a pause also stops the
 * clock, a silent room never reached its planned stop. So zeros are recorded like any audio; only the system saying
 * the capture is silenced pauses it (each platform watches that itself). What this adds is the person being told:
 * after [alertAfterMs] of nothing but zeros, once, and again only after sound has come back and gone again.
 *
 * One per recording, fed every read made while recording, on a monotonic clock.
 */
class SilenceWatch(private val alertAfterMs: Long = RecorderConfig.SILENT_ALERT_MS) {

    private var zeroSince: Long? = null
    private var told = false

    /**
     * One read: [silent] when every sample in it was exactly zero, at [nowMs]. Returns [SilenceEvent.SILENT] once a
     * run of zeros has lasted [alertAfterMs], [SilenceEvent.BACK] on the first sound after that, otherwise null.
     */
    fun onAudio(silent: Boolean, nowMs: Long): SilenceEvent? {
        if (!silent) {
            zeroSince = null
            if (!told) return null
            told = false
            return SilenceEvent.BACK
        }
        val since = zeroSince ?: nowMs.also { zeroSince = it }
        if (told || nowMs - since < alertAfterMs) return null
        told = true
        return SilenceEvent.SILENT
    }

    /** How long the current run of zeros has lasted at [nowMs], or 0. For the log. */
    fun silentForMs(nowMs: Long): Long = zeroSince?.let { (nowMs - it).coerceAtLeast(0L) } ?: 0L

    /**
     * Capture stopped (a pause, a restart): a run of zeros counts again from the next read. A notice already given
     * stays until sound comes back.
     */
    fun reset() {
        zeroSince = null
    }
}
