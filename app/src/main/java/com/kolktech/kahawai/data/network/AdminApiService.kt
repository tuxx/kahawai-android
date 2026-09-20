package com.kolktech.kahawai.data.network

import com.kolktech.kahawai.data.network.dto.ApproveRequest
import com.kolktech.kahawai.data.network.dto.ApproveResponse
import com.kolktech.kahawai.data.network.dto.CatalogueMembership
import com.kolktech.kahawai.data.network.dto.CatalogueCollection
import com.kolktech.kahawai.data.network.dto.CreateLibraryRequest
import com.kolktech.kahawai.data.network.dto.LibrarySummary
import com.kolktech.kahawai.data.network.dto.EnrichRunResponse
import com.kolktech.kahawai.data.network.dto.EnrichStatusResponse
import com.kolktech.kahawai.data.network.dto.EnrollmentsResponse
import com.kolktech.kahawai.data.network.dto.ProvidersResponse
import com.kolktech.kahawai.data.network.dto.RefreshLibraryResponse
import com.kolktech.kahawai.data.network.dto.SavedResponse
import com.kolktech.kahawai.data.network.dto.SatellitesResponse
import com.kolktech.kahawai.data.network.dto.SessionsResponse
import com.kolktech.kahawai.data.network.dto.SetAnidbRequest
import com.kolktech.kahawai.data.network.dto.SetAnidbResponse
import com.kolktech.kahawai.data.network.dto.SetChainRequest
import com.kolktech.kahawai.data.network.dto.SetDisabledRequest
import com.kolktech.kahawai.data.network.dto.SetTmdbRequest
import com.kolktech.kahawai.data.network.dto.SetTvdbRequest
import com.kolktech.kahawai.data.network.dto.SetFanartRequest
import com.kolktech.kahawai.data.network.dto.SetTheAudioDbRequest
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.PUT
import retrofit2.http.Path

/// The hub's admin-only `/admin/v1/*` surface (crates/kahawai-hub/src/api.rs) —
/// requires the JWT `admin` claim; the hub 403s otherwise regardless of
/// what this client shows/hides. See [ApiService] for the client-facing
/// `/api/v1/*` surface this sits alongside.
interface AdminApiService {
    @GET("admin/v1/enrollments")
    suspend fun enrollments(): EnrollmentsResponse

    @POST("admin/v1/enrollments/approve")
    suspend fun approve(@Body body: ApproveRequest): ApproveResponse

    @GET("admin/v1/satellites")
    suspend fun satellites(): SatellitesResponse

    @DELETE("admin/v1/satellites/{id}")
    suspend fun deleteSatellite(@Path("id") id: String)

    @POST("admin/v1/satellites/{id}/disabled")
    suspend fun setSatelliteDisabled(@Path("id") id: String, @Body body: SetDisabledRequest)

    /// There is no admin library LISTING any more: the client-facing
    /// `GET /api/v1/catalogue/libraries` carries `collection_ids`, which is
    /// everything the admin screen needs. See [ApiService.libraries].
    /// Answers with the created library itself, not just its id.
    @POST("admin/v1/catalogue/libraries")
    suspend fun createLibrary(@Body body: CreateLibraryRequest): LibrarySummary

    @DELETE("admin/v1/catalogue/libraries/{id}")
    suspend fun deleteLibrary(@Path("id") id: String)

    /// Sets the whole membership — attach/detach are gone (see
    /// [CatalogueMembership]).
    @PUT("admin/v1/catalogue/libraries/{id}/collections")
    suspend fun setCollections(@Path("id") id: String, @Body body: CatalogueMembership)

    @POST("admin/v1/catalogue/libraries/{id}/refresh")
    suspend fun refreshLibrary(@Path("id") id: String): RefreshLibraryResponse

    /// A bare array, like the client-facing library listing.
    @GET("admin/v1/catalogue/collections")
    suspend fun collections(): List<CatalogueCollection>

    @GET("admin/v1/providers")
    suspend fun providers(): ProvidersResponse

    @POST("admin/v1/providers/chains/{mediaType}")
    suspend fun setChain(@Path("mediaType") mediaType: String, @Body body: SetChainRequest)

    @POST("admin/v1/providers/tmdb")
    suspend fun setTmdb(@Body body: SetTmdbRequest): SavedResponse

    @POST("admin/v1/providers/tvdb")
    suspend fun setTvdb(@Body body: SetTvdbRequest): SavedResponse

    @POST("admin/v1/providers/anidb")
    suspend fun setAnidb(@Body body: SetAnidbRequest): SetAnidbResponse

    @POST("admin/v1/providers/fanart")
    suspend fun setFanart(@Body body: SetFanartRequest): SavedResponse

    @POST("admin/v1/providers/theaudiodb")
    suspend fun setTheAudioDb(@Body body: SetTheAudioDbRequest): SavedResponse

    /// Detaches a provider's stored credentials without touching the
    /// chains that name it.
    @DELETE("admin/v1/providers/{provider}/credentials")
    suspend fun deleteProviderCredentials(@Path("provider") provider: String)

    @GET("admin/v1/enrich")
    suspend fun enrichStatus(): EnrichStatusResponse

    @POST("admin/v1/enrich")
    suspend fun enrichRun(): EnrichRunResponse

    @GET("admin/v1/sessions")
    suspend fun sessions(): SessionsResponse

    @DELETE("admin/v1/sessions/{id}")
    suspend fun endSession(@Path("id") id: String)
}
