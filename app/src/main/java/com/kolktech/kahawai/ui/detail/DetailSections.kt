package com.kolktech.kahawai.ui.detail

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Replay
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.kolktech.kahawai.R
import com.kolktech.kahawai.data.network.dto.Chapter
import com.kolktech.kahawai.data.network.dto.Credit
import com.kolktech.kahawai.data.network.dto.Item
import com.kolktech.kahawai.ui.components.WatchProgressBar
import com.kolktech.kahawai.util.formatDurationCoarse
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

private val CARD_SHAPE = RoundedCornerShape(10.dp)

/// The poster, softened and dimmed, as the page's backdrop. The hub serves
/// only portrait art, and cropping a poster to a wide hero cuts it through
/// the middle (legs and half a title treatment), so its colours carry the
/// header instead and the poster itself is shown whole on top. [model] is
/// meant to be a small size: scaled up it is soft on every API level, and
/// the blur (API 31+) only smooths it further.
@Composable
internal fun DetailBackdrop(model: Any?, modifier: Modifier = Modifier) {
    val background = MaterialTheme.colorScheme.background
    Box(modifier) {
        AsyncImage(
            model = model,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            alpha = 0.7f,
            modifier = Modifier.fillMaxSize().blur(36.dp),
        )
        Box(
            Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        0f to background.copy(alpha = 0.3f),
                        0.6f to background.copy(alpha = 0.75f),
                        1f to background,
                    ),
                ),
        )
    }
}

/// The phone header: the whole poster on the softened backdrop, with the
/// title block beside it. The top inset clears the floating back/home
/// buttons.
@Composable
internal fun DetailHero(
    backdropModel: Any?,
    posterModel: Any?,
    contentDescription: String,
    positionMs: Long?,
    durationMs: Long?,
    played: Boolean,
    content: @Composable () -> Unit,
) {
    Box(Modifier.fillMaxWidth()) {
        DetailBackdrop(backdropModel, Modifier.matchParentSize())
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 16.dp, top = 80.dp, bottom = 4.dp),
            verticalAlignment = Alignment.Bottom,
        ) {
            Box(
                Modifier
                    .width(116.dp)
                    .aspectRatio(2f / 3f)
                    .shadow(12.dp, CARD_SHAPE)
                    .clip(CARD_SHAPE)
                    .background(MaterialTheme.colorScheme.surfaceVariant),
            ) {
                AsyncImage(
                    model = posterModel,
                    contentDescription = contentDescription,
                    contentScale = ContentScale.Crop,
                    placeholder = painterResource(R.drawable.placeholder_poster),
                    error = painterResource(R.drawable.placeholder_poster),
                    modifier = Modifier.fillMaxSize(),
                )
                WatchProgressBar(
                    positionMs = positionMs,
                    durationMs = durationMs,
                    played = played,
                    modifier = Modifier.align(Alignment.BottomCenter),
                )
            }
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) { content() }
        }
    }
}

/// Title, what it is, and the quick facts, in falling order of weight.
@Composable
internal fun TitleBlock(title: String, meta: String?, rating: Double?, facts: List<String>, genres: List<String>) {
    Text(
        title,
        style = MaterialTheme.typography.headlineSmall,
        fontWeight = FontWeight.SemiBold,
        maxLines = 3,
        overflow = TextOverflow.Ellipsis,
    )
    if (!meta.isNullOrBlank()) {
        Text(
            meta,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp),
        )
    }
    val ratingText = rating?.takeIf { it > 0 }?.let { "%.1f".format(it) }
    if (ratingText != null || facts.isNotEmpty()) {
        Text(
            buildAnnotatedString {
                if (ratingText != null) {
                    withStyle(SpanStyle(color = MaterialTheme.colorScheme.secondary)) { append("★ ") }
                    append(ratingText)
                    if (facts.isNotEmpty()) append("  ·  ")
                }
                append(facts.joinToString("  ·  "))
            },
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(top = 6.dp),
        )
    }
    if (genres.isNotEmpty()) {
        Text(
            genres.joinToString(", "),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 2.dp),
        )
    }
}

/// Picture and sound facts as small outlined chips, so they read as specs
/// rather than as more of the sentence above them.
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun TechBadges(badges: List<String>, modifier: Modifier = Modifier) {
    if (badges.isEmpty()) return
    FlowRow(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        badges.forEach { badge ->
            Text(
                badge,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(4.dp))
                    .padding(horizontal = 7.dp, vertical = 3.dp),
            )
        }
    }
}

