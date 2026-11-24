package com.humblesolutions.ainotetaker.usecase

import com.humblesolutions.ainotetaker.config.RecorderConfig
import com.humblesolutions.ainotetaker.config.RecorderLimits
import com.humblesolutions.ainotetaker.model.ChunkState
import com.humblesolutions.ainotetaker.model.EndReason
import com.humblesolutions.ainotetaker.model.FinalizeResult
import com.humblesolutions.ainotetaker.model.GapReason
import com.humblesolutions.ainotetaker.model.LocalRecording
import com.humblesolutions.ainotetaker.model.OthersWaiting
import com.humblesolutions.ainotetaker.model.RecorderDevice
import com.humblesolutions.ainotetaker.model.RecorderRefused
import com.humblesolutions.ainotetaker.model.RecordingChunk
import com.humblesolutions.ainotetaker.model.RecordingCompany
import com.humblesolutions.ainotetaker.model.RecordingGap
import com.humblesolutions.ainotetaker.model.RecordingState
import com.humblesolutions.ainotetaker.model.RecordingSummary
import com.humblesolutions.ainotetaker.model.TransferResult
import com.humblesolutions.ainotetaker.model.UploadTargets
import com.humblesolutions.ainotetaker.recording.Adts
import com.humblesolutions.ainotetaker.recording.RecorderRuntime
import com.humblesolutions.ainotetaker.recording.RecordingPaths
import com.humblesolutions.ainotetaker.recording.RecordingPlans
import com.humblesolutions.ainotetaker.repository.ChunkTransfer
import com.humblesolutions.ainotetaker.repository.RecordingFiles
import com.humblesolutions.ainotetaker.repository.RecordingStatusRepository
import com.humblesolutions.ainotetaker.repository.RecordingStore
import com.humblesolutions.ainotetaker.repository.RecordingUploadRepository
import com.humblesolutions.ainotetaker.util.Cancellable
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.withLock
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.TimeSource
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/**
 * The meeting recorder: capture on the phone, upload in the background.
 *
 * THE PLATFORMS OWN THE AUDIO; THIS FILE OWNS EVERYTHING ELSE. Android's
 * encoder and iOS's converter each turn microphone samples into AAC frames and
 * write them to a file. Which file, when to start the next one, what a pause
 * or a crash leaves behind, what goes to the server and what to do when it
 * says no — all of that is decided here, once, so the two phones cannot
 * disagree about a meeting.
 *
 * WHAT THE PLATFORM ENGINE DOES, IN ORDER:
 *
 * 1. [StartRecordingUseCase] (or [ContinueRecordingUseCase]) → an id, and the
 *    audio already recorded for the clock.
 * 2. On the first frame of a chunk, [BeginChunkUseCase] → the file to write.
 *    The row exists BEFORE the file does, so a crash between the two leaves
 *    something recovery can find.
 * 3. Frames go to the file with an ADTS header each; `RecordingClock` says
 *    when a minute is up, when to warn and when to stop. Keep recording goes
 *    to the clock first, then to [ExtendRecordingUseCase].
 * 4. At a minute, a pause, or the end: [CloseChunkUseCase] with the byte
 *    count, frame count and MD5.
 * 5. [HeartbeatUseCase] every few seconds; [PauseRecordingUseCase] and
 *    [ResumeRecordingUseCase] around calls; [StopRecordingUseCase] at the end.
 *
 * Every time is epoch milliseconds from the phone, passed in, so the tests can
 * stand in the middle of a meeting without waiting for one.
 */

/** The `recorder.err.*` keys, from the server and from here. */
object RecorderErrors {
    // From `recordings.ts`.
    const val SIGNED_OUT = "recorder.err.signedOut"
    const val INACTIVE = "recorder.err.inactive"
    const val BAD_REQUEST = "recorder.err.badRequest"
    const val NOT_YOURS = "recorder.err.notYours"
    const val ALREADY_FINISHED = "recorder.err.alreadyFinished"
    const val SERVER = "recorder.err.server"
    const val NOTHING_UPLOADED = "recorder.err.nothingUploaded"
    const val TOO_LARGE = "recorder.err.tooLarge"

    /** The account signed in is not the recording's owner — a shared phone mid-switch. */
    const val ACCOUNT_CHANGED = "recorder.err.accountChanged"

    // Decided on the phone.
    const val NETWORK = "recorder.err.network"
    const val CHUNK_DROPPED = "recorder.err.chunkDropped"
    const val ALREADY_RECORDING = "recorder.err.alreadyRecording"
    const val NO_SPACE = "recorder.err.noSpace"
    const val NOT_FOUND = "recorder.err.notFound"
    const val CANNOT_CONTINUE = "recorder.err.cannotContinue"
    const val LIMIT_REACHED = "recorder.err.limitReached"
    /**
     * the chunk ceiling, a recording paused (or interrupted) hundreds of times. It stops with end
     * reason `limit` on the server, which already takes it, but the phone says why: not "3 hours reached".
     */
    const val CHUNK_LIMIT = "recorder.err.chunkLimit"
    /** the server refused a link for one chunk, over its 1 MB limit; set aside on the phone. */
    const val CHUNK_TOO_LARGE = "recorder.err.chunkTooLarge"

    // Decided by the platform recorder, which owns the microphone and the disk.
    const val MIC_PERMISSION = "recorder.err.micPermission"
    const val MIC_BUSY = "recorder.err.micBusy"
    const val START_FAILED = "recorder.err.startFailed"
    const val STORAGE = "recorder.err.storage"

    /** Every key above, so a test can prove each one reads as a sentence. */
    val ALL: List<String> = listOf(
        SIGNED_OUT, INACTIVE, BAD_REQUEST, NOT_YOURS, ALREADY_FINISHED, SERVER,
        NOTHING_UPLOADED, TOO_LARGE, ACCOUNT_CHANGED, NETWORK, CHUNK_DROPPED,
        ALREADY_RECORDING, NO_SPACE, NOT_FOUND, CANNOT_CONTINUE, LIMIT_REACHED,
        MIC_PERMISSION, MIC_BUSY, START_FAILED, STORAGE, CHUNK_TOO_LARGE, CHUNK_LIMIT,
    )

    /** Answers that will be the same however many times the call is made. */
    internal val PERMANENT = setOf(BAD_REQUEST, NOT_YOURS, TOO_LARGE)

    /**
     * Answers about the person, not the recording: nothing more can go this
     * run. The recording is not marked — it did nothing wrong, and it goes
     * the next time its owner is the one signed in.
     */
    internal val ACCOUNT = setOf(SIGNED_OUT, INACTIVE, ACCOUNT_CHANGED)
}

