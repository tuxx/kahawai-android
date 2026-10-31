package com.kolktech.kahawai.ui.detail

import android.app.Application
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Tv
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.adaptive.currentWindowAdaptiveInfoV2
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.min
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.window.core.layout.WindowSizeClass
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import com.kolktech.kahawai.R
import com.kolktech.kahawai.data.network.dto.CatalogueChildGroup
import com.kolktech.kahawai.data.network.dto.ClientAudioStream
import com.kolktech.kahawai.data.network.dto.ClientVideoStream
import com.kolktech.kahawai.data.network.dto.Item
import com.kolktech.kahawai.data.network.dto.SubtitleTrack
import com.kolktech.kahawai.data.network.dto.negotiatedSource
import com.kolktech.kahawai.data.repository.CatalogRepository
import com.kolktech.kahawai.playback.groupSources
import com.kolktech.kahawai.playback.resolutionLabel
import com.kolktech.kahawai.ui.components.ErrorView
import com.kolktech.kahawai.ui.components.OnResumeEffect
import com.kolktech.kahawai.ui.components.PosterCard
import com.kolktech.kahawai.ui.components.WatchProgressBar
import com.kolktech.kahawai.ui.player.PlaybackPrefetch
import com.kolktech.kahawai.ui.player.PrefetchSource
import com.kolktech.kahawai.ui.player.audioCodecName
import com.kolktech.kahawai.ui.player.channelLabel
import com.kolktech.kahawai.util.formatDurationCoarse
import com.kolktech.kahawai.util.formatEndsAt

/// A thick, high-contrast ring around whatever holds D-pad focus. The
/// default Material focus indication is nearly invisible from couch
/// distance; every focusable control on this screen routes through this
/// so "where am I" is always obvious. The border is always present
/// (transparent when unfocused) so gaining focus never shifts layout.
@Composable
internal fun Modifier.dpadFocusBorder(shape: Shape = CircleShape): Modifier {
    var focused by remember { mutableStateOf(false) }
    return this
        .onFocusChanged { focused = it.isFocused }
        .border(
            width = 3.dp,
            color = if (focused) MaterialTheme.colorScheme.onBackground else Color.Transparent,
            shape = shape,
        )
}

/// Narrows a show's page to one season. [number] is null for the episodes
/// that carry no season number at all.
data class SeasonFilter(val number: Int?)

/// Server summaries are complete even when no episode rows have been loaded.
internal fun seasonGroups(groups: List<CatalogueChildGroup>, children: List<Item>): List<CatalogueChildGroup> =
    groups.filter { it.kind == "episode" }.takeIf { it.isNotEmpty() }
        ?: groupSeasons(children).map { (number, episodes) ->
            CatalogueChildGroup("episode", number, episodes.size, episodes.count { it.played })
        }

/// The seasons to offer as boxes, in the order the hub listed their
/// episodes — or none when there is nothing to choose between: a
/// single-season show (and an album, whose tracks carry no season) keeps
/// its flat list rather than hiding it behind one lone box.
internal fun groupSeasons(children: List<Item>): List<Pair<Int?, List<Item>>> =
    children.filter { it.kind == "episode" }
        .groupBy { it.season }
        .takeIf { it.size > 1 }
        ?.toList()
        .orEmpty()

private val SEASON_CARD_MIN_WIDTH = 110.dp

