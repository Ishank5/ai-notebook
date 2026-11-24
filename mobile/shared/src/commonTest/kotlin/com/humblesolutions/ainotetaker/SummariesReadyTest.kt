package com.humblesolutions.ainotetaker

import com.humblesolutions.ainotetaker.model.LocalRecording
import com.humblesolutions.ainotetaker.model.RecordingState
import com.humblesolutions.ainotetaker.model.RecordingSummary
import com.humblesolutions.ainotetaker.repository.RecordingStatusRepository
import com.humblesolutions.ainotetaker.usecase.SummariesReadyUseCase
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SummariesReadyTest {

    private val now = 1_780_000_000_000L
    private val day = 24 * 60 * 60 * 1000L

    private fun row(id: String, state: RecordingState, uploadedAt: Long? = now - day) =
        RecordingSummary(recording = LocalRecording(id = id, state = state, uploadedAt = uploadedAt))

    private class Answers(
        private val statuses: Map<String, String> = emptyMap(),
        private val fail: Boolean = false,
    ) : RecordingStatusRepository {
        val asked = mutableListOf<List<String>>()
        override suspend fun serverStatuses(ids: List<String>): Map<String, String> {
            asked += ids
            if (fail) error("no signal")
            return statuses.filterKeys { it in ids }
        }
    }

    @Test
    fun onlyRecentSentRecordingsAreAskedAbout_andOnlyReadyOnesReturned() = runTest {
        val answers = Answers(mapOf("a" to "ready", "b" to "processing", "c" to "failed"))
        val ready = SummariesReadyUseCase(answers).execute(
            listOf(
                row("a", RecordingState.UPLOADED),
                row("b", RecordingState.UPLOADED),
                row("c", RecordingState.UPLOADED),
                row("live", RecordingState.RECORDING, uploadedAt = null),
                row("waiting", RecordingState.STOPPED, uploadedAt = null),
                row("old", RecordingState.UPLOADED, uploadedAt = now - 30 * day),
            ),
            known = emptySet(),
            now = now,
        )
        assertEquals(setOf("a"), ready)
        assertEquals(listOf(setOf("a", "b", "c")), answers.asked.map { it.toSet() })
    }

    @Test
    fun knownReadyOnesAreNotAskedAgain_andNothingToAskMeansNoCall() = runTest {
        val answers = Answers(mapOf("a" to "ready"))
        val ready = SummariesReadyUseCase(answers).execute(listOf(row("a", RecordingState.UPLOADED)), known = setOf("a"), now = now)
        assertTrue(ready.isEmpty())
        assertTrue(answers.asked.isEmpty())
    }

    @Test
    fun noSignalIsAnEmptyAnswer_notAFailure() = runTest {
        val ready = SummariesReadyUseCase(Answers(fail = true))
            .execute(listOf(row("a", RecordingState.UPLOADED)), known = emptySet(), now = now)
        assertTrue(ready.isEmpty())
    }

    @Test
    fun atMostThirtyAreAskedAbout_newestFirst() = runTest {
        val answers = Answers()
        val rows = (1..40).map { row("r$it", RecordingState.UPLOADED, uploadedAt = now - day + it) }
        SummariesReadyUseCase(answers).execute(rows, known = emptySet(), now = now)
        val asked = answers.asked.single()
        assertEquals(SummariesReadyUseCase.MAX_ASKED, asked.size)
        assertTrue("r40" in asked && "r11" in asked && "r10" !in asked)
    }
}
