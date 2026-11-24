package com.humblesolutions.ainotetaker.recorder

import android.app.ActivityManager
import android.app.AlarmManager
import android.app.ApplicationExitInfo
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.SystemClock
import android.util.Log
import com.humblesolutions.ainotetaker.model.GapReason
import com.humblesolutions.ainotetaker.model.LocalRecording
import com.humblesolutions.ainotetaker.recording.RecordingDriverFactory
import com.humblesolutions.ainotetaker.recording.RecordingSource
import com.humblesolutions.ainotetaker.usecase.NoteRecordingExitUseCase
import com.humblesolutions.ainotetaker.usecase.RecoverRecordingsUseCase
import com.humblesolutions.ainotetaker.usecase.RecoveryReport
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * the recorder's alarms. TAS only learnt that a recording was cut off from the hourly upload check,
 * so the person was told late, or never.
 *
 * THE DEAD MAN'S SWITCH. A microphone service killed in the background cannot be restarted (Android forbids it, and
 * rightly), but the person can be told. Every heartbeat pushes an alarm two minutes ahead; while the recorder is
 * alive it never fires. Once the process is gone it does, in a fresh process, which runs recovery and posts
 * "Recording stopped at 14:02. Tap to continue." A normal end cancels it. A force-stop from Settings cancels every
 * alarm the app has; then the boot and update receivers, the hourly check and the next launch are what tell them.
 *
 * STILL PAUSED. A pause the person made, left for ten minutes, gets a reminder (and every half hour after), because
 * a meeting carries on while a forgotten Pause records nothing. the dead man's alarm is cancelled for
 * that pause, so a long pause does not wake the phone every few minutes; this alarm runs recovery instead when the
 * process it fires in is not the one that paused.
 */
internal object RecorderAlarms {

    const val ACTION_DEAD_MAN = "com.humblesolutions.ainotetaker.recorder.DEAD_MAN"
    const val ACTION_STILL_PAUSED = "com.humblesolutions.ainotetaker.recorder.STILL_PAUSED"

    /** Two heartbeat-free minutes mean the recorder is gone. Inexact by design: Android may run it a little late. */
    private const val DEAD_MAN_MS = 2 * 60_000L
    const val STILL_PAUSED_FIRST_MS = 10 * 60_000L
    const val STILL_PAUSED_AGAIN_MS = 30 * 60_000L

    fun armDeadMan(context: Context) = arm(context, ACTION_DEAD_MAN, DEAD_MAN_MS)
    fun cancelDeadMan(context: Context) = cancel(context, ACTION_DEAD_MAN)

    fun armStillPaused(context: Context, delayMs: Long = STILL_PAUSED_FIRST_MS) =
        arm(context, ACTION_STILL_PAUSED, delayMs)

    fun cancelStillPaused(context: Context) = cancel(context, ACTION_STILL_PAUSED)

    private fun pending(context: Context, action: String): PendingIntent = PendingIntent.getBroadcast(
        context,
        action.hashCode(),
        Intent(context, RecorderAlarmReceiver::class.java).setAction(action),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    /**
     * ALLOWED WHILE IDLE, on the boot clock: it fires in Doze, and changing the time cannot move it. Not exact: that
     * needs a permission only alarm-clock apps may ask for, and a minute late is still in time to say so.
     */
    private fun arm(context: Context, action: String, delayMs: Long) {
        val alarms = context.getSystemService(AlarmManager::class.java) ?: return
        try {
            alarms.setAndAllowWhileIdle(
                AlarmManager.ELAPSED_REALTIME_WAKEUP,
                SystemClock.elapsedRealtime() + delayMs,
                pending(context, action),
            )
        } catch (e: Exception) {
            // Some phones cap the alarms an app may hold. The hourly check still finds a cut-off recording.
            Log.w(TAG, "could not set the $action alarm", e)
        }
    }

    private fun cancel(context: Context, action: String) {
        runCatching { context.getSystemService(AlarmManager::class.java)?.cancel(pending(context, action)) }
    }

    private const val TAG = "Recorder"
}

/**
 * the alarms above. NOT EXPORTED: only this app's own alarms reach it.
 *
 * The work goes on a background thread under `goAsync`, which gives a receiver about ten seconds: plenty for
 * recovery, which reads a few rows and repairs at most one open file.
 */
class RecorderAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        val app = context.applicationContext
        val done = goAsync()
        RecorderRecovery.scope.launch {
            try {
                when (action) {
                    RecorderAlarms.ACTION_STILL_PAUSED -> RecorderRecovery.remindIfStillPaused(app)
                    RecorderAlarms.ACTION_DEAD_MAN -> RecorderRecovery.run(app, announce = true)
                }
            } finally {
                done.finish()
            }
        }
    }
}

/**
 * the phone restarted, or the app was updated. Either can end a recording without a word: recovery
 * runs and the person is told. NEVER STARTS THE RECORDER: a microphone service may not start from here, and a
 * meeting nobody asked to record must not begin.
 */
class RecorderBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED && intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        val app = context.applicationContext
        val done = goAsync()
        RecorderRecovery.scope.launch {
            try {
                RecorderRecovery.run(app, announce = true)
                // Uploads, and the hourly check, outlive a restart only if WorkManager knows there is work.
                RecorderSync.now(app)
            } finally {
                done.finish()
            }
        }
    }
}

/**
 * one recovery pass with everything Android adds to it: why the process died (logged and kept with
 * the recording), the notice, and the dead man's switch kept armed while a recording runs in this process.
 */
internal object RecorderRecovery {

    /** A failure here is logged; it is never the end of the process the recorder may be running in. */
    val scope = CoroutineScope(
        SupervisorJob() + Dispatchers.IO +
            CoroutineExceptionHandler { _, e -> Log.e(TAG, "recovery failed", e) },
    )

    /**
     * [announce]: post "Recording stopped at …" for every recording cut off that has not been announced. The
     * recorder screen passes false, since it shows them itself; they are marked announced either way.
     */
    suspend fun run(context: Context, announce: Boolean): RecoveryReport {
        val store = RecordingSource.store(RecordingDriverFactory(context))
        val files = AndroidRecordingFiles(context)
        val report = RecoverRecordingsUseCase(store, files, RecordingSource.runtime)
            .execute(SystemClock.elapsedRealtime())
        for (recording in report.cutOff) {
            val info = exitInfo(context, recording) ?: continue
            Log.w(TAG, "recording ${recording.id} was cut off: $info")
            runCatching { NoteRecordingExitUseCase(store).execute(recording.id, info) }
        }
        val notify = (report.cutOff + report.waiting).distinctBy { it.id }
        if (announce) {
            RecorderNotifications.announceInterrupted(context, RecorderNotifications.language(context), notify)
        } else {
            RecorderNotifications.markAnnounced(context, notify)
        }
        // A recording alive in this process: the alarm that brought us here is spent, so set the next one.
        if (RecordingSource.runtime.liveRecordingId != null) RecorderAlarms.armDeadMan(context)
        return report
    }

    /**
     * The person's own pause, still on: remind them, and again in half an hour.
     *
     * the dead man's alarm is off during that pause (it woke the phone every few minutes), so this one
     * stands in for it. Fired in a process with nothing paused, the recorder is gone: recovery runs and the person is
     * told the recording stopped.
     */
    suspend fun remindIfStillPaused(context: Context) {
        val status = LiveRecorder.status.value
        if (status.phase != LiveRecorder.Phase.PAUSED || status.pausedFor != GapReason.USER) {
            run(context, announce = true)
            return
        }
        RecorderNotifications.stillPaused(context, RecorderNotifications.language(context))
        RecorderNotifications.buzz(context)
        RecorderAlarms.armStillPaused(context, RecorderAlarms.STILL_PAUSED_AGAIN_MS)
    }

    /**
     * What Android says ended the process the recording died with (Android 11+): the newest exit since a minute
     * before its last heartbeat. Kept so we learn how phones (OnePlus above all) kill it.
     */
    private fun exitInfo(context: Context, recording: LocalRecording): String? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null
        val manager = context.getSystemService(ActivityManager::class.java) ?: return null
        val since = (recording.heartbeatAt ?: recording.startedAt) - 60_000L
        val exit = runCatching { manager.getHistoricalProcessExitReasons(context.packageName, 0, 10) }
            .getOrNull()
            ?.firstOrNull { it.timestamp >= since }
            ?: return null
        return "reason=${reasonName(exit.reason)} status=${exit.status} importance=${exit.importance} " +
            "pss=${exit.pss} rss=${exit.rss} at=${exit.timestamp} desc=${exit.description.orEmpty()}"
    }

    private fun reasonName(reason: Int): String = when (reason) {
        ApplicationExitInfo.REASON_EXIT_SELF -> "EXIT_SELF"
        ApplicationExitInfo.REASON_SIGNALED -> "SIGNALED"
        ApplicationExitInfo.REASON_LOW_MEMORY -> "LOW_MEMORY"
        ApplicationExitInfo.REASON_CRASH -> "CRASH"
        ApplicationExitInfo.REASON_CRASH_NATIVE -> "CRASH_NATIVE"
        ApplicationExitInfo.REASON_ANR -> "ANR"
        ApplicationExitInfo.REASON_INITIALIZATION_FAILURE -> "INITIALIZATION_FAILURE"
        ApplicationExitInfo.REASON_PERMISSION_CHANGE -> "PERMISSION_CHANGE"
        ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE -> "EXCESSIVE_RESOURCE_USAGE"
        ApplicationExitInfo.REASON_USER_REQUESTED -> "USER_REQUESTED"
        ApplicationExitInfo.REASON_USER_STOPPED -> "USER_STOPPED"
        ApplicationExitInfo.REASON_DEPENDENCY_DIED -> "DEPENDENCY_DIED"
        ApplicationExitInfo.REASON_OTHER -> "OTHER"
        else -> "UNKNOWN($reason)"
    }

    private const val TAG = "Recorder"
}