@Composable
fun DetailScreen(
    itemId: String,
    /// Navigation context from the row this was opened from — the item's
    /// own detail names no library, and its media type is what the
    /// account's track preferences are keyed by (see TrackChoice).
    libraryId: String,
    /// Set when this is one season's episode list rather than the show's
    /// own page — the same screen, a level further in.
    season: SeasonFilter?,
    repo: CatalogRepository,
    onOpenItem: (itemId: String, libraryId: String) -> Unit,
    onOpenSeason: (season: Int?) -> Unit,
    onPlay: (itemId: String, startMs: Long, audioTrack: Int, subtitleTrackId: Long?, prefetch: PlaybackPrefetch) -> Unit,
    /// Plays a child episode from a show or season page without opening
    /// its own page first. The player resolves its tracks itself.
    onPlayEpisode: (itemId: String, startMs: Long) -> Unit,
    onBack: () -> Unit,
    /// Straight back to Home, past every series/season page on the stack.
    onHome: () -> Unit,
    onSessionExpired: () -> Unit,
) {
    val application = LocalContext.current.applicationContext as Application
    val viewModel: DetailViewModel = viewModel(
        key = itemId,
        factory = viewModelFactory {
            initializer { DetailViewModel(application, repo, itemId, libraryId, season = season) }
        },
    )
    val state by viewModel.state.collectAsState()
    val transientError by viewModel.transientError.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }

    // The ViewModel outlives trips into the player (it's retained by this
    // destination's back-stack entry), so without this the screen would
    // redisplay the resume position from when it was FIRST opened —
    // "Resume at 1m" forever, no matter how far playback got.
    OnResumeEffect(viewModel::refresh)

    // A failed "Mark watched" toggle — the page and its data are intact,
    // so this is a line on it, not a replacement for it (mirrors
    // PlayerScreen's transientError/Snackbar handling).
    LaunchedEffect(transientError) {
        transientError?.let { message ->
            snackbarHostState.showSnackbar(message)
            viewModel.clearTransientError()
        }
    }

    val playButtonFocusRequester = remember { FocusRequester() }
    val firstChildFocusRequester = remember { FocusRequester() }
    val backButtonFocusRequester = remember { FocusRequester() }

    Box(Modifier.fillMaxSize()) {
        when (val s = state) {
            is DetailState.Loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            is DetailState.Error -> ErrorView(
                message = s.message,
                isAuthError = s.isAuthError,
                onRetry = { viewModel.load() },
                onSignInAgain = onSessionExpired,
            )
            // A season page stacked under a home feed episode (see
            // NavGraph's onOpenFeedItem) before anyone knew the show keeps
            // a flat list: it would be a copy of the show's own page, so
            // it hands straight back to that one instead of drawing.
            is DetailState.Loaded if season != null && seasonGroups(s.seasons, s.children).size <= 1 -> {
                LaunchedEffect(Unit) { onBack() }
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            }
            is DetailState.Loaded -> {
                // A show/album has no Play button (only Next up, when
                // there's an episode to go on with), so without this,
                // nothing on the page is ever focused and D-pad
                // navigation has no anchor to move from (arrow keys do
                // nothing). The Play button sits in the LazyColumn's
                // always-composed first item, so it's safe to focus
                // directly from here — but the first episode row is a
                // later lazy item that may not exist yet at this point,
                // so ChildRow requests its own focus once it's actually
                // composed (see its focusRequester handling) instead. The
                // same goes for the first season box.
                //
                // A multi-season show lists its seasons as boxes here and
                // leaves the episodes to each season's own page.
                val seasons = if (season == null) {
                    seasonGroups(s.seasons, s.children).takeIf { it.size > 1 }.orEmpty()
                } else {
                    emptyList()
                }
                val children = when {
                    season != null -> s.children.filter { it.season == season.number }
                    seasons.isNotEmpty() -> emptyList()
                    else -> s.children
                }
                if (s.detail.kind !in NOT_DIRECTLY_PLAYABLE || s.nextUp != null) {
                    LaunchedEffect(Unit) { playButtonFocusRequester.requestFocus() }
                } else if (children.isEmpty() && seasons.isEmpty()) {
                    LaunchedEffect(Unit) { backButtonFocusRequester.requestFocus() }
                }
                DetailContent(
                    state = s,
                    children = children,
                    seasons = seasons,
                    selectedSeason = season,
                    seasonLabel = season?.let { seasonLabel(it.number) },
                    repo = repo,
                    onOpenItem = onOpenItem,
                    onOpenSeason = onOpenSeason,
                    onPlay = onPlay,
                    onPlayEpisode = onPlayEpisode,
                    onSelectAudioTrack = viewModel::selectAudioTrackIndex,
                    onSelectSource = viewModel::selectSource,
                    onSelectSubtitleTrack = viewModel::selectSubtitleTrack,
                    onToggleWatched = viewModel::toggleWatched,
                    playButtonFocusRequester = playButtonFocusRequester,
                    firstChildFocusRequester = firstChildFocusRequester,
                )
            }
        }
        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier.align(Alignment.BottomCenter).padding(16.dp),
        )
        // Floating over the hero image (same treatment as the player's
        // own back button) rather than a full TopAppBar, which would
        // shove the artwork down and duplicate the title shown right
        // below it.
        Row(
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Surface(
                onClick = onBack,
                modifier = Modifier
                    .focusRequester(backButtonFocusRequester)
                    .dpadFocusBorder(),
                shape = CircleShape,
                color = Color.Black.copy(alpha = 0.5f),
            ) {
                Icon(
                    Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = stringResource(R.string.kw_back),
                    modifier = Modifier.padding(10.dp),
                    tint = Color.White,
                )
            }
            Surface(
                onClick = onHome,
                modifier = Modifier.dpadFocusBorder(),
                shape = CircleShape,
                color = Color.Black.copy(alpha = 0.5f),
            ) {
                Icon(
                    Icons.Filled.Home,
                    contentDescription = stringResource(R.string.kw_home),
                    modifier = Modifier.padding(10.dp),
                    tint = Color.White,
                )
            }
        }
    }
}

