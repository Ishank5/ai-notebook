package com.humblesolutions.ainotetaker.meeting

import com.humblesolutions.ainotetaker.model.ActionItem
import com.humblesolutions.ainotetaker.model.Commitment
import com.humblesolutions.ainotetaker.model.CompanyOption
import com.humblesolutions.ainotetaker.model.FeedbackItem
import com.humblesolutions.ainotetaker.model.HiringIntent
import com.humblesolutions.ainotetaker.model.IssueItem
import com.humblesolutions.ainotetaker.model.MeetingAnalysis
import com.humblesolutions.ainotetaker.model.MeetingDetail
import com.humblesolutions.ainotetaker.model.MeetingProblem
import com.humblesolutions.ainotetaker.model.MeetingRow
import com.humblesolutions.ainotetaker.model.OutreachContact
import com.humblesolutions.ainotetaker.model.OutreachDecision
import com.humblesolutions.ainotetaker.model.OutreachDraft
import com.humblesolutions.ainotetaker.model.OutreachEntry
import com.humblesolutions.ainotetaker.model.OutreachFields
import com.humblesolutions.ainotetaker.model.Participant
import com.humblesolutions.ainotetaker.model.QuestionItem
import com.humblesolutions.ainotetaker.model.TimedText
import com.humblesolutions.ainotetaker.repository.CrmApiClient
import com.humblesolutions.ainotetaker.repository.CrmResponse
import com.humblesolutions.ainotetaker.repository.MeetingRepository
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/**
 * the CRM API's meetings and outreach calls, written once for both phones. The platform only makes
 * the HTTPS call ([CrmApiClient]); reading and writing the JSON, and what each refusal means, is here.
 *
 * The answers are the same JSON the Recordings pages and the Outreach Log read
 * (`server/src/recording/read.js`, `server/src/routes/logs.js`).
 */
class ApiMeetingRepository(private val api: CrmApiClient) : MeetingRepository {

    override suspend fun companies(): List<CompanyOption> =
        MeetingJson.companies(call("GET", "/api/recordings/companies"))

    override suspend fun meetings(fromMs: Long, toMs: Long): List<MeetingRow> =
        MeetingJson.meetings(call("GET", "/api/recordings?from=$fromMs&to=$toMs"))

    override suspend fun meeting(id: String): MeetingDetail =
        MeetingJson.meeting(call("GET", "/api/recordings/${pathPart(id)}"))

    override suspend fun saveEntry(entryId: String, draft: OutreachDraft): OutreachEntry =
        MeetingJson.entry(MeetingJson.objectOf(call("PATCH", "/api/logs/${pathPart(entryId)}", MeetingJson.draftBody(draft))))

    override suspend fun confirmEntry(entryId: String, draft: OutreachDraft): OutreachEntry =
        MeetingJson.entry(MeetingJson.objectOf(call("POST", "/api/logs/${pathPart(entryId)}/confirm", MeetingJson.draftBody(draft))))

    override suspend fun updateStatus(entryId: String): OutreachDecision =
        MeetingJson.decision(MeetingJson.objectOf(call("POST", "/api/logs/${pathPart(entryId)}/analyse", "{}")))
            ?: throw MeetingProblem("meetings.err.server")

    /** The body of a 2xx answer; anything else as a [MeetingProblem]. */
    private suspend fun call(method: String, path: String, body: String? = null): String {
        val answer: CrmResponse = try {
            api.send(method, path, body)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            throw MeetingProblem("meetings.err.network")
        }
        if (answer.status in 200..299) return answer.body
        throw MeetingJson.problemOf(answer)
    }

    /** Ids are UUIDs and the CRM's slug ids; anything else is kept out of the path. */
    private fun pathPart(id: String): String = id.filter { it.isLetterOrDigit() || it == '-' || it == '_' }
}

/** The JSON of the meetings calls, read leniently: a missing or odd value is empty, never a crash. */
object MeetingJson {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    fun objectOf(text: String): JsonObject = try {
        json.parseToJsonElement(text) as? JsonObject ?: JsonObject(emptyMap())
    } catch (e: Exception) {
        JsonObject(emptyMap())
    }

    fun companies(text: String): List<CompanyOption> =
        objectOf(text).arr("companies").mapNotNull { (it as? JsonObject)?.let(::company) }

    private fun company(o: JsonObject): CompanyOption? {
        val id = o.str("id").ifBlank { return null }
        return CompanyOption(id = id, name = o.str("name").ifBlank { id }, hint = o.str("hint"))
    }

    /** The list as the phone keeps it between launches: the API's own shape, so one reader serves both. */
    fun companiesText(companies: List<CompanyOption>): String = buildJsonObject {
        put("companies", JsonArray(companies.map { c -> buildJsonObject { put("id", c.id); put("name", c.name); put("hint", c.hint) } }))
    }.toString()

