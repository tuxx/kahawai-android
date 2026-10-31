package com.kolktech.kahawai.ui.player

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.kolktech.kahawai.R
import com.kolktech.kahawai.ui.components.WatchProgressBar

/// A page of the side panel. The panel keeps a stack of these, so a picker
/// opened from Settings has a back arrow to it, while one opened straight
/// from the CC button closes on back.
internal sealed interface PanelPage {
    data object Settings : PanelPage
    data class Tracks(val tab: TracksTab) : PanelPage
    data object Speed : PanelPage
    data object PictureSize : PanelPage
    data object Episodes : PanelPage
}

internal enum class TracksTab { Subtitles, Audio }

/// One row of the Episodes page. [season] heads the group it falls in.
internal data class EpisodeOption(
    val id: String,
    val season: String?,
    val title: String,
    val artworkUrl: String?,
    val resumePositionMs: Long?,
    val resumeDurationMs: Long?,
    val played: Boolean,
    val current: Boolean,
)

/// Everything the panel shows, already resolved to labels. [subtitles]
/// starts with the "Off" row. A null [autoSkip] means there's nothing to
/// skip in this item, and a null [autoPlayNext] means there's no next
/// episode; either way the row is left out.
internal data class PlayerPanelModel(
    val episodes: List<EpisodeOption>,
    val subtitles: List<TrackOption>,
    val audio: List<TrackOption>,
    val pictureSizes: List<TrackOption>,
    val speeds: List<TrackOption>,
    val autoSkip: Boolean?,
    val autoPlayNext: Boolean?,
    val statsForNerds: Boolean,
)

private val PanelBackground = PlayerPanelColors.panel
private val GroupBackground = PlayerPanelColors.group
private val SecondaryText = PlayerPanelColors.secondaryText

