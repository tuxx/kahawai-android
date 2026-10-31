package com.kolktech.kahawai.ui.player

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

private const val STATS_INTERVAL_MS = 1_000L

/// The stats-for-nerds readout. It reads the player once a second and
/// shows rates over that second. It draws only: it takes no focus and no
/// touches, so the controls under it keep working.
@Composable
internal fun PlaybackStatsOverlay(
    sample: () -> PlaybackStatsSnapshot,
    display: () -> DisplayStats,
    modifier: Modifier = Modifier,
) {
    var lines by remember { mutableStateOf<List<StatLine>>(emptyList()) }
    LaunchedEffect(Unit) {
        var previous = sample()
        // The first reading has nothing to compare against, so it shows
        // without rates rather than leaving the box empty for a second.
        lines = statsLines(previous, previous, display())
        while (true) {
            delay(STATS_INTERVAL_MS)
            val current = sample()
            lines = statsLines(previous, current, display())
            previous = current
        }
    }
    Column(
        modifier
            .widthIn(max = 420.dp)
            .background(Color.Black.copy(alpha = 0.6f), RoundedCornerShape(8.dp))
            .padding(horizontal = 10.dp, vertical = 8.dp),
    ) {
        lines.forEach { line ->
            Row {
                Text(
                    line.label,
                    color = Color.White.copy(alpha = 0.6f),
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp,
                    lineHeight = 14.sp,
                    modifier = Modifier.width(84.dp),
                )
                Text(
                    line.value,
                    color = Color.White,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp,
                    lineHeight = 14.sp,
                )
            }
        }
    }
}