    fun meetings(text: String): List<MeetingRow> =
        objectOf(text).arr("rows").mapNotNull { (it as? JsonObject)?.let(::meetingRow) }
            .sortedByDescending { it.meetingAt ?: 0L }

    private fun meetingRow(o: JsonObject): MeetingRow? {
        val id = o.str("id").ifBlank { return null }
        return MeetingRow(
            id = id,
            status = o.str("status"),
            title = o.str("title").ifBlank { o.str("summaryTitle") },
            summaryText = o.str("summaryText"),
            mood = o.str("mood"),
            meetingAt = o.long("meetingAt"),
            durationMs = o.long("durationMs"),
            companyChoice = o.str("companyChoice").ifBlank { null },
            companyName = o.str("companyName").ifBlank { null },
            noSpeech = o.bool("noSpeech"),
        )
    }

    fun meeting(text: String): MeetingDetail {
        val o = objectOf(text)
        val recording = o.obj("recording")
        val row = meetingRow(recording) ?: throw MeetingProblem("meetings.err.notFound")
        val summaries = o.arr("summaries").mapNotNull { it as? JsonObject }
        val main = summaries.firstOrNull { it.str("id") == recording.str("summaryId") } ?: summaries.firstOrNull()
        return MeetingDetail(
            meeting = row,
            analysis = main?.obj("analysis")?.takeIf { it.isNotEmpty() }?.let(::analysis),
            outreach = (o["outreach"] as? JsonObject)?.let(::entry),
        )
    }

    fun analysis(a: JsonObject): MeetingAnalysis = MeetingAnalysis(
        title = a.str("title"),
        summary = a.str("summary"),
        howItWent = a.str("how_it_went"),
        mood = a.str("mood").ifBlank { "neutral" },
        moodReason = a.str("mood_reason"),
        meetingKind = a.str("meeting_kind"),
        suggestedStatus = a.str("suggested_status"),
        relationshipScore = a.long("relationship_score")?.toInt()?.coerceIn(0, 100),
        scoreReason = a.str("score_reason"),
        keyPoints = a.objects("key_points").map { TimedText(it.str("text"), it.at()) }.filter { it.text.isNotBlank() },
        decisions = a.objects("decisions").map { TimedText(it.str("text"), it.at()) }.filter { it.text.isNotBlank() },
        actionItems = a.objects("action_items").map {
            ActionItem(it.str("task"), it.str("owner"), it.str("due"), it.str("priority").ifBlank { "medium" }, it.str("raised_by"), it.at())
        }.filter { it.task.isNotBlank() },
        questions = a.objects("questions_raised").map {
            QuestionItem(it.str("question"), it.str("raised_by"), it.str("status").ifBlank { "open" }, it.str("answer"), it.at())
        }.filter { it.question.isNotBlank() },
        participants = a.objects("participants").map {
            Participant(it.str("name"), it.str("role"), it.str("stance"), it.str("note"), it.strings("speakers"))
        },
        hiringIntent = a.obj("hiring_intent").let {
            HiringIntent(it.strings("roles"), it.str("headcount"), it.str("timeline"), it.strings("branches"))
        },
        feedback = a.objects("industry_feedback").map {
            FeedbackItem(it.str("skill"), it.str("department"), it.str("severity").ifBlank { "medium" }, it.str("detail"), it.at())
        }.filter { it.skill.isNotBlank() || it.detail.isNotBlank() },
        issues = a.objects("issues_raised").map {
            IssueItem(it.str("area"), it.str("topic"), it.str("detail"), it.str("severity").ifBlank { "medium" }, it.str("raised_by"), it.at())
        },
        commitments = a.objects("commitments").map { Commitment(it.str("text"), it.str("by"), it.at()) }.filter { it.text.isNotBlank() },
        opportunities = a.strings("opportunities"),
        risks = a.strings("risks"),
        nextMeeting = a.str("next_meeting"),
        futureScope = a.str("future_scope"),
        conclusion = a.str("conclusion"),
    )

    private val SHOWN_FIELDS = setOf("status", "nextStep", "hiringModel", "projected", "stipend", "ctc", "driveDates", "dateFinalisation", "remarks", "contacts")

