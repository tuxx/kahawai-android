package com.kolktech.kahawai.ui.player

import com.kolktech.kahawai.data.network.dto.ClientAudioStream
import com.kolktech.kahawai.data.network.dto.SubtitleTrack
import com.kolktech.kahawai.data.network.dto.languageDisplayName

/// One row in the side panel's pickers (subtitle, audio, speed, picture
/// size). [summary] is what the Settings page shows as the row's current
/// value, which for a track is shorter than the full title + detail.
internal data class TrackOption(
    val title: String,
    val badges: List<String> = emptyList(),
    val detail: String? = null,
    val summary: String = title,
    val selected: Boolean = false,
    val enabled: Boolean = true,
)

/// The user-facing words [subtitleOption] needs. They come in from the
/// caller's resources so the mapping itself stays plain and testable.
internal data class SubtitleOptionStrings(
    val embedded: String,
    val external: String,
    val downloaded: String,
    val ocr: String,
    val burnedIn: String,
    val machine: String,
)

/// Accessibility markers worth a chip of their own. A provider puts them
/// in the release label ("English SDH", "Forced"), so they're pulled out of
/// it by word, case-insensitively.
private val SUBTITLE_BADGE_WORDS = listOf("SDH", "CC", "Forced")

internal fun subtitleBadges(label: String?): List<String> {
    val words = label.orEmpty().split(Regex("[^A-Za-z]+")).filter { it.isNotEmpty() }
    return SUBTITLE_BADGE_WORDS.filter { badge -> words.any { it.equals(badge, ignoreCase = true) } }
}

/// What the label still says once its badge words are gone, e.g. "Latin
/// American" out of "Latin American SDH". Null when nothing is left.
internal fun subtitleLabelRemainder(label: String?): String? {
    if (label.isNullOrBlank()) return null
    val remainder = label.split(Regex("\\s+"))
        .filterNot { word -> SUBTITLE_BADGE_WORDS.any { word.trim('(', ')', '[', ']', ',').equals(it, ignoreCase = true) } }
        .joinToString(" ")
        .trim(' ', '-', '|', '·', ',')
    return remainder.ifBlank { null }
}

internal fun subtitleOption(track: SubtitleTrack, selected: Boolean, strings: SubtitleOptionStrings): TrackOption {
    val title = languageDisplayName(track.language)
        ?: track.language?.takeIf { it.isNotBlank() }
        ?: subtitleLabelRemainder(track.label)
        ?: track.format.uppercase()
    val badges = subtitleBadges(track.label) + listOfNotNull(strings.machine.takeIf { track.machine })
    val origin = when (track.origin) {
        "embedded" -> strings.embedded
        "sidecar" -> strings.external
        "downloaded" -> strings.downloaded
        "ocr" -> strings.ocr
        else -> null
    }
    val detail = listOfNotNull(
        subtitleLabelRemainder(track.label)?.takeIf { it != title },
        track.format.uppercase(),
        origin,
        strings.burnedIn.takeIf { track.delivery == "burn" },
        track.note.takeIf { it.isNotBlank() },
    ).joinToString(" · ")
    return TrackOption(
        title = title,
        badges = badges,
        detail = detail.ifBlank { null },
        summary = (listOf(title) + badges).joinToString(" · "),
        selected = selected,
        // "none" can't be served to this client at all.
        enabled = track.delivery != "none",
    )
}

/// A hub-negotiated audio stream as a picker row: the language as its
/// title, codec and channel layout beneath it.
internal fun audioOption(stream: ClientAudioStream, selected: Boolean, mono: String, stereo: String): TrackOption {
    val channels = channelLabel(stream.channels, mono, stereo)
    val title = languageDisplayName(stream.language)
        ?: stream.language?.uppercase()
        ?: audioCodecName(stream.codec).orEmpty()
    return TrackOption(
        title = title,
        detail = listOfNotNull(audioCodecName(stream.codec), channels).joinToString(" · ").ifBlank { null },
        summary = listOfNotNull(title, channels).joinToString(" · "),
        selected = selected,
    )
}

/// Codec names as people know them, from either a hub codec name ("eac3")
/// or a media3 sample MIME type ("audio/eac3").
internal fun audioCodecName(raw: String?): String? {
    val name = raw?.lowercase()?.removePrefix("audio/")?.takeIf { it.isNotBlank() } ?: return null
    return when (name) {
        "aac", "mp4a-latm" -> "AAC"
        "ac3" -> "AC3"
        "eac3" -> "E-AC3"
        "eac3-joc" -> "E-AC3 Atmos"
        "ac4" -> "AC4"
        "truehd", "true-hd" -> "TrueHD"
        "dts", "vnd.dts" -> "DTS"
        "dts-hd", "vnd.dts.hd" -> "DTS-HD"
        "flac" -> "FLAC"
        "opus" -> "Opus"
        "vorbis" -> "Vorbis"
        "mp3", "mpeg" -> "MP3"
        "raw", "pcm" -> "PCM"
        else -> name.uppercase()
    }
}

internal fun channelLabel(channels: Int, mono: String, stereo: String): String? = when {
    channels <= 0 -> null
    channels == 1 -> mono
    channels == 2 -> stereo
    channels == 6 -> "5.1"
    channels == 8 -> "7.1"
    else -> "${channels}ch"
}

/// "1×", "1.25×" — no trailing zeros.
internal fun formatSpeed(speed: Float): String =
    (if (speed % 1f == 0f) speed.toInt().toString() else speed.toString().trimEnd('0')) + "×"
