package com.humblesolutions.ainotetaker.model

/**
 * A meeting recording as the phone knows it.
 *
 * NOT THE SERVER'S RECORD. The server keeps `recordings/{id}` with the same id,
 * written only by Cloud Functions; this is the phone's own queue entry, and it
 * exists from the first second of audio — offline, before the server has heard
 * of it. The two meet when every chunk has uploaded and the server has checked
 * them, at which point [state] becomes [RecordingState.UPLOADED].
 */
data class LocalRecording(
    val id: String = "",
    val ownerUid: String = "",
    val state: RecordingState = RecordingState.RECORDING,
    /** Epoch milliseconds on the phone's clock. */
    val startedAt: Long = 0L,
    val endedAt: Long? = null,
    val endReason: EndReason? = null,
    val title: String? = null,
    val meetingType: String? = null,
    val location: String? = null,
    val attendees: String? = null,
    val heartbeatAt: Long? = null,
    val uploadedAt: Long? = null,
    /** The audio files were removed after the retention window; the row stays as history. */
    val purgedAt: Long? = null,
    /** A translation key, not prose. */
    val lastError: String? = null,
    /** the company the meeting is with ([CompanyChoice] id), its CRM id and its name then. */
    val companyChoice: String? = null,
    val companyId: String? = null,
    val companyName: String? = null,
    /** the length picked before Record, in audio ms; null on a row from before plans. */
    val planMs: Long? = null,
    /** where it stops now, in audio ms, after any Keep recording; null on a row from before plans. */
    val stopAtMs: Long? = null,
    /**
     * when the phone found it cut off, in monotonic ms since boot (counting sleep); null while it
     * runs, and on one continued. The hour before it is sent by itself counts from here, not from the wall clock.
     */
    val foundUptimeMs: Long? = null,
    /** what the platform said about the process that died, for the developers. */
    val exitInfo: String? = null,
    /** who recorded it, by name, when the phone knew; null on a row from before. */
    val ownerName: String? = null,
) {
    /** the company as picked, or null when the phone did not ask. */
    val company: RecordingCompany? get() = RecordingCompany.of(companyChoice, companyId, companyName)
}

/**
 * another person's recordings on this phone that are not yet on the server. They go only when that
 * person signs in here again, so the person signed in now is told whose they are.
 */
data class OthersWaiting(
    val ownerUid: String = "",
    /** Null for recordings made before names were kept. */
    val ownerName: String? = null,
    val count: Int = 0,
)

/**
 * A recording with the numbers a list row needs.
 *
 * Kept apart from [LocalRecording] because these are derived from the chunk
 * table on every read, never stored — a stored count would be one more thing
 * to keep in step with the files it counts.
 */
data class RecordingSummary(
    val recording: LocalRecording = LocalRecording(),
    val chunks: Int = 0,
    val uploadedChunks: Int = 0,
    val durationMs: Long = 0L,
    val bytes: Long = 0L,
) {
    /** Every chunk is on the server, even if the server has not yet confirmed. */
    val fullyUploaded: Boolean get() = chunks > 0 && uploadedChunks == chunks
}

/**
 * Where a recording is in its life on this phone.
 *
 * `interrupted` is the one state a person has to act on: the app died while
 * recording, and they choose to continue into the same recording or to finish
 * it. Everything else moves by itself.
 */
enum class RecordingState(val id: String) {
    RECORDING("recording"),
    PAUSED("paused"),
    INTERRUPTED("interrupted"),
    STOPPED("stopped"),
    UPLOADED("uploaded");

    val isActive: Boolean get() = this == RECORDING || this == PAUSED

    companion object {
        fun fromId(id: String?): RecordingState =
            entries.firstOrNull { it.id == id } ?: STOPPED
    }
}

/** Why capture ended. Sent to the server as-is; the ids are a contract. */
enum class EndReason(val id: String) {
    STOPPED("stopped"),
    INTERRUPTED("interrupted"),
    /** The 3-hour cap (TAS's was 90 minutes). */
    LIMIT("limit"),

    /**
     * the planned length was reached, nobody answered the
     * warning within the grace window, and it stopped by itself. The server
     * accepts it from the same release (`END_REASONS` in recordings.js).
     */
    PLANNED("planned");

    companion object {
        fun fromId(id: String?): EndReason? = entries.firstOrNull { it.id == id }
    }
}

enum class ChunkState(val id: String) {
    OPEN("open"),
    CLOSED("closed"),
    UPLOADED("uploaded");

    companion object {
        fun fromId(id: String?): ChunkState = entries.firstOrNull { it.id == id } ?: CLOSED
    }
}

