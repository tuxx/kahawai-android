package com.kolktech.kahawai.ui.home

import android.content.res.Configuration
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.kolktech.kahawai.R
import com.kolktech.kahawai.data.network.dto.Item
import com.kolktech.kahawai.data.repository.CatalogRepository
import com.kolktech.kahawai.ui.components.ContinueWatchingCard
import com.kolktech.kahawai.ui.components.ErrorView
import com.kolktech.kahawai.ui.components.OnResumeEffect
import com.kolktech.kahawai.ui.components.PosterCard
import com.kolktech.kahawai.ui.components.ReturnFocus
import com.kolktech.kahawai.ui.components.rememberReturnFocus
import com.kolktech.kahawai.ui.theme.KahawaiOnSurfaceVariant

private val POSTER_MIN_WIDTH = 100.dp
// Wider than a poster column: these cards are 16:9, so a poster-width
// column would make each one nearly square-cropped instead of a banner.
private val CONTINUE_WATCHING_MIN_WIDTH = 200.dp
private val GRID_SPACING = 10.dp
private val HEADER_SHAPE = RoundedCornerShape(8.dp)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    repo: CatalogRepository,
    onOpenItem: (itemId: String, libraryId: String) -> Unit,
    onOpenFeedItem: (item: Item) -> Unit,
    onOpenLibrary: (id: String, name: String) -> Unit,
    onSearch: () -> Unit,
    onOpenSettings: () -> Unit,
    onLogout: () -> Unit,
    onSessionExpired: () -> Unit,
) {
    val viewModel: HomeViewModel = viewModel(
        factory = viewModelFactory { initializer { HomeViewModel(repo) } },
    )
    val state by viewModel.state.collectAsState()
    OnResumeEffect(viewModel::refresh)
    var menuExpanded by remember { mutableStateOf(false) }
    // TV has no touchscreen to pull-to-refresh with (see the optional
    // android.hardware.touchscreen feature in the manifest), so it gets an
    // explicit reload button next to search instead.
    val isTelevision = LocalConfiguration.current.uiMode and Configuration.UI_MODE_TYPE_MASK ==
        Configuration.UI_MODE_TYPE_TELEVISION

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            // No title/wordmark — the library rows below start right
            // under this row, so it reads as icon controls floating over
            // the full-bleed catalog rather than a separate branded bar.
            TopAppBar(
                title = {},
                navigationIcon = {
                    IconButton(onClick = { menuExpanded = true }) {
                        Icon(
                            Icons.Default.Menu,
                            contentDescription = stringResource(R.string.home_menu),
                            tint = KahawaiOnSurfaceVariant,
                        )
                    }
                    // DropdownMenu (not a swipe drawer) so this app's
                    // TV/D-pad users can reach it too — it's just a popup
                    // anchored to a focusable IconButton.
                    DropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.home_settings)) },
                            leadingIcon = { Icon(Icons.Default.Settings, contentDescription = null) },
                            onClick = { menuExpanded = false; onOpenSettings() },
                        )
                        HorizontalDivider()
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.home_log_out)) },
                            leadingIcon = { Icon(Icons.AutoMirrored.Filled.Logout, contentDescription = null) },
                            onClick = { menuExpanded = false; onLogout() },
                        )
                    }
                },
                actions = {
                    if (isTelevision) {
                        IconButton(onClick = viewModel::refresh) {
                            Icon(
                                Icons.Default.Refresh,
                                contentDescription = stringResource(R.string.home_reload),
                                tint = KahawaiOnSurfaceVariant,
                            )
                        }
                    }
                    IconButton(onClick = onSearch) {
                        Icon(
                            Icons.Default.Search,
                            contentDescription = stringResource(R.string.search),
                            tint = KahawaiOnSurfaceVariant,
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                    titleContentColor = MaterialTheme.colorScheme.onBackground,
                ),
            )
        },
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
            when (val s = state) {
                is HomeState.Loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
                is HomeState.Error -> ErrorView(
                    message = s.message,
                    isAuthError = s.isAuthError,
                    onRetry = { viewModel.load() },
                    onSignInAgain = onSessionExpired,
                )
                is HomeState.Loaded -> {
                    val content: @Composable BoxScope.() -> Unit = {
                        if (s.rows.isEmpty() && s.continueWatching.isEmpty() && s.upNext.isEmpty()) {
                            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                Text(stringResource(R.string.home_no_libraries))
                            }
                        } else {
                            // The very first poster on screen needs a D-pad
                            // focus target the instant the grid appears —
                            // otherwise there's nothing highlighted for a TV
                            // remote to act on until the user presses a key.
                            // PosterCard requests its own focus once composed
                            // (see its focusRequester handling) rather than
                            // this scope guessing when that's safe to do.
                            val firstItemFocusRequester = remember { FocusRequester() }
                            // Coming back from an item's page, focus goes
                            // to that item instead.
                            val returnFocus = rememberReturnFocus()

                            // Column count is derived once from the measured
                            // width (so landscape/tablet fills the row instead
                            // of showing two oversized posters), then reused
                            // for every library's chunking below.
                            BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
                                val columns = ((maxWidth + GRID_SPACING) / (POSTER_MIN_WIDTH + GRID_SPACING))
                                    .toInt()
                                    .coerceAtLeast(2)
                                val continueWatchingColumns =
                                    ((maxWidth + GRID_SPACING) / (CONTINUE_WATCHING_MIN_WIDTH + GRID_SPACING))
                                        .toInt()
                                        .coerceAtLeast(1)
                                // Each grid row of ~[columns] posters is its own
                                // LazyColumn item (not one item per whole
                                // library) so scrolling a library into view
                                // only has to compose/measure and kick off
                                // Coil requests for the couple of poster rows
                                // actually entering the viewport per frame,
                                // rather than all ROW_SIZE posters in that
                                // library at once — that all-at-once burst was
                                // the cause of the scroll stutter.
                                LazyColumn(modifier = Modifier.fillMaxSize()) {
                                    if (s.continueWatching.isNotEmpty()) {
                                        item(key = "continue-watching-header") {
                                            ContinueWatchingHeader()
                                        }
                                        itemsIndexed(
                                            s.continueWatching.chunked(continueWatchingColumns),
                                            key = { _, chunk -> "continue-watching-${chunk.first().id}" },
                                        ) { chunkIndex, chunk ->
                                            ContinueWatchingGridRow(
                                                chunk,
                                                continueWatchingColumns,
                                                repo,
                                                onOpenFeedItem,
                                                returnFocus,
                                                keyPrefix = "continue-watching",
                                                firstItemFocusRequester = if (chunkIndex == 0) {
                                                    firstItemFocusRequester
                                                } else {
                                                    null
                                                },
                                            )
                                        }
                                    }
                                    // The episode after the last one finished, one per
                                    // current series — mirrors web/src/views/Home.vue's
                                    // "Up next" row. No progress bar: see
                                    // ContinueWatchingCard's showProgress doc.
                                    if (s.upNext.isNotEmpty()) {
                                        item(key = "up-next-header") {
                                            UpNextHeader()
                                        }
                                        itemsIndexed(
                                            s.upNext.chunked(continueWatchingColumns),
                                            key = { _, chunk -> "up-next-${chunk.first().id}" },
                                        ) { chunkIndex, chunk ->
                                            ContinueWatchingGridRow(
                                                chunk,
                                                continueWatchingColumns,
                                                repo,
                                                onOpenFeedItem,
                                                returnFocus,
                                                keyPrefix = "up-next",
                                                firstItemFocusRequester = if (s.continueWatching.isEmpty() &&
                                                    chunkIndex == 0
                                                ) {
                                                    firstItemFocusRequester
                                                } else {
                                                    null
                                                },
                                                showProgress = false,
                                            )
                                        }
                                    }
                                    s.rows.forEachIndexed { rowIndex, row ->
                                        item(key = "${row.library.id}-header") {
                                            LibraryHeader(row, onOpenLibrary)
                                        }
                                        itemsIndexed(
                                            row.items.chunked(columns),
                                            key = { _, chunk -> "${row.library.id}-${chunk.first().id}" },
                                        ) { chunkIndex, chunk ->
                                            PosterGridRow(
                                                chunk,
                                                columns,
                                                repo,
                                                onOpenItem,
                                                returnFocus,
                                                keyPrefix = row.library.id,
                                                firstItemFocusRequester = if (s.continueWatching.isEmpty() &&
                                                    s.upNext.isEmpty() &&
                                                    rowIndex == 0 &&
                                                    chunkIndex == 0
                                                ) {
                                                    firstItemFocusRequester
                                                } else {
                                                    null
                                                },
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }

                    if (isTelevision) {
                        // TV has no touch to drag a pull gesture with, and
                        // PullToRefreshBox's nested scroll connection was
                        // misreading D-pad-driven focus scrolling as a slow
                        // pull, animating the indicator down on its own —
                        // so TV skips the wrapper entirely and relies on the
                        // explicit reload button in the top bar instead.
                        content()
                    } else {
                        // Wraps both branches above (not just the grid) so the
                        // empty-library state can also be pulled to refresh.
                        // isRefreshing only turns on for this drag-triggered
                        // path — resume calls refresh() without the
                        // indicator, since that isn't a pull gesture.
                        PullToRefreshBox(
                            isRefreshing = s.isRefreshing,
                            onRefresh = { viewModel.refresh(showIndicator = true) },
                            modifier = Modifier.fillMaxSize(),
                            content = content,
                        )
                    }
                }
            }
        }
    }
}

/// No "see all" and no [onOpenLibrary] target: continue-watching is a
/// cross-library row with no library of its own to open — see
/// [HomeState.Loaded.continueWatching].
@Composable
private fun ContinueWatchingHeader() {
    Text(
        stringResource(R.string.home_continue_watching),
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.onBackground,
        modifier = Modifier.fillMaxWidth().padding(top = 20.dp, start = 16.dp, end = 16.dp, bottom = 8.dp),
    )
}

/// No "see all" and no [onOpenLibrary] target, same reasoning as
/// [ContinueWatchingHeader] — up-next is cross-library with no library of
/// its own, see [HomeState.Loaded.upNext].
@Composable
private fun UpNextHeader() {
    Text(
        stringResource(R.string.home_up_next),
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.onBackground,
        modifier = Modifier.fillMaxWidth().padding(top = 20.dp, start = 16.dp, end = 16.dp, bottom = 8.dp),
    )
}

/// The whole bar is the "see all" target rather than a small button at its
/// end: one wide tap target on touch, and a single D-pad stop per library
/// on TV — moving up/down through the grid lands on the title itself, so a
/// remote never has to steer sideways to a corner button to open a library.
@Composable
private fun LibraryHeader(
    row: LibraryRow,
    onOpenLibrary: (id: String, name: String) -> Unit,
) {
    // Same reasoning as PosterCard: D-pad focus needs its own obvious
    // affordance, so the bar gets a tinted fill and the accent color.
    var focused by remember { mutableStateOf(false) }
    val contentColor = if (focused) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onBackground
    val secondaryColor = if (focused) MaterialTheme.colorScheme.primary else KahawaiOnSurfaceVariant
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 4.dp, start = 8.dp, end = 8.dp, bottom = 4.dp)
            .clip(HEADER_SHAPE)
            .background(if (focused) MaterialTheme.colorScheme.primary.copy(alpha = 0.16f) else Color.Transparent)
            .onFocusChanged { focused = it.isFocused }
            .clickable(
                onClickLabel = stringResource(R.string.home_open_library, row.library.name),
                role = Role.Button,
            ) { onOpenLibrary(row.library.id, row.library.name) }
            .heightIn(min = 48.dp)
            .padding(horizontal = 8.dp),
    ) {
        Text(
            row.library.name,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = contentColor,
        )
        Icon(
            Icons.AutoMirrored.Filled.ArrowForward,
            contentDescription = null,
            tint = secondaryColor,
            modifier = Modifier.padding(start = 6.dp).size(16.dp),
        )
        Spacer(modifier = Modifier.weight(1f))
        if (row.total > row.items.size) {
            Text(
                stringResource(R.string.home_see_all, row.total),
                style = MaterialTheme.typography.bodySmall,
                color = secondaryColor,
            )
        }
    }
}

