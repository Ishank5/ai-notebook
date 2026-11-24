package com.humblesolutions.ainotetaker.recorder

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioRecordingConfiguration
import android.media.MediaRecorder
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.os.SystemClock
import android.util.Log
import com.humblesolutions.ainotetaker.config.RecorderConfig
import com.humblesolutions.ainotetaker.config.RecorderLimits
import com.humblesolutions.ainotetaker.model.EndReason
import com.humblesolutions.ainotetaker.model.GapReason
import com.humblesolutions.ainotetaker.model.RecorderRefused
import com.humblesolutions.ainotetaker.recording.Adts
import com.humblesolutions.ainotetaker.recording.RecorderRuntime
import com.humblesolutions.ainotetaker.recording.RecorderWarning
import com.humblesolutions.ainotetaker.recording.RecordingClock
import com.humblesolutions.ainotetaker.recording.SilenceEvent
import com.humblesolutions.ainotetaker.recording.SilenceWatch
import com.humblesolutions.ainotetaker.repository.RecordingStore
import com.humblesolutions.ainotetaker.usecase.BeginChunkUseCase
import com.humblesolutions.ainotetaker.usecase.CloseChunkUseCase
import com.humblesolutions.ainotetaker.usecase.ExtendRecordingUseCase
import com.humblesolutions.ainotetaker.usecase.HeartbeatUseCase
import com.humblesolutions.ainotetaker.usecase.InterruptRecordingUseCase
import com.humblesolutions.ainotetaker.usecase.PauseRecordingUseCase
import com.humblesolutions.ainotetaker.usecase.RecorderErrors
import com.humblesolutions.ainotetaker.usecase.RecorderStart
import com.humblesolutions.ainotetaker.usecase.ResumeRecordingUseCase
import com.humblesolutions.ainotetaker.usecase.StopRecordingUseCase
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.IOException
import java.util.concurrent.Executors
import kotlin.math.abs

/**
 * The microphone, the encoder and the chunk files, for one recording.
 *
 * ONE THREAD OWNS EVERYTHING. Microphone reads, encoding, file writes and the
 * queue's bookkeeping all happen on `tas-recorder`, in order, so there is no
 * moment where a chunk is half-closed on one thread while another writes to
 * it. Pause, Stop and the system's "your microphone was silenced" arrive as
 * commands and are handled between reads — never more than a frame late.
 *
 * NOTHING HERE DECIDES WHAT A CRASH MEANS. The engine writes; the shared use
 * cases record what was written, and the same use cases repair whatever this
 * leaves behind if the process dies in the middle of it.
 */
