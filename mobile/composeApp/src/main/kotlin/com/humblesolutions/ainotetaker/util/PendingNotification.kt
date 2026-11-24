package com.humblesolutions.ainotetaker.util

import android.content.Intent
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * A notification somebody tapped, waiting for a screen to act on it.
 *
 * A PROCESS-WIDE ONE-SHOT, modelled on [SharedLink] and for the same reason.
 * The tap arrives at `MainActivity` as an Intent before any screen exists to
 * receive it — and with `singleTask`, usually while the app is already running
 * and showing something else. Threading it through the navigation graph would
 * mean every screen knowing about a case that concerns one of them.
 *
 * CONSUMED, NOT JUST READ. Once the app has navigated, it is cleared — so
 * rotating the device or returning from the background does not throw the
 * person back to Events every time.
 */
object PendingNotification {

    /** What the Cloud Functions put in the message's `data` payload. */
    private const val KIND = "kind"
    private const val KIND_EVENT = "event"
    private const val KIND_UPDATE = "update"

    /** The recorder's own notifications — see `RecorderNotifications.KIND_RECORDER`. */
    private const val KIND_RECORDER = "recorder"

    private val _openEvents = MutableStateFlow(false)
    val openEvents: StateFlow<Boolean> = _openEvents.asStateFlow()

    private val _openUpdates = MutableStateFlow(false)
    val openUpdates: StateFlow<Boolean> = _openUpdates.asStateFlow()

    private val _openRecorder = MutableStateFlow(false)
    val openRecorder: StateFlow<Boolean> = _openRecorder.asStateFlow()

    /** a locked notification button (Pause, Stop, Stop now) that opened the app after the unlock. */
    private const val RECORDER_ACTION = "recorder_action"
    private val _recorderAction = MutableStateFlow<String?>(null)
    val recorderAction: StateFlow<String?> = _recorderAction.asStateFlow()

    /**
     * Notice a tapped notification.
     *
     * FCM puts the message's `data` map into the launch Intent's extras, so a
     * tap is recognised by the `kind` the function sent rather than by the
     * Intent action — which is the ordinary launcher action and says nothing.
     */
    fun offer(intent: Intent?) {
        when (intent?.getStringExtra(KIND)) {
            KIND_EVENT -> _openEvents.value = true
            KIND_UPDATE -> _openUpdates.value = true
            KIND_RECORDER -> {
                _openRecorder.value = true
                intent.getStringExtra(RECORDER_ACTION)?.let { _recorderAction.value = it }
            }
        }
    }

    fun consumeEvents() {
        _openEvents.value = false
    }

    fun consumeUpdates() {
        _openUpdates.value = false
    }

    fun consumeRecorder() {
        _openRecorder.value = false
    }

    fun consumeRecorderAction() {
        _recorderAction.value = null
    }

    /**
     * Dropped when the session ends.
     *
     * WITHOUT THIS A TAP OUTLIVES ITS OWNER. Somebody taps a notification,
     * signs out, and the flag is still set — so the next person to sign in on
     * that handset is thrown to Events for a tap they never made. They see
     * only their own events, because the rules decide that and not this class,
     * but the app doing something nobody asked for is its own bug.
     */
    fun clear() {
        _openEvents.value = false
        _openUpdates.value = false
        _openRecorder.value = false
        _recorderAction.value = null
    }
}
