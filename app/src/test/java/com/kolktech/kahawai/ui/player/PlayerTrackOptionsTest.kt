package com.kolktech.kahawai.ui.player

import com.kolktech.kahawai.data.network.dto.SubtitleTrack
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class PlayerTrackOptionsTest {

    private val strings = SubtitleOptionStrings(
        embedded = "Embedded",
        external = "External",
        downloaded = "Downloaded",
        ocr = "OCR",
        burnedIn = "Burned in",
        machine = "Auto",
    )

    private fun track(
        language: String? = "en",
        label: String? = null,
        format: String = "srt",
        origin: String = "embedded",
        delivery: String = "text",
        machine: Boolean = false,
        note: String = "",
    ) = SubtitleTrack(
        id = 1,
        itemId = "item",
        origin = origin,
        format = format,
        language = language,
        label = label,
        machine = machine,
        delivery = delivery,
        note = note,
    )

    @Test
    fun `accessibility markers in the label become badges`() {
        assertEquals(listOf("SDH"), subtitleBadges("English SDH"))
        assertEquals(listOf("SDH", "Forced"), subtitleBadges("forced (sdh)"))
        assertEquals(emptyList<String>(), subtitleBadges("Latin American"))
        // Inside another word it's not a marker.
        assertEquals(emptyList<String>(), subtitleBadges("Ccitt"))
    }

    @Test
    fun `the label keeps what the badges don't already say`() {
        assertEquals("Latin American", subtitleLabelRemainder("Latin American SDH"))
        assertNull(subtitleLabelRemainder("SDH"))
        assertNull(subtitleLabelRemainder(" "))
    }

    @Test
    fun `a subtitle row reads language, badges, then format and origin`() {
        val option = subtitleOption(track(language = "es", label = "Latin American SDH"), selected = true, strings = strings)
        assertEquals("Spanish", option.title)
        assertEquals(listOf("SDH"), option.badges)
        assertEquals("Latin American · SRT · Embedded", option.detail)
        assertEquals("Spanish · SDH", option.summary)
    }

    @Test
    fun `burned and machine tracks say so, and undeliverable ones are disabled`() {
        val burn = subtitleOption(track(format = "pgs", delivery = "burn", machine = true), selected = false, strings = strings)
        assertEquals(listOf("Auto"), burn.badges)
        assertEquals("PGS · Embedded · Burned in", burn.detail)
        assertFalse(subtitleOption(track(delivery = "none"), selected = false, strings = strings).enabled)
    }

    @Test
    fun `a track without a language falls back to its label, then its format`() {
        assertEquals("Commentary", subtitleOption(track(language = null, label = "Commentary"), false, strings).title)
        assertEquals("ASS", subtitleOption(track(language = null, format = "ass", origin = "sidecar"), false, strings).title)
    }

    @Test
    fun `codec names come out the same from hub names and mime types`() {
        assertEquals("E-AC3", audioCodecName("eac3"))
        assertEquals("E-AC3", audioCodecName("audio/eac3"))
        assertEquals("AAC", audioCodecName("audio/mp4a-latm"))
        assertEquals("TrueHD", audioCodecName("audio/true-hd"))
        assertEquals("DTS-HD", audioCodecName("audio/vnd.dts.hd"))
        assertEquals("XYZ", audioCodecName("xyz"))
        assertNull(audioCodecName(null))
    }

    @Test
    fun `channel counts read as layouts`() {
        assertEquals("Mono", channelLabel(1, "Mono", "Stereo"))
        assertEquals("5.1", channelLabel(6, "Mono", "Stereo"))
        assertEquals("4ch", channelLabel(4, "Mono", "Stereo"))
        assertNull(channelLabel(-1, "Mono", "Stereo"))
    }

    @Test
    fun `speeds drop trailing zeros`() {
        assertEquals("1×", formatSpeed(1f))
        assertEquals("1.25×", formatSpeed(1.25f))
        assertEquals("0.5×", formatSpeed(0.5f))
    }
}