/** What the engine needs to start writing. */
data class RecorderStart(
    val recordingId: String,
    /** Audio already in the recording; nonzero after [ContinueRecordingUseCase]. */
    val priorMs: Long = 0L,
    /** the planned end in audio ms, for the clock; kept by a continued recording. */
    val stopAtMs: Long = RecorderConfig.DEFAULT_PLAN_MS,
)

/** The file for the chunk about to be written. */
data class ChunkStart(val seq: Int, val path: String)

private fun offsetOf(recording: LocalRecording, at: Long): Long =
    (at - recording.startedAt).coerceAtLeast(0L)

/** When a recording that is not running last showed signs of life. */
private fun diedAt(recording: LocalRecording): Long =
    recording.endedAt ?: recording.heartbeatAt ?: recording.startedAt

/**
 * Putting the queue right after the app died, shared by every use case that
 * has to know the queue is sound before it acts. Callers hold the runtime lock.
 */
internal class RecorderRepair(
    private val store: RecordingStore,
    private val files: RecordingFiles,
    private val runtime: RecorderRuntime,
) {

    /**
     * A chunk file as it is on disk, made into a closed chunk.
     *
     * A file the app was writing when it died ends in a partial frame, or a
     * header with no body; everything before that is good audio. The file is
     * cut back to its last whole frame and checksummed, so a crash costs the
     * last fraction of a second, not the minute.
     *
     * Returns false when there is nothing worth keeping, and the chunk is gone.
     */
    suspend fun repairChunk(chunk: RecordingChunk): Boolean {
        val bytes = files.read(chunk.path)
        // a file that is there but could not be read (iOS keeps files locked until the first unlock
        // after a restart, which is when the background upload may run) is left as it is for the next pass. TAS
        // took any read failure for a missing file and deleted the chunk. One that stays unreadable for a day of
        // upload runs is set aside by [SyncRecordingsUseCase], so it cannot hold its recording back forever.
        if (bytes == null && files.exists(chunk.path)) {
            println("[Recorder] chunk ${chunk.path} could not be read just now; left for later")
            return true
        }
        val prefix = if (bytes == null) Adts.Prefix(0, 0) else Adts.validPrefix(bytes)
        if (prefix.frames == 0) {
            // bytes with no whole frame in them are set aside in `rejected/`, not deleted; an empty
            // or missing file has nothing to keep.
            dropChunk(chunk, keep = bytes != null && bytes.isNotEmpty())
            return false
        }
        // NULL MEANS THE DISK WOULD NOT CO-OPERATE, NOT THAT THE AUDIO IS BAD.
        // The chunk is left as it was and the next recovery tries again.
        val md5 = files.seal(chunk.path, prefix.bytes.toLong()) ?: return true
        store.closeChunk(
            chunk.recordingId,
            chunk.seq,
            prefix.bytes.toLong(),
            Adts.durationMs(prefix.frames.toLong()),
            md5,
        )
        return true
    }

    /**
     * the chunk repaired from its file; true when its row changed (or it is gone), so a refusal was
     * about a stale row and not about Storage.
     */
    suspend fun changed(chunk: RecordingChunk): Boolean {
        repairChunk(chunk)
        val after = store.chunks(chunk.recordingId).firstOrNull { it.seq == chunk.seq } ?: return true
        return after.bytes != chunk.bytes || after.md5 != chunk.md5
    }

    /**
     * The chunk leaves the queue, so the recording is finalized without it. with [keep], its file is
     * moved to `rejected/` instead of deleted (TAS deleted every file it gave up on).
     */
    suspend fun dropChunk(chunk: RecordingChunk, keep: Boolean = false): Boolean {
        if (keep) {
            if (!files.reject(chunk.path)) return false // Not moved: left in the queue for another try.
        } else {
            files.delete(chunk.path)
        }
        store.deleteChunk(chunk.recordingId, chunk.seq)
        return true
    }

    /**
     * Repairs every file nothing in this process is writing, and interrupts their recordings.
     *
     * returns the recordings found cut off by this pass, so the platform can tell the person at once
     * (TAS only told them about the ones still waiting, and a recording found an hour after it died was finished in
     * the same pass, with no word and no Continue). The hour before an interrupted recording is sent by itself now
     * counts from when it was FOUND, on [uptimeMs], the platform's monotonic clock (ms since boot, counting sleep),
     * so a phone that was off all night, or a clock changed by hand, cannot take Continue away before anybody knew.
     */
    suspend fun recover(uptimeMs: Long): List<LocalRecording> {
        val live = runtime.liveId

        for (chunk in store.openChunks()) {
            if (chunk.recordingId != live) repairChunk(chunk)
        }

        val found = mutableListOf<LocalRecording>()
        val dead = store.inState(RecordingState.RECORDING) + store.inState(RecordingState.PAUSED)
        for (recording in dead) {
            if (recording.id == live) continue
            if (store.chunks(recording.id).isEmpty()) {
                // Died before the first frame. There is nothing to continue.
                forget(recording.id)
                continue
            }
            store.finish(
                recording.id,
                RecordingState.INTERRUPTED,
                recording.heartbeatAt ?: recording.startedAt,
                EndReason.INTERRUPTED,
            )
            store.markFound(recording.id, uptimeMs)
            store.byId(recording.id)?.let { found += it }
            println("[Recorder] ${recording.id} was cut off; waiting for Continue or Finish")
        }

        for (recording in store.inState(RecordingState.INTERRUPTED)) {
            val foundAt = recording.foundUptimeMs
            if (foundAt == null) {
                // Cut off before this version kept the time: its hour starts now.
                store.markFound(recording.id, uptimeMs)
                continue
            }
            if (RecorderClocks.since(foundAt, uptimeMs) >= RecorderConfig.INTERRUPTED_AUTO_FINISH_MS) {
                finishInterrupted(recording)
            }
        }
        return found
    }

    /**
     * Closes an interrupted recording where the audio stopped, so it can upload. [reason] is
     * [EndReason.STOPPED] when the person pressed Stop while it was waiting to be continued.
     */
    suspend fun finishInterrupted(recording: LocalRecording, reason: EndReason = EndReason.INTERRUPTED) {
        if (recording.state != RecordingState.INTERRUPTED) return
        val end = diedAt(recording)
        store.closeGaps(recording.id, offsetOf(recording, end))
        if (store.chunks(recording.id).isEmpty()) {
            forget(recording.id)
            return
        }
        store.finish(recording.id, RecordingState.STOPPED, end, reason)
    }

    /** A recording with no audio in it is not kept: there is nothing to send. */
    suspend fun forget(recordingId: String) {
        files.deleteFolder(RecordingPaths.folder(recordingId))
        store.delete(recordingId)
    }
}

