package com.kolktech.kahawai.data.repository

import com.kolktech.kahawai.data.network.ApiClient
import com.kolktech.kahawai.data.network.ApiService
import com.kolktech.kahawai.data.network.apiJson
import com.kolktech.kahawai.data.network.isAuthError
import com.kolktech.kahawai.data.network.dto.CapabilityProfile
import com.kolktech.kahawai.data.network.dto.Item
import com.kolktech.kahawai.data.network.dto.ItemDetail
import com.kolktech.kahawai.data.network.dto.ItemQueryRequest
import com.kolktech.kahawai.data.network.dto.FeedItems
import com.kolktech.kahawai.data.network.dto.ItemsResponse
import com.kolktech.kahawai.data.network.dto.LibraryChild
import com.kolktech.kahawai.data.network.dto.LibrarySummary
import com.kolktech.kahawai.data.network.dto.SubtitleCandidate
import com.kolktech.kahawai.data.network.dto.SubtitleDownloadRequest
import com.kolktech.kahawai.data.network.dto.SubtitleSearchRequest
import com.kolktech.kahawai.data.network.dto.SubtitleSource
import com.kolktech.kahawai.data.network.dto.WatchUpdate
import com.kolktech.kahawai.data.network.dto.WatchedRequest
import com.kolktech.kahawai.data.network.dto.toDetail
import com.kolktech.kahawai.data.network.dto.toItem
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.json.JsonNull

/// [api] is built lazily, not eagerly at construction: this repository is
/// `remember`ed once at the top of the nav graph, which composes (and
/// thus constructs this) before the user has necessarily been through
/// Setup — an eager [ApiClient.apiService] call would hit
/// [ApiClient.baseUrl]'s "hub server not configured yet" throw on every
/// first launch, well before the Setup screen ever renders. Deferring
/// construction to first actual use means it only runs once a hub is
/// configured, since every suspend fun below is only reachable from
/// screens gated behind Setup.
///
/// Every catalogue call is library-scoped now (the hub's mediadb rewrite),
/// so `library` is a required argument throughout rather than an optional
/// filter. The wire shapes are mapped to the flat [Item]/[ItemDetail] the
/// UI speaks — see the mappers in dto/Catalog.kt.
class CatalogRepository(apiProvider: () -> ApiService = { ApiClient.apiService() }) {
    private val api: ApiService by lazy(apiProvider)

    suspend fun libraries(): List<LibrarySummary> = api.libraries()

    suspend fun items(
        library: String,
        q: String? = null,
        sort: String? = null,
        artist: String? = null,
        limit: Int? = null,
        offset: Int? = null,
    ): ItemsResponse = api.items(library, q, sort, artist, limit, offset).let { page ->
        ItemsResponse(
            items = page.items.map { it.toItem(library) },
            total = page.total,
            limit = page.limit,
            offset = page.offset,
        )
    }

    /// The hub has no cross-library item route — `q` is a parameter on a
    /// library's own item list — so searching everything means asking each
    /// library in parallel and merging. `total` is the sum of what the
    /// libraries reported, which is a count of matches and not of the rows
    /// returned here once [limit] truncates them.
    ///
    /// One library failing is tolerated: partial results beat no results,
    /// and a single offline mediahost shouldn't empty the screen. Two cases
    /// are not, because both would render as "nothing matched" when the
    /// truth is "we never found out": an auth failure (the app needs to
    /// send the viewer back through login, so it is rethrown even if
    /// another library answered), and every library failing.
    suspend fun search(q: String, limit: Int? = null): ItemsResponse = coroutineScope {
        val results = libraries()
            .map { library -> async { runCatching { items(library.id, q = q, limit = limit) } } }
            .awaitAll()
        results.firstNotNullOfOrNull { it.exceptionOrNull()?.takeIf { e -> e.isAuthError() } }
            ?.let { throw it }
        val pages = results.mapNotNull { it.getOrNull() }
        if (pages.isEmpty()) {
            results.firstNotNullOfOrNull { it.exceptionOrNull() }?.let { throw it }
        }
        val merged = pages.flatMap { it.items }
        ItemsResponse(
            items = limit?.let { merged.take(it) } ?: merged,
            total = pages.sumOf { it.total },
            limit = limit ?: merged.size,
            offset = 0,
        )
    }

    suspend fun continueWatching(library: String? = null, limit: Int? = null, offset: Int? = null): ItemsResponse =
        api.continueWatching(library, limit, offset).toItemsResponse()

