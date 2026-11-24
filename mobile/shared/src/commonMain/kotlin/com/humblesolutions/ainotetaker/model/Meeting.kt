package com.humblesolutions.ainotetaker.model

/*
 * , all of this file: the company a meeting is with, the person's meetings as the CRM has them, each
 * one's summary, and the outreach entry it drafted. Read from the CRM API (`/api/recordings`, `/api/logs`); see the
 * "Notetaker" section of DEVELOPER_GUIDE.md.
 */

/** Who a meeting is with, picked before recording. The ids are the API's (`companyChoice`). */
enum class CompanyChoice(val id: String) {
    /** One of the CRM's outreach companies. */
    COMPANY("company"),

    /** An internal meeting: no outreach entry is drafted. */
    NONE("none"),

    /** No company list on the phone yet: picked when the entry is reviewed. */
    LATER("later");

    companion object {
        fun fromId(id: String?): CompanyChoice? = entries.firstOrNull { it.id == id }
    }
}

/** The company picked for a recording. [id] and [name] are set only for [CompanyChoice.COMPANY]. */
data class RecordingCompany(
    val choice: CompanyChoice = CompanyChoice.LATER,
    val id: String? = null,
    val name: String? = null,
) {
    companion object {
        fun company(option: CompanyOption) = RecordingCompany(CompanyChoice.COMPANY, option.id, option.name)
        val NONE = RecordingCompany(CompanyChoice.NONE)
        val LATER = RecordingCompany(CompanyChoice.LATER)

        /** From stored parts; a company without an id is one still to be picked. */
        fun of(choice: String?, id: String?, name: String?): RecordingCompany? = when (CompanyChoice.fromId(choice)) {
            CompanyChoice.COMPANY -> if (id.isNullOrBlank()) LATER else RecordingCompany(CompanyChoice.COMPANY, id, name)
            CompanyChoice.NONE -> NONE
            CompanyChoice.LATER -> LATER
            null -> null
        }
    }
}

/** One company in the picker: the CRM's outreach entry id, its name, and "location · manager". */
data class CompanyOption(val id: String = "", val name: String = "", val hint: String = "")

/**
 * The picker's list. [loaded] is false when no list has ever reached this phone (offline on the first day), when
 * the only choices are "No company" and "Pick later". [fromCache] says it is the last list saved, not a fresh one.
 */
data class CompanyList(
    val companies: List<CompanyOption> = emptyList(),
    val loaded: Boolean = false,
    val fromCache: Boolean = false,
)

/** A meeting in the person's list, as the CRM has it. */
data class MeetingRow(
    val id: String = "",
    /** The server's status: uploading, uploaded, processing, ready, failed or deleted. */
    val status: String = "",
    val title: String = "",
    val summaryText: String = "",
    val mood: String = "",
    val meetingAt: Long? = null,
    val durationMs: Long? = null,
    val companyChoice: String? = null,
    val companyName: String? = null,
    val noSpeech: Boolean = false,
) {
    /** What the row says: uploading, processing, ready, noSpeech, failed or deleted. */
    val statusKey: String
        get() = when {
            status == "ready" && noSpeech -> "noSpeech"
            status == "uploaded" -> "processing"
            status in setOf("uploading", "processing", "ready", "failed", "deleted") -> status
            else -> "processing"
        }

    /** A summary to open. */
    val hasSummary: Boolean get() = status == "ready" && !noSpeech

    /** Still uploading or being processed on the server: screens look again every few seconds. */
    val working: Boolean get() = status == "uploading" || status == "uploaded" || status == "processing"
}

data class TimedText(val text: String = "", val atSeconds: Int? = null)

data class ActionItem(
    val task: String = "",
    val owner: String = "",
    val due: String = "",
    /** high, medium or low. */
    val priority: String = "medium",
    val raisedBy: String = "",
    val atSeconds: Int? = null,
)

data class QuestionItem(
    val question: String = "",
    val raisedBy: String = "",
    /** answered, open or parked. */
    val status: String = "open",
    val answer: String = "",
    val atSeconds: Int? = null,
)

data class IssueItem(
    val area: String = "",
    val topic: String = "",
    val detail: String = "",
    val severity: String = "medium",
    val raisedBy: String = "",
    val atSeconds: Int? = null,
)

/** Who spoke, and where they stood: the old demo's stakeholder reactions. */
data class Participant(
    val name: String = "",
    val role: String = "",
    val stance: String = "",
    val note: String = "",
    val speakers: List<String> = emptyList(),
)

data class Commitment(val text: String = "", val by: String = "", val atSeconds: Int? = null)

data class HiringIntent(
    val roles: List<String> = emptyList(),
    val headcount: String = "",
    val timeline: String = "",
    val branches: List<String> = emptyList(),
) {
    val isEmpty: Boolean get() = roles.isEmpty() && headcount.isBlank() && timeline.isBlank() && branches.isEmpty()
}

/** Feedback for academics: a gap the company saw in students' skills or the curriculum. */
data class FeedbackItem(
    val skill: String = "",
    val department: String = "",
    val severity: String = "medium",
    val detail: String = "",
    val atSeconds: Int? = null,
)

