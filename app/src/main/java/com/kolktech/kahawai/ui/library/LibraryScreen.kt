package com.kolktech.kahawai.ui.library

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.kolktech.kahawai.R
import com.kolktech.kahawai.data.repository.CatalogRepository
import com.kolktech.kahawai.ui.components.ErrorView
import com.kolktech.kahawai.ui.components.OnResumeEffect
import com.kolktech.kahawai.ui.components.PosterCard

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryScreen(
    libraryId: String,
    libraryName: String,
    repo: CatalogRepository,
    onOpenItem: (itemId: String, libraryId: String) -> Unit,
    onBack: () -> Unit,
    onSessionExpired: () -> Unit,
) {
    val viewModel: LibraryViewModel = viewModel(
        key = libraryId,
        factory = viewModelFactory { initializer { LibraryViewModel(repo, libraryId) } },
    )
    val state by viewModel.state.collectAsState()
    OnResumeEffect(viewModel::refresh)

    Column(modifier = Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text(libraryName) },
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.kw_back))
                }
            },
        )
        when (val s = state) {
            is LibraryState.Loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            is LibraryState.Error -> ErrorView(
                message = s.message,
                isAuthError = s.isAuthError,
                onRetry = { viewModel.load() },
                onSignInAgain = onSessionExpired,
            )
            is LibraryState.Loaded -> {
                val gridState = rememberLazyGridState()
                // Fetch the next page once the viewer scrolls within a
                // couple of rows of the end, rather than waiting for a
                // dedicated "load more" tap.
                val shouldLoadMore by remember {
                    derivedStateOf {
                        val last = gridState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
                        last >= s.items.size - 6
                    }
                }
                LaunchedEffect(shouldLoadMore, s.items.size) {
                    if (shouldLoadMore) viewModel.loadMore()
                }
                // PosterCard requests its own focus once composed (it's
                // the only point that's guaranteed the item actually
                // exists in the lazy grid yet), so this only needs to
                // hand the first item a requester — not call it itself.
                val firstItemFocusRequester = remember { FocusRequester() }
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(minSize = 110.dp),
                    state = gridState,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    contentPadding = PaddingValues(16.dp),
                    modifier = Modifier.fillMaxSize(),
                ) {
                    itemsIndexed(s.items, key = { _, item -> item.id }) { index, item ->
                        PosterCard(
                            item,
                            repo,
                            { id -> onOpenItem(id, item.libraryId ?: libraryId) },
                            focusRequester = if (index == 0) firstItemFocusRequester else null,
                        )
                    }
                    if (s.loadingMore) {
                        item(span = { androidx.compose.foundation.lazy.grid.GridItemSpan(maxLineSpan) }) {
                            Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
                                CircularProgressIndicator()
                            }
                        }
                    }
                }
            }
        }
    }
}
