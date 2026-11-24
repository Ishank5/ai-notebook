package com.humblesolutions.ainotetaker.ui.recorder

import com.humblesolutions.ainotetaker.util.PendingNotification
import android.Manifest
import android.content.Intent
import android.os.Build
import android.provider.Settings
import android.text.format.DateFormat
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.humblesolutions.ainotetaker.config.RecorderConfig
import com.humblesolutions.ainotetaker.config.RecorderLimits
import com.humblesolutions.ainotetaker.i18n.Language
import com.humblesolutions.ainotetaker.model.GapReason
import com.humblesolutions.ainotetaker.model.RecordingState
import com.humblesolutions.ainotetaker.model.RecordingSummary
import com.humblesolutions.ainotetaker.model.User
import com.humblesolutions.ainotetaker.recorder.LiveRecorder
import com.humblesolutions.ainotetaker.recorder.RecorderNotifications
import com.humblesolutions.ainotetaker.recorder.RecorderService
import com.humblesolutions.ainotetaker.recorder.RecorderTestMode
import com.humblesolutions.ainotetaker.recording.RecordingPlans
import com.humblesolutions.ainotetaker.ui.components.ButtonTone
import com.humblesolutions.ainotetaker.ui.components.ChipTone
import com.humblesolutions.ainotetaker.ui.components.TasButton
import com.humblesolutions.ainotetaker.ui.components.TasCard
import com.humblesolutions.ainotetaker.ui.components.TasChip
import com.humblesolutions.ainotetaker.ui.components.TasField
import com.humblesolutions.ainotetaker.ui.theme.Tas
import com.humblesolutions.ainotetaker.ui.theme.TasShape
import com.humblesolutions.ainotetaker.ui.theme.tr
import com.humblesolutions.ainotetaker.usecase.RecorderErrors
import com.humblesolutions.ainotetaker.viewmodel.RecorderUiState
import com.humblesolutions.ainotetaker.viewmodel.RecorderViewModel
import com.humblesolutions.ainotetaker.viewmodel.RecordingDraft
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.delay

private const val PENDING_RECORD = "record"
private const val PENDING_CONTINUE = "continue:"

/** The kinds offered as chips: the id sent with the recording, and its label. */
private val MEETING_KINDS = listOf(
    "meeting" to "Meeting",
    "lecture" to "Lecture",
    "voice_note" to "Voice note",
    "journal" to "Journal",
    "interview" to "Interview",
    "other" to "Other",
)

/** The record button's diameter; the level ring sits just outside it. */
private val RECORD_BUTTON = 112.dp

/**
 * The meeting recorder: one big round button, a big clock, and the list of what is safe on the phone.
 */