@Composable
private fun DetailContent(
    state: DetailState.Loaded,
    children: List<Item>,
    seasons: List<CatalogueChildGroup>,
    selectedSeason: SeasonFilter?,
    seasonLabel: String?,
    repo: CatalogRepository,
    onOpenItem: (itemId: String, libraryId: String) -> Unit,
    onOpenSeason: (season: Int?) -> Unit,
    onPlay: (itemId: String, startMs: Long, audioTrack: Int, subtitleTrackId: Long?, prefetch: PlaybackPrefetch) -> Unit,
    onPlayEpisode: (itemId: String, startMs: Long) -> Unit,
    onSelectAudioTrack: (Int) -> Unit,
    onSelectSource: (Int?) -> Unit,
    onSelectSubtitleTrack: (SubtitleTrack?) -> Unit,
    onToggleWatched: () -> Unit,
    playButtonFocusRequester: FocusRequester,
    firstChildFocusRequester: FocusRequester,
) {
    val detail = state.detail
    // Window size class rather than orientation: a portrait tablet or
    // unfolded foldable (medium+ width) has room for the side-by-side
    // layout even though it's "portrait", and a landscape phone (compact
    // height) is too short for the stacked header even when its width
    // lands under the medium breakpoint.
    val windowSizeClass = currentWindowAdaptiveInfoV2().windowSizeClass
    val useTwoPane = windowSizeClass.isWidthAtLeastBreakpoint(WindowSizeClass.WIDTH_DP_MEDIUM_LOWER_BOUND) ||
        !windowSizeClass.isHeightAtLeastBreakpoint(WindowSizeClass.HEIGHT_DP_MEDIUM_LOWER_BOUND)
    // The source negotiation chose, not whichever happens to be listed
    // first — its streams are what would actually be served.
    val primarySource = detail.negotiatedSource()
    val audioTracks = primarySource?.streams?.audio.orEmpty()
    val videoStream = primarySource?.streams?.video?.firstOrNull()
    val runtimeMs = primarySource?.streams?.durationMs ?: detail.resumeDurationMs
    val playable = detail.kind !in NOT_DIRECTLY_PLAYABLE

    val seasonGroup = selectedSeason?.let { selected -> state.seasons.find { it.number == selected.number } }
    val posterModel = seasonArtworkModel(repo, detail.libraryId, detail.id, seasonGroup)
    val backdropModel = seasonArtworkModel(repo, detail.libraryId, detail.id, seasonGroup, size = "thumb")

    // What PlayerViewModel would otherwise spend its own itemQuery round
    // trip re-deriving — this screen already has it (see PlaybackPrefetch).
    val prefetch = PlaybackPrefetch(
        state.subtitleTracks,
        detail.segments,
        detail.chapters,
        detail.parentId,
        sources = detail.sources.map {
            PrefetchSource(
                sourceId = it.sourceId,
                copyId = it.collectionItemId,
                mediaEntryId = it.mediaEntryId,
                audio = it.streams?.audio.orEmpty(),
            )
        },
        sourceId = state.sourceId,
        audioTrack = state.selectedAudioTrackIndex,
    )
    val playFrom = { startMs: Long ->
        onPlay(detail.id, startMs, state.selectedAudioTrackIndex, state.selectedSubtitleTrack?.id, prefetch)
    }

    val title = @Composable {
        // Remaining, not the full runtime: resuming partway through should
        // still point at when playback would actually finish if started now.
        val remainingMs = runtimeMs?.minus(detail.resumePositionMs?.coerceAtLeast(0) ?: 0)?.coerceAtLeast(0)
        TitleBlock(
            title = detail.title,
            meta = listOfNotNull(
                detail.parentTitle,
                seasonLabel,
                detail.season?.let { "S$it" }?.plus(detail.episode?.let { ":E$it" } ?: ""),
                detail.year?.toString(),
            ).joinToString(" · "),
            rating = detail.metadata?.rating,
            facts = listOfNotNull(
                runtimeMs?.takeIf { it > 0 }?.let { formatDurationCoarse(it) },
                remainingMs?.takeIf { it > 0 && playable }?.let { formatEndsAt(it) },
            ),
            genres = detail.metadata?.genres.orEmpty(),
        )
    }

    val body = @Composable {
        if (playable) {
            TechBadges(
                techBadges(videoStream, audioTracks.getOrNull(state.selectedAudioTrackIndex)),
                modifier = Modifier.padding(top = 14.dp),
            )
            ProgressLine(
                positionMs = detail.resumePositionMs,
                durationMs = detail.resumeDurationMs,
                played = detail.played,
                modifier = Modifier.padding(top = 14.dp),
            )
            val resumeMs = detail.resumePositionMs ?: 0
            PlayActions(
                playLabel = stringResource(if (resumeMs > 0) R.string.detail_resume else R.string.detail_play),
                onPlay = { playFrom(resumeMs) },
                onStartOver = if (resumeMs > 0) ({ playFrom(0) }) else null,
                played = detail.played,
                watchedActionInFlight = state.watchedActionInFlight,
                onToggleWatched = onToggleWatched,
                playFocusRequester = playButtonFocusRequester,
                modifier = Modifier.padding(top = 12.dp),
            )
        }
        state.nextUp?.let { episode ->
            NextUp(
                episode = episode,
                onPlay = { onPlayEpisode(episode.id, episode.resumePositionMs ?: 0) },
                focusRequester = playButtonFocusRequester,
                modifier = Modifier.padding(top = 16.dp),
            )
        }
        detail.metadata?.overview?.takeIf { it.isNotBlank() }?.let {
            Overview(it, Modifier.padding(top = 16.dp))
        }
        if (playable) {
            PlaybackOptions(
                works = groupSources(detail.sources),
                selectedSourceId = state.sourceId,
                automaticSourceId = state.automaticSourceId,
                sourcePinned = state.sourcePinned,
                audioTracks = audioTracks,
                selectedAudioTrackIndex = state.selectedAudioTrackIndex,
                subtitleTracks = state.subtitleTracks,
                selectedSubtitleTrack = state.selectedSubtitleTrack,
                onSelectSource = onSelectSource,
                onSelectAudioTrack = onSelectAudioTrack,
                onSelectSubtitleTrack = onSelectSubtitleTrack,
                modifier = Modifier.padding(top = 20.dp),
            )
        }
    }

    // Everything under the header, shared by both layouts. Rows that
    // scroll sideways (chapters, cast) run edge to edge of the column, so
    // each item pads itself rather than the list padding them all.
    fun LazyListScope.sections() {
        if (playable && hasNamedChapters(detail.chapters)) {
            item { ChaptersRow(detail.chapters, onPlayChapter = playFrom, modifier = Modifier.padding(top = 24.dp)) }
        }
        if (seasons.isNotEmpty()) {
            item {
                SectionTitle(
                    stringResource(R.string.detail_seasons),
                    seasons.size,
                    Modifier.padding(start = 16.dp, end = 16.dp, top = 24.dp, bottom = 12.dp),
                )
            }
            item {
                SeasonGrid(
                    seasons = seasons,
                    repo = repo,
                    libraryId = detail.libraryId,
                    parentId = detail.id,
                    onOpenSeason = onOpenSeason,
                    // Next up holds focus when there is one.
                    firstFocusRequester = firstChildFocusRequester.takeIf { state.nextUp == null },
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
            }
        }
        if (children.isNotEmpty()) {
            item {
                SectionTitle(
                    stringResource(if (children.first().kind == "episode") R.string.detail_episodes else R.string.detail_tracks),
                    children.size,
                    Modifier.padding(start = 16.dp, end = 16.dp, top = 24.dp, bottom = 4.dp),
                )
            }
            itemsIndexed(children, key = { _, child -> child.id }) { index, child ->
                ChildRow(
                    child,
                    onOpenItem,
                    onPlay = if (child.kind == "episode") ({ onPlayEpisode(child.id, child.resumePositionMs ?: 0) }) else null,
                    focusRequester = if (index == 0 && state.nextUp == null) firstChildFocusRequester else null,
                )
            }
        }
        detail.metadata?.cast?.takeIf { it.isNotEmpty() }?.let { cast ->
            item { CastRow(cast, Modifier.padding(top = 24.dp)) }
        }
        item { Spacer(Modifier.height(24.dp)) }
    }

    if (useTwoPane) {
        // The poster stays fixed on the left; info + episodes scroll
        // together in their own column on the right (Netflix/TV-style
        // detail layout) rather than the whole page — including the
        // poster — scrolling as one unit. This also means the episode
        // list sits in a LazyColumn with a compact sibling (just the
        // text info, no full-height hero image eating the viewport), so
        // the first row is reliably composed immediately instead of
        // being deferred below the fold.
        BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
            DetailBackdrop(backdropModel, Modifier.matchParentSize())
            // Full-height 2:3 poster where the window is short (landscape
            // phone/TV — the poster column fills the height exactly), but
            // never more than 40% of the width, so a tall window (portrait
            // tablet) keeps the poster modest and leaves the width to the
            // info/episodes pane.
            val posterWidth = min(maxHeight * 2f / 3f, maxWidth * 0.4f)
            Row(modifier = Modifier.fillMaxSize()) {
                Box(
                    modifier = Modifier
                        .width(posterWidth)
                        .aspectRatio(2f / 3f),
                ) {
                    AsyncImage(
                        model = posterModel,
                        contentDescription = detail.title,
                        contentScale = ContentScale.Crop,
                        placeholder = painterResource(R.drawable.placeholder_poster),
                        error = painterResource(R.drawable.placeholder_poster),
                        modifier = Modifier.fillMaxSize(),
                    )
                    WatchProgressBar(
                        positionMs = detail.resumePositionMs,
                        durationMs = detail.resumeDurationMs,
                        played = detail.played,
                        modifier = Modifier.align(Alignment.BottomCenter),
                    )
                }
                LazyColumn(
                    modifier = Modifier.weight(1f).fillMaxHeight(),
                    contentPadding = PaddingValues(top = 16.dp),
                ) {
                    item {
                        Column(Modifier.padding(horizontal = 16.dp)) {
                            title()
                            body()
                        }
                    }
                    sections()
                }
            }
        }
    } else {
        LazyColumn(modifier = Modifier.fillMaxSize()) {
            item {
                Column {
                    DetailHero(
                        backdropModel = backdropModel,
                        posterModel = posterModel,
                        contentDescription = detail.title,
                        positionMs = detail.resumePositionMs,
                        durationMs = detail.resumeDurationMs,
                        played = detail.played,
                        content = title,
                    )
                    Column(Modifier.padding(horizontal = 16.dp)) { body() }
                }
            }
            sections()
        }
    }
}

