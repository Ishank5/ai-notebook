package com.humblesolutions.ainotetaker.ui.meetings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.humblesolutions.ainotetaker.model.MeetingRow
import com.humblesolutions.ainotetaker.ui.components.ButtonTone
import com.humblesolutions.ainotetaker.ui.components.TasButton
import com.humblesolutions.ainotetaker.ui.components.TasCard
import com.humblesolutions.ainotetaker.ui.theme.Tas
import com.humblesolutions.ainotetaker.ui.theme.tr
import com.humblesolutions.ainotetaker.viewmodel.MeetingsViewModel
import kotlinx.coroutines.delay

/**
 * The person's meetings, newest first. A ready one opens its summary. Read again each time the tab comes into
 * view, on pull, and every 5 s while one is uploading or processing.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MeetingsScreen(viewModel: MeetingsViewModel, onOpen: (String) -> Unit) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val colors = Tas.colors
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { viewModel.refresh() }
    val working = state.rows.any { it.working }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(working, lifecycle) {
        if (!working) return@LaunchedEffect
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) {
                delay(MeetingsViewModel.WORKING_REFRESH_MS)
                viewModel.refresh(quiet = true)
            }
        }
    }

    PullToRefreshBox(
        isRefreshing = state.refreshing && state.loaded,
        onRefresh = viewModel::refresh,
        modifier = Modifier.fillMaxSize().background(colors.background),
    ) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item {
                Text(
                    tr("meetings.title"),
                    style = Tas.type.displayLg,
                    color = colors.foreground,
                    modifier = Modifier.padding(start = 4.dp, bottom = 6.dp),
                )
            }
            when {
                !state.loaded -> item {
                    Row(Modifier.fillMaxWidth().padding(top = 40.dp), horizontalArrangement = Arrangement.Center) {
                        CircularProgressIndicator(color = colors.primary)
                    }
                }
                state.problem != null && state.rows.isEmpty() -> item {
                    TasCard {
                        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(tr("meetings.loadFailed"), style = Tas.type.subheading, color = colors.foreground)
                            Text(say(state.problem!!), style = Tas.type.bodySmall, color = colors.mutedForeground)
                            TasButton(
                                text = tr("meetings.retry"),
                                onClick = viewModel::refresh,
                                tone = ButtonTone.OUTLINE,
                                fillWidth = false,
                                modifier = Modifier.padding(top = 14.dp),
                            )
                        }
                    }
                }
                state.rows.isEmpty() -> item {
                    TasCard {
                        Text(
                            tr("meetings.empty"),
                            style = Tas.type.body,
                            color = colors.mutedForeground,
                            modifier = Modifier.fillMaxWidth().padding(vertical = 18.dp),
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                        )
                    }
                }
                else -> items(state.rows, key = { it.id }) { row ->
                    MeetingCard(row, onClick = { onOpen(row.id) }.takeIf { row.hasSummary })
                }
            }
        }
    }
}

/** Title, date and length, the status chip, and one line of the summary. */
@Composable
private fun MeetingCard(row: MeetingRow, onClick: (() -> Unit)?) {
    val colors = Tas.colors
    TasCard(onClick = onClick) {
        Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    row.title.ifBlank { tr("meetings.untitled") },
                    style = Tas.type.subheading,
                    color = colors.foreground,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    listOf(meetingTime(row.meetingAt), meetingLength(row.durationMs)).filter { it.isNotBlank() }.joinToString(" · "),
                    style = Tas.type.caption,
                    color = colors.faintForeground,
                )
            }
            MeetingStatusChip(row.statusKey)
        }
        if (row.summaryText.isNotBlank()) {
            Text(
                row.summaryText,
                style = Tas.type.bodySmall.copy(fontWeight = FontWeight.Normal),
                color = colors.mutedForeground,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 10.dp),
            )
        }
    }
}
