package com.kolktech.kahawai.data.network.dto

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonClassDiscriminator

// ---------------------------------------------------------------------------
// Wire shapes — mirror crates/kahawai-hub/src/api/catalogue.rs. The hub's
// catalogue moved to mediadb (kahawai 6d77264) and every catalogue route is
// now library-scoped: there is no `/api/v1/items/{id}` any more, only
// `/api/v1/catalogue/libraries/{library}/items/{item}`. The authority is
// web/openapi.json, which a hub-side test pins to the generated document.
//
// The wire shapes are richer than this client needs — mediadb separates a
// logical *identity* from the physical *copies* backing it — so they are
// mapped down to the [Item]/[ItemDetail] models the UI has always spoken
// (see `toItem`/`toDetail` at the bottom of this file).
// ---------------------------------------------------------------------------

@Serializable
data class LibrarySummary(
    val id: String,
    val name: String,
    val mediaType: String,
    /// The collections feeding this library. Browse never needs it; the
    /// admin screen edits it as a set (PUT .../collections).
    val collectionIds: List<String> = emptyList(),
)

@Serializable
data class Credit(val name: String, val role: String? = null)

/// The descriptive fields a provider chain resolved, field by field.
/// `releaseDate` is what the client used to call `premiered`.
@Serializable
data class Description(
    val overview: String? = null,
    val originalLanguage: String? = null,
    val originalTitle: String? = null,
    val rating: Double? = null,
    val releaseDate: String? = null,
    val genres: List<String>? = null,
    val artwork: List<String>? = null,
    val cast: List<Credit>? = null,
)

/// [Description] plus the per-field evidence that produced it. Only
/// `description` is rendered here; `provenance`/`providers` are the admin
/// UI's concern and are deliberately not modelled.
@Serializable
data class ResolvedDescription(val description: Description = Description())

/// `CatalogueItem` — one *identity* in a library (a movie, a series, an
/// album), not a file. `representativeId`/`copyIds` name the physical
/// copies behind it; `kind` is one of movie|series|album, which this
/// client maps onto its own longer vocabulary (see [toItem]).
@Serializable
data class CatalogueItem(
    val id: String,
    val kind: String,
    val mediaType: String,
    val title: String,
    val artist: String? = null,
    val year: Int? = null,
    val representativeId: String,
    val copyIds: List<String> = emptyList(),
    val matchConfidence: String? = null,
    val metadata: ResolvedDescription = ResolvedDescription(),
    // WatchState, flattened into the item by the hub.
    val played: Boolean = false,
    val resumePositionMs: Long? = null,
    val resumeDurationMs: Long? = null,
)

@Serializable
data class CatalogueItems(
    val items: List<CatalogueItem> = emptyList(),
    val total: Int = 0,
    val limit: Int = 0,
    val offset: Int = 0,
)

/// Where a child sits in its parent — an episode in a season, a track on a
/// disc, or an unnumbered track keyed by its media entry. Tagged by `kind`
/// on the wire.
@OptIn(ExperimentalSerializationApi::class)
@Serializable
@JsonClassDiscriminator("kind")
sealed class ChildPosition {
    @Serializable
    @SerialName("episode")
    data class Episode(val episode: Int, val season: Int? = null) : ChildPosition()

    @Serializable
    @SerialName("track")
    data class Track(val track: Int, val disc: Int? = null) : ChildPosition()

    @Serializable
    @SerialName("unnumbered_track")
    data class UnnumberedTrack(val entryId: String, val disc: Int? = null) : ChildPosition()
}

/// One episode/track under a parent identity. Its `id` is a `child1:`-
/// prefixed handle that the item, watched, children, QUERY and subtitle
/// routes all accept in place of a plain item id — which is why the UI can
/// keep navigating to a child exactly as it navigates to a movie.
@Serializable
data class LibraryChild(
    val id: String,
    val parentId: String,
    val representativeId: String,
    val title: String,
    val artist: String? = null,
    val position: ChildPosition,
    val sourceCount: Int = 0,
    val metadata: ResolvedDescription = ResolvedDescription(),
)

