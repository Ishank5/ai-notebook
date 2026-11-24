package com.humblesolutions.ainotetaker

import com.humblesolutions.ainotetaker.config.RecorderConfig
import com.humblesolutions.ainotetaker.config.RecorderLimits
import com.humblesolutions.ainotetaker.model.ChunkState
import com.humblesolutions.ainotetaker.model.ChunkUploadTarget
import com.humblesolutions.ainotetaker.model.EndReason
import com.humblesolutions.ainotetaker.model.FinalizeResult
import com.humblesolutions.ainotetaker.model.GapReason
import com.humblesolutions.ainotetaker.model.LocalRecording
import com.humblesolutions.ainotetaker.model.OthersWaiting
import com.humblesolutions.ainotetaker.i18n.Language
import com.humblesolutions.ainotetaker.i18n.Strings
import com.humblesolutions.ainotetaker.model.RecorderDevice
import com.humblesolutions.ainotetaker.model.RecorderRefused
import com.humblesolutions.ainotetaker.model.RecordingChunk
import com.humblesolutions.ainotetaker.model.RecordingGap
import com.humblesolutions.ainotetaker.model.RecordingState
import com.humblesolutions.ainotetaker.model.RecordingSummary
import com.humblesolutions.ainotetaker.model.TransferResult
import com.humblesolutions.ainotetaker.model.UploadTargets
import com.humblesolutions.ainotetaker.recording.Adts
import com.humblesolutions.ainotetaker.recording.RecorderRuntime
import com.humblesolutions.ainotetaker.recording.SqlRecordingStore
import com.humblesolutions.ainotetaker.recording.db.RecordingDatabase
import com.humblesolutions.ainotetaker.repository.ChunkTransfer
import com.humblesolutions.ainotetaker.repository.RecordingFiles
import com.humblesolutions.ainotetaker.repository.RecordingUploadRepository
import com.humblesolutions.ainotetaker.usecase.BeginChunkUseCase
import com.humblesolutions.ainotetaker.usecase.CloseChunkUseCase
import com.humblesolutions.ainotetaker.usecase.ContinueRecordingUseCase
import com.humblesolutions.ainotetaker.usecase.ExtendRecordingUseCase
import com.humblesolutions.ainotetaker.usecase.FinishInterruptedUseCase
import com.humblesolutions.ainotetaker.usecase.HeartbeatUseCase
import com.humblesolutions.ainotetaker.usecase.OthersWaitingUseCase
import com.humblesolutions.ainotetaker.usecase.InterruptRecordingUseCase
import com.humblesolutions.ainotetaker.usecase.NoteRecordingExitUseCase
import com.humblesolutions.ainotetaker.usecase.ObserveRecordingsUseCase
import com.humblesolutions.ainotetaker.usecase.PauseRecordingUseCase
import com.humblesolutions.ainotetaker.usecase.PurgeUploadedRecordingsUseCase
import com.humblesolutions.ainotetaker.usecase.RecorderErrors
import com.humblesolutions.ainotetaker.usecase.RecoverRecordingsUseCase
import com.humblesolutions.ainotetaker.usecase.ResumeRecordingUseCase
import com.humblesolutions.ainotetaker.usecase.SetRecordingDetailsUseCase
import com.humblesolutions.ainotetaker.usecase.StartRecordingUseCase
import com.humblesolutions.ainotetaker.usecase.StopRecordingUseCase
import com.humblesolutions.ainotetaker.usecase.SyncRecordingsUseCase
import com.humblesolutions.ainotetaker.usecase.SyncStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The recorder's queue, end to end: capture, crash, recovery and upload,
 * against a real SQLite database and fakes for the disk and the network.
 *
 * A "crash" here is a new [RecorderRuntime] over the same database and files —
 * which is exactly what a new process sees: the queue on disk says a recording
 * is running, and nothing in memory is running it.
 */
class RecorderTest {

    private val t0 = 1_760_000_000_000L

    /** the phone's monotonic clock (ms since boot, counting sleep), [ms] into the test. */
    private fun up(ms: Long) = 1_000_000L + ms
    private fun up(ms: Int) = up(ms.toLong())
    private val owner = "u1"
    private val device = RecorderDevice("android", "4.0", "test")

    /** The phone's disk. */
    private class FakeFiles : RecordingFiles {
        val files = mutableMapOf<String, ByteArray>()
        var free = Long.MAX_VALUE

        override fun freeBytes() = free
        /** files that are there but cannot be read just now (iOS before the first unlock). */
        val locked = mutableSetOf<String>()

        override suspend fun read(path: String) = if (path in locked) null else files[path]
        override suspend fun exists(path: String) = path in files
        override suspend fun size(path: String) = files[path]?.size?.toLong()
        /** files the disk will not let be cut or hashed. */
        val sealFails = mutableSetOf<String>()

        override suspend fun seal(path: String, length: Long): String? {
            if (path in sealFails) return null
            val bytes = files[path] ?: return null
            val cut = bytes.copyOf(length.toInt())
            files[path] = cut
            return "md5-${cut.size}"
        }
        override suspend fun delete(path: String) {
            files.remove(path)
        }
        override suspend fun reject(path: String): Boolean {
            files.remove(path)?.let { files["rejected/$path"] = it }
            return true
        }
        override suspend fun deleteFolder(folder: String) {
            files.keys.removeAll { it.startsWith("$folder/") }
        }
    }

    /** Cloud Storage: which chunk numbers each recording has. */
    private class Bucket {
        val chunks = mutableMapOf<String, MutableSet<Int>>()
        fun has(recordingId: String) = chunks[recordingId].orEmpty()
    }

    private data class Finalized(
        val recordingId: String,
        val seqs: List<Int>,
        val durationMs: Long,
        val gaps: List<RecordingGap>,
        val title: String?,
    )

    /** The two Cloud Functions, behaving as `recordings.ts` does. */
    private class FakeServer(private val bucket: Bucket) : RecordingUploadRepository {
        val refuseLinks = mutableMapOf<String, String>()
        var refuseAllLinks: String? = null
        /** chunks the server gives no link for, as over its size limit. */
        val tooLarge = mutableSetOf<Int>()

        /** Who the calls authenticate as; null means whoever owns the recording. */
        var signedIn: String? = null
        val linkCalls = mutableMapOf<String, Int>()

        /** Recordings the server has marked uploaded; they take details and nothing else. */
        val closed = mutableSetOf<String>()
        val finalized = mutableListOf<Finalized>()

        override suspend fun uploadTargets(
            recording: LocalRecording,
            chunks: List<RecordingChunk>,
            device: RecorderDevice,
        ): UploadTargets {
            linkCalls[recording.id] = (linkCalls[recording.id] ?: 0) + 1
            signedIn?.let { if (it != recording.ownerUid) return UploadTargets(errorKey = RecorderErrors.ACCOUNT_CHANGED) }
            (refuseAllLinks ?: refuseLinks[recording.id])?.let { return UploadTargets(errorKey = it) }
            if (recording.id in closed) return UploadTargets(errorKey = RecorderErrors.ALREADY_FINISHED)
            // signed for the row's size, as R2 links are.
            return UploadTargets(
                chunks.filter { it.seq !in tooLarge }.map {
                    ChunkUploadTarget(it.seq, "https://up/${recording.id}/${it.seq}", mapOf("Content-Length" to "${it.bytes}"))
                },
                refusedSeqs = chunks.map { it.seq }.filter { it in tooLarge },
            )
        }

        override suspend fun finalize(
            recording: LocalRecording,
            seqs: List<Int>,
            durationMs: Long,
            gaps: List<RecordingGap>,
        ): FinalizeResult {
            if (recording.id !in closed) {
                val missing = seqs.filter { it !in bucket.has(recording.id) }
                if (missing.isNotEmpty()) return FinalizeResult(ok = false, missing = missing)
                closed += recording.id
            }
            finalized += Finalized(recording.id, seqs, durationMs, gaps, recording.title)
            return FinalizeResult(ok = true)
        }
    }

