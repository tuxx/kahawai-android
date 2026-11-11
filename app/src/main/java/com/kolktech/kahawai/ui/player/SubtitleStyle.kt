@file:OptIn(UnstableApi::class)

package com.kolktech.kahawai.ui.player

import android.graphics.Color
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.CaptionStyleCompat

/// How "text" delivery (SRT/VTT through media3's SubtitleView) is drawn:
/// white glyphs with a black outline and no box behind them, which is what
/// every desktop player settles on (VLC, mpv) because it stays legible over
/// both a snowfield and a night scene without covering picture.
///
/// Without this the cues came up white on a solid black rectangle. That is
/// not something this app chose: PlayerView asks its SubtitleView for the
/// platform's captioning style (`setUserDefaultStyle()` in PlayerView's
/// constructor), and on a device where the viewer has never opened the
/// system captioning settings — the out-of-the-box state on Android TV —
/// that resolves to `CaptionStyleCompat.DEFAULT`, whose background colour
/// is `Color.BLACK`. The box is the platform default showing through, not
/// styling that came with the subtitle.
///
/// Deliberately fixed rather than derived from the system style: this is
/// the one caption appearance the app renders in every mode, and a device
/// caption preference that reintroduced a background here would disagree
/// with the ASS and bitmap overlays, which draw their own way and have no
/// notion of it.
///
/// Embedded cue styling is left enabled, so a track that genuinely carries
/// its own colours (a flattened ASS rendition, say) still overrides these.
internal val TEXT_SUBTITLE_STYLE = CaptionStyleCompat(
    Color.WHITE,
    // No box behind the glyphs, and none behind the line's full width
    // either — windowColor is the second, wider one.
    Color.TRANSPARENT,
    Color.TRANSPARENT,
    CaptionStyleCompat.EDGE_TYPE_OUTLINE,
    Color.BLACK,
    // null keeps whatever typeface the view already has.
    null,
)

/// The white that anything drawn over the video uses while an HDR picture
/// is on screen: subtitle cues here, and the skip pill (SkipPill and the
/// controller's kw_skip).
///
/// The app's UI is an SDR layer, which the compositor maps into the HDR
/// signal being sent to the panel. Full-scale white there is not "paper
/// white": it is the top of the SDR range mapped into a picture whose
/// highlights run to a thousand nits and beyond, which is how a line of
/// dialogue, or a pill-shaped button, ends up glaring off a dark scene
/// like a torch.
///
/// 75% is where ITU-R BT.2408 puts HDR reference white (203 cd/m² — the
/// level graphics, captions and titles are meant to sit at). 0xBF = 191 =
/// 75% of full scale, written as a literal because Color.rgb() is a real
/// platform call and the styles here are top-level vals: a JVM unit test
/// that so much as reads one would die initialising it.
internal const val HDR_REFERENCE_WHITE: Int = 0xFFBFBFBF.toInt()

/// The same style, dimmed to [HDR_REFERENCE_WHITE] for while an HDR
/// picture is on screen. The outline stays pure black: it is there to
/// separate the text from whatever is behind it, and black has no
/// brightness to give back.
internal val TEXT_SUBTITLE_STYLE_HDR = CaptionStyleCompat(
    HDR_REFERENCE_WHITE,
    Color.TRANSPARENT,
    Color.TRANSPARENT,
    CaptionStyleCompat.EDGE_TYPE_OUTLINE,
    Color.BLACK,
    null,
)