/// The picture and sound facts worth a chip: resolution class (the crop
/// detail stays in the Version picker), codec, bit depth, and the audio
/// Play would use.
@Composable
private fun techBadges(video: ClientVideoStream?, audio: ClientAudioStream?): List<String> {
    val mono = stringResource(R.string.channels_mono)
    val stereo = stringResource(R.string.channels_stereo)
    return listOfNotNull(
        video?.resolutionLabel()?.substringBefore(" (")?.takeIf { it.isNotBlank() },
        video?.codec?.takeIf { it.isNotBlank() }?.uppercase(),
        // The probe no longer reports an HDR label, only the bit depth it
        // measured. 10-bit is the honest thing to show for it.
        video?.bitDepth?.takeIf { it > 8 }?.let { "$it-bit" },
        audio?.let { stream ->
            listOfNotNull(audioCodecName(stream.codec), channelLabel(stream.channels, mono, stereo))
                .joinToString(" ")
                .ifBlank { null }
        },
    )
}

@Composable
private fun seasonLabel(number: Int?): String = when (number) {
    null -> stringResource(R.string.detail_season_other)
    0 -> stringResource(R.string.detail_season_specials)
    else -> stringResource(R.string.detail_season, number)
}

/// A season's cover URL stays the same while the hub backfills its art
/// (the hub keys its own cache off the resolved artwork), so the client
/// cache key carries the summary's artwork value: it changes, and the
/// cover reloads, only when new art actually arrives. Until then a
/// return from the player is served from Coil's caches without a flash.
@Composable
private fun seasonArtworkModel(
    repo: CatalogRepository,
    library: String,
    parent: String,
    group: CatalogueChildGroup?,
    size: String? = "card",
): Any? {
    val artwork = group?.metadata?.description?.artwork
    if (artwork.isNullOrEmpty()) return repo.artworkUrl(library, parent, size)
    val url = repo.artworkUrl(library, parent, size, season = group.number?.toString() ?: "absolute")
        ?: return null
    val context = LocalContext.current
    return remember(context, url, artwork) {
        val key = "$url#${artwork.joinToString("|")}"
        ImageRequest.Builder(context)
            .data(url)
            .memoryCacheKey(key)
            .diskCacheKey(key)
            .build()
    }
}

