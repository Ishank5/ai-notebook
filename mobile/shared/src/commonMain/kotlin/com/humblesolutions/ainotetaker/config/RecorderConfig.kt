package com.humblesolutions.ainotetaker.config

/**
 * The recorder's numbers, in one place, identical on both phones.
 *
 * Every value here is either a promise made in the plan or a limit the server
 * checks, so changing one is a product decision, not a tweak.
 */
object RecorderConfig {

    /**
     * 16 kHz mono AAC-LC at 32 kbps: about 14 MB an hour. Speech models are
     * trained at 16 kHz and resample to it anyway, and a worker on a prepaid
     * plan uploads every byte of it.
     */
    const val SAMPLE_RATE = 16_000
    const val CHANNELS = 1
    const val BIT_RATE = 32_000

    /** One AAC frame is always 1024 samples: 64 ms at 16 kHz. */
    const val SAMPLES_PER_FRAME = 1024

    /**
     * A new file every minute. Small enough that a dropped upload on a weak
     * connection retries ~240 KB rather than a meeting, large enough that a
     * 3-hour recording is 180 objects rather than thousands.
     */
    const val CHUNK_MS = 60_000L

    /**
     * How often the open file is forced to disk. This is the whole loss window
     * on a power cut, which is why it is small.
     */
    const val FLUSH_MS = 2_000L

    /**
     * nothing but exact zeros from the microphone for this long, while recording, and the person is
     * told it may be in use by another app. It goes on recording: a quiet room can be exact zeros on some phones.
     */
    const val SILENT_ALERT_MS = 30_000L

    /** Heartbeats prove a recording is still live; recovery trusts nothing else. */
    const val HEARTBEAT_MS = 10_000L

    /** A recording marked live with no heartbeat for this long was killed. */
    const val STALE_HEARTBEAT_MS = 45_000L

    /**
     * the hard stop is 3 hours of audio (TAS stopped at 1 hour
     * 30 minutes). Nothing records past it, however the plan was extended.
     */
    const val MAX_DURATION_MS = 180 * 60_000L

    /*
     * TAS warned at 80 and 89 minutes (WARN_AT_MS). A recording
     * now has a planned length instead, picked before Record, with one warning
     * when it is reached and a grace window to answer it. See RecorderLimits
     * and RecordingClock.
     */

    /** the lengths offered before Record: 1 h 30 m, 2 h, 2 h 30 m and 3 h. */
    val PLAN_CHOICES_MS: List<Long> = listOf(90 * 60_000L, 120 * 60_000L, 150 * 60_000L, 180 * 60_000L)

    /** what Instant start and an untouched length row use. */
    const val DEFAULT_PLAN_MS = 90 * 60_000L

    /** Keep recording moves the planned end this far, never past [MAX_DURATION_MS]. */
    const val EXTEND_BY_MS = 90 * 60_000L

    /**
     * how long a recording carries on after the warning before
     * it stops by itself, and how early the warning comes before the 3-hour cap.
     */
    const val GRACE_MS = 5 * 60_000L

    /** A 3-hour recording is ~42 MB; this leaves room for a queue behind it. */
    const val MIN_FREE_BYTES = 100L * 1024 * 1024

    /**
     * the floor when the recorder continues a recording itself straight after an error. The meeting is
     * already under way and nobody asked for a new one; 20 MB is over an hour of audio, and the recorder says when the
     * phone is nearly full. With [MIN_FREE_BYTES] here, a phone between 20 and 100 MB free cut the meeting off.
     */
    const val MIN_FREE_BYTES_AUTO_CONTINUE = 20L * 1024 * 1024

    /** Matches the server's per-call ceiling in `recordingUploadUrls`. */
    const val UPLOAD_BATCH = 20

    /**
     * The server's ceiling on chunk numbers (`MAX_CHUNKS` in recordings.js).
     * Three hours is 180 chunks, but every pause closes one early, so this is
     * only reached by a recording paused hundreds of times, which then stops
     * as if it had hit the time limit.
     *
     * 1000 (TAS: 400), so a microphone that keeps dropping out
     * (each drop closes a chunk) cannot stop a meeting as "limit": that would
     * take a drop every eleven seconds for three hours. The server must accept
     * chunk numbers 0 to 999 in upload-urls and up to 1000 in a finalize.
     */
    const val MAX_CHUNKS = 1000

    /**
     * The server keeps at most this many pauses (`MAX_GAPS`). never a reason to stop: the phone keeps
     * every gap, and merges the closest ones when sending more than this (see `SyncRecordingsUseCase`).
     */
    const val MAX_GAPS = 200

    /** Longest title and detail fields the server keeps (`text()` limits). */
    const val MAX_TITLE = 200
    const val MAX_DETAIL = 500

    /**
     * Upload links live fifteen minutes on the server. A batch still going
     * after ten asks for fresh ones rather than risk a refusal on a crawling
     * connection that would be counted against the chunk.
     */
    const val URL_REFRESH_MS = 10 * 60_000L

    /**
     * Storage refusing the same file this many times means the file can never
     * upload. It is dropped, so the rest of the meeting is not held back
     * behind one bad minute forever.
     */
    const val MAX_REJECTIONS = 5

    /**
     * a chunk whose file is there but cannot be read (or sealed) is set aside in `rejected/` after this
     * many upload runs over at least [UNREADABLE_FOR_MS], so it no longer holds its recording back forever. A day at
     * least, so iOS keeping files locked until the first unlock after a restart never costs a chunk.
     */
    const val UNREADABLE_RUNS = 5
    const val UNREADABLE_FOR_MS = 24 * 60 * 60_000L

    /**
     * An interrupted recording nobody decides about is finished automatically
     * after this long, so its audio still reaches the server.
     */
    const val INTERRUPTED_AUTO_FINISH_MS = 60 * 60_000L

    /**
     * How long a verified recording's audio stays on the phone as a safety net
     * after the server has confirmed every chunk.
     */
    const val RETAIN_AFTER_UPLOAD_MS = 3L * 24 * 60 * 60_000L

    /**
     * a recording the server would take nothing more for (deleted in the CRM) keeps its audio this
     * long, unsent chunks and all, in case the deletion was a mistake.
     */
    const val RETAIN_CLOSED_BY_SERVER_MS = 30L * 24 * 60 * 60_000L

    /** Duration of one AAC frame in milliseconds, exactly. */
    val FRAME_MS: Double get() = SAMPLES_PER_FRAME * 1000.0 / SAMPLE_RATE
}
