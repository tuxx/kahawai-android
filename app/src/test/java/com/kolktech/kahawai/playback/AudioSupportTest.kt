package com.kolktech.kahawai.playback

import androidx.media3.common.MimeTypes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/// The rule the hub negotiates against: a codec is claimed when this box can
/// play it by EITHER route, and the channel ceiling is the widest either
/// route reaches. Under-claiming costs a needless re-encode (and drags the
/// session onto the transcode path); over-claiming hands the player a track
/// it cannot render.
class AudioSupportTest {

    private val noPassthrough: (String) -> Int = { 0 }

    private fun support(
        decodable: Set<String> = emptySet(),
        passthrough: (String) -> Int = noPassthrough,
        decodeMax: Int = 0,
    ) = CapabilityProfileBuilder.audioSupport(decodable, passthrough, decodeMax)

    @Test
    fun `a decoder alone is enough`() {
        assertEquals(listOf("aac"), support(decodable = setOf(MimeTypes.AUDIO_AAC), decodeMax = 6).codecs)
    }

    /// The case that motivated this: a Shield wired to a receiver bitstreams
    /// AC3 without owning a decoder for it.
    @Test
    fun `passthrough alone is enough`() {
        val s = support(passthrough = { if (it == MimeTypes.AUDIO_AC3) 6 else 0 })
        assertEquals(listOf("ac3"), s.codecs)
    }

    @Test
    fun `neither route means the codec is not claimed`() {
        assertTrue(support().codecs.isEmpty())
    }

    /// The hub calls every DTS variant `dts`, so any one of them earns the
    /// name — a source it labels `dts` may be plain DTS.
    @Test
    fun `a DTS-HD sink claims the hub's dts`() {
        assertEquals(listOf("dts"), support(passthrough = { if (it == MimeTypes.AUDIO_DTS_HD) 8 else 0 }).codecs)
    }

    @Test
    fun `an Atmos sink claims the hub's eac3`() {
        assertEquals(listOf("eac3"), support(passthrough = { if (it == MimeTypes.AUDIO_E_AC3_JOC) 8 else 0 }).codecs)
    }

    @Test
    fun `the ceiling is the widest route, not the first`() {
        // Decoder folds down to stereo; the receiver bitstreams 7.1.
        val s = support(
            decodable = setOf(MimeTypes.AUDIO_AAC),
            passthrough = { if (it == MimeTypes.AUDIO_TRUEHD) 8 else 0 },
            decodeMax = 2,
        )
        assertEquals(8, s.maxChannels)
        assertEquals(listOf("aac", "truehd"), s.codecs)
    }

    @Test
    fun `a decode-only box reports its own output ceiling`() {
        assertEquals(6, support(decodable = setOf(MimeTypes.AUDIO_AAC), decodeMax = 6).maxChannels)
    }

    /// Nothing decodes and nothing bitstreams: the PCM ceiling describes a
    /// route this box cannot take, so it must not be reported as capacity.
    @Test
    fun `a box that can play nothing claims no width`() {
        assertEquals(0, support(decodeMax = 8).maxChannels)
    }

    @Test
    fun `a bitstream-only box reports the passthrough ceiling`() {
        val s = support(passthrough = { if (it == MimeTypes.AUDIO_AC3) 6 else 0 }, decodeMax = 8)
        assertEquals(6, s.maxChannels)
    }

    /// Names must match crates/kahawai-media/src/lib.rs normalize_audio_codec,
    /// which negotiate.rs compares verbatim — a typo here is a silent
    /// re-encode, not an error.
    @Test
    fun `every claimed name is one the hub emits`() {
        val hubNames = setOf(
            "mp3", "mpeg-audio", "aac", "vorbis", "opus", "flac",
            "ac3", "eac3", "dts", "truehd", "pcm", "ac4",
        )
        val everything = support(passthrough = { 8 }).codecs
        assertTrue(everything.isNotEmpty())
        everything.forEach { assertTrue("$it is not a hub codec name", it in hubNames) }
    }

    /// LPCM has no decoder entry and nothing to pass through, so neither
    /// probe can answer for it; claiming it would push multi-Mbps audio at a
    /// client that may not take it.
    @Test
    fun `pcm is never claimed`() {
        assertFalse("pcm" in support(passthrough = { 8 }).codecs)
    }

    @Test
    fun `the old four still come through when only they decode`() {
        val decodable = setOf(
            MimeTypes.AUDIO_AAC, MimeTypes.AUDIO_MPEG,
            MimeTypes.AUDIO_OPUS, MimeTypes.AUDIO_FLAC,
        )
        assertEquals(listOf("aac", "mp3", "opus", "flac"), support(decodable = decodable, decodeMax = 6).codecs)
    }
}
