package com.kolktech.kahawai.ui.player

import com.kolktech.kahawai.data.network.dto.PlaybackStreams
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PlaybackStatsTest {

    private fun snapshot(
        atNs: Long = 0,
        renderedFrames: Int = 0,
        droppedFrames: Int = 0,
        bytesTransferred: Long = 0,
        streams: PlaybackStreams? = null,
    ) = PlaybackStatsSnapshot(
        atNs = atNs,
        mode = "transcode",
        streams = streams,
        videoFormat = null,
        audioFormat = null,
        sourceAudio = null,
        subtitle = null,
        videoDecoder = null,
        audioDecoder = null,
        renderedFrames = renderedFrames,
        droppedFrames = droppedFrames,
        skippedFrames = 0,
        maxConsecutiveDropped = 0,
        frameOffsetUs = 0,
        frameOffsetCount = 0,
        bytesTransferred = bytesTransferred,
        bandwidthEstimateBps = 0,
        fileBitrateBps = null,
        bufferedMs = 12_300,
        frameTiming = null,
    )

    private val display = DisplayStats(refreshRateHz = 60f, viewWidth = 1920, viewHeight = 1080)

    private fun List<StatLine>.value(label: String) = first { it.label == label }.value

    @Test
    fun `steady 24 fps cadence has no jitter`() {
        val times = LongArray(10) { it * 41_666_667L }
        val timing = frameTimingOf(times)!!
        assertEquals(41.67, timing.meanMs, 0.01)
        assertEquals(0.0, timing.jitterMs, 0.01)
        assertEquals(41.67, timing.maxMs, 0.01)
    }

    @Test
    fun `a dropped frame shows as a doubled interval`() {
        val times = longArrayOf(0, 16_666_667, 33_333_333, 66_666_667, 83_333_333)
        assertEquals(33.33, frameTimingOf(times)!!.maxMs, 0.01)
    }

    @Test
    fun `a pause is not a frame`() {
        val times = longArrayOf(0, 16_666_667, 33_333_333, 5_000_000_000, 5_016_666_667)
        assertEquals(16.67, frameTimingOf(times)!!.maxMs, 0.01)
    }

    @Test
    fun `too few frames say nothing`() {
        assertNull(frameTimingOf(longArrayOf(0, 16_666_667)))
    }

    @Test
    fun `rates are per second between readings`() {
        val lines = statsLines(
            snapshot(atNs = 0, renderedFrames = 100, bytesTransferred = 0),
            snapshot(atNs = 2_000_000_000, renderedFrames = 148, bytesTransferred = 2_500_000),
            display,
        )
        assertEquals("24.00 rendered · display 60 Hz", lines.value("FPS"))
        assertEquals("10.0 Mbps net", lines.value("Bitrate"))
    }

    @Test
    fun `a counter reset by a re-attach counts from zero`() {
        val lines = statsLines(
            snapshot(atNs = 0, renderedFrames = 5_000, droppedFrames = 40),
            snapshot(atNs = 1_000_000_000, renderedFrames = 24, droppedFrames = 1),
            display,
        )
        assertEquals("24.00 rendered · display 60 Hz", lines.value("FPS"))
        assertEquals("1 (1.0/s) · skipped 0 · max run 0", lines.value("Dropped"))
    }

    @Test
    fun `session line names the hub's verdict`() {
        val streams = PlaybackStreams(video = "transcode", audio = "copy", cost = "video_encode")
        val lines = statsLines(snapshot(streams = streams), snapshot(streams = streams), display)
        assertEquals("transcode · video encode", lines.value("Session"))
        assertEquals("transcode", lines.value("Video"))
        assertEquals("copy", lines.value("Audio"))
    }

    @Test
    fun `software decoders are marked`() {
        assertEquals("c2.android.avc.decoder (sw)", decoderLabel("c2.android.avc.decoder"))
        assertEquals("c2.qti.hevc.decoder", decoderLabel("c2.qti.hevc.decoder"))
    }
}
