package com.kolktech.kahawai.ui.detail

import android.app.Application
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.adaptive.currentWindowAdaptiveInfoV2
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.min
import androidx.window.core.layout.WindowSizeClass
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import coil3.compose.AsyncImage
import com.kolktech.kahawai.R
import com.kolktech.kahawai.data.network.dto.AudioStreamInfo
import com.kolktech.kahawai.data.network.dto.Chapter
import com.kolktech.kahawai.data.network.dto.Item
import com.kolktech.kahawai.data.network.dto.ItemDetail
import com.kolktech.kahawai.data.network.dto.SubtitleTrack
import com.kolktech.kahawai.data.network.dto.VideoStreamInfo
import com.kolktech.kahawai.data.network.dto.displayLabel
import com.kolktech.kahawai.data.repository.CatalogRepository
import com.kolktech.kahawai.ui.components.ErrorView
import com.kolktech.kahawai.ui.components.OnResumeEffect
import com.kolktech.kahawai.ui.components.WatchProgressBar
import com.kolktech.kahawai.ui.player.PlaybackPrefetch
import com.kolktech.kahawai.util.formatDurationCoarse
import com.kolktech.kahawai.util.formatEndsAt

/// A thick, high-contrast ring around whatever holds D-pad focus. The
/// default Material focus indication is nearly invisible from couch
/// distance; every focusable control on this screen routes through this
/// so "where am I" is always obvious. The border is always present
/// (transparent when unfocused) so gaining focus never shifts layout.
@Composable
private fun Modifier.dpadFocusBorder(shape: Shape = CircleShape): Modifier {
    var focused by remember { mutableStateOf(false) }
    return this
        .onFocusChanged { focused = it.isFocused }
        .border(
            width = 3.dp,
            color = if (focused) MaterialTheme.colorScheme.onBackground else Color.Transparent,
            shape = shape,
        )
}

