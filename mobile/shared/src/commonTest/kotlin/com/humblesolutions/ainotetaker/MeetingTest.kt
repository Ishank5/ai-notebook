package com.humblesolutions.ainotetaker

import com.humblesolutions.ainotetaker.meeting.ApiMeetingRepository
import com.humblesolutions.ainotetaker.meeting.MeetingJson
import com.humblesolutions.ainotetaker.model.CompanyChoice
import com.humblesolutions.ainotetaker.model.CompanyOption
import com.humblesolutions.ainotetaker.model.MeetingProblem
import com.humblesolutions.ainotetaker.model.OutreachContact
import com.humblesolutions.ainotetaker.model.OutreachDraft
import com.humblesolutions.ainotetaker.model.OutreachFields
import com.humblesolutions.ainotetaker.model.RecordingCompany
import com.humblesolutions.ainotetaker.model.RecordingState
import com.humblesolutions.ainotetaker.model.Role
import com.humblesolutions.ainotetaker.recording.RecorderRuntime
import com.humblesolutions.ainotetaker.recording.SqlRecordingStore
import com.humblesolutions.ainotetaker.recording.db.RecordingDatabase
import com.humblesolutions.ainotetaker.repository.CompanyCacheStore
import com.humblesolutions.ainotetaker.repository.CrmApiClient
import com.humblesolutions.ainotetaker.repository.CrmResponse
import com.humblesolutions.ainotetaker.repository.RecordingFiles
import com.humblesolutions.ainotetaker.usecase.ConfirmOutreachEntryUseCase
import com.humblesolutions.ainotetaker.usecase.LoadCompaniesUseCase
import com.humblesolutions.ainotetaker.usecase.LoadMeetingsUseCase
import com.humblesolutions.ainotetaker.usecase.SetRecordingCompanyUseCase
import com.humblesolutions.ainotetaker.usecase.StartRecordingUseCase
import com.humblesolutions.ainotetaker.usecase.picksCompany
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * the company before recording, the Meetings tab and the outreach entry, against a fake CRM API.
 * The names are made up.
 */
class MeetingTest {

    /** The CRM API: answers by method and path, and records every call. */
    private class FakeApi : CrmApiClient {
        val answers = mutableMapOf<String, CrmResponse>()
        val calls = mutableListOf<Triple<String, String, String?>>()
        var offline = false

        override suspend fun send(method: String, path: String, body: String?): CrmResponse {
            calls += Triple(method, path, body)
            if (offline) throw IllegalStateException("no signal")
            return answers["$method ${path.substringBefore('?')}"] ?: CrmResponse(404, """{"error":"Not found."}""")
        }
    }

    private class MemoryCache : CompanyCacheStore {
        var text: String? = null
        override fun load() = text
        override fun save(text: String) { this.text = text }
    }

    private val detail = """
        {"recording":{"id":"r1","status":"ready","summaryId":"s2","summaryTitle":"Hiring call","meetingAt":1763000000000,"companyChoice":"company","companyName":"Lumenfield Systems"},
         "summaries":[{"id":"s1","analysis":{"title":"Old"}},{"id":"s2","analysis":{
           "title":"Hiring call","summary":"They will hire.","mood":"positive","suggested_status":"Warm - Engagement Done",
           "relationship_score":140,"score_reason":"Keen.","key_points":[{"text":"Twelve interns","at_seconds":42},{"text":"","at_seconds":1}],
           "action_items":[{"task":"Send the JD","owner":"Hema Hirewell","due":"Friday","priority":"high","raised_by":"S1","at_seconds":-1}],
           "questions_raised":[{"question":"Stipend?","raised_by":"S2","status":"answered","answer":"45K","at_seconds":60}],
           "participants":[{"name":"Hema Hirewell","role":"HR Lead","stance":"Keen","note":"","speakers":["S2"]}],
           "hiring_intent":{"roles":["SDE intern"],"headcount":"12","timeline":"January","branches":["CSE"]},
           "industry_feedback":[{"skill":"System design","department":"CSED","severity":"medium","detail":"More of it.","at_seconds":90}],
           "opportunities":["A yearly drive"],"risks":[],"next_meeting":"","future_scope":"Two drives a year.","conclusion":"Done."}}],
         "access":[],
         "outreach":{"id":"log1","recordingId":"r1","companyId":"lumenfield","companyName":"Lumenfield Systems","date":"2026-11-20","channel":"In-person meeting",
           "contactName":"Hema Hirewell","notes":"Met the hiring team.","confirmed":false,"byUid":"rec1","byName":"Riya Recorder","editedAt":null,"ai":{"state":"none"},
           "fields":{"status":"Hot - JD to be shared","ctc":"1400000","selections":"3","contacts":{"primary":{"name":"Hema Hirewell","designation":"HR Lead"}}}}}
    """.trimIndent()

