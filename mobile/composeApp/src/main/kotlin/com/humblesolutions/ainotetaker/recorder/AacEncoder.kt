package com.humblesolutions.ainotetaker.recorder

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.os.SystemClock
import com.humblesolutions.ainotetaker.config.RecorderConfig

/**
 * Microphone PCM in, raw AAC-LC frames out: the phone's own hardware or
 * platform encoder, driven synchronously from the recorder's thread.
 *
 * FRAMES ARE RETURNED, NOT PUSHED. The caller writes each one to a chunk, and
 * starting a chunk is a database write that suspends — so the encoder hands
 * back a list and the recorder decides what to do with it, rather than calling
 * out from inside the codec loop.
 *
 * Each output buffer is exactly one access unit, 1024 samples; the ADTS header
 * that makes it a playable file is added by the caller.
 */
internal class AacEncoder {

    private val format = MediaFormat.createAudioFormat(
        MediaFormat.MIMETYPE_AUDIO_AAC,
        RecorderConfig.SAMPLE_RATE,
        RecorderConfig.CHANNELS,
    ).apply {
        setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
        setInteger(MediaFormat.KEY_BIT_RATE, RecorderConfig.BIT_RATE)
        setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16 * 1024)
    }
    private val codec: MediaCodec = open()
    private val info = MediaCodec.BufferInfo()
    private var samplesQueued = 0L

    /**
     * An encoder that says it takes 16 kHz mono, configured and started.
     *
     * NOT SIMPLY THE DEFAULT ONE. `createEncoderByType` hands back whichever
     * AAC encoder the phone lists first, and on some phones that is a
     * hardware encoder built for 44.1 and 48 kHz music that refuses — or,
     * worse, mangles — 16 kHz speech. The platform is asked for one that
     * supports this exact format, and the software encoder every Android
     * ships is tried before giving up.
     */
    private fun open(): MediaCodec {
        val names = listOfNotNull(
            runCatching { MediaCodecList(MediaCodecList.REGULAR_CODECS).findEncoderForFormat(format) }
                .getOrNull(),
            "c2.android.aac.encoder",
            "OMX.google.aac.encoder",
        ).distinct()
        var failure: Exception? = null
        for (name in names) {
            val candidate = try {
                MediaCodec.createByCodecName(name)
            } catch (e: Exception) {
                failure = e
                continue
            }
            try {
                candidate.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                candidate.start()
                return candidate
            } catch (e: Exception) {
                failure = e
                candidate.release()
            }
        }
        throw IllegalStateException("no AAC encoder takes 16 kHz mono", failure)
    }

    /** Encodes [length] bytes of 16-bit mono PCM; returns every frame that came out. */
    fun encode(pcm: ByteArray, length: Int): List<ByteArray> {
        val frames = mutableListOf<ByteArray>()
        var offset = 0
        var waits = 0
        while (offset < length) {
            val index = codec.dequeueInputBuffer(INPUT_WAIT_US)
            // A codec that takes nothing for two seconds is not coming back;
            // the recorder stops and keeps what it has, rather than spin here.
            check(index >= 0 || ++waits < MAX_INPUT_WAITS) { "the AAC encoder stopped taking audio" }
            if (index >= 0) {
                val buffer = codec.getInputBuffer(index)
                if (buffer == null) {
                    // Handed back empty, so the codec does not lose the slot.
                    codec.queueInputBuffer(index, 0, 0, presentationUs(), 0)
                    continue
                }
                buffer.clear()
                val count = minOf(buffer.remaining(), length - offset)
                buffer.put(pcm, offset, count)
                codec.queueInputBuffer(index, 0, count, presentationUs(), 0)
                samplesQueued += count / BYTES_PER_SAMPLE
                offset += count
            }
            // Always drain: a full output queue is what stops input buffers
            // coming free.
            drain(frames, 0)
        }
        return frames
    }

    /**
     * Everything still inside the codec, at the end of a recording.
     *
     * AAC runs about two frames behind its input, so without this the last
     * ~130 ms of every meeting — often "thank you, everyone" — would be lost.
     * Bounded in time: a codec that never answers must not hold Stop hostage.
     */
    fun finish(): List<ByteArray> {
        val frames = mutableListOf<ByteArray>()
        val deadline = SystemClock.elapsedRealtime() + FINISH_TIMEOUT_MS
        var queued = false
        while (!queued && SystemClock.elapsedRealtime() < deadline) {
            val index = codec.dequeueInputBuffer(INPUT_WAIT_US)
            if (index >= 0) {
                codec.queueInputBuffer(
                    index, 0, 0, presentationUs(), MediaCodec.BUFFER_FLAG_END_OF_STREAM,
                )
                queued = true
            } else {
                drain(frames, 0)
            }
        }
        while (queued && SystemClock.elapsedRealtime() < deadline) {
            if (drain(frames, OUTPUT_WAIT_US)) break
        }
        return frames
    }

    fun release() {
        runCatching { codec.stop() }
        runCatching { codec.release() }
    }

    /** Moves finished frames into [into]; true once end-of-stream has come out. */
    private fun drain(into: MutableList<ByteArray>, timeoutUs: Long): Boolean {
        while (true) {
            val index = codec.dequeueOutputBuffer(info, timeoutUs)
            if (index == MediaCodec.INFO_TRY_AGAIN_LATER) return false
            if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                checkOutput(codec.outputFormat)
                continue
            }
            // The deprecated buffers-changed signal carries no audio.
            if (index < 0) continue

            val endOfStream = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
            // The codec-specific data describes the stream for an MP4 muxer.
            // ADTS carries the same facts in every frame header, so it is dropped.
            val config = info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0
            if (!config && info.size > 0) {
                val buffer = codec.getOutputBuffer(index)
                if (buffer != null) {
                    buffer.position(info.offset)
                    buffer.limit(info.offset + info.size)
                    into += ByteArray(info.size).also { buffer.get(it) }
                }
            }
            codec.releaseOutputBuffer(index, false)
            if (endOfStream) return true
        }
    }

    /**
     * The encoder must produce what every ADTS header will claim.
     *
     * The header is written by us, not by the codec, and says 16 kHz mono.
     * An encoder that quietly produced anything else would give a recording
     * that plays as noise from start to finish — so a mismatch stops the
     * recording at its first second, where it is an error on screen, rather
     * than being found by whoever tries to transcribe it.
     */
    private fun checkOutput(output: MediaFormat) {
        val rate = output.getIntegerOrNull(MediaFormat.KEY_SAMPLE_RATE)
        val channels = output.getIntegerOrNull(MediaFormat.KEY_CHANNEL_COUNT)
        check(rate == null || rate == RecorderConfig.SAMPLE_RATE) { "the encoder produces $rate Hz" }
        check(channels == null || channels == RecorderConfig.CHANNELS) { "the encoder produces $channels channels" }
    }

    private fun MediaFormat.getIntegerOrNull(key: String): Int? =
        if (containsKey(key)) getInteger(key) else null

    private fun presentationUs(): Long = samplesQueued * 1_000_000L / RecorderConfig.SAMPLE_RATE

    private companion object {
        const val BYTES_PER_SAMPLE = 2
        const val INPUT_WAIT_US = 10_000L
        const val OUTPUT_WAIT_US = 10_000L
        const val FINISH_TIMEOUT_MS = 2_000L
        const val MAX_INPUT_WAITS = 200
    }
}