@Composable
fun DetailScreen(
    itemId: String,
    /// Navigation context from the row this was opened from — the item's
    /// own detail names no library, and its media type is what the
    /// account's track preferences are keyed by (see TrackChoice).
    libraryId: String?,
    repo: CatalogRepository,
    onOpenItem: (itemId: String, libraryId: String?) -> Unit,
    onPlay: (itemId: String, startMs: Long, audioTrack: Int, subtitleTrackId: Long?, prefetch: PlaybackPrefetch) -> Unit,
    onBack: () -> Unit,
    onSessionExpired: () -> Unit,
) {
    val application = LocalContext.current.applicationContext as Application
    val viewModel: DetailViewModel = viewModel(
        key = itemId,
        factory = viewModelFactory { initializer { DetailViewModel(application, repo, itemId, libraryId) } },
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
            is DetailState.Loaded -> {
                // A show/album has no Play button, so without this,
                // nothing on the page is ever focused and D-pad
                // navigation has no anchor to move from (arrow keys do
                // nothing). The Play button sits in the LazyColumn's
                // always-composed first item, so it's safe to focus
                // directly from here — but the first episode row is a
                // later lazy item that may not exist yet at this point,
                // so ChildRow requests its own focus once it's actually
                // composed (see its focusRequester handling) instead.
                if (s.detail.kind !in NOT_DIRECTLY_PLAYABLE) {
                    LaunchedEffect(Unit) { playButtonFocusRequester.requestFocus() }
                } else if (s.children.isEmpty()) {
                    LaunchedEffect(Unit) { backButtonFocusRequester.requestFocus() }
                }
                DetailContent(
                    state = s,
                    libraryId = libraryId,
                    repo = repo,
                    onOpenItem = onOpenItem,
                    onPlay = onPlay,
                    onSelectAudioTrack = viewModel::selectAudioTrackIndex,
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
        Surface(
            onClick = onBack,
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(16.dp)
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
    }
}

@Composable
private fun DetailContent(
    state: DetailState.Loaded,
    libraryId: String?,
    repo: CatalogRepository,
    onOpenItem: (itemId: String, libraryId: String?) -> Unit,
    onPlay: (itemId: String, startMs: Long, audioTrack: Int, subtitleTrackId: Long?, prefetch: PlaybackPrefetch) -> Unit,
    onSelectAudioTrack: (Int) -> Unit,
    onSelectSubtitleTrack: (SubtitleTrack?) -> Unit,
    onToggleWatched: () -> Unit,
    playButtonFocusRequester: FocusRequester,
    firstChildFocusRequester: FocusRequester,
) {
    val detail = state.detail
    val children = state.children
    // Window size class rather than orientation: a portrait tablet or
    // unfolded foldable (medium+ width) has room for the side-by-side
    // layout even though it's "portrait", and a landscape phone (compact
    // height) is too short for the stacked full-width hero even when its
    // width lands under the medium breakpoint.
    val windowSizeClass = currentWindowAdaptiveInfoV2().windowSizeClass
    val useTwoPane = windowSizeClass.isWidthAtLeastBreakpoint(WindowSizeClass.WIDTH_DP_MEDIUM_LOWER_BOUND) ||
        !windowSizeClass.isHeightAtLeastBreakpoint(WindowSizeClass.HEIGHT_DP_MEDIUM_LOWER_BOUND)
    val primarySource = detail.sources.firstOrNull()
    val audioTracks = primarySource?.streams?.audio.orEmpty()
    val videoStream = primarySource?.streams?.video?.firstOrNull()
    val runtimeMs = primarySource?.streams?.durationMs ?: detail.resumeDurationMs

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
                        model = repo.artworkUrl(detail.id, detail.artVersion, "card"),
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
                LazyColumn(modifier = Modifier.weight(1f).fillMaxHeight().padding(16.dp)) {
                    item {
                        DetailInfo(
                            detail = detail,
                            audioTracks = audioTracks,
                            videoStream = videoStream,
                            runtimeMs = runtimeMs,
                            subtitleTracks = state.subtitleTracks,
                            selectedAudioTrackIndex = state.selectedAudioTrackIndex,
                            selectedSubtitleTrack = state.selectedSubtitleTrack,
                            onSelectAudioTrack = onSelectAudioTrack,
                            onSelectSubtitleTrack = onSelectSubtitleTrack,
                            onPlay = onPlay,
                            onToggleWatched = onToggleWatched,
                            watchedActionInFlight = state.watchedActionInFlight,
                            playButtonFocusRequester = playButtonFocusRequester,
                        )
                    }
                    if (children.isNotEmpty()) {
                        itemsIndexed(children, key = { _, child -> child.id }) { index, child ->
                            ChildRow(
                                child,
                                onOpenItem,
                                libraryId,
                                focusRequester = if (index == 0) firstChildFocusRequester else null,
                            )
                            HorizontalDivider()
                        }
                    }
                }
            }
        }
    } else {
        LazyColumn(modifier = Modifier.fillMaxSize()) {
            item {
                Column {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .aspectRatio(16f / 9f),
                    ) {
                        AsyncImage(
                            model = repo.artworkUrl(detail.id, detail.artVersion),
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
                    Column(modifier = Modifier.padding(16.dp)) {
                        DetailInfo(
                            detail = detail,
                            audioTracks = audioTracks,
                            videoStream = videoStream,
                            runtimeMs = runtimeMs,
                            subtitleTracks = state.subtitleTracks,
                            selectedAudioTrackIndex = state.selectedAudioTrackIndex,
                            selectedSubtitleTrack = state.selectedSubtitleTrack,
                            onSelectAudioTrack = onSelectAudioTrack,
                            onSelectSubtitleTrack = onSelectSubtitleTrack,
                            onPlay = onPlay,
                            onToggleWatched = onToggleWatched,
                            watchedActionInFlight = state.watchedActionInFlight,
                            playButtonFocusRequester = playButtonFocusRequester,
                        )
                    }
                }
            }
            if (children.isNotEmpty()) {
                itemsIndexed(children, key = { _, child -> child.id }) { index, child ->
                    ChildRow(
                        child,
                        onOpenItem,
                        libraryId,
                        focusRequester = if (index == 0) firstChildFocusRequester else null,
                    )
                    HorizontalDivider()
                }
            }
        }
    }
}

@Composable
private fun DetailInfo(
    detail: ItemDetail,
    audioTracks: List<AudioStreamInfo>,
    videoStream: VideoStreamInfo?,
    runtimeMs: Long?,
    subtitleTracks: List<SubtitleTrack>,
    selectedAudioTrackIndex: Int,
    selectedSubtitleTrack: SubtitleTrack?,
    onSelectAudioTrack: (Int) -> Unit,
    onSelectSubtitleTrack: (SubtitleTrack?) -> Unit,
    onPlay: (itemId: String, startMs: Long, audioTrack: Int, subtitleTrackId: Long?, prefetch: PlaybackPrefetch) -> Unit,
    onToggleWatched: () -> Unit,
    watchedActionInFlight: Boolean,
    playButtonFocusRequester: FocusRequester,
) {
    Text(detail.title, style = MaterialTheme.typography.headlineSmall)
    val meta = listOfNotNull(
        detail.year?.toString(),
        detail.season?.let { "S$it" }
            ?.plus(detail.episode?.let { "E$it" } ?: ""),
        detail.parentTitle,
    ).joinToString(" · ")
    if (meta.isNotBlank()) {
        Text(meta, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 4.dp))
    }

    // Remaining, not the full runtime: resuming partway through should
    // still point at when playback would actually finish if started now.
    val remainingMs = runtimeMs?.minus(detail.resumePositionMs?.coerceAtLeast(0) ?: 0)?.coerceAtLeast(0)
    val facts = listOfNotNull(
        detail.metadata?.rating?.takeIf { it > 0 }?.let { "★ %.1f".format(it) },
        runtimeMs?.takeIf { it > 0 }?.let { formatDurationCoarse(it) },
        remainingMs?.takeIf { it > 0 }?.let { formatEndsAt(it) },
        videoStream?.resolutionLabel(),
        videoStream?.let { it.codec.uppercase() },
        videoStream?.hdr?.uppercase(),
    ).joinToString("  ·  ")
    if (facts.isNotBlank()) {
        Text(facts, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 6.dp))
    }
    detail.metadata?.genres?.takeIf { it.isNotEmpty() }?.let {
        Text(it.joinToString(", "), style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 2.dp))
    }

    ResumeLine(detail)

    if (detail.kind !in NOT_DIRECTLY_PLAYABLE) {
        val resumeMs = detail.resumePositionMs ?: 0
        // What PlayerViewModel would otherwise spend its own itemQuery
        // round trip re-deriving — this screen already has it (see
        // PlaybackPrefetch).
        val prefetch = PlaybackPrefetch(subtitleTracks, detail.segments, detail.chapters, detail.parentId)
        Row(
            modifier = Modifier.padding(top = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Button(
                onClick = { onPlay(detail.id, resumeMs, selectedAudioTrackIndex, selectedSubtitleTrack?.id, prefetch) },
                modifier = Modifier.focusRequester(playButtonFocusRequester).dpadFocusBorder(),
            ) {
                Text(stringResource(if (resumeMs > 0) R.string.detail_resume else R.string.detail_play))
            }
            if (resumeMs > 0) {
                OutlinedButton(
                    onClick = { onPlay(detail.id, 0, selectedAudioTrackIndex, selectedSubtitleTrack?.id, prefetch) },
                    modifier = Modifier.dpadFocusBorder(),
                ) {
                    Text(stringResource(R.string.detail_start_over))
                }
            }
            OutlinedButton(
                onClick = onToggleWatched,
                enabled = !watchedActionInFlight,
                modifier = Modifier.dpadFocusBorder(),
            ) {
                Text(stringResource(if (detail.played) R.string.detail_mark_unwatched else R.string.detail_mark_watched))
            }
        }

        if (audioTracks.size > 1) {
            AudioPicker(
                tracks = audioTracks,
                selectedIndex = selectedAudioTrackIndex,
                onSelect = onSelectAudioTrack,
                modifier = Modifier.padding(top = 16.dp),
            )
        }

        if (subtitleTracks.isNotEmpty()) {
            SubtitlePicker(
                tracks = subtitleTracks,
                selected = selectedSubtitleTrack,
                onSelect = onSelectSubtitleTrack,
                modifier = Modifier.padding(top = 16.dp),
            )
        }

        // The file's own chapters (kahawai commit db7d743, "Chapters
        // ride the item from the scan to the seek bar") — buttons, not a
        // table of numbers, mirroring web/src/views/Detail.vue: the
        // useful thing about a chapter is jumping straight into it.
        if (detail.chapters.isNotEmpty()) {
            ChaptersList(
                chapters = detail.chapters,
                onPlayChapter = { startMs -> onPlay(detail.id, startMs, selectedAudioTrackIndex, selectedSubtitleTrack?.id, prefetch) },
                modifier = Modifier.padding(top = 16.dp),
            )
        }
    }

    detail.metadata?.overview?.let {
        Text(it, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 16.dp))
    }
}

