package com.humblesolutions.ainotetaker.recorder

import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.google.firebase.auth.FirebaseAuth
import com.humblesolutions.ainotetaker.config.RecorderConfig
import com.humblesolutions.ainotetaker.config.RecorderLimits
import com.humblesolutions.ainotetaker.i18n.Language
import com.humblesolutions.ainotetaker.i18n.Strings
import com.humblesolutions.ainotetaker.model.EndReason
import com.humblesolutions.ainotetaker.model.GapReason
import com.humblesolutions.ainotetaker.model.RecorderRefused
import com.humblesolutions.ainotetaker.recording.RecorderWarning
import com.humblesolutions.ainotetaker.recording.RecordingDriverFactory
import com.humblesolutions.ainotetaker.recording.RecordingPlans
import com.humblesolutions.ainotetaker.recording.RecordingSource
import com.humblesolutions.ainotetaker.repository.FunctionsRecordingUploadRepository
import com.humblesolutions.ainotetaker.repository.HttpChunkTransfer
import com.humblesolutions.ainotetaker.usecase.ContinueRecordingUseCase
import com.humblesolutions.ainotetaker.usecase.FinishInterruptedUseCase
import com.humblesolutions.ainotetaker.usecase.InterruptRecordingUseCase
import com.humblesolutions.ainotetaker.usecase.RecorderErrors
import com.humblesolutions.ainotetaker.usecase.StartRecordingUseCase
import com.humblesolutions.ainotetaker.model.RecordingCompany
import com.humblesolutions.ainotetaker.usecase.SyncRecordingsUseCase
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Keeps a meeting recording with the screen off and the app out of sight.
 *
 * A FOREGROUND SERVICE OF TYPE MICROPHONE is the only thing Android lets hold
 * the microphone once the app leaves the screen. It must be started while the
 * app is on screen — Record is pressed there — and it keeps the right until it
 * stops. It cannot be restarted from the background, which is why it is
 * `START_NOT_STICKY`: if the system kills it, the recording is recovered and
 * offered for Continue, never silently restarted into a new one.
 *
 * WHILE IT RECORDS, IT ALSO UPLOADS. Every closed chunk is sent from here while
 * there is signal, so Stop leaves a minute to send rather than 180.
 * WorkManager takes over for everything after.
 *
 * SWIPING THE APP AWAY DOES NOT STOP IT. The manifest sets
 * `stopWithTask="false"` (Android's default, made explicit) and
 * [onTaskRemoved] leaves the service running, so the recording and its
 * notification carry on with the app gone from recents. It also takes Keep
 * recording ([ACTION_EXTEND]) and owns the warning before the planned stop.
 */
class RecorderService : Service() {

    // A failure in a notification or an upload is logged, never the app's end.
    private val scope = CoroutineScope(
        SupervisorJob() + Dispatchers.Main.immediate +
            CoroutineExceptionHandler { _, e -> Log.e(TAG, "recorder service task failed", e) },
    )
    private var engine: RecorderEngine? = null
    private var starting = false
    private var language: Language = Language.default
    /** the planned length's numbers for the recording running; shorter in the debug test mode. */
    private var limits: RecorderLimits = RecorderLimits.STANDARD
    private var wakeLock: PowerManager.WakeLock? = null
    private var syncing: Job? = null
    @Volatile private var syncAgain = false
    /** onDestroy has run; nothing may be continued or started from here. */
    @Volatile private var destroyed = false
    /** a Record or Continue that arrived while the last recording was saving. */
    private data class PendingStart(
        val continueId: String?,
        val company: RecordingCompany?,
        val planMs: Long?,
        val ownerName: String?,
    )
    private var pendingStart: PendingStart? = null
    /**
     * Stop was sent to the engine running now. Read on the engine's thread too, when it reports the
     * end, so a Stop and an error that cross are settled as the Stop.
     */
    @Volatile private var stopRequested = false
    /** Stop arrived while a recording was being started; it is passed on once the engine runs. */
    private var stopWhileStarting = false
    /**
     * the second between an error ending a recording and the service continuing it. The recording is
     * still the person's live one: Stop ends it as stopped, Pause, Resume and Keep recording wait for it.
     */
    private var continuing: Job? = null
    private var continuingId: String? = null
    /** the recording an automatic continue is starting, until its engine runs. */
    private var autoStartingId: String? = null
    /** the newest start request, so stopping never drops one that arrived after it. */
    private var lastStartId = 0

    /** the person's own pause is on, and its reminder is set. */
    private var userPaused = false
    /** watches the engine's loop go round; see [watch]. */
    private var watchdog: Job? = null
    /** when recent recordings were continued after an error (elapsedRealtime), to stop a loop. */
    private val autoContinues = ArrayDeque<Long>()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        lastStartId = startId
        when (intent?.action) {
            // with the company and the length picked before Record, if any.
            ACTION_START -> begin(
                continueId = null,
                company = companyOf(intent),
                planMs = intent.getLongExtra(EXTRA_PLAN_MS, 0L).takeIf { it > 0L },
                ownerName = intent.getStringExtra(EXTRA_OWNER_NAME),
            )
            ACTION_CONTINUE -> begin(continueId = intent.getStringExtra(EXTRA_RECORDING_ID))
            // A button on a notification that outlived its recording starts
            // an empty service; it must not be left running.
            // nor stopped in the second before an automatic continue, which would cancel it and leave
            // the screen on "Starting": Pause, Resume and Keep recording are ignored then (nothing records to pause).
            ACTION_PAUSE -> engine?.pause() ?: idleUnlessContinuing()
            ACTION_RESUME -> engine?.resume() ?: idleUnlessContinuing()
            // Keep recording, from the warning or the ongoing notification.
            ACTION_EXTEND -> engine?.extend() ?: idleUnlessContinuing()
            ACTION_STOP -> {
                val current = engine
                when {
                    current != null -> {
                        stopRequested = true
                        current.stop()
                    }
                    // the person's Stop is never lost: before an automatic continue it ends the
                    // recording as stopped; while one is starting it is passed on once the engine runs.
                    continuing != null -> stopBeforeContinuing()
                    starting -> stopWhileStarting = true
                    else -> stopIfIdle()
                }
            }
            else -> idleUnlessContinuing()
        }
        return START_NOT_STICKY
    }

    /**
     * [auto] is the service itself continuing a recording an error just ended, with the same owner
     * and limits, the time in between marked as an interruption.
     */
    private fun begin(
        continueId: String?,
        company: RecordingCompany? = null,
        planMs: Long? = null,
        auto: Boolean = false,
        ownerName: String? = null,
    ) {
        // FIRST, ALWAYS. A service started with startForegroundService() that
        // does not call startForeground() in time crashes the app, whatever
        // else goes wrong afterwards.
        // not when the service continues a recording itself: it never left the foreground, and Android
        // can refuse a second startForeground from the background, which stopped the service without a word.
        if (!auto && !enterForeground()) {
            // never stopSelf() here: a second START or CONTINUE refused while a recording runs
            // must not end it.
            stopIfIdle()
            return
        }
        // an automatic continue that cannot run (something else is recording or starting) leaves the
        // recording waiting for Continue, and the person is told at once.
        if (auto && (engine != null || starting || continuing != null)) {
            Log.w(TAG, "could not continue $continueId: the recorder is busy")
            continueId?.let {
                RecorderAlarms.cancelDeadMan(applicationContext)
                cutOff(it)
            }
            return
        }
        // Record pressed while the last recording is still saving is kept for when it has, not
        // swallowed (TAS dropped it, and then stopped the service).
        engine?.let { current ->
            if (stopRequested || current.ending) pendingStart = PendingStart(continueId, company, planMs, ownerName)
            return
        }
        // a recording about to be continued by the service is still running, as far as Record goes.
        if (starting || continuing != null) return
        starting = true
        stopWhileStarting = false
        autoStartingId = continueId.takeIf { auto }
        // continued by the service, the screen keeps its clock and plan while it starts.
        LiveRecorder.update {
            if (auto) it.copy(phase = LiveRecorder.Phase.STARTING) else LiveRecorder.Status(phase = LiveRecorder.Phase.STARTING)
        }

        scope.launch {
            var started: String? = null
            try {
                language = RecorderNotifications.language(applicationContext)
                RecorderNotifications.ensureChannels(applicationContext, language)
                val store = withContext(Dispatchers.IO) {
                    RecordingSource.store(RecordingDriverFactory(applicationContext))
                }
                // continued by the service itself, the recording's own owner, whoever is signed in.
                val owner = (if (auto) continueId?.let { withContext(Dispatchers.IO) { store.byId(it) } }?.ownerUid
                    else FirebaseAuth.getInstance().currentUser?.uid)
                    ?: throw RecorderRefused(if (auto) RecorderErrors.NOT_FOUND else RecorderErrors.SIGNED_OUT)
                val files = AndroidRecordingFiles(applicationContext)
                val runtime = RecordingSource.runtime
                // uploads and recovery keep running after this service is gone (hourly while anything
                // waits, and after a reboot), set up BEFORE the recording exists, so nothing that throws after it can
                // leave audio on the phone that nothing will come back for.
                RecorderSync.keepWatching(applicationContext)
                val now = System.currentTimeMillis()
                // read once per recording; the debug test mode can shorten them. Kept as they were
                // when the service continues one itself.
                val limits = if (auto) limits else RecorderTestMode.limits(applicationContext)
                this@RecorderService.limits = limits
                val start = withContext(Dispatchers.IO) {
                    if (continueId == null) {
                        // the company and the length are written with the recording's first row.
                        // and the person's name, so another account on this phone can be told whose it is.
                        StartRecordingUseCase(store, files, runtime).execute(owner, now, company, planMs, limits, ownerName)
                    } else {
                        ContinueRecordingUseCase(store, files, runtime).execute(
                            continueId, owner, now, limits,
                            if (auto) GapReason.INTERRUPTION else GapReason.KILLED,
                            // a meeting under way is not cut off for want of 100 MB.
                            if (auto) RecorderConfig.MIN_FREE_BYTES_AUTO_CONTINUE else RecorderConfig.MIN_FREE_BYTES,
                        )
                    }
                }
                started = start.recordingId
                // from the first second, a recording that dies without a word is announced.
                RecorderAlarms.armDeadMan(applicationContext)
                continueId?.let { RecorderNotifications.clearInterrupted(applicationContext, it) }
                engine = RecorderEngine(applicationContext, store, files, runtime, EngineEvents(), limits)
                    .also { it.start(start) }
                autoStartingId = null
                watch()
                Log.i(TAG, "recording ${start.recordingId} started (prior ${start.priorMs} ms)")
                // Stop pressed while it was starting.
                if (stopWhileStarting) {
                    stopWhileStarting = false
                    stopRequested = true
                    engine?.stop()
                }
            } catch (e: CancellationException) {
                // the service was destroyed mid-start. A recording made but never handed to an engine
                // is kept for Continue, not left "live" in this process.
                started?.let { withContext(NonCancellable) { abandonStart(it) } }
                throw e
            } catch (e: RecorderRefused) {
                if (auto && continueId != null && autoContinueFailed(continueId)) return@launch
                fail(e.stringKey)
            } catch (e: Exception) {
                Log.e(TAG, "could not start recording", e)
                // A recording made in the queue but never handed to an engine
                // would stay "live" in this process and refuse the next Record.
                started?.let { abandonStart(it) }
                if (auto && continueId != null && autoContinueFailed(continueId)) return@launch
                fail(RecorderErrors.START_FAILED)
            } finally {
                starting = false
                autoStartingId = null
            }
        }
    }

    /** kept for Continue (TAS stopped it, so a failed Continue could never be tried again). */
    private suspend fun abandonStart(recordingId: String) {
        runCatching {
            withContext(Dispatchers.IO) {
                val store = RecordingSource.store(RecordingDriverFactory(applicationContext))
                InterruptRecordingUseCase(store, AndroidRecordingFiles(applicationContext), RecordingSource.runtime)
                    .execute(recordingId, System.currentTimeMillis(), SystemClock.elapsedRealtime())
            }
        }
    }

    /**
     * THE WATCHDOG. The engine's loop goes round many times a second, paused or not; one that has not
     * gone round for half a minute is stuck in something that will not return, and is recording nothing. The
     * heartbeat stops with it, but the dead man's alarm cannot tell, because this process still says it is live:
     * so the person is told from here. Both clocks are uptime, which stops in deep sleep.
     */
    private fun watch() {
        watchdog?.cancel()
        watchdog = scope.launch {
            var alerted = false
            while (true) {
                delay(WATCHDOG_EVERY_MS)
                val current = engine ?: break
                if (current.ending) continue
                val silent = SystemClock.uptimeMillis() - current.lastTick
                if (silent > WATCHDOG_STUCK_MS && !alerted) {
                    alerted = true
                    Log.e(TAG, "the recorder loop has not gone round for $silent ms")
                    RecorderNotifications.buzz(applicationContext)
                    RecorderNotifications.alert(
                        applicationContext, language, Strings.get("recorder.notif.stuck", language),
                    )
                } else if (silent <= WATCHDOG_STUCK_MS && alerted) {
                    alerted = false
                    Log.i(TAG, "the recorder loop is going round again")
                    RecorderNotifications.cancelAlert(applicationContext)
                }
            }
        }
    }

    /**
     * an automatic continue did not start. The recording waits for Continue and the person is told at
     * once; or, when Stop was pressed meanwhile, it ends as stopped and nothing is left to do (true).
     */
    private fun autoContinueFailed(recordingId: String): Boolean {
        RecorderAlarms.cancelDeadMan(applicationContext)
        if (!stopWhileStarting) {
            cutOff(recordingId)
            return false
        }
        stopWhileStarting = false
        starting = false
        finishStopped(recordingId)
        stopIfIdle()
        return true
    }

    /** the second before an automatic continue: nothing to pause, resume or extend, and not idle. */
    private fun idleUnlessContinuing() {
        if (continuing == null) stopIfIdle()
    }

    /**
     * Stop in the second before an automatic continue. The continue is called off and the recording,
     * already sealed and waiting, ends as the person asked: stopped, details on the screen, and sent.
     */
    private fun stopBeforeContinuing() {
        val id = continuingId ?: return
        continuing?.cancel()
        continuing = null
        continuingId = null
        Log.i(TAG, "Stop before $id was continued; it ends here")
        RecorderAlarms.cancelDeadMan(applicationContext)
        finishStopped(id)
        endService()
    }

    /**
     * a recording left INTERRUPTED by an error, ended as stopped because the person pressed Stop.
     * On the recovery scope, not this service's, so it finishes even if the service goes in the meantime.
     */
    private fun finishStopped(recordingId: String) {
        val context = applicationContext
        LiveRecorder.update { LiveRecorder.Status(justStopped = recordingId) }
        RecorderNotifications.clearInterrupted(context, recordingId)
        RecorderRecovery.scope.launch {
            runCatching {
                val store = RecordingSource.store(RecordingDriverFactory(context))
                FinishInterruptedUseCase(store, AndroidRecordingFiles(context), RecordingSource.runtime)
                    .execute(recordingId, EndReason.STOPPED)
            }.onFailure { Log.e(TAG, "could not finish $recordingId after Stop; recovery will send it", it) }
            RecorderSync.now(context, recordingId)
        }
    }

    /** nothing more to record; the service lets go. */
    private fun endService() {
        releaseWakeLock()
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf(lastStartId)
    }

    /** "Recording stopped at HH:MM. Tap to continue.", felt as well as shown. */
    private fun cutOff(recordingId: String) {
        RecorderNotifications.buzz(applicationContext)
        RecorderNotifications.cutOff(applicationContext, language, recordingId, System.currentTimeMillis())
    }

    /** at most a few automatic continues in ten minutes, so an error that comes back is not a loop. */
    private fun mayAutoContinue(): Boolean {
        val now = SystemClock.elapsedRealtime()
        while (autoContinues.isNotEmpty() && now - autoContinues.first() > AUTO_CONTINUE_WINDOW_MS) {
            autoContinues.removeFirst()
        }
        if (autoContinues.size >= MAX_AUTO_CONTINUES) return false
        autoContinues.addLast(now)
        return true
    }

    /** Declares the microphone service, with its notification. False when Android refuses. */
    private fun enterForeground(): Boolean = try {
        RecorderNotifications.ensureChannels(this, language)
        ServiceCompat.startForeground(
            this,
            RecorderNotifications.ONGOING_ID,
            RecorderNotifications.ongoing(this, language, LiveRecorder.status.value),
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            } else 0,
        )
        true
    } catch (e: Exception) {
        // No RECORD_AUDIO (Android 14+ checks it here), or the app was not
        // on screen. The screen asks for both before starting, so this is the
        // backstop, not the path.
        Log.e(TAG, "could not enter the foreground", e)
        if (engine == null) {
            LiveRecorder.update {
                LiveRecorder.Status(
                    errorKey = if (e is SecurityException) RecorderErrors.MIC_PERMISSION else RecorderErrors.START_FAILED,
                )
            }
        }
        false
    }

    private fun fail(errorKey: String) {
        starting = false
        // a refusal never overwrites the status of a recording that is running.
        if (engine == null) LiveRecorder.update { LiveRecorder.Status(errorKey = errorKey) }
        stopIfIdle()
    }

    private fun stopIfIdle() {
        if (engine != null || starting || continuing != null) return
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        // not if a newer start request has arrived since.
        stopSelf(lastStartId)
    }

    /**
     * Sends closed chunks while the meeting goes on.
     *
     * ONE AT A TIME, AND NEVER LOST. A chunk that closes while a run is going
     * sets [syncAgain], and the run goes round once more — the shared sync
     * lock would stop two running at once anyway, but this keeps them from
     * queueing up behind each other during a long run with poor signal.
     */
    private fun syncWhileRecording() {
        if (syncing?.isActive == true) {
            syncAgain = true
            return
        }
        syncing = scope.launch(Dispatchers.IO) {
            do {
                syncAgain = false
                /*
                 * WHOEVER IS SIGNED IN NOW, not whoever pressed Record. The
                 * upload authenticates as the current account, so the queue it
                 * sends must be that account's; a recording made before a
                 * switch waits for its owner, like any other.
                 */
                val owner = FirebaseAuth.getInstance().currentUser?.uid ?: break
                val outcome = runCatching {
                    val store = RecordingSource.store(RecordingDriverFactory(applicationContext))
                    val files = AndroidRecordingFiles(applicationContext)
                    SyncRecordingsUseCase(
                        store,
                        FunctionsRecordingUploadRepository(),
                        HttpChunkTransfer(files),
                        files,
                        RecordingSource.runtime,
                    ).execute(owner, RecorderDevices.current(applicationContext), System.currentTimeMillis())
                }
                outcome.exceptionOrNull()?.let {
                    // cancelled is the recording ending (the service's scope goes with it), not a
                    // failure: passed on, never logged as one.
                    if (it is CancellationException) throw it
                    Log.w(TAG, "upload during recording failed", it)
                }
            } while (syncAgain)
        }
    }

    private fun refreshNotification() {
        // also in the second before an automatic continue, and while it starts, so the notification
        // does not go on saying "recording" with a Pause that does nothing.
        if (engine == null && continuing == null && !starting) return
        try {
            NotificationManagerCompat.from(this).notify(
                RecorderNotifications.ONGOING_ID,
                RecorderNotifications.ongoing(this, language, LiveRecorder.status.value),
            )
        } catch (e: SecurityException) {
            // Notifications refused: recording carries on without the update.
        }
    }

    /**
     * The CPU stays awake while the microphone is being read — including
     * through a call, when the loop must notice the call end — and is let go
     * while the person has paused, which can be hours.
     *
     * Re-taken on every resume, so the bound — the 3-hour cap plus a margin, which
     * stops a recorder that somehow never ends from holding the phone awake
     * forever — is counted from the last resume, not from the first press.
     */
    private fun updateWakeLock() {
        val status = LiveRecorder.status.value
        // Stopping too: the last frames and the last rows are written then.
        val capturing = status.phase == LiveRecorder.Phase.RECORDING ||
            status.phase == LiveRecorder.Phase.STOPPING ||
            // a recording being continued by the service after an error.
            status.phase == LiveRecorder.Phase.STARTING ||
            (status.phase == LiveRecorder.Phase.PAUSED && status.pausedFor != GapReason.USER)
        if (!capturing) {
            releaseWakeLock()
            return
        }
        val power = getSystemService(PowerManager::class.java) ?: return
        val lock = wakeLock ?: power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "tas:recorder").apply {
            setReferenceCounted(false)
        }.also { wakeLock = it }
        lock.acquire(RecorderConfig.MAX_DURATION_MS + WAKE_LOCK_MARGIN_MS)
    }

    /**
     * a pause the person made gets a reminder after ten minutes (an alarm, since the phone may sleep
     * through a pause); resuming or stopping takes it back.
     *
     * THE DEAD MAN'S ALARM IS OFF FOR THE PAUSE. Pushed on by every heartbeat, it woke the phone every two to three
     * and a half minutes of a pause that can last hours. The still-paused alarm stands in for it: in a process that is
     * gone it runs recovery and says the recording stopped (see `RecorderRecovery.remindIfStillPaused`). Resuming
     * arms it again.
     */
    private fun updateStillPaused() {
        val status = LiveRecorder.status.value
        val paused = status.phase == LiveRecorder.Phase.PAUSED && status.pausedFor == GapReason.USER
        if (paused == userPaused) return
        userPaused = paused
        if (paused) {
            RecorderAlarms.armStillPaused(applicationContext)
            RecorderAlarms.cancelDeadMan(applicationContext)
        } else {
            RecorderAlarms.cancelStillPaused(applicationContext)
            RecorderNotifications.cancelStillPaused(applicationContext)
            // Not once it is ending: the end cancels it, and a late re-arm would only wake the phone for nothing.
            if (status.phase == LiveRecorder.Phase.RECORDING || status.phase == LiveRecorder.Phase.PAUSED) {
                RecorderAlarms.armDeadMan(applicationContext)
            }
        }
    }

    private fun releaseWakeLock() {
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
    }

    /** The engine's thread hands everything to the main thread through here. */
    private inner class EngineEvents : RecorderEngine.Listener {

        override fun onPhaseChanged() {
            scope.launch {
                refreshNotification()
                updateWakeLock()
                updateStillPaused()
            }
        }

        override fun onChunkClosed() {
            scope.launch { syncWhileRecording() }
        }

        /**
         * on the engine's thread; an alarm is a quick call, and must not wait for the main thread. Not
         * during the person's own pause, when the still-paused alarm stands in for it (see [updateStillPaused]).
         */
        override fun onHeartbeat() {
            val status = LiveRecorder.status.value
            if (status.phase == LiveRecorder.Phase.PAUSED && status.pausedFor == GapReason.USER) return
            RecorderAlarms.armDeadMan(applicationContext)
        }

        /** half a minute without the microphone, still trying: heard, felt and shown. */
        override fun onCaptureLost() {
            scope.launch {
                RecorderNotifications.buzz(applicationContext)
                RecorderNotifications.captureLost(applicationContext, language)
            }
        }

        override fun onCaptureBack() {
            scope.launch { RecorderNotifications.cancelCaptureLost(applicationContext) }
        }

        /** half a minute of exact zeros; still recording. Felt and shown, once. */
        override fun onMicSilent() {
            scope.launch {
                RecorderNotifications.buzz(applicationContext)
                RecorderNotifications.micSilent(applicationContext, language)
            }
        }

        override fun onMicSound() {
            scope.launch { RecorderNotifications.cancelMicSilent(applicationContext) }
        }

        /** under 50 MB free. Heard and felt while there is still time to free some space. */
        override fun onLowSpace() {
            scope.launch {
                RecorderNotifications.buzz(applicationContext)
                RecorderNotifications.alert(
                    applicationContext, language, Strings.get("recorder.notif.lowSpace", language),
                )
            }
        }

        /**
         * the planned length is reached, or the cap is near.
         * Heard and felt with the phone locked (a beep and two pulses), shown
         * as an alert with Keep recording and Stop now, and Keep recording
         * added to the ongoing notification. The screen shows its own dialog
         * from LiveRecorder.
         */
        override fun onWarning(warning: RecorderWarning) {
            scope.launch {
                RecorderNotifications.beep()
                RecorderNotifications.buzz(applicationContext)
                val status = LiveRecorder.status.value
                RecorderNotifications.warn(applicationContext, language, warning, status.elapsedMs, limits.capMs)
                refreshNotification()
            }
        }

        /** Keep recording was pressed; the warning is answered. */
        override fun onPlanExtended() {
            scope.launch {
                RecorderNotifications.cancelWarning(applicationContext)
                refreshNotification()
            }
        }

        /**
         * On the engine's thread, and deliberately not through [scope]: when
         * the service is being destroyed the scope is already cancelled, and
         * the upload must still be queued and the screen still told.
         */
        override fun onFinished(
            recordingId: String,
            reason: EndReason,
            hadAudio: Boolean,
            errorKey: String?,
            unchosen: Boolean,
        ) {
            // "Could not start" is the wrong sentence for a recording that ran
            // for forty minutes; the notification below says it is safe.
            val shown = if (hadAudio && errorKey == RecorderErrors.START_FAILED) null else errorKey
            // Stop was pressed and an error ended it before the engine read the Stop: it ends as the
            // person asked, not as cut off, and is not continued.
            val stoppedAnyway = unchosen && hadAudio && stopRequested
            // an error ended it while this service is alive: the same recording goes on at once.
            val again = unchosen && errorKey != null && hadAudio && !destroyed && !stopRequested
            LiveRecorder.update {
                when {
                    // the clock and the plan stay on the screen while it starts again.
                    again -> it.copy(
                        phase = LiveRecorder.Phase.STARTING, recordingId = recordingId, pausedFor = null,
                        level = 0f, warning = null, errorKey = null, justStopped = null,
                    )
                    stoppedAnyway -> LiveRecorder.Status(justStopped = recordingId)
                    // one nobody chose to end waits on the screen for Continue, not for its details.
                    else -> LiveRecorder.Status(errorKey = shown, justStopped = recordingId.takeIf { hadAudio && !unchosen })
                }
            }
            if (stoppedAnyway) finishStopped(recordingId) else if (hadAudio) RecorderSync.now(applicationContext, recordingId)
            RecorderNotifications.cancelCaptureLost(applicationContext)
            RecorderNotifications.cancelMicSilent(applicationContext)
            RecorderNotifications.cancelWarning(applicationContext)
            RecorderAlarms.cancelStillPaused(applicationContext)
            RecorderNotifications.cancelStillPaused(applicationContext)
            when {
                // the chunk ceiling says so; only the cap says "3 hours".
                reason == EndReason.LIMIT && errorKey == RecorderErrors.CHUNK_LIMIT -> {
                    RecorderNotifications.buzz(applicationContext)
                    RecorderNotifications.alert(
                        applicationContext, language, Strings.get("recorder.notif.chunkLimit", language),
                    )
                }
                reason == EndReason.LIMIT -> {
                    RecorderNotifications.buzz(applicationContext)
                    RecorderNotifications.alert(
                        applicationContext, language,
                        Strings.get("recorder.notif.limit", language, mapOf("cap" to RecordingPlans.words(limits.capMs))),
                    )
                }
                // nobody answered the warning; it stopped, and says it is uploading.
                reason == EndReason.PLANNED -> {
                    RecorderNotifications.buzz(applicationContext)
                    RecorderNotifications.alert(
                        applicationContext, language, Strings.get("recorder.notif.planned", language),
                    )
                }
                again || stoppedAnyway -> Unit
                // every end the person did not choose is felt and shown, and the recording waits for
                // Continue (TAS said nothing when the system destroyed the service, and stopped the recording).
                unchosen && hadAudio -> cutOff(recordingId)
                unchosen -> {
                    RecorderNotifications.buzz(applicationContext)
                    RecorderNotifications.alert(
                        applicationContext, language, Strings.get(errorKey ?: RecorderErrors.START_FAILED, language),
                    )
                }
                hadAudio && errorKey != null -> {
                    // felt too, like every stop the person did not choose.
                    RecorderNotifications.buzz(applicationContext)
                    RecorderNotifications.alert(
                        applicationContext, language, Strings.get("recorder.notif.stoppedError", language),
                    )
                }
            }
            // ended here, so nothing is left for the dead man's alarm to announce. Not while it goes on.
            if (!again) RecorderAlarms.cancelDeadMan(applicationContext)
            scope.launch {
                // a Stop that came after the engine ended but before this ran is still the person's.
                val stopAsked = stopRequested
                engine = null
                userPaused = false
                stopRequested = false
                if (again) {
                    when {
                        stopAsked -> {
                            Log.i(TAG, "Stop came as $recordingId ended by an error; it ends here")
                            RecorderAlarms.cancelDeadMan(applicationContext)
                            finishStopped(recordingId)
                        }
                        mayAutoContinue() -> {
                            Log.w(TAG, "recording $recordingId ended by an error; continuing it")
                            // kept, so a Stop in this second can call it off (see ACTION_STOP).
                            continuingId = recordingId
                            continuing = scope.launch {
                                delay(AUTO_CONTINUE_DELAY_MS)
                                continuing = null
                                continuingId = null
                                begin(continueId = recordingId, auto = true)
                            }
                            refreshNotification()
                            return@launch
                        }
                        else -> {
                            Log.w(TAG, "recording $recordingId keeps failing; left for Continue")
                            RecorderAlarms.cancelDeadMan(applicationContext)
                            cutOff(recordingId)
                            LiveRecorder.update { LiveRecorder.Status(errorKey = shown) }
                        }
                    }
                }
                // Record pressed while this one was saving.
                pendingStart?.let { next ->
                    pendingStart = null
                    if (!destroyed) {
                        begin(next.continueId, next.company, next.planMs, ownerName = next.ownerName)
                        return@launch
                    }
                }
                endService()
            }
        }
    }

    /**
     * the app was swiped away from recents. The service is not
     * stopped (`stopWithTask="false"`), so the recording carries on; the
     * notification is posted again so it is plainly still there.
     */
    override fun onTaskRemoved(rootIntent: Intent?) {
        Log.i(TAG, "app removed from recents; the recording carries on")
        refreshNotification()
    }

    override fun onDestroy() {
        // Destroyed without a Stop — the system reclaiming the service, or the
        // app being stopped from the task manager. The engine closes the file
        // on its own thread and keeps the recording for
        // Continue, with a buzz and "Recording stopped at HH:MM. Tap to
        // continue."; TAS stopped it, silently. If the process dies first, the
        // dead man's alarm says the same from a fresh process.
        destroyed = true
        engine?.stop(EndReason.INTERRUPTED)
        // destroyed in the second before an automatic continue, or while one was starting. The
        // continue goes with the scope, so the screen must not stay on "Starting" (counted as live, with Continue and
        // Record hidden), and the recording, sealed and waiting, is announced for Continue now.
        // An engine that has ended but whose end this scope never got to handle counts as none.
        val status = LiveRecorder.status.value
        if (engine?.ending != false && status.phase == LiveRecorder.Phase.STARTING) {
            val waiting = continuingId ?: autoStartingId ?: status.recordingId
            LiveRecorder.update { LiveRecorder.Status() }
            waiting?.let {
                RecorderAlarms.cancelDeadMan(applicationContext)
                cutOff(it)
            }
        }
        continuing = null
        continuingId = null
        releaseWakeLock()
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val TAG = "Recorder"
        private const val WAKE_LOCK_MARGIN_MS = 10 * 60_000L
        /** a breath between an error and continuing, and at most five of those in ten minutes. */
        private const val AUTO_CONTINUE_DELAY_MS = 1_000L
        private const val AUTO_CONTINUE_WINDOW_MS = 10 * 60_000L
        private const val MAX_AUTO_CONTINUES = 5
        /** the watchdog looks every ten seconds and speaks after thirty without a turn of the loop. */
        private const val WATCHDOG_EVERY_MS = 10_000L
        private const val WATCHDOG_STUCK_MS = 30_000L

        const val ACTION_START = "com.humblesolutions.ainotetaker.recorder.START"
        const val ACTION_CONTINUE = "com.humblesolutions.ainotetaker.recorder.CONTINUE"
        const val ACTION_PAUSE = "com.humblesolutions.ainotetaker.recorder.PAUSE"
        const val ACTION_RESUME = "com.humblesolutions.ainotetaker.recorder.RESUME"
        const val ACTION_STOP = "com.humblesolutions.ainotetaker.recorder.STOP"
        /** Keep recording. */
        const val ACTION_EXTEND = "com.humblesolutions.ainotetaker.recorder.EXTEND"
        const val EXTRA_RECORDING_ID = "recordingId"
        /** the length picked before Record, in audio ms. */
        private const val EXTRA_PLAN_MS = "planMs"
        /** the name of the person recording. */
        private const val EXTRA_OWNER_NAME = "ownerName"

        // the company picked before Record (RecordingCompany), as three extras.
        private const val EXTRA_COMPANY_CHOICE = "companyChoice"
        private const val EXTRA_COMPANY_ID = "companyId"
        private const val EXTRA_COMPANY_NAME = "companyName"

        private fun companyOf(intent: Intent): RecordingCompany? = RecordingCompany.of(
            intent.getStringExtra(EXTRA_COMPANY_CHOICE),
            intent.getStringExtra(EXTRA_COMPANY_ID),
            intent.getStringExtra(EXTRA_COMPANY_NAME),
        )

        /**
         * Record was pressed. Call only with RECORD_AUDIO granted and the app on screen.
         * [company] is what staff picked first; null for a contributor, who is not asked.
         * [planMs] is the length picked; null for the default.
         */
        fun start(context: Context, company: RecordingCompany? = null, planMs: Long? = null, ownerName: String? = null) {
            ContextCompat.startForegroundService(
                context,
                Intent(context, RecorderService::class.java)
                    .setAction(ACTION_START)
                    .putExtra(EXTRA_OWNER_NAME, ownerName)
                    .putExtra(EXTRA_COMPANY_CHOICE, company?.choice?.id)
                    .putExtra(EXTRA_COMPANY_ID, company?.id)
                    .putExtra(EXTRA_COMPANY_NAME, company?.name)
                    .putExtra(EXTRA_PLAN_MS, planMs ?: 0L),
            )
        }

        /** Continue was pressed on a recording that was cut off. */
        fun continueRecording(context: Context, recordingId: String) {
            ContextCompat.startForegroundService(
                context,
                Intent(context, RecorderService::class.java)
                    .setAction(ACTION_CONTINUE)
                    .putExtra(EXTRA_RECORDING_ID, recordingId),
            )
        }

        /** Pause, Resume or Stop, to a service that is already running. */
        fun send(context: Context, action: String) {
            try {
                context.startService(Intent(context, RecorderService::class.java).setAction(action))
            } catch (e: IllegalStateException) {
                // Not running and not allowed to start from here: nothing to control.
            }
        }

        /** The notification's buttons. */
        internal fun actionIntent(context: Context, action: String): PendingIntent =
            PendingIntent.getService(
                context,
                action.hashCode(),
                Intent(context, RecorderService::class.java).setAction(action),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
    }
}