    fun entry(o: JsonObject): OutreachEntry {
        val f = o.obj("fields")
        val contacts = f.obj("contacts")
        fun person(kind: String) = contacts.obj(kind).let { OutreachContact(it.str("name"), it.str("designation")) }
        val ai = o.obj("ai")
        return OutreachEntry(
            id = o.str("id"),
            recordingId = o.str("recordingId").ifBlank { null },
            companyId = o.str("companyId"),
            companyName = o.str("companyName"),
            date = o.str("date"),
            channel = o.str("channel"),
            contactName = o.str("contactName"),
            notes = o.str("notes"),
            fields = OutreachFields(
                status = f.str("status"),
                nextStep = f.str("nextStep"),
                hiringModel = f.str("hiringModel"),
                projected = f.str("projected"),
                stipend = f.str("stipend"),
                ctc = f.str("ctc"),
                driveDates = f.str("driveDates"),
                dateFinalisation = f.str("dateFinalisation"),
                remarks = f.str("remarks"),
                primary = person("primary"),
                cxo = person("cxo"),
                alumni = person("alumni"),
                others = f.filterKeys { it !in SHOWN_FIELDS }.mapNotNull { (k, v) -> v.text()?.let { k to it } }.toMap(),
            ),
            confirmed = (o["confirmed"] as? JsonPrimitive)?.booleanOrNull ?: true,
            byUid = o.str("byUid"),
            byName = o.str("byName"),
            edited = o.str("editedAt").isNotBlank(),
            aiState = ai.str("state"),
            decision = decision(ai),
        )
    }

    /** The status step's answer, from `/analyse` or an entry's `ai`; null until it is done. */
    fun decision(o: JsonObject): OutreachDecision? {
        if (o.str("state") != "done") return null
        return OutreachDecision(
            status = o.str("status"),
            previousStatus = o.str("previousStatus"),
            nextStep = o.str("nextStep"),
            reason = o.str("reason"),
            applied = (o["applied"] as? JsonPrimitive)?.booleanOrNull ?: true,
        )
    }

    /** A draft as `PATCH /api/logs/:id` and `/confirm` take it. Empty fields are left out: they mean "no change". */
    fun draftBody(d: OutreachDraft): String = buildJsonObject {
        put("companyId", d.companyId)
        put("date", d.date)
        put("channel", d.channel)
        put("contactName", d.contactName.trim())
        put("notes", d.notes.trim())
        putJsonObject("fields") {
            val f = d.fields
            fun keep(key: String, value: String) { if (value.isNotBlank()) put(key, value.trim()) }
            for ((k, v) in f.others) keep(k, v)
            keep("status", f.status)
            keep("nextStep", f.nextStep)
            keep("hiringModel", f.hiringModel)
            keep("projected", f.projected)
            keep("stipend", f.stipend)
            keep("ctc", f.ctc)
            keep("driveDates", f.driveDates)
            keep("dateFinalisation", f.dateFinalisation)
            keep("remarks", f.remarks)
            putJsonObject("contacts") {
                for ((kind, c) in listOf("primary" to f.primary, "cxo" to f.cxo, "alumni" to f.alumni)) {
                    if (c.name.isBlank() && c.designation.isBlank()) continue
                    putJsonObject(kind) {
                        if (c.name.isNotBlank()) put("name", c.name.trim())
                        if (c.designation.isNotBlank()) put("designation", c.designation.trim())
                    }
                }
            }
        }
    }.toString()

    /** A refusal as a [MeetingProblem]: the CRM's sentence when it gave one, else what kind of failure it was. */
    fun problemOf(answer: CrmResponse): MeetingProblem {
        val body = objectOf(answer.body)
        val code = body.str("code")
        // The Outreach Log's refusals are sentences; the Recordings ones are keys such as "recordings.err.notFound".
        val sentence = body.str("error").takeIf { it.contains(' ') }
        val key = when {
            answer.status == 401 -> "meetings.err.signedOut"
            answer.status == 403 && code in setOf("inactive", "no_profile") -> "meetings.err.inactive"
            answer.status == 403 -> "meetings.err.notAllowed"
            answer.status == 404 -> "meetings.err.notFound"
            answer.status >= 500 -> "meetings.err.server"
            else -> "meetings.err.refused"
        }
        return MeetingProblem(key, if (answer.status in 400..499 && answer.status != 401) sentence else null)
    }

    /* ------------------------------------------------------------ reading */

    private fun JsonElement?.text(): String? = (this as? JsonPrimitive)?.takeIf { it !is JsonNull }?.contentOrNull

    private fun JsonObject.str(key: String): String = this[key].text()?.trim().orEmpty()
    private fun JsonObject.long(key: String): Long? = (this[key] as? JsonPrimitive)?.let { it.longOrNull ?: it.doubleOrNull?.toLong() }
    private fun JsonObject.bool(key: String): Boolean = (this[key] as? JsonPrimitive)?.booleanOrNull ?: false
    private fun JsonObject.obj(key: String): JsonObject = this[key] as? JsonObject ?: JsonObject(emptyMap())
    private fun JsonObject.arr(key: String): List<JsonElement> = this[key] as? JsonArray ?: emptyList()
    private fun JsonObject.objects(key: String): List<JsonObject> = arr(key).mapNotNull { it as? JsonObject }
    private fun JsonObject.strings(key: String): List<String> = arr(key).mapNotNull { it.text()?.trim() }.filter { it.isNotEmpty() }

    /** An item's moment in the audio, in seconds; null when it is not tied to one. */
    private fun JsonObject.at(): Int? = long("at_seconds")?.toInt()?.takeIf { it >= 0 }
}