@Composable
private fun AudioPicker(
    tracks: List<AudioStreamInfo>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    var showPicker by remember { mutableStateOf(false) }
    Column(modifier = modifier) {
        Text(stringResource(R.string.audio), style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(bottom = 4.dp))
        OutlinedButton(onClick = { showPicker = true }, modifier = Modifier.fillMaxWidth().dpadFocusBorder()) {
            Text(
                tracks.getOrNull(selectedIndex)?.displayLabel() ?: "",
                modifier = Modifier.weight(1f),
                textAlign = TextAlign.Start,
            )
        }
    }
    if (showPicker) {
        AlertDialog(
            onDismissRequest = { showPicker = false },
            confirmButton = {
                TextButton(onClick = { showPicker = false }) { Text(stringResource(R.string.detail_subtitles_close)) }
            },
            title = { Text(stringResource(R.string.audio)) },
            text = {
                // A capped height so the popup itself stays small and
                // scrolls, instead of growing to fit every track.
                LazyColumn(modifier = Modifier.heightIn(max = 320.dp)) {
                    itemsIndexed(tracks) { index, track ->
                        SelectableRow(
                            text = track.displayLabel(),
                            selected = index == selectedIndex,
                            onClick = { onSelect(index); showPicker = false },
                        )
                    }
                }
            },
        )
    }
}

