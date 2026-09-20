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

@Composable
fun PosterCard(
    item: Item,
    repo: CatalogRepository,
    onClick: (String) -> Unit,
    modifier: Modifier = Modifier,
    focusRequester: FocusRequester? = null,
) {
    // D-pad navigation has no hover/pressed state to lean on, so the
    // poster needs its own clearly visible affordance on focus — a scale
    // pop plus the hub's own mint-teal accent border, matching the
    // reference web UI's button/CTA color rather than a generic
    // Material focus ring.
    var focused by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(if (focused) 1.06f else 1f, label = "posterFocusScale")
    // Requested from here rather than by the caller: a focusRequester
    // isn't usable until the node it's attached to actually exists, and
    // inside a lazy list that only happens once this composable itself
    // runs — a caller-side LaunchedEffect can fire a frame too early,
    // before this item has been composed, and silently no-op.
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
                .aspectRatio(2f / 3f)
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
                // No local cover, unmatched metadata, or a transient fetch
                // failure all land here (the hub 404s rather than erroring) —
                // same fallback whether it's still loading or gave up.
                placeholder = painterResource(R.drawable.placeholder_poster),
                error = painterResource(R.drawable.placeholder_poster),
                modifier = Modifier.fillMaxWidth().aspectRatio(2f / 3f),
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
            // Sits inside the clipped Box so the line follows the card's
            // rounded bottom corners.
            WatchProgressBar(
                positionMs = item.resumePositionMs,
                durationMs = item.resumeDurationMs,
                played = item.played,
                modifier = Modifier.align(Alignment.BottomCenter),
            )
        }
        Text(
            item.title,
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.SemiBold,
            color = if (focused) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 6.dp),
        )
        item.year?.let {
            Text(
                it.toString(),
                style = MaterialTheme.typography.labelSmall,
                color = KahawaiOnSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