@Composable
fun RecorderScreen(me: User, viewModel: RecorderViewModel) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val colors = Tas.colors

    LaunchedEffect(me.uid) { viewModel.bind(me) }
    // "Summary ready" arrives minutes after "Sent". Looked for only while the screen is in front.
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            viewModel.onScreenShown(true)
            try {
                while (true) {
                    viewModel.refreshSummaries()
                    delay(viewModel.summaryCheckEveryMs)
                }
            } finally {
                viewModel.onScreenShown(false)
            }
        }
    }
    // Battery settings are changed outside the app; look again on the way back.
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { viewModel.refreshReliability() }

    /*
     * The microphone first, notifications alongside, asked as one moment. What to do once allowed is saved as
     * text, because the permission dialog can outlive this screen (a rotation, or Android reclaiming the app).
     */
    var afterPermission by rememberSaveable { mutableStateOf<String?>(null) }
    val permissions = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { granted ->
        val action = afterPermission
        afterPermission = null
        when {
            granted[Manifest.permission.RECORD_AUDIO] != true -> viewModel.onMicrophoneRefused()
            action == PENDING_RECORD -> viewModel.onRecord()
            action?.startsWith(PENDING_CONTINUE) == true ->
                viewModel.onContinue(action.removePrefix(PENDING_CONTINUE))
        }
    }
    fun withMicrophone(action: String) {
        afterPermission = action
        permissions.launch(
            buildList {
                add(Manifest.permission.RECORD_AUDIO)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    add(Manifest.permission.POST_NOTIFICATIONS)
                }
            }.toTypedArray(),
        )
    }
    fun openAppSettings() {
        context.startActivity(
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                .setData("package:${context.packageName}".toUri())
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }

    val live = state.live
    val errorKey = state.errorKey ?: live.errorKey

    LazyColumn(
        modifier = Modifier.fillMaxSize().background(colors.background),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            // A long press on the title switches the fast test mode, in debug builds only.
            TitleRow(
                testMode = state.testMode,
                onLongPress = {
                    if (!live.isLive) {
                        val on = viewModel.onToggleTestMode()
                        if (RecorderTestMode.available) {
                            Toast.makeText(context, if (on) "Fast test mode on" else "Fast test mode off", Toast.LENGTH_SHORT).show()
                        }
                    }
                },
            )
        }

        if (!live.isLive) {
            items(state.interrupted, key = { "interrupted-" + it.recording.id }) { summary ->
                InterruptedCard(
                    summary = summary,
                    limits = state.limits,
                    onContinue = { withMicrophone(PENDING_CONTINUE + summary.recording.id) },
                    onFinish = { viewModel.onFinish(summary.recording.id) },
                )
            }
        }

        item {
            ControlCard(
                live = live,
                enabled = state.loaded && !state.unavailable,
                limits = state.limits,
                planMs = state.planMs,
                onPlan = viewModel::onPlanPicked,
                onRecord = { withMicrophone(PENDING_RECORD) },
                onInstant = {
                    viewModel.onInstantStart()
                    withMicrophone(PENDING_RECORD)
                },
                onPause = viewModel::onPause,
                onResume = viewModel::onResume,
                onStop = viewModel::onStop,
                onExtend = viewModel::onExtend,
                // What could still cut a meeting off, while it is true.
                health = {
                    HealthChips(
                        state = state,
                        onBattery = { openBatterySettings(context, state.batteryExempt) },
                        onAlerts = { openAlertSettings(context, state.alertsChannelMuted) },
                    )
                },
            )
        }

        (errorKey ?: RecorderErrors.STORAGE.takeIf { state.unavailable })?.let { key ->
            item {
                Notice(
                    text = tr(key),
                    background = colors.destructiveWash,
                    foreground = colors.destructiveWashForeground,
                    action = if (key == RecorderErrors.MIC_PERMISSION) tr("recorder.openSettings") else null,
                    onAction = ::openAppSettings,
                    onDismiss = viewModel::onDismissError.takeIf { !state.unavailable },
                )
            }
        }

        state.draft?.let { draft ->
            item {
                DetailsCard(
                    draft = draft,
                    saving = state.savingDraft,
                    onChange = viewModel::onDraftChange,
                    onSave = viewModel::onSaveDetails,
                    onSkip = viewModel::onSkipDetails,
                )
            }
        }

        if (state.detailsSaved) {
            item {
                Notice(
                    text = tr("recorder.details.saved"),
                    background = colors.successWash,
                    foreground = colors.successWashForeground,
                )
            }
        }

        item {
            Row(
                modifier = Modifier.fillMaxWidth().padding(start = 4.dp, top = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(tr("recorder.list.title"), style = Tas.type.subheading, color = colors.foreground, modifier = Modifier.weight(1f))
                if (state.waiting && state.online) {
                    TasChip(
                        text = tr("recorder.sendNow"),
                        tone = ChipTone.PRIMARY,
                        onClick = viewModel::onSendNow,
                    )
                }
            }
        }

        // Other people's meetings on this phone; they go when that person signs in here again.
        items(state.othersWaiting, key = { "others-" + it.ownerUid }) { others ->
            Text(
                tr(
                    "recorder.othersWaiting",
                    "count" to others.count,
                    "name" to (others.ownerName ?: tr("recorder.othersWaiting.someone")),
                ),
                style = Tas.type.caption,
                color = colors.mutedForeground,
                modifier = Modifier.padding(start = 4.dp),
            )
        }

        val listed = state.recordings.filter { it.recording.id != live.recordingId || !live.isLive }
        if (state.loaded && listed.isEmpty()) {
            item {
                Text(
                    tr("recorder.list.empty"),
                    style = Tas.type.bodySmall,
                    color = colors.mutedForeground,
                    modifier = Modifier.padding(start = 4.dp),
                )
            }
        }
        items(listed, key = { it.recording.id }) { summary ->
            RecordingRow(
                summary = summary,
                online = state.online,
                summaryReady = summary.recording.id in state.summaryReady,
                onEdit = { viewModel.onEditDetails(summary) },
            )
        }
    }
}

