package com.kolktech.kahawai.ui.player.subtitle

import org.junit.Assert.*
import org.junit.Test

class ImageSubtitleTimelineTest {
    private fun cue(time: Long, bytes: Int = 10) = ImageSubtitleCue(
        time, 1920, 1080, listOf(SubtitlePng(0, 0, ByteArray(bytes))),
    )
    private fun clear(time: Long) = ImageSubtitleCue(time, 1920, 1080, emptyList())

    @Test
    fun `an unpaced whole-track burst retains the first cue and backward seek targets`() {
        val timeline = ImageSubtitleTimeline()
        repeat(6_000) { timeline.put(cue(it * 1_000L)) }
        assertEquals(6_000, timeline.storage().cues)
        assertEquals(60_000L, timeline.storage().compressedBytes)
        assertEquals(0L, timeline.currentAt(5)?.startMs)
        assertEquals(5_900_000L, timeline.currentAt(5_900_500)?.startMs)
        assertEquals(1_000L, timeline.currentAt(1_500)?.startMs)
    }

    @Test
    fun `clear events remain active while the next visible cue is prefetched`() {
        val timeline = ImageSubtitleTimeline()
        timeline.put(cue(1_000))
        timeline.put(clear(2_000))
        timeline.put(clear(3_000))
        timeline.put(cue(4_000))
        val window = timeline.windowAt(2_500)
        assertEquals(2_000L, window.current?.startMs)
        assertTrue(window.current!!.objects.isEmpty())
        assertEquals(4_000L, window.upcoming?.startMs)
        assertNull(timeline.windowAt(0).current)
        assertEquals(1_000L, timeline.windowAt(0).upcoming?.startMs)
    }

    @Test
    fun `replayed cues preserve identity and byte accounting but corrections replace them`() {
        val timeline = ImageSubtitleTimeline()
        val first = cue(1_000)
        timeline.put(first)
        timeline.put(cue(1_000))
        assertSame(first, timeline.currentAt(1_000))
        assertEquals(ImageSubtitleStorage(1, 10), timeline.storage())
        val corrected = cue(1_000, 20)
        timeline.put(corrected)
        assertSame(corrected, timeline.currentAt(1_000))
        assertEquals(ImageSubtitleStorage(1, 20), timeline.storage())
    }

    @Test
    fun `decoded images stay bounded and prefetch is reused at presentation`() {
        val timeline = ImageSubtitleTimeline()
        repeat(1_000) { timeline.put(cue(it * 1_000L)) }
        var decodes = 0
        val decoder = DecodedSubtitleWindow { cue -> decodes++; cue.startMs }
        repeat(1_000) { i ->
            assertEquals(i * 1_000L, decoder.update(timeline.windowAt(i * 1_000L)))
            assertTrue(decoder.size <= 2)
        }
        assertEquals(1_000, decodes)
    }

    @Test
    fun `backward seeking rebuilds only the requested decoded window`() {
        val timeline = ImageSubtitleTimeline()
        repeat(100) { timeline.put(cue(it * 1_000L)) }
        val decoded = mutableListOf<Long>()
        val decoder = DecodedSubtitleWindow { cue -> decoded.add(cue.startMs); cue.startMs }
        decoder.update(timeline.windowAt(50_000))
        assertEquals(1_000L, decoder.update(timeline.windowAt(1_000)))
        assertEquals(listOf(50_000L, 51_000L, 1_000L, 2_000L), decoded)
        assertEquals(2, decoder.size)
    }
}