/** A meeting's summary, every section the Recordings page shows. Empty sections are empty, never padded. */
data class MeetingAnalysis(
    val title: String = "",
    val summary: String = "",
    val howItWent: String = "",
    /** very_positive, positive, neutral, tense or negative. */
    val mood: String = "neutral",
    val moodReason: String = "",
    val meetingKind: String = "",
    /** One of [OutreachVocabulary.STATUSES], or empty. */
    val suggestedStatus: String = "",
    /** 0 to 100, or null when the summary has none (one written before the score existed). */
    val relationshipScore: Int? = null,
    val scoreReason: String = "",
    val keyPoints: List<TimedText> = emptyList(),
    val decisions: List<TimedText> = emptyList(),
    val actionItems: List<ActionItem> = emptyList(),
    val questions: List<QuestionItem> = emptyList(),
    val participants: List<Participant> = emptyList(),
    val hiringIntent: HiringIntent = HiringIntent(),
    val feedback: List<FeedbackItem> = emptyList(),
    val issues: List<IssueItem> = emptyList(),
    val commitments: List<Commitment> = emptyList(),
    val opportunities: List<String> = emptyList(),
    val risks: List<String> = emptyList(),
    val nextMeeting: String = "",
    val futureScope: String = "",
    val conclusion: String = "",
)

data class OutreachContact(val name: String = "", val designation: String = "")

/**
 * The company fields an outreach entry sets, as text. Empty means "no change" to what the company has on file.
 * Fields the phone's form does not show (selections, converted on, location, campus, manager, region, how to
 * position) are kept in [others] and sent back as they were, so confirming on the phone never drops one typed on
 * the website.
 */
data class OutreachFields(
    val status: String = "",
    val nextStep: String = "",
    val hiringModel: String = "",
    val projected: String = "",
    val stipend: String = "",
    val ctc: String = "",
    val driveDates: String = "",
    val dateFinalisation: String = "",
    val remarks: String = "",
    val primary: OutreachContact = OutreachContact(),
    val cxo: OutreachContact = OutreachContact(),
    val alumni: OutreachContact = OutreachContact(),
    val others: Map<String, String> = emptyMap(),
) {
    /** Something was typed: notes are then optional. Pay and counts are read on the server. */
    val anyTyped: Boolean
        get() = listOf(status, nextStep, hiringModel, projected, stipend, ctc, driveDates, dateFinalisation, remarks).any { it.isNotBlank() } ||
            listOf(primary, cxo, alumni).any { it.name.isNotBlank() || it.designation.isNotBlank() } ||
            others.values.any { it.isNotBlank() }
}

/** The status step's answer after an entry with notes is confirmed: the lead status before and after. */
data class OutreachDecision(
    val status: String = "",
    val previousStatus: String = "",
    val nextStep: String = "",
    val reason: String = "",
    /** False when a later entry already set the status, so this one did not change it. */
    val applied: Boolean = true,
)

/** An outreach entry: a meeting's draft before it is confirmed, or any entry after. */
data class OutreachEntry(
    val id: String = "",
    val recordingId: String? = null,
    /** Empty while a "pick later" meeting has no company yet. */
    val companyId: String = "",
    val companyName: String = "",
    /** yyyy-mm-dd. */
    val date: String = "",
    val channel: String = "",
    val contactName: String = "",
    val notes: String = "",
    val fields: OutreachFields = OutreachFields(),
    val confirmed: Boolean = true,
    val byUid: String = "",
    val byName: String = "",
    val edited: Boolean = false,
    /** pending, running, done, failed or none: where the status step is. */
    val aiState: String = "",
    val decision: OutreachDecision? = null,
) {
    /** Only the person who recorded the meeting, or the Super Admin, reviews and confirms a draft. */
    fun editableBy(uid: String, role: Role?): Boolean = !confirmed && (byUid == uid || role == Role.SUPER_ADMIN)

    /** The form's starting values. */
    fun toDraft() = OutreachDraft(companyId, companyName, date, channel, contactName, notes, fields)
}

/** The review form: every value of an entry, edited before it is saved or confirmed. */
data class OutreachDraft(
    val companyId: String = "",
    val companyName: String = "",
    val date: String = "",
    val channel: String = "",
    val contactName: String = "",
    val notes: String = "",
    val fields: OutreachFields = OutreachFields(),
) {
    /** Why it cannot be confirmed yet, as a string key, or null when it can. The server checks again. */
    fun problem(): String? = when {
        companyId.isBlank() -> "meetings.err.pickCompany"
        !Regex("^\\d{4}-\\d{2}-\\d{2}$").matches(date) -> "meetings.err.badDate"
        notes.isBlank() && !fields.anyTyped -> "meetings.err.notesOrField"
        else -> null
    }
}

/** One meeting: its row, its main summary, and the entry it drafted, if any. */
data class MeetingDetail(
    val meeting: MeetingRow = MeetingRow(),
    val analysis: MeetingAnalysis? = null,
    val outreach: OutreachEntry? = null,
)

/** After confirming: the entry, and the status step's answer, or why it could not run (it can be retried). */
data class ConfirmOutcome(
    val entry: OutreachEntry = OutreachEntry(),
    val decision: OutreachDecision? = null,
    val statusProblem: String? = null,
)

/**
 * Why a meetings call failed. [key] is a `meetings.err.*` string key; [serverMessage] is the CRM's own sentence when
 * it gave one ("Pick a valid date."), which a screen shows instead.
 */
class MeetingProblem(val key: String, val serverMessage: String? = null) : Exception(serverMessage ?: key)