/**
 * The button and the clock. The clock is audio recorded, not time since the button was pressed, so a paused
 * meeting does not look longer than it will sound.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ControlCard(
    live: LiveRecorder.Status,
    enabled: Boolean,
    limits: RecorderLimits,
    planMs: Long,
    onPlan: (Long) -> Unit,
    onRecord: () -> Unit,
    onInstant: () -> Unit,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onStop: () -> Unit,
    onExtend: () -> Unit,
    health: @Composable () -> Unit = {},
) {
    val colors = Tas.colors
    val context = LocalContext.current
    TasCard(padding = PaddingValues(horizontal = 20.dp, vertical = 26.dp)) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            when (live.phase) {
                LiveRecorder.Phase.IDLE -> {
                    Text("0:00", style = Tas.type.timer, color = colors.faintForeground)
                    RecordButton(
                        recording = false,
                        level = 0f,
                        enabled = enabled,
                        description = tr("recorder.record"),
                        onClick = onRecord,
                    )
                    Text(
                        tr("recorder.record"),
                        style = Tas.type.subheading,
                        color = colors.foreground,
                    )
                    // The planned length: one row of pill chips.
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        for (choice in limits.choicesMs) {
                            TasChip(
                                text = RecordingPlans.label(choice),
                                selected = choice == planMs,
                                onClick = { if (enabled) onPlan(choice) },
                            )
                        }
                    }
                    Text(
                        tr("recorder.limitNote"),
                        style = Tas.type.caption,
                        color = colors.faintForeground,
                        textAlign = TextAlign.Center,
                    )
                    // Straight away, with the default length.
                    TasButton(
                        text = tr("recorder.instant"),
                        onClick = onInstant,
                        tone = ButtonTone.OUTLINE,
                        enabled = enabled,
                    )
                    health()
                }

                LiveRecorder.Phase.STARTING, LiveRecorder.Phase.STOPPING -> {
                    Text(clockText(live.elapsedMs), style = Tas.type.timer, color = colors.faintForeground)
                    Box(Modifier.size(RECORD_BUTTON + 28.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(color = colors.record, strokeWidth = 3.dp, modifier = Modifier.size(56.dp))
                    }
                    Text(
                        tr(if (live.phase == LiveRecorder.Phase.STARTING) "recorder.starting" else "recorder.stopping"),
                        style = Tas.type.subheading,
                        color = colors.mutedForeground,
                    )
                    health()
                }

                LiveRecorder.Phase.RECORDING, LiveRecorder.Phase.PAUSED -> {
                    val paused = live.phase == LiveRecorder.Phase.PAUSED
                    LiveBadge(paused)
                    Text(
                        clockText(live.elapsedMs),
                        style = Tas.type.timer,
                        color = if (paused) colors.mutedForeground else colors.foreground,
                    )
                    RecordButton(
                        recording = true,
                        level = if (paused) 0f else live.level,
                        enabled = true,
                        description = tr("recorder.stop"),
                        onClick = onStop,
                    )

                    when (live.pausedFor) {
                        GapReason.CALL -> Caption(tr("recorder.pausedCall"))
                        GapReason.INTERRUPTION -> Caption(tr("recorder.pausedInterruption"))
                        else -> Unit
                    }
                    // When it stops, and while the warning shows, Keep recording.
                    val warning = live.warning
                    when {
                        warning != null -> {
                            TasChip(
                                text = tr("recorder.stopsIn", "time" to clockText(warning.endAtMs - live.elapsedMs)),
                                tone = ChipTone.WARNING,
                            )
                            if (warning.extendToMs != null) {
                                TasButton(text = tr("recorder.keep"), onClick = onExtend)
                            }
                        }
                        !paused -> TasChip(
                            text = tr(
                                "recorder.stopsAt",
                                "time" to DateFormat.getTimeFormat(context)
                                    .format(Date(System.currentTimeMillis() + live.stopAtMs - live.elapsedMs)),
                            ),
                        )
                    }

                    // The round button stops; Pause and Resume sit under it.
                    when {
                        !paused -> TasButton(text = tr("recorder.pause"), onClick = onPause, tone = ButtonTone.OUTLINE)
                        live.pausedFor == GapReason.USER -> TasButton(text = tr("recorder.resume"), onClick = onResume)
                    }
                    Caption(tr("recorder.lockedOk"))
                    health()
                }
            }
        }
    }
}

/**
 * The big round coral button: a white dot to start, a white square to stop. While recording, a thin ring
 * just outside it grows and fades with the microphone's level, as proof it is hearing the room.
 */
