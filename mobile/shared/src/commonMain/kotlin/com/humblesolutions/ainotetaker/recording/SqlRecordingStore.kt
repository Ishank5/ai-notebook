package com.humblesolutions.ainotetaker.recording

import app.cash.sqldelight.Query
import com.humblesolutions.ainotetaker.model.ChunkState
import com.humblesolutions.ainotetaker.model.EndReason
import com.humblesolutions.ainotetaker.model.GapReason
import com.humblesolutions.ainotetaker.model.LocalRecording
import com.humblesolutions.ainotetaker.model.OthersWaiting
import com.humblesolutions.ainotetaker.model.RecordingChunk
import com.humblesolutions.ainotetaker.model.RecordingCompany
import com.humblesolutions.ainotetaker.model.RecordingGap
import com.humblesolutions.ainotetaker.model.RecordingState
import com.humblesolutions.ainotetaker.model.RecordingSummary
import com.humblesolutions.ainotetaker.recording.db.Chunk
import com.humblesolutions.ainotetaker.recording.db.Gap
import com.humblesolutions.ainotetaker.recording.db.Recording
import com.humblesolutions.ainotetaker.recording.db.RecordingDatabase
import com.humblesolutions.ainotetaker.repository.RecordingStore
import com.humblesolutions.ainotetaker.roll.rollDispatcher
import com.humblesolutions.ainotetaker.util.Cancellable
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The recorder's queue over SQLDelight, one implementation for both phones.
 *
 * OFF THE MAIN THREAD, on the same dispatcher as the roll and for the same
 * reason: SQLite is blocking, and the recorder writes a row on every chunk and
 * every heartbeat while the screen is animating a level meter.
 */
