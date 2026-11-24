package com.humblesolutions.ainotetaker.recorder

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.text.format.DateFormat
import android.media.AudioAttributes
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import com.humblesolutions.ainotetaker.MainActivity
import com.humblesolutions.ainotetaker.R
import com.humblesolutions.ainotetaker.i18n.Language
import com.humblesolutions.ainotetaker.i18n.Strings
import com.humblesolutions.ainotetaker.model.GapReason
import com.humblesolutions.ainotetaker.model.LocalRecording
import com.humblesolutions.ainotetaker.recording.RecorderWarning
import com.humblesolutions.ainotetaker.recording.RecordingPlans
import com.humblesolutions.ainotetaker.util.LanguageStore
import kotlinx.coroutines.flow.first
import java.util.Date

/**
 * The recorder's notifications: the one that is always there while recording,
 * and the few that matter enough to interrupt.
 *
 * TWO CHANNELS, so somebody can silence one without losing the other. The
 * ongoing one is LOW — it must not chime once a second — and Android requires
 * it anyway for a microphone service. Alerts are HIGH, because each of them
 * (the recording stops soon, the recording stopped, a recording was cut off)
 * is something the person has to know while the phone is in their pocket.
 *
 * titled AI Notebook; the ongoing one shows the planned stop
 * time and, while the warning shows, Keep recording; the warning itself is its
 * own alert with Keep recording and Stop now, and a beep.
 */
internal object RecorderNotifications {

    const val RECORDING_CHANNEL = "tas_recorder_v1"
    const val ALERTS_CHANNEL = "tas_recorder_alerts_v1"
    const val ONGOING_ID = 7301
    private const val ALERT_ID = 7302
    /** the warning before the stop, cancelled once it is answered. */
    private const val WARNING_ID = 7303
    private const val INTERRUPTED_TAG = "recorder-interrupted"
    /** the reminder that the person's pause is still on. */
    private const val STILL_PAUSED_ID = 7304
    /** capture paused by a fault for half a minute and still being retried. */
    private const val CAPTURE_LOST_ID = 7305
    /** recording, but nothing but exact zeros for half a minute. */
    private const val MIC_SILENT_ID = 7306

    /** What `PendingNotification` looks for in the launch intent. */
    const val KIND_EXTRA = "kind"
    const val KIND_RECORDER = "recorder"

    /** what a locked button asks the opened app to do (see [locked]). */
    const val RECORDER_ACTION_EXTRA = "recorder_action"
    const val RECORDER_PAUSE = "pause"
    const val RECORDER_ASK_STOP = "ask_stop"
    const val RECORDER_STOP_NOW = "stop_now"

    private const val PREFS = "recorder"
    private const val ANNOUNCED = "announcedInterrupted"

    /** The language the person chose, for text written outside any screen. */
    suspend fun language(context: Context): Language =
        runCatching { LanguageStore(context).language.first() }.getOrDefault(Language.default)