@Composable
private fun RecordButton(
    recording: Boolean,
    level: Float,
    enabled: Boolean,
    description: String,
    onClick: () -> Unit,
) {
    val colors = Tas.colors
    // Speech peaks well below full scale; this makes a normal voice move the ring most of its way.
    val target = if (recording) (level * 2.5f).coerceIn(0f, 1f) else 0f
    val shown by animateFloatAsState(targetValue = target, animationSpec = tween(120), label = "level")
    val ring = 28.dp
    Box(Modifier.size(RECORD_BUTTON + ring), contentAlignment = Alignment.Center) {
        Canvas(Modifier.size(RECORD_BUTTON + ring)) {
            val base = RECORD_BUTTON.toPx() / 2
            val stroke = 2.dp.toPx()
            if (recording) {
                drawCircle(
                    color = colors.record.copy(alpha = 0.25f + 0.5f * shown),
                    radius = base + 4.dp.toPx() + shown * (ring.toPx() / 2 - 6.dp.toPx()),
                    style = Stroke(width = stroke),
                )
            } else {
                drawCircle(color = colors.border, radius = base + 6.dp.toPx(), style = Stroke(width = stroke))
            }
        }
        Box(
            modifier = Modifier
                .size(RECORD_BUTTON)
                .clip(RoundedCornerShape(TasShape.pill))
                .background(colors.record.copy(alpha = if (enabled) 1f else 0.45f))
                .clickable(enabled = enabled, onClick = onClick)
                .semantics { contentDescription = description },
            contentAlignment = Alignment.Center,
        ) {
            Canvas(Modifier.size(34.dp)) {
                if (recording) {
                    drawRoundRect(color = Color.White, cornerRadius = CornerRadius(7.dp.toPx()))
                } else {
                    drawCircle(color = Color.White)
                }
            }
        }
    }
}

/** "Recording" with a live dot, or "Paused" in amber, as a soft pill. */
@Composable
private fun LiveBadge(paused: Boolean) {
    val colors = Tas.colors
    val fg = if (paused) colors.warning else colors.record
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(TasShape.pill))
            .background(if (paused) colors.warningWash else colors.recordTint)
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(Modifier.size(8.dp).clip(RoundedCornerShape(TasShape.pill)).background(fg))
        Text(
            tr(if (paused) "recorder.paused" else "recorder.recording"),
            style = Tas.type.caption.copy(fontWeight = FontWeight.Bold),
            color = if (paused) colors.warningWashForeground else colors.destructiveWashForeground,
        )
    }
}

