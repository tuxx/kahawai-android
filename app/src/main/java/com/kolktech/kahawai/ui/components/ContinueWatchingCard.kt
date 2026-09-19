package com.kolktech.kahawai.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.Tv
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.kolktech.kahawai.R
import com.kolktech.kahawai.data.network.dto.Item
import com.kolktech.kahawai.data.repository.CatalogRepository
import com.kolktech.kahawai.ui.theme.KahawaiOnSurfaceVariant
import com.kolktech.kahawai.ui.theme.KahawaiOutline
import com.kolktech.kahawai.ui.theme.KahawaiSurface

private val CARD_SHAPE = RoundedCornerShape(8.dp)
private val CARD_ASPECT_RATIO = 16f / 9f

/// A wide, cropped-poster tile for the continue-watching row — [PosterCard]
/// is bluray-case shaped (2:3), which reads as "browse the library" rather
/// than "pick up where you left off". The hub has no separate landscape
/// artwork (crates/kahawai-hub/src/artwork.rs's `SIZES` are all posters), so
/// this crops the same `card` image into a 16:9 box instead of fetching a
/// different asset.
@Composable
fun ContinueWatchingCard(
    item: Item,
    repo: CatalogRepository,
    onClick: (String) -> Unit,
    modifier: Modifier = Modifier,
    focusRequester: FocusRequester? = null,
    /// Off for the up-next row: those items are, by construction, not
    /// meaningfully started (a series with one is in continue-watching
    /// instead — see api.rs `up_next_from`), so a bar would only ever
    /// draw at 0%.
    showProgress: Boolean = true,
) {
    var focused by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(if (focused) 1.04f else 1f, label = "continueWatchingFocusScale")
    if (focusRequester != null) {
        LaunchedEffect(Unit) { focusRequester.requestFocus() }
    }
    Column(
        modifier = modifier
            .fillMaxWidth()
            .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
            .onFocusChanged { focused = it.isFocused }
            .clickable { onClick(item.id) },
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(CARD_ASPECT_RATIO)
                .graphicsLayer { scaleX = scale; scaleY = scale }
                .clip(CARD_SHAPE)
                .background(KahawaiSurface)
                .border(
                    width = if (focused) 2.dp else 1.dp,
                    color = if (focused) MaterialTheme.colorScheme.primary else KahawaiOutline,
                    shape = CARD_SHAPE,
                ),
        ) {
            AsyncImage(
                model = repo.artworkUrl(item.libraryId, item.id, "card"),
                contentDescription = item.title,
                contentScale = ContentScale.Crop,
                // Same fallback whether the poster is still loading or the
                // hub has none for this item — see PosterCard.
                placeholder = painterResource(R.drawable.placeholder_poster),
                error = painterResource(R.drawable.placeholder_poster),
                modifier = Modifier.fillMaxWidth().aspectRatio(CARD_ASPECT_RATIO),
            )
            Box(
                modifier = Modifier
                    .padding(6.dp)
                    .size(22.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(KahawaiSurface.copy(alpha = 0.85f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = if (item.kind == "movie") Icons.Filled.Movie else Icons.Filled.Tv,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(14.dp),
                )
            }
            if (showProgress) {
                WatchProgressBar(
                    positionMs = item.resumePositionMs,
                    durationMs = item.resumeDurationMs,
                    played = item.played,
                    modifier = Modifier.align(Alignment.BottomCenter),
                )
            }
        }
        Text(
            item.continueWatchingTitle(),
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.SemiBold,
            color = if (focused) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 6.dp),
        )
        item.continueWatchingSubtitle()?.let {
            Text(
                it,
                style = MaterialTheme.typography.labelSmall,
                color = KahawaiOnSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/// The show's name for an episode (a title of just "Pilot" is one of eight
/// on this row) — the item's own title everywhere else.
private fun Item.continueWatchingTitle(): String =
    if (kind == "episode") parentTitle ?: title else title

/// Season + episode number under the show name; the release year under a
/// movie's own title, same split as [PosterCard].
private fun Item.continueWatchingSubtitle(): String? =
    if (kind == "episode") seasonEpisodeLabel(season, episode) else year?.toString()

/// S01E02 for seasoned episodes; E11 for absolute numbering (anime) — a null
/// season means absolute numbering, not a missing one, mirroring
/// web/src/domain/label.ts's `seLabel`.
private fun seasonEpisodeLabel(season: Int?, episode: Int?): String? {
    if (season == null && episode == null) return null
    val e = "E%02d".format(episode ?: 0)
    return if (season == null) e else "S%02d%s".format(season, e)
}
