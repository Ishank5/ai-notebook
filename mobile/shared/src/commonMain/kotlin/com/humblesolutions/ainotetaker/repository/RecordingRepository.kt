package com.humblesolutions.ainotetaker.repository

import com.humblesolutions.ainotetaker.model.EndReason
import com.humblesolutions.ainotetaker.model.FinalizeResult
import com.humblesolutions.ainotetaker.model.GapReason
import com.humblesolutions.ainotetaker.model.LocalRecording
import com.humblesolutions.ainotetaker.model.OthersWaiting
import com.humblesolutions.ainotetaker.model.ChunkUploadTarget
import com.humblesolutions.ainotetaker.model.RecorderDevice
import com.humblesolutions.ainotetaker.model.RecordingChunk
import com.humblesolutions.ainotetaker.model.RecordingCompany
import com.humblesolutions.ainotetaker.model.RecordingGap
import com.humblesolutions.ainotetaker.model.RecordingState
import com.humblesolutions.ainotetaker.model.RecordingSummary
import com.humblesolutions.ainotetaker.model.TransferResult
import com.humblesolutions.ainotetaker.model.UploadTargets
import com.humblesolutions.ainotetaker.util.Cancellable

/**
 * The phone's record of every recording, chunk and pause.
 *
 * WRITTEN BEFORE ANYTHING TOUCHES THE NETWORK. A chunk row exists from the
 * moment its file is opened, which is what lets the next launch find — and
 * repair — the file the app was writing when it died.
 *
 * Implemented once, in `shared`, over SQLDelight, like the offline roll: the
 * queue's rules are the same on both phones, so there is one copy of them.
 */
interface RecordingStore {
    /**
     * with the company picked before recording, if any, the planned length in audio ms, and the
     * owner's name.
     */
    suspend fun create(
        id: String,
        ownerUid: String,
        startedAt: Long,
        company: RecordingCompany? = null,
        planMs: Long? = null,
        ownerName: String? = null,
    )
    suspend fun byId(id: String): LocalRecording?

    /** Recordings marked as capturing. After a crash there may be one nobody is capturing. */
    suspend fun active(): LocalRecording?
    suspend fun inState(state: RecordingState): List<LocalRecording>
    suspend fun summaries(ownerUid: String): List<RecordingSummary>

    /** This person's recordings the server has not yet confirmed, oldest first. */
    suspend fun toSync(ownerUid: String): List<LocalRecording>

    /** everybody else's recordings not yet confirmed, counted by person. */
    suspend fun othersWaiting(ownerUid: String): List<OthersWaiting>

    /** Every change to the owner's recordings or their chunks, as it lands. */
    fun observeSummaries(ownerUid: String, onChange: (List<RecordingSummary>) -> Unit): Cancellable

    suspend fun setState(id: String, state: RecordingState)

    /** Sets the state and the end together; null both to reopen a recording. */
    suspend fun finish(id: String, state: RecordingState, endedAt: Long?, reason: EndReason?)
    suspend fun heartbeat(id: String, at: Long)
    suspend fun setDetails(
        id: String,
        title: String?,
        meetingType: String?,
        location: String?,
        attendees: String?,
    )
    /** the company changed after the meeting. */
    suspend fun setCompany(id: String, company: RecordingCompany?)
    /** Keep recording moved the planned end. */
    suspend fun setStopAt(id: String, stopAtMs: Long)
    suspend fun markUploaded(id: String, at: Long)
    /** the server takes nothing more for it; [reason] stays on the row. Unsent chunks stay as they are. */
    suspend fun markClosedByServer(id: String, at: Long, reason: String)
    /** found cut off at [uptimeMs] (monotonic, since boot), or continued (null). */
    suspend fun markFound(id: String, uptimeMs: Long?)
    /** what the platform said about the process that died. */
    suspend fun setExitInfo(id: String, info: String?)
    suspend fun setLastError(id: String, key: String?)
    suspend fun uploadedBefore(at: Long): List<LocalRecording>
    suspend fun markPurged(id: String, at: Long)
    suspend fun delete(id: String)

