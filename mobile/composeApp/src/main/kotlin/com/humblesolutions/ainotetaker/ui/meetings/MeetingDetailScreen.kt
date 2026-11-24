package com.humblesolutions.ainotetaker.ui.meetings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.humblesolutions.ainotetaker.model.MeetingAnalysis
import com.humblesolutions.ainotetaker.model.MeetingRow
import com.humblesolutions.ainotetaker.model.User
import com.humblesolutions.ainotetaker.ui.components.BackHeader
import com.humblesolutions.ainotetaker.ui.components.ButtonTone
import com.humblesolutions.ainotetaker.ui.components.SectionLabel
import com.humblesolutions.ainotetaker.ui.components.TasButton
import com.humblesolutions.ainotetaker.ui.components.TasCard
import com.humblesolutions.ainotetaker.ui.theme.Tas
import com.humblesolutions.ainotetaker.ui.theme.tr
import com.humblesolutions.ainotetaker.viewmodel.MeetingDetailViewModel
import com.humblesolutions.ainotetaker.viewmodel.MeetingsViewModel
import kotlinx.coroutines.delay

/**
 * One meeting: title, meta line, then the summary's sections as cards. Empty sections are left out; long lists
 * show their first few.
 */
@Composable
fun MeetingDetailScreen(me: User, viewModel: MeetingDetailViewModel, onBack: () -> Unit) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val colors = Tas.colors
    LaunchedEffect(me.uid) { viewModel.bind(me) }
    // Still processing: look again every 5 s while the screen is in front.
    val working = state.detail?.meeting?.working == true
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(working, lifecycle) {
        if (!working) return@LaunchedEffect
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) {
                delay(MeetingsViewModel.WORKING_REFRESH_MS)
                viewModel.reload()
            }
        }
    }

    val detail = state.detail
    Column(Modifier.fillMaxSize().background(colors.background)) {
        BackHeader("", onBack)
        when {
            state.loading -> Row(Modifier.fillMaxWidth().padding(top = 40.dp), horizontalArrangement = Arrangement.Center) {
                CircularProgressIndicator(color = colors.primary)
            }
            detail == null -> Column(Modifier.fillMaxWidth().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(say(state.problem ?: "meetings.err.notFound"), style = Tas.type.body, color = colors.foreground)
                TasButton(
                    text = tr("meetings.retry"),
                    onClick = viewModel::reload,
                    tone = ButtonTone.OUTLINE,
                    fillWidth = false,
                    modifier = Modifier.padding(top = 12.dp),
                )
            }
            else -> LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 32.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                item { Heading(detail.meeting, detail.analysis) }
                val a = detail.analysis
                if (a == null) {
                    item {
                        TasCard {
                            Text(tr("meetings.detail.notReady"), style = Tas.type.body, color = colors.mutedForeground)
                        }
                    }
                } else {
                    analysisSections(a)
                }
            }
        }
    }
}

@Composable
private fun Heading(meeting: MeetingRow, a: MeetingAnalysis?) {
    val colors = Tas.colors
    Column(
        modifier = Modifier.padding(start = 4.dp, end = 4.dp, bottom = 4.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            a?.title?.ifBlank { null } ?: meeting.title.ifBlank { tr("meetings.untitled") },
            style = Tas.type.displayLg,
            color = colors.foreground,
        )
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(
                listOf(meetingTime(meeting.meetingAt), meetingLength(meeting.durationMs))
                    .filter { it.isNotBlank() }.joinToString(" · "),
                style = Tas.type.caption,
                color = colors.mutedForeground,
                modifier = Modifier.weight(1f, fill = false),
            )
            if (a != null) MoodChip(a.mood)
        }
    }
}

/* ------------------------------------------------------------ the summary's sections */

