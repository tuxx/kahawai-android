package com.kolktech.kahawai.ui.admin

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import com.kolktech.kahawai.R
import com.kolktech.kahawai.data.network.dto.CatalogueCollection
import com.kolktech.kahawai.data.network.dto.AdminSession
import com.kolktech.kahawai.data.network.dto.LibrarySummary
import com.kolktech.kahawai.data.network.dto.EnrichStatusResponse
import com.kolktech.kahawai.data.network.dto.PendingEnrollment
import com.kolktech.kahawai.data.network.dto.ProvidersResponse
import com.kolktech.kahawai.data.network.dto.Satellite
import com.kolktech.kahawai.data.network.isAuthError
import com.kolktech.kahawai.data.network.readableMessage
import com.kolktech.kahawai.data.repository.AdminRepository

data class AdminData(
    val pending: List<PendingEnrollment>,
    val satellites: List<Satellite>,
    val sessions: List<AdminSession>,
    val libraries: List<LibrarySummary>,
    val collections: List<CatalogueCollection>,
    val providers: ProvidersResponse,
    val enrich: EnrichStatusResponse,
)

sealed interface AdminState {
    data object Loading : AdminState
    data class Error(val message: String, val isAuthError: Boolean) : AdminState
    data class Loaded(val data: AdminData) : AdminState
}

/// Backs [AdminScreen] — full parity with `~/code/kahawai/web/src/views/Admin.tsx`:
/// metadata providers, pending enrollments, satellites, libraries, active
/// sessions. Polling only (no SSE `/api/v1/events` channel — see the plan
/// this was built from), same 15s interval the web client uses as its own
/// fallback.
class AdminViewModel(
    application: Application,
    private val repo: AdminRepository = AdminRepository(),
) : AndroidViewModel(application) {
    private fun string(resId: Int): String = getApplication<Application>().getString(resId)
    private fun string(resId: Int, vararg args: Any): String = getApplication<Application>().getString(resId, *args)

    private val _state = MutableStateFlow<AdminState>(AdminState.Loading)
    val state: StateFlow<AdminState> = _state

    private val _notice = MutableStateFlow<String?>(null)
    val notice: StateFlow<String?> = _notice

    init {
        reload()
    }

    fun reload() {
        viewModelScope.launch {
            try {
                _state.value = AdminState.Loaded(fetchAll())
            } catch (e: Exception) {
                _state.value = AdminState.Error(e.readableMessage(), e.isAuthError())
            }
        }
    }

    fun clearNotice() {
        _notice.value = null
    }

    fun approve(code: String) = runAction {
        val id = repo.approve(code)
        notice(string(R.string.admin_notice_approved, id))
    }

    fun deleteSatellite(id: String) = runAction {
        repo.deleteSatellite(id)
        notice(string(R.string.admin_notice_deleted, id))
    }

    fun setSatelliteDisabled(id: String, disabled: Boolean) = runAction {
        repo.setSatelliteDisabled(id, disabled)
    }

    fun createLibrary(name: String, mediaType: String) = runAction { repo.createLibrary(name, mediaType) }

    fun deleteLibrary(id: String) = runAction { repo.deleteLibrary(id) }

    /// Membership is set wholesale (see [AdminRepository.setCollections]);
    /// the screen computes the list it wants from the one it is showing.
    fun setCollections(libraryId: String, collectionIds: List<String>) = runAction {
        repo.setCollections(libraryId, collectionIds)
    }

    fun refreshLibrary(id: String) = runAction {
        val r = repo.refreshLibrary(id)
        notice(
            string(R.string.admin_notice_refresh, r.asked) +
                if (r.offline + r.unsupported > 0) {
                    string(R.string.admin_notice_refresh_offline_suffix, r.offline + r.unsupported)
                } else {
                    ""
                },
        )
    }

    fun setTmdbKey(key: String) = runAction {
        repo.setTmdbKey(key)
        notice(string(R.string.admin_notice_tmdb_saved))
    }

    fun setTvdbKey(key: String, pin: String?) = runAction {
        repo.setTvdbKey(key, pin)
        notice(string(R.string.admin_notice_tvdb_saved))
    }

    fun setAnidb(username: String, password: String, udpApiKey: String?) = runAction {
        val r = repo.setAnidb(username, password, udpApiKey)
        notice(
            if (r.verified) string(R.string.admin_notice_anidb_verified)
            else string(R.string.admin_notice_anidb_failed, r.error ?: string(R.string.unknown)),
        )
    }

    fun enrichRun() = runAction { repo.enrichRun() }

    fun setChain(mediaType: String, order: List<String>) = runAction {
        repo.setChain(mediaType, order)
        notice(string(R.string.admin_notice_chain_applied, mediaType))
    }

    fun endSession(id: String) = runAction { repo.endSession(id) }

    private fun notice(message: String) {
        _notice.value = message
    }

    private fun runAction(block: suspend () -> Unit) {
        viewModelScope.launch {
            try {
                block()
                reload()
            } catch (e: Exception) {
                _notice.value = string(R.string.admin_notice_error, e.readableMessage())
            }
        }
    }

    private suspend fun fetchAll(): AdminData = coroutineScope {
        val pending = async { repo.pendingEnrollments() }
        val satellites = async { repo.satellites() }
        val sessions = async { repo.sessions() }
        val libraries = async { repo.libraries() }
        val collections = async { repo.collections() }
        val providers = async { repo.providers() }
        val enrich = async { repo.enrichStatus() }
        AdminData(
            pending = pending.await(),
            satellites = satellites.await(),
            sessions = sessions.await(),
            libraries = libraries.await(),
            collections = collections.await(),
            providers = providers.await(),
            enrich = enrich.await(),
        )
    }
}
