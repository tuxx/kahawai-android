package com.kolktech.kahawai.ui.detail

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kolktech.kahawai.R
import com.kolktech.kahawai.data.network.dto.ClientAudioStream
import com.kolktech.kahawai.data.network.dto.SubtitleTrack
import com.kolktech.kahawai.playback.SourceWork
import com.kolktech.kahawai.playback.location
import com.kolktech.kahawai.playback.resolutionLabel
import com.kolktech.kahawai.ui.player.GroupDivider
import com.kolktech.kahawai.ui.player.LocalPanelColors
import com.kolktech.kahawai.ui.player.OptionList
import com.kolktech.kahawai.ui.player.PanelColors
import com.kolktech.kahawai.ui.player.RowGroup
import com.kolktech.kahawai.ui.player.SubtitleOptionStrings
import com.kolktech.kahawai.ui.player.TabButton
import com.kolktech.kahawai.ui.player.TrackOption
import com.kolktech.kahawai.ui.player.ValueRow
import com.kolktech.kahawai.ui.player.audioOption
import com.kolktech.kahawai.ui.player.subtitleOption

private enum class TrackSheet { Subtitles, Audio, Version }

/// The player panel's rows on the app's own teal surfaces.
@Composable
private fun detailPanelColors() = PanelColors(
    panel = MaterialTheme.colorScheme.surface,
    group = MaterialTheme.colorScheme.surfaceVariant,
    focus = MaterialTheme.colorScheme.primary.copy(alpha = 0.25f),
    secondaryText = MaterialTheme.colorScheme.onSurfaceVariant,
    sectionText = MaterialTheme.colorScheme.onSurfaceVariant,
)