/// The show's seasons as poster boxes, laid out like the library grid. Rows
/// are chunked by hand rather than a LazyVerticalGrid because this sits
/// inside the page's own LazyColumn, and there are only ever a handful.
/// Covers use the hub's season projection; absent metadata keeps show art.
@Composable
private fun SeasonGrid(
    seasons: List<CatalogueChildGroup>,
    repo: CatalogRepository,
    libraryId: String,
    parentId: String,
    onOpenSeason: (season: Int?) -> Unit,
    firstFocusRequester: FocusRequester?,
    modifier: Modifier = Modifier,
) {
    BoxWithConstraints(modifier = modifier.fillMaxWidth()) {
        val columns = (maxWidth / SEASON_CARD_MIN_WIDTH).toInt().coerceAtLeast(1)
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            seasons.chunked(columns).forEachIndexed { rowIndex, row ->
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    row.forEachIndexed { index, season ->
                        PosterCard(
                            artworkModel = seasonArtworkModel(repo, libraryId, parentId, season),
                            title = seasonLabel(season.number),
                            subtitle = when {
                                season.played == season.total -> stringResource(R.string.watched)
                                season.played > 0 -> stringResource(R.string.detail_season_progress, season.played, season.total)
                                else -> pluralStringResource(R.plurals.detail_season_episodes, season.total, season.total)
                            },
                            icon = Icons.Filled.Tv,
                            onClick = { onOpenSeason(season.number) },
                            modifier = Modifier.weight(1f),
                            focusRequester = if (rowIndex == 0 && index == 0) firstFocusRequester else null,
                            positionMs = season.played.toLong(),
                            durationMs = season.total.toLong(),
                            played = season.played == season.total,
                        )
                    }
                    // Keeps a short last row's boxes the same width as the rest.
                    repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }
    }
}

