package com.kolktech.kahawai.data.repository

import com.kolktech.kahawai.data.network.ApiClient
import com.kolktech.kahawai.data.network.ApiService
import com.kolktech.kahawai.data.network.dto.CapabilityProfile
import com.kolktech.kahawai.data.network.dto.FontsResponse
import com.kolktech.kahawai.data.network.dto.ItemDetail
import com.kolktech.kahawai.data.network.dto.ItemQueryRequest
import com.kolktech.kahawai.data.network.dto.ProgressRequest
import com.kolktech.kahawai.data.network.dto.SeekRequest
import com.kolktech.kahawai.data.network.dto.SeekResponse
import com.kolktech.kahawai.data.network.dto.StartSessionRequest
import com.kolktech.kahawai.data.network.dto.StartSessionResponse
import com.kolktech.kahawai.data.network.dto.SubtitleTrack
import com.kolktech.kahawai.data.network.dto.toDetail

class PlaybackRepository(private val api: ApiService = ApiClient.apiService()) {
    /// [libraryId] is required by the hub: a session is opened against a
    /// library-scoped item, not a bare id.
    ///
    /// [resumeSourceFingerprint] is what a previous session reported for
    /// the file it played. Sending it back lets the hub tell whether
    /// [startMs] still refers to the same physical source — when it does
    /// not, it starts from zero and says so in
    /// [StartSessionResponse.effectiveStartMs] rather than dropping the
    /// viewer somewhere meaningless.
    suspend fun startSession(
        itemId: String,
        libraryId: String,
        profile: CapabilityProfile,
        startMs: Long = 0,
        audioTrack: Int = 0,
        videoTrack: Int = 0,
        subtitleTrack: Long? = null,
        mediaEntryId: String? = null,
        resumeSourceFingerprint: String? = null,
    ): StartSessionResponse = api.startSession(
        StartSessionRequest(
            itemId = itemId,
            libraryId = libraryId,
            profile = profile,
            startMs = startMs,
            audioTrack = audioTrack,
            videoTrack = videoTrack,
            subtitleTrack = subtitleTrack,
            mediaEntryId = mediaEntryId,
            resume = startMs > 0,
            resumeSourceFingerprint = resumeSourceFingerprint,
        ),
    )

    /// [audioTrack] switches the muxed audio during the restart — the only
    /// way to change audio on a remux/transcode session, where the hub has
    /// already muxed down to the single stream it was asked for.
    suspend fun seek(
        sessionId: String,
        positionMs: Long,
        subtitleTrack: Long? = null,
        audioTrack: Int? = null,
    ): SeekResponse = api.seek(
        sessionId,
        SeekRequest(positionMs = positionMs, subtitleTrack = subtitleTrack, audioTrack = audioTrack),
    )

    /// Track list + computed delivery for this client's declared [profile],
    /// read off the same QUERY that negotiates the source `startSession`
    /// will start — a separate listing route could resolve a different
    /// source than the session did, which is why the hub does not have one.
    suspend fun subtitles(libraryId: String, itemId: String, profile: CapabilityProfile): List<SubtitleTrack> =
        itemQuery(libraryId, itemId, profile).negotiated?.subtitles ?: emptyList()

    /// The full QUERY answer — subtitles, skip segments and chapters
    /// together in the one round trip the hub designed them to share (see
    /// [subtitles], which only keeps the first of the three).
    suspend fun itemQuery(libraryId: String, itemId: String, profile: CapabilityProfile): ItemDetail =
        api.itemQuery(libraryId, itemId, ItemQueryRequest(profile = profile)).toDetail(libraryId)

    /// Embedded ASS fonts belong to the negotiated source, so they are a
    /// property of a running session rather than of the item.
    suspend fun fonts(sessionId: String): FontsResponse = api.fonts(sessionId)

    suspend fun reportProgress(sessionId: String, positionMs: Long) {
        api.progress(sessionId, ProgressRequest(positionMs = positionMs))
    }

    suspend fun endSession(sessionId: String) {
        api.endSession(sessionId)
    }
}
