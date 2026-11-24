package com.humblesolutions.ainotetaker.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import com.humblesolutions.ainotetaker.model.MeetingDetail
import com.humblesolutions.ainotetaker.model.MeetingProblem
import com.humblesolutions.ainotetaker.model.MeetingRow
import com.humblesolutions.ainotetaker.model.User
import com.humblesolutions.ainotetaker.repository.Meetings
import com.humblesolutions.ainotetaker.usecase.LoadMeetingUseCase
import com.humblesolutions.ainotetaker.usecase.LoadMeetingsUseCase
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/* The Meetings tab (the person's own recordings, from the API) and one meeting's summary. */

/** A failed call as a string key or the server's own sentence; [tr] shows either. */
internal fun problemText(e: Throwable): String =
    (e as? MeetingProblem)?.let { it.serverMessage ?: it.key } ?: "meetings.err.network"

data class MeetingsUiState(
    val loaded: Boolean = false,
    val refreshing: Boolean = false,
    val rows: List<MeetingRow> = emptyList(),
    /** A string key or the server's sentence. Shown instead of the list only when there is none to show. */
    val problem: String? = null,
)

class MeetingsViewModel(application: Application) : AndroidViewModel(application) {

    private val loadMeetings = LoadMeetingsUseCase(Meetings.repository)
    private var loading: Job? = null

    private val _uiState = MutableStateFlow(MeetingsUiState())
    val uiState: StateFlow<MeetingsUiState> = _uiState.asStateFlow()

    /**
     * Reads the list again: on opening the tab, coming back to it, and pull to refresh. [quiet] is the
     * every-few-seconds look while a meeting is processing, which shows no spinner.
     */
    fun refresh(quiet: Boolean = false) {
        if (loading?.isActive == true) return
        if (!quiet) _uiState.update { it.copy(refreshing = true) }
        loading = viewModelScope.launch {
            try {
                val rows = loadMeetings.execute(System.currentTimeMillis())
                _uiState.update { MeetingsUiState(loaded = true, rows = rows) }
            } catch (e: Exception) {
                _uiState.update { it.copy(loaded = true, refreshing = false, problem = problemText(e)) }
            }
        }
    }

    companion object {
        /** How often a visible list or meeting is read again while one is uploading or processing. */
        const val WORKING_REFRESH_MS = 5_000L
    }
}

data class MeetingDetailUiState(
    val loading: Boolean = true,
    val problem: String? = null,
    val detail: MeetingDetail? = null,
)

class MeetingDetailViewModel(application: Application, handle: SavedStateHandle) : AndroidViewModel(application) {

    private val meetingId: String = handle.get<String>("id").orEmpty()
    private val loadMeeting = LoadMeetingUseCase(Meetings.repository)
    private var me: User? = null
    private var loading: Job? = null

    private val _uiState = MutableStateFlow(MeetingDetailUiState())
    val uiState: StateFlow<MeetingDetailUiState> = _uiState.asStateFlow()

    fun bind(user: User) {
        if (me?.uid == user.uid) return
        me = user
        reload()
    }

    fun reload() {
        if (me == null || loading?.isActive == true) return
        _uiState.update { it.copy(loading = it.detail == null, problem = null) }
        loading = viewModelScope.launch {
            try {
                val detail = loadMeeting.execute(meetingId)
                _uiState.update { it.copy(loading = false, problem = null, detail = detail) }
            } catch (e: Exception) {
                _uiState.update { it.copy(loading = false, problem = problemText(e)) }
            }
        }
    }
}