/** The screen's title. A long press is the hidden switch for the fast test mode (debug builds only). */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun TitleRow(testMode: Boolean, onLongPress: () -> Unit) {
    val colors = Tas.colors
    Row(
        modifier = Modifier.padding(start = 4.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(
            "Record",
            style = Tas.type.displayLg,
            color = colors.foreground,
            modifier = Modifier.combinedClickable(
                interactionSource = null,
                indication = null,
                onClick = {},
                onLongClick = onLongPress,
            ),
        )
        if (testMode) TasChip(text = "Fast test", tone = ChipTone.WARNING)
    }
}

/**
 * the warning before a recording stops, while the app is open,
 * on whichever tab. Keep recording (while the plan is below the cap) or Stop
 * now; closing it changes nothing, and the recorder card keeps both buttons.
 * The notification and the beep come from the service either way.
 */
@Composable
fun RecorderWarningDialog() {
    val live by LiveRecorder.status.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val warning = live.warning ?: return
    var dismissedFor by rememberSaveable { mutableStateOf<Long?>(null) }
    if (dismissedFor == warning.endAtMs) return
    val left = RecordingPlans.wordsLeft(warning.endAtMs - live.elapsedMs)
    val text = when (val to = warning.extendToMs) {
        null -> tr("recorder.warn.final", "left" to left, "cap" to RecordingPlans.words(live.capMs))
        else -> tr("recorder.warn.body", "left" to left) + " " + tr("recorder.warn.extend", "to" to RecordingPlans.words(to))
    }
    val colors = Tas.colors
    AlertDialog(
        onDismissRequest = { dismissedFor = warning.endAtMs },
        containerColor = colors.card,
        title = { Text(tr("recorder.warn.title"), style = Tas.type.body.copy(fontWeight = FontWeight.SemiBold), color = colors.foreground) },
        text = { Text(text, style = Tas.type.bodySmall, color = colors.mutedForeground) },
        confirmButton = {
            if (warning.extendToMs != null) {
                TextButton(onClick = {
                    dismissedFor = warning.endAtMs
                    RecorderService.send(context, RecorderService.ACTION_EXTEND)
                }) { Text(tr("recorder.keep"), color = colors.primary, fontWeight = FontWeight.SemiBold) }
            } else {
                TextButton(onClick = { dismissedFor = warning.endAtMs }) {
                    Text(tr("recorder.warn.ok"), color = colors.primary, fontWeight = FontWeight.SemiBold)
                }
            }
        },
        dismissButton = {
            TextButton(onClick = {
                dismissedFor = warning.endAtMs
                RecorderService.send(context, RecorderService.ACTION_STOP)
            }) { Text(tr("recorder.stopNow"), color = colors.destructive) }
        },
    )
}

/**
 * a locked button on the recorder's notification, tapped on the lock screen, arrives here once the
 * phone is unlocked (see `RecorderNotifications.locked`). Pause and Stop now act at once; Stop asks first, the same
 * question the iPhone's Live Activity asks, so a tap in a pocket cannot end a meeting.
 */
@Composable
fun RecorderNotificationAction() {
    val action by PendingNotification.recorderAction.collectAsStateWithLifecycle()
    val live by LiveRecorder.status.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var asking by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(action) {
        when (action ?: return@LaunchedEffect) {
            RecorderNotifications.RECORDER_PAUSE -> RecorderService.send(context, RecorderService.ACTION_PAUSE)
            RecorderNotifications.RECORDER_STOP_NOW -> RecorderService.send(context, RecorderService.ACTION_STOP)
            RecorderNotifications.RECORDER_ASK_STOP -> asking = true
        }
        PendingNotification.consumeRecorderAction()
    }
    if (!asking) return
    if (!live.isLive) {
        asking = false
        return
    }
    val colors = Tas.colors
    AlertDialog(
        onDismissRequest = { asking = false },
        containerColor = colors.card,
        title = { Text(tr("recorder.confirmStop.title"), style = Tas.type.body.copy(fontWeight = FontWeight.SemiBold), color = colors.foreground) },
        confirmButton = {
            TextButton(onClick = {
                asking = false
                RecorderService.send(context, RecorderService.ACTION_STOP)
            }) { Text(tr("recorder.stop"), color = colors.destructive, fontWeight = FontWeight.SemiBold) }
        },
        dismissButton = {
            TextButton(onClick = { asking = false }) { Text(tr("recorder.confirmStop.cancel"), color = colors.primary) }
        },
    )
}
@Composable
private fun InterruptedCard(
    summary: RecordingSummary,
    limits: RecorderLimits,
    onContinue: () -> Unit,
    onFinish: () -> Unit,
) {
    val colors = Tas.colors
    val language = Tas.language
    // room under the 3-hour cap, by the shared rule.
    val canContinue = RecordingPlans.canContinue(summary.durationMs, limits)
    TasCard {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                tr("recorder.interrupted.title"),
                style = Tas.type.body.copy(fontWeight = FontWeight.SemiBold),
                color = colors.foreground,
            )
            Text(
                tr(
                    "recorder.interrupted.body",
                    "time" to timeText(summary.recording.startedAt, language),
                    "duration" to clockText(summary.durationMs),
                ),
                style = Tas.type.bodySmall,
                color = colors.mutedForeground,
            )
            if (canContinue) {
                TasButton(text = tr("recorder.interrupted.continue"), onClick = onContinue)
            }
            TasButton(
                text = tr("recorder.interrupted.finish"),
                onClick = onFinish,
                tone = ButtonTone.OUTLINE,
            )
            Caption(tr("recorder.interrupted.auto"))
        }
    }
}