/// A season/disc tally alongside the children themselves.
@Serializable
data class CatalogueChildGroup(
    val kind: String,
    val number: Int? = null,
    val total: Int = 0,
    val played: Int = 0,
)

@Serializable
data class CatalogueChildren(
    val children: List<LibraryChild> = emptyList(),
    val groups: List<CatalogueChildGroup> = emptyList(),
    val total: Int = 0,
    val limit: Int = 0,
    val offset: Int = 0,
    /// Per-child watch state, keyed by child id — the hub keeps it out of
    /// [LibraryChild] so the child rows stay cacheable across viewers.
    val watch: Map<String, WatchState> = emptyMap(),
)

@Serializable
data class WatchState(
    val played: Boolean = false,
    val resumePositionMs: Long? = null,
    val resumeDurationMs: Long? = null,
)

/// A continue-watching / up-next row: the identity, plus the library it
/// lives in and (for a series) the specific episode being pointed at.
@Serializable
data class FeedItem(
    val id: String,
    val kind: String,
    val mediaType: String,
    val title: String,
    val artist: String? = null,
    val year: Int? = null,
    val representativeId: String,
    val copyIds: List<String> = emptyList(),
    val metadata: ResolvedDescription = ResolvedDescription(),
    val played: Boolean = false,
    val resumePositionMs: Long? = null,
    val resumeDurationMs: Long? = null,
    val libraryId: String,
    val parentTitle: String? = null,
    val child: LibraryChild? = null,
)

@Serializable
data class FeedItems(
    val items: List<FeedItem> = emptyList(),
    val total: Int = 0,
    val limit: Int = 0,
    val offset: Int = 0,
)

@Serializable
data class ClientVideoStream(
    val codec: String,
    val displayWidth: Int? = null,
    val displayHeight: Int? = null,
    val bitDepth: Int? = null,
    val bitrateKbps: Int? = null,
)

@Serializable
data class ClientAudioStream(
    val codec: String,
    val channels: Int,
    val language: String? = null,
    val layout: String? = null,
    val sampleRate: Int = 0,
    val bitrateKbps: Int? = null,
)

@Serializable
data class ClientSubtitleStream(
    val codec: String? = null,
    val language: String? = null,
)

@Serializable
data class ReplayGain(
    val trackGainDb: Double? = null,
    val trackPeak: Double? = null,
    val albumGainDb: Double? = null,
    val albumPeak: Double? = null,
    val referenceLevelDb: Double? = null,
)

/// `ClientMediaInfo` — the probe for one source file, trimmed to what a
/// client may see. Note the video dimensions are now `display_*`
/// (rotation applied), not the raw coded size.
@Serializable
data class MediaStreams(
    val container: String? = null,
    val durationMs: Long? = null,
    val video: List<ClientVideoStream> = emptyList(),
    val audio: List<ClientAudioStream> = emptyList(),
    val subtitles: List<ClientSubtitleStream> = emptyList(),
    val replayGain: ReplayGain? = null,
)

/// One playable file behind an item. `sourceId` groups the file parts
/// *within this response* — it is not a mediadb id and not a playback
/// handle; `mediaEntryId` is the durable one, and is what a QUERY or a
/// session start names to pin a specific source.
@Serializable
data class ItemSource(
    val sourceId: Int,
    val moduleId: String,
    val collectionId: String,
    val collectionItemId: String,
    val mediaEntryId: String? = null,
    val pathRel: String,
    val size: Long = 0,
    val revision: Int = 0,
    val available: Boolean = false,
    val part: Int = 1,
    val parts: Int = 1,
    val hostName: String? = null,
    val streams: MediaStreams? = null,
)

