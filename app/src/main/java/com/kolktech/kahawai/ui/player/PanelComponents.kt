package com.kolktech.kahawai.ui.player

import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/// The rows, lists and chips the player's side panel is built from, shared
/// with the detail page's track sheet so both pick tracks the same way.
/// Only the colours differ between the two: the panel sits over video in
/// neutral greys, the detail page on the app's own teal surfaces.
internal data class PanelColors(
    val panel: Color,
    val group: Color,
    val focus: Color,
    val secondaryText: Color,
    val sectionText: Color,
)

internal val PlayerPanelColors = PanelColors(
    panel = Color(0xF2161618),
    group = Color(0xFF242427),
    focus = Color.White.copy(alpha = 0.16f),
    secondaryText = Color.White.copy(alpha = 0.6f),
    sectionText = Color.White.copy(alpha = 0.5f),
)

internal val LocalPanelColors = staticCompositionLocalOf { PlayerPanelColors }

// Sized for a phone held sideways (~400dp tall), where the panel has to
// show a whole settings group without scrolling; a tablet or TV just gets
// more of it on screen at once.
internal val RowLabelSize = 15.sp
internal val RowValueSize = 14.sp
internal val RowDetailSize = 12.sp
private val RowMinHeight = 44.dp

@Composable
internal fun RowScope.TabButton(label: String, count: Int, selected: Boolean, onClick: () -> Unit) {
    val colors = LocalPanelColors.current
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    Box(
        modifier = Modifier
            .weight(1f)
            .clip(CircleShape)
            .background(
                when {
                    selected -> Color.White
                    focused -> colors.focus
                    else -> Color.Transparent
                },
            )
            .clickable(interaction, LocalIndication.current, role = Role.Tab, onClick = onClick)
            .padding(vertical = 7.dp),
        contentAlignment = Alignment.Center,
    ) {
        val textColor = if (selected) Color.Black else Color.White
        Text(
            buildAnnotatedString {
                append(label)
                withStyle(SpanStyle(color = textColor.copy(alpha = 0.5f), fontWeight = FontWeight.Normal)) { append("  $count") }
            },
            color = textColor,
            fontWeight = FontWeight.SemiBold,
            fontSize = 14.sp,
        )
    }
}

/// A list of choices with the current one ticked. D-pad focus lands on the
/// ticked row, so a remote user starts where they already are.
@Composable
internal fun OptionList(options: List<TrackOption>, onSelect: (Int) -> Unit) {
    val initial = options.indexOfFirst { it.selected }.coerceAtLeast(0)
    val listState = rememberLazyListState(initialFirstVisibleItemIndex = (initial - 2).coerceAtLeast(0))
    val focus = rememberInitialFocus()
    LazyColumn(
        state = listState,
        contentPadding = PaddingValues(start = 6.dp, end = 6.dp, bottom = 12.dp),
    ) {
        itemsIndexed(options) { index, option ->
            OptionRow(
                option,
                onClick = { onSelect(index) },
                modifier = if (index == initial) Modifier.focusRequester(focus) else Modifier,
            )
        }
    }
}

@Composable
internal fun OptionRow(option: TrackOption, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val colors = LocalPanelColors.current
    PanelRow(
        onClick = onClick,
        role = Role.RadioButton,
        enabled = option.enabled,
        background = if (option.selected) colors.group else Color.Transparent,
        shape = RoundedCornerShape(12.dp),
        modifier = modifier,
    ) {
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    option.title,
                    color = Color.White,
                    fontSize = RowLabelSize,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                option.badges.forEach { Badge(it) }
            }
            option.detail?.let {
                Text(it, color = colors.secondaryText, fontSize = RowDetailSize, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
        if (option.selected) {
            Spacer(Modifier.width(10.dp))
            Icon(Icons.Filled.Check, contentDescription = null, tint = Color.White, modifier = Modifier.size(20.dp))
        }
    }
}

@Composable
internal fun Badge(text: String) {
    Text(
        text,
        color = Color.White.copy(alpha = 0.85f),
        fontSize = 10.sp,
        fontWeight = FontWeight.Bold,
        maxLines = 1,
        modifier = Modifier
            .background(Color.White.copy(alpha = 0.15f), RoundedCornerShape(4.dp))
            .padding(horizontal = 6.dp, vertical = 1.dp),
    )
}

@Composable
internal fun SectionHeader(text: String) {
    val colors = LocalPanelColors.current
    Text(
        text.uppercase(),
        color = colors.sectionText,
        fontSize = 11.sp,
        fontWeight = FontWeight.Medium,
        letterSpacing = 1.sp,
        modifier = Modifier.padding(start = 12.dp, top = 12.dp, bottom = 5.dp),
    )
}

@Composable
internal fun RowGroup(content: @Composable () -> Unit) {
    val colors = LocalPanelColors.current
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(colors.group),
    ) { content() }
}

@Composable
internal fun GroupDivider() {
    HorizontalDivider(color = Color.White.copy(alpha = 0.06f))
}

@Composable
internal fun ValueRow(
    label: String,
    value: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val colors = LocalPanelColors.current
    PanelRow(onClick = onClick, role = Role.Button, enabled = enabled, modifier = modifier) {
        Text(label, color = Color.White, fontSize = RowLabelSize, fontWeight = FontWeight.Medium, maxLines = 1)
        Spacer(Modifier.width(12.dp))
        Text(
            value,
            color = colors.secondaryText,
            fontSize = RowValueSize,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
            textAlign = TextAlign.End,
        )
        Icon(
            Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = null,
            tint = colors.secondaryText,
            modifier = Modifier.padding(start = 2.dp).size(18.dp),
        )
    }
}

/// A clickable panel row with a visible focus state, which is all a D-pad
/// user has to go by: the ripple's own focus tint is too faint over the
/// dark panel.
@Composable
internal fun PanelRow(
    onClick: () -> Unit,
    role: Role,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    background: Color = Color.Transparent,
    shape: RoundedCornerShape = RoundedCornerShape(0.dp),
    content: @Composable RowScope.() -> Unit,
) {
    val colors = LocalPanelColors.current
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = RowMinHeight)
            .clip(shape)
            .background(if (focused) colors.focus else background)
            .clickable(interaction, LocalIndication.current, enabled = enabled, role = role, onClick = onClick)
            .alpha(if (enabled) 1f else 0.4f)
            .padding(horizontal = 14.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        content = content,
    )
}

/// Focus for the row a page should start on. Requested a frame after the
/// page appears, once the row is laid out; on touch devices, where rows
/// aren't focusable, the request just does nothing.
@Composable
internal fun rememberInitialFocus(): FocusRequester {
    val requester = remember { FocusRequester() }
    LaunchedEffect(requester) {
        withFrameNanos {}
        runCatching { requester.requestFocus() }
    }
    return requester
}