/** The details of a recording that just stopped (or was tapped): all optional. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DetailsCard(
    draft: RecordingDraft,
    saving: Boolean,
    onChange: (RecordingDraft) -> Unit,
    onSave: () -> Unit,
    onSkip: () -> Unit,
) {
    val colors = Tas.colors
    TasCard {
        Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(tr("recorder.details.title"), style = Tas.type.subheading, color = colors.foreground)
                Caption(tr("recorder.details.hint"), center = false)
            }
            TasField(
                label = tr("recorder.details.fieldTitle"),
                value = draft.title,
                onValueChange = { onChange(draft.copy(title = it)) },
                maxLength = RecorderConfig.MAX_TITLE,
            )
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    tr("recorder.details.fieldKind"),
                    style = Tas.type.caption.copy(fontWeight = FontWeight.SemiBold),
                    color = colors.mutedForeground,
                )
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    MEETING_KINDS.forEach { (kind, label) ->
                        TasChip(
                            text = label,
                            selected = draft.kind == kind,
                            onClick = { onChange(draft.copy(kind = if (draft.kind == kind) "" else kind)) },
                        )
                    }
                }
            }
            TasField(
                label = tr("recorder.details.fieldWhere"),
                value = draft.location,
                onValueChange = { onChange(draft.copy(location = it)) },
                maxLength = RecorderConfig.MAX_DETAIL,
            )
            TasField(
                label = tr("recorder.details.fieldWho"),
                value = draft.attendees,
                onValueChange = { onChange(draft.copy(attendees = it)) },
                singleLine = false,
                maxLines = 4,
                maxLength = RecorderConfig.MAX_DETAIL,
            )
            TasButton(text = tr("recorder.details.save"), onClick = onSave, busy = saving)
            TasButton(text = tr("recorder.details.skip"), onClick = onSkip, tone = ButtonTone.GHOST)
        }
    }
}

/**
 * small chips for what could still cut a meeting off, shown on the record card for as long as each is
 * true. Battery: Android may stop the app in the background. One line more on a OnePlus, whose own battery manager
 * needs two switches Android's request cannot set.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun HealthChips(state: RecorderUiState, onBattery: () -> Unit, onAlerts: () -> Unit) {
    if (!state.batteryAtRisk && !state.alertsOff) return
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally)) {
            if (state.batteryAtRisk) {
                TasChip(text = tr("recorder.chip.battery"), tone = ChipTone.WARNING, onClick = onBattery)
            }
            // without alerts, a recording that stops, or is about to, says so to nobody.
            if (state.alertsOff) {
                TasChip(text = tr("recorder.chip.alerts"), tone = ChipTone.WARNING, onClick = onAlerts)
            }
        }
        if (state.batteryAtRisk && state.oneplus) Caption(tr("recorder.hint.oneplus"))
    }
}

/** the alerts channel's own page when only it is muted; otherwise the app's notification settings. */
private fun openAlertSettings(context: android.content.Context, channelOnly: Boolean) {
    val tries = buildList {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            if (channelOnly) {
                add(
                    Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
                        .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                        .putExtra(Settings.EXTRA_CHANNEL_ID, RecorderNotifications.ALERTS_CHANNEL),
                )
            }
            add(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName))
        }
        add(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, "package:${context.packageName}".toUri()))
    }
    for (intent in tries) {
        try {
            context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            return
        } catch (e: android.content.ActivityNotFoundException) {
            // The next one.
        }
    }
}

/**
 * asks Android to let the app run in the background (one tap, Allow). Already allowed but restricted
 * by the person: the app's own settings page, where Battery is.
 */
@android.annotation.SuppressLint("BatteryLife") // The recorder's whole job is to run with the screen off.
private fun openBatterySettings(context: android.content.Context, exempt: Boolean) {
    val pkg = "package:${context.packageName}".toUri()
    val tries = buildList {
        if (!exempt) add(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, pkg))
        add(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, pkg))
    }
    for (intent in tries) {
        try {
            context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            return
        } catch (e: android.content.ActivityNotFoundException) {
            // A phone without that screen: the next one.
        }
    }
}