    suspend fun nextSeq(recordingId: String): Int
    suspend fun openChunk(recordingId: String, seq: Int, path: String)
    suspend fun closeChunk(recordingId: String, seq: Int, bytes: Long, durationMs: Long, md5: String)
    suspend fun deleteChunk(recordingId: String, seq: Int)
    suspend fun chunks(recordingId: String): List<RecordingChunk>
    suspend fun openChunks(): List<RecordingChunk>
    suspend fun markChunkUploaded(recordingId: String, seq: Int)
    suspend fun markChunkPending(recordingId: String, seq: Int)
    suspend fun noteAttempt(recordingId: String, seq: Int, at: Long)
    suspend fun noteRejection(recordingId: String, seq: Int)
    /** an upload run found the file there but could not read it; the first one at [at] (epoch ms). */
    suspend fun noteUnreadable(recordingId: String, seq: Int, at: Long)

    suspend fun openGap(recordingId: String, startMs: Long, reason: GapReason)
    suspend fun closeGaps(recordingId: String, endMs: Long)
    suspend fun gaps(recordingId: String): List<RecordingGap>
}

/**
 * The two server calls: upload links for chunks, and "check and close".
 *
 * platform implementations call the CRM API on Render,
 * `POST /api/recordings/upload-urls` and `POST /api/recordings/finalize`,
 * with the callables' old payloads (TAS called two Cloud Functions).
 *
 * NEITHER THROWS FOR AN ANSWER THE SERVER GAVE. A refusal comes back as a
 * `recorder.err.*` key in the result — see [UploadTargets] for why. A throw
 * is still caught, and read as "no signal".
 */
interface RecordingUploadRepository {
    suspend fun uploadTargets(
        recording: LocalRecording,
        chunks: List<RecordingChunk>,
        device: RecorderDevice,
    ): UploadTargets

    /**
     * [seqs] are the chunks this phone has, which is not always `0…n-1`: a
     * chunk whose file was lost is dropped rather than holding the rest of
     * the meeting back forever.
     */
    suspend fun finalize(
        recording: LocalRecording,
        seqs: List<Int>,
        durationMs: Long,
        gaps: List<RecordingGap>,
    ): FinalizeResult
}

/**
 * Putting one chunk file at one signed URL.
 *
 * A PLATFORM CONCERN BECAUSE THE OS OWNS BACKGROUND TRANSFER: Android runs
 * this inside WorkManager, iOS hands long waits to a background URL session.
 * What is shared is the decision of what to upload, when, and what a refusal
 * means.
 */
interface ChunkTransfer {
    suspend fun upload(target: ChunkUploadTarget, path: String): TransferResult
}

/**
 * The recorder's folder on disk.
 *
 * EVERY PATH IS RELATIVE TO IT. iOS moves an app's container when the app is
 * updated, so an absolute path stored today can point nowhere tomorrow; the
 * queue stores `id/00003.aac` and each platform resolves it against wherever
 * its folder is now.
 *
 * Kept out of iCloud and Android backup on both platforms: a queue restored
 * onto a new phone without its audio would be a list of meetings that can
 * never upload.
 */
interface RecordingFiles {

    /** Bytes free on the volume the recorder writes to. */
    fun freeBytes(): Long

    /**
     * The whole file, or null when it does not exist. A chunk is under a megabyte.
     * or when it cannot be read just now (iOS before the first unlock after a restart); [exists] tells
     * the two apart.
     */
    suspend fun read(path: String): ByteArray?

    /** the file is there, readable or not. Nothing is ever deleted for a read that failed. */
    suspend fun exists(path: String): Boolean

    /**
     * the file's length on disk, or null when it cannot be had. Checked against the chunk's row before
     * an upload link is asked for: a link is signed for the row's size, and Storage refuses any other forever.
     */
    suspend fun size(path: String): Long?

    /**
     * Cuts the file to its first [length] bytes, forces it to disk, and
     * returns the base64 MD5 of what is left — or null when it cannot.
     */
    suspend fun seal(path: String, length: Long): String?

    suspend fun delete(path: String)

    /**
     * moves the file to `rejected/<path>` in the recorder's folder rather than deleting it: audio
     * Storage keeps refusing, or that cannot be made into whole frames, is still somebody's meeting. True when it
     * was moved, or was not there to move.
     */
    suspend fun reject(path: String): Boolean

    /** A folder — one recording's, from `RecordingPaths.folder` — and everything in it. */
    suspend fun deleteFolder(folder: String)
}