    /** Safe to call repeatedly; also renames the channels when the language changes. */
    fun ensureChannels(context: Context, language: Language) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        manager.createNotificationChannel(
            NotificationChannel(
                RECORDING_CHANNEL,
                Strings.get("recorder.notif.channel", language),
                NotificationManager.IMPORTANCE_LOW,
            ).apply { setShowBadge(false) },
        )
        manager.createNotificationChannel(
            NotificationChannel(
                ALERTS_CHANNEL,
                Strings.get("recorder.notif.alertsChannel", language),
                NotificationManager.IMPORTANCE_HIGH,
            ).apply { enableVibration(true) },
        )
    }

    /** Opens the app on the recorder, wherever it was. */
    fun openRecorder(context: Context): PendingIntent = PendingIntent.getActivity(
        context,
        0,
        Intent(context, MainActivity::class.java)
            .putExtra(KIND_EXTRA, KIND_RECORDER)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    /**
     * The notification that stays up for the whole recording, with the app
     * open, in the background or swiped away (the service outlives the task).
     *
     * THE CLOCK IS THE SYSTEM'S. A chronometer counting from "now minus the
     * audio so far" ticks by itself, so the notification is rebuilt only when
     * something changes — not once a second for three hours.
     */
    fun ongoing(context: Context, language: Language, status: LiveRecorder.Status): Notification {
        // resuming is the second before the service continues a recording an error ended (or that
        // continue starting): nothing records, so no clock, no Pause, and Stop still ends it.
        val resuming = status.phase == LiveRecorder.Phase.STARTING && status.recordingId != null
        val paused = status.phase == LiveRecorder.Phase.PAUSED || resuming
        val title = when {
            resuming -> "recorder.notif.resuming"
            paused && status.pausedFor == GapReason.CALL -> "recorder.notif.pausedCall"
            paused -> "recorder.notif.paused"
            else -> "recorder.notif.recording"
        }
        // when it stops, as a time of day. Audio and the wall
        // clock run together while recording; a pause moves it, and the next
        // rebuild (on Resume) says the new time.
        val text = if (paused) {
            Strings.get("recorder.notif.pausedText", language)
        } else {
            Strings.get("recorder.notif.recordingText", language, mapOf("time" to stopTime(context, status)))
        }
        val builder = NotificationCompat.Builder(context, RECORDING_CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_recorder)
            .setContentTitle(Strings.get(title, language))
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setContentIntent(openRecorder(context))
            .setWhen(System.currentTimeMillis() - status.elapsedMs)
            .setShowWhen(!paused)
            .setUsesChronometer(!paused)

        // Resume only a pause the person made; a call resumes itself.
        // Pause and Stop ask for the phone to be unlocked first (Android 12+), so a tap in a pocket or
        // by somebody else at the table cannot end or pause the meeting. Resume and Keep recording stay one tap.
        when {
            resuming -> Unit
            !paused -> builder.addAction(
                locked(Strings.get("recorder.pause", language), RECORDER_PAUSE, context),
            )
            status.pausedFor == GapReason.USER -> builder.addAction(
                0, Strings.get("recorder.resume", language),
                RecorderService.actionIntent(context, RecorderService.ACTION_RESUME),
            )
        }
        // while the warning shows and the plan is below the cap.
        if (status.warning?.extendToMs != null && !resuming) {
            builder.addAction(
                0, Strings.get("recorder.keep", language),
                RecorderService.actionIntent(context, RecorderService.ACTION_EXTEND),
            )
        }
        builder.addAction(locked(Strings.get("recorder.stop", language), RECORDER_ASK_STOP, context))
        return builder.build()
    }

    /**
     * a button that works only once the phone is unlocked. It OPENS THE APP rather than reaching the
     * service: on a OnePlus (Android 14) a service button marked `setAuthenticationRequired` asked for the unlock and
     * then never ran, so Stop on the lock screen did nothing. Opening an activity always waits for the unlock and then
     * runs, on every phone. The app then pauses, stops, or asks "Stop recording?" first ([RECORDER_ACTION_EXTRA],
     * `RecorderNotificationAction`).
     */
    private fun locked(title: String, recorderAction: String, context: Context): NotificationCompat.Action =
        NotificationCompat.Action.Builder(
            0,
            title,
            PendingIntent.getActivity(
                context,
                "open:$recorderAction".hashCode(),
                Intent(context, MainActivity::class.java)
                    .putExtra(KIND_EXTRA, KIND_RECORDER)
                    .putExtra(RECORDER_ACTION_EXTRA, recorderAction)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            ),
        )
            .setAuthenticationRequired(true)
            .build()

    /**
     * the planned stop as a time of day, in the phone's 12 or 24 hour format. During the warning, the
     * end of the grace window rounded up to the minute, so it never shows a time before the real stop.
     */
    fun stopTime(context: Context, status: LiveRecorder.Status): String {
        val endAt = status.warning?.endAtMs ?: status.stopAtMs
        val shown = RecordingPlans.shownStopAt(System.currentTimeMillis(), endAt - status.elapsedMs, status.warning != null)
        return DateFormat.getTimeFormat(context).format(Date(shown))
    }

    /**
     * the warning before the stop. HIGH, with the alerts
     * channel's sound and Keep recording (while the plan is below the cap) and
     * Stop now. Cancelled by [cancelWarning] once answered or over.
     */
    fun warn(context: Context, language: Language, warning: RecorderWarning, elapsedMs: Long, capMs: Long) {
        ensureChannels(context, language)
        val left = RecordingPlans.wordsLeft(warning.endAtMs - elapsedMs)
        val body = Strings.get("recorder.warn.body", language, mapOf("left" to left))
        val text = when (val to = warning.extendToMs) {
            null -> Strings.get("recorder.warn.final", language, mapOf("left" to left, "cap" to RecordingPlans.words(capMs)))
            else -> body + " " + Strings.get("recorder.warn.extend", language, mapOf("to" to RecordingPlans.words(to)))
        }
        val builder = NotificationCompat.Builder(context, ALERTS_CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_recorder)
            .setContentTitle(Strings.get("recorder.warn.title", language))
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setAutoCancel(true)
            .setContentIntent(openRecorder(context))
        if (warning.extendToMs != null) {
            builder.addAction(
                0, Strings.get("recorder.keep", language),
                RecorderService.actionIntent(context, RecorderService.ACTION_EXTEND),
            )
        }
        builder.addAction(locked(Strings.get("recorder.stopNow", language), RECORDER_STOP_NOW, context))
        notify(context, null, WARNING_ID, builder.build())
    }

    fun cancelWarning(context: Context) {
        NotificationManagerCompat.from(context).cancel(WARNING_ID)
    }

    /**
     * a short beep on the notification stream, so the warning
     * is heard with the phone locked even when notifications are turned off
     * for the app. Follows the ringer: a phone on silent only vibrates.
     */
    fun beep() {
        val tone = try {
            ToneGenerator(AudioManager.STREAM_NOTIFICATION, 90)
        } catch (e: RuntimeException) {
            return // No audio hardware free for it; the vibration and the notification still go.
        }
        tone.startTone(ToneGenerator.TONE_PROP_BEEP2, 700)
        Handler(Looper.getMainLooper()).postDelayed({ tone.release() }, 1_000)
    }

    /** One interrupting message; each replaces the last. */
    fun alert(context: Context, language: Language, text: String) {
        ensureChannels(context, language)
        val notification = NotificationCompat.Builder(context, ALERTS_CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_recorder)
            .setContentTitle(Strings.get("recorder.title", language))
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(openRecorder(context))
            .build()
        notify(context, null, ALERT_ID, notification)
    }

    /** the last [alert] is no longer true. */
    fun cancelAlert(context: Context) {
        NotificationManagerCompat.from(context).cancel(ALERT_ID)
    }

    /**
     * "A recording was cut off", once per recording.
     *
     * "Recording stopped at 14:02. Tap to continue.", posted by the dead man's alarm, the boot and
     * update receivers, the hourly check, or the recorder service itself when it ends without the person. Tapping it
     * opens the recorder, where Continue is. Remembered so the next pass does not post it again.
     */
    fun announceInterrupted(context: Context, language: Language, recordings: List<LocalRecording>) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val announced = prefs.getStringSet(ANNOUNCED, emptySet()).orEmpty()
        val fresh = recordings.filter { it.id !in announced }
        if (fresh.isEmpty()) return
        for (recording in fresh) {
            cutOff(context, language, recording.id, recording.endedAt ?: recording.heartbeatAt ?: recording.startedAt)
        }
        markAnnounced(context, recordings)
        // felt as well as shown, like the service's own notice: the phone is in a pocket, and the
        // alerts channel may be muted. Once for the lot, and only for fresh notices.
        buzz(context)
    }

    /** the recorder screen showed these itself; no notice for them later. */
    fun markAnnounced(context: Context, recordings: List<LocalRecording>) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val announced = prefs.getStringSet(ANNOUNCED, emptySet()).orEmpty()
        val ids = recordings.map { it.id }.toSet()
        if (ids.isEmpty() || announced.containsAll(ids)) return
        // Only the ids still waiting are kept, so the set cannot grow forever.
        prefs.edit { putStringSet(ANNOUNCED, (announced intersect ids) + ids) }
    }

    /**
     * one cut-off recording, stopped at [stoppedAt] (epoch ms). HIGH, on the alerts channel, under the
     * recording's own tag and id, so the service and the recovery receivers never post it twice.
     */
    fun cutOff(context: Context, language: Language, recordingId: String, stoppedAt: Long) {
        ensureChannels(context, language)
        val time = DateFormat.getTimeFormat(context).format(Date(stoppedAt))
        val text = Strings.get("recorder.notif.cutOff", language, mapOf("time" to time))
        val notification = NotificationCompat.Builder(context, ALERTS_CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_recorder)
            .setContentTitle(Strings.get("recorder.interrupted.title", language))
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_ERROR)
            .setAutoCancel(true)
            .setContentIntent(openRecorder(context))
            .build()
        notify(context, INTERRUPTED_TAG, recordingId.hashCode(), notification)
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val announced = prefs.getStringSet(ANNOUNCED, emptySet()).orEmpty()
        prefs.edit { putStringSet(ANNOUNCED, announced + recordingId) }
    }

    /**
     * capture has been paused by a fault for half a minute (another app has the microphone, or it
     * gives no audio) and is still being retried. Cancelled by [cancelCaptureLost] when it records again.
     */
    fun captureLost(context: Context, language: Language) {
        ensureChannels(context, language)
        val text = Strings.get("recorder.notif.micLost", language)
        val notification = NotificationCompat.Builder(context, ALERTS_CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_recorder)
            .setContentTitle(Strings.get("recorder.title", language))
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_ERROR)
            .setContentIntent(openRecorder(context))
            .build()
        notify(context, null, CAPTURE_LOST_ID, notification)
    }

    fun cancelCaptureLost(context: Context) {
        NotificationManagerCompat.from(context).cancel(CAPTURE_LOST_ID)
    }

    /**
     * recording, but the microphone has given nothing but exact zeros for half a minute: another app
     * may have it, or the room is that quiet. It goes on recording. Cancelled by [cancelMicSilent] on sound again.
     */
    fun micSilent(context: Context, language: Language) {
        ensureChannels(context, language)
        val text = Strings.get("recorder.notif.micSilent", language)
        val notification = NotificationCompat.Builder(context, ALERTS_CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_recorder)
            .setContentTitle(Strings.get("recorder.title", language))
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_ERROR)
            .setAutoCancel(true)
            .setContentIntent(openRecorder(context))
            .build()
        notify(context, null, MIC_SILENT_ID, notification)
    }

    fun cancelMicSilent(context: Context) {
        NotificationManagerCompat.from(context).cancel(MIC_SILENT_ID)
    }

    /** the person's own pause has gone on a while. */
    fun stillPaused(context: Context, language: Language) {
        ensureChannels(context, language)
        val text = Strings.get("recorder.notif.stillPaused", language)
        val notification = NotificationCompat.Builder(context, ALERTS_CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_recorder)
            .setContentTitle(Strings.get("recorder.title", language))
            .setContentText(text)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setAutoCancel(true)
            .setContentIntent(openRecorder(context))
            .addAction(
                0, Strings.get("recorder.resume", language),
                RecorderService.actionIntent(context, RecorderService.ACTION_RESUME),
            )
            .build()
        notify(context, null, STILL_PAUSED_ID, notification)
    }

    fun cancelStillPaused(context: Context) {
        NotificationManagerCompat.from(context).cancel(STILL_PAUSED_ID)
    }

    /** The recording was continued or finished; its notice is out of date. */
    fun clearInterrupted(context: Context, recordingId: String) {
        NotificationManagerCompat.from(context).cancel(INTERRUPTED_TAG, recordingId.hashCode())
    }

    /** Two firm pulses: felt through a pocket, unlike a chime in a noisy hall. */
    fun buzz(context: Context) {
        val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            context.getSystemService(VibratorManager::class.java)?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Vibrator::class.java)
        } ?: return
        val pattern = longArrayOf(0, 400, 250, 400)
        // as an alarm, so it is felt with the alerts channel muted, notifications refused, or the app
        // in the background, which is when a recording that stopped matters most.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            vibrator.vibrate(
                VibrationEffect.createWaveform(pattern, -1),
                VibrationAttributes.createForUsage(VibrationAttributes.USAGE_ALARM),
            )
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            @Suppress("DEPRECATION")
            vibrator.vibrate(
                VibrationEffect.createWaveform(pattern, -1),
                AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).build(),
            )
        } else {
            @Suppress("DEPRECATION")
            vibrator.vibrate(pattern, -1)
        }
    }

    /** Without the permission on Android 13+ this is a no-op, never a crash. */
    private fun notify(context: Context, tag: String?, id: Int, notification: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) return
        val manager = NotificationManagerCompat.from(context)
        if (!manager.areNotificationsEnabled()) return
        try {
            manager.notify(tag, id, notification)
        } catch (e: SecurityException) {
            // POST_NOTIFICATIONS revoked between the check and the call.
        }
    }
}
