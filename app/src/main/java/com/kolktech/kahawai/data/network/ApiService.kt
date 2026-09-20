package com.kolktech.kahawai.data.network

import com.kolktech.kahawai.data.network.dto.BootstrapResponse
import com.kolktech.kahawai.data.network.dto.CatalogueArtists
import com.kolktech.kahawai.data.network.dto.CatalogueChildren
import com.kolktech.kahawai.data.network.dto.CatalogueDetail
import com.kolktech.kahawai.data.network.dto.CatalogueItems
import com.kolktech.kahawai.data.network.dto.FeedItems
import com.kolktech.kahawai.data.network.dto.ItemQueryRequest
import com.kolktech.kahawai.data.network.dto.LibrarySummary
import com.kolktech.kahawai.data.network.dto.LoginRequest
import com.kolktech.kahawai.data.network.dto.LogoutRequest
import com.kolktech.kahawai.data.network.dto.OkResponse
import com.kolktech.kahawai.data.network.dto.PrefsResponse
import com.kolktech.kahawai.data.network.dto.ProgressRequest
import com.kolktech.kahawai.data.network.dto.ProviderConfiguration
import com.kolktech.kahawai.data.network.dto.PutPrefRequest
import com.kolktech.kahawai.data.network.dto.PutPrefResponse
import com.kolktech.kahawai.data.network.dto.RefreshRequest
import com.kolktech.kahawai.data.network.dto.SetOpenSubtitlesAccountRequest
import com.kolktech.kahawai.data.network.dto.FontsResponse
import com.kolktech.kahawai.data.network.dto.SeekRequest
import com.kolktech.kahawai.data.network.dto.SubtitleDownloadRequest
import com.kolktech.kahawai.data.network.dto.SubtitleDownloadResponse
import com.kolktech.kahawai.data.network.dto.SubtitleSearchRequest
import com.kolktech.kahawai.data.network.dto.SubtitleSearchResponse
import com.kolktech.kahawai.data.network.dto.SeekResponse
import com.kolktech.kahawai.data.network.dto.StartSessionRequest
import com.kolktech.kahawai.data.network.dto.StartSessionResponse
import com.kolktech.kahawai.data.network.dto.TokenPair
import com.kolktech.kahawai.data.network.dto.UpdatedResponse
import com.kolktech.kahawai.data.network.dto.WatchedRequest
import kotlinx.serialization.json.JsonElement
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.HTTP
import retrofit2.http.POST
import retrofit2.http.PUT
import retrofit2.http.Path
import retrofit2.http.Query

/// The kahawai hub's client-facing `/api/v1/*` surface. The catalogue half
/// moved to mediadb and is library-scoped throughout — `/api/v1/items/{id}`
/// and its children are gone, replaced by
/// `/api/v1/catalogue/libraries/{library}/items/{item}`. The authoritative
/// route table is crates/kahawai-hub/src/api.rs, and web/openapi.json (which
/// a hub-side test pins to the generated document) is the authority on shapes.
interface ApiService {
    @GET("api/v1/bootstrap")
    suspend fun bootstrap(): BootstrapResponse

    @POST("api/v1/auth/token")
    suspend fun login(@Body body: LoginRequest): TokenPair

    @POST("api/v1/auth/refresh")
    suspend fun refresh(@Body body: RefreshRequest): TokenPair

    /// Revokes this login's refresh family server-side. Authenticated
    /// (needs the access bearer) — called via [ApiClient.apiService].
    @POST("api/v1/auth/logout")
    suspend fun logout(@Body body: LogoutRequest)

    /// A bare array, not an envelope — unlike the item/feed listings next
    /// to it, which do wrap their rows.
    @GET("api/v1/catalogue/libraries")
    suspend fun libraries(): List<LibrarySummary>

    /// Browse/search one library. `q` is a per-library search: the hub has
    /// no cross-library item route, so searching everything means fanning
    /// out over [libraries] (see CatalogRepository.search).
    @GET("api/v1/catalogue/libraries/{id}/items")
    suspend fun items(
        @Path("id") library: String,
        @Query("q") q: String? = null,
        @Query("sort") sort: String? = null,
        @Query("artist") artist: String? = null,
        @Query("limit") limit: Int? = null,
        @Query("offset") offset: Int? = null,
    ): CatalogueItems

    /// Started and not finished, most recently watched first — the home
    /// screen's continue-watching row. Its own order, so no `sort`/`q`;
    /// `library` still scopes it, and each row names the library it came
    /// from.
    @GET("api/v1/catalogue/continue-watching")
    suspend fun continueWatching(
        @Query("library") library: String? = null,
        @Query("limit") limit: Int? = null,
        @Query("offset") offset: Int? = null,
    ): FeedItems

    /// The episode after the last one finished, one per current series —
    /// the home screen's up-next row.
    @GET("api/v1/catalogue/up-next")
    suspend fun upNext(
        @Query("library") library: String? = null,
        @Query("limit") limit: Int? = null,
        @Query("offset") offset: Int? = null,
    ): FeedItems

    /// "What did we find" — sources without stream info, no negotiation.
    /// [item] accepts a `child1:`-prefixed id for an episode/track just as
    /// it accepts a plain identity id.
    @GET("api/v1/catalogue/libraries/{library}/items/{id}")
    suspend fun item(@Path("library") library: String, @Path("id") id: String): CatalogueDetail