/** One recording and whether it is safe: the status chip is the point of the row. */
@Composable
private fun RecordingRow(summary: RecordingSummary, online: Boolean, summaryReady: Boolean, onEdit: () -> Unit) {
    val colors = Tas.colors
    val language = Tas.language
    val recording = summary.recording
    val (status, tone) = statusOf(summary, online, summaryReady)

    TasCard(
        padding = PaddingValues(16.dp),
        onClick = onEdit.takeIf { recording.state != RecordingState.INTERRUPTED },
    ) {
        Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(
                    recording.title ?: tr("recorder.list.untitled", "date" to timeText(recording.startedAt, language)),
                    style = Tas.type.body.copy(fontWeight = FontWeight.Bold),
                    color = colors.foreground,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    listOfNotNull(
                        timeText(recording.startedAt, language).takeIf { recording.title != null },
                        clockText(summary.durationMs),
                    ).joinToString(" · "),
                    style = Tas.type.caption.copy(fontFeatureSettings = "tnum"),
                    color = colors.faintForeground,
                )
                recording.lastError
                    ?.takeIf { recording.state != RecordingState.UPLOADED }
                    ?.let { Caption(tr(it), center = false) }
                if (recording.title == null && recording.state != RecordingState.INTERRUPTED) {
                    Text(
                        tr("recorder.details.edit"),
                        style = Tas.type.caption.copy(fontWeight = FontWeight.Bold),
                        color = colors.primary,
                    )
                }
            }
            TasChip(text = status, tone = tone)
        }
    }
}

@Composable
private fun statusOf(summary: RecordingSummary, online: Boolean, summaryReady: Boolean): Pair<String, ChipTone> {
    val recording = summary.recording
    val progress = arrayOf("sent" to summary.uploadedChunks, "total" to summary.chunks)
    return when (recording.state) {
        RecordingState.RECORDING, RecordingState.PAUSED -> tr("recorder.status.live") to ChipTone.DANGER
        RecordingState.INTERRUPTED -> tr("recorder.status.interrupted") to ChipTone.WARNING
        // the server took nothing more because the recording was deleted by an admin; not "Sent".
        RecordingState.UPLOADED -> when {
            recording.lastError == RecorderErrors.ALREADY_FINISHED -> tr("recorder.status.deleted") to ChipTone.NEUTRAL
            summaryReady -> tr("recorder.status.summaryReady") to ChipTone.SUCCESS
            else -> tr("recorder.status.sent") to ChipTone.SUCCESS
        }
        RecordingState.STOPPED -> when {
            recording.lastError in PERMANENT_ERRORS -> tr("recorder.status.failed") to ChipTone.DANGER
            summary.fullyUploaded -> tr("recorder.status.checking") to ChipTone.PRIMARY
            !online -> tr("recorder.status.waiting", *progress) to ChipTone.NEUTRAL
            else -> tr("recorder.status.sending", *progress) to ChipTone.PRIMARY
        }
    }
}

/** Refusals that will not change by trying again; see `RecorderErrors.PERMANENT`. */
private val PERMANENT_ERRORS = setOf(
    RecorderErrors.BAD_REQUEST, RecorderErrors.NOT_YOURS, RecorderErrors.TOO_LARGE,
)

@Composable
private fun Caption(text: String, center: Boolean = true) {
    Text(
        text,
        style = Tas.type.caption,
        color = Tas.colors.mutedForeground,
        textAlign = if (center) TextAlign.Center else TextAlign.Start,
    )
}

@Composable
private fun Notice(
    text: String,
    background: Color,
    foreground: Color,
    action: String? = null,
    onAction: () -> Unit = {},
    onDismiss: (() -> Unit)? = null,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(TasShape.radius))
            .background(background)
            .then(if (onDismiss != null && action == null) Modifier.clickable(onClick = onDismiss) else Modifier)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(text, style = Tas.type.bodySmall.copy(fontWeight = FontWeight.SemiBold), color = foreground)
        if (action != null) {
            Text(
                action,
                style = Tas.type.bodySmall.copy(fontWeight = FontWeight.Bold),
                color = foreground,
                modifier = Modifier.clickable(onClick = onAction),
            )
        }
    }
}

/** 4:05, 1:04:05 — the same digits in every language. */
private fun clockText(ms: Long): String {
    val seconds = ms / 1000
    val h = seconds / 3600
    val m = (seconds % 3600) / 60
    val s = seconds % 60
    // Locale.US so a phone set to Hindi does not switch the clock to other digits.
    return if (h > 0) String.format(Locale.US, "%d:%02d:%02d", h, m, s)
    else String.format(Locale.US, "%d:%02d", m, s)
}

/** "25 Sep, 14:02", with the month in the reader's language. */
private fun timeText(epochMs: Long, language: Language): String =
    SimpleDateFormat("d MMM, HH:mm", Locale.forLanguageTag(language.code)).format(Date(epochMs))
