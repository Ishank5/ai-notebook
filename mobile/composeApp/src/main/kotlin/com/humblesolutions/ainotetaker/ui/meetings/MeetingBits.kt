package com.humblesolutions.ainotetaker.ui.meetings

import androidx.compose.runtime.Composable
import com.humblesolutions.ainotetaker.ui.components.ChipTone
import com.humblesolutions.ainotetaker.ui.components.TasChip
import com.humblesolutions.ainotetaker.ui.theme.tr
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/* Small pieces the Meetings screens share. */

/** A string key, or a sentence the server sent, as text to show. */
@Composable
fun say(text: String): String = if (text.startsWith("meetings.")) tr(text) else text

/** A meeting's status as the server has it. */
@Composable
fun MeetingStatusChip(statusKey: String) {
    val tone = when (statusKey) {
        "ready" -> ChipTone.SUCCESS
        "failed" -> ChipTone.DANGER
        "uploading", "processing" -> ChipTone.PRIMARY
        else -> ChipTone.NEUTRAL
    }
    TasChip(text = tr("meetings.status.$statusKey"), tone = tone)
}

/** The summary's mood: positive, neutral, negative or mixed (older summaries may say tense or very_positive). */
@Composable
fun MoodChip(mood: String) {
    val (label, tone) = when (mood) {
        "positive", "very_positive" -> "Positive" to ChipTone.SUCCESS
        "negative" -> "Negative" to ChipTone.DANGER
        "mixed", "tense" -> "Mixed" to ChipTone.WARNING
        else -> "Neutral" to ChipTone.NEUTRAL
    }
    TasChip(text = label, tone = tone)
}

@Composable
fun LevelChip(level: String) {
    val tone = when (level) {
        "high" -> ChipTone.DANGER
        "medium" -> ChipTone.WARNING
        else -> ChipTone.NEUTRAL
    }
    TasChip(text = tr("meetings.level.$level"), tone = tone)
}

@Composable
fun QuestionChip(status: String) {
    val tone = when (status) {
        "answered" -> ChipTone.SUCCESS
        "open" -> ChipTone.WARNING
        else -> ChipTone.NEUTRAL
    }
    TasChip(text = tr("meetings.question.$status"), tone = tone)
}

/** "25 Nov, 14:02". */
fun meetingTime(epochMs: Long?): String =
    epochMs?.let { SimpleDateFormat("d MMM, HH:mm", Locale.ENGLISH).format(Date(it)) }.orEmpty()

/** "1 h 4 min", "12 min", "40 s". */
fun meetingLength(ms: Long?): String {
    val seconds = (ms ?: return "") / 1000
    return when {
        seconds <= 0 -> ""
        seconds < 60 -> "$seconds s"
        seconds < 3600 -> "${seconds / 60} min"
        else -> "${seconds / 3600} h ${(seconds % 3600) / 60} min"
    }
}

/** A moment in the meeting: "4:05". */
fun moment(seconds: Int?): String? = seconds?.let { String.format(Locale.US, "%d:%02d", it / 60, it % 60) }
