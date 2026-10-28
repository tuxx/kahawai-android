package com.kolktech.kahawai.ui.player

import androidx.annotation.DrawableRes
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kolktech.kahawai.R

@DrawableRes
internal fun skipOfferIcon(offer: SkipOffer): Int =
    if (offer.isNextEpisode) R.drawable.kw_ic_skip_next else R.drawable.kw_ic_fast_forward

/// The skip / next-episode offer floating over the video while the
/// controls are hidden; the controller's kw_skip button is its twin for
/// while they're up (same look, same spot). The bright part of the fill
/// drains right to left as [remaining] runs from 1 to 0, counting down to
/// the pill hiding itself.
///
/// Never focusable: with the controls hidden the remote's OK key takes the
/// offer directly (see PlayerContent's key routing), so there's no focus
/// for it to hold.
@Composable
internal fun SkipPill(
    label: String,
    @DrawableRes icon: Int,
    remaining: () -> Float,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .height(42.dp)
            .clip(CircleShape)
            .drawBehind {
                drawRect(Color.White.copy(alpha = 0.7f))
                drawRect(Color.White, size = size.copy(width = size.width * remaining().coerceIn(0f, 1f)))
            }
            .focusProperties { canFocus = false }
            .clickable(role = Role.Button, onClick = onClick)
            .padding(start = 18.dp, end = 20.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(painterResource(icon), contentDescription = null, tint = Color.Black, modifier = Modifier.size(24.dp))
        Spacer(Modifier.width(8.dp))
        Text(label, color = Color.Black, fontWeight = FontWeight.Bold, fontSize = 15.sp, maxLines = 1)
    }
}
