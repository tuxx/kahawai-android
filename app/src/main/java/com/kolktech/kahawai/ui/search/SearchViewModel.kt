package com.kolktech.kahawai.ui.search

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import com.kolktech.kahawai.data.network.dto.Item
import com.kolktech.kahawai.data.network.isAuthError
import com.kolktech.kahawai.data.network.readableMessage
import com.kolktech.kahawai.data.repository.CatalogRepository

sealed interface SearchState {
    data object Idle : SearchState
    data object Loading : SearchState
    data class Error(val message: String, val isAuthError: Boolean = false) : SearchState
    data class Loaded(val items: List<Item>, val total: Int) : SearchState
}

private const val DEBOUNCE_MS = 300L
private const val RESULT_LIMIT = 60

@OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
class SearchViewModel(private val repo: CatalogRepository) : ViewModel() {
    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query

    private val _state = MutableStateFlow<SearchState>(SearchState.Idle)
    val state: StateFlow<SearchState> = _state

    init {
        viewModelScope.launch {
            _query
                .debounce(DEBOUNCE_MS)
                .distinctUntilChanged()
                .collectLatest { q -> search(q) }
        }
    }

    private suspend fun search(q: String) {
        if (q.isBlank()) {
            _state.value = SearchState.Idle
            return
        }
        _state.value = SearchState.Loading
        try {
            val result = repo.search(q, limit = RESULT_LIMIT)
            _state.value = SearchState.Loaded(result.items, result.total)
        } catch (e: Exception) {
            _state.value = SearchState.Error(e.readableMessage(), e.isAuthError())
        }
    }

    fun onQueryChange(q: String) {
        _query.value = q
    }

    /// The debounced flow's `distinctUntilChanged` won't re-run the same
    /// query, so a retry after a failure re-issues it directly instead of
    /// going back through the query flow.
    fun retry() {
        viewModelScope.launch { search(_query.value) }
    }

    /// In-place re-run of the current query when results are already up
    /// (back from the player, app foregrounded), so watch-progress bars
    /// stay current. Unlike retry()/search() this never sets Loading —
    /// the visible results are swapped, not blanked — and a failure
    /// keeps them as-is.
    fun refresh() {
        val q = _query.value
        if (_state.value !is SearchState.Loaded || q.isBlank()) return
        viewModelScope.launch {
            try {
                val result = repo.search(q, limit = RESULT_LIMIT)
                _state.value = SearchState.Loaded(result.items, result.total)
            } catch (e: Exception) {
                // Keep showing the results we have.
            }
        }
    }
}