private fun LazyListScope.analysisSections(a: MeetingAnalysis) {
    if (a.summary.isNotBlank() || a.howItWent.isNotBlank()) item {
        Section("meetings.detail.summary") {
            if (a.summary.isNotBlank()) Text(a.summary, style = Tas.type.body, color = Tas.colors.foreground)
            if (a.howItWent.isNotBlank()) Text(a.howItWent, style = Tas.type.bodySmall, color = Tas.colors.mutedForeground)
        }
    }
    if (a.keyPoints.isNotEmpty()) item { Listed("meetings.detail.keyPoints", a.keyPoints) { Bullet(it.text, moment(it.atSeconds)) } }
    if (a.decisions.isNotEmpty()) item { Listed("meetings.detail.decisions", a.decisions) { Bullet(it.text, moment(it.atSeconds)) } }
    if (a.actionItems.isNotEmpty()) item {
        Listed("meetings.detail.actions", a.actionItems) { item ->
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Line(item.task, moment(item.atSeconds), bold = true)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    LevelChip(item.priority)
                    Caption(listOfNotNull(
                        item.owner.takeIf { it.isNotBlank() }?.let { "${tr("meetings.detail.owner")}: $it" },
                        item.due.takeIf { it.isNotBlank() }?.let { "${tr("meetings.detail.due")}: $it" },
                    ).joinToString(" · "))
                }
            }
        }
    }
    if (a.questions.isNotEmpty()) item {
        Listed("meetings.detail.questions", a.questions) { q ->
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Line(q.question, moment(q.atSeconds), bold = true)
                if (q.answer.isNotBlank()) Text(q.answer, style = Tas.type.bodySmall, color = Tas.colors.mutedForeground)
                QuestionChip(q.status)
            }
        }
    }
    if (a.risks.isNotEmpty()) item { Listed("meetings.detail.risks", a.risks) { Bullet(it, null) } }
    if (a.nextMeeting.isNotBlank()) item { Prose("meetings.detail.nextMeeting", a.nextMeeting) }
    if (a.participants.isNotEmpty()) item {
        Listed("meetings.detail.people", a.participants) { p ->
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Line(listOf(p.name.ifBlank { p.speakers.joinToString(", ") }, p.role).filter { it.isNotBlank() }.joinToString(" · "), null, bold = true)
                if (p.stance.isNotBlank()) Text(p.stance, style = Tas.type.bodySmall, color = Tas.colors.foreground)
                if (p.note.isNotBlank()) Caption(p.note)
            }
        }
    }
    if (a.issues.isNotEmpty()) item {
        Listed("meetings.detail.issues", a.issues) { i ->
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        listOf(i.topic, i.area).filter { it.isNotBlank() }.joinToString(" · "),
                        style = Tas.type.bodySmall.copy(fontWeight = FontWeight.SemiBold),
                        color = Tas.colors.foreground,
                        modifier = Modifier.weight(1f),
                    )
                    LevelChip(i.severity)
                }
                if (i.detail.isNotBlank()) Line(i.detail, moment(i.atSeconds))
            }
        }
    }
    if (a.commitments.isNotEmpty()) item {
        Listed("meetings.detail.commitments", a.commitments) { c ->
            Bullet(listOf(c.text, c.by).filter { it.isNotBlank() }.joinToString(" · "), moment(c.atSeconds))
        }
    }
    if (a.opportunities.isNotEmpty()) item { Listed("meetings.detail.opportunities", a.opportunities) { Bullet(it, null) } }
    if (a.futureScope.isNotBlank()) item { Prose("meetings.detail.futureScope", a.futureScope) }
    if (a.conclusion.isNotBlank()) item { Prose("meetings.detail.conclusion", a.conclusion) }
}

@Composable
private fun Section(titleKey: String, content: @Composable () -> Unit) {
    TasCard {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            SectionLabel(tr(titleKey))
            content()
        }
    }
}

@Composable
private fun Prose(titleKey: String, text: String) = Section(titleKey) {
    Text(text, style = Tas.type.body, color = Tas.colors.foreground)
}

/** A list that shows its first few and opens the rest on a tap. */
@Composable
private fun <T> Listed(titleKey: String, items: List<T>, row: @Composable (T) -> Unit) {
    var open by rememberSaveable(titleKey) { mutableStateOf(false) }
    val shown = if (open || items.size <= FIRST) items else items.take(FIRST)
    Section(titleKey) {
        shown.forEach { row(it) }
        if (items.size > FIRST) {
            Text(
                if (open) tr("meetings.detail.showLess") else tr("meetings.detail.showMore", "count" to items.size - FIRST),
                style = Tas.type.caption.copy(fontWeight = FontWeight.Bold),
                color = Tas.colors.primary,
                modifier = Modifier.clickable { open = !open }.padding(vertical = 4.dp),
            )
        }
    }
}

private const val FIRST = 5

/** A point with a small primary dot, and its moment in the audio on the right. */
@Composable
private fun Bullet(text: String, at: String?) {
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
        Box(
            Modifier
                .padding(top = 8.dp)
                .size(6.dp)
                .background(Tas.colors.primary, RoundedCornerShape(50)),
        )
        Text(text, style = Tas.type.bodySmall, color = Tas.colors.foreground, modifier = Modifier.weight(1f))
        at?.let { Caption(it) }
    }
}

@Composable
private fun Line(text: String, at: String?, bold: Boolean = false) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
        Text(
            text,
            style = Tas.type.bodySmall.copy(fontWeight = if (bold) FontWeight.SemiBold else FontWeight.Normal),
            color = Tas.colors.foreground,
            modifier = Modifier.weight(1f),
        )
        at?.let { Caption(it) }
    }
}

@Composable
private fun Caption(text: String) {
    if (text.isNotBlank()) {
        Text(
            text,
            style = Tas.type.caption.copy(fontFeatureSettings = "tnum"),
            color = Tas.colors.faintForeground,
        )
    }
}