@Composable
private fun SubtitlePicker(
    tracks: List<SubtitleTrack>,
    selected: SubtitleTrack?,
    onSelect: (SubtitleTrack?) -> Unit,
    modifier: Modifier = Modifier,
) {
    var showPicker by remember { mutableStateOf(false) }
    val offLabel = stringResource(R.string.subtitle_off)
    Column(modifier = modifier) {
        Text(stringResource(R.string.subtitles), style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(bottom = 4.dp))
        OutlinedButton(onClick = { showPicker = true }, modifier = Modifier.fillMaxWidth().dpadFocusBorder()) {
            Text(selected?.displayLabel() ?: offLabel, modifier = Modifier.weight(1f), textAlign = TextAlign.Start)
        }
    }
    if (showPicker) {
        AlertDialog(
            onDismissRequest = { showPicker = false },
            confirmButton = {
                TextButton(onClick = { showPicker = false }) { Text(stringResource(R.string.detail_subtitles_close)) }
            },
            title = { Text(stringResource(R.string.subtitles)) },
            text = {
                // A capped height so the popup itself stays small and
                // scrolls, instead of growing to fit every track.
                LazyColumn(modifier = Modifier.heightIn(max = 320.dp)) {
                    item {
                        SelectableRow(
                            text = offLabel,
                            selected = selected == null,
                            onClick = { onSelect(null); showPicker = false },
                        )
                    }
                    items(tracks, key = { it.id }) { track ->
                        SelectableRow(
                            text = track.displayLabel(),
                            selected = selected?.id == track.id,
                            enabled = track.delivery != "none",
                            onClick = { onSelect(track); showPicker = false },
                        )
                    }
                }
            },
        )
    }
}

/// A nameless chapter is still a seek point worth showing — plenty of
/// rips number them and say nothing else. Mirrors
/// web/src/domain/chapters.ts's chapterTitle.
@Composable
private fun chapterTitle(chapter: Chapter, index: Int): String =
    chapter.title?.trim().takeUnless { it.isNullOrEmpty() }
        ?: stringResource(R.string.detail_chapter_numbered, index + 1)

private fun formatTimestamp(ms: Long): String {
    val totalSeconds = ms / 1000
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60
    return if (hours > 0) "%d:%02d:%02d".format(hours, minutes, seconds) else "%d:%02d".format(minutes, seconds)
}