    /** The PUT itself. Scripted answers first, then success. */
    private class FakeTransfer(private val bucket: Bucket, private val disk: FakeFiles) : ChunkTransfer {
        val script = ArrayDeque<TransferResult>()

        /** Answer 200 for these, but never store them — a write the bucket lost. */
        val lose = mutableSetOf<Int>()

        /** Runs before each upload: somebody pressing Stop mid-run, say. */
        var during: (suspend () -> Unit)? = null

        /** files that are there but the upload cannot open. */
        val unopenable = mutableSetOf<String>()

        override suspend fun upload(target: ChunkUploadTarget, path: String): TransferResult {
            during?.let { during = null; it() }
            if (path !in disk.files || path in unopenable) return TransferResult.MISSING
            // R2 refuses a body of any size but the one signed into the link, as a bad signature.
            val signed = target.headers["Content-Length"]?.toLongOrNull()
            if (signed != null && signed != disk.files.getValue(path).size.toLong()) return TransferResult.MISMATCH
            val answer = script.removeFirstOrNull() ?: TransferResult.UPLOADED
            if (answer == TransferResult.UPLOADED && !lose.remove(target.seq)) {
                val recordingId = target.url.split("/").dropLast(1).last()
                bucket.chunks.getOrPut(recordingId) { mutableSetOf() }.add(target.seq)
            }
            return answer
        }
    }

    private inner class Phone(
        val store: SqlRecordingStore = SqlRecordingStore(
            RecordingDatabase(testRecordingDriver()),
            Dispatchers.Unconfined,
        ),
        val files: FakeFiles = FakeFiles(),
        val bucket: Bucket = Bucket(),
        val server: FakeServer = FakeServer(bucket),
        val transfer: FakeTransfer = FakeTransfer(bucket, files),
        var runtime: RecorderRuntime = RecorderRuntime(),
    ) {
        fun start() = StartRecordingUseCase(store, files, runtime)
        fun begin() = BeginChunkUseCase(store, files, runtime)
        fun close() = CloseChunkUseCase(store, files)
        fun stop() = StopRecordingUseCase(store, files, runtime)
        fun recover() = RecoverRecordingsUseCase(store, files, runtime)
        fun continueRec() = ContinueRecordingUseCase(store, files, runtime)
        fun interrupt() = InterruptRecordingUseCase(store, files, runtime)
        fun sync() = SyncRecordingsUseCase(store, server, transfer, files, runtime)

        /** The process dies. The disk and the queue survive; memory does not. */
        fun crash() {
            runtime = RecorderRuntime()
        }

        /** One chunk of [frames] whole frames, written and closed like the engine does. */
        suspend fun chunk(recordingId: String, frames: Int, at: Long): Int {
            val start = begin().execute(recordingId)
            val bytes = frameBytes(frames)
            files.files[start.path] = bytes
            close().execute(
                recordingId, start.seq, start.path, bytes.size.toLong(), frames.toLong(), "md5-${bytes.size}", at,
            )
            return start.seq
        }

        suspend fun state(id: String) = store.byId(id)?.state
    }

    private fun frameBytes(n: Int, payload: Int = 50): ByteArray {
        val one = Adts.header(payload) + ByteArray(payload) { 3 }
        return ByteArray(one.size * n) { one[it % one.size] }
    }

    // ------------------------------------------------------------- the happy path

    @Test
    fun `a meeting is recorded then uploaded and confirmed`() = runTest {
        val phone = Phone()
        val id = phone.start().execute(owner, t0).recordingId
        phone.chunk(id, 938, t0 + 60_032)
        phone.chunk(id, 100, t0 + 66_432)
        assertTrue(phone.stop().execute(id, EndReason.STOPPED, t0 + 67_000))
        assertNull(phone.runtime.liveRecordingId)

        val outcome = phone.sync().execute(owner, device, t0 + 70_000)

        assertEquals(SyncStatus.DONE, outcome.status)
        assertEquals(2, outcome.uploaded)
        assertEquals(1, outcome.finalized)
        val sent = phone.server.finalized.single()
        assertEquals(listOf(0, 1), sent.seqs)
        assertEquals(60_032L + 6_400L, sent.durationMs)
        assertEquals(RecordingState.UPLOADED, phone.state(id))
        assertTrue(phone.store.chunks(id).all { it.state == ChunkState.UPLOADED })
    }

    @Test
    fun `a live recording sends what it has but is not closed`() = runTest {
        val phone = Phone()
        val id = phone.start().execute(owner, t0).recordingId
        phone.chunk(id, 938, t0 + 60_032)

        val outcome = phone.sync().execute(owner, device, t0 + 61_000)

        assertEquals(1, outcome.uploaded)
        assertEquals(0, outcome.finalized)
        assertEquals(RecordingState.RECORDING, phone.state(id))
        assertEquals(id, phone.runtime.liveRecordingId)
    }

    @Test
    fun `a pause is kept as a gap with its reason`() = runTest {
        val phone = Phone()
        val id = phone.start().execute(owner, t0).recordingId
        phone.chunk(id, 100, t0 + 6_400)
        PauseRecordingUseCase(phone.store).execute(id, GapReason.CALL, t0 + 10_000)
        assertEquals(RecordingState.PAUSED, phone.state(id))
        ResumeRecordingUseCase(phone.store).execute(id, t0 + 40_000)
        phone.chunk(id, 100, t0 + 46_400)
        phone.stop().execute(id, EndReason.STOPPED, t0 + 47_000)

        phone.sync().execute(owner, device, t0 + 50_000)

        assertEquals(
            listOf(RecordingGap(id, 10_000, 40_000, GapReason.CALL)),
            phone.server.finalized.single().gaps,
        )
    }

    @Test
    fun `a pause can change its reason and keeps every second accounted for`() = runTest {
        val phone = Phone()
        val id = phone.start().execute(owner, t0).recordingId
        phone.chunk(id, 100, t0 + 6_400)
        val pause = PauseRecordingUseCase(phone.store)
        pause.execute(id, GapReason.USER, t0 + 10_000)
        // Resume was pressed and the microphone would not come back: an interruption from here.
        pause.execute(id, GapReason.INTERRUPTION, t0 + 20_000)
        pause.execute(id, GapReason.INTERRUPTION, t0 + 25_000)
        // Then the system said a call had taken it.
        pause.execute(id, GapReason.CALL, t0 + 30_000)
        ResumeRecordingUseCase(phone.store).execute(id, t0 + 40_000)

        assertEquals(
            listOf(
                RecordingGap(id, 10_000, 20_000, GapReason.USER),
                RecordingGap(id, 20_000, 30_000, GapReason.INTERRUPTION),
                RecordingGap(id, 30_000, 40_000, GapReason.CALL),
            ),
            phone.store.gaps(id),
        )
        assertEquals(RecordingState.RECORDING, phone.state(id))
    }

    @Test
    fun `stopping before any audio leaves nothing behind`() = runTest {
        val phone = Phone()
        val id = phone.start().execute(owner, t0).recordingId
        assertFalse(phone.stop().execute(id, EndReason.STOPPED, t0 + 1_000))
        assertNull(phone.store.byId(id))
    }

    @Test
    fun `the recorder refuses a second recording and a full phone`() = runTest {
        val phone = Phone()
        phone.start().execute(owner, t0)
        val busy = assertFailsWith<RecorderRefused> { phone.start().execute(owner, t0 + 1) }
        assertEquals(RecorderErrors.ALREADY_RECORDING, busy.stringKey)

        val full = Phone().apply { files.free = 10L * 1024 * 1024 }
        val refused = assertFailsWith<RecorderRefused> { full.start().execute(owner, t0) }
        assertEquals(RecorderErrors.NO_SPACE, refused.stringKey)
    }

    // ---------------------------------------------------------------- crashes