/// One physical copy of an identity, as matched. The detail screen shows
/// these when an identity has more than one.
@Serializable
data class CollectionCopy(
    val id: String,
    val title: String,
    val artist: String? = null,
    val year: Int? = null,
    val season: Int? = null,
    val episode: Int? = null,
    val moduleId: String? = null,
    val collectionId: String? = null,
    val parentLibraryItemId: String? = null,
    val matchedTitle: String? = null,
    val matchedYear: Int? = null,
    val matchConfidence: String? = null,
    val hostName: String? = null,
    val paths: List<String> = emptyList(),
)

@Serializable
data class NegotiatedSource(
    val sourceId: Int,
    val moduleId: String,
    val collectionId: String,
    val pathRel: String,
    val displayWidth: Int? = null,
    val displayHeight: Int? = null,
    val orientation: String? = null,
)

@Serializable
data class SubtitleVerdict(
    val index: Int,
    val format: String,
    val language: String? = null,
    val tier: String,
    val note: String = "",
    val trackId: Long? = null,
)

@Serializable
data class NegotiatedStreams(
    val video: String,
    val audio: String,
    val subtitles: List<SubtitleVerdict> = emptyList(),
)

/// The converged half of a QUERY: the source negotiation chose and what
/// this client would be served from it.
@Serializable
data class Negotiated(
    val source: NegotiatedSource? = null,
    val mode: String,
    val cost: String,
    val streams: NegotiatedStreams? = null,
    val subtitles: List<SubtitleTrack> = emptyList(),
    /// The hub's own HLS target duration for this negotiation. ExoPlayer
    /// times an idle playlist out at 3.5x this, so it is worth honouring
    /// rather than assuming (see [CapabilityProfile.targetDuration]).
    val targetDurationSecs: Int = 0,
)

/// Why [ItemDetail.negotiated] is absent. Same shape as an error body,
/// deliberately: `source_offline` comes back once the host does,
/// `unplayable` does not.
@Serializable
data class Unavailable(
    val code: String,
    val message: String,
    val requestId: String = "",
)

/// `CatalogueDetail`: a [CatalogueItem] plus its physical context, plus
/// the flattened QUERY result. `sources`/`streams`/`negotiated`/`segments`
/// are only filled by a QUERY; a plain GET reports what was discovered,
/// not what would be served.
@Serializable
data class CatalogueDetail(
    val id: String,
    val kind: String,
    val mediaType: String,
    val title: String,
    val artist: String? = null,
    val year: Int? = null,
    val representativeId: String,
    val copyIds: List<String> = emptyList(),
    val matchConfidence: String? = null,
    val metadata: ResolvedDescription = ResolvedDescription(),
    val played: Boolean = false,
    val resumePositionMs: Long? = null,
    val resumeDurationMs: Long? = null,
    val child: LibraryChild? = null,
    val parentTitle: String? = null,
    val sources: List<ItemSource> = emptyList(),
    val durationMs: Long? = null,
    val chapters: List<Chapter> = emptyList(),
    val provider: String? = null,
    val tmdbId: Long? = null,
    val tvdbId: Long? = null,
    val copies: List<CollectionCopy> = emptyList(),
    // ItemQueryResult, flattened.
    val negotiated: Negotiated? = null,
    val segments: List<Segment> = emptyList(),
    val subtitleSource: SubtitleSource? = null,
    val unavailable: Unavailable? = null,
)

/// `PUT .../items/{id}/watched` body. `items`/`season` stay null — this
/// client only ever ticks the one item on its own detail page.
@Serializable
data class WatchedRequest(
    val played: Boolean,
    val items: List<String>? = null,
    val season: String? = null,
)

@Serializable
data class WatchUpdate(
    val itemId: String,
    val positionMs: Long,
    val played: Boolean,
)

@Serializable
data class UpdatedResponse(val updated: List<WatchUpdate> = emptyList())

@Serializable
data class ArtistSummary(val key: String, val name: String, val albumCount: Int = 0)