@Composable
private fun ChildRow(
    child: Item,
    onOpenItem: (itemId: String, libraryId: String) -> Unit,
    /// Starts it straight from the list; null for rows that only open.
    onPlay: (() -> Unit)?,
    focusRequester: FocusRequester? = null,
) {
    var focused by remember { mutableStateOf(false) }
    // Requested from here rather than by the caller: inside a lazy list,
    // a focusRequester isn't attached to anything until this composable
    // itself actually runs — a caller-side LaunchedEffect can fire a
    // frame too early, before the row has been composed, and silently
    // no-op (this is exactly what broke D-pad Down into the episode list).
    if (focusRequester != null) {
        LaunchedEffect(Unit) { focusRequester.requestFocus() }
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 2.dp)
            .clip(RoundedCornerShape(12.dp))
            .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
            .onFocusChanged { focused = it.isFocused }
            .background(if (focused) MaterialTheme.colorScheme.primary.copy(alpha = 0.2f) else Color.Transparent)
            .border(
                width = 2.dp,
                color = if (focused) MaterialTheme.colorScheme.primary else Color.Transparent,
                shape = RoundedCornerShape(12.dp),
            )
            .clickable { onOpenItem(child.id, child.libraryId) }
            .padding(horizontal = 8.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // The number, or a tick once it's watched — the list reads as a
        // checklist of where the viewer is.
        Box(
            Modifier
                .size(40.dp)
                .clip(CircleShape)
                .background(
                    if (child.played) MaterialTheme.colorScheme.primary.copy(alpha = 0.2f) else MaterialTheme.colorScheme.surfaceVariant,
                ),
            contentAlignment = Alignment.Center,
        ) {
            if (child.played) {
                Icon(
                    Icons.Filled.Check,
                    contentDescription = stringResource(R.string.watched),
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp),
                )
            } else {
                Text(child.episode?.toString() ?: "•", style = MaterialTheme.typography.titleSmall)
            }
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(child.title, style = MaterialTheme.typography.bodyLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
            val resumeMs = child.resumePositionMs?.takeIf { it > 0 && !child.played }
            val duration = child.resumeDurationMs?.takeIf { it > 0 }
            val sub = listOfNotNull(
                if (child.kind == "episode") episodeCode(child) else null,
                formatAirDate(child.premiered),
                if (resumeMs != null && duration != null) {
                    stringResource(R.string.detail_time_left, formatDurationCoarse((duration - resumeMs).coerceAtLeast(0)))
                } else {
                    null
                },
            ).joinToString(" · ")
            if (sub.isNotEmpty()) {
                Text(
                    sub,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
            if (resumeMs != null) {
                WatchProgressBar(
                    positionMs = child.resumePositionMs,
                    durationMs = child.resumeDurationMs,
                    played = child.played,
                    modifier = Modifier.padding(top = 6.dp).clip(CircleShape),
                )
            }
        }
        if (onPlay != null) {
            Spacer(Modifier.width(8.dp))
            IconButton(onClick = onPlay, modifier = Modifier.dpadFocusBorder()) {
                Icon(
                    Icons.Filled.PlayArrow,
                    contentDescription = stringResource(R.string.detail_play),
                    tint = MaterialTheme.colorScheme.primary,
                )
            }
        }
    }
}