/// Play takes the width; the secondary actions are icons beside it, so
/// there's one obvious thing to press.
@Composable
internal fun PlayActions(
    playLabel: String,
    onPlay: () -> Unit,
    onStartOver: (() -> Unit)?,
    played: Boolean,
    watchedActionInFlight: Boolean,
    onToggleWatched: () -> Unit,
    playFocusRequester: FocusRequester,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        PlayButton(playLabel, onPlay, Modifier.weight(1f).focusRequester(playFocusRequester))
        if (onStartOver != null) {
            ActionIcon(Icons.Filled.Replay, stringResource(R.string.detail_start_over), onClick = onStartOver)
        }
        ActionIcon(
            Icons.Filled.Check,
            stringResource(if (played) R.string.detail_mark_unwatched else R.string.detail_mark_watched),
            onClick = onToggleWatched,
            enabled = !watchedActionInFlight,
            active = played,
        )
    }
}

@Composable
internal fun PlayButton(label: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Button(onClick = onClick, modifier = modifier.heightIn(min = 48.dp).dpadFocusBorder()) {
        Icon(Icons.Filled.PlayArrow, contentDescription = null, modifier = Modifier.size(22.dp))
        Spacer(Modifier.width(6.dp))
        Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/// [active] fills the button in the primary colour — "watched" is a state
/// as well as an action, and should look like one.
@Composable
private fun ActionIcon(
    icon: ImageVector,
    description: String,
    onClick: () -> Unit,
    enabled: Boolean = true,
    active: Boolean = false,
) {
    FilledTonalIconButton(
        onClick = onClick,
        enabled = enabled,
        colors = if (active) {
            IconButtonDefaults.filledTonalIconButtonColors(
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
            )
        } else {
            IconButtonDefaults.filledTonalIconButtonColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant,
                contentColor = MaterialTheme.colorScheme.onSurface,
            )
        },
        modifier = Modifier.size(48.dp).dpadFocusBorder(),
    ) {
        Icon(icon, contentDescription = description)
    }
}

/// How far along this is, as a bar and the time still to go — or a tick
/// once it's watched. Nothing at all for something not yet started.
@Composable
internal fun ProgressLine(positionMs: Long?, durationMs: Long?, played: Boolean, modifier: Modifier = Modifier) {
    if (played) {
        Row(modifier, verticalAlignment = Alignment.CenterVertically) {
            Icon(
                Icons.Filled.Check,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(16.dp),
            )
            Spacer(Modifier.width(4.dp))
            Text(stringResource(R.string.watched), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
        }
        return
    }
    val position = positionMs?.takeIf { it > 0 } ?: return
    val duration = durationMs?.takeIf { it > 0 }
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        if (duration != null) {
            ProgressTrack((position.toFloat() / duration).coerceIn(0f, 1f), Modifier.weight(1f))
            Spacer(Modifier.width(10.dp))
            Text(
                stringResource(R.string.detail_time_left, formatDurationCoarse((duration - position).coerceAtLeast(0))),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            Text(
                stringResource(R.string.resume_at, formatDurationCoarse(position)),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun ProgressTrack(fraction: Float, modifier: Modifier = Modifier) {
    Box(
        modifier
            .height(4.dp)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Box(
            Modifier
                .fillMaxWidth(fraction)
                .fillMaxHeight()
                .background(MaterialTheme.colorScheme.primary),
        )
    }
}

/// "S2:E5", the way the player titles an episode. Null for anything that
/// isn't numbered as one.
internal fun episodeCode(item: Item): String? {
    val episode = item.episode?.let { "E$it" }
    return when {
        item.season != null && episode != null -> "S${item.season}:$episode"
        else -> episode
    }
}

/// The page's main action on a show or season: carry on with the episode
/// the viewer is up to.
@Composable
internal fun NextUp(
    episode: Item,
    onPlay: () -> Unit,
    focusRequester: FocusRequester,
    modifier: Modifier = Modifier,
) {
    val resuming = (episode.resumePositionMs ?: 0) > 0
    val code = episodeCode(episode)
    Column(modifier) {
        PlayButton(
            label = stringResource(
                if (resuming) R.string.detail_resume_episode else R.string.detail_play_episode,
                code ?: episode.title,
            ),
            onClick = onPlay,
            modifier = Modifier.fillMaxWidth().focusRequester(focusRequester),
        )
        if (code != null) {
            Text(
                episode.title,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
        ProgressLine(
            positionMs = episode.resumePositionMs,
            durationMs = episode.resumeDurationMs,
            played = false,
            modifier = Modifier.padding(top = 6.dp),
        )
    }
}

/// The overview, cut to a few lines with a More toggle when it runs long.
/// The toggle is the whole block, so a D-pad user can reach it.
@Composable
internal fun Overview(text: String, modifier: Modifier = Modifier) {
    var expanded by rememberSaveable(text) { mutableStateOf(false) }
    var overflows by remember(text) { mutableStateOf(false) }
    val expandable = overflows || expanded
    Column(
        modifier
            .fillMaxWidth()
            .then(
                if (expandable) {
                    Modifier
                        .dpadFocusBorder(RoundedCornerShape(8.dp))
                        .clip(RoundedCornerShape(8.dp))
                        .clickable { expanded = !expanded }
                } else {
                    Modifier
                },
            ),
    ) {
        Text(
            text,
            style = MaterialTheme.typography.bodyMedium,
            maxLines = if (expanded) Int.MAX_VALUE else 4,
            overflow = TextOverflow.Ellipsis,
            onTextLayout = { if (!expanded) overflows = it.hasVisualOverflow },
        )
        if (expandable) {
            Text(
                stringResource(if (expanded) R.string.detail_less else R.string.detail_more),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
}

@Composable
internal fun SectionTitle(text: String, count: Int? = null, modifier: Modifier = Modifier) {
    Text(
        buildAnnotatedString {
            append(text)
            if (count != null) {
                withStyle(SpanStyle(color = MaterialTheme.colorScheme.onSurfaceVariant, fontWeight = FontWeight.Normal)) {
                    append("  $count")
                }
            }
        },
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.SemiBold,
        modifier = modifier,
    )
}

/// Generic names a rip gives chapters when it has nothing to say: "Chapter
/// 01", "Chapter 1", a bare number, or the start time again.
private val GENERIC_CHAPTER = Regex("""(?i)^\s*(chapter|chap\.?|ch\.?)?\s*\d+\s*$|^\s*\d{1,2}:\d{2}(:\d{2})?([.,]\d+)?\s*$""")

/// Whether the chapters say anything beyond where they start. Numbered-only
/// chapters are just seek marks, which the player's seek bar already shows.
internal fun hasNamedChapters(chapters: List<Chapter>): Boolean =
    chapters.any { chapter -> chapter.title?.takeIf { it.isNotBlank() }?.let { !GENERIC_CHAPTER.matches(it) } == true }

/// Named chapters as a strip of cards; each one starts playback there.
@Composable
internal fun ChaptersRow(
    chapters: List<Chapter>,
    onPlayChapter: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier) {
        SectionTitle(stringResource(R.string.detail_chapters), chapters.size, Modifier.padding(horizontal = 16.dp))
        LazyRow(
            contentPadding = PaddingValues(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier.padding(top = 10.dp),
        ) {
            itemsIndexed(chapters) { index, chapter ->
                Column(
                    Modifier
                        .width(168.dp)
                        .dpadFocusBorder(CARD_SHAPE)
                        .clip(CARD_SHAPE)
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                        .clickable { onPlayChapter(chapter.startMs) }
                        .padding(12.dp),
                ) {
                    Text(
                        formatTimestamp(chapter.startMs),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Text(
                        chapterTitle(chapter, index),
                        style = MaterialTheme.typography.bodyMedium,
                        minLines = 2,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
            }
        }
    }
}

/// A nameless chapter is still a seek point worth showing. Mirrors
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

private const val MAX_CAST = 20

/// Who's in it. The hub has names and roles but no headshots, so each
/// person gets their initials in a circle. Focusable only so a D-pad can
/// scroll the row.
@Composable
internal fun CastRow(cast: List<Credit>, modifier: Modifier = Modifier) {
    Column(modifier) {
        SectionTitle(stringResource(R.string.detail_cast), modifier = Modifier.padding(horizontal = 16.dp))
        LazyRow(
            contentPadding = PaddingValues(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.padding(top = 10.dp),
        ) {
            items(cast.take(MAX_CAST)) { credit ->
                Column(
                    Modifier
                        .width(88.dp)
                        .dpadFocusBorder(RoundedCornerShape(8.dp))
                        .focusable()
                        .padding(4.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Box(
                        Modifier
                            .size(56.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.surfaceVariant),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(initials(credit.name), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Text(
                        credit.name,
                        style = MaterialTheme.typography.bodySmall,
                        textAlign = TextAlign.Center,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 6.dp),
                    )
                    credit.role?.takeIf { it.isNotBlank() }?.let {
                        Text(
                            it,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
    }
}

internal fun initials(name: String): String =
    name.split(Regex("\\s+")).filter { it.isNotEmpty() }.let { words ->
        listOfNotNull(words.firstOrNull(), words.drop(1).lastOrNull())
    }.mapNotNull { it.firstOrNull { c -> c.isLetterOrDigit() }?.uppercaseChar() }.joinToString("")

/// An air date as the device's locale writes it ("5 Mar 2024"); the raw
/// string when it isn't a plain date.
internal fun formatAirDate(premiered: String?): String? {
    val raw = premiered?.takeIf { it.isNotBlank() } ?: return null
    return runCatching {
        LocalDate.parse(raw.take(10)).format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM))
    }.getOrDefault(raw)
}