@Serializable
data class CatalogueArtists(
    val artists: List<ArtistSummary> = emptyList(),
    val total: Int = 0,
    val limit: Int = 0,
    val offset: Int = 0,
)

// ---------------------------------------------------------------------------
// Client-facing models
//
// The UI has always spoken a single flat row shape with a five-value `kind`
// (movie|show|episode|album|track). mediadb's identity/child split doesn't
// change what a screen draws, so the split is absorbed here rather than
// spread across every screen: a parent identity maps on its `kind`, and a
// [LibraryChild] maps on its [ChildPosition].
// ---------------------------------------------------------------------------

object ItemKind {
    const val MOVIE = "movie"
    const val SHOW = "show"
    const val EPISODE = "episode"
    const val ALBUM = "album"
    const val TRACK = "track"
}

/// One browse/search/children row. `libraryId` is required now: every
/// catalogue route is library-scoped, so a row that could not name its
/// library would be a row that cannot be opened.
@Serializable
data class Item(
    val id: String,
    val kind: String,
    val title: String,
    val libraryId: String,
    val artist: String? = null,
    val year: Int? = null,
    val season: Int? = null,
    val episode: Int? = null,
    val parentId: String? = null,
    val parentTitle: String? = null,
    val sources: Int = 0,
    val premiered: String? = null,
    val resumePositionMs: Long? = null,
    val resumeDurationMs: Long? = null,
    val played: Boolean = false,
)

@Serializable
data class ItemsResponse(
    val items: List<Item>,
    val total: Int,
    val limit: Int,
    val offset: Int,
)

@Serializable
data class ItemMetadata(
    val overview: String? = null,
    val originalLanguage: String? = null,
    val rating: Double? = null,
    val premiered: String? = null,
    val genres: List<String>? = null,
    val cast: List<Credit>? = null,
)

/// The detail-page model. Carries [libraryId] because the page's own
/// follow-up calls (QUERY, watched, subtitles, session start) all need it
/// and the hub's response does not repeat it.
@Serializable
data class ItemDetail(
    val id: String,
    val kind: String,
    val title: String,
    val libraryId: String,
    val artist: String? = null,
    val year: Int? = null,
    val season: Int? = null,
    val episode: Int? = null,
    val parentId: String? = null,
    val parentTitle: String? = null,
    val sources: List<ItemSource> = emptyList(),
    val copies: List<CollectionCopy> = emptyList(),
    val durationMs: Long? = null,
    val premiered: String? = null,
    val resumePositionMs: Long? = null,
    val resumeDurationMs: Long? = null,
    val played: Boolean = false,
    val metadata: ItemMetadata? = null,
    val negotiated: Negotiated? = null,
    val unavailable: Unavailable? = null,
    val chapters: List<Chapter> = emptyList(),
    val segments: List<Segment> = emptyList(),
    val subtitleSource: SubtitleSource? = null,
)

/// movie|series|album as the hub names them, in the vocabulary the UI
/// uses. `series` was called `show` before the mediadb rewrite and still
/// is here — renaming it across every screen would buy nothing.
private fun parentKind(kind: String): String = when (kind) {
    "series" -> ItemKind.SHOW
    "album" -> ItemKind.ALBUM
    else -> ItemKind.MOVIE
}

private fun ChildPosition.kindName(): String = when (this) {
    is ChildPosition.Episode -> ItemKind.EPISODE
    is ChildPosition.Track, is ChildPosition.UnnumberedTrack -> ItemKind.TRACK
}

private fun ChildPosition.seasonNumber(): Int? = (this as? ChildPosition.Episode)?.season

private fun ChildPosition.episodeNumber(): Int? = when (this) {
    is ChildPosition.Episode -> episode
    is ChildPosition.Track -> track
    is ChildPosition.UnnumberedTrack -> null
}

private fun Description.toMetadata() = ItemMetadata(
    overview = overview,
    originalLanguage = originalLanguage,
    rating = rating,
    premiered = releaseDate,
    genres = genres,
    cast = cast,
)

