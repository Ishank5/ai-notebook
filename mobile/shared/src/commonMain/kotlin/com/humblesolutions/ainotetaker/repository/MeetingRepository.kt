package com.humblesolutions.ainotetaker.repository

import com.humblesolutions.ainotetaker.model.CompanyOption
import com.humblesolutions.ainotetaker.model.MeetingDetail
import com.humblesolutions.ainotetaker.model.MeetingRow
import com.humblesolutions.ainotetaker.model.OutreachDecision
import com.humblesolutions.ainotetaker.model.OutreachDraft
import com.humblesolutions.ainotetaker.model.OutreachEntry

/*
 * , all of this file: the person's meetings and their outreach entries, from the CRM API.
 */

/**
 * One HTTPS call to the CRM API, carrying the signed-in person's Firebase ID token, made by the platform (Android's
 * `CrmApi`, iOS's `CrmApi.swift`), which own the token and the connection. Everything about what the calls mean is
 * shared, in [com.humblesolutions.ainotetaker.meeting.ApiMeetingRepository].
 */
interface CrmApiClient {
    /** The server's answer, whatever its status. Throws only when none came back: no signal, a timeout, no token. */
    @Throws(Throwable::class)
    suspend fun send(method: String, path: String, body: String?): CrmResponse
}

/** An HTTP status and the JSON body as text (empty when there was none). */
data class CrmResponse(val status: Int = 0, val body: String = "")

/**
 * What the Meetings tab and the review form ask the CRM. Every call throws
 * [com.humblesolutions.ainotetaker.model.MeetingProblem] when it fails.
 */
interface MeetingRepository {
    /** The companies staff pick from before recording. A contributor is refused. */
    @Throws(Throwable::class)
    suspend fun companies(): List<CompanyOption>

    /** The person's own meetings in [fromMs, toMs], newest first. */
    @Throws(Throwable::class)
    suspend fun meetings(fromMs: Long, toMs: Long): List<MeetingRow>

    /** One of the person's meetings, with its main summary and its outreach entry. Someone else's is not found. */
    @Throws(Throwable::class)
    suspend fun meeting(id: String): MeetingDetail

    /** Saves a draft's values without confirming it. */
    @Throws(Throwable::class)
    suspend fun saveEntry(entryId: String, draft: OutreachDraft): OutreachEntry

    /** Confirms a draft with its final values: from now on it is an entry like one logged by hand. */
    @Throws(Throwable::class)
    suspend fun confirmEntry(entryId: String, draft: OutreachDraft): OutreachEntry

    /** Reads a confirmed entry's notes for the lead status, as the website does after logging an outreach. */
    @Throws(Throwable::class)
    suspend fun updateStatus(entryId: String): OutreachDecision
}

/**
 * Where the last company list is kept on the phone, so a meeting can be recorded with no signal: SharedPreferences
 * on Android, UserDefaults on iOS. The text is opaque to the platform.
 */
interface CompanyCacheStore {
    fun load(): String?
    fun save(text: String)
}
