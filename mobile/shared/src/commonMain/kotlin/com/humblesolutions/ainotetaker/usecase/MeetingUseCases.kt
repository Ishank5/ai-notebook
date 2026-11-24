package com.humblesolutions.ainotetaker.usecase

import com.humblesolutions.ainotetaker.meeting.MeetingJson
import com.humblesolutions.ainotetaker.model.CompanyList
import com.humblesolutions.ainotetaker.model.ConfirmOutcome
import com.humblesolutions.ainotetaker.model.MeetingDetail
import com.humblesolutions.ainotetaker.model.MeetingProblem
import com.humblesolutions.ainotetaker.model.MeetingRow
import com.humblesolutions.ainotetaker.model.OutreachDecision
import com.humblesolutions.ainotetaker.model.OutreachDraft
import com.humblesolutions.ainotetaker.model.OutreachEntry
import com.humblesolutions.ainotetaker.model.Role
import com.humblesolutions.ainotetaker.repository.CompanyCacheStore
import com.humblesolutions.ainotetaker.repository.MeetingRepository
import kotlinx.coroutines.CancellationException

/*
 * , all of this file: the company before recording, the Meetings tab, and confirming the outreach
 * entry a meeting drafted. See the "Notetaker" section of DEVELOPER_GUIDE.md.
 */

/** Only staff are asked for a company before recording: outreach is theirs. A contributor records as before. */
fun Role?.picksCompany(): Boolean = this == Role.SUPER_ADMIN || this == Role.ADMIN

/**
 * The companies to pick from before recording.
 *
 * NEVER IN THE WAY OF A RECORDING. [cached] answers at once from the last list saved on the phone; [refresh] asks
 * the CRM and saves what it gets, and falls back to the saved list when there is no signal. With no list ever
 * loaded, [CompanyList.loaded] is false and the screen offers "Pick later".
 */
class LoadCompaniesUseCase(
    private val repository: MeetingRepository,
    private val cache: CompanyCacheStore,
) {
    fun cached(): CompanyList {
        val text = cache.load() ?: return CompanyList()
        return CompanyList(MeetingJson.companies(text), loaded = true, fromCache = true)
    }

    @Throws(Throwable::class)
    suspend fun refresh(): CompanyList = try {
        val companies = repository.companies()
        cache.save(MeetingJson.companiesText(companies))
        CompanyList(companies, loaded = true, fromCache = false)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Throwable) {
        cached()
    }
}

/** The person's own meetings from the CRM, newest first: the last six months. */
class LoadMeetingsUseCase(private val repository: MeetingRepository) {
    @Throws(Throwable::class)
    suspend fun execute(now: Long): List<MeetingRow> =
        repository.meetings(now - WINDOW_MS, now + DAY_MS).sortedByDescending { it.meetingAt ?: 0L }

    companion object {
        const val DAY_MS = 24 * 60 * 60 * 1000L
        const val WINDOW_MS = 183 * DAY_MS
    }
}

/** One meeting, its summary and its outreach entry. */
class LoadMeetingUseCase(private val repository: MeetingRepository) {
    @Throws(Throwable::class)
    suspend fun execute(id: String): MeetingDetail = repository.meeting(id)
}

/** Saves the review form without confirming: the draft keeps the edits, and a new summary no longer replaces it. */
class SaveOutreachDraftUseCase(private val repository: MeetingRepository) {
    @Throws(Throwable::class)
    suspend fun execute(entryId: String, draft: OutreachDraft): OutreachEntry = repository.saveEntry(entryId, draft)
}

/**
 * Confirms the draft with what the form says, then, when it has notes, runs the status step exactly as the website
 * does after an outreach is logged. The entry is confirmed even if the status step fails: [ConfirmOutcome.statusProblem]
 * says so, and [RetryOutreachStatusUseCase] tries again.
 */
class ConfirmOutreachEntryUseCase(private val repository: MeetingRepository) {
    @Throws(Throwable::class)
    suspend fun execute(entryId: String, draft: OutreachDraft): ConfirmOutcome {
        draft.problem()?.let { throw MeetingProblem(it) }
        val entry = repository.confirmEntry(entryId, draft)
        if (entry.notes.isBlank()) return ConfirmOutcome(entry)
        return try {
            ConfirmOutcome(entry, decision = repository.updateStatus(entry.id))
        } catch (e: CancellationException) {
            throw e
        } catch (e: MeetingProblem) {
            ConfirmOutcome(entry, statusProblem = e.serverMessage ?: e.key)
        } catch (e: Throwable) {
            ConfirmOutcome(entry, statusProblem = "meetings.err.network")
        }
    }
}

/** The status step again, for an entry whose notes could not be read the first time. */
class RetryOutreachStatusUseCase(private val repository: MeetingRepository) {
    @Throws(Throwable::class)
    suspend fun execute(entryId: String): OutreachDecision = repository.updateStatus(entryId)
}