internal class RecorderEngine(
    context: Context,
    store: RecordingStore,
    private val files: AndroidRecordingFiles,
    runtime: RecorderRuntime,
    private val listener: Listener,
    /** the planned length's numbers; shorter only in the debug test mode. */
    private val limits: RecorderLimits = RecorderLimits.STANDARD,
) {

    /** Called on the recorder's thread; implementations hand off to their own. */
    interface Listener {
        fun onPhaseChanged()
        fun onChunkClosed()
        /** the recording is alive; push the dead man's alarm on. */
        fun onHeartbeat()
        /** capture has been paused by a fault for half a minute and is still being retried. */
        fun onCaptureLost()
        /** capture is back after [onCaptureLost]. */
        fun onCaptureBack()
        /** the phone is nearly full; the recording goes on, but may not for long. */
        fun onLowSpace()
        /** half a minute of exact zeros while recording; it goes on recording. */
        fun onMicSilent()
        /** sound again after [onMicSilent]. */
        fun onMicSound()
        /** the planned length is reached (or the cap is near); replaces TAS's minutes-left warnings. */
        fun onWarning(warning: RecorderWarning)
        /** Keep recording moved the planned end; the warning is over. */
        fun onPlanExtended()
        /**
         * [unchosen] when nobody chose the end (an error, or the service destroyed): the recording is
         * kept INTERRUPTED, to be continued, rather than stopped.
         */
        fun onFinished(recordingId: String, reason: EndReason, hadAudio: Boolean, errorKey: String?, unchosen: Boolean)
    }

    private sealed interface Command {
        data object Pause : Command
        data object Resume : Command
        /** Keep recording. */
        data object Extend : Command
        data class Stop(val reason: EndReason) : Command
        data class Silenced(val silenced: Boolean) : Command
    }

    /** Thrown when the microphone cannot be opened or keeps failing. */
    private class MicrophoneUnavailable : Exception()

    private val audioManager = context.getSystemService(AudioManager::class.java)

    private val beginChunk = BeginChunkUseCase(store, files, runtime)
    private val closeChunk = CloseChunkUseCase(store, files)
    private val extendPlan = ExtendRecordingUseCase(store)
    private val heartbeat = HeartbeatUseCase(store)
    private val pause = PauseRecordingUseCase(store)
    private val resume = ResumeRecordingUseCase(store)
    private val stop = StopRecordingUseCase(store, files, runtime)
    private val interrupt = InterruptRecordingUseCase(store, files, runtime)

    private val executor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "tas-recorder")
    }
    /*
     * THE LAST LINE, NOT THE PLAN. Everything that can fail inside the loop is
     * caught where it happens; this only keeps something nobody foresaw from
     * taking the whole app — and every other screen — down with the recorder.
     * The recording it leaves is repaired and offered for Continue like any
     * other that stopped without a Stop.
     */
    private val scope = CoroutineScope(
        SupervisorJob() + executor.asCoroutineDispatcher() +
            CoroutineExceptionHandler { _, e -> Log.e(TAG, "the recorder failed", e) },
    )
    private val commands = Channel<Command>(Channel.UNLIMITED)

    // Touched only on the recorder's thread.
    private var recordingId = ""
    private var clock = RecordingClock()
    private var microphone: AudioRecord? = null
    private var encoder: AacEncoder? = null
    private var writer: ChunkWriter? = null
    private var pausedFor: GapReason? = null
    private var silencing: AudioManager.AudioRecordingCallback? = null

    /*
     * THE MICROPHONE IS NEVER GIVEN UP ON WHILE THE SERVICE LIVES. TAS ended the meeting after about a
     * minute of failed reopens; a call, another app or an audio server restart can take longer than that. The gap
     * stays open as an interruption, the microphone is tried again every few seconds, the person is told after half
     * a minute, and it records again by itself when the microphone is free.
     */
    /** When capture went down (elapsedRealtime), or 0 while it is up. */
    private var downSince = 0L
    private var retryAt = 0L
    private var retries = 0
    private var lostAlerted = false
    /** Capture came back after a fault: the first audio ends the interruption. */
    private var resumeOnAudio = false

    /*
     * AN ENCODER THAT KEEPS FAILING. One failure builds a new encoder in place; a second within a few
     * seconds is a fault like a lost microphone: a gap, a fresh encoder tried every few seconds, and the person told
     * after half a minute of it. Rebuilt on every failure, a broken codec recorded nothing, with no gap and no word.
     */
    /** When the encoder last failed (elapsedRealtime), or 0. */
    private var lastEncoderFailure = 0L
    /** When the current run of encoder failures began (elapsedRealtime); 0 once it has worked a while. */
    private var encoderTroubleSince = 0L

    /*
     * SILENCE THE SYSTEM HANDS OVER IS NOT THE MEETING. While another app or a call has the
     * microphone, Android delivers zeros; TAS could record them as meeting audio (a zero-filled read even ended an
     * interruption). The system's own "silenced" flag is watched from before the microphone starts and checked after
     * every open (and once a second while the reads are exact zeros), it can turn an interruption into a call, and
     * only real audio ends a gap.
     *
     * EXACT ZEROS ALONE NEVER PAUSE. Some phones' noise gates hand over a quiet room as true zeros, and a pause also
     * stops the clock, so a silent room never reached its planned stop. Zeros are recorded; after half a minute of
     * them the person is asked whether another app is using the microphone ([silence]).
     */
    private val silence = SilenceWatch()
    /** chunk writes that failed in a row; see [write]. */
    private var writeFailures = 0

    /** the person has been told the phone is nearly full, until it has room again. */
    private var lowSpaceAlerted = false

    /** The system says this capture is silenced. */
    private var silenced = false
    /** When the current run of exact-zero reads began (elapsedRealtime), or 0. */
    private var zeroSince = 0L
    /** When the silenced flag was last asked for while paused (elapsedRealtime). */
    private var lastSilenceCheck = 0L

    /**
     * when the loop last went round, on the uptime clock (which, like the service's watchdog timer,
     * stops in deep sleep, so a phone asleep during a pause is not mistaken for a stuck loop).
     */
    @Volatile var lastTick: Long = SystemClock.uptimeMillis()
        private set

    /** the end has begun; nothing more will be recorded. */
    @Volatile var ending: Boolean = false
        private set

    fun start(start: RecorderStart) {
        scope.launch { run(start) }
    }

    fun pause() {
        commands.trySend(Command.Pause)
    }

    fun resume() {
        commands.trySend(Command.Resume)
    }

    /** Keep recording; ignored unless the warning is showing and the plan is below the cap. */
    fun extend() {
        commands.trySend(Command.Extend)
    }

    fun stop(reason: EndReason = EndReason.STOPPED) {
        commands.trySend(Command.Stop(reason))
    }

    private suspend fun run(start: RecorderStart) {
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
        recordingId = start.recordingId
        // the clock follows the plan the recording was started (or continued) with.
        clock = RecordingClock(priorMs = start.priorMs, stopAtMs = start.stopAtMs, limits = limits)

        var reason = EndReason.STOPPED
        var errorKey: String? = null
        var unchosen = false
        try {
            // an encoder that will not start yet is a fault to retry, like the microphone.
            encoder = newEncoder()
            publish(LiveRecorder.Phase.RECORDING)
            // a microphone that will not open yet is an interruption to wait out, not the end.
            if (encoder == null || !tryOpenMicrophone()) captureDown(GapReason.INTERRUPTION)
            reason = capture()
            // INTERRUPTED from outside is the service being destroyed: nobody chose it.
            unchosen = reason == EndReason.INTERRUPTED
        } catch (e: RecorderRefused) {
            // The chunk ceiling: a recording paused hundreds of times. Treated
            // exactly like the time limit — stop, keep everything, send it.
            // `limit` to the server, but its own words on the phone (not "3 hours").
            reason = EndReason.LIMIT
            errorKey = RecorderErrors.CHUNK_LIMIT
        } catch (e: IOException) {
            Log.e(TAG, "could not write the recording", e)
            reason = EndReason.INTERRUPTED
            unchosen = true
            errorKey = if (files.freeBytes() < LOW_SPACE_BYTES) RecorderErrors.NO_SPACE else RecorderErrors.STORAGE
        } catch (e: Throwable) {
            // kept for Continue, and continued at once by the service, which is still alive. Any
            // Throwable: TAS caught only Exception, and an Error (out of memory, a class that would not load) left
            // the recorder a zombie, "recording" in the notification with nothing running it.
            Log.e(TAG, "the recorder stopped unexpectedly", e)
            reason = EndReason.INTERRUPTED
            unchosen = true
            errorKey = RecorderErrors.START_FAILED
        }
        finish(reason, errorKey, unchosen)
    }

    /** The loop. Returns why it ended. */
    private suspend fun capture(): EndReason {
        val pcm = ByteArray(PCM_BYTES)
        var lastHeartbeat = SystemClock.elapsedRealtime()
        var lastFlush = lastHeartbeat
        var lastPublish = 0L
        var lastAudio = lastHeartbeat

        while (true) {
            lastTick = SystemClock.uptimeMillis()
            while (true) {
                val command = commands.tryReceive().getOrNull() ?: break
                when (command) {
                    is Command.Stop -> return command.reason
                    // the clock holds the rule; the queue keeps the result for a Continue.
                    Command.Extend -> if (clock.extend()) {
                        // the clock has it; a queue that would not take it costs a Continue the longer
                        // plan, never the meeting.
                        runCatching { extendPlan.execute(recordingId, clock.stopAtMs, limits) }
                            .onFailure { Log.w(TAG, "could not save Keep recording", it) }
                        publish(if (pausedFor == null) LiveRecorder.Phase.RECORDING else LiveRecorder.Phase.PAUSED)
                        runCatching { listener.onPlanExtended() }
                    }
                    Command.Pause -> if (pausedFor == null) {
                        enterPause(GapReason.USER)
                        // Released while paused, so the privacy dot goes out:
                        // "paused" should mean the room is not being heard.
                        runCatching { microphone?.stop() }
                    }
                    Command.Resume -> if (pausedFor == GapReason.USER) {
                        lastAudio = SystemClock.elapsedRealtime()
                        if (runCatching { startMicrophone() }.isSuccess || tryOpenMicrophone()) {
                            leavePause()
                            zeroSince = 0L
                            // A call that began during the pause silenced us
                            // without a change the callback would report.
                            if (isSilencedNow()) onSilenced(true)
                        } else {
                            // the microphone will not come back yet: an interruption from here,
                            // retried like any other.
                            captureDown(GapReason.INTERRUPTION)
                        }
                    }
                    is Command.Silenced -> onSilenced(command.silenced)
                }
            }

            val now = SystemClock.elapsedRealtime()
            if (now - lastHeartbeat >= RecorderConfig.HEARTBEAT_MS) {
                // a heartbeat the queue would not take is logged; the next one tries again.
                runCatching { heartbeat.execute(recordingId, System.currentTimeMillis()) }
                    .onFailure { Log.w(TAG, "could not write the heartbeat", it) }
                runCatching { listener.onHeartbeat() }
                // a full phone is the one thing that can still end a recording; say so while there is
                // time to free some space.
                val free = files.freeBytes()
                if (free < LOW_SPACE_ALERT_BYTES && !lowSpaceAlerted) {
                    lowSpaceAlerted = true
                    Log.w(TAG, "the phone is nearly full: $free bytes free")
                    runCatching { listener.onLowSpace() }
                } else if (free > LOW_SPACE_CLEAR_BYTES) {
                    lowSpaceAlerted = false
                }
                lastHeartbeat = now
            }

            if (pausedFor == GapReason.USER) {
                delay(PAUSED_POLL_MS)
                continue
            }

            // capture is down: try again on time, tell the person after half a minute, never give up.
            if (downSince != 0L) {
                if (now >= retryAt) {
                    if (encoder == null) encoder = newEncoder()
                    if (encoder != null && tryOpenMicrophone()) {
                        Log.i(TAG, "microphone back after ${now - downSince} ms")
                        captureBack()
                        lastAudio = now
                        if (silenced) onSilenced(true)
                    } else {
                        retries++
                        retryAt = now + minOf(REOPEN_BASE_MS * retries, REOPEN_MAX_MS)
                    }
                }
                if (downSince != 0L) {
                    if (!lostAlerted && now - downSince >= LOST_ALERT_MS) {
                        lostAlerted = true
                        Log.w(TAG, "microphone still unavailable after ${now - downSince} ms; still trying")
                        runCatching { listener.onCaptureLost() }
                    }
                    delay(PAUSED_POLL_MS)
                    continue
                }
            }

            // NON-BLOCKING, so a Stop is never stuck behind a read that a
            // wedged audio driver will not return from.
            val read = microphone?.read(pcm, 0, pcm.size, AudioRecord.READ_NON_BLOCKING)
                ?: AudioRecord.ERROR_DEAD_OBJECT
            val stalled = read == 0 && now - lastAudio > STALL_MS
            if (read < 0 || stalled) {
                /*
                 * The audio server restarted, the microphone was taken, or it
                 * went quiet in a way silence never does. Marked as a gap, and
                 * retried for as long as the service lives (TAS
                 * gave up after about a minute). On phones before Android 10
                 * this is also what a phone call looks like.
                 */
                Log.w(TAG, "microphone failed ($read, stalled=$stalled); reopening")
                captureDown(GapReason.INTERRUPTION)
                continue
            }
            if (read == 0) {
                delay(EMPTY_READ_WAIT_MS)
                continue
            }

            lastAudio = now
            val peak = peakOf(pcm, read)
            if (peak == 0) {
                if (zeroSince == 0L) zeroSince = now
            } else {
                zeroSince = 0L
            }
            if (pausedFor != null) {
                // real audio while paused for a call or an interruption: ask the system once a second
                // whether it still silences us, in case the callback that says it stopped never comes.
                if (peak > 0 && now - lastSilenceCheck >= SILENCE_RECHECK_MS) {
                    lastSilenceCheck = now
                    if (!isSilencedNow()) {
                        silenced = false
                        resumeOnAudio = true
                    }
                }
                // only real audio ends a call or an interruption, never the zeros of a silenced
                // microphone. Until then the reads are not the meeting, and the gap already says why.
                if (resumeOnAudio && peak > 0 && !silenced) {
                    resumeOnAudio = false
                    leavePause()
                    faultCleared()
                } else {
                    if (!lostAlerted && zeroSince != 0L && now - zeroSince >= ZERO_ALERT_MS) {
                        lostAlerted = true
                        Log.w(TAG, "the microphone has given only digital silence for ${now - zeroSince} ms")
                        runCatching { listener.onCaptureLost() }
                    }
                    continue
                }
            } else {
                // exact zeros while recording: the system is asked once a second whether it silences
                // us (its callback may never come), and only its yes pauses. Otherwise they are recorded.
                if (peak == 0 && now - lastSilenceCheck >= SILENCE_RECHECK_MS) {
                    lastSilenceCheck = now
                    if (isSilencedNow()) {
                        Log.w(TAG, "digital silence, and the system says the capture is silenced; paused")
                        onSilenced(true)
                        continue
                    }
                }
                when (silence.onAudio(peak == 0, now)) {
                    SilenceEvent.SILENT -> {
                        Log.w(
                            TAG,
                            "only digital silence for ${silence.silentForMs(now)} ms on ${Build.MANUFACTURER} " +
                                "${Build.MODEL} (Android ${Build.VERSION.RELEASE}); still recording",
                        )
                        runCatching { listener.onMicSilent() }
                    }
                    SilenceEvent.BACK -> {
                        Log.i(TAG, "sound from the microphone again")
                        runCatching { listener.onMicSound() }
                    }
                    null -> Unit
                }
            }

            val frames = try {
                encoder?.encode(pcm, read)
            } catch (e: IllegalStateException) {
                // a codec error, a codec that stopped taking audio, or one the system reclaimed. A new
                // encoder, in place; frames are whole ADTS frames, so the chunk carries on. TAS ended the meeting.
                runCatching { encoder?.release() }
                encoder = null
                val repeated = lastEncoderFailure != 0L && now - lastEncoderFailure < ENCODER_FAILURE_WINDOW_MS
                lastEncoderFailure = now
                if (encoderTroubleSince == 0L) encoderTroubleSince = now
                if (repeated) {
                    // the second failure in a few seconds: a gap, retried like a lost microphone.
                    Log.w(TAG, "the AAC encoder failed again; paused, and tried again every few seconds", e)
                    captureDown(GapReason.INTERRUPTION, since = encoderTroubleSince)
                } else {
                    Log.w(TAG, "the AAC encoder failed; building a new one", e)
                    encoder = newEncoder()
                    if (encoder == null) captureDown(GapReason.INTERRUPTION, since = encoderTroubleSince)
                }
                continue
            }
            // an encoder that has worked for a while since its last failure is trusted again.
            if (encoderTroubleSince != 0L && now - lastEncoderFailure >= ENCODER_HEALTHY_MS) {
                Log.i(TAG, "the AAC encoder has worked for ${now - lastEncoderFailure} ms since it last failed")
                encoderTroubleSince = 0L
                faultCleared()
            }
            for (frame in frames.orEmpty()) {
                write(frame)?.let { return it }
            }

            if (now - lastFlush >= RecorderConfig.FLUSH_MS) {
                // logged, not fatal. The next sync tries again, and Stop's repair seals whatever
                // reached the disk.
                runCatching { writer?.sync() }.onFailure { Log.w(TAG, "could not sync the chunk to disk", it) }
                lastFlush = now
            }
            if (now - lastPublish >= PUBLISH_MS) {
                val level = (peak / 32_768f).coerceIn(0f, 1f)
                LiveRecorder.update { it.copy(elapsedMs = clock.totalMs, level = level) }
                lastPublish = now
            }
        }
    }

    /**
     * capture failed. The recording pauses for [reason] (or a pause already open takes that reason),
     * the microphone is let go, and the loop tries again shortly.
     */
    private suspend fun captureDown(reason: GapReason, since: Long = 0L) {
        if (pausedFor == null) enterPause(reason) else if (pausedFor != reason) switchPause(reason)
        releaseMicrophone()
        val now = SystemClock.elapsedRealtime()
        if (downSince == 0L) {
            // [since] is when an encoder that keeps failing first failed, so the half minute before the
            // person is told counts from then, however often it seemed to come back in between.
            downSince = if (since in 1..now) since else now
            retries = 0
        }
        // a failing encoder is given the full wait before the next try, not a quick one.
        retryAt = now + if (since != 0L) REOPEN_MAX_MS else REOPEN_BASE_MS
    }

    /** capture works again; the first real audio ends the interruption. */
    private fun captureBack() {
        downSince = 0L
        retries = 0
        resumeOnAudio = true
        zeroSince = 0L
    }

    /** an encoder, or null when none will start yet (retried with the microphone). */
    private fun newEncoder(): AacEncoder? = try {
        AacEncoder()
    } catch (e: Exception) {
        Log.w(TAG, "no AAC encoder yet", e)
        null
    }

    /**
     * recording again after a fault; the notice about it goes. Not while the encoder is still
     * suspect: it goes once the encoder has worked a while (see the encode step).
     */
    private fun faultCleared() {
        if (encoderTroubleSince != 0L) return
        if (lostAlerted) {
            lostAlerted = false
            runCatching { listener.onCaptureBack() }
        }
    }

    /**
     * the system silenced this capture, or let it go. Silenced while recording is a pause (a call when
     * one is on); silenced while paused for an interruption makes it a call when one is on. Let go, the next real
     * audio ends the pause; a zero-filled read never does.
     */
    private suspend fun onSilenced(value: Boolean) {
        silenced = value
        if (value) {
            when (pausedFor) {
                null -> enterPause(if (inCall()) GapReason.CALL else GapReason.INTERRUPTION)
                GapReason.INTERRUPTION -> if (inCall()) switchPause(GapReason.CALL)
                else -> Unit // The person's own pause stays theirs; a call stays a call.
            }
        } else if (pausedFor == GapReason.CALL || pausedFor == GapReason.INTERRUPTION) {
            resumeOnAudio = true
        }
    }

    /**
     * One AAC frame into the current chunk. returns why to stop
     * (the cap, or the planned length left unanswered), or null to carry on.
     */
    private suspend fun write(frame: ByteArray): EndReason? {
        val header = Adts.header(frame.size)
        while (true) {
            val current = writer ?: run {
                val chunk = beginChunk.execute(recordingId)
                clock.chunkStarted()
                ChunkWriter(chunk.seq, chunk.path, files.resolve(chunk.path)).also { writer = it }
            }
            try {
                current.write(header, frame)
                writeFailures = 0
                break
            } catch (e: IOException) {
                /*
                 * the chunk is let go as it is. Its row stays open, so the next chunk (or Stop) seals
                 * it from what reached the disk, and its size always matches its file: TAS closed it with the bytes
                 * it had counted, a partial frame on disk made the two differ, and Storage refused that chunk forever.
                 * One fresh chunk is tried; a second failure in a row ends capture, and the service continues it.
                 */
                Log.w(TAG, "could not write chunk ${current.seq}; starting a fresh one", e)
                current.abandon()
                writer = null
                if (++writeFailures >= 2) throw e
            }
        }
        val tick = clock.advance()
        if (tick.warning) warn()
        tick.stop?.let { return it }
        if (tick.rotate) closeCurrentChunk()
        return null
    }

    private suspend fun closeCurrentChunk() {
        val current = writer ?: return
        writer = null
        val closed = try {
            current.close()
        } catch (e: IOException) {
            // what reached the disk is sealed by the next chunk's (or Stop's) repair, from the disk;
            // the recording carries on. TAS ended the loop here.
            Log.w(TAG, "could not close chunk ${current.seq}; it will be sealed from the disk", e)
            current.abandon()
            return
        }
        closeChunk.execute(
            recordingId, current.seq, current.path,
            closed.bytes, closed.frames, closed.md5,
            System.currentTimeMillis(),
        )
        runCatching { listener.onChunkClosed() }
    }

    private suspend fun enterPause(reason: GapReason) {
        // a run of zeros counts again once it records again.
        silence.reset()
        closeCurrentChunk()
        pause.execute(recordingId, reason, System.currentTimeMillis())
        pausedFor = reason
        publish(LiveRecorder.Phase.PAUSED)
    }

    /** already paused; the open gap takes [reason] from now. */
    private suspend fun switchPause(reason: GapReason) {
        pause.execute(recordingId, reason, System.currentTimeMillis())
        pausedFor = reason
        publish(LiveRecorder.Phase.PAUSED)
    }

    private suspend fun leavePause() {
        resume.execute(recordingId, System.currentTimeMillis())
        pausedFor = null
        publish(LiveRecorder.Phase.RECORDING)
    }

    /**
     * The end, however it came: the last frames out of the encoder, the last
     * chunk closed, the microphone released, and the recording marked stopped
     * so it uploads.
     */
    private suspend fun finish(reason: EndReason, errorKey: String?, unchosen: Boolean) {
        ending = true
        // whatever fails in here, the queue is put right and the service is told, or the recording
        // would stay "live" in this process with nothing writing it.
        var reported = false
        try {
            finishSteps(reason, errorKey, unchosen)
            reported = true
        } catch (e: Throwable) {
            Log.e(TAG, "the end of $recordingId failed part way", e)
        } finally {
            if (!reported) {
                val hadAudio = runCatching {
                    interrupt.execute(recordingId, System.currentTimeMillis(), SystemClock.elapsedRealtime())
                }.getOrDefault(true)
                runCatching { releaseMicrophone() }
                runCatching { listener.onFinished(recordingId, EndReason.INTERRUPTED, hadAudio, RecorderErrors.STORAGE, true) }
            }
            executor.shutdown()
        }
    }

    private suspend fun finishSteps(reason: EndReason, errorKey: String?, unchosen: Boolean) {
        publish(LiveRecorder.Phase.STOPPING)
        // AAC runs two frames behind; they belong to the end of the meeting.
        // Not after a pause (they are from before it) nor past the end the
        // clock called (the cap, or the planned length).
        if (pausedFor == null && errorKey == null && downSince == 0L &&
            reason != EndReason.LIMIT && reason != EndReason.PLANNED
        ) {
            try {
                for (frame in encoder?.finish().orEmpty()) {
                    if (write(frame) != null) break
                }
            } catch (e: Exception) {
                Log.w(TAG, "could not drain the encoder", e)
            }
        }
        try {
            closeCurrentChunk()
        } catch (e: Exception) {
            Log.w(TAG, "could not close the last chunk; recovery will seal it", e)
        }
        releaseMicrophone()
        encoder?.release()
        encoder = null

        val hadAudio = try {
            if (unchosen) {
                // nobody chose this end: kept for Continue, not stopped.
                interrupt.execute(recordingId, System.currentTimeMillis(), SystemClock.elapsedRealtime())
            } else {
                stop.execute(recordingId, reason, System.currentTimeMillis())
            }
        } catch (e: Exception) {
            // The queue could not be written. The recording stays marked live
            // on disk, so the next launch interrupts it and offers Continue.
            Log.e(TAG, "could not mark the recording stopped", e)
            true
        }
        Log.i(TAG, "recording $recordingId ended: $reason (audio=$hadAudio, error=$errorKey, unchosen=$unchosen)")
        runCatching { listener.onFinished(recordingId, reason, hadAudio, errorKey, unchosen) }
            .onFailure { Log.e(TAG, "could not report the end of $recordingId", it) }
    }

    @SuppressLint("MissingPermission") // Checked before the service is started.
    private fun openMicrophone() {
        val minimum = AudioRecord.getMinBufferSize(
            RecorderConfig.SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
        )
        if (minimum <= 0) throw MicrophoneUnavailable()
        val record = try {
            AudioRecord(
                // The general-purpose microphone: the system's own tuning for
                // a room, rather than the close-talk processing of a call.
                MediaRecorder.AudioSource.MIC,
                RecorderConfig.SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                // ten seconds of room (TAS had two) for the
                // moments the thread is writing a chunk's rows, syncing a file
                // or rebuilding the encoder instead of reading.
                maxOf(minimum, BUFFER_BYTES),
            )
        } catch (e: Exception) {
            throw MicrophoneUnavailable()
        }
        if (record.state != AudioRecord.STATE_INITIALIZED) {
            record.release()
            throw MicrophoneUnavailable()
        }
        microphone = record
        // the phone's own microphone, every time it is opened: a headset plugged in mid-meeting
        // would otherwise take over and record the inside of a pocket.
        preferBuiltInMicrophone(record)
        // the silenced callback BEFORE the microphone starts, so a silencing that comes with the start
        // is heard; then the flag as it is now.
        watchSilencing(record)
        startMicrophone()
        silenced = isSilencedNow()
    }

    /** the built-in microphone, when the phone lists one; otherwise the system's choice. */
    private fun preferBuiltInMicrophone(record: AudioRecord) {
        val builtIn = audioManager?.getDevices(AudioManager.GET_DEVICES_INPUTS)
            ?.firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_MIC }
            ?: return
        if (!runCatching { record.setPreferredDevice(builtIn) }.getOrDefault(false)) {
            Log.w(TAG, "could not prefer the built-in microphone")
        }
    }

    private fun startMicrophone() {
        val record = microphone ?: throw MicrophoneUnavailable()
        try {
            record.startRecording()
        } catch (e: IllegalStateException) {
            throw MicrophoneUnavailable()
        }
        if (record.recordingState != AudioRecord.RECORDSTATE_RECORDING) throw MicrophoneUnavailable()
    }

    /** a fresh microphone, or false; never throws. */
    private fun tryOpenMicrophone(): Boolean {
        releaseMicrophone()
        return try {
            openMicrophone()
            true
        } catch (e: MicrophoneUnavailable) {
            releaseMicrophone()
            false
        }
    }

    private fun releaseMicrophone() {
        silencing?.let { runCatching { audioManager?.unregisterAudioRecordingCallback(it) } }
        silencing = null
        microphone?.let { record ->
            runCatching { record.stop() }
            runCatching { record.release() }
        }
        microphone = null
    }

    /**
     * Hears the system silence this recording — a call, or an app with a
     * stronger claim on the microphone — and un-silence it again.
     *
     * Android 10+ only; before that the microphone simply fails, which the
     * loop treats as an interruption.
     */
    private fun watchSilencing(record: AudioRecord) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return
        val manager = audioManager ?: return
        val session = record.audioSessionId
        val callback = object : AudioManager.AudioRecordingCallback() {
            override fun onRecordingConfigChanged(configs: MutableList<AudioRecordingConfiguration>) {
                val mine = configs.firstOrNull { it.clientAudioSessionId == session } ?: return
                commands.trySend(Command.Silenced(mine.isClientSilenced))
            }
        }
        manager.registerAudioRecordingCallback(callback, Handler(Looper.getMainLooper()))
        silencing = callback
    }

    private fun isSilencedNow(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return false
        val session = microphone?.audioSessionId ?: return false
        return audioManager?.activeRecordingConfigurations
            ?.firstOrNull { it.clientAudioSessionId == session }
            ?.isClientSilenced == true
    }

    private fun inCall(): Boolean = when (audioManager?.mode) {
        AudioManager.MODE_IN_CALL, AudioManager.MODE_IN_COMMUNICATION -> true
        else -> false
    }

    private fun publish(phase: LiveRecorder.Phase) {
        val id = recordingId
        val paused = pausedFor
        val elapsed = clock.totalMs
        // the plan and the warning travel with every change.
        val stopAt = clock.stopAtMs
        val warning = clock.warning
        val cap = limits.capMs
        LiveRecorder.update {
            it.copy(
                phase = phase, recordingId = id, pausedFor = paused, elapsedMs = elapsed,
                stopAtMs = stopAt, capMs = cap, warning = warning,
            )
        }
        runCatching { listener.onPhaseChanged() }
    }

    /** the one warning, at the planned end or a grace window before the cap. */
    private fun warn() {
        val warning = clock.warning ?: return
        val elapsed = clock.totalMs
        LiveRecorder.update { it.copy(warning = warning, elapsedMs = elapsed) }
        runCatching { listener.onWarning(warning) }
    }

    /**
     * The loudest sample in the buffer, 0 to 32768: the meter, and the digital-silence check, since
     * a real room never reads exactly zero. Cheap enough for every read.
     */
    private fun peakOf(pcm: ByteArray, length: Int): Int {
        var peak = 0
        var i = 0
        while (i + 1 < length) {
            val sample = ((pcm[i + 1].toInt() shl 8) or (pcm[i].toInt() and 0xFF)).toShort().toInt()
            val magnitude = abs(sample)
            if (magnitude > peak) peak = magnitude
            i += 2
        }
        return peak
    }

    private companion object {
        const val TAG = "Recorder"

        /** One AAC frame's worth of 16-bit samples. */
        const val PCM_BYTES = RecorderConfig.SAMPLES_PER_FRAME * 2

        /** ten seconds of 16 kHz mono 16-bit, 320 KB (TAS: two). */
        const val BUFFER_BYTES = RecorderConfig.SAMPLE_RATE * 2 * 10

        const val PAUSED_POLL_MS = 100L
        const val EMPTY_READ_WAIT_MS = 20L
        const val PUBLISH_MS = 250L

        /** No audio at all for this long, unpaused, is a microphone that stopped. */
        const val STALL_MS = 5_000L

        const val REOPEN_BASE_MS = 500L
        /** and then every five seconds, for as long as it takes. */
        const val REOPEN_MAX_MS = 5_000L
        /** a second encoder failure this soon after the last is a fault, not a hiccup. */
        const val ENCODER_FAILURE_WINDOW_MS = 10_000L
        /** working this long since its last failure, the encoder is trusted again. */
        const val ENCODER_HEALTHY_MS = 30_000L
        /** the person hears about a microphone that is still down after this long. */
        const val LOST_ALERT_MS = 30_000L
        /** paused for a call or an interruption, still only zeros after this long: the person is told. */
        const val ZERO_ALERT_MS = 30_000L
        /** how often a paused capture hearing real audio asks whether it is still silenced. */
        const val SILENCE_RECHECK_MS = 1_000L

        const val LOW_SPACE_BYTES = 20L * 1024 * 1024
        /** the person is told below this, and again once it has gone above the second. */
        const val LOW_SPACE_ALERT_BYTES = 50L * 1024 * 1024
        const val LOW_SPACE_CLEAR_BYTES = 60L * 1024 * 1024
    }
}
