package com.humblesolutions.ainotetaker.viewmodel

import android.Manifest
import android.app.ActivityManager
import android.app.Application
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.humblesolutions.ainotetaker.i18n.Language
import android.net.ConnectivityManager
import android.net.Network
import android.os.PowerManager
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.humblesolutions.ainotetaker.config.RecorderLimits
import com.humblesolutions.ainotetaker.model.OthersWaiting
import com.humblesolutions.ainotetaker.model.RecordingState
import com.humblesolutions.ainotetaker.model.RecordingSummary
import com.humblesolutions.ainotetaker.model.User
import com.humblesolutions.ainotetaker.recorder.AndroidRecordingFiles
import com.humblesolutions.ainotetaker.recorder.LiveRecorder
import com.humblesolutions.ainotetaker.recorder.RecorderNotifications
import com.humblesolutions.ainotetaker.recorder.RecorderRecovery
import com.humblesolutions.ainotetaker.recorder.RecorderService
import com.humblesolutions.ainotetaker.recorder.RecorderSync
import com.humblesolutions.ainotetaker.recorder.RecorderTestMode
import com.humblesolutions.ainotetaker.recording.RecordingDriverFactory
import com.humblesolutions.ainotetaker.recording.RecordingSource
import com.humblesolutions.ainotetaker.repository.ApiRecordingStatusRepository
import com.humblesolutions.ainotetaker.repository.RecordingStore
import com.humblesolutions.ainotetaker.usecase.FinishInterruptedUseCase
import com.humblesolutions.ainotetaker.usecase.ObserveRecordingsUseCase
import com.humblesolutions.ainotetaker.usecase.OthersWaitingUseCase
import com.humblesolutions.ainotetaker.usecase.PurgeUploadedRecordingsUseCase
import com.humblesolutions.ainotetaker.usecase.RecorderErrors
import com.humblesolutions.ainotetaker.usecase.SetRecordingDetailsUseCase
import com.humblesolutions.ainotetaker.usecase.SummariesReadyUseCase
import com.humblesolutions.ainotetaker.util.Cancellable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** The details being typed for one recording. */
data class RecordingDraft(
    val recordingId: String = "",
    val title: String = "",
    /** One of the `notetaker.kind.*` ids, or blank. */
    val kind: String = "",
    val location: String = "",
    val attendees: String = "",
)

data class RecorderUiState(
    val live: LiveRecorder.Status = LiveRecorder.Status(),
    val recordings: List<RecordingSummary> = emptyList(),
    val loaded: Boolean = false,
    /** The queue would not open — a full disk, most likely. Nothing can record. */
    val unavailable: Boolean = false,
    val draft: RecordingDraft? = null,
    val savingDraft: Boolean = false,
    val detailsSaved: Boolean = false,
    /**
     * Android may stop the app in the background: it is not exempt from battery optimisation, or the
     * person restricted its background use. A chip on the record card says so for as long as it is true (TAS showed
     * a card once, and it could be put away for good).
     */
    val batteryExempt: Boolean = true,
    val backgroundRestricted: Boolean = false,
    /** a OnePlus (or its OPPO and realme cousins), which needs two more switches of its own. */
    val oneplus: Boolean = false,
    /**
     * the recorder's alerts cannot reach the person: notifications refused (Android 13+) or turned
     * off for the app, or the alerts channel muted. A chip says so; every warning and "it stopped" goes silent.
     */
    val alertsOff: Boolean = false,
    /** only the alerts channel is muted, so its own settings page is the one to open. */
    val alertsChannelMuted: Boolean = false,
    /** other people's meetings on this phone, waiting for them to sign in here again. */
    val othersWaiting: List<OthersWaiting> = emptyList(),
    val online: Boolean = true,
    val errorKey: String? = null,
    /** Sent recordings the server has finished summarising. */
    val summaryReady: Set<String> = emptySet(),
    /** the planned length's numbers, and the length picked for the next recording. */
    val limits: RecorderLimits = RecorderLimits.STANDARD,
    val planMs: Long = RecorderLimits.STANDARD.defaultMs,
    /** the debug-only fast test mode is on (never true in a release build). */
    val testMode: Boolean = false,
) {
    val interrupted: List<RecordingSummary>
        get() = recordings.filter { it.recording.state == RecordingState.INTERRUPTED }

    /** the battery chip shows. */
    val batteryAtRisk: Boolean get() = !batteryExempt || backgroundRestricted

    /** Something is on the phone that the server has not confirmed. */
    val waiting: Boolean
        get() = recordings.any { it.recording.state == RecordingState.STOPPED }
}

