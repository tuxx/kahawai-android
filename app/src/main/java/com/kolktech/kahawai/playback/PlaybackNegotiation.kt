package com.kolktech.kahawai.playback

import com.kolktech.kahawai.data.network.dto.CapabilityProfile
import com.kolktech.kahawai.data.network.dto.ItemDetail
import com.kolktech.kahawai.data.network.dto.ItemSource
import com.kolktech.kahawai.data.network.dto.Pref
import com.kolktech.kahawai.data.repository.CatalogRepository

/// The source to play, and the audio index that means something INSIDE it.
data class PlaybackChoice(
    val item: ItemDetail,
    val sourceId: Int?,
    val mediaEntryId: String?,
    val audioTrack: Int,
)

/// How many times a source change may restart the ranking before giving up.
/// A concurrent scan can add a rendition or reorder streams underneath us;
/// re-ranking is correct, looping forever is not.
private const val MAX_ATTEMPTS = 3

/// The audio index each source would be played with, keyed by source id —
/// what `ItemQueryRequest.sourceAudioTracks` carries. Every source is ranked
/// on ITS own streams and ITS own remembered choice, because an index means
/// nothing across sources.
internal fun sourceAudioTracks(
    item: ItemDetail,
    prefs: List<Pref>,
    mediaType: String,
): Map<String, Int> {
    // The series memory's scope is the item's own parent, which only the
    // fetched item can name — the caller has not necessarily learned it yet
    // when the first query goes out.
    val seriesId = item.parentId ?: item.id
    return item.sources
        .map { it.sourceId }
        .distinct()
        .associate { id ->
            id.toString() to resolveAudioTrack(
                prefs = prefs,
                seriesId = seriesId,
                sourceScope = sourcePreferenceScope(item.sources, id),
                mediaType = mediaType,
                originalLanguage = item.metadata?.originalLanguage,
                audio = item.sources.firstOrNull { it.sourceId == id }?.streams?.audio.orEmpty(),
            )
        }
}

private fun ItemSource?.entryId(): String? = this?.mediaEntryId

/// Negotiate which physical source plays and with which audio index — a port
/// of `selectPlaybackSource` in web/src/api/playback.ts.
///
/// The first QUERY can only rank sources on audio index 0, because the
/// client cannot know a source's streams until that answer arrives. Once it
/// does, each source's real preference is resolvable, and a preference that
/// isn't 0 means the ranking was made on the wrong basis — so the item is
/// re-queried with [sourceAudioTracks] and the hub re-ranks. A concurrent
/// scan can change the sources under that, which restarts the ranking rather
/// than borrowing an index resolved against a source that no longer applies.
///
/// [preview] is a QUERY this caller has already run (the Detail screen runs
/// one to draw the page); passing it saves the first round trip.
suspend fun negotiatePlayback(
    repo: CatalogRepository,
    libraryId: String,
    itemId: String,
    profile: CapabilityProfile,
    prefs: List<Pref>,
    mediaType: String,
    preview: ItemDetail? = null,
    /// A source the user pinned explicitly; null lets the hub choose.
    pinnedSourceId: Int? = null,
): PlaybackChoice {
    var item = preview ?: repo.queryItem(
        library = libraryId,
        id = itemId,
        profile = profile,
        sourceId = pinnedSourceId,
    )
    var audio = sourceAudioTracks(item, prefs, mediaType)
    // A preview already ranked on the right audio needs no second pass.
    var settled = pinnedSourceId?.let { (audio[it.toString()] ?: 0) == 0 }
        ?: audio.values.all { it == 0 }

    var attempt = 0
    while (!settled) {
        if (attempt++ >= MAX_ATTEMPTS) {
            // Stop re-ranking and play what the last answer chose. The
            // alternative — failing — turns a racing scan into an error on a
            // Play button, which is worse than a source chosen one ranking
            // behind.
            break
        }
        item = repo.queryItem(
            library = libraryId,
            id = itemId,
            profile = profile,
            sourceId = pinnedSourceId,
            sourceAudioTracks = audio,
        )
        val next = sourceAudioTracks(item, prefs, mediaType)
        settled = next == audio
        audio = next
    }

    val chosen = pinnedSourceId ?: item.negotiated?.source?.sourceId
    return PlaybackChoice(
        item = item,
        sourceId = chosen,
        // Pinning by media entry is what actually binds START to this
        // source: the numeric id groups parts within one response and is
        // not durable across them.
        mediaEntryId = item.sources.firstOrNull { it.sourceId == chosen }.entryId(),
        audioTrack = chosen?.let { audio[it.toString()] } ?: 0,
    )
}