/// Settings and track pickers as a panel over the video, in place of the
/// old anchored popups: on the right in landscape, as a bottom sheet in
/// portrait. Every row is a focusable button, so it works with a D-pad as
/// well as touch; the caller routes remote keys here while it's open.
@Composable
internal fun PlayerSidePanel(
    stack: List<PanelPage>,
    onStackChange: (List<PanelPage>) -> Unit,
    model: PlayerPanelModel,
    onSelectSubtitle: (Int) -> Unit,
    onSelectAudio: (Int) -> Unit,
    onSelectPictureSize: (Int) -> Unit,
    onSelectSpeed: (Int) -> Unit,
    onAutoSkipChange: (Boolean) -> Unit,
    onAutoPlayNextChange: (Boolean) -> Unit,
    onStatsForNerdsChange: (Boolean) -> Unit,
    onSelectEpisode: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val open = stack.isNotEmpty()
    // The last open stack stays on screen while the panel slides away.
    var lastStack by remember { mutableStateOf(stack) }
    SideEffect { if (open) lastStack = stack }
    val shownStack = if (open) stack else lastStack
    val page = shownStack.lastOrNull() ?: return

    val close = { onStackChange(emptyList()) }
    val back = { onStackChange(stack.dropLast(1)) }
    val push = { next: PanelPage -> onStackChange(stack + next) }
    // A pick goes back to where it was opened from: Settings, or closed
    // when the picker was the first page.
    val afterPick = back

    BackHandler(enabled = open, onBack = back)

    BoxWithConstraints(modifier.fillMaxSize()) {
        val landscape = maxWidth > maxHeight
        val sheetMaxHeight = maxHeight * 0.8f
        val panelWidth = (maxWidth * 0.36f).coerceIn(280.dp, 380.dp).coerceAtMost(maxWidth)

        AnimatedVisibility(visible = open, enter = fadeIn(), exit = fadeOut()) {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.35f))
                    .pointerInput(Unit) { detectTapGestures { close() } },
            )
        }

        AnimatedVisibility(
            visible = open,
            modifier = Modifier.align(if (landscape) Alignment.CenterEnd else Alignment.BottomCenter),
            enter = if (landscape) slideInHorizontally { it } + fadeIn() else slideInVertically { it } + fadeIn(),
            exit = if (landscape) slideOutHorizontally { it } + fadeOut() else slideOutVertically { it } + fadeOut(),
        ) {
            val shape = if (landscape) RoundedCornerShape(24.dp) else RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp)
            Column(
                modifier = Modifier
                    .then(
                        if (landscape) {
                            Modifier
                                .fillMaxHeight()
                                .windowInsetsPadding(WindowInsets.displayCutout)
                                .padding(8.dp)
                                .width(panelWidth)
                        } else {
                            Modifier.fillMaxWidth().heightIn(max = sheetMaxHeight)
                        },
                    )
                    .clip(shape)
                    .background(PanelBackground)
                    // Taps on the panel's own empty space mustn't fall
                    // through to the scrim and close it.
                    .pointerInput(Unit) { detectTapGestures {} },
            ) {
                PanelHeader(
                    title = stringResource(
                        when (page) {
                            PanelPage.Settings -> R.string.player_panel_settings
                            is PanelPage.Tracks -> R.string.player_panel_audio_subtitles
                            PanelPage.Speed -> R.string.player_panel_speed
                            PanelPage.PictureSize -> R.string.player_panel_picture_size
                            PanelPage.Episodes -> R.string.player_episodes
                        },
                    ),
                    onBack = back.takeIf { shownStack.size > 1 },
                    onClose = close,
                )
                AnimatedContent(
                    targetState = page,
                    transitionSpec = { fadeIn() togetherWith fadeOut() },
                    // Tab switches swap only the list, not the whole page.
                    contentKey = { if (it is PanelPage.Tracks) PanelPage.Tracks::class else it },
                    label = "panel-page",
                ) { current ->
                    when (current) {
                        PanelPage.Settings -> SettingsPage(
                            model = model,
                            onOpen = push,
                            onAutoSkipChange = onAutoSkipChange,
                            onAutoPlayNextChange = onAutoPlayNextChange,
                            onStatsForNerdsChange = onStatsForNerdsChange,
                        )
                        is PanelPage.Tracks -> TracksPage(
                            tab = current.tab,
                            model = model,
                            onTabChange = { tab -> onStackChange(stack.dropLast(1) + PanelPage.Tracks(tab)) },
                            onSelectSubtitle = { onSelectSubtitle(it); afterPick() },
                            onSelectAudio = { onSelectAudio(it); afterPick() },
                        )
                        PanelPage.Speed -> OptionList(model.speeds, onSelect = { onSelectSpeed(it); afterPick() })
                        PanelPage.PictureSize -> OptionList(
                            model.pictureSizes,
                            onSelect = { onSelectPictureSize(it); afterPick() },
                        )
                        // Picking one leaves the player for that episode's
                        // own, so there's nothing to go back to.
                        PanelPage.Episodes -> EpisodeList(model.episodes, onSelect = { onSelectEpisode(it); close() })
                    }
                }
            }
        }
    }
}

@Composable
private fun PanelHeader(title: String, onBack: (() -> Unit)?, onClose: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = if (onBack != null) 4.dp else 20.dp, end = 8.dp, top = 8.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (onBack != null) {
            IconButton(onClick = onBack, modifier = Modifier.size(40.dp)) {
                Icon(
                    Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = stringResource(R.string.kw_back),
                    tint = Color.White,
                    modifier = Modifier.size(20.dp),
                )
            }
        }
        Text(
            title,
            modifier = Modifier.weight(1f),
            color = Color.White,
            fontSize = 19.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        IconButton(onClick = onClose, modifier = Modifier.size(36.dp).background(GroupBackground, CircleShape)) {
            Icon(
                Icons.Filled.Close,
                contentDescription = stringResource(R.string.player_panel_close),
                tint = Color.White,
                modifier = Modifier.size(18.dp),
            )
        }
    }
}