/**
 * The recorder screen: the button, the clock, and the list of what is safe.
 *
 * THE SCREEN DOES NOT RECORD. [RecorderService] does, and keeps doing it when
 * this ViewModel is long gone; the screen reads its live state from
 * [LiveRecorder] and the queue from `recordings.db`, and sends it commands.
 */
class RecorderViewModel(application: Application) : AndroidViewModel(application) {

    private val app = application
    private val files = AndroidRecordingFiles(application)
    private val runtime = RecordingSource.runtime

    private var store: RecordingStore? = null
    private var watching: Cancellable? = null
    private var ownerUid: String? = null
    /** the name of the person signed in, kept with each recording they make. */
    private var ownerName: String? = null

    // statuses come from the CRM API, not from Firestore.
    private val summariesReady = SummariesReadyUseCase(ApiRecordingStatusRepository())
    /** Instant start's default length, waiting for the microphone permission's answer. */
    private var pendingPlanMs: Long? = null
    private var asking: Job? = null
    private var askedAt = 0L
    private var askedAbout: Set<String> = emptySet()
    /** The server said one of them is still processing at the last look: look again in seconds, not a minute. */
    private var serverWorking = false
    /** How long the screen waits before its next look for summaries. */
    val summaryCheckEveryMs: Long get() = if (serverWorking) WORKING_CHECK_MS else IDLE_CHECK_MS
    /** How many of the screen's watches are running; see [onScreenShown]. */
    private var shownBy = 0

    private val _uiState = MutableStateFlow(RecorderUiState())
    val uiState: StateFlow<RecorderUiState> = _uiState.asStateFlow()