@Composable
private fun ChaptersList(chapters: List<Chapter>, onPlayChapter: (Long) -> Unit, modifier: Modifier = Modifier) {
    Column(modifier = modifier) {
        Text(stringResource(R.string.detail_chapters), style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(bottom = 4.dp))
        chapters.forEachIndexed { index, chapter ->
            var focused by remember { mutableStateOf(false) }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .onFocusChanged { focused = it.isFocused }
                    .background(
                        if (focused) MaterialTheme.colorScheme.primary.copy(alpha = 0.3f) else Color.Transparent,
                        RoundedCornerShape(4.dp),
                    )
                    .border(
                        width = 2.dp,
                        color = if (focused) MaterialTheme.colorScheme.primary else Color.Transparent,
                        shape = RoundedCornerShape(4.dp),
                    )
                    .clickable { onPlayChapter(chapter.startMs) }
                    .padding(vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    formatTimestamp(chapter.startMs),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                )
                Text(chapterTitle(chapter, index), style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

@Composable
private fun SelectableRow(text: String, selected: Boolean, enabled: Boolean = true, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .onFocusChanged { focused = it.isFocused }
            .background(
                if (focused) MaterialTheme.colorScheme.primary.copy(alpha = 0.3f) else Color.Transparent,
                RoundedCornerShape(4.dp),
            )
            .border(
                width = 2.dp,
                color = if (focused) MaterialTheme.colorScheme.primary else Color.Transparent,
                shape = RoundedCornerShape(4.dp),
            )
            .clickable(enabled = enabled, onClick = onClick)
            .padding(vertical = 4.dp),
    ) {
        RadioButton(selected = selected, onClick = null, enabled = enabled)
        Text(
            text,
            style = MaterialTheme.typography.bodyMedium,
            color = if (enabled) LocalContentColor.current else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f),
            modifier = Modifier.padding(start = 4.dp),
        )
    }
}

private fun VideoStreamInfo.resolutionLabel(): String = when {
    height >= 2160 -> "4K"
    height >= 1080 -> "1080p"
    height >= 720 -> "720p"
    height >= 480 -> "480p"
    height > 0 -> "${height}p"
    else -> ""
}

@Composable
private fun AudioStreamInfo.displayLabel(): String {
    val channelLabel = when (channels) {
        1 -> stringResource(R.string.channels_mono)
        2 -> stringResource(R.string.channels_stereo)
        6 -> "5.1"
        8 -> "7.1"
        0 -> null
        else -> "${channels}ch"
    }
    return listOfNotNull(language?.uppercase(), codec.uppercase(), channelLabel).joinToString(" · ")
}

@Composable
private fun ResumeLine(detail: ItemDetail) {
    val resumeMs = detail.resumePositionMs
    val durationMs = detail.resumeDurationMs
    val text = when {
        detail.played -> stringResource(R.string.watched)
        resumeMs != null && resumeMs > 0 -> {
            val elapsed = formatDurationCoarse(resumeMs)
            if (durationMs != null && durationMs > 0) {
                stringResource(R.string.resume_at_percent, elapsed, resumeMs * 100 / durationMs)
            } else {
                stringResource(R.string.resume_at, elapsed)
            }
        }
        else -> null
    }
    text?.let {
        Text(it, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 4.dp))
    }
}

@Composable
private fun ChildRow(
    child: Item,
    onOpenItem: (itemId: String, libraryId: String?) -> Unit,
    libraryId: String?,
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
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
            .onFocusChanged { focused = it.isFocused }
            .background(if (focused) MaterialTheme.colorScheme.primary.copy(alpha = 0.3f) else Color.Transparent)
            .border(
                width = 2.dp,
                color = if (focused) MaterialTheme.colorScheme.primary else Color.Transparent,
            )
            // Membership runs through the show, so an episode row rarely
            // names a library of its own — this screen's own answers for it.
            .clickable { onOpenItem(child.id, child.libraryId ?: libraryId) }
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        val label = if (child.kind == "episode") {
            listOfNotNull(
                child.season?.let { "S$it" }?.plus(child.episode?.let { "E$it" } ?: ""),
                child.title,
            ).joinToString(" · ")
        } else {
            child.title
        }
        Text(label, style = MaterialTheme.typography.bodyLarge)
        if (child.playCount > 0 || child.played) {
            Text(
                stringResource(R.string.watched),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
            )
        } else {
            WatchProgressBar(
                positionMs = child.resumePositionMs,
                durationMs = child.resumeDurationMs,
                played = child.played,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
}