    /// "What would I be served" (RFC 10008 QUERY) — same response shape as
    /// [item] plus the flattened query result: the source negotiation
    /// chose, its per-stream verdicts, the unified subtitle track list with
    /// delivery computed for the declared
    /// [com.kolktech.kahawai.data.network.dto.CapabilityProfile], and the
    /// [com.kolktech.kahawai.data.network.dto.SubtitleSource] a subtitle
    /// search must be bound to. One request answers the whole item page.
    @HTTP(method = "QUERY", path = "api/v1/catalogue/libraries/{library}/items/{id}", hasBody = true)
    suspend fun itemQuery(
        @Path("library") library: String,
        @Path("id") id: String,
        @Body body: ItemQueryRequest,
    ): CatalogueDetail

    /// Ticks an item watched/unwatched without a playback session. Either
    /// direction clears the item's resume position server-side.
    @PUT("api/v1/catalogue/libraries/{library}/items/{id}/watched")
    suspend fun setWatched(
        @Path("library") library: String,
        @Path("id") id: String,
        @Body body: WatchedRequest,
    ): UpdatedResponse

    @GET("api/v1/catalogue/libraries/{library}/items/{id}/children")
    suspend fun children(
        @Path("library") library: String,
        @Path("id") id: String,
        @Query("season") season: Int? = null,
        @Query("disc") disc: Int? = null,
        @Query("limit") limit: Int? = null,
        @Query("offset") offset: Int? = null,
    ): CatalogueChildren

    /// The next episode after this one, within the same library. The end of
    /// a series answers with a JSON `null` body on a 200, not a 404 — and
    /// Retrofit's converter decodes against the declared type rather than a
    /// nullable one, so this comes back as a [JsonElement] for
    /// CatalogRepository.nextItem to tell the two apart.
    @GET("api/v1/catalogue/libraries/{library}/items/{id}/next")
    suspend fun nextItem(@Path("library") library: String, @Path("id") id: String): JsonElement

    @GET("api/v1/catalogue/libraries/{library}/artists")
    suspend fun artists(
        @Path("library") library: String,
        @Query("limit") limit: Int? = null,
        @Query("offset") offset: Int? = null,
    ): CatalogueArtists

    /// Subtitle search and download are bound to the physical rendition
    /// named by [com.kolktech.kahawai.data.network.dto.SubtitleSource],
    /// not to the logical title: the hub revalidates it before spending
    /// provider quota.
    @POST("api/v1/catalogue/libraries/{library}/items/{id}/subtitles/search")
    suspend fun searchSubtitles(
        @Path("library") library: String,
        @Path("id") id: String,
        @Body body: SubtitleSearchRequest,
    ): SubtitleSearchResponse

    @POST("api/v1/catalogue/libraries/{library}/items/{id}/subtitles/download")
    suspend fun downloadSubtitle(
        @Path("library") library: String,
        @Path("id") id: String,
        @Body body: SubtitleDownloadRequest,
    ): SubtitleDownloadResponse

    /// Only a `downloaded` track is removable, and only by whoever spent
    /// the quota on it or an admin — the hub says which via
    /// [com.kolktech.kahawai.data.network.dto.SubtitleTrack.deletable].
    @DELETE("api/v1/catalogue/libraries/{library}/items/{id}/subtitles/{track}")
    suspend fun deleteSubtitle(
        @Path("library") library: String,
        @Path("id") id: String,
        @Path("track") track: Long,
    )

    @POST("api/v1/playback/sessions")
    suspend fun startSession(@Body body: StartSessionRequest): StartSessionResponse

    @POST("api/v1/playback/sessions/{id}/seek")
    suspend fun seek(@Path("id") id: String, @Body body: SeekRequest): SeekResponse

    @POST("api/v1/playback/sessions/{id}/progress")
    suspend fun progress(@Path("id") id: String, @Body body: ProgressRequest)

    /// Embedded font names for ASS tracks, for the session's negotiated
    /// source; bytes are fetched per-index from the same session. Fonts
    /// belong to the source, so they hang off a session now rather than
    /// off an item.
    @GET("api/v1/playback/sessions/{id}/fonts")
    suspend fun fonts(@Path("id") id: String): FontsResponse

    @DELETE("api/v1/playback/sessions/{id}")
    suspend fun endSession(@Path("id") id: String)

    /// Per-user preferences (HUB-33) — audio/subtitle language defaults,
    /// OpenSubtitles account, bandwidth cap, ASS fallback order. See
    /// [com.kolktech.kahawai.ui.settings.ServerSettingsScreen].
    @GET("api/v1/prefs")
    suspend fun prefs(): PrefsResponse

    @PUT("api/v1/prefs")
    suspend fun putPref(@Body body: PutPrefRequest): PutPrefResponse

    /// Per-viewer OpenSubtitles account (HUB-21), sealed in the hub's own
    /// credential store (kahawai commit 7835630, "Seal viewer OpenSubtitles
    /// accounts"). Replaced the old `opensubtitles.username`/`.password`
    /// generic prefs, which the hub no longer reads at all.
    @GET("api/v1/account/opensubtitles")
    suspend fun openSubtitlesAccount(): ProviderConfiguration

    /// Both fields required; empty either side is a 400. Replaces whatever
    /// account was previously attached.
    @POST("api/v1/account/opensubtitles")
    suspend fun setOpenSubtitlesAccount(@Body body: SetOpenSubtitlesAccountRequest): OkResponse

    @DELETE("api/v1/account/opensubtitles")
    suspend fun deleteOpenSubtitlesAccount(): OkResponse
}