class SqlRecordingStore(
    private val db: RecordingDatabase,
    private val dispatcher: CoroutineDispatcher = rollDispatcher(),
) : RecordingStore {

    private val q get() = db.recordingQueries

    // the company and the plan picked before recording are written with the row, in the same statement.
    override suspend fun create(
        id: String,
        ownerUid: String,
        startedAt: Long,
        company: RecordingCompany?,
        planMs: Long?,
        ownerName: String?,
    ) = write {
        q.insertRecording(
            id, ownerUid, RecordingState.RECORDING.id, startedAt,
            company?.choice?.id, company?.id, company?.name,
            planMs, planMs, ownerName,
        )
    }

    override suspend fun byId(id: String): LocalRecording? = io {
        q.recordingById(id).executeAsOneOrNull()?.toModel()
    }

    override suspend fun active(): LocalRecording? = io {
        q.activeRecording().executeAsOneOrNull()?.toModel()
    }

    override suspend fun inState(state: RecordingState): List<LocalRecording> = io {
        q.recordingsInState(state.id).executeAsList().map { it.toModel() }
    }

    override suspend fun summaries(ownerUid: String): List<RecordingSummary> = io {
        summariesNow(ownerUid)
    }

    override suspend fun toSync(ownerUid: String): List<LocalRecording> = io {
        q.recordingsToSync(ownerUid).executeAsList().map { it.toModel() }
    }

    // 
    override suspend fun othersWaiting(ownerUid: String): List<OthersWaiting> = io {
        q.waitingForOthers(ownerUid).executeAsList().map {
            OthersWaiting(it.owner_uid, it.owner_name?.takeIf { name -> name.isNotBlank() }, it.waiting.toInt())
        }
    }

    private fun summariesNow(ownerUid: String): List<RecordingSummary> {
        val totals = q.chunkTotals().executeAsList().associateBy { it.recording_id }
        return q.recordingsForOwner(ownerUid).executeAsList().map { row ->
            val t = totals[row.id]
            RecordingSummary(
                recording = row.toModel(),
                chunks = (t?.total ?: 0L).toInt(),
                uploadedChunks = (t?.uploaded ?: 0L).toInt(),
                durationMs = t?.duration_ms ?: 0L,
                bytes = t?.bytes ?: 0L,
            )
        }
    }

    /**
     * Re-reads on every write to either table.
     *
     * SQLDelight notifies a query's listeners when a statement touching its
     * tables runs through the same database object — which is why each app
     * process holds exactly one, in [RecordingSource]: the recorder service,
     * the upload worker and the screen all write through it, so the list moves
     * the moment a chunk closes or uploads.
     */
    override fun observeSummaries(
        ownerUid: String,
        onChange: (List<RecordingSummary>) -> Unit,
    ): Cancellable {
        val scope = CoroutineScope(SupervisorJob() + dispatcher)
        /*
         * ONE READER, CONFLATED. A chunk closing writes three rows in quick
         * succession; a read per write, each on its own pool thread, could
         * finish out of order and leave the screen showing the older of two
         * answers. A conflated channel read by one coroutine re-reads once
         * per burst, and always last.
         */
        val changes = Channel<Unit>(Channel.CONFLATED)
        val recordings = q.recordingsForOwner(ownerUid)
        val totals = q.chunkTotals()
        val listener = Query.Listener { changes.trySend(Unit) }
        recordings.addListener(listener)
        totals.addListener(listener)
        changes.trySend(Unit)
        scope.launch {
            for (change in changes) {
                val summaries = try {
                    summariesNow(ownerUid)
                } catch (e: Throwable) {
                    // A list that stops updating is better than a crash in
                    // the middle of a meeting.
                    println("[Recorder] could not read the recordings list: ${e.message}")
                    continue
                }
                onChange(summaries)
            }
        }
        return Cancellable {
            recordings.removeListener(listener)
            totals.removeListener(listener)
            changes.close()
            scope.cancel()
        }
    }

    override suspend fun setState(id: String, state: RecordingState) = write {
        q.setState(state.id, id)
    }

    override suspend fun finish(
        id: String,
        state: RecordingState,
        endedAt: Long?,
        reason: EndReason?,
    ) = write {
        q.finish(state.id, endedAt, reason?.id, id)
    }

    override suspend fun heartbeat(id: String, at: Long) = write { q.heartbeat(at, id) }

    override suspend fun setDetails(
        id: String,
        title: String?,
        meetingType: String?,
        location: String?,
        attendees: String?,
    ) = write {
        q.setDetails(title, meetingType, location, attendees, id)
    }

    // 
    override suspend fun setCompany(id: String, company: RecordingCompany?) = write {
        q.setCompany(company?.choice?.id, company?.id, company?.name, id)
    }

    // 
    override suspend fun setStopAt(id: String, stopAtMs: Long) = write { q.setStopAt(stopAtMs, id) }

    override suspend fun markUploaded(id: String, at: Long) = write { q.markUploaded(at, id) }

    // 
    override suspend fun markClosedByServer(id: String, at: Long, reason: String) = write {
        q.markClosedByServer(at, reason, id)
    }

    // 
    override suspend fun markFound(id: String, uptimeMs: Long?) = write { q.markFound(uptimeMs, id) }

    // 
    override suspend fun setExitInfo(id: String, info: String?) = write { q.setExitInfo(info, id) }

    override suspend fun setLastError(id: String, key: String?) = write { q.setLastError(key, id) }

    override suspend fun uploadedBefore(at: Long): List<LocalRecording> = io {
        q.uploadedBefore(at).executeAsList().map { it.toModel() }
    }

    override suspend fun markPurged(id: String, at: Long) = write { q.markPurged(at, id) }

    override suspend fun delete(id: String) = write { q.deleteRecording(id) }

    override suspend fun nextSeq(recordingId: String): Int = io {
        val max = q.maxSeq(recordingId).executeAsOneOrNull()?.max
        (max?.toInt() ?: -1) + 1
    }

    override suspend fun openChunk(recordingId: String, seq: Int, path: String) = write {
        q.insertChunk(recordingId, seq.toLong(), path)
    }

    override suspend fun closeChunk(
        recordingId: String,
        seq: Int,
        bytes: Long,
        durationMs: Long,
        md5: String,
    ) = write {
        q.closeChunk(bytes, durationMs, md5, recordingId, seq.toLong())
    }

    override suspend fun deleteChunk(recordingId: String, seq: Int) = write {
        q.deleteChunk(recordingId, seq.toLong())
    }

    override suspend fun chunks(recordingId: String): List<RecordingChunk> = io {
        q.chunksFor(recordingId).executeAsList().map { it.toModel() }
    }

    override suspend fun openChunks(): List<RecordingChunk> = io {
        q.openChunks().executeAsList().map { it.toModel() }
    }

    override suspend fun markChunkUploaded(recordingId: String, seq: Int) = write {
        q.markChunkUploaded(recordingId, seq.toLong())
    }

    override suspend fun markChunkPending(recordingId: String, seq: Int) = write {
        q.markChunkPending(recordingId, seq.toLong())
    }

    override suspend fun noteAttempt(recordingId: String, seq: Int, at: Long) = write {
        q.noteAttempt(at, recordingId, seq.toLong())
    }

    override suspend fun noteRejection(recordingId: String, seq: Int) = write {
        q.noteRejection(recordingId, seq.toLong())
    }

    override suspend fun noteUnreadable(recordingId: String, seq: Int, at: Long) = write {
        q.noteUnreadable(at, recordingId, seq.toLong())
    }

    override suspend fun openGap(recordingId: String, startMs: Long, reason: GapReason) = write {
        q.insertGap(recordingId, startMs, reason.id)
    }

    override suspend fun closeGaps(recordingId: String, endMs: Long) = write {
        q.closeOpenGaps(endMs, recordingId)
    }

    override suspend fun gaps(recordingId: String): List<RecordingGap> = io {
        q.gapsFor(recordingId).executeAsList().map { it.toModel() }
    }

    private suspend fun <T> io(block: () -> T): T = withContext(dispatcher) { block() }

    /** A write; SQLDelight's row count is not something any caller wants. */
    private suspend fun write(block: () -> Unit) = withContext(dispatcher) { block() }
}

private fun Recording.toModel() = LocalRecording(
    id = id,
    ownerUid = owner_uid,
    state = RecordingState.fromId(state),
    startedAt = started_at,
    endedAt = ended_at,
    endReason = EndReason.fromId(end_reason),
    title = title,
    meetingType = meeting_type,
    location = location,
    attendees = attendees,
    heartbeatAt = heartbeat_at,
    uploadedAt = uploaded_at,
    purgedAt = purged_at,
    lastError = last_error,
    companyChoice = company_choice,
    companyId = company_id,
    companyName = company_name,
    planMs = plan_ms,
    stopAtMs = stop_at_ms,
    foundUptimeMs = found_uptime_ms,
    exitInfo = exit_info,
    ownerName = owner_name,
)

private fun Chunk.toModel() = RecordingChunk(
    recordingId = recording_id,
    seq = seq.toInt(),
    path = path,
    bytes = bytes,
    durationMs = duration_ms,
    md5 = md5,
    state = ChunkState.fromId(state),
    attempts = attempts.toInt(),
    rejections = rejections.toInt(),
    unreadable = unreadable.toInt(),
    unreadableSince = unreadable_since,
)

private fun Gap.toModel() = RecordingGap(
    recordingId = recording_id,
    startMs = start_ms,
    endMs = end_ms,
    reason = GapReason.fromId(reason),
)
