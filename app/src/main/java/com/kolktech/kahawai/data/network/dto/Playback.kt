package com.kolktech.kahawai.data.network.dto

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonClassDiscriminator

/// Mirrors `VideoCap` in crates/kahawai-core/src/media.rs.
@Serializable
data class VideoCap(
    val codec: String,
    val maxProfile: String? = null,
    val maxLevel: String? = null,
)

/// Mirrors `TargetDuration` (`#[serde(tag = "mode")]`). Required on
/// [CapabilityProfile] — there is no default that is right for every
/// client. ExoPlayer times an idle playlist out at 3.5x the declared
/// value, so `Accurate` — the measured keyframe-bound truth — is what this
/// client needs; the old constant-and-violate `Ignore` behaviour is what
/// caused that hang in the first place.
@OptIn(ExperimentalSerializationApi::class)
@Serializable
@JsonClassDiscriminator("mode")
sealed class TargetDuration {
    @Serializable
    @SerialName("ignore")
    object Ignore : TargetDuration()

    @Serializable
    @SerialName("accurate")
    object Accurate : TargetDuration()

    @Serializable
    @SerialName("short")
    data class Short(val maxSecs: Int) : TargetDuration()
}

/// Mirrors `CapabilityProfile` in crates/kahawai-core/src/media.rs — sent
/// verbatim on every session start and on every item QUERY. The hub
/// negotiates direct/remux/transcode per stream from it.
@Serializable
data class CapabilityProfile(
    val containers: List<String>,
    val video: List<VideoCap>,
    val audio: List<String>,
    val maxAudioChannels: Int,
    val maxHeight: Int? = null,
    val maxFps: Int? = null,
    val hdr: Boolean,
    val maxBandwidthKbps: Int? = null,
    val assRender: Boolean,
    val graphicsOverlay: Boolean,
    // The one text rendering path — a converted SRT, a flattened ASS and
    // an OCR-derived track all deliver as WebVTT, which Media3's built-in
    // TextRenderer always reads, so this stays true.
    val vttRender: Boolean = true,
    val targetDuration: TargetDuration,
)

/// `POST /api/v1/playback/sessions`. `libraryId` is required — the hub
/// rejects a body without it — because a session is opened against a
/// library-scoped item, not a bare id.
///
/// `mediaEntryId`/`sourceId` pin a specific physical source; leaving both
/// null lets the hub negotiate one. `resumeSourceFingerprint` carries the
/// fingerprint a previous session reported, so the hub can tell whether
/// the resume position still refers to the same physical file.
@Serializable
data class StartSessionRequest(
    val itemId: String,
    val libraryId: String,
    val mode: String? = null,
    val profile: CapabilityProfile? = null,
    val startMs: Long = 0,
    val audioTrack: Int = 0,
    val videoTrack: Int = 0,
    val subtitleTrack: Long? = null,
    val mediaEntryId: String? = null,
    val sourceId: Int? = null,
    val resume: Boolean = false,
    val resumeSourceFingerprint: String? = null,
)

/// `QUERY /api/v1/catalogue/libraries/{library}/items/{item}` (RFC 10008)
/// request body — the same inputs a session start takes, minus everything
/// that only matters once actually playing.
@Serializable
data class ItemQueryRequest(
    val profile: CapabilityProfile? = null,
    val audioTrack: Int = 0,
    val videoTrack: Int = 0,
    val subtitleTrack: Long? = null,
    val mode: String? = null,
    val mediaEntryId: String? = null,
    val sourceId: Int? = null,
    /// source_id -> the audio stream index THAT source would be played
    /// with, so the hub ranks each copy on the audio you would actually
    /// get rather than on index 0. A source left out of the map is ranked
    /// on [audioTrack]. Keys are stringified source ids, because JSON
    /// object keys are strings.
    ///
    /// An index only means anything inside one source, so this never
    /// travels to a session start: START takes the chosen source and ITS
    /// resolved index (see [StartSessionRequest.mediaEntryId]).
    val sourceAudioTracks: Map<String, Int>? = null,
)

/// The per-stream verdict for a running session: "direct"/"remux"/
/// "transcode" per stream, plus the subtitle tier for each track.
@Serializable
data class PlaybackStreams(
    val video: String = "",
    val audio: String = "",
    val cost: String? = null,
    val subtitles: List<SubtitleVerdict> = emptyList(),
)

/// `stream_url` is a byte-range GET for `mode == "direct"`, or an HLS
/// master playlist (`.m3u8`) for `remux`/`transcode`.
///
/// `effectiveStartMs` is where the hub actually started, which is not
/// necessarily the requested `startMs`: a resume against a source whose
/// fingerprint no longer matches starts from zero instead of somewhere
/// meaningless. `sourceFingerprint` is what to send back on the next
/// resume; `segments` here supersede a QUERY's, since this is the source
/// that was actually accepted.
@Serializable
data class StartSessionResponse(
    val sessionId: String,
    val mode: String,
    val size: Long? = null,
    val durationMs: Long? = null,
    val partBaseMs: Long = 0,
    val parts: Int = 1,
    val contentType: String,
    val streamUrl: String,
    val effectiveStartMs: Long = 0,
    val sourceId: Int = 0,
    val sourceFingerprint: String = "",
    val mediaEntryId: String? = null,
    val libraryItemIds: List<String> = emptyList(),
    val streams: PlaybackStreams? = null,
    val replayGain: ReplayGain? = null,
    val segments: List<Segment> = emptyList(),
    /// The unified track list computed against THIS session's effective
    /// profile and negotiated source. The item QUERY's listing reflects
    /// the profile at page load; after a capability-masked restart the two
    /// can disagree, so this is what the player refreshes its track list
    /// from once a session actually starts.
    val subtitleListing: List<SubtitleTrack> = emptyList(),
)

@Serializable
data class SeekRequest(
    val positionMs: Long,
    val audioTrack: Int? = null,
    val videoTrack: Int? = null,
    val subtitleTrack: Long? = null,
)

/// Seek-restart response: same session id/URLs, a new `partBaseMs` — the
/// client re-attaches to the same playlist URL, now serving from the new
/// offset.
@Serializable
data class SeekResponse(
    val partBaseMs: Long = 0,
    val streams: PlaybackStreams? = null,
)

@Serializable
data class ProgressRequest(val positionMs: Long)

@Serializable
data class ProgressResponse(
    val positionMs: Long = 0,
    val played: Boolean = false,
)

/// One chapter of a file, on the ITEM's timeline. `endMs` is only what the
/// container states — Matroska usually leaves it out and means "until the
/// next one starts".
@Serializable
data class Chapter(
    val startMs: Long,
    val endMs: Long? = null,
    val title: String? = null,
)

/// One recap/intro/credits boundary the hub's background sweep found.
/// `kind` is one of "recap"|"intro"|"credits"; `source` names which
/// analyzer answered ("chapter"|"chromaprint"|"blackframe") and is
/// display-only here.
@Serializable
data class Segment(
    val kind: String,
    val startMs: Long,
    val endMs: Long,
    val source: String,
)
