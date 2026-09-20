package com.kolktech.kahawai.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import com.kolktech.kahawai.data.network.dto.Item
import com.kolktech.kahawai.data.network.dto.LibrarySummary
import com.kolktech.kahawai.data.network.isAuthError
import com.kolktech.kahawai.data.network.readableMessage
import com.kolktech.kahawai.data.repository.CatalogRepository

data class LibraryRow(val library: LibrarySummary, val items: List<Item>, val total: Int)

sealed interface HomeState {
    data object Loading : HomeState
    data class Error(val message: String, val isAuthError: Boolean = false) : HomeState
    data class Loaded(
        val rows: List<LibraryRow>,
        /// Started and not finished, most recently watched first — mirrors
        /// web/src/views/Libraries.tsx's `continuing` row. Fetched
        /// cross-library in one call, ahead of the per-library rows.
        val continueWatching: List<Item> = emptyList(),
        /// The episode after the last one finished, one per current series —
        /// mirrors web/src/views/Home.vue's `next` row. Its own
        /// cross-library call, same reasoning as [continueWatching].
        val upNext: List<Item> = emptyList(),
        val isRefreshing: Boolean = false,
    ) : HomeState
}

class HomeViewModel(private val repo: CatalogRepository) : ViewModel() {
    private val _state = MutableStateFlow<HomeState>(HomeState.Loading)
    val state: StateFlow<HomeState> = _state

    init {
        load()
    }

    fun load() {
        _state.value = HomeState.Loading
        viewModelScope.launch {
            try {
                val fetched = fetchHome()
                _state.value = HomeState.Loaded(fetched.rows, fetched.continueWatching, fetched.upNext)
            } catch (e: Exception) {
                _state.value = HomeState.Error(e.readableMessage(), e.isAuthError())
            }
        }
    }

    /// In-place re-fetch for an already-showing screen (back from the
    /// player, app foregrounded, pull-to-refresh, TV reload button) —
    /// keeps the current rows up instead of dropping to the Loading
    /// spinner, so watch-progress bars update without a flash or focus
    /// loss. The Loaded guard also skips the ON_RESUME that fires during
    /// init{}'s own load. isRefreshing drives the pull-to-refresh spinner
    /// and is only set for an actual pull gesture — resume/reload-button
    /// triggers refresh silently since the user didn't ask for a
    /// loading indicator there.
    fun refresh(showIndicator: Boolean = false) {
        val current = _state.value as? HomeState.Loaded ?: return
        if (showIndicator) {
            _state.value = current.copy(isRefreshing = true)
        }
        viewModelScope.launch {
            _state.value = try {
                val fetched = fetchHome()
                HomeState.Loaded(fetched.rows, fetched.continueWatching, fetched.upNext)
            } catch (e: Exception) {
                current.copy(isRefreshing = false)
            }
        }
    }

    private data class HomeFetch(
        val continueWatching: List<Item>,
        val upNext: List<Item>,
        val rows: List<LibraryRow>,
    )

    private suspend fun fetchHome(): HomeFetch = coroutineScope {
        // Cross-library and in one request — recency only means anything
        // across the whole set, and a per-library fetch could not be
        // merged since the hub doesn't return the timestamp it sorted by.
        // It is its own endpoint now rather than a flag on the item list,
        // and each row names the library it came from.
        val continueWatchingDeferred = async {
            repo.continueWatching(limit = CONTINUE_WATCHING_SIZE).items
        }
        // Same reasoning, its own endpoint: the series' last-watched
        // timestamp that orders this row isn't part of an item row either,
        // so it has to be a cross-library call too.
        val upNextDeferred = async {
            repo.upNext(limit = UP_NEXT_SIZE).items
        }
        val libraries = repo.libraries()
        // One request per library, concurrently — the items
        // endpoint has no "top N per library" shape, mirroring
        // web/src/views/Libraries.tsx:31-34.
        val rows = libraries
            .map { library ->
                async {
                    val response = repo.items(library = library.id, sort = "-added", limit = ROW_SIZE)
                    LibraryRow(library, response.items, response.total)
                }
            }
            .awaitAll()
            .filter { it.items.isNotEmpty() }
        HomeFetch(continueWatchingDeferred.await(), upNextDeferred.await(), rows)
    }

    private companion object {
        const val ROW_SIZE = 20
        const val CONTINUE_WATCHING_SIZE = 12
        const val UP_NEXT_SIZE = 12
    }
}