/// Which audio, subtitles and file Play will use, as one group of rows in the
/// player's style. Each row opens the same picker the player has, so a choice
/// looks the same before playback as during it. A row only appears when there
/// is something to choose.
@Composable
internal fun PlaybackOptions(
    works: List<SourceWork>,
    selectedSourceId: Int?,
    automaticSourceId: Int?,
    sourcePinned: Boolean,
    audioTracks: List<ClientAudioStream>,
    selectedAudioTrackIndex: Int,
    subtitleTracks: List<SubtitleTrack>,
    selectedSubtitleTrack: SubtitleTrack?,
    onSelectSource: (Int?) -> Unit,
    onSelectAudioTrack: (Int) -> Unit,
    onSelectSubtitleTrack: (SubtitleTrack?) -> Unit,
    modifier: Modifier = Modifier,
) {
    val showAudio = audioTracks.size > 1
    val showSubtitles = subtitleTracks.isNotEmpty()
    val showVersion = works.size > 1
    if (!showAudio && !showSubtitles && !showVersion) return

    val mono = stringResource(R.string.channels_mono)
    val stereo = stringResource(R.string.channels_stereo)
    val subtitleStrings = SubtitleOptionStrings(
        embedded = stringResource(R.string.subtitle_origin_embedded),
        external = stringResource(R.string.subtitle_origin_external),
        downloaded = stringResource(R.string.subtitle_origin_downloaded),
        ocr = stringResource(R.string.subtitle_origin_ocr),
        burnedIn = stringResource(R.string.subtitle_burned_in),
        machine = stringResource(R.string.subtitle_badge_machine),
    )
    val subtitles = listOf(TrackOption(stringResource(R.string.subtitle_off), selected = selectedSubtitleTrack == null)) +
        subtitleTracks.map { subtitleOption(it, it.id == selectedSubtitleTrack?.id, subtitleStrings) }
    val audio = audioTracks.mapIndexed { index, stream -> audioOption(stream, index == selectedAudioTrackIndex, mono, stereo) }
    val versions = versionOptions(works, selectedSourceId, automaticSourceId, sourcePinned)

    var sheet by remember { mutableStateOf<TrackSheet?>(null) }
    val rows = buildList {
        if (showAudio) {
            add(Triple(stringResource(R.string.audio), audio.firstOrNull { it.selected }?.summary.orEmpty(), TrackSheet.Audio))
        }
        if (showSubtitles) {
            // A remembered pick this file doesn't have plays as Off.
            add(Triple(stringResource(R.string.subtitles), (subtitles.firstOrNull { it.selected } ?: subtitles.first()).summary, TrackSheet.Subtitles))
        }
        if (showVersion) {
            add(Triple(stringResource(R.string.detail_source), versions.firstOrNull { it.selected }?.summary.orEmpty(), TrackSheet.Version))
        }
    }

    CompositionLocalProvider(LocalPanelColors provides detailPanelColors()) {
        Box(modifier) {
            RowGroup {
                rows.forEachIndexed { index, (label, value, target) ->
                    if (index > 0) GroupDivider()
                    ValueRow(label, value, onClick = { sheet = target })
                }
            }
        }

        sheet?.let { shown ->
            TrackSheetDialog(
                sheet = shown,
                subtitles = subtitles.takeIf { showSubtitles }.orEmpty(),
                audio = audio.takeIf { showAudio }.orEmpty(),
                versions = versions,
                onSheetChange = { sheet = it },
                onDismiss = { sheet = null },
                onSelectSubtitle = { index ->
                    onSelectSubtitleTrack(if (index == 0) null else subtitleTracks[index - 1])
                    sheet = null
                },
                onSelectAudio = { index ->
                    onSelectAudioTrack(index)
                    sheet = null
                },
                onSelectVersion = { index ->
                    onSelectSource(if (index == 0) null else works[index - 1].sourceId)
                    sheet = null
                },
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TrackSheetDialog(
    sheet: TrackSheet,
    subtitles: List<TrackOption>,
    audio: List<TrackOption>,
    versions: List<TrackOption>,
    onSheetChange: (TrackSheet) -> Unit,
    onDismiss: () -> Unit,
    onSelectSubtitle: (Int) -> Unit,
    onSelectAudio: (Int) -> Unit,
    onSelectVersion: (Int) -> Unit,
) {
    val colors = LocalPanelColors.current
    // Capped so a long track list scrolls inside the sheet rather than
    // pushing it to full height.
    val maxListHeight = (LocalConfiguration.current.screenHeightDp * 0.6f).dp
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = colors.panel,
    ) {
        Column {
            Text(
                stringResource(if (sheet == TrackSheet.Version) R.string.detail_source else R.string.player_panel_audio_subtitles),
                fontSize = 19.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(start = 20.dp, end = 20.dp, bottom = 8.dp),
            )
            // The same two tabs as the player's Audio & Subtitles page, when
            // both have something to choose.
            if (sheet != TrackSheet.Version && subtitles.isNotEmpty() && audio.isNotEmpty()) {
                Row(
                    modifier = Modifier
                        .padding(horizontal = 12.dp)
                        .padding(bottom = 6.dp)
                        .fillMaxWidth()
                        .clip(CircleShape)
                        .background(colors.group)
                        .padding(3.dp),
                    horizontalArrangement = Arrangement.spacedBy(3.dp),
                ) {
                    // Off isn't a track, so it doesn't count.
                    TabButton(stringResource(R.string.subtitles), subtitles.size - 1, sheet == TrackSheet.Subtitles) {
                        onSheetChange(TrackSheet.Subtitles)
                    }
                    TabButton(stringResource(R.string.player_panel_audio), audio.size, sheet == TrackSheet.Audio) {
                        onSheetChange(TrackSheet.Audio)
                    }
                }
            }
            Box(Modifier.heightIn(max = maxListHeight)) {
                when (sheet) {
                    TrackSheet.Subtitles -> OptionList(subtitles, onSelectSubtitle)
                    TrackSheet.Audio -> OptionList(audio, onSelectAudio)
                    TrackSheet.Version -> OptionList(versions, onSelectVersion)
                }
            }
        }
    }
}

/// "Automatic" first, naming the file it resolves to, then every file with
/// the automatic one badged — so the hub's own choice is never invisible and
/// a hand-picked file never looks like the one that was going to play anyway.
@Composable
private fun versionOptions(
    works: List<SourceWork>,
    selectedSourceId: Int?,
    automaticSourceId: Int?,
    pinned: Boolean,
): List<TrackOption> {
    val autoLabel = stringResource(R.string.detail_source_automatic)
    val autoBadge = stringResource(R.string.detail_source_auto_badge)
    val automatic = works.firstOrNull { it.sourceId == automaticSourceId }
    val automaticTitle = automatic?.title()
    return listOf(
        TrackOption(
            title = autoLabel,
            detail = automatic?.let { listOfNotNull(automaticTitle, it.details()).joinToString(" · ") },
            summary = listOfNotNull(autoLabel, automaticTitle).joinToString(" · "),
            selected = !pinned,
        ),
    ) + works.map { work ->
        TrackOption(
            title = work.title(),
            badges = listOfNotNull(autoBadge.takeIf { work.sourceId == automaticSourceId }),
            detail = work.details(),
            selected = pinned && work.sourceId == selectedSourceId,
        )
    }
}

/// What tells two encodes apart at a glance: picture size and codec.
private fun SourceWork.title(): String {
    val video = first?.streams?.video?.firstOrNull()
    return listOfNotNull(
        video?.resolutionLabel()?.takeIf { it.isNotBlank() },
        video?.codec?.uppercase(),
    ).joinToString(" · ").ifBlank { location() }
}

/// Where it lives, how big it is, and whether it can actually play right now.
@Composable
private fun SourceWork.details(): String? = listOfNotNull(
    location().takeIf { it.isNotBlank() },
    first?.size?.takeIf { it > 0 }?.let { "%.1f GB".format(it / 1_073_741_824.0) },
    parts.size.takeIf { it > 1 }?.let { stringResource(R.string.detail_source_parts, it) },
    if (!whole) stringResource(R.string.detail_source_incomplete, expectedParts) else null,
    if (!available) stringResource(R.string.detail_source_offline) else null,
).joinToString(" · ").ifBlank { null }
