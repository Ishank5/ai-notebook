package com.humblesolutions.ainotetaker

import com.humblesolutions.ainotetaker.config.RecorderConfig
import com.humblesolutions.ainotetaker.recording.SilenceEvent
import com.humblesolutions.ainotetaker.recording.SilenceWatch
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** R6 and R12. Exact zeros are recorded; the person is told once after half a minute of them. */
class SilenceWatchTest {

    private fun SilenceWatch.feed(silent: Boolean, fromMs: Long, toMs: Long, stepMs: Long = 64): List<SilenceEvent> =
        (fromMs..toMs step stepMs).mapNotNull { onAudio(silent, it) }

    @Test
    fun `half a minute of exact zeros is told once`() {
        val watch = SilenceWatch()
        assertEquals(30_000L, RecorderConfig.SILENT_ALERT_MS)
        assertEquals(emptyList(), watch.feed(true, 0, 29_999))
        assertEquals(SilenceEvent.SILENT, watch.onAudio(true, 30_000))
        assertEquals(emptyList(), watch.feed(true, 30_064, 600_000), "once, however long it lasts")
    }

    @Test
    fun `sound in between starts the half minute again`() {
        val watch = SilenceWatch()
        assertEquals(emptyList(), watch.feed(true, 0, 20_000))
        assertNull(watch.onAudio(false, 20_064), "nothing was told, so nothing comes back")
        assertEquals(emptyList(), watch.feed(true, 20_128, 50_000))
        assertEquals(SilenceEvent.SILENT, watch.onAudio(true, 50_128))
    }

    @Test
    fun `told again only after sound came back and went again`() {
        val watch = SilenceWatch()
        watch.feed(true, 0, 29_999)
        assertEquals(SilenceEvent.SILENT, watch.onAudio(true, 30_000))
        assertEquals(SilenceEvent.BACK, watch.onAudio(false, 31_000))
        assertNull(watch.onAudio(false, 31_064))
        assertEquals(emptyList(), watch.feed(true, 32_000, 61_999))
        assertEquals(SilenceEvent.SILENT, watch.onAudio(true, 62_000))
        assertEquals(30_000L, watch.silentForMs(62_000))
    }

    @Test
    fun `a pause starts the count again and keeps a notice already given`() {
        val watch = SilenceWatch()
        watch.feed(true, 0, 20_000)
        watch.reset()
        assertEquals(emptyList(), watch.feed(true, 100_000, 129_999), "the time paused is not counted")
        assertEquals(SilenceEvent.SILENT, watch.onAudio(true, 130_000))
        watch.reset()
        assertEquals(0L, watch.silentForMs(130_100))
        assertEquals(SilenceEvent.BACK, watch.onAudio(false, 200_000), "the notice stood through the pause")
    }
}