fun CatalogueItem.toItem(libraryId: String) = Item(
    id = id,
    kind = parentKind(kind),
    title = title,
    libraryId = libraryId,
    artist = artist,
    year = year,
    sources = copyIds.size,
    premiered = metadata.description.releaseDate,
    resumePositionMs = resumePositionMs,
    resumeDurationMs = resumeDurationMs,
    played = played,
)

fun LibraryChild.toItem(libraryId: String, watch: WatchState? = null, parentTitle: String? = null) = Item(
    id = id,
    kind = position.kindName(),
    title = title,
    libraryId = libraryId,
    artist = artist,
    season = position.seasonNumber(),
    episode = position.episodeNumber(),
    parentId = parentId,
    parentTitle = parentTitle,
    sources = sourceCount,
    premiered = metadata.description.releaseDate,
    resumePositionMs = watch?.resumePositionMs,
    resumeDurationMs = watch?.resumeDurationMs,
    played = watch?.played ?: false,
)

/// A feed row points at whatever is actually resumable: the episode when
/// the hub named one, otherwise the identity itself. The identity's watch
/// state is the row's either way — the feed computed it for the thing it
/// is pointing at.
fun FeedItem.toItem(): Item = child?.let { c ->
    Item(
        id = c.id,
        kind = c.position.kindName(),
        title = c.title,
        libraryId = libraryId,
        artist = c.artist ?: artist,
        season = c.position.seasonNumber(),
        episode = c.position.episodeNumber(),
        parentId = c.parentId,
        parentTitle = parentTitle ?: title,
        sources = c.sourceCount,
        premiered = c.metadata.description.releaseDate,
        resumePositionMs = resumePositionMs,
        resumeDurationMs = resumeDurationMs,
        played = played,
    )
} ?: Item(
    id = id,
    kind = parentKind(kind),
    title = title,
    libraryId = libraryId,
    artist = artist,
    year = year,
    parentTitle = parentTitle,
    sources = copyIds.size,
    premiered = metadata.description.releaseDate,
    resumePositionMs = resumePositionMs,
    resumeDurationMs = resumeDurationMs,
    played = played,
)

fun CatalogueDetail.toDetail(libraryId: String): ItemDetail {
    val description = (child?.metadata ?: metadata).description
    return ItemDetail(
        id = id,
        kind = child?.position?.kindName() ?: parentKind(kind),
        title = child?.title ?: title,
        libraryId = libraryId,
        artist = child?.artist ?: artist,
        year = year,
        season = child?.position?.seasonNumber(),
        episode = child?.position?.episodeNumber(),
        parentId = child?.parentId,
        parentTitle = parentTitle ?: child?.let { title },
        sources = sources,
        copies = copies,
        durationMs = durationMs,
        premiered = description.releaseDate,
        resumePositionMs = resumePositionMs,
        resumeDurationMs = resumeDurationMs,
        played = played,
        metadata = description.toMetadata(),
        negotiated = negotiated,
        unavailable = unavailable,
        chapters = chapters,
        segments = segments,
        subtitleSource = subtitleSource,
    )
}

/// The source negotiation actually chose, or null before a QUERY has run.
/// Streams belong to a physical source — reading them off `sources.first()`
/// describes a file that may not be the one being served. Mirrors
/// `sourceStreams` in web/src/domain/source.ts.
fun ItemDetail.negotiatedSource(): ItemSource? {
    val id = negotiated?.source?.sourceId ?: return sources.firstOrNull()
    return sources.firstOrNull { it.sourceId == id } ?: sources.firstOrNull()
}

/// The audio streams of [negotiatedSource] — the list
/// `StartSessionRequest.audioTrack` indexes into.
fun ItemDetail.negotiatedAudioStreams(): List<ClientAudioStream> =
    negotiatedSource()?.streams?.audio.orEmpty()