@Composable
private fun ContinueWatchingGridRow(
    chunk: List<Item>,
    columns: Int,
    repo: CatalogRepository,
    onOpenItem: (item: Item) -> Unit,
    returnFocus: ReturnFocus,
    keyPrefix: String,
    firstItemFocusRequester: FocusRequester? = null,
    showProgress: Boolean = true,
) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(GRID_SPACING),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
    ) {
        chunk.forEachIndexed { index, item ->
            val key = "$keyPrefix-${item.id}"
            ContinueWatchingCard(
                item,
                repo,
                { returnFocus.onOpen(key); onOpenItem(item) },
                modifier = Modifier.weight(1f).onFocusChanged { if (it.isFocused) returnFocus.onFocused(key) },
                focusRequester = returnFocus.requesterFor(key)
                    ?: firstItemFocusRequester.takeIf { index == 0 && !returnFocus.hasTarget },
                showProgress = showProgress,
            )
        }
        repeat(columns - chunk.size) {
            Spacer(modifier = Modifier.weight(1f))
        }
    }
}

@Composable
private fun PosterGridRow(
    chunk: List<Item>,
    columns: Int,
    repo: CatalogRepository,
    onOpenItem: (itemId: String, libraryId: String) -> Unit,
    returnFocus: ReturnFocus,
    keyPrefix: String,
    firstItemFocusRequester: FocusRequester? = null,
) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(GRID_SPACING),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
    ) {
        chunk.forEachIndexed { index, item ->
            val key = "$keyPrefix-${item.id}"
            PosterCard(
                item,
                repo,
                { id -> returnFocus.onOpen(key); onOpenItem(id, item.libraryId) },
                modifier = Modifier.weight(1f).onFocusChanged { if (it.isFocused) returnFocus.onFocused(key) },
                focusRequester = returnFocus.requesterFor(key)
                    ?: firstItemFocusRequester.takeIf { index == 0 && !returnFocus.hasTarget },
            )
        }
        repeat(columns - chunk.size) {
            Spacer(modifier = Modifier.weight(1f))
        }
    }
}