@Composable
private fun SettingsPage(
    model: PlayerPanelModel,
    onOpen: (PanelPage) -> Unit,
    onAutoSkipChange: (Boolean) -> Unit,
    onAutoPlayNextChange: (Boolean) -> Unit,
    onStatsForNerdsChange: (Boolean) -> Unit,
) {
    val firstRowFocus = rememberInitialFocus()
    val pictureSize = model.pictureSizes.firstOrNull { it.selected }?.summary.orEmpty()
    val speed = model.speeds.firstOrNull { it.selected }?.summary.orEmpty()
    val subtitle = model.subtitles.firstOrNull { it.selected }?.summary.orEmpty()
    val audio = model.audio.firstOrNull { it.selected }?.summary
    val showSession = model.autoSkip != null || model.autoPlayNext != null

    LazyColumn(contentPadding = PaddingValues(start = 10.dp, end = 10.dp, bottom = 12.dp)) {
        item { SectionHeader(stringResource(R.string.player_panel_picture)) }
        item {
            RowGroup {
                ValueRow(
                    stringResource(R.string.player_panel_picture_size),
                    pictureSize,
                    onClick = { onOpen(PanelPage.PictureSize) },
                    modifier = Modifier.focusRequester(firstRowFocus),
                )
                GroupDivider()
                ValueRow(stringResource(R.string.player_panel_speed), speed, onClick = { onOpen(PanelPage.Speed) })
            }
        }
        item { SectionHeader(stringResource(R.string.player_panel_audio_subtitles)) }
        item {
            RowGroup {
                ValueRow(
                    stringResource(R.string.subtitles),
                    subtitle,
                    enabled = model.subtitles.size > 1,
                    onClick = { onOpen(PanelPage.Tracks(TracksTab.Subtitles)) },
                )
                if (audio != null || model.audio.isNotEmpty()) {
                    GroupDivider()
                    ValueRow(
                        stringResource(R.string.player_panel_audio),
                        audio ?: stringResource(R.string.default_label),
                        onClick = { onOpen(PanelPage.Tracks(TracksTab.Audio)) },
                    )
                }
            }
        }
        if (showSession) {
            item { SectionHeader(stringResource(R.string.player_panel_this_session)) }
            item {
                RowGroup {
                    model.autoSkip?.let { checked ->
                        SwitchRow(stringResource(R.string.player_panel_skip_intros), checked, onAutoSkipChange)
                    }
                    if (model.autoSkip != null && model.autoPlayNext != null) GroupDivider()
                    model.autoPlayNext?.let { checked ->
                        SwitchRow(stringResource(R.string.player_panel_auto_play_next), checked, onAutoPlayNextChange)
                    }
                }
            }
        }
        item { SectionHeader(stringResource(R.string.player_panel_advanced)) }
        item {
            RowGroup {
                SwitchRow(stringResource(R.string.player_panel_stats_for_nerds), model.statsForNerds, onStatsForNerdsChange)
            }
        }
    }
}

@Composable
private fun TracksPage(
    tab: TracksTab,
    model: PlayerPanelModel,
    onTabChange: (TracksTab) -> Unit,
    onSelectSubtitle: (Int) -> Unit,
    onSelectAudio: (Int) -> Unit,
) {
    Column {
        if (model.audio.isNotEmpty()) {
            Row(
                modifier = Modifier
                    .padding(horizontal = 12.dp)
                    .padding(top = 4.dp, bottom = 6.dp)
                    .fillMaxWidth()
                    .clip(CircleShape)
                    .background(GroupBackground)
                    .padding(3.dp),
                horizontalArrangement = Arrangement.spacedBy(3.dp),
            ) {
                // Off isn't a track, so it doesn't count.
                TabButton(stringResource(R.string.subtitles), model.subtitles.size - 1, tab == TracksTab.Subtitles) {
                    onTabChange(TracksTab.Subtitles)
                }
                TabButton(stringResource(R.string.player_panel_audio), model.audio.size, tab == TracksTab.Audio) {
                    onTabChange(TracksTab.Audio)
                }
            }
        }
        AnimatedContent(targetState = tab, transitionSpec = { fadeIn() togetherWith fadeOut() }, label = "tracks-tab") { shown ->
            when (shown) {
                TracksTab.Subtitles -> OptionList(model.subtitles, onSelectSubtitle)
                TracksTab.Audio -> OptionList(model.audio, onSelectAudio)
            }
        }
    }
}


