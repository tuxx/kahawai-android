package com.kolktech.kahawai.playback

import com.kolktech.kahawai.data.network.dto.ClientVideoStream
import org.junit.Assert.assertEquals
import org.junit.Test

class VideoLabelsTest {

    private fun video(width: Int?, height: Int?) =
        ClientVideoStream(codec = "hevc", displayWidth = width, displayHeight = height)

    @Test
    fun `standard sizes read as their class alone`() {
        assertEquals("4K", video(3840, 2160).resolutionLabel())
        assertEquals("1440p", video(2560, 1440).resolutionLabel())
        assertEquals("1080p", video(1920, 1080).resolutionLabel())
        assertEquals("720p", video(1280, 720).resolutionLabel())
    }

    /// The bug this exists for: a 2.40:1 scope release of a UHD master is
    /// 3840 wide and 1602 tall. Bucketing on height called that "1080p",
    /// which is a false statement about the file.
    /// Measured off the real file: the Shield's decoder reports 3836x1602,
    /// not 3840 — encodes crop, so an exact threshold demoted a UHD scope
    /// release to 1440p.
    @Test
    fun `a cropped width still classes as its real class`() {
        assertEquals("4K (1602p)", video(3836, 1602).resolutionLabel())
        assertEquals("1080p", video(1916, 1080).resolutionLabel())
    }

    /// A picture a few pixels shorter than its class is the same shape
    /// trimmed, not a different one — no need to spell that out.
    @Test
    fun `a slightly trimmed height reads as its class`() {
        assertEquals("4K", video(3840, 2158).resolutionLabel())
        assertEquals("1080p", video(1920, 1072).resolutionLabel())
    }

    @Test
    fun `a scope release is classed by width and still states its real height`() {
        assertEquals("4K (1602p)", video(3840, 1602).resolutionLabel())
        assertEquals("1080p (800p)", video(1920, 800).resolutionLabel())
    }

    /// DCI 4K is wider than UHD, and a 2.39:1 crop of it is shorter again.
    @Test
    fun `DCI widths class up, not down`() {
        assertEquals("4K (1716p)", video(4096, 1716).resolutionLabel())
    }

    @Test
    fun `an unclassifiable width falls back to the true height`() {
        assertEquals("480p", video(null, 480).resolutionLabel())
        assertEquals("576p", video(720, 576).resolutionLabel())
    }

    @Test
    fun `nothing measured says nothing`() {
        assertEquals("", video(null, null).resolutionLabel())
        assertEquals("", video(0, 0).resolutionLabel())
    }
}