/**
 * Record was pressed.
 *
 * RECOVERY FIRST. Whatever the last run left behind is put right before a new
 * recording exists, so the new one cannot be mistaken for a casualty of the
 * old crash. Any recording still waiting on Continue is finished: a new
 * meeting means nobody is going back to the old one.
 */
class StartRecordingUseCase(
    private val store: RecordingStore,
    private val files: RecordingFiles,
    private val runtime: RecorderRuntime,
) {
    private val repair = RecorderRepair(store, files, runtime)

    /**
     * [company] is what staff picked before pressing Record (null for a contributor, who is not
     * asked). It is written with the recording's first row, so even a meeting cut off in its first second knows it.
     * So is [planMs], the length picked before Record: one of [RecorderLimits.choicesMs], anything else is the
     * default.
     */
    @OptIn(ExperimentalUuidApi::class)
    @Throws(RecorderRefused::class, Throwable::class)
    suspend fun execute(
        ownerUid: String,
        now: Long,
        company: RecordingCompany? = null,
        planMs: Long? = null,
        limits: RecorderLimits = RecorderLimits.STANDARD,
        /** the person's name, kept so another account on this phone can be told whose it is. */
        ownerName: String? = null,
    ): RecorderStart = runtime.lock.withLock {
        if (ownerUid.isBlank()) throw RecorderRefused(RecorderErrors.SIGNED_OUT)
        if (runtime.liveId != null) throw RecorderRefused(RecorderErrors.ALREADY_RECORDING)
        if (files.freeBytes() < RecorderConfig.MIN_FREE_BYTES) {
            throw RecorderRefused(RecorderErrors.NO_SPACE)
        }

        // Uptime does not matter here: everything waiting is finished on the next line.
        repair.recover(uptimeMs = 0L)
        store.inState(RecordingState.INTERRUPTED).forEach { repair.finishInterrupted(it) }

        // The server checks this shape; lower case is what it stores.
        val id = Uuid.random().toString().lowercase()
        val plan = limits.planOrDefault(planMs)
        store.create(id, ownerUid, now, company, plan, ownerName?.trim()?.take(MAX_OWNER_NAME)?.ifEmpty { null })
        store.heartbeat(id, now)
        runtime.liveId = id
        RecorderStart(recordingId = id, stopAtMs = plan)
    }
}

private const val MAX_OWNER_NAME = 120

/**
 * other people's recordings on this phone that have not reached the server, by person. They go only
 * when that person signs in here again; without this nobody signed in now would ever know they were there.
 */
class OthersWaitingUseCase(private val store: RecordingStore) {
    @Throws(Throwable::class)
    suspend fun execute(ownerUid: String): List<OthersWaiting> = store.othersWaiting(ownerUid)
}

/**
 * The file for the next chunk, called on the first frame that goes into it.
 *
 * LAZY ON PURPOSE. A chunk opened at a minute boundary during a long pause
 * would be an empty file, and the server refuses empty chunks — so a chunk
 * only exists once there is audio for it.
 */
class BeginChunkUseCase(
    private val store: RecordingStore,
    private val files: RecordingFiles,
    private val runtime: RecorderRuntime,
) {
    private val repair = RecorderRepair(store, files, runtime)

    @Throws(RecorderRefused::class, Throwable::class)
    suspend fun execute(recordingId: String): ChunkStart {
        // One file at a time. If the engine asks for a new chunk without
        // closing the last, the last is sealed from what reached the disk,
        // rather than left open for recovery to mistake for a crash.
        store.chunks(recordingId)
            .filter { it.state == ChunkState.OPEN }
            .forEach { repair.repairChunk(it) }

        val seq = store.nextSeq(recordingId)
        // its own key, so the phone does not call it the 3-hour limit.
        if (seq >= RecorderConfig.MAX_CHUNKS) throw RecorderRefused(RecorderErrors.CHUNK_LIMIT)
        val path = RecordingPaths.chunk(recordingId, seq)
        store.openChunk(recordingId, seq, path)
        return ChunkStart(seq, path)
    }
}

/** A chunk file is complete and on disk: a minute is up, or capture paused or ended. */
class CloseChunkUseCase(
    private val store: RecordingStore,
    private val files: RecordingFiles,
) {
    @Throws(Throwable::class)
    suspend fun execute(
        recordingId: String,
        seq: Int,
        path: String,
        bytes: Long,
        frames: Long,
        md5: String,
        now: Long,
    ) {
        if (frames <= 0L || bytes <= 0L) {
            // Cannot happen with lazy chunks, and must not reach the server,
            // which refuses a chunk with no bytes in it.
            files.delete(path)
            store.deleteChunk(recordingId, seq)
            return
        }
        store.closeChunk(recordingId, seq, bytes, Adts.durationMs(frames), md5)
        // A closed chunk is as good a sign of life as a heartbeat, and it is
        // the moment recovery most needs to be accurate about.
        store.heartbeat(recordingId, now)
    }
}

/** Proof the recording is still running; what recovery dates a crash by. */
class HeartbeatUseCase(private val store: RecordingStore) {
    @Throws(Throwable::class)
    suspend fun execute(recordingId: String, now: Long) {
        store.heartbeat(recordingId, now)
    }
}

/**
 * Capture stopped for a while: the worker pressed Pause, or a call took the
 * microphone. The engine closes the current chunk first.
 *
 * called again while already paused, with another [reason], it ends the open gap there and opens one
 * with the new reason: a pause the person made whose microphone will not come back is now an interruption, and a
 * microphone that failed because a call took it is a call. The same reason again changes nothing.
 */
class PauseRecordingUseCase(private val store: RecordingStore) {
    @Throws(Throwable::class)
    suspend fun execute(recordingId: String, reason: GapReason, now: Long) {
        val recording = store.byId(recordingId) ?: return
        when (recording.state) {
            RecordingState.RECORDING -> {
                store.setState(recordingId, RecordingState.PAUSED)
                store.openGap(recordingId, offsetOf(recording, now), reason)
            }
            RecordingState.PAUSED -> {
                val open = store.gaps(recordingId).lastOrNull { it.endMs == null }
                if (open?.reason == reason) return
                val at = offsetOf(recording, now)
                store.closeGaps(recordingId, at)
                store.openGap(recordingId, at, reason)
            }
            else -> return
        }
        store.heartbeat(recordingId, now)
    }
}