    private val connectivity = application.getSystemService(ConnectivityManager::class.java)
    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) = _uiState.update { it.copy(online = true) }
        override fun onLost(network: Network) = _uiState.update { it.copy(online = false) }
    }

    init {
        viewModelScope.launch {
            LiveRecorder.status.collect { live ->
                _uiState.update { state ->
                    // A recording that has just stopped opens its details
                    // straight away: the one moment somebody knows what the
                    // meeting was called and is still holding the phone.
                    val draft = state.draft
                        ?: live.justStopped?.let { id -> draftFor(id, state.recordings) }
                    state.copy(
                        live = live,
                        draft = draft,
                        detailsSaved = if (draft != null) false else state.detailsSaved,
                    )
                }
            }
        }
        _uiState.update { it.copy(online = connectivity?.activeNetwork != null) }
        runCatching { connectivity?.registerDefaultNetworkCallback(networkCallback) }
        refreshLimits()
    }

    fun bind(me: User) {
        if (ownerUid == me.uid) return
        ownerUid = me.uid
        ownerName = me.name.takeIf { it.isNotBlank() } ?: me.email
        refreshReliability()

        viewModelScope.launch {
            val opened = try {
                withContext(Dispatchers.IO) { RecordingSource.store(RecordingDriverFactory(app)) }
            } catch (e: Exception) {
                _uiState.update { it.copy(unavailable = true, loaded = true) }
                return@launch
            }
            store = opened
            watching?.cancel()
            watching = ObserveRecordingsUseCase(opened).execute(me.uid) { list ->
                _uiState.update { it.copy(recordings = list, loaded = true) }
                // The list arrives on the store's thread; the asking is the main thread's.
                viewModelScope.launch {
                    refreshSummaries()
                    refreshOthers()
                }
            }
            // Whatever the last run left: a recording cut off is repaired and
            // offered for Continue before the screen has finished drawing.
            val pending = runCatching {
                withContext(Dispatchers.IO) {
                    val now = System.currentTimeMillis()
                    // the screen shows what it finds, so no notice for it.
                    RecorderRecovery.run(app, announce = false)
                    PurgeUploadedRecordingsUseCase(opened, files).execute(now)
                    opened.toSync(me.uid).isNotEmpty()
                }
            }.getOrDefault(true)
            // replacing a run waiting out an earlier failure's backoff.
            if (pending) RecorderSync.now(app, replace = true)
        }
    }

    /**
     * The screen has come into view, or gone out of it. Summaries are looked
     * for only while it is in view: a recording keeps the app running for
     * hours with the screen off, and nobody is reading "Summary ready" then.
     *
     * COUNTED, NOT SET: a new watch can start before the one it replaces has
     * finished stopping, and the late "gone" must not cancel the new "shown".
     */
    fun onScreenShown(value: Boolean) {
        shownBy = maxOf(0, shownBy + if (value) 1 else -1)
    }

    /**
     * Asks the server which sent recordings now have a summary.
     *
     * Called whenever the list changes and every minute while the screen is
     * in view (every 5 s while the server is still processing one), so it
     * asks at most that often unless a newly sent recording appears — the
     * list changes with every chunk while a meeting uploads.
     */
    fun refreshSummaries() {
        if (shownBy == 0 || asking?.isActive == true) return
        val state = _uiState.value
        val waiting = state.recordings
            .filter { it.recording.state == RecordingState.UPLOADED && it.recording.id !in state.summaryReady }
            .map { it.recording.id }
            .toSet()
        if (waiting.isEmpty()) {
            serverWorking = false
            return
        }
        val now = System.currentTimeMillis()
        val every = if (serverWorking) ASK_WORKING_MS else ASK_EVERY_MS
        if (waiting.all { it in askedAbout } && now - askedAt < every) return
        askedAt = now
        askedAbout = waiting
        asking = viewModelScope.launch {
            val check = summariesReady.checkStatuses(state.recordings, state.summaryReady, now)
            serverWorking = check.working
            if (check.ready.isNotEmpty()) _uiState.update { it.copy(summaryReady = it.summaryReady + check.ready) }
        }
    }

    /** whose meetings are waiting for another sign-in on this phone. */
    private suspend fun refreshOthers() {
        val opened = store ?: return
        val me = ownerUid ?: return
        val others = runCatching { withContext(Dispatchers.IO) { OthersWaitingUseCase(opened).execute(me) } }
            .getOrNull() ?: return
        _uiState.update { it.copy(othersWaiting = others) }
    }

    /** Record, once the microphone permission is granted. */
    fun onRecord() {
        _uiState.update { it.copy(errorKey = null, detailsSaved = false) }
        LiveRecorder.acknowledge()
        // No company: every recording is just a meeting. Only the planned length goes with it.
        val planMs = pendingPlanMs ?: _uiState.value.planMs
        pendingPlanMs = null
        RecorderService.start(app, null, planMs, ownerName)
    }

    /* ------------------------------------------ length and Instant start */

    /** A length picked in the row before Record. */
    fun onPlanPicked(planMs: Long) = _uiState.update { it.copy(planMs = it.limits.planOrDefault(planMs)) }

    /**
     * Instant start: the default length. The screen asks for the microphone
     * next and then calls [onRecord]. Details are added after the meeting.
     */
    fun onInstantStart() {
        pendingPlanMs = _uiState.value.limits.defaultMs
    }

    /** Keep recording, from the screen or its dialog. */
    fun onExtend() = RecorderService.send(app, RecorderService.ACTION_EXTEND)

    /** Debug builds only: the hidden switch for the fast test mode. Returns the new state. */
    fun onToggleTestMode(): Boolean {
        if (!RecorderTestMode.available || _uiState.value.live.isLive) return _uiState.value.testMode
        RecorderTestMode.toggle(app)
        refreshLimits()
        return _uiState.value.testMode
    }

    private fun refreshLimits() {
        val limits = RecorderTestMode.limits(app)
        _uiState.update {
            it.copy(
                limits = limits,
                planMs = limits.defaultMs,
                testMode = RecorderTestMode.available && RecorderTestMode.isOn(app),
            )
        }
    }

    fun onPause() = RecorderService.send(app, RecorderService.ACTION_PAUSE)
    fun onResume() = RecorderService.send(app, RecorderService.ACTION_RESUME)
    fun onStop() = RecorderService.send(app, RecorderService.ACTION_STOP)

    fun onContinue(recordingId: String) {
        _uiState.update { it.copy(errorKey = null) }
        LiveRecorder.acknowledge()
        RecorderService.continueRecording(app, recordingId)
    }

    fun onFinish(recordingId: String) {
        val opened = store ?: return
        viewModelScope.launch {
            val finished = runCatching {
                withContext(Dispatchers.IO) {
                    FinishInterruptedUseCase(opened, files, runtime).execute(recordingId)
                }
            }
            if (finished.isFailure) {
                _uiState.update { it.copy(errorKey = RecorderErrors.STORAGE) }
                return@launch
            }
            RecorderNotifications.clearInterrupted(app, recordingId)
            RecorderSync.now(app, recordingId)
        }
    }

    fun onMicrophoneRefused() {
        // Nothing picked for a recording that did not start carries over to the next.
        pendingPlanMs = null
        _uiState.update { it.copy(errorKey = RecorderErrors.MIC_PERMISSION) }
    }

    fun onDismissError() {
        LiveRecorder.acknowledge()
        _uiState.update { it.copy(errorKey = null) }
    }

    fun onEditDetails(summary: RecordingSummary) = _uiState.update {
        it.copy(draft = draftFor(summary.recording.id, it.recordings), detailsSaved = false)
    }

    fun onDraftChange(draft: RecordingDraft) = _uiState.update { it.copy(draft = draft) }

    fun onSaveDetails() {
        val opened = store ?: return
        val draft = _uiState.value.draft ?: return
        _uiState.update { it.copy(savingDraft = true) }
        viewModelScope.launch {
            val saved = runCatching {
                withContext(Dispatchers.IO) {
                    SetRecordingDetailsUseCase(opened).execute(
                        draft.recordingId, draft.title, draft.kind, draft.location, draft.attendees,
                    )
                }
            }
            if (saved.isFailure) {
                _uiState.update { it.copy(savingDraft = false, errorKey = RecorderErrors.STORAGE) }
                return@launch
            }
            LiveRecorder.acknowledge()
            _uiState.update { it.copy(draft = null, savingDraft = false, detailsSaved = true) }
            RecorderSync.now(app, draft.recordingId)
        }
    }

    fun onSkipDetails() {
        LiveRecorder.acknowledge()
        _uiState.update { it.copy(draft = null) }
    }

    fun onSendNow() = RecorderSync.now(app, replace = true)

    /**
     * Checked again whenever the screen comes back, since the answer lives in Settings.
     *
     * every time, not once: the chip stays until the phone lets the app run in the background.
     */
    fun refreshReliability() {
        val power = app.getSystemService(PowerManager::class.java)
        val exempt = power?.isIgnoringBatteryOptimizations(app.packageName) ?: true
        val restricted = Build.VERSION.SDK_INT >= Build.VERSION_CODES.P &&
            app.getSystemService(ActivityManager::class.java)?.isBackgroundRestricted == true
        val maker = Build.MANUFACTURER.orEmpty().lowercase()
        val notifications = NotificationManagerCompat.from(app)
        val permitted = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(app, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        val appOn = permitted && notifications.areNotificationsEnabled()
        // Made now if it is not there yet, so a channel muted before the first recording is seen too.
        RecorderNotifications.ensureChannels(app, Language.default)
        val channelMuted = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            (notifications.getNotificationChannelCompat(RecorderNotifications.ALERTS_CHANNEL)?.importance
                ?: NotificationManagerCompat.IMPORTANCE_HIGH) < NotificationManagerCompat.IMPORTANCE_DEFAULT
        _uiState.update {
            it.copy(
                batteryExempt = exempt,
                backgroundRestricted = restricted,
                oneplus = maker in ONEPLUS_FAMILY,
                alertsOff = !appOn || channelMuted,
                alertsChannelMuted = appOn && channelMuted,
            )
        }
    }

    private fun draftFor(recordingId: String, recordings: List<RecordingSummary>): RecordingDraft {
        val recording = recordings.firstOrNull { it.recording.id == recordingId }?.recording
        return RecordingDraft(
            recordingId = recordingId,
            title = recording?.title.orEmpty(),
            kind = recording?.meetingType.orEmpty(),
            location = recording?.location.orEmpty(),
            attendees = recording?.attendees.orEmpty(),
        )
    }

    override fun onCleared() {
        watching?.cancel()
        runCatching { connectivity?.unregisterNetworkCallback(networkCallback) }
    }

    private companion object {
        const val ASK_EVERY_MS = 55_000L
        const val ASK_WORKING_MS = 4_500L
        const val IDLE_CHECK_MS = 60_000L
        const val WORKING_CHECK_MS = 5_000L
        /** makers whose battery manager needs its own switches (ColorOS / OxygenOS). */
        val ONEPLUS_FAMILY = setOf("oneplus", "oppo", "realme")
    }
}
