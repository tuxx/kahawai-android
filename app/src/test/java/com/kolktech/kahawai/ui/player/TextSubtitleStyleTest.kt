package com.kolktech.kahawai.ui.player

import android.graphics.Color
import androidx.media3.ui.CaptionStyleCompat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/// Locks the intent: an outline, and nothing painted behind the glyphs.
/// media3's own DEFAULT is white-on-black, so a style that silently fell
/// back to it would put the box back without anything else failing.
class TextSubtitleStyleTest {

    @Test
    fun `cues are drawn with a black outline and no background`() {
        assertEquals(Color.WHITE, TEXT_SUBTITLE_STYLE.foregroundColor)
        assertEquals(CaptionStyleCompat.EDGE_TYPE_OUTLINE, TEXT_SUBTITLE_STYLE.edgeType)
        assertEquals(Color.BLACK, TEXT_SUBTITLE_STYLE.edgeColor)
        assertEquals(Color.TRANSPARENT, TEXT_SUBTITLE_STYLE.backgroundColor)
        assertEquals(Color.TRANSPARENT, TEXT_SUBTITLE_STYLE.windowColor)
    }

    @Test
    fun `it is not media3's white-on-black default`() {
        assertEquals(Color.BLACK, CaptionStyleCompat.DEFAULT.backgroundColor)
    }
}

/// The HDR variant. Cues live in the SDR UI layer that the compositor maps
/// into the HDR signal, so full-scale white is not paper white — it rides
/// up with the picture's headroom.
class HdrTextSubtitleStyleTest {

    @Test
    fun `hdr glyphs sit at BT2408 reference white, not full scale`() {
        // Opaque 0xBFBFBFBF-grey: 191/255 = 75%.
        assertEquals(0xFFBFBFBF.toInt(), TEXT_SUBTITLE_STYLE_HDR.foregroundColor)
    }

    /// The skip pill reads the same constant, so the two can't drift.
    @Test
    fun `hdr glyphs are the shared overlay reference white`() {
        assertEquals(HDR_REFERENCE_WHITE, TEXT_SUBTITLE_STYLE_HDR.foregroundColor)
    }

    @Test
    fun `it is dimmer than the SDR style but otherwise identical`() {
        assertTrue(
            (TEXT_SUBTITLE_STYLE_HDR.foregroundColor and 0xFF) <
                (TEXT_SUBTITLE_STYLE.foregroundColor and 0xFF),
        )
        assertEquals(TEXT_SUBTITLE_STYLE.edgeType, TEXT_SUBTITLE_STYLE_HDR.edgeType)
        assertEquals(TEXT_SUBTITLE_STYLE.edgeColor, TEXT_SUBTITLE_STYLE_HDR.edgeColor)
        assertEquals(TEXT_SUBTITLE_STYLE.backgroundColor, TEXT_SUBTITLE_STYLE_HDR.backgroundColor)
        assertEquals(TEXT_SUBTITLE_STYLE.windowColor, TEXT_SUBTITLE_STYLE_HDR.windowColor)
    }
}
