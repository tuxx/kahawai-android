package com.kolktech.kahawai.playback

import com.kolktech.kahawai.data.network.dto.ClientVideoStream

/// Resolution classes, widest first, with the height each one is named
/// after. Classed by WIDTH: cinema aspect ratios crop the picture's height,
/// so a 2.40:1 scope release of a UHD master is 3840x1602 — 4K by every
/// consumer definition, and by width, but only "1080p" to anything that
/// buckets on height alone.
/// Thresholds sit well below each nominal width because real encodes crop:
/// a UHD scope release measured 3836 wide, not 3840, and an exact threshold
/// demoted it a whole class. Roughly 90% of nominal tolerates cropping
/// without letting a genuinely smaller picture claim the class above it.
private val CLASSES = listOf(
    3400 to ("4K" to 2160),
    2300 to ("1440p" to 1440),
    1700 to ("1080p" to 1080),
    1150 to ("720p" to 720),
    // Nothing below 720p is classed on width: NTSC and PAL standard
    // definition are both 720 wide and differ only in height (480 vs 576),
    // so the height is the only thing that tells them apart. Those print
    // their true height instead.
)

/// How a file's picture size reads on an item page.
///
/// The class alone would state something false about a scope release, whose
/// height is nothing like the one its class is named after — so the real
/// height comes along whenever it differs. "4K" for 3840x2160, "4K (1602p)"
/// for 3840x1602, and a bare "1602p" when the width is unknown and there is
/// nothing to classify on.
fun ClientVideoStream.resolutionLabel(): String {
    val width = displayWidth ?: 0
    val height = displayHeight ?: 0
    val (name, nominal) = CLASSES.firstOrNull { width >= it.first }?.second
        ?: return if (height > 0) "${height}p" else ""
    // A picture only a little shorter than its class is the same shape with
    // its edges trimmed; a scope release is a different shape entirely, and
    // that is the case worth spelling out.
    val cropped = height in (nominal * 95 / 100)..nominal
    return if (height <= 0 || height == nominal || cropped) name else "$name (${height}p)"
}
