package com.humblesolutions.ainotetaker.recorder

import com.humblesolutions.ainotetaker.config.RecorderConfig
import com.humblesolutions.ainotetaker.model.GapReason
import com.humblesolutions.ainotetaker.recording.RecorderWarning
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * What the microphone is doing right now, for the screen and the notification.
 *
 * A PROCESS-WIDE FLOW, like [com.humblesolutions.ainotetaker.util.PendingNotification]:
 * the recorder runs in a service that outlives every screen, and the screen
 * that comes back after the phone was locked for forty minutes must show the
 * same clock the notification has been showing.
 *
 * The queue in `recordings.db` is the record of what happened; this is only
 * the live picture, and it is gone with the process — which is correct, since
 * so is the recording it describes.
 */
object LiveRecorder {

    enum class Phase { IDLE, STARTING, RECORDING, PAUSED, STOPPING }

    data class Status(
        val phase: Phase = Phase.IDLE,
        val recordingId: String? = null,
        /** Why capture is paused; null while recording. */
        val pausedFor: GapReason? = null,
        /** Audio captured, including any from before a Continue. */
        val elapsedMs: Long = 0L,
        /** Input level, 0–1, for the meter that proves the microphone hears the room. */
        val level: Float = 0f,
        /** the planned end in audio ms, after any Keep recording. */
        val stopAtMs: Long = RecorderConfig.DEFAULT_PLAN_MS,
        /** the 3-hour cap (shorter in the debug test mode), for the words of the last warning. */
        val capMs: Long = RecorderConfig.MAX_DURATION_MS,
        /** the warning before it stops, while it is showing (replaces TAS's minutes left). */
        val warning: RecorderWarning? = null,
        /** A `recorder.err.*` key from the last attempt to record. */
        val errorKey: String? = null,
        /** A recording that has just stopped with audio in it, waiting to be named. */
        val justStopped: String? = null,
    ) {
        val isLive: Boolean
            get() = phase == Phase.STARTING || phase == Phase.RECORDING ||
                phase == Phase.PAUSED || phase == Phase.STOPPING
    }

    private val _status = MutableStateFlow(Status())
    val status: StateFlow<Status> = _status.asStateFlow()

    internal fun update(change: (Status) -> Status) = _status.update(change)

    /** The details editor has been dealt with, or an error has been read. */
    fun acknowledge() = _status.update { it.copy(justStopped = null, errorKey = null) }
}
