package com.kolktech.kahawai.ui.player

import com.kolktech.kahawai.data.network.dto.Chapter
import com.kolktech.kahawai.data.network.dto.Segment
import com.kolktech.kahawai.data.network.dto.SubtitleTrack
import kotlinx.serialization.Serializable

/// What PlayerViewModel.start() would otherwise learn from its own
/// itemQuery round trip, carried forward from the Detail screen instead —
/// it already ran the identical QUERY (DetailViewModel.load()) before the
/// user ever pressed Play, so pressing Play doesn't need to pay for it a
/// second time. [parentId] is the item's own, for the series-scoped
/// subtitle/audio memory (see PlayerViewModel.seriesId). Absent (null)
/// for a navigation that skipped the Detail screen — auto-advance and the
/// in-player "<"/">" buttons jump straight into the next/previous
/// episode's player — in which case start() falls back to querying it
/// itself, exactly as it always has.
@Serializable
data class PlaybackPrefetch(
    val subtitleTracks: List<SubtitleTrack> = emptyList(),
    val segments: List<Segment> = emptyList(),
    val chapters: List<Chapter> = emptyList(),
    val parentId: String? = null,
)