    suspend fun upNext(library: String? = null, limit: Int? = null, offset: Int? = null): ItemsResponse =
        api.upNext(library, limit, offset).toItemsResponse()

    private fun FeedItems.toItemsResponse() = ItemsResponse(
        items = items.map { it.toItem() },
        total = total,
        limit = limit,
        offset = offset,
    )

    suspend fun item(library: String, id: String): ItemDetail = api.item(library, id).toDetail(library)

    /// The item viewer's call: what this client would actually be served,
    /// negotiated against [profile]. Carries the subtitle track list (with
    /// delivery), the skip segments and the
    /// [com.kolktech.kahawai.data.network.dto.SubtitleSource] a subtitle
    /// search must be bound to — see [com.kolktech.kahawai.data.network.ApiService.itemQuery].
    suspend fun queryItem(
        library: String,
        id: String,
        profile: CapabilityProfile,
        audioTrack: Int = 0,
        videoTrack: Int = 0,
        subtitleTrack: Long? = null,
        sourceId: Int? = null,
        /// Per-source audio preferences, so the hub ranks each copy on the
        /// audio it would actually be played with — see
        /// [com.kolktech.kahawai.playback.negotiatePlayback].
        sourceAudioTracks: Map<String, Int>? = null,
    ): ItemDetail = api.itemQuery(
        library,
        id,
        ItemQueryRequest(
            profile = profile,
            audioTrack = audioTrack,
            videoTrack = videoTrack,
            subtitleTrack = subtitleTrack,
            sourceId = sourceId,
            sourceAudioTracks = sourceAudioTracks,
        ),
    ).toDetail(library)

    /// Episodes of a series / tracks of an album. Watch state arrives
    /// alongside the children rather than on them, and is folded in here.
    suspend fun children(library: String, id: String): List<Item> =
        api.children(library, id).let { page ->
            page.children.map { it.toItem(library, page.watch[it.id]) }
        }

    /// The episode after [id] in the same series, or null at the end of it
    /// — which the hub answers with a JSON `null` body on a 200, not a 404.
    suspend fun nextItem(library: String, id: String): Item? =
        api.nextItem(library, id).takeIf { it !is JsonNull }
            ?.let { apiJson.decodeFromJsonElement(LibraryChild.serializer(), it).toItem(library) }

    /// Ticks [id] watched or unwatched without playing it — the detail
    /// page's "Mark watched" toggle. Returns this item's own update
    /// (there's always exactly one, since no batch `items` is sent).
    suspend fun setWatched(library: String, id: String, played: Boolean): WatchUpdate =
        api.setWatched(library, id, WatchedRequest(played = played)).updated.first { it.itemId == id }

    /// Subtitle search and download spend a shared, rate-limited provider
    /// quota, and are bound to the physical rendition [source] names — read
    /// it off the QUERY response ([ItemDetail.subtitleSource]) rather than
    /// constructing one.
    suspend fun searchSubtitles(
        library: String,
        id: String,
        source: SubtitleSource,
        languages: List<String> = emptyList(),
    ): List<SubtitleCandidate> =
        api.searchSubtitles(library, id, SubtitleSearchRequest(source, languages)).candidates

    suspend fun downloadSubtitle(
        library: String,
        id: String,
        source: SubtitleSource,
        fileId: String,
        language: String? = null,
    ): Long = api.downloadSubtitle(library, id, SubtitleDownloadRequest(source, fileId, language)).trackId

    suspend fun deleteSubtitle(library: String, id: String, trackId: Long) =
        api.deleteSubtitle(library, id, trackId)

    /// `size` is one of the hub's named sizes ("thumb", "card"); null
    /// serves the original. Artwork is library-scoped like everything else
    /// now, and the hub no longer takes a version parameter — it keys its
    /// own cache off the resolved artwork instead.
    ///
    /// Called from composition (Coil's `model`), so it must not throw:
    /// null falls through to Coil as "no image" rather than crashing the
    /// screen that's still on-screen while the server gets unconfigured
    /// (e.g. mid "change server" navigation).
    fun artworkUrl(libraryId: String, id: String, size: String? = null): String? {
        val base = ApiClient.baseUrlOrNull() ?: return null
        val query = size?.let { "?size=$it" }.orEmpty()
        return "${base}api/v1/catalogue/libraries/$libraryId/items/$id/artwork$query"
    }
}
