package com.humblesolutions.ainotetaker.recording

import app.cash.sqldelight.db.SqlDriver
import com.humblesolutions.ainotetaker.recording.db.RecordingDatabase
import com.humblesolutions.ainotetaker.repository.RecordingStore
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Opens the recorder's queue, `recordings.db`.
 *
 * CONSTRUCTED BY THE PLATFORM, like [com.humblesolutions.ainotetaker.roll.RollDriverFactory]:
 * Android needs a `Context` and iOS needs nothing.
 *
 * UNLIKE THE ROLL, FAILURE IS AN ERROR. The roll can fall back to Firestore;
 * the recorder has nowhere to fall back to, and recording without the queue
 * would be recording audio nothing knows how to find after a crash. So this
 * throws, and the recorder refuses to start.
 */
expect class RecordingDriverFactory {
    fun createDriver(): SqlDriver
}

/** Where the recorder's files live, relative to its folder. */
object RecordingPaths {

    /** The folder holding one recording's chunks. */
    fun folder(recordingId: String): String = recordingId

    /** `id/00007.aac` — padded like the server's object names, so both sort in order. */
    fun chunk(recordingId: String, seq: Int): String =
        "$recordingId/${seq.toString().padStart(5, '0')}.aac"
}

/**
 * What this process is doing with the microphone.
 *
 * THE ONLY WAY TO TELL A LIVE RECORDING FROM A DEAD ONE. After a crash the
 * queue still says `recording`, and so does a recording that is running
 * perfectly; the difference is whether anything in this process is writing
 * it. Recovery skips the live one, and repairs and interrupts every other.
 *
 * ONE PER PROCESS, in [RecordingSource]. On Android the recorder service, the
 * upload worker and the screen share a process, so they share this.
 */
class RecorderRuntime {

    /** Start, stop, continue and recovery take turns on this. */
    internal val lock = Mutex()

    /** Two syncs at once would upload every chunk twice. */
    internal val syncLock = Mutex()

    /** Written only while holding [lock]. */
    internal var liveId: String? = null

    /** The recording this process is capturing, if any. */
    val liveRecordingId: String? get() = liveId
}

/**
 * The recorder's queue, opened once per process.
 *
 * A SINGLETON FOR THE REASON THE ROLL IS ONE, and one more. SQLDelight tells a
 * query's listeners about writes made through the same database object, so the
 * list on screen moves when the recorder service closes a chunk or the upload
 * worker finishes one — but only if all three hold the same object.
 */
object RecordingSource {

    val runtime = RecorderRuntime()

    private val opening = Mutex()
    private var store: SqlRecordingStore? = null

    /**
     * The queue, opened on first use.
     *
     * LAZY, NOT AT STARTUP. On Android the recorder service and the upload
     * worker can each start the process without an activity, so there is no
     * single place that always runs first. Whoever arrives first opens it.
     *
     * Throws when the database cannot be opened — a full disk, most likely.
     */
    @Throws(Throwable::class)
    suspend fun store(factory: RecordingDriverFactory): RecordingStore = opening.withLock {
        store ?: SqlRecordingStore(RecordingDatabase(factory.createDriver())).also {
            store = it
            println("[Recorder] queue opened")
        }
    }
}