class ResumeRecordingUseCase(private val store: RecordingStore) {
    @Throws(Throwable::class)
    suspend fun execute(recordingId: String, now: Long) {
        val recording = store.byId(recordingId) ?: return
        if (recording.state != RecordingState.PAUSED) return
        store.closeGaps(recordingId, offsetOf(recording, now))
        store.setState(recordingId, RecordingState.RECORDING)
        store.heartbeat(recordingId, now)
    }
}

/**
 * Capture is over: Stop, the 3-hour cap, the planned length left unanswered,
 * or an error the engine could not recover from. The engine closes its chunk
 * first.
 *
 * Returns true when there is audio to upload. A recording stopped before its
 * first frame is removed instead — the server would refuse it, and the list
 * would show a meeting with nothing in it.
 */
class StopRecordingUseCase(
    private val store: RecordingStore,
    private val files: RecordingFiles,
    private val runtime: RecorderRuntime,
) {
    private val repair = RecorderRepair(store, files, runtime)

    @Throws(Throwable::class)
    suspend fun execute(recordingId: String, reason: EndReason, now: Long): Boolean =
        runtime.lock.withLock {
            if (runtime.liveId == recordingId) runtime.liveId = null
            val recording = store.byId(recordingId) ?: return@withLock false
            if (!recording.state.isActive) return@withLock false

            store.chunks(recordingId)
                .filter { it.state == ChunkState.OPEN }
                .forEach { repair.repairChunk(it) }

            store.closeGaps(recordingId, offsetOf(recording, now))
            if (store.chunks(recordingId).isEmpty()) {
                repair.forget(recordingId)
                return@withLock false
            }
            store.finish(recordingId, RecordingState.STOPPED, now, reason)
            true
        }
}

/**
 * capture ended WITHOUT THE PERSON CHOOSING IT: an error the engine could not get past, or the system
 * destroying the recorder service. The recording is not stopped (TAS stopped it, so it uploaded half a meeting and
 * could not be continued): it is sealed as it is and left INTERRUPTED, to be continued, at once by a recorder that is
 * still alive or later by the person, or sent as it is an hour after [uptimeMs] (the monotonic clock) like any
 * recording found cut off.
 *
 * Returns true when there is audio to keep; a recording with none is removed, as Stop does.
 */
class InterruptRecordingUseCase(
    private val store: RecordingStore,
    private val files: RecordingFiles,
    private val runtime: RecorderRuntime,
) {
    private val repair = RecorderRepair(store, files, runtime)

    @Throws(Throwable::class)
    suspend fun execute(recordingId: String, now: Long, uptimeMs: Long): Boolean = runtime.lock.withLock {
        if (runtime.liveId == recordingId) runtime.liveId = null
        val recording = store.byId(recordingId) ?: return@withLock false
        if (!recording.state.isActive) return@withLock false

        store.chunks(recordingId)
            .filter { it.state == ChunkState.OPEN }
            .forEach { repair.repairChunk(it) }

        if (store.chunks(recordingId).isEmpty()) {
            repair.forget(recordingId)
            return@withLock false
        }
        // A pause open now stays open: Continue ends it here and marks the time in between as its own gap.
        store.finish(recordingId, RecordingState.INTERRUPTED, now, EndReason.INTERRUPTED)
        store.heartbeat(recordingId, now)
        store.markFound(recordingId, uptimeMs)
        println("[Recorder] $recordingId ended without Stop; kept for Continue")
        true
    }
}

/**
 * what a recovery pass found. [cutOff] were found cut off by this pass (tell the person now, even if
 * they are not offered Continue any more); [waiting] are every recording waiting for Continue or Finish.
 */
data class RecoveryReport(
    val cutOff: List<LocalRecording> = emptyList(),
    val waiting: List<LocalRecording> = emptyList(),
)

/**
 * The time since [since] on a monotonic clock that restarts at boot.
 *
 * A REBOOT SINCE IS COUNTED FROM THE BOOT, which is never more than the truth: the time before the restart is lost,
 * so a recording may wait a little longer than an hour, never less.
 */
object RecorderClocks {
    fun since(since: Long, uptimeMs: Long): Long = if (uptimeMs >= since) uptimeMs - since else uptimeMs
}

/**
 * Putting the queue right after the app died: at launch, and before every
 * upload run.
 *
 * returns a [RecoveryReport]: the recordings found cut off by this pass, and those waiting for
 * somebody to choose Continue or Finish. [uptimeMs] is the platform's monotonic clock (ms since boot, counting
 * sleep); see [RecorderRepair.recover].
 */
class RecoverRecordingsUseCase(
    private val store: RecordingStore,
    files: RecordingFiles,
    private val runtime: RecorderRuntime,
) {
    private val repair = RecorderRepair(store, files, runtime)

    @Throws(Throwable::class)
    suspend fun execute(uptimeMs: Long): RecoveryReport = runtime.lock.withLock {
        val cutOff = repair.recover(uptimeMs)
        RecoveryReport(cutOff = cutOff, waiting = store.inState(RecordingState.INTERRUPTED))
    }
}

/**
 * what the platform said about the process a cut-off recording died with (Android's
 * `ApplicationExitInfo`), kept with the recording so the developers can learn how phones kill it.
 */
class NoteRecordingExitUseCase(private val store: RecordingStore) {
    @Throws(Throwable::class)
    suspend fun execute(recordingId: String, info: String) {
        store.setExitInfo(recordingId, info.take(MAX_INFO))
    }

    private companion object {
        const val MAX_INFO = 500
    }
}

/**
 * Carry on recording into a meeting the app was cut off from.
 *
 * THE SAME RECORDING, NOT A NEW ONE. An admin reading it later gets one
 * meeting with a marked hole — "the phone was off from 14:02 to 14:09" — rather
 * than two halves to put together. The 3-hour cap counts the audio already in
 * it, and the plan it was started with (and any Keep recording) carries on.
 */