    @Test
    fun `a crash mid-chunk keeps every whole frame written before it`() = runTest {
        val phone = Phone()
        val id = phone.start().execute(owner, t0).recordingId
        phone.chunk(id, 938, t0 + 60_032)
        HeartbeatUseCase(phone.store).execute(id, t0 + 70_000)
        // The second chunk was being written when the battery died: ten whole
        // frames, then a frame cut off twenty bytes in.
        val open = phone.begin().execute(id)
        val whole = frameBytes(10)
        phone.files.files[open.path] = whole + frameBytes(1).copyOf(20)

        phone.crash()
        val interrupted = phone.recover().execute(up(5 * 60_000)).waiting

        val recording = interrupted.single()
        assertEquals(id, recording.id)
        assertEquals(RecordingState.INTERRUPTED, recording.state)
        assertEquals(t0 + 70_000, recording.endedAt, "dated by the last sign of life")
        val repaired = phone.store.chunks(id).last()
        assertEquals(ChunkState.CLOSED, repaired.state)
        assertEquals(whole.size.toLong(), repaired.bytes)
        assertEquals(640L, repaired.durationMs)
        assertEquals(whole.size, phone.files.files[open.path]!!.size, "the torn frame is cut off")
    }

    // TAS deleted a chunk it could not read, as if it were gone.
    @Test
    fun `a chunk that cannot be read just now is kept for the next pass`() = runTest {
        val phone = Phone()
        val id = phone.start().execute(owner, t0).recordingId
        phone.chunk(id, 938, t0 + 60_032)
        val open = phone.begin().execute(id)
        val whole = frameBytes(10)
        phone.files.files[open.path] = whole + frameBytes(1).copyOf(20)
        phone.files.locked += open.path
        phone.crash()

        phone.recover().execute(up(60_000))

        assertEquals(ChunkState.OPEN, phone.store.chunks(id).last().state, "left as it was")
        assertTrue(open.path in phone.files.files, "and its file kept")

        phone.files.locked.clear()
        phone.recover().execute(up(120_000))
        assertEquals(ChunkState.CLOSED, phone.store.chunks(id).last().state)
        assertEquals(whole.size.toLong(), phone.store.chunks(id).last().bytes)
    }

    // R9. A file that is there but never reads used to hold its recording back forever.
    @Test
    fun `an open chunk that never reads is set aside after five runs over a day and the rest is sent`() = runTest {
        val phone = Phone()
        val id = phone.start().execute(owner, t0).recordingId
        phone.chunk(id, 938, t0 + 60_032)
        val open = phone.begin().execute(id)
        phone.files.files[open.path] = frameBytes(10)
        phone.files.locked += open.path
        assertTrue(phone.stop().execute(id, EndReason.STOPPED, t0 + 61_000))
        assertEquals(ChunkState.OPEN, phone.store.chunks(id).last().state, "kept for a later pass")

        val hour = 60 * 60_000L
        phone.sync().execute(owner, device, t0)
        assertEquals(1, phone.store.chunks(id).last().unreadable, "counted once a run")
        for (run in 1..3) phone.sync().execute(owner, device, t0 + run * 8 * hour)
        assertEquals(RecordingState.STOPPED, phone.state(id), "four runs are not enough, even over a day")
        assertTrue(open.path in phone.files.files)

        phone.sync().execute(owner, device, t0 + 25 * hour)

        assertEquals(RecordingState.UPLOADED, phone.state(id))
        assertEquals(listOf(0), phone.server.finalized.single().seqs)
        assertTrue("rejected/${open.path}" in phone.files.files, "moved aside, never deleted")
        assertFalse(open.path in phone.files.files)
    }

    @Test
    fun `a file locked until the first unlock is never set aside within a day`() = runTest {
        val phone = Phone()
        val id = phone.start().execute(owner, t0).recordingId
        phone.chunk(id, 938, t0 + 60_032)
        val open = phone.begin().execute(id)
        phone.files.files[open.path] = frameBytes(10)
        phone.files.locked += open.path
        phone.stop().execute(id, EndReason.STOPPED, t0 + 61_000)

        for (run in 0 until 20) phone.sync().execute(owner, device, t0 + run * 60 * 60_000L)
        assertEquals(RecordingState.STOPPED, phone.state(id), "twenty runs in under a day are not enough")
        assertEquals(20, phone.store.chunks(id).last().unreadable)

        phone.files.locked.clear()
        phone.sync().execute(owner, device, t0 + 21 * 60 * 60_000L)
        assertEquals(RecordingState.UPLOADED, phone.state(id))
        assertEquals(listOf(0, 1), phone.server.finalized.single().seqs, "nothing lost once it reads")
    }

    @Test
    fun `a closed chunk the upload can never open is set aside after five runs over a day`() = runTest {
        val phone = Phone()
        val id = phone.start().execute(owner, t0).recordingId
        phone.chunk(id, 938, t0 + 60_032)
        phone.chunk(id, 938, t0 + 120_064)
        phone.stop().execute(id, EndReason.STOPPED, t0 + 121_000)
        val stuck = phone.store.chunks(id)[1].path
        phone.transfer.unopenable += stuck

        val hour = 60 * 60_000L
        for (run in 0..3) phone.sync().execute(owner, device, t0 + run * 8 * hour)
        assertEquals(RecordingState.STOPPED, phone.state(id))
        assertEquals(ChunkState.CLOSED, phone.store.chunks(id)[1].state)

        phone.sync().execute(owner, device, t0 + 25 * hour)
        assertEquals(RecordingState.UPLOADED, phone.state(id))
        assertEquals(listOf(0), phone.server.finalized.single().seqs)
        assertTrue("rejected/$stuck" in phone.files.files)
    }

    @Test
    fun `continuing after a crash is the same recording with the dead time marked`() = runTest {
        val phone = Phone()
        val id = phone.start().execute(owner, t0).recordingId
        phone.chunk(id, 938, t0 + 60_032)
        phone.crash()
        phone.recover().execute(up(3 * 60_000))

        val resumed = phone.continueRec().execute(id, owner, t0 + 3 * 60_000)

        assertEquals(id, resumed.recordingId)
        assertEquals(60_032L, resumed.priorMs, "the 3-hour cap counts the audio already recorded")
        assertEquals(RecordingState.RECORDING, phone.state(id))
        assertEquals(id, phone.runtime.liveRecordingId)
        assertEquals(
            listOf(RecordingGap(id, 60_032, 3 * 60_000, GapReason.KILLED)),
            phone.store.gaps(id),
        )
        assertEquals(1, phone.begin().execute(id).seq, "numbering carries on")
    }

    @Test
    fun `a pause open when the app died ends there and the dead time is its own gap`() = runTest {
        val phone = Phone()
        val id = phone.start().execute(owner, t0).recordingId
        phone.chunk(id, 100, t0 + 6_400)
        PauseRecordingUseCase(phone.store).execute(id, GapReason.USER, t0 + 10_000)
        HeartbeatUseCase(phone.store).execute(id, t0 + 30_000)
        phone.crash()
        phone.recover().execute(up(60_000))
        phone.continueRec().execute(id, owner, t0 + 90_000)

        assertEquals(
            listOf(
                RecordingGap(id, 10_000, 30_000, GapReason.USER),
                RecordingGap(id, 30_000, 90_000, GapReason.KILLED),
            ),
            phone.store.gaps(id),
        )
    }

    @Test
    fun `a crash before the first frame leaves nothing to continue`() = runTest {
        val phone = Phone()
        val id = phone.start().execute(owner, t0).recordingId
        // The row was made, and the app died before the file was.
        phone.begin().execute(id)
        phone.crash()

        assertTrue(phone.recover().execute(up(60_000)).waiting.isEmpty())
        assertNull(phone.store.byId(id))
    }

