package com.humblesolutions.ainotetaker.recording

import com.humblesolutions.ainotetaker.config.RecorderConfig

/**
 * AAC in ADTS framing: the format every chunk file is written in.
 *
 * CHOSEN FOR WHAT HAPPENS WHEN THE APP DIES MID-WRITE. Every frame carries its
 * own 7-byte header, so a file cut off anywhere still plays up to its last
 * complete frame. A normal `.m4a` writes its index last and is unplayable if
 * the process dies first — which, on a phone that runs out of battery during a
 * meeting, is exactly the recording that matters most.
 *
 * It is also why chunks join so simply on the server: ADTS files concatenate
 * byte for byte into one valid stream, so Cloud Storage's compose operation can
 * assemble a meeting without downloading it.
 *
 * SHARED, NOT PER PLATFORM. Android's encoder and iOS's converter both hand
 * back raw AAC frames; this writes the header both of them need, and repairs
 * the file both of them might leave half-written.
 */
object Adts {

    const val HEADER_BYTES = 7

    /** Audio object type 2 (AAC-LC); the header stores it minus one. */
    private const val PROFILE_AAC_LC = 1

    /** The 13-bit frame-length field's ceiling. */
    private const val MAX_FRAME_BYTES = 0x1FFF

    private val SAMPLE_RATES = intArrayOf(
        96_000, 88_200, 64_000, 48_000, 44_100, 32_000,
        24_000, 22_050, 16_000, 12_000, 11_025, 8_000, 7_350,
    )

    fun sampleRateIndex(sampleRate: Int): Int {
        val index = SAMPLE_RATES.indexOf(sampleRate)
        require(index >= 0) { "ADTS has no index for $sampleRate Hz" }
        return index
    }

    /**
     * The header to write in front of one raw AAC frame of [payloadBytes].
     *
     * No CRC (`protection_absent = 1`), so it is always seven bytes. Buffer
     * fullness is `0x7FF`, the value that means "variable bitrate".
     */
    fun header(
        payloadBytes: Int,
        sampleRate: Int = RecorderConfig.SAMPLE_RATE,
        channels: Int = RecorderConfig.CHANNELS,
    ): ByteArray {
        val frameLength = payloadBytes + HEADER_BYTES
        require(payloadBytes > 0 && frameLength <= MAX_FRAME_BYTES) {
            "an AAC frame of $payloadBytes bytes does not fit an ADTS header"
        }
        val rate = sampleRateIndex(sampleRate)
        return byteArrayOf(
            0xFF.toByte(),
            // Sync word's last four bits, MPEG-4, layer 0, no CRC.
            0xF1.toByte(),
            ((PROFILE_AAC_LC shl 6) or (rate shl 2) or (channels shr 2)).toByte(),
            (((channels and 0x3) shl 6) or (frameLength shr 11)).toByte(),
            ((frameLength shr 3) and 0xFF).toByte(),
            (((frameLength and 0x7) shl 5) or 0x1F).toByte(),
            0xFC.toByte(),
        )
    }

    /**
     * The length of the frame starting at [offset], or -1 when there is no
     * valid header there — including when fewer than seven bytes remain.
     */
    fun frameLengthAt(bytes: ByteArray, offset: Int, size: Int = bytes.size): Int {
        if (offset < 0 || offset + HEADER_BYTES > size) return -1
        val b0 = bytes[offset].toInt() and 0xFF
        val b1 = bytes[offset + 1].toInt() and 0xFF
        if (b0 != 0xFF || (b1 and 0xF0) != 0xF0) return -1
        val length = ((bytes[offset + 3].toInt() and 0x03) shl 11) or
            ((bytes[offset + 4].toInt() and 0xFF) shl 3) or
            ((bytes[offset + 5].toInt() and 0xE0) shr 5)
        return if (length <= HEADER_BYTES) -1 else length
    }

    /** How much of a file is whole frames, and how many. */
    data class Prefix(val bytes: Int, val frames: Int)

    /**
     * The longest run of complete frames from the start of [bytes].
     *
     * RECOVERY USES THIS. A file the app was writing when it died ends in a
     * partial frame, or a header with no body; everything before that is good
     * audio. The platform truncates the file to [Prefix.bytes], checksums it,
     * and closes the chunk — so a crash costs the last fraction of a second,
     * not the minute.
     */
    fun validPrefix(bytes: ByteArray, size: Int = bytes.size): Prefix {
        var offset = 0
        var frames = 0
        while (true) {
            val length = frameLengthAt(bytes, offset, size)
            if (length < 0 || offset + length > size) break
            offset += length
            frames++
        }
        return Prefix(bytes = offset, frames = frames)
    }

    /** Duration of [frames] AAC frames, to the millisecond. */
    fun durationMs(frames: Long, sampleRate: Int = RecorderConfig.SAMPLE_RATE): Long =
        frames * RecorderConfig.SAMPLES_PER_FRAME * 1000 / sampleRate
}