    @Test
    fun `a meeting is read with its main summary every section and its draft entry`() {
        val d = MeetingJson.meeting(detail)
        assertEquals("Hiring call", d.meeting.title)
        assertEquals("ready", d.meeting.statusKey)
        val a = d.analysis!!
        assertEquals("They will hire.", a.summary)
        assertEquals(100, a.relationshipScore, "a score is held to 0 to 100")
        assertEquals(listOf("Twelve interns"), a.keyPoints.map { it.text })
        assertEquals(42, a.keyPoints[0].atSeconds)
        assertNull(a.actionItems[0].atSeconds, "-1 is not a moment")
        assertEquals("answered", a.questions[0].status)
        assertEquals("12", a.hiringIntent.headcount)
        assertEquals("CSED", a.feedback[0].department)
        assertEquals("Two drives a year.", a.futureScope)
        val e = d.outreach!!
        assertFalse(e.confirmed)
        assertEquals("Hot - JD to be shared", e.fields.status)
        assertEquals(OutreachContact("Hema Hirewell", "HR Lead"), e.fields.primary)
        assertEquals(mapOf("selections" to "3"), e.fields.others, "a field the phone does not show is kept")
        assertTrue(e.editableBy("rec1", Role.ADMIN))
        assertTrue(e.editableBy("someone", Role.SUPER_ADMIN))
        assertFalse(e.editableBy("other", Role.ADMIN))
    }

    @Test
    fun `a draft is sent without its empty fields and keeps the ones the phone does not show`() {
        val body = MeetingJson.objectOf(
            MeetingJson.draftBody(
                OutreachDraft(
                    companyId = "lumenfield", date = "2026-11-20", channel = "Call", notes = " Met them. ",
                    fields = OutreachFields(status = "Hot - JD to be shared", ctc = " ", cxo = OutreachContact(designation = "CTO"), others = mapOf("selections" to "3")),
                ),
            ),
        )
        assertTrue(body["fields"].toString().contains("\"contacts\":{\"cxo\":{\"designation\":\"CTO\"}}"))
        assertTrue(body["fields"].toString().contains("\"selections\":\"3\""))
        assertFalse(body["fields"].toString().contains("ctc"))
        assertEquals("\"Met them.\"", body["notes"].toString())
    }

    @Test
    fun `the company list is saved for the next time there is no signal`() = runTest {
        val api = FakeApi()
        val cache = MemoryCache()
        val load = LoadCompaniesUseCase(ApiMeetingRepository(api), cache)
        assertFalse(load.cached().loaded, "nothing saved yet: the screen offers Pick later")

        api.offline = true
        assertFalse(load.refresh().loaded)

        api.offline = false
        api.answers["GET /api/recordings/companies"] = CrmResponse(200, """{"companies":[{"id":"lumenfield","name":"Lumenfield Systems","hint":"Pune · Asha"},{"name":"No id"}]}""")
        val fresh = load.refresh()
        assertEquals(listOf(CompanyOption("lumenfield", "Lumenfield Systems", "Pune · Asha")), fresh.companies)
        assertFalse(fresh.fromCache)

        api.offline = true
        val saved = load.refresh()
        assertTrue(saved.loaded && saved.fromCache)
        assertEquals(fresh.companies, saved.companies)
    }

    @Test
    fun `confirming runs the status step only when there are notes and a failed step leaves the entry confirmed`() = runTest {
        val api = FakeApi()
        val confirm = ConfirmOutreachEntryUseCase(ApiMeetingRepository(api))
        val draft = OutreachDraft(companyId = "lumenfield", date = "2026-11-20", channel = "Call", notes = "Met them.")

        assertEquals("meetings.err.pickCompany", assertFailsWith<MeetingProblem> { confirm.execute("log1", draft.copy(companyId = "")) }.key)
        assertEquals("meetings.err.notesOrField", assertFailsWith<MeetingProblem> { confirm.execute("log1", draft.copy(notes = "")) }.key)
        assertTrue(api.calls.isEmpty(), "nothing is sent until the form is complete")

        api.answers["POST /api/logs/log1/confirm"] = CrmResponse(200, """{"id":"log1","notes":"Met them.","confirmed":true,"ai":{"state":"pending"}}""")
        api.answers["POST /api/logs/log1/analyse"] = CrmResponse(200, """{"state":"done","status":"Hot - JD to be shared","previousStatus":"In Pipeline","nextStep":"Waiting for JD","applied":true}""")
        val done = confirm.execute("log1", draft)
        assertTrue(done.entry.confirmed)
        assertEquals("Hot - JD to be shared", done.decision?.status)
        assertEquals("In Pipeline", done.decision?.previousStatus)

        api.answers["POST /api/logs/log1/analyse"] = CrmResponse(429, """{"error":"Too many updates. Wait a few minutes and retry."}""")
        val busy = confirm.execute("log1", draft)
        assertTrue(busy.entry.confirmed)
        assertEquals("Too many updates. Wait a few minutes and retry.", busy.statusProblem)

        api.calls.clear()
        api.answers["POST /api/logs/log1/confirm"] = CrmResponse(200, """{"id":"log1","notes":"","confirmed":true,"ai":{"state":"none"}}""")
        confirm.execute("log1", draft.copy(notes = "", fields = OutreachFields(status = "On Hold")))
        assertEquals(listOf("POST /api/logs/log1/confirm"), api.calls.map { "${it.first} ${it.second}" }, "no notes, nothing to read")
    }