    @Test
    fun `an interrupted recording nobody decides about finishes itself after an hour`() = runTest {
        val phone = Phone()
        val id = phone.start().execute(owner, t0).recordingId
        phone.chunk(id, 938, t0 + 60_032)
        phone.crash()
        phone.recover().execute(up(2 * 60_000))
        assertEquals(RecordingState.INTERRUPTED, phone.state(id))
        phone.recover().execute(up(2 * 60_000 + 59 * 60_000))
        assertEquals(RecordingState.INTERRUPTED, phone.state(id), "an hour from when it was found, not from the crash")

        phone.recover().execute(up(2 * 60_000 + 60 * 60_000))

        val finished = phone.store.byId(id)!!
        assertEquals(RecordingState.STOPPED, finished.state)
        assertEquals(EndReason.INTERRUPTED, finished.endReason)
        assertEquals(t0 + 60_032, finished.endedAt)
        phone.sync().execute(owner, device, t0 + 2 * 60 * 60_000)
        assertEquals(RecordingState.UPLOADED, phone.state(id))
    }

    // the person is told, and keeps Continue, however long ago the app died.

    @Test
    fun `a recording found long after it died is reported and still offered for Continue`() = runTest {
        val phone = Phone()
        val id = phone.start().execute(owner, t0).recordingId
        phone.chunk(id, 938, t0 + 60_032)
        phone.crash()

        // The phone was off all night; the first pass after it comes back finds the recording.
        val report = phone.recover().execute(up(10 * 60 * 60_000))

        assertEquals(listOf(id), report.cutOff.map { it.id }, "found by this pass, so the person is told")
        assertEquals(listOf(id), report.waiting.map { it.id }, "and it can still be continued")
        assertEquals(RecordingState.INTERRUPTED, phone.state(id))
        assertEquals(up(10 * 60 * 60_000), phone.store.byId(id)!!.foundUptimeMs)
        assertTrue(phone.recover().execute(up(10 * 60 * 60_000 + 1_000)).cutOff.isEmpty(), "reported once")
    }

    @Test
    fun `a restart of the phone never shortens the hour before it is sent by itself`() = runTest {
        val phone = Phone()
        val id = phone.start().execute(owner, t0).recordingId
        phone.chunk(id, 938, t0 + 60_032)
        phone.crash()
        phone.recover().execute(50 * minute)

        // The phone restarted: its clock since boot began again, below the time it was found.
        phone.recover().execute(30 * minute)
        assertEquals(RecordingState.INTERRUPTED, phone.state(id))
        phone.recover().execute(100 * minute)
        assertEquals(RecordingState.INTERRUPTED, phone.state(id), "50 minutes counted since it was found")

        phone.recover().execute(110 * minute)
        assertEquals(RecordingState.STOPPED, phone.state(id))
    }

    @Test
    fun `a recording cut off before found times were kept gets a full hour from now`() = runTest {
        val phone = Phone()
        val id = phone.start().execute(owner, t0).recordingId
        phone.chunk(id, 938, t0 + 60_032)
        phone.store.finish(id, RecordingState.INTERRUPTED, t0 + 60_032, EndReason.INTERRUPTED)
        phone.crash()

        phone.recover().execute(up(0))
        assertEquals(RecordingState.INTERRUPTED, phone.state(id))
        phone.recover().execute(up(59 * minute))
        assertEquals(RecordingState.INTERRUPTED, phone.state(id))
        phone.recover().execute(up(60 * minute))
        assertEquals(RecordingState.STOPPED, phone.state(id))
    }

    @Test
    fun `continuing forgets when it was found`() = runTest {
        val phone = Phone()
        val id = phone.start().execute(owner, t0).recordingId
        phone.chunk(id, 938, t0 + 60_032)
        phone.crash()
        phone.recover().execute(up(60_000))

        phone.continueRec().execute(id, owner, t0 + 3 * 60_000)

        assertNull(phone.store.byId(id)!!.foundUptimeMs)
    }

    @Test
    fun `why the process died is kept with the recording`() = runTest {
        val phone = Phone()
        val id = phone.stoppedMeeting()

        NoteRecordingExitUseCase(phone.store).execute(id, "reason=USER_REQUESTED " + "x".repeat(600))

        assertEquals(500, phone.store.byId(id)!!.exitInfo!!.length)
    }

    // an end nobody chose keeps the recording for Continue.

    @Test
    fun `an end nobody chose seals the audio and keeps the recording for Continue`() = runTest {
        val phone = Phone()
        val id = phone.start().execute(owner, t0).recordingId
        phone.chunk(id, 938, t0 + 60_032)
        val open = phone.begin().execute(id)
        phone.files.files[open.path] = frameBytes(10) + frameBytes(1).copyOf(20)

        assertTrue(phone.interrupt().execute(id, t0 + 61_000, up(61_000)))

        val recording = phone.store.byId(id)!!
        assertEquals(RecordingState.INTERRUPTED, recording.state)
        assertEquals(EndReason.INTERRUPTED, recording.endReason)
        assertEquals(t0 + 61_000, recording.endedAt)
        assertEquals(up(61_000), recording.foundUptimeMs, "its hour counts from now")
        assertNull(phone.runtime.liveRecordingId, "nothing in this process is recording it any more")
        assertTrue(phone.store.chunks(id).none { it.state == ChunkState.OPEN }, "the open chunk is sealed")
        assertEquals(640L, phone.store.chunks(id).last().durationMs)
        // A later pass does not report it again as found: it was never lost.
        assertTrue(phone.recover().execute(up(62_000)).cutOff.isEmpty())
        assertEquals(RecordingState.INTERRUPTED, phone.state(id))
    }

    @Test
    fun `a recorder still alive continues the same recording at once with the gap marked`() = runTest {
        val phone = Phone()
        val id = phone.start().execute(owner, t0).recordingId
        phone.chunk(id, 938, t0 + 60_032)
        phone.interrupt().execute(id, t0 + 61_000, up(61_000))

        val again = phone.continueRec().execute(id, owner, t0 + 62_000, gapReason = GapReason.INTERRUPTION)

        assertEquals(id, again.recordingId)
        assertEquals(60_032L, again.priorMs)
        assertEquals(listOf(RecordingGap(id, 61_000, 62_000, GapReason.INTERRUPTION)), phone.store.gaps(id))
        assertEquals(1, phone.begin().execute(id).seq)
    }

    // R3. The recorder's own continue needs far less room than a new recording.
    @Test
    fun `the recorder continues a meeting by itself on a phone with little space left`() = runTest {
        val phone = Phone()
        val id = phone.start().execute(owner, t0).recordingId
        phone.chunk(id, 938, t0 + 60_032)
        phone.interrupt().execute(id, t0 + 61_000, up(61_000))
        phone.files.free = 50L * 1024 * 1024

        val refused = assertFailsWith<RecorderRefused> { phone.continueRec().execute(id, owner, t0 + 62_000) }
        assertEquals(RecorderErrors.NO_SPACE, refused.stringKey, "Continue pressed by the person keeps the 100 MB floor")

        val again = phone.continueRec().execute(
            id, owner, t0 + 62_000,
            gapReason = GapReason.INTERRUPTION,
            minFreeBytes = RecorderConfig.MIN_FREE_BYTES_AUTO_CONTINUE,
        )
        assertEquals(id, again.recordingId)

        phone.interrupt().execute(id, t0 + 63_000, up(63_000))
        phone.files.free = 10L * 1024 * 1024
        assertFailsWith<RecorderRefused> {
            phone.continueRec().execute(
                id, owner, t0 + 64_000,
                gapReason = GapReason.INTERRUPTION,
                minFreeBytes = RecorderConfig.MIN_FREE_BYTES_AUTO_CONTINUE,
            )
        }
    }

    @Test
    fun `an end nobody chose before any audio leaves nothing behind`() = runTest {
        val phone = Phone()
        val id = phone.start().execute(owner, t0).recordingId

        assertFalse(phone.interrupt().execute(id, t0 + 1_000, up(1_000)))

        assertNull(phone.store.byId(id))
        assertNull(phone.runtime.liveRecordingId)
    }

    @Test
    fun `finishing an interrupted recording sends it as it is`() = runTest {
        val phone = Phone()
        val id = phone.start().execute(owner, t0).recordingId
        phone.chunk(id, 938, t0 + 60_032)
        phone.crash()
        phone.recover().execute(up(2 * 60_000))

        FinishInterruptedUseCase(phone.store, phone.files, phone.runtime).execute(id)
        phone.sync().execute(owner, device, t0 + 3 * 60_000)

        assertEquals(RecordingState.UPLOADED, phone.state(id))
        assertEquals(listOf(0), phone.server.finalized.single().seqs)
    }

