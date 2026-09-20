package com.kolktech.kahawai.data.network.dto

import java.util.Locale
import kotlinx.serialization.Serializable

/// `TrackListing` in crates/kahawai-hub/src/tracks.rs — one subtitle track
/// plus what it means for THIS client. `delivery` is one of
/// "text"|"ass"|"overlay"|"burn"|"none", computed per request from the
/// declared capability bits and never stored, so the same track lists
/// differently for different clients. `deletable` is computed the same way
/// and is a fact about who is asking: only a `downloaded` track qualifies,
/// and only for whoever spent the provider quota on it (or an admin).
@Serializable
data class SubtitleTrack(
    val id: Long,
    val itemId: String,
    val origin: String, // "embedded" | "sidecar" | "downloaded" | "ocr"
    val streamIndex: Long? = null,
    val format: String, // srt/vtt/ass/ssa/pgs/vobsub/dvdsub/...
    val language: String? = null,
    val label: String? = null,
    val machine: Boolean = false,
    val derivedFrom: Long? = null,
    val delivery: String,
    val note: String = "",
    val deletable: Boolean = false,
)

/// Which physical rendition a subtitle search/download is bound to.
/// Ownership is the selected rendition and version, not the logical
/// title: the hub revalidates this before spending provider quota. Read
/// it off a QUERY response ([ItemDetail.subtitleSource]) and hand it back
/// unchanged.
@Serializable
data class SubtitleSource(
    val mediaEntryId: String,
    val sourceVersion: String,
)

@Serializable
data class SubtitleSearchRequest(
    val source: SubtitleSource,
    val languages: List<String> = emptyList(),
)

/// What the provider has left. `perAccount` distinguishes the viewer's own
/// OpenSubtitles account from the hub's shared one.
@Serializable
data class Quota(
    val remaining: Int? = null,
    val total: Int? = null,
    val resetsInSecs: Long? = null,
    val perAccount: Boolean = false,
)

@Serializable
data class SubtitleCandidate(
    val provider: String,
    val fileId: String,
    val language: String? = null,
    val releaseName: String? = null,
    val uploader: String? = null,
    val rating: Double? = null,
    val downloads: Int = 0,
    val fps: Double? = null,
    val hashMatch: Boolean = false,
)

@Serializable
data class SubtitleSearchResponse(
    val candidates: List<SubtitleCandidate> = emptyList(),
    val quota: Quota = Quota(),
)

@Serializable
data class SubtitleDownloadRequest(
    val source: SubtitleSource,
    val fileId: String,
    val language: String? = null,
)

@Serializable
data class SubtitleDownloadResponse(
    val trackId: Long,
    val quota: Quota = Quota(),
)

/// "English", not "en" — the hub only sends the raw ISO code, so the full
/// name is resolved on-device. `label` (a downloaded track's provider
/// release name, e.g. "SDH"/"Forced") and `note` (a technical delivery
/// caveat, e.g. "burned in — restarts with a video encode") tack on when
/// present. Shared by every subtitle picker (Detail screen, the player's
/// CC menu) so they read the same everywhere.
fun SubtitleTrack.displayLabel(): String {
    val languageName = language
        ?.let { code -> runCatching { Locale.forLanguageTag(code).getDisplayLanguage(Locale.ENGLISH) }.getOrNull() }
        ?.takeIf { it.isNotBlank() && !it.equals(language, ignoreCase = true) }
    val base = listOfNotNull(languageName ?: language, label?.takeIf { it.isNotBlank() })
        .joinToString(" ")
        .ifBlank { format.uppercase() }
    return if (note.isNotBlank()) "$base ($note)" else base
}

/// `GET /api/v1/playback/sessions/{id}/fonts` — embedded font names for ASS
/// tracks; bytes are fetched per-index from the same session. Fonts are a
/// property of the negotiated source, so they hang off the session now
/// rather than off the item.
@Serializable
data class FontsResponse(val fonts: List<String> = emptyList())