class ContinueRecordingUseCase(
    private val store: RecordingStore,
    private val files: RecordingFiles,
    private val runtime: RecorderRuntime,
) {
    private val repair = RecorderRepair(store, files, runtime)

    /**
     * [limits] decide the cap and the plan of a row from before plans. [gapReason] names the time the
     * recording was not running: [GapReason.KILLED] when the app died, [GapReason.INTERRUPTION] when a recorder that
     * stayed alive continues it straight after an error. [minFreeBytes] is the space it needs:
     * [RecorderConfig.MIN_FREE_BYTES_AUTO_CONTINUE] for that automatic continue.
     */
    @Throws(RecorderRefused::class, Throwable::class)
    suspend fun execute(
        recordingId: String,
        ownerUid: String,
        now: Long,
        limits: RecorderLimits = RecorderLimits.STANDARD,
        gapReason: GapReason = GapReason.KILLED,
        minFreeBytes: Long = RecorderConfig.MIN_FREE_BYTES,
    ): RecorderStart =
        runtime.lock.withLock {
            if (runtime.liveId != null) throw RecorderRefused(RecorderErrors.ALREADY_RECORDING)
            val recording = store.byId(recordingId)
                ?: throw RecorderRefused(RecorderErrors.NOT_FOUND)
            if (recording.ownerUid != ownerUid || recording.state != RecordingState.INTERRUPTED) {
                throw RecorderRefused(RecorderErrors.CANNOT_CONTINUE)
            }
            if (files.freeBytes() < minFreeBytes) {
                throw RecorderRefused(RecorderErrors.NO_SPACE)
            }

            val priorMs = store.chunks(recordingId).sumOf { it.durationMs }
            if (!RecordingPlans.canContinue(priorMs, limits)) {
                repair.finishInterrupted(recording)
                throw RecorderRefused(RecorderErrors.LIMIT_REACHED)
            }

            // A pause that was open when the app died ends there; the time the
            // app was dead is its own gap, so the reason stays honest.
            val died = offsetOf(recording, diedAt(recording))
            store.closeGaps(recordingId, died)
            store.openGap(recordingId, died, gapReason)
            store.closeGaps(recordingId, offsetOf(recording, now))
            store.finish(recordingId, RecordingState.RECORDING, null, null)
            store.markFound(recordingId, null)
            store.heartbeat(recordingId, now)
            runtime.liveId = recordingId
            // the plan as it was, under the cap. One already past its planned end is warned again on
            // the first frame, with a fresh grace window: pressing Continue is somebody who wants it to go on.
            RecorderStart(recordingId, priorMs, RecordingPlans.stopAt(recording.stopAtMs, limits))
        }
}

/**
 * Keep recording was pressed, and the clock moved the planned end
 * (`RecordingClock.extend`, which holds the rule). Written to the queue so a
 * recording continued after a crash keeps the longer plan.
 */
class ExtendRecordingUseCase(private val store: RecordingStore) {
    @Throws(Throwable::class)
    suspend fun execute(recordingId: String, stopAtMs: Long, limits: RecorderLimits = RecorderLimits.STANDARD) {
        val recording = store.byId(recordingId) ?: return
        if (!recording.state.isActive) return
        // Never shorter than the plan on file, never past the cap.
        val current = RecordingPlans.stopAt(recording.stopAtMs, limits)
        store.setStopAt(recordingId, stopAtMs.coerceIn(current, limits.capMs))
    }
}

/**
 * Finish a cut-off recording as it is, so it uploads.
 *
 * [reason] is [EndReason.STOPPED] when the person pressed Stop in the moment between an error ending
 * the recording and the recorder continuing it: the Stop is theirs, so it ends as stopped, not as cut off.
 */
class FinishInterruptedUseCase(
    private val store: RecordingStore,
    files: RecordingFiles,
    private val runtime: RecorderRuntime,
) {
    private val repair = RecorderRepair(store, files, runtime)

    @Throws(Throwable::class)
    suspend fun execute(recordingId: String, reason: EndReason = EndReason.INTERRUPTED) {
        runtime.lock.withLock {
            store.byId(recordingId)?.let { repair.finishInterrupted(it, reason) }
        }
    }
}

/**
 * Naming a meeting, during it or afterwards.
 *
 * AFTER THE UPLOAD IS FINE. The server takes new details for a finished
 * recording, so a recording already confirmed is put back one step, and the
 * next sync sends the details and nothing else.
 */
class SetRecordingDetailsUseCase(private val store: RecordingStore) {

    @Throws(Throwable::class)
    suspend fun execute(
        recordingId: String,
        title: String?,
        meetingType: String?,
        location: String?,
        attendees: String?,
    ) {
        val recording = store.byId(recordingId) ?: return
        store.setDetails(
            recordingId,
            title.clean(RecorderConfig.MAX_TITLE),
            meetingType.clean(RecorderConfig.MAX_DETAIL),
            location.clean(RecorderConfig.MAX_DETAIL),
            attendees.clean(RecorderConfig.MAX_DETAIL),
        )
        // not one deleted in the CRM: sending its details again would only "confirm" it as sent.
        if (recording.state == RecordingState.UPLOADED && recording.lastError != RecorderErrors.ALREADY_FINISHED) {
            store.setState(recordingId, RecordingState.STOPPED)
        }
    }

    /**
     * Trimmed, capped, and blank as null. Cut on a character, not a UTF-16
     * unit: half an emoji at the limit is not text, and a server has to decide
     * what to do with it.
     */
    private fun String?.clean(max: Int): String? {
        val text = this?.trim() ?: return null
        val end = if (text.length > max && text[max - 1].isHighSurrogate()) max - 1 else max
        return text.take(end).ifEmpty { null }
    }
}

/**
 * the company a meeting was with, changed afterwards from the details card. Like the details, a
 * recording already confirmed is put back one step so the next sync sends it.
 */
class SetRecordingCompanyUseCase(private val store: RecordingStore) {

    @Throws(Throwable::class)
    suspend fun execute(recordingId: String, company: RecordingCompany?) {
        val recording = store.byId(recordingId) ?: return
        store.setCompany(recordingId, company)
        // not one deleted in the CRM (see SetRecordingDetailsUseCase).
        if (recording.state == RecordingState.UPLOADED && recording.lastError != RecorderErrors.ALREADY_FINISHED) {
            store.setState(recordingId, RecordingState.STOPPED)
        }
    }
}

class ObserveRecordingsUseCase(private val store: RecordingStore) {
    fun execute(ownerUid: String, onChange: (List<RecordingSummary>) -> Unit): Cancellable =
        store.observeSummaries(ownerUid, onChange)
}

/** How an upload run ended, which decides when the platform runs the next one. */
enum class SyncStatus {
    /** Nothing left that can be sent now. */
    DONE,

    /** No signal, or the server is struggling: run again, with backoff. */
    RETRY,

    /** This person cannot upload at all — signed out or suspended. Waits for sign-in. */
    BLOCKED,
}

data class SyncOutcome(
    val status: SyncStatus,
    val uploaded: Int = 0,
    val finalized: Int = 0,
)