    // R1. Stop in the second between an error and the automatic continue is the person's Stop.
    @Test
    fun `a Stop before the automatic continue ends the recording as stopped and sends it`() = runTest {
        val phone = Phone()
        val id = phone.start().execute(owner, t0).recordingId
        phone.chunk(id, 938, t0 + 60_032)
        phone.interrupt().execute(id, t0 + 61_000, up(61_000))

        FinishInterruptedUseCase(phone.store, phone.files, phone.runtime).execute(id, EndReason.STOPPED)

        val recording = phone.store.byId(id)!!
        assertEquals(RecordingState.STOPPED, recording.state)
        assertEquals(EndReason.STOPPED, recording.endReason, "the person's Stop, not a cut-off")
        assertEquals(t0 + 61_000, recording.endedAt, "where the audio stopped")
        phone.sync().execute(owner, device, t0 + 62_000)
        assertEquals(RecordingState.UPLOADED, phone.state(id))
    }

    @Test
    fun `a new recording finishes the one waiting on Continue`() = runTest {
        val phone = Phone()
        val old = phone.start().execute(owner, t0).recordingId
        phone.chunk(old, 938, t0 + 60_032)
        phone.crash()

        val fresh = phone.start().execute(owner, t0 + 5 * 60_000).recordingId

        assertEquals(RecordingState.STOPPED, phone.state(old))
        assertEquals(RecordingState.RECORDING, phone.state(fresh))
        assertEquals(fresh, phone.runtime.liveRecordingId)
    }

    @Test
    fun `recovery never touches the recording this process is making`() = runTest {
        val phone = Phone()
        val id = phone.start().execute(owner, t0).recordingId
        val open = phone.begin().execute(id)
        phone.files.files[open.path] = frameBytes(5) + frameBytes(1).copyOf(3)

        assertTrue(phone.recover().execute(up(60_000)).waiting.isEmpty())

        assertEquals(RecordingState.RECORDING, phone.state(id))
        assertEquals(ChunkState.OPEN, phone.store.chunks(id).single().state)
    }

    @Test
    fun `only the owner can continue`() = runTest {
        val phone = Phone()
        val id = phone.start().execute(owner, t0).recordingId
        phone.chunk(id, 100, t0 + 6_400)
        phone.crash()
        phone.recover().execute(up(60_000))

        val refused = assertFailsWith<RecorderRefused> {
            phone.continueRec().execute(id, "someone-else", t0 + 60_000)
        }
        assertEquals(RecorderErrors.CANNOT_CONTINUE, refused.stringKey)
    }

    // ------------------------------------------- the planned length

    private val minute = 60_000L

    /** A closed chunk holding [ms] of audio, without writing that much to the fake disk. */
    private suspend fun Phone.longChunk(recordingId: String, ms: Long, at: Long) {
        val start = begin().execute(recordingId)
        files.files[start.path] = frameBytes(1)
        close().execute(recordingId, start.seq, start.path, 57, ms / 64, "md5-57", at)
    }

    @Test
    fun `the length picked before Record is kept with the recording`() = runTest {
        val phone = Phone()
        val start = phone.start().execute(owner, t0, planMs = 150 * minute)

        assertEquals(150 * minute, start.stopAtMs)
        val row = phone.store.byId(start.recordingId)!!
        assertEquals(150 * minute, row.planMs)
        assertEquals(150 * minute, row.stopAtMs)
    }

    @Test
    fun `no length - or one that is not offered - starts on 1 h 30 m`() = runTest {
        val phone = Phone()
        val plain = phone.start().execute(owner, t0)
        assertEquals(90 * minute, plain.stopAtMs)
        assertEquals(90 * minute, phone.store.byId(plain.recordingId)!!.planMs)
        phone.stop().execute(plain.recordingId, EndReason.STOPPED, t0 + 1)

        val odd = phone.start().execute(owner, t0 + 2, planMs = 100 * minute)
        assertEquals(90 * minute, odd.stopAtMs)
    }

    @Test
    fun `Keep recording is kept and a recording continued after a crash carries on with it`() = runTest {
        val phone = Phone()
        val id = phone.start().execute(owner, t0).recordingId
        phone.longChunk(id, 91 * minute, t0 + 91 * minute)
        ExtendRecordingUseCase(phone.store).execute(id, 180 * minute)
        assertEquals(180 * minute, phone.store.byId(id)!!.stopAtMs)
        assertEquals(90 * minute, phone.store.byId(id)!!.planMs, "the length first picked stays as it was")

        phone.crash()
        phone.recover().execute(up(95 * minute))
        val resumed = phone.continueRec().execute(id, owner, t0 + 96 * minute)

        assertEquals(180 * minute, resumed.stopAtMs)
        assertTrue(resumed.priorMs >= 90 * minute)
    }

    @Test
    fun `a recording continued with no plan on its row gets 1 h 30 m`() = runTest {
        val phone = Phone()
        val id = "0b6c1f5e-7a3d-4c1e-9f2a-5d8e7c6b4a31"
        // A row as an earlier version wrote it: no plan columns.
        phone.store.create(id, owner, t0)
        phone.longChunk(id, 10 * minute, t0 + 10 * minute)
        phone.recover().execute(up(20 * minute))

        val resumed = phone.continueRec().execute(id, owner, t0 + 21 * minute)

        assertEquals(90 * minute, resumed.stopAtMs)
    }

    @Test
    fun `continuing stops at the 3-hour cap`() = runTest {
        val phone = Phone()
        val id = phone.start().execute(owner, t0, planMs = 180 * minute).recordingId
        phone.longChunk(id, 180 * minute - 4_000, t0 + 180 * minute)
        phone.crash()
        phone.recover().execute(up(181 * minute))

        val refused = assertFailsWith<RecorderRefused> {
            phone.continueRec().execute(id, owner, t0 + 182 * minute)
        }

        assertEquals(RecorderErrors.LIMIT_REACHED, refused.stringKey)
        assertEquals(RecordingState.STOPPED, phone.state(id), "finished as it is, so it is sent")
    }

    @Test
    fun `a recording with room under the cap can still be continued near it`() = runTest {
        val phone = Phone()
        val id = phone.start().execute(owner, t0, planMs = 180 * minute).recordingId
        phone.longChunk(id, 170 * minute, t0 + 170 * minute)
        phone.crash()
        phone.recover().execute(up(171 * minute))

        val resumed = phone.continueRec().execute(id, owner, t0 + 172 * minute)

        assertEquals(180 * minute, resumed.stopAtMs)
    }

    @Test
    fun `Keep recording never moves the plan back or past the cap and only while recording`() = runTest {
        val phone = Phone()
        val id = phone.start().execute(owner, t0, planMs = 120 * minute).recordingId
        phone.longChunk(id, minute, t0 + minute)

        ExtendRecordingUseCase(phone.store).execute(id, 60 * minute)
        assertEquals(120 * minute, phone.store.byId(id)!!.stopAtMs)
        ExtendRecordingUseCase(phone.store).execute(id, 300 * minute)
        assertEquals(180 * minute, phone.store.byId(id)!!.stopAtMs)

        val other = phone.stoppedMeetingAfter(id)
        ExtendRecordingUseCase(phone.store).execute(other, 180 * minute)
        assertEquals(90 * minute, phone.store.byId(other)!!.stopAtMs)
    }

    private suspend fun Phone.stoppedMeetingAfter(live: String): String {
        stop().execute(live, EndReason.STOPPED, t0 + 2 * minute)
        return stoppedMeeting(at = t0 + 3 * minute)
    }