    @Test
    fun `a refusal says what the CRM said and someone else's meeting is not found`() = runTest {
        val api = FakeApi()
        val repo = ApiMeetingRepository(api)
        api.answers["POST /api/logs/log1/confirm"] = CrmResponse(403, """{"error":"Only the person who recorded this meeting can change this entry."}""")
        val refused = assertFailsWith<MeetingProblem> { repo.confirmEntry("log1", OutreachDraft()) }
        assertEquals("meetings.err.notAllowed", refused.key)
        assertEquals("Only the person who recorded this meeting can change this entry.", refused.serverMessage)
        api.answers["GET /api/recordings/r9"] = CrmResponse(404, """{"error":"recordings.err.notFound","code":"recordings.err.notFound"}""")
        val missing = assertFailsWith<MeetingProblem> { repo.meeting("r9") }
        assertEquals("meetings.err.notFound", missing.key)
        assertNull(missing.serverMessage, "a key is not a sentence to show")
        api.offline = true
        assertEquals("meetings.err.network", assertFailsWith<MeetingProblem> { repo.meetings(0, 1) }.key)
    }

    @Test
    fun `meetings are listed newest first over the last six months`() = runTest {
        val api = FakeApi()
        api.answers["GET /api/recordings"] = CrmResponse(200, """{"rows":[{"id":"a","status":"uploaded","meetingAt":1},{"id":"b","status":"ready","noSpeech":true,"meetingAt":3}],"truncated":false}""")
        val rows = LoadMeetingsUseCase(ApiMeetingRepository(api)).execute(1_763_000_000_000L)
        assertEquals(listOf("b", "a"), rows.map { it.id })
        assertEquals(listOf("noSpeech", "processing"), rows.map { it.statusKey })
        assertFalse(rows[0].hasSummary)
        assertTrue(api.calls.single().second.startsWith("/api/recordings?from="))
    }

    @Test
    fun `only staff pick a company`() {
        assertTrue(Role.ADMIN.picksCompany())
        assertTrue(Role.SUPER_ADMIN.picksCompany())
        assertFalse(Role.CONTRIBUTOR.picksCompany())
        assertFalse((null as Role?).picksCompany())
    }

    @Test
    fun `the company is stored with the recording from its first row and a change after upload sends it again`() = runTest {
        val store = SqlRecordingStore(RecordingDatabase(testRecordingDriver()), Dispatchers.Unconfined)
        val files = object : RecordingFiles {
            override fun freeBytes() = Long.MAX_VALUE
            override suspend fun read(path: String): ByteArray? = null
            override suspend fun size(path: String): Long? = null
            override suspend fun exists(path: String): Boolean = false
            override suspend fun seal(path: String, length: Long): String? = null
            override suspend fun delete(path: String) {}
            override suspend fun reject(path: String): Boolean = true
            override suspend fun deleteFolder(folder: String) {}
        }
        val company = RecordingCompany.company(CompanyOption("lumenfield", "Lumenfield Systems"))
        val id = StartRecordingUseCase(store, files, RecorderRuntime()).execute("u1", 1_763_000_000_000L, company).recordingId
        assertEquals(company, store.byId(id)?.company)

        store.markUploaded(id, 1_763_000_100_000L)
        SetRecordingCompanyUseCase(store).execute(id, RecordingCompany.NONE)
        assertEquals(CompanyChoice.NONE, store.byId(id)?.company?.choice)
        assertEquals(RecordingState.STOPPED, store.byId(id)?.state, "sent again with the next sync")

        val plain = StartRecordingUseCase(store, files, RecorderRuntime()).execute("u1", 1_763_000_200_000L).recordingId
        assertNull(store.byId(plain)?.company, "a contributor's recording carries none")
    }
}
