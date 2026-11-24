package com.humblesolutions.ainotetaker.model

/**
 * the Outreach Log's lists, the same values as the original CRM (taken from the
 * university's Outreach Tracker), with the short names the website shows. The stored values are sent as they are;
 * the server refuses anything else. Keep the two in step.
 */
object OutreachVocabulary {

    val STATUSES: List<String> = listOf(
        "Super Hot - JD Shared",
        "Hot - JD to be shared",
        "Warm - Engagement Done",
        "Cold - In touch, Meeting/Call has happened",
        "Process Ongoing",
        "In Pipeline",
        "Hiring Completed",
        "Low CTC",
        "On Hold",
    )

    private val STATUS_LABEL = mapOf(
        "Super Hot - JD Shared" to "Super Hot",
        "Hot - JD to be shared" to "Hot",
        "Warm - Engagement Done" to "Warm",
        "Cold - In touch, Meeting/Call has happened" to "Cold",
    )

    val NEXT_STEPS: List<String> = listOf(
        "Requires Immediate action/follow-up",
        "Plan Meeting",
        "Calling to be done",
        "Waiting for JD",
        "Hiring plan to be shared",
        "Date Finalization",
        "Check for update",
        "Not heard from them",
    )

    private val NEXT_STEP_LABEL = mapOf(
        "Requires Immediate action/follow-up" to "Immediate follow-up",
        "Plan Meeting" to "Plan a meeting",
        "Calling to be done" to "Call to be made",
        "Date Finalization" to "Finalise dates",
        "Not heard from them" to "No response yet",
    )

    val HIRING_MODELS: List<String> = listOf(
        "FT", "FT + 2/3/6 month internship", "Intern + PPO", "2 Mon Intern", "3 Mon Intern", "6 Mon Intern", "1 Year Intern",
    )

    val DATE_FINALISATION: List<String> = listOf("Date Finalised", "Date In- Process", "Date not Finalised")

    private val DATE_LABEL = mapOf(
        "Date Finalised" to "Finalised",
        "Date In- Process" to "In process",
        "Date not Finalised" to "Not finalised",
    )

    val CHANNELS: List<String> = listOf(
        "Call", "Email", "In-person meeting", "Online meeting", "Campus visit", "LinkedIn", "WhatsApp", "Event",
    )

    fun statusLabel(value: String): String = STATUS_LABEL[value] ?: value
    fun nextStepLabel(value: String): String = NEXT_STEP_LABEL[value] ?: value
    fun dateLabel(value: String): String = DATE_LABEL[value] ?: value

    /** The colour family a status pill uses on the website, so both read the same: hot, warm, cold, good, neutral, hold. */
    fun statusTone(value: String): String = when (value) {
        "Super Hot - JD Shared", "Hot - JD to be shared" -> "hot"
        "Warm - Engagement Done" -> "warm"
        "Cold - In touch, Meeting/Call has happened" -> "cold"
        "Process Ongoing", "Hiring Completed" -> "good"
        "On Hold" -> "hold"
        else -> "neutral"
    }
}