    @Test
    fun `an unanswered warning ends the recording with its own reason`() = runTest {
        val phone = Phone()
        val id = phone.start().execute(owner, t0).recordingId
        phone.longChunk(id, 95 * minute, t0 + 95 * minute)

        assertTrue(phone.stop().execute(id, EndReason.PLANNED, t0 + 96 * minute))

        assertEquals(EndReason.PLANNED, phone.store.byId(id)!!.endReason)
        assertEquals("planned", EndReason.PLANNED.id)
        phone.sync().execute(owner, device, t0 + 97 * minute)
        assertEquals(RecordingState.UPLOADED, phone.state(id), "uploaded exactly as a normal stop is")
    }

    @Test
    fun `shorter limits decide the cap when continuing`() = runTest {
        val fast = RecorderLimits(
            choicesMs = listOf(120_000L, 240_000L), defaultMs = 120_000L,
            capMs = 240_000L, extendByMs = 120_000L, graceMs = 30_000L,
        )
        val phone = Phone()
        val start = phone.start().execute(owner, t0, planMs = 240_000L, limits = fast)
        assertEquals(240_000L, start.stopAtMs)
        phone.longChunk(start.recordingId, 238_000L, t0 + 240_000L)
        phone.crash()
        phone.recover().execute(up(300_000L))

        val refused = assertFailsWith<RecorderRefused> {
            phone.continueRec().execute(start.recordingId, owner, t0 + 301_000L, limits = fast)
        }
        assertEquals(RecorderErrors.LIMIT_REACHED, refused.stringKey)
    }

    // ----------------------------------------------------------------- upload

    private suspend fun Phone.stoppedMeeting(chunks: Int = 2, who: String = owner, at: Long = t0): String {
        val id = start().execute(who, at).recordingId
        repeat(chunks) { i -> chunk(id, 100, at + (i + 1) * 6_400L) }
        stop().execute(id, EndReason.STOPPED, at + 60_000)
        return id
    }

    @Test
    fun `no signal stops the run and loses nothing`() = runTest {
        val phone = Phone()
        val id = phone.stoppedMeeting()
        phone.transfer.script += TransferResult.RETRY

        val first = phone.sync().execute(owner, device, t0 + 70_000)
        assertEquals(SyncStatus.RETRY, first.status)
        assertTrue(phone.store.chunks(id).all { it.state == ChunkState.CLOSED })

        val second = phone.sync().execute(owner, device, t0 + 80_000)
        assertEquals(SyncStatus.DONE, second.status)
        assertEquals(RecordingState.UPLOADED, phone.state(id))
    }

    @Test
    fun `a chunk the server lost is sent again before the recording is confirmed`() = runTest {
        val phone = Phone()
        val id = phone.stoppedMeeting()
        phone.transfer.lose += 0

        val outcome = phone.sync().execute(owner, device, t0 + 70_000)

        assertEquals(3, outcome.uploaded, "chunk 0 went twice")
        assertEquals(listOf(0, 1), phone.server.finalized.single().seqs)
        assertEquals(RecordingState.UPLOADED, phone.state(id))
    }

    @Test
    fun `a file lost from the phone is dropped and the rest of the meeting still goes`() = runTest {
        val phone = Phone()
        val id = phone.stoppedMeeting(chunks = 3)
        phone.files.files.remove(phone.store.chunks(id)[1].path)

        phone.sync().execute(owner, device, t0 + 70_000)

        assertEquals(listOf(0, 2), phone.server.finalized.single().seqs)
        assertEquals(RecordingState.UPLOADED, phone.state(id))
    }

    // a row whose size no longer matches its file used to be refused by Storage forever.

    @Test
    fun `a chunk whose row and file disagree is measured again before it is sent`() = runTest {
        val phone = Phone()
        val id = phone.start().execute(owner, t0).recordingId
        // A write failed halfway: the row counted 10 frames, the disk holds 10 and a torn 11th.
        val start = phone.begin().execute(id)
        val whole = frameBytes(10)
        phone.files.files[start.path] = whole + frameBytes(1).copyOf(20)
        phone.close().execute(id, start.seq, start.path, whole.size.toLong(), 10, "md5-${whole.size}", t0 + 640)
        phone.files.files[start.path] = whole + frameBytes(1).copyOf(20)
        phone.stop().execute(id, EndReason.STOPPED, t0 + 1_000)

        val outcome = phone.sync().execute(owner, device, t0 + 2_000)

        assertEquals(SyncStatus.DONE, outcome.status)
        assertEquals(RecordingState.UPLOADED, phone.state(id))
        assertEquals(whole.size.toLong(), phone.store.chunks(id).single().bytes, "the row matches the file again")
        assertEquals(0, phone.store.chunks(id).single().rejections)
    }

    @Test
    fun `a row the disk will not let be repaired makes its recording wait and never loops`() = runTest {
        val phone = Phone()
        val stuck = phone.stoppedMeeting(at = t0)
        val good = phone.stoppedMeeting(at = t0 + 60 * 60_000)
        val first = phone.store.chunks(stuck).first()
        // The file grew under its row, and sealing it fails.
        phone.files.files[first.path] = phone.files.files.getValue(first.path) + frameBytes(1)
        phone.files.sealFails += first.path

        val outcome = phone.sync().execute(owner, device, t0 + 2 * 60 * 60_000)

        assertEquals(SyncStatus.RETRY, outcome.status)
        assertEquals(RecordingState.UPLOADED, phone.state(good))
        assertEquals(RecordingState.STOPPED, phone.state(stuck))
    }

    @Test
    fun `a refused signature on a file that matches its row is not held against the chunk`() = runTest {
        val phone = Phone()
        val id = phone.stoppedMeeting()
        phone.transfer.script += TransferResult.MISMATCH

        val outcome = phone.sync().execute(owner, device, t0 + 70_000)

        assertEquals(SyncStatus.RETRY, outcome.status, "the server's problem: tried again later")
        assertTrue(phone.store.chunks(id).all { it.rejections == 0 })
        assertEquals(2, phone.store.chunks(id).size, "nothing dropped")
        assertEquals(SyncStatus.DONE, phone.sync().execute(owner, device, t0 + 80_000).status)
        assertEquals(RecordingState.UPLOADED, phone.state(id))
    }

    @Test
    fun `a file Storage keeps refusing is set aside on the fifth refusal`() = runTest {
        val phone = Phone()
        val id = phone.stoppedMeeting()
        val refusedPath = phone.store.chunks(id)[0].path
        repeat(5) { phone.transfer.script += TransferResult.REJECTED }

        repeat(4) {
            assertEquals(SyncStatus.RETRY, phone.sync().execute(owner, device, t0 + 70_000).status)
        }
        assertEquals(SyncStatus.DONE, phone.sync().execute(owner, device, t0 + 80_000).status)

        assertEquals(listOf(1), phone.server.finalized.single().seqs)
        assertEquals(RecordingState.UPLOADED, phone.state(id))
        // kept on the phone, not deleted.
        assertTrue("rejected/$refusedPath" in phone.files.files, "moved to rejected/")
        assertFalse(refusedPath in phone.files.files)
    }

    // the server's answer for a chunk over its size limit used to leave it pending for ever.
    @Test
    fun `a chunk the server will not take is set aside and the rest is finalized`() = runTest {
        val phone = Phone()
        val id = phone.stoppedMeeting(chunks = 3)
        val big = phone.store.chunks(id)[1]
        phone.server.tooLarge += big.seq

        val outcome = phone.sync().execute(owner, device, t0 + 70_000)

        assertEquals(SyncStatus.DONE, outcome.status)
        assertEquals(listOf(0, 2), phone.server.finalized.single().seqs)
        assertEquals(RecordingState.UPLOADED, phone.state(id))
        assertTrue("rejected/${big.path}" in phone.files.files, "kept on the phone")
    }

    @Test
    fun `bytes with no whole frame in them are set aside and not deleted`() = runTest {
        val phone = Phone()
        val id = phone.start().execute(owner, t0).recordingId
        phone.chunk(id, 100, t0 + 6_400)
        val open = phone.begin().execute(id)
        phone.files.files[open.path] = ByteArray(300) { 7 }
        phone.crash()

        phone.recover().execute(up(60_000))

        assertEquals(1, phone.store.chunks(id).size, "the unreadable chunk leaves the queue")
        assertTrue("rejected/${open.path}" in phone.files.files)
    }