@Composable
private fun SwitchRow(label: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    PanelRow(onClick = { onCheckedChange(!checked) }, role = Role.Switch) {
        Text(label, color = Color.White, fontSize = RowLabelSize, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
        // The whole row is the toggle (and the focus target), so the
        // switch itself only shows the state — drawn smaller than
        // Material's touch-sized default to sit in a compact row.
        Switch(checked = checked, onCheckedChange = null, modifier = Modifier.scale(0.8f))
    }
}

/// The show's episodes under season headings, scrolled to (and, with a
/// D-pad, focused on) the one playing now.
@Composable
private fun EpisodeList(episodes: List<EpisodeOption>, onSelect: (Int) -> Unit) {
    // Headings interleave with the rows, so the list's own indices aren't
    // the episodes' — keep both.
    val entries = remember(episodes) {
        buildList {
            var lastSeason: String? = null
            episodes.forEachIndexed { index, episode ->
                if (episode.season != null && episode.season != lastSeason) add(episode.season)
                lastSeason = episode.season
                add(index)
            }
        }
    }
    val currentEntry = entries.indexOfFirst { it is Int && episodes[it].current }.coerceAtLeast(0)
    val listState = rememberLazyListState(initialFirstVisibleItemIndex = (currentEntry - 1).coerceAtLeast(0))
    val focus = rememberInitialFocus()
    LazyColumn(state = listState, contentPadding = PaddingValues(start = 6.dp, end = 6.dp, bottom = 12.dp)) {
        itemsIndexed(entries) { entryIndex, entry ->
            when (entry) {
                is String -> Box(Modifier.padding(start = 8.dp)) { SectionHeader(entry) }
                is Int -> EpisodeRow(
                    episodes[entry],
                    onClick = { onSelect(entry) },
                    modifier = if (entryIndex == currentEntry) Modifier.focusRequester(focus) else Modifier,
                )
            }
        }
    }
}

@Composable
private fun EpisodeRow(episode: EpisodeOption, onClick: () -> Unit, modifier: Modifier = Modifier) {
    PanelRow(
        onClick = onClick,
        role = Role.Button,
        background = if (episode.current) GroupBackground else Color.Transparent,
        shape = RoundedCornerShape(12.dp),
        modifier = modifier,
    ) {
        Box(
            Modifier
                .width(96.dp)
                .aspectRatio(16f / 9f)
                .clip(RoundedCornerShape(8.dp))
                .background(GroupBackground),
        ) {
            AsyncImage(
                model = episode.artworkUrl,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
            WatchProgressBar(
                positionMs = episode.resumePositionMs,
                durationMs = episode.resumeDurationMs,
                played = episode.played,
                modifier = Modifier.align(Alignment.BottomCenter),
            )
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                episode.title,
                color = Color.White,
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (episode.current) {
                Text(
                    stringResource(R.string.player_now_playing),
                    color = SecondaryText,
                    fontSize = RowDetailSize,
                )
            }
        }
        if (episode.played) {
            Spacer(Modifier.width(8.dp))
            Icon(
                Icons.Filled.Check,
                contentDescription = stringResource(R.string.player_watched),
                tint = SecondaryText,
                modifier = Modifier.size(18.dp),
            )
        }
    }
}