/**
 * One file of audio: about a minute of ADTS AAC, or less when a pause,
 * interruption or Stop closed it early.
 */
data class RecordingChunk(
    val recordingId: String = "",
    val seq: Int = 0,
    /** Relative to the recorder's folder — see `RecordingPaths`. */
    val path: String = "",
    val bytes: Long = 0L,
    val durationMs: Long = 0L,
    /** Base64 MD5 of the file. Null while it is still being written. */
    val md5: String? = null,
    val state: ChunkState = ChunkState.OPEN,
    val attempts: Int = 0,
    /** Times Storage refused this exact file, as opposed to never hearing it. */
    val rejections: Int = 0,
    /** upload runs that found the file there but could not read (or seal) it. */
    val unreadable: Int = 0,
    /** when the first of those was (epoch ms), or null. */
    val unreadableSince: Long? = null,
)

/**
 * Time the room was not being recorded, and why.
 *
 * WALL-CLOCK OFFSETS from the recording's start, so an admin reading
 * "paused 14:02–14:07, phone call" can line it up with the meeting itself.
 */
data class RecordingGap(
    val recordingId: String = "",
    val startMs: Long = 0L,
    val endMs: Long? = null,
    val reason: GapReason = GapReason.INTERRUPTION,
)

enum class GapReason(val id: String) {
    /** Another app — nearly always a phone call — took the microphone. */
    CALL("call"),
    /** The worker pressed Pause. */
    USER("user"),
    /** The system interrupted the audio session (Siri, an alarm, a route change). */
    INTERRUPTION("interruption"),
    /** The app died and was later continued. */
    KILLED("killed");

    companion object {
        fun fromId(id: String?): GapReason = entries.firstOrNull { it.id == id } ?: INTERRUPTION
    }
}

/** A signed link for one chunk, and the headers the signature covers. */
data class ChunkUploadTarget(
    val seq: Int = 0,
    val url: String = "",
    val headers: Map<String, String> = emptyMap(),
)

/**
 * The server's answer to "links for these chunks, please".
 *
 * A RESULT, NOT AN EXCEPTION, because iOS implements this in Swift: an error
 * thrown across that boundary arrives in Kotlin as whatever the bridge makes
 * of it, and the sync has to tell "no signal" from "not your recording" to
 * decide between trying again and giving up.
 */
data class UploadTargets(
    val targets: List<ChunkUploadTarget> = emptyList(),
    /** A `recorder.err.*` key; null when [targets] is the answer. */
    val errorKey: String? = null,
    /**
     * chunks the server will never take, with no link (`recorder.err.chunkTooLarge`: over 1 MB). They
     * are set aside, and the recording is finalized without them.
     */
    val refusedSeqs: List<Int> = emptyList(),
)

/** What the server said when asked to close a recording. */
data class FinalizeResult(
    val ok: Boolean = false,
    /** Chunk numbers the server does not have; they go back into the queue. */
    val missing: List<Int> = emptyList(),
    /** A `recorder.err.*` key when the call itself failed. */
    val errorKey: String? = null,
)

/** How one PUT of one chunk went. */
enum class TransferResult {
    /** Storage answered 2xx, which means it checked the MD5 and kept the file. */
    UPLOADED,

    /**
     * No answer at all: no signal, a timeout. The same bytes may work later, and nothing else will go now either.
     * a 5xx or 429 is [FAILED] now, not this.
     */
    RETRY,

    /**
     * Storage answered, and not with success or a fault in the file: a 5xx, a 429, an expired or
     * refused link. This recording waits for the next run; the others still go in this one.
     */
    FAILED,

    /** A 4xx: Storage saw these bytes and refused them. Trying again unchanged will not help. */
    REJECTED,

    /** The file is not on the phone any more. */
    MISSING,

    /**
     * Storage refused the signature (403 `SignatureDoesNotMatch`). The link is signed for the row's
     * size and MD5, so this is nearly always a file that no longer matches its row: it is measured again and
     * repaired. A file that still matches is the server's problem, and is not counted against the chunk.
     */
    MISMATCH,
}

/**
 * Why the recorder would not do what was asked, as a translation key.
 *
 * Thrown only by use cases, only for things a person can act on — the
 * microphone is taken, the phone is full — never for a failed upload, which is
 * the sync's business and never the person's problem.
 */
class RecorderRefused(val stringKey: String) : Exception(stringKey)

/** Sent with the first upload so an admin can tell which phone made a recording. */
data class RecorderDevice(
    val platform: String = "",
    val appVersion: String = "",
    val model: String = "",
)