    @Test
    fun `a refusal about one recording does not hold up the next`() = runTest {
        val phone = Phone()
        val bad = phone.stoppedMeeting(at = t0)
        val good = phone.stoppedMeeting(at = t0 + 60 * 60_000)
        phone.server.refuseLinks[bad] = RecorderErrors.NOT_YOURS

        val outcome = phone.sync().execute(owner, device, t0 + 2 * 60 * 60_000)

        assertEquals(SyncStatus.DONE, outcome.status)
        assertEquals(RecorderErrors.NOT_YOURS, phone.store.byId(bad)!!.lastError)
        assertEquals(RecordingState.STOPPED, phone.state(bad))
        assertEquals(RecordingState.UPLOADED, phone.state(good))
    }

    // an answer that is a failure holds up only its own recording.

    @Test
    fun `a server error about one recording does not hold up the next and the run asks again`() = runTest {
        val phone = Phone()
        val stuck = phone.stoppedMeeting(at = t0)
        val good = phone.stoppedMeeting(at = t0 + 60 * 60_000)
        phone.server.refuseLinks[stuck] = RecorderErrors.SERVER

        val outcome = phone.sync().execute(owner, device, t0 + 2 * 60 * 60_000)

        assertEquals(SyncStatus.RETRY, outcome.status, "run again later for the one that waited")
        assertEquals(RecordingState.UPLOADED, phone.state(good))
        assertEquals(RecordingState.STOPPED, phone.state(stuck))
        assertEquals(1, phone.server.linkCalls[stuck], "asked once this run, not in a loop")

        phone.server.refuseLinks.remove(stuck)
        assertEquals(SyncStatus.DONE, phone.sync().execute(owner, device, t0 + 3 * 60 * 60_000).status)
        assertEquals(RecordingState.UPLOADED, phone.state(stuck))
        assertNull(phone.store.byId(stuck)!!.lastError)
    }

    @Test
    fun `a chunk Storage will not take holds up only its own recording`() = runTest {
        val phone = Phone()
        val stuck = phone.stoppedMeeting(at = t0)
        val good = phone.stoppedMeeting(at = t0 + 60 * 60_000)
        // The first PUT of the run is answered 403 (an expired link, say): an answer, not "no signal".
        phone.transfer.script += TransferResult.FAILED

        val outcome = phone.sync().execute(owner, device, t0 + 2 * 60 * 60_000)

        assertEquals(SyncStatus.RETRY, outcome.status)
        assertEquals(RecordingState.UPLOADED, phone.state(good))
        assertEquals(RecordingState.STOPPED, phone.state(stuck))
        assertTrue(phone.store.chunks(stuck).none { it.state == ChunkState.UPLOADED }, "nothing claimed as sent")
    }

    @Test
    fun `no answer at all still ends the run at once`() = runTest {
        val phone = Phone()
        phone.stoppedMeeting(at = t0)
        val second = phone.stoppedMeeting(at = t0 + 60 * 60_000)
        phone.transfer.script += TransferResult.RETRY

        assertEquals(SyncStatus.RETRY, phone.sync().execute(owner, device, t0 + 2 * 60 * 60_000).status)
        assertNull(phone.server.linkCalls[second], "no signal: the others are not tried either")
    }

    @Test
    fun `a suspended account stops the run`() = runTest {
        val phone = Phone()
        phone.stoppedMeeting()
        phone.server.refuseAllLinks = RecorderErrors.INACTIVE

        assertEquals(SyncStatus.BLOCKED, phone.sync().execute(owner, device, t0 + 70_000).status)
    }

    // TAS marked the unsent chunks as sent here, and deleted them three days later.
    @Test
    fun `a recording deleted in the CRM keeps its unsent audio and says so`() = runTest {
        val phone = Phone()
        val id = phone.stoppedMeeting()
        // Deleted on the website before any chunk went.
        phone.server.closed += id

        val outcome = phone.sync().execute(owner, device, t0 + 70_000)

        assertEquals(SyncStatus.DONE, outcome.status)
        assertEquals(0, outcome.uploaded)
        val row = phone.store.byId(id)!!
        assertEquals(RecordingState.UPLOADED, row.state, "the server takes nothing more, so it is not sent again")
        assertEquals(RecorderErrors.ALREADY_FINISHED, row.lastError, "shown as deleted in the CRM, not as sent")
        assertTrue(phone.store.chunks(id).none { it.state == ChunkState.UPLOADED }, "nothing unsent is marked sent")
        assertTrue(phone.bucket.has(id).isEmpty())

        // Not sent again on a later run, and details typed afterwards do not reopen it.
        phone.sync().execute(owner, device, t0 + 80_000)
        assertEquals(1, phone.server.linkCalls[id], "asked once")
        SetRecordingDetailsUseCase(phone.store).execute(id, "Board meeting", null, null, null)
        assertEquals(RecordingState.UPLOADED, phone.state(id))

        // Its audio stays a month, not three days.
        val purge = PurgeUploadedRecordingsUseCase(phone.store, phone.files)
        assertEquals(0, purge.execute(t0 + 70_000 + 4 * 24 * 60 * 60_000L))
        assertTrue(phone.files.files.keys.any { it.startsWith("$id/") })
        assertEquals(1, purge.execute(t0 + 70_000 + 30L * 24 * 60 * 60_000L + 1))
        assertTrue(phone.files.files.keys.none { it.startsWith("$id/") })
    }

    @Test
    fun `a live recording deleted in the CRM keeps recording and keeps its chunks`() = runTest {
        val phone = Phone()
        val id = phone.start().execute(owner, t0).recordingId
        phone.chunk(id, 100, t0 + 6_400)
        phone.server.closed += id

        phone.sync().execute(owner, device, t0 + 7_000)

        assertEquals(RecordingState.RECORDING, phone.state(id))
        assertEquals(RecorderErrors.ALREADY_FINISHED, phone.store.byId(id)!!.lastError)
        assertEquals(ChunkState.CLOSED, phone.store.chunks(id).single().state)
    }

    @Test
    fun `another worker's recordings wait for them on a shared phone`() = runTest {
        val phone = Phone()
        val theirs = phone.stoppedMeeting(who = "u2")

        val outcome = phone.sync().execute(owner, device, t0 + 70_000)

        assertEquals(0, outcome.uploaded)
        assertEquals(RecordingState.STOPPED, phone.state(theirs))
        assertTrue(phone.bucket.has(theirs).isEmpty())
    }

    // somebody else's meetings on a shared phone are not invisible.
    @Test
    fun `the person signed in is told whose meetings wait for another sign-in`() = runTest {
        val phone = Phone()
        val first = phone.start().execute("u2", t0, ownerName = "  Asha Verma  ").recordingId
        phone.chunk(first, 100, t0 + 6_400)
        phone.stop().execute(first, EndReason.STOPPED, t0 + 7_000)
        val second = phone.start().execute("u2", t0 + 10_000, ownerName = "Asha Verma").recordingId
        phone.chunk(second, 100, t0 + 16_400)
        phone.stop().execute(second, EndReason.STOPPED, t0 + 17_000)
        // A row from before names were kept.
        phone.store.create("4c2d9e1a-7b3f-4a5e-8c6d-1f2a3b4c5d6e", "u3", t0 + 20_000)
        phone.store.finish("4c2d9e1a-7b3f-4a5e-8c6d-1f2a3b4c5d6e", RecordingState.STOPPED, t0 + 25_000, EndReason.STOPPED)
        phone.stoppedMeeting(at = t0 + 30_000)

        val waiting = OthersWaitingUseCase(phone.store).execute(owner)

        assertEquals(
            listOf(OthersWaiting("u2", "Asha Verma", 2), OthersWaiting("u3", null, 1)),
            waiting,
        )
        assertEquals("Asha Verma", phone.store.byId(first)!!.ownerName, "kept trimmed")
        assertEquals("2 meetings waiting for Asha Verma to sign in",
            Strings.get("recorder.othersWaiting", Language.ENGLISH, mapOf("count" to 2, "name" to "Asha Verma")))
        assertEquals("1 meeting waiting for another account to sign in",
            Strings.get("recorder.othersWaiting", Language.ENGLISH, mapOf("count" to 1, "name" to Strings.get("recorder.othersWaiting.someone", Language.ENGLISH))))
    }