/**
 * Sending the queue: every closed chunk up, then every stopped recording
 * checked and closed.
 *
 * ONE PERSON'S RECORDINGS ONLY. Links are signed for the person signed in, and
 * a shared phone must never upload one worker's meeting as another's; the
 * rest wait until their owner signs in here again.
 *
 * WHILE RECORDING TOO. A live recording's closed chunks go as soon as there is
 * signal, so Stop leaves one minute to send, not 180.
 *
 * WHAT EACH FAILURE MEANS:
 * - No answer at all (no signal, a timeout): stop the run, try later.
 * - an answer that is a failure (a server error, a link Storage
 *   would not take): that recording waits for the next run, the others carry
 *   on in this one, and the run asks to be run again. TAS stopped the run, so
 *   one stuck chunk held up every recording behind it, for good.
 * - Signed out, suspended: nothing of this person's can go; stop the run.
 * - A refusal about one recording: note it on that recording, carry on with
 *   the next — one bad recording must never hold up the others.
 * - Storage refusing a file: check the file again. A file that is truly bad,
 *   or gone, is dropped, and the server is told which chunks exist.
 *   a bad file is moved to `rejected/`, never deleted.
 */
class SyncRecordingsUseCase(
    private val store: RecordingStore,
    private val uploads: RecordingUploadRepository,
    private val transfer: ChunkTransfer,
    private val files: RecordingFiles,
    private val runtime: RecorderRuntime,
) {
    private val repair = RecorderRepair(store, files, runtime)

    /** [LATER]: this recording waits for the next run; the others go on, and the run says RETRY. */
    private enum class Step { OK, SKIP, LATER, RETRY, BLOCKED }

    private var uploaded = 0
    private var finalized = 0
    /** chunks already counted as unreadable this run (one count per run each). */
    private val countedUnreadable = mutableSetOf<Pair<String, Int>>()

    /**
     * THE QUEUE IS READ AGAIN UNTIL A PASS FINDS NOTHING TO DO. A run can take
     * minutes on a weak connection, and a meeting stopped in that time — or a
     * chunk closed — would otherwise wait for the next run, which the platform
     * may not schedule for an hour: a request for another run while this one
     * is going is dropped, not queued. A recording refused this run is not
     * asked about again until the next.
     */
    @Throws(Throwable::class)
    suspend fun execute(ownerUid: String, device: RecorderDevice, now: Long): SyncOutcome =
        runtime.syncLock.withLock {
            uploaded = 0
            finalized = 0
            countedUnreadable.clear()
            if (ownerUid.isBlank()) return@withLock SyncOutcome(SyncStatus.BLOCKED)

            val refused = mutableSetOf<String>()
            var later = false
            val done = { SyncOutcome(if (later) SyncStatus.RETRY else SyncStatus.DONE, uploaded, finalized) }
            repeat(MAX_PASSES) {
                val before = uploaded + finalized
                for (recording in store.toSync(ownerUid)) {
                    if (recording.id in refused) continue
                    when (syncOne(recording, device, now)) {
                        Step.OK -> Unit
                        Step.SKIP -> refused += recording.id
                        Step.LATER -> {
                            refused += recording.id
                            later = true
                        }
                        Step.RETRY -> return@withLock SyncOutcome(SyncStatus.RETRY, uploaded, finalized)
                        Step.BLOCKED -> return@withLock SyncOutcome(SyncStatus.BLOCKED, uploaded, finalized)
                    }
                }
                if (uploaded + finalized == before) return@withLock done()
            }
            done()
        }

    private suspend fun syncOne(first: LocalRecording, device: RecorderDevice, now: Long): Step {
        // A server that keeps answering "missing" gets three rounds, not a loop.
        repeat(FINALIZE_ROUNDS) {
            val upload = uploadClosed(first, device, now)
            if (upload != Step.OK) return upload

            val recording = store.byId(first.id) ?: return Step.OK
            // Only a stopped recording is closed on the server; a live or
            // interrupted one may still grow.
            if (recording.state != RecordingState.STOPPED) return Step.OK

            val chunks = store.chunks(recording.id)
            if (chunks.isEmpty()) {
                repair.forget(recording.id)
                return Step.OK
            }
            // An open chunk on a stopped recording is one recovery could not
            // seal yet. It is audio; wait for it.
            // but not forever. Tried again here; one sealed now goes in the next round, and one whose
            // file is there but cannot be read (or sealed) for a day of runs is set aside, so the rest is finalized.
            val open = chunks.filter { it.state == ChunkState.OPEN }
            if (open.isNotEmpty()) {
                var moved = false
                for (chunk in open) {
                    repair.repairChunk(chunk)
                    val after = store.chunks(chunk.recordingId).firstOrNull { it.seq == chunk.seq }
                    if (after == null || after.state != ChunkState.OPEN) {
                        moved = true
                    } else if (files.exists(after.path) && unreadableTooLong(after, now)) {
                        moved = true
                    }
                }
                if (!moved) return Step.OK
                return@repeat
            }
            if (chunks.any { it.state != ChunkState.UPLOADED }) return Step.OK

            val result = try {
                uploads.finalize(
                    recording,
                    chunks.map { it.seq },
                    chunks.sumOf { it.durationMs },
                    sendable(store.gaps(recording.id)),
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                FinalizeResult(errorKey = RecorderErrors.NETWORK)
            }

            when {
                result.ok -> {
                    store.markUploaded(recording.id, now)
                    finalized++
                    println("[Recorder] ${recording.id} confirmed by the server")
                    return Step.OK
                }
                // The server has no record of it — deleted there, or never
                // created. Everything goes again; the first link recreates it.
                result.errorKey == RecorderErrors.NOTHING_UPLOADED ->
                    chunks.forEach { store.markChunkPending(it.recordingId, it.seq) }
                result.errorKey != null -> return refused(recording.id, result.errorKey)
                else -> result.missing.forEach { store.markChunkPending(recording.id, it) }
            }
        }
        return Step.OK
    }

    /** Every closed chunk of one recording, up. */
    private suspend fun uploadClosed(
        recording: LocalRecording,
        device: RecorderDevice,
        now: Long,
    ): Step {
        // chunks repaired in this call. One that still does not match after a repair (a disk that
        // would not co-operate) makes the recording wait for the next run, never a loop.
        val repaired = mutableSetOf<Int>()
        while (true) {
            val pending = store.chunks(recording.id)
                .filter { it.state == ChunkState.CLOSED && it.md5 != null }
            if (pending.isEmpty()) return Step.OK

            val batch = pending.take(RecorderConfig.UPLOAD_BATCH)
            // a link is signed for the row's size. A row that no longer matches its file (a write
            // that failed halfway) would be refused by Storage forever, so it is measured again and repaired first.
            var remeasured = false
            for (chunk in batch) {
                val onDisk = files.size(chunk.path) ?: continue
                if (onDisk != chunk.bytes) {
                    if (!repaired.add(chunk.seq)) return Step.LATER
                    println("[Recorder] chunk ${chunk.path} is $onDisk bytes on disk, ${chunk.bytes} in its row; repaired")
                    repair.repairChunk(chunk)
                    remeasured = true
                }
            }
            if (remeasured) continue
            val signedAt = TimeSource.Monotonic.markNow()
            val answer = try {
                uploads.uploadTargets(recording, batch, device)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                UploadTargets(errorKey = RecorderErrors.NETWORK)
            }

            val errorKey = answer.errorKey
            if (errorKey == RecorderErrors.ALREADY_FINISHED) {
                /*
                 * the server will take nothing more: the recording was deleted in the CRM (or this
                 * phone's own finalize closed it). TAS counted these chunks as sent so it could be "confirmed with what
                 * arrived", which deleted audio that never left the phone (a recording processed early on the website
                 * lost its last minutes that way). Now nothing is marked sent unless it was sent: a stopped recording
                 * is closed with the reason kept ("Deleted in the CRM", not "Sent"), and its files stay for
                 * [RecorderConfig.RETAIN_CLOSED_BY_SERVER_MS]. One still recording or waiting for Continue keeps the
                 * reason and is asked again next run.
                 */
                if (store.byId(recording.id)?.state == RecordingState.STOPPED) {
                    store.markClosedByServer(recording.id, now, errorKey)
                    println("[Recorder] ${recording.id} takes no more uploads (deleted by an admin); kept on the phone")
                    return Step.OK
                }
                store.setLastError(recording.id, errorKey)
                return Step.SKIP
            }
            if (errorKey != null) return refused(recording.id, errorKey)

            var progress = false
            // chunks the server refused a link for (over its size limit) can never go. Set aside in
            // `rejected/`, they no longer hold the recording back, and it is finalized without them.
            for (seq in answer.refusedSeqs) {
                val chunk = batch.firstOrNull { it.seq == seq } ?: continue
                println("[Recorder] chunk ${chunk.path} refused by the server as too large; set aside")
                if (!repair.dropChunk(chunk, keep = true)) return Step.LATER
                store.setLastError(recording.id, RecorderErrors.CHUNK_TOO_LARGE)
                progress = true
            }

            val targets = answer.targets.associateBy { it.seq }
            for (chunk in batch) {
                if (signedAt.elapsedNow() > RecorderConfig.URL_REFRESH_MS.milliseconds) break
                val target = targets[chunk.seq] ?: continue

                store.noteAttempt(chunk.recordingId, chunk.seq, now)
                val result = try {
                    transfer.upload(target, chunk.path)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    TransferResult.RETRY
                }

                when (result) {
                    TransferResult.UPLOADED -> {
                        store.markChunkUploaded(chunk.recordingId, chunk.seq)
                        uploaded++
                        progress = true
                    }
                    TransferResult.RETRY -> return Step.RETRY
                    // Storage answered with a failure: this recording waits, the others go on.
                    TransferResult.FAILED -> {
                        store.setLastError(recording.id, RecorderErrors.SERVER)
                        return Step.LATER
                    }
                    // only a file that is truly not there; one that could not be opened just now waits,
                    // and one that has not opened for a day of runs is set aside.
                    TransferResult.MISSING -> if (files.exists(chunk.path)) {
                        if (unreadableTooLong(chunk, now)) progress = true else return Step.LATER
                    } else {
                        println("[Recorder] chunk ${chunk.path} is gone from the phone; dropped")
                        repair.dropChunk(chunk)
                        store.setLastError(recording.id, RecorderErrors.CHUNK_DROPPED)
                        progress = true
                    }
                    // the signature refused: measured again. A file that had changed under its row is
                    // repaired and asked about again with a fresh link; one that matches is the server's problem.
                    TransferResult.MISMATCH -> {
                        if (repaired.add(chunk.seq) && repair.changed(chunk)) {
                            progress = true
                        } else {
                            store.setLastError(recording.id, RecorderErrors.SERVER)
                            return Step.LATER
                        }
                    }
                    TransferResult.REJECTED -> {
                        store.noteRejection(chunk.recordingId, chunk.seq)
                        // Check the file again: the row may describe bytes the
                        // disk no longer holds. Repair re-reads, re-cuts and
                        // re-hashes, and drops a file with no audio left.
                        val kept = repair.repairChunk(chunk)
                        if (!kept || chunk.rejections + 1 >= RecorderConfig.MAX_REJECTIONS) {
                            // set aside in `rejected/`, not deleted.
                            if (kept && !repair.dropChunk(chunk, keep = true)) return Step.LATER
                            println("[Recorder] chunk ${chunk.path} refused by Storage; set aside")
                            store.setLastError(recording.id, RecorderErrors.CHUNK_DROPPED)
                            progress = true
                        } else {
                            // an answer, so the others still go; this one is tried next run.
                            return Step.LATER
                        }
                    }
                }
            }
            // Links that went unused, or a server that returned none: rather
            // than ask again in a tight loop, leave it for the next run.
            // and only this recording; the others still go.
            if (!progress) return Step.LATER
        }
    }

    /**
     * a chunk whose file is there but could not be read (or sealed) on this run. Counted once a run;
     * after [RecorderConfig.UNREADABLE_RUNS] runs over at least [RecorderConfig.UNREADABLE_FOR_MS] the file is moved
     * to `rejected/` (never deleted) and the chunk dropped, and true is returned. A day at least, so iOS keeping files
     * locked until the first unlock after a restart never costs a chunk.
     */
    private suspend fun unreadableTooLong(chunk: RecordingChunk, now: Long): Boolean {
        if (!countedUnreadable.add(chunk.recordingId to chunk.seq)) return false
        store.noteUnreadable(chunk.recordingId, chunk.seq, now)
        val after = store.chunks(chunk.recordingId).firstOrNull { it.seq == chunk.seq } ?: return false
        val since = after.unreadableSince ?: now
        if (after.unreadable < RecorderConfig.UNREADABLE_RUNS || now - since < RecorderConfig.UNREADABLE_FOR_MS) {
            println("[Recorder] chunk ${chunk.path} could not be read (${after.unreadable} runs); left for later")
            return false
        }
        if (!repair.dropChunk(after, keep = true)) return false
        println("[Recorder] chunk ${chunk.path} could not be read for ${after.unreadable} runs over a day; set aside")
        store.setLastError(chunk.recordingId, RecorderErrors.CHUNK_DROPPED)
        return true
    }

    private suspend fun refused(recordingId: String, key: String): Step = when (key) {
        // only no answer at all ends the run.
        RecorderErrors.NETWORK -> Step.RETRY
        in RecorderErrors.ACCOUNT -> Step.BLOCKED
        in RecorderErrors.PERMANENT -> {
            println("[Recorder] $recordingId refused by the server: $key")
            store.setLastError(recordingId, key)
            Step.SKIP
        }
        // any other answer (a server error, something new): this recording waits for the next run,
        // the others go on. TAS stopped the run here.
        else -> {
            println("[Recorder] $recordingId not taken this time: $key")
            store.setLastError(recordingId, key)
            Step.LATER
        }
    }

    /**
     * Gaps as the server accepts them: inside a day, never ending before they
     * start, at most [RecorderConfig.MAX_GAPS]. A phone whose clock was changed
     * mid-meeting can produce any of those, and a refused finalize would hold
     * the whole recording back over a label.
     */
    private fun sendable(gaps: List<RecordingGap>): List<RecordingGap> =
        merged(gaps.sortedBy { it.startMs }, RecorderConfig.MAX_GAPS).map { gap ->
            val start = gap.startMs.coerceIn(0L, DAY_MS)
            gap.copy(startMs = start, endMs = gap.endMs?.coerceIn(start, DAY_MS))
        }

    internal companion object {
        /**
         * at most [max] gaps, by joining the two closest neighbours until few enough are left. TAS
         * sent the first 200 and dropped the rest, so the end of a meeting with a flapping microphone had no gaps at
         * all. Every second that was a gap stays inside one; a join keeps its reason when both agree, and is an
         * interruption otherwise.
         */
        fun merged(sorted: List<RecordingGap>, max: Int): List<RecordingGap> {
            if (sorted.size <= max) return sorted
            val gaps = sorted.toMutableList()
            while (gaps.size > max) {
                var best = 0
                var bestSpace = Long.MAX_VALUE
                for (i in 0 until gaps.size - 1) {
                    val space = gaps[i + 1].startMs - (gaps[i].endMs ?: gaps[i + 1].startMs)
                    if (space < bestSpace) {
                        bestSpace = space
                        best = i
                    }
                }
                val a = gaps[best]
                val b = gaps[best + 1]
                val end = if (a.endMs == null || b.endMs == null) null else maxOf(a.endMs, b.endMs)
                gaps[best] = a.copy(
                    endMs = end,
                    reason = if (a.reason == b.reason) a.reason else GapReason.INTERRUPTION,
                )
                gaps.removeAt(best + 1)
            }
            return gaps
        }

        const val FINALIZE_ROUNDS = 3
        const val MAX_PASSES = 4
        const val DAY_MS = 24 * 60 * 60 * 1000L
    }
}

/**
 * Freeing the phone's copy of recordings the server confirmed days ago.
 *
 * THE ROW STAYS. The worker's list keeps its history; only the audio goes, and
 * only after [RecorderConfig.RETAIN_AFTER_UPLOAD_MS] as a safety net in case
 * something is found wrong with the server's copy.
 */
class PurgeUploadedRecordingsUseCase(
    private val store: RecordingStore,
    private val files: RecordingFiles,
) {
    @Throws(Throwable::class)
    suspend fun execute(now: Long): Int {
        // one the server closed without taking everything (deleted by an admin) keeps its audio 30 days.
        val old = store.uploadedBefore(now - RecorderConfig.RETAIN_AFTER_UPLOAD_MS).filter {
            it.lastError != RecorderErrors.ALREADY_FINISHED ||
                (it.uploadedAt ?: 0L) < now - RecorderConfig.RETAIN_CLOSED_BY_SERVER_MS
        }
        for (recording in old) {
            files.deleteFolder(RecordingPaths.folder(recording.id))
            store.markPurged(recording.id, now)
        }
        return old.size
    }
}

/**
 * Which of the person's sent recordings the server has finished summarising —
 * "Summary ready" beside a meeting, where it used to stop at "Sent".
 *
 * ONLY RECENT ONES ARE ASKED ABOUT. A list row stays for good once the audio
 * is purged, and asking about every meeting ever sent on each visit would
 * grow without end; a meeting sent more than [RECENT_MS] ago keeps saying
 * "Sent", which is still true.
 *
 * NEVER FAILS THE SCREEN. No signal, or a server that cannot answer, is an
 * empty answer: the rows go on saying "Sent".
 */
class SummariesReadyUseCase(private val statuses: RecordingStatusRepository) {

    @Throws(Throwable::class)
    suspend fun execute(recordings: List<RecordingSummary>, known: Set<String>, now: Long): Set<String> =
        checkStatuses(recordings, known, now).ready

    /** The ready ones, and whether any asked about is still being processed, so the screen can ask again soon. */
    @Throws(Throwable::class)
    suspend fun checkStatuses(recordings: List<RecordingSummary>, known: Set<String>, now: Long): SummaryCheck {
        val ids = recordings
            .map { it.recording }
            // one deleted in the CRM will never have a summary.
            .filter { it.state == RecordingState.UPLOADED && it.id !in known && it.lastError != RecorderErrors.ALREADY_FINISHED }
            .filter { (it.uploadedAt ?: 0L) >= now - RECENT_MS }
            .sortedByDescending { it.uploadedAt ?: 0L }
            .take(MAX_ASKED)
            .map { it.id }
        if (ids.isEmpty()) return SummaryCheck()
        val answers = try {
            statuses.serverStatuses(ids)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            return SummaryCheck()
        }
        val asked = ids.toSet()
        return SummaryCheck(
            ready = answers.filterValues { it == READY }.keys.intersect(asked),
            working = answers.any { (id, status) -> id in asked && status in WORKING },
        )
    }

    companion object {
        const val RECENT_MS = 14 * 24 * 60 * 60 * 1000L
        const val MAX_ASKED = 30
        private const val READY = "ready"
        private val WORKING = setOf("uploading", "uploaded", "processing")
    }
}

/** One look at the server: which recordings now have a summary, and whether any is still being processed. */
data class SummaryCheck(val ready: Set<String> = emptySet(), val working: Boolean = false)
