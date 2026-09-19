package com.kolktech.kahawai.data.repository

import com.kolktech.kahawai.data.network.ApiClient
import com.kolktech.kahawai.data.network.dto.AdminSession
import com.kolktech.kahawai.data.network.dto.ApproveRequest
import com.kolktech.kahawai.data.network.dto.CatalogueCollection
import com.kolktech.kahawai.data.network.dto.CatalogueMembership
import com.kolktech.kahawai.data.network.dto.LibrarySummary
import com.kolktech.kahawai.data.network.dto.CreateLibraryRequest
import com.kolktech.kahawai.data.network.dto.EnrichStatusResponse
import com.kolktech.kahawai.data.network.dto.PendingEnrollment
import com.kolktech.kahawai.data.network.dto.ProvidersResponse
import com.kolktech.kahawai.data.network.dto.RefreshLibraryResponse
import com.kolktech.kahawai.data.network.dto.Satellite
import com.kolktech.kahawai.data.network.dto.SetAnidbRequest
import com.kolktech.kahawai.data.network.dto.SetAnidbResponse
import com.kolktech.kahawai.data.network.dto.SetChainRequest
import com.kolktech.kahawai.data.network.dto.SetDisabledRequest
import com.kolktech.kahawai.data.network.dto.SetTmdbRequest
import com.kolktech.kahawai.data.network.dto.SetFanartRequest
import com.kolktech.kahawai.data.network.dto.SetTheAudioDbRequest
import com.kolktech.kahawai.data.network.dto.SetTvdbRequest

/// Thin wrapper over [ApiClient.adminApiService], same style as
/// [CatalogRepository] — one function per `/admin/v1/*` call, unwrapping
/// the envelope response types.
class AdminRepository {
    private fun service() = ApiClient.adminApiService()
    private fun catalogue() = ApiClient.apiService()

    suspend fun pendingEnrollments(): List<PendingEnrollment> = service().enrollments().pending
    suspend fun approve(code: String): String = service().approve(ApproveRequest(code)).approved

    suspend fun satellites(): List<Satellite> = service().satellites().satellites
    suspend fun deleteSatellite(id: String) = service().deleteSatellite(id)
    suspend fun setSatelliteDisabled(id: String, disabled: Boolean) =
        service().setSatelliteDisabled(id, SetDisabledRequest(disabled))

    /// The admin surface has no library listing of its own any more — the
    /// client-facing catalogue route carries `collection_ids`, which is
    /// everything this screen needs.
    suspend fun libraries(): List<LibrarySummary> = catalogue().libraries()
    suspend fun createLibrary(name: String, mediaType: String): String =
        service().createLibrary(CreateLibraryRequest(name, mediaType)).id
    suspend fun deleteLibrary(id: String) = service().deleteLibrary(id)

    /// Membership is SET, not patched: attach and detach both read the
    /// current list and send the one they want. The caller holds that list
    /// already (it is on [LibrarySummary]), so it passes the result.
    suspend fun setCollections(libraryId: String, collectionIds: List<String>) =
        service().setCollections(libraryId, CatalogueMembership(collectionIds))
    suspend fun refreshLibrary(id: String): RefreshLibraryResponse = service().refreshLibrary(id)
    suspend fun collections(): List<CatalogueCollection> = service().collections()

    suspend fun providers(): ProvidersResponse = service().providers()
    suspend fun setChain(mediaType: String, order: List<String>) =
        service().setChain(mediaType, SetChainRequest(order))
    suspend fun setTmdbKey(apiKey: String) = service().setTmdb(SetTmdbRequest(apiKey))
    suspend fun setTvdbKey(apiKey: String, pin: String?) = service().setTvdb(SetTvdbRequest(apiKey, pin))
    suspend fun setAnidb(username: String, password: String, udpApiKey: String?): SetAnidbResponse =
        service().setAnidb(SetAnidbRequest(username, password, udpApiKey))
    suspend fun setFanartKey(clientKey: String) = service().setFanart(SetFanartRequest(clientKey))
    suspend fun setTheAudioDbKey(apiKey: String) = service().setTheAudioDb(SetTheAudioDbRequest(apiKey))
    suspend fun deleteProviderCredentials(provider: String) = service().deleteProviderCredentials(provider)
    suspend fun enrichStatus(): EnrichStatusResponse = service().enrichStatus()
    suspend fun enrichRun() = service().enrichRun()

    suspend fun sessions(): List<AdminSession> = service().sessions().sessions
    suspend fun endSession(id: String) = service().endSession(id)
}