    @Test
    fun `details added after the upload are sent on the next run`() = runTest {
        val phone = Phone()
        val id = phone.stoppedMeeting()
        phone.sync().execute(owner, device, t0 + 70_000)
        assertNull(phone.server.finalized.single().title)

        SetRecordingDetailsUseCase(phone.store).execute(id, "  Ward 3 booth meeting  ", null, "", null)
        assertEquals(RecordingState.STOPPED, phone.state(id))
        phone.sync().execute(owner, device, t0 + 80_000)

        assertEquals("Ward 3 booth meeting", phone.server.finalized.last().title)
        assertEquals(RecordingState.UPLOADED, phone.state(id))
        assertNull(phone.store.byId(id)!!.location, "a blank field is no field")
    }

    @Test
    fun `audio stays on the phone for three days after the server confirms it`() = runTest {
        val phone = Phone()
        val id = phone.stoppedMeeting()
        val confirmedAt = t0 + 70_000
        phone.sync().execute(owner, device, confirmedAt)
        val purge = PurgeUploadedRecordingsUseCase(phone.store, phone.files)

        assertEquals(0, purge.execute(confirmedAt + 2 * 24 * 60 * 60_000L))
        assertTrue(phone.files.files.keys.any { it.startsWith("$id/") })

        assertEquals(1, purge.execute(confirmedAt + 3 * 24 * 60 * 60_000L + 1))
        assertTrue(phone.files.files.keys.none { it.startsWith("$id/") })
        val kept = phone.store.byId(id)
        assertNotNull(kept, "the row stays as history")
        assertNotNull(kept.purgedAt)
    }

    @Test
    fun `the list follows the queue as it changes`() = runTest {
        val phone = Phone()
        val seen = mutableListOf<List<RecordingSummary>>()
        val watch = ObserveRecordingsUseCase(phone.store).execute(owner) { seen += it }

        val id = phone.start().execute(owner, t0).recordingId
        phone.chunk(id, 938, t0 + 60_032)
        phone.stop().execute(id, EndReason.STOPPED, t0 + 61_000)
        phone.sync().execute(owner, device, t0 + 62_000)
        watch.cancel()

        val last = seen.last().single()
        assertEquals(RecordingState.UPLOADED, last.recording.state)
        assertEquals(1, last.chunks)
        assertEquals(1, last.uploadedChunks)
        assertTrue(last.fullyUploaded)
        assertEquals(60_032L, last.durationMs)
    }

    @Test
    fun `a meeting stopped while its chunks are sending is confirmed by that same run`() = runTest {
        val phone = Phone()
        val id = phone.start().execute(owner, t0).recordingId
        phone.chunk(id, 100, t0 + 6_400)
        // Stop is pressed, with one more chunk closed, while the run is busy
        // sending this recording's first chunk.
        phone.transfer.during = {
            phone.chunk(id, 100, t0 + 12_800)
            phone.stop().execute(id, EndReason.STOPPED, t0 + 13_000)
        }

        val outcome = phone.sync().execute(owner, device, t0 + 14_000)

        assertEquals(SyncStatus.DONE, outcome.status)
        assertEquals(RecordingState.UPLOADED, phone.state(id))
        assertEquals(listOf(0, 1), phone.server.finalized.single().seqs)
    }

    @Test
    fun `an account switch mid-run stops the run and blames no recording`() = runTest {
        val phone = Phone()
        val id = phone.stoppedMeeting()
        phone.server.signedIn = "u2"

        val outcome = phone.sync().execute(owner, device, t0 + 70_000)

        assertEquals(SyncStatus.BLOCKED, outcome.status)
        assertNull(phone.store.byId(id)!!.lastError)
        assertEquals(RecordingState.STOPPED, phone.state(id))
    }

    @Test
    fun `a refused recording is asked about once per run`() = runTest {
        val phone = Phone()
        val bad = phone.stoppedMeeting(at = t0)
        phone.stoppedMeeting(at = t0 + 60 * 60_000)
        phone.server.refuseLinks[bad] = RecorderErrors.BAD_REQUEST

        phone.sync().execute(owner, device, t0 + 2 * 60 * 60_000)

        assertEquals(1, phone.server.linkCalls[bad])
    }

    // a flapping microphone cannot stop a meeting, or lose its gaps.

    @Test
    fun `more gaps than the server keeps are joined and never dropped`() = runTest {
        val phone = Phone()
        val id = phone.start().execute(owner, t0).recordingId
        phone.chunk(id, 100, t0 + 6_400)
        val pause = PauseRecordingUseCase(phone.store)
        val resume = ResumeRecordingUseCase(phone.store)
        // 250 drop-outs of a second, ten seconds apart, and one longer call near the end.
        repeat(250) { i ->
            val at = t0 + 10_000L + i * 10_000L
            pause.execute(id, if (i == 240) GapReason.CALL else GapReason.INTERRUPTION, at)
            resume.execute(id, at + if (i == 240) 60_000L else 1_000L)
        }
        phone.chunk(id, 100, t0 + 3_000_000)
        phone.stop().execute(id, EndReason.STOPPED, t0 + 3_001_000)

        phone.sync().execute(owner, device, t0 + 3_100_000)

        val sent = phone.server.finalized.single().gaps
        assertEquals(200, sent.size)
        assertEquals(10_000L, sent.first().startMs, "the first gap still starts where it did")
        assertEquals(2_500_000L + 1_000L, sent.last().endMs, "and the last still ends where it did")
        val original = phone.store.gaps(id)
        for (gap in original) {
            assertTrue(sent.any { it.startMs <= gap.startMs && (it.endMs ?: Long.MAX_VALUE) >= gap.endMs!! }, "every gap is inside one sent")
        }
        assertEquals(250, original.size, "the phone keeps them all")
    }

    // R7. Its own key, so the phone never calls it the 3-hour limit.
    @Test
    fun `a recording can have a thousand chunks before it stops as the limit`() = runTest {
        val phone = Phone()
        val id = phone.start().execute(owner, t0).recordingId
        repeat(999) { seq -> phone.store.openChunk(id, seq, "$id/x$seq.aac") }
        phone.store.openChunks().forEach { phone.store.closeChunk(it.recordingId, it.seq, 57, 64, "md5-57") }

        assertEquals(999, phone.begin().execute(id).seq)
        phone.store.closeChunk(id, 999, 57, 64, "md5-57")
        val refused = assertFailsWith<RecorderRefused> { phone.begin().execute(id) }
        assertEquals(RecorderErrors.CHUNK_LIMIT, refused.stringKey)
        assertEquals(
            "Recording stopped after too many pauses. It is saved and will be sent.",
            Strings.get(RecorderErrors.CHUNK_LIMIT, Language.ENGLISH),
        )
        assertFalse("3 hours" in Strings.get("recorder.notif.chunkLimit", Language.ENGLISH))

        // The server is sent `limit`, which it already takes.
        assertTrue(phone.stop().execute(id, EndReason.LIMIT, t0 + 60_000))
        assertEquals("limit", phone.store.byId(id)!!.endReason!!.id)
    }

    @Test
    fun `a title cut at the limit never splits a character`() = runTest {
        val phone = Phone()
        val id = phone.stoppedMeeting()
        // 199 letters, then an emoji whose two halves straddle the 200 limit.
        val title = "a".repeat(199) + "\uD83D\uDE00" + "tail"

        SetRecordingDetailsUseCase(phone.store).execute(id, title, null, null, null)

        assertEquals("a".repeat(199), phone.store.byId(id)!!.title)
    }
}
