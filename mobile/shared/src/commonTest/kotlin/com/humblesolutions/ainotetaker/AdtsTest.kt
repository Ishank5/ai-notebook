package com.humblesolutions.ainotetaker

import com.humblesolutions.ainotetaker.recording.Adts
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * The frame format every chunk is written in, and the repair that runs on a
 * file the app was writing when it died.
 */
class AdtsTest {

    private fun frame(payload: Int = 50): ByteArray = Adts.header(payload) + ByteArray(payload) { 7 }

    private fun frames(n: Int, payload: Int = 50): ByteArray =
        (0 until n).fold(ByteArray(0)) { acc, _ -> acc + frame(payload) }

    @Test
    fun `a header describes 16 kHz mono AAC-LC and its own length`() {
        val header = Adts.header(100)
        assertEquals(7, header.size)
        assertEquals(0xFF, header[0].toInt() and 0xFF)
        // MPEG-4, no CRC.
        assertEquals(0xF1, header[1].toInt() and 0xFF)
        // AAC-LC (profile 1), sample-rate index 8 (16 kHz), channel bit 0.
        assertEquals(0x60, header[2].toInt() and 0xFF)
        // One channel.
        assertEquals(0x40, header[3].toInt() and 0xC0)
        assertEquals(107, Adts.frameLengthAt(header + ByteArray(100), 0))
    }

    @Test
    fun `headers match what ffmpeg writes for the same frames`() {
        /*
         * AN INDEPENDENT ANSWER, not this file's own reading of the spec.
         * Taken from `ffmpeg -ar 16000 -ac 1 -c:a aac -b:a 32k -f adts`: every
         * one of its 80 headers matched this implementation, and the stream
         * rebuilt with these headers decodes, joins chunk to chunk, and — cut
         * mid-frame and repaired with validPrefix — decodes again.
         */
        val ffmpeg = mapOf(
            285 to "fff16040249ffc",
            286 to "fff1604024bffc",
            260 to "fff16040217ffc",
            250 to "fff16040203ffc",
        )
        for ((payload, expected) in ffmpeg) {
            val actual = Adts.header(payload).joinToString("") {
                (it.toInt() and 0xFF).toString(16).padStart(2, '0')
            }
            assertEquals(expected, actual, "header for a $payload-byte frame")
        }
    }

    @Test
    fun `a frame too big for the length field is refused`() {
        assertFailsWith<IllegalArgumentException> { Adts.header(0x1FFF) }
        assertFailsWith<IllegalArgumentException> { Adts.header(0) }
    }

    @Test
    fun `a file cut off mid-frame keeps every whole frame before it`() {
        val whole = frames(3)
        val cut = whole + frame().copyOf(20)
        assertEquals(Adts.Prefix(bytes = whole.size, frames = 3), Adts.validPrefix(cut))
    }

    @Test
    fun `a header with no body after it is not a frame`() {
        val whole = frames(2)
        val cut = whole + Adts.header(50)
        assertEquals(Adts.Prefix(bytes = whole.size, frames = 2), Adts.validPrefix(cut))
    }

    @Test
    fun `fewer than seven bytes at the end are ignored`() {
        val whole = frames(4)
        assertEquals(4, Adts.validPrefix(whole + byteArrayOf(0xFF.toByte(), 0xF1.toByte())).frames)
    }

    @Test
    fun `an empty or foreign file has nothing to keep`() {
        assertEquals(Adts.Prefix(0, 0), Adts.validPrefix(ByteArray(0)))
        assertEquals(Adts.Prefix(0, 0), Adts.validPrefix(ByteArray(500) { 1 }))
    }

    @Test
    fun `frames of different sizes are walked by their own lengths`() {
        val mixed = frame(10) + frame(300) + frame(64)
        assertEquals(Adts.Prefix(mixed.size, 3), Adts.validPrefix(mixed))
    }

    @Test
    fun `durations are exact at 16 kHz`() {
        // 1024 samples at 16 kHz is 64 ms precisely.
        assertEquals(64L, Adts.durationMs(1))
        assertEquals(60_032L, Adts.durationMs(938))
        // Ninety minutes is a whole number of frames.
        assertEquals(5_400_000L, Adts.durationMs(84_375))
    }
}
