package com.kolktech.kahawai.ui.player

import androidx.media3.common.C
import com.kolktech.kahawai.R
import com.kolktech.kahawai.data.network.dto.Chapter
import com.kolktech.kahawai.data.network.dto.Item
import com.kolktech.kahawai.data.network.dto.Segment
import com.kolktech.kahawai.data.network.dto.SubtitleTrack
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PlayerViewModelLogicTest {

    private fun episode(id: String) = Item(id = id, kind = "episode", title = id, libraryId = "lib1")

    private fun subtitle(
        id: Long,
        origin: String = "embedded",
        format: String = "vobsub",
        delivery: String = "overlay",
        language: String? = "en",
        streamIndex: Long? = null,
    ) = SubtitleTrack(
        id = id,
        itemId = "ep1",
        origin = origin,
        streamIndex = streamIndex,
        format = format,
        language = language,
        delivery = delivery,
    )

    // resumePlan

    @Test
    fun `direct mode seeks natively with offset zero`() {
        val plan = resumePlan("direct", startMs = 45_000)
        assertEquals(ResumePlan(offsetMs = 0, startPositionMs = 45_000), plan)
    }

    @Test
    fun `remux mode attaches at zero with offset equal to startMs`() {
        val plan = resumePlan("remux", startMs = 45_000)
        assertEquals(ResumePlan(offsetMs = 45_000, startPositionMs = 0), plan)
    }

    @Test
    fun `transcode mode is treated like remux`() {
        val plan = resumePlan("transcode", startMs = 45_000)
        assertEquals(ResumePlan(offsetMs = 45_000, startPositionMs = 0), plan)
    }

    @Test
    fun `both modes converge when startMs is zero`() {
        assertEquals(ResumePlan(offsetMs = 0, startPositionMs = 0), resumePlan("direct", startMs = 0))
        assertEquals(ResumePlan(offsetMs = 0, startPositionMs = 0), resumePlan("remux", startMs = 0))
    }

    // localSeekPositionMs

    @Test
    fun `a target inside the produced window seeks locally`() {
        // Playlist starts at 10:00 and 5 minutes of it have been produced.
        val local = localSeekPositionMs(targetMs = 720_000, offsetMs = 600_000, producedMs = 300_000)
        assertEquals(120_000L, local)
    }

    @Test
    fun `a target before the playlist start needs the hub`() {
        assertNull(localSeekPositionMs(targetMs = 599_000, offsetMs = 600_000, producedMs = 300_000))
    }

    @Test
    fun `a target past the produced edge needs the hub`() {
        assertNull(localSeekPositionMs(targetMs = 960_000, offsetMs = 600_000, producedMs = 300_000))
    }

    @Test
    fun `the produced edge itself is left to the hub`() {
        // Exactly at the edge, and just inside it by less than the tail:
        // both would land on content the encoder hasn't reached yet.
        assertNull(localSeekPositionMs(targetMs = 900_000, offsetMs = 600_000, producedMs = 300_000))
        assertNull(localSeekPositionMs(targetMs = 898_000, offsetMs = 600_000, producedMs = 300_000))
        assertEquals(
            297_000L,
            localSeekPositionMs(targetMs = 897_000, offsetMs = 600_000, producedMs = 300_000),
        )
    }

    @Test
    fun `the playlist start itself seeks locally`() {
        assertEquals(0L, localSeekPositionMs(targetMs = 600_000, offsetMs = 600_000, producedMs = 300_000))
    }

    @Test
    fun `an unknown produced length needs the hub`() {
        assertNull(localSeekPositionMs(targetMs = 720_000, offsetMs = 600_000, producedMs = C.TIME_UNSET))
    }

    // seekWindowBandPx

    @Test
    fun `the band spans the window across the bar's own edges`() {
        // Bar drawn from x=100 to x=1100; window covers the second half.
        val band = seekWindowBandPx(startMs = 50_000, endMs = 100_000, durationMs = 100_000, leftPx = 100, rightPx = 1100)
        assertEquals(600..1100, band)
    }

    @Test
    fun `a window covering everything spans the whole bar`() {
        val band = seekWindowBandPx(startMs = 0, endMs = 100_000, durationMs = 100_000, leftPx = 100, rightPx = 1100)
        assertEquals(100..1100, band)
    }

    @Test
    fun `an empty or unmeasured bar draws nothing`() {
        assertNull(seekWindowBandPx(startMs = 0, endMs = 100_000, durationMs = 0, leftPx = 100, rightPx = 1100))
        assertNull(seekWindowBandPx(startMs = 0, endMs = 100_000, durationMs = 100_000, leftPx = 100, rightPx = 100))
        assertNull(seekWindowBandPx(startMs = 50_000, endMs = 50_000, durationMs = 100_000, leftPx = 100, rightPx = 1100))
    }

    @Test
    fun `a window running past the ends is clamped to the bar`() {
        val band = seekWindowBandPx(startMs = -5_000, endMs = 200_000, durationMs = 100_000, leftPx = 100, rightPx = 1100)
        assertEquals(100..1100, band)
    }

    // resolveNextEpisode

    @Test
    fun `returns next sibling id when item is mid-list`() {
        val siblings = listOf(episode("e1"), episode("e2"), episode("e3"))
        val next = resolveNextEpisode(kind = "episode", parentId = "show1", itemId = "e2", siblings = siblings)
        assertEquals("e3", next)
    }

    @Test
    fun `returns null for the last episode`() {
        val siblings = listOf(episode("e1"), episode("e2"), episode("e3"))
        val next = resolveNextEpisode(kind = "episode", parentId = "show1", itemId = "e3", siblings = siblings)
        assertNull(next)
    }

    @Test
    fun `returns null for a non-episode kind`() {
        val siblings = listOf(episode("e1"), episode("e2"))
        val next = resolveNextEpisode(kind = "movie", parentId = "show1", itemId = "e1", siblings = siblings)
        assertNull(next)
    }

    @Test
    fun `returns null when parentId is null`() {
        val siblings = listOf(episode("e1"), episode("e2"))
        val next = resolveNextEpisode(kind = "episode", parentId = null, itemId = "e1", siblings = siblings)
        assertNull(next)
    }

    @Test
    fun `returns null when itemId is not found in siblings`() {
        val siblings = listOf(episode("e1"), episode("e2"))
        val next = resolveNextEpisode(kind = "episode", parentId = "show1", itemId = "missing", siblings = siblings)
        assertNull(next)
    }

    @Test
    fun `returns null for empty siblings`() {
        val next = resolveNextEpisode(kind = "episode", parentId = "show1", itemId = "e1", siblings = emptyList())
        assertNull(next)
    }

    // resolvePreviousEpisode

    @Test
    fun `returns previous sibling id when item is mid-list`() {
        val siblings = listOf(episode("e1"), episode("e2"), episode("e3"))
        val previous = resolvePreviousEpisode(kind = "episode", parentId = "show1", itemId = "e2", siblings = siblings)
        assertEquals("e1", previous)
    }

    @Test
    fun `returns null for the first episode`() {
        val siblings = listOf(episode("e1"), episode("e2"), episode("e3"))
        val previous = resolvePreviousEpisode(kind = "episode", parentId = "show1", itemId = "e1", siblings = siblings)
        assertNull(previous)
    }

    @Test
    fun `previous returns null for a non-episode kind`() {
        val siblings = listOf(episode("e1"), episode("e2"))
        val previous = resolvePreviousEpisode(kind = "movie", parentId = "show1", itemId = "e2", siblings = siblings)
        assertNull(previous)
    }

    @Test
    fun `previous returns null when parentId is null`() {
        val siblings = listOf(episode("e1"), episode("e2"))
        val previous = resolvePreviousEpisode(kind = "episode", parentId = null, itemId = "e2", siblings = siblings)
        assertNull(previous)
    }

    @Test
    fun `previous returns null when itemId is not found in siblings`() {
        val siblings = listOf(episode("e1"), episode("e2"))
        val previous = resolvePreviousEpisode(kind = "episode", parentId = "show1", itemId = "missing", siblings = siblings)
        assertNull(previous)
    }

    @Test
    fun `previous returns null for empty siblings`() {
        val previous = resolvePreviousEpisode(kind = "episode", parentId = "show1", itemId = "e1", siblings = emptyList())
        assertNull(previous)
    }

    // computeOriginCorrection

    @Test
    fun `treats null partBaseMs as zero`() {
        val correction = computeOriginCorrection(partBaseMs = null, localMs = 5_000, currentOffsetMs = 0)
        assertEquals(5_000L, correction.correctedOffsetMs)
    }

    @Test
    fun `sums partBaseMs and local position`() {
        val correction = computeOriginCorrection(partBaseMs = 120_000, localMs = 5_000, currentOffsetMs = 0)
        assertEquals(125_000L, correction.correctedOffsetMs)
    }

    @Test
    fun `is in bounds when within the default 60s tolerance`() {
        val correction = computeOriginCorrection(partBaseMs = 0, localMs = 45_000, currentOffsetMs = 44_000)
        assertEquals(true, correction.inBounds)
    }

    @Test
    fun `is out of bounds when the correction is wildly different`() {
        val correction = computeOriginCorrection(partBaseMs = 0, localMs = 200_000, currentOffsetMs = 0)
        assertEquals(false, correction.inBounds)
    }

    @Test
    fun `honors a custom bound`() {
        val correction = computeOriginCorrection(partBaseMs = 0, localMs = 1_500, currentOffsetMs = 0, boundMs = 1_000)
        assertEquals(false, correction.inBounds)
    }

    // matchesSideloadedTrackId

    @Test
    fun `matches a track id rewritten with a MergingMediaPeriod child prefix`() {
        assertEquals(true, matchesSideloadedTrackId(formatId = "1:1234", wantedId = "1234"))
    }

    @Test
    fun `matches a plain track id with no prefix`() {
        assertEquals(true, matchesSideloadedTrackId(formatId = "1234", wantedId = "1234"))
    }

    @Test
    fun `does not match a different track id`() {
        assertEquals(false, matchesSideloadedTrackId(formatId = "1:1234", wantedId = "5678"))
    }

    @Test
    fun `does not match a null format id`() {
        assertEquals(false, matchesSideloadedTrackId(formatId = null, wantedId = "1234"))
    }

    // subtitleVttUrl

    @Test
    fun `builds a vtt url with the shift negated from offset`() {
        val url = subtitleVttUrl(baseUrl = "https://hub.local", sessionId = "s1", trackId = 42, offsetMs = 5_000)
        assertEquals("https://hub.local/api/v1/playback/sessions/s1/subtitles/42.vtt?shift_ms=-5000", url)
    }

    @Test
    fun `trims a trailing slash off the base url`() {
        val url = subtitleVttUrl(baseUrl = "https://hub.local/", sessionId = "s1", trackId = 42, offsetMs = 0)
        assertEquals("https://hub.local/api/v1/playback/sessions/s1/subtitles/42.vtt?shift_ms=0", url)
    }

    // skippableSegment / skipLabelRes / skipTargetMs

    private fun segment(kind: String, startMs: Long, endMs: Long, source: String = "chromaprint") =
        Segment(kind = kind, startMs = startMs, endMs = endMs, source = source)

    @Test
    fun `finds the segment the playhead is inside`() {
        val intro = segment("intro", 10_000, 30_000)
        val found = skippableSegment(listOf(intro), posMs = 15_000)
        assertEquals(intro, found)
    }

    @Test
    fun `returns null before the segment starts`() {
        val intro = segment("intro", 10_000, 30_000)
        assertNull(skippableSegment(listOf(intro), posMs = 9_999))
    }

    @Test
    fun `returns null inside the tail where the button would be unpressable`() {
        val intro = segment("intro", 10_000, 30_000)
        // 30_000 - SKIP_TAIL_MS (1_500) = 28_500; at or past that, hidden.
        assertNull(skippableSegment(listOf(intro), posMs = 28_500))
    }

    @Test
    fun `honors a custom tail`() {
        val intro = segment("intro", 10_000, 30_000)
        assertNull(skippableSegment(listOf(intro), posMs = 25_000, tailMs = 10_000))
    }

    @Test
    fun `ignores a segment of an unknown kind`() {
        val weird = segment("preview", 10_000, 30_000)
        assertNull(skippableSegment(listOf(weird), posMs = 15_000))
    }

    @Test
    fun `first match wins when segments overlap`() {
        val recap = segment("recap", 0, 20_000)
        val intro = segment("intro", 10_000, 30_000)
        val found = skippableSegment(listOf(recap, intro), posMs = 15_000)
        assertEquals(recap, found)
    }

    @Test
    fun `maps each known kind to its own label resource`() {
        assertEquals(R.string.player_skip_recap, skipLabelRes(segment("recap", 0, 1_000)))
        assertEquals(R.string.player_skip_intro, skipLabelRes(segment("intro", 0, 1_000)))
        assertEquals(R.string.player_skip_credits, skipLabelRes(segment("credits", 0, 1_000)))
        assertNull(skipLabelRes(segment("preview", 0, 1_000)))
        assertNull(skipLabelRes(null))
    }

    @Test
    fun `skip target lands at the segment end when short of the duration`() {
        val credits = segment("credits", 100_000, 118_000)
        assertEquals(118_000L, skipTargetMs(credits, durationMs = 130_000))
    }

    @Test
    fun `skip target never lands on the very last second of the file`() {
        val credits = segment("credits", 100_000, 120_000)
        assertEquals(119_000L, skipTargetMs(credits, durationMs = 120_000))
    }

    @Test
    fun `skip target is untouched when duration is unknown`() {
        val credits = segment("credits", 100_000, 120_000)
        assertEquals(120_000L, skipTargetMs(credits, durationMs = 0))
    }

    // chapterMarkTimesMs

    @Test
    fun `drops a chapter at zero and keeps the rest`() {
        val chapters = listOf(
            Chapter(startMs = 0, title = "Start"),
            Chapter(startMs = 60_000, title = "Two"),
        )
        val marks = chapterMarkTimesMs(chapters, durationMs = 120_000)
        assertEquals(listOf(60_000L), marks.toList())
    }

    @Test
    fun `drops a chapter at or past the duration`() {
        val chapters = listOf(Chapter(startMs = 60_000), Chapter(startMs = 120_000))
        val marks = chapterMarkTimesMs(chapters, durationMs = 120_000)
        assertEquals(listOf(60_000L), marks.toList())
    }

    @Test
    fun `no marks when duration is unknown`() {
        val chapters = listOf(Chapter(startMs = 60_000))
        assertEquals(0, chapterMarkTimesMs(chapters, durationMs = 0).size)
    }

    // isNativeBitmapPick

    @Test
    fun `an embedded bitmap track in a direct session decodes in-player`() {
        assertEquals(true, isNativeBitmapPick(subtitle(1), isDirect = true))
        assertEquals(true, isNativeBitmapPick(subtitle(1, format = "pgs"), isDirect = true))
        assertEquals(true, isNativeBitmapPick(subtitle(1, format = "dvdsub"), isDirect = true))
    }

    @Test
    fun `an hls session keeps the session tap`() {
        assertEquals(false, isNativeBitmapPick(subtitle(1), isDirect = false))
    }

    @Test
    fun `a raster track keeps its own item-scoped source`() {
        assertEquals(false, isNativeBitmapPick(subtitle(1, origin = "raster"), isDirect = true))
    }

    @Test
    fun `text and ass deliveries are untouched`() {
        assertEquals(false, isNativeBitmapPick(subtitle(1, format = "srt", delivery = "text"), isDirect = true))
        assertEquals(false, isNativeBitmapPick(subtitle(1, format = "ass", delivery = "ass"), isDirect = true))
        assertEquals(false, isNativeBitmapPick(null, isDirect = true))
    }

    // embeddedBitmapOrdinal

    @Test
    fun `counts only the bitmap tracks the container carries, in stream order`() {
        val second = subtitle(7, streamIndex = 4, language = "nl")
        val tracks = listOf(
            subtitle(1, origin = "sidecar", format = "srt", delivery = "text", streamIndex = null),
            second,
            subtitle(9, format = "srt", delivery = "text", streamIndex = 2),
            subtitle(3, streamIndex = 3),
        )
        assertEquals(0, embeddedBitmapOrdinal(tracks, tracks.first { it.id == 3L }))
        assertEquals(1, embeddedBitmapOrdinal(tracks, second))
    }

    @Test
    fun `a track the container does not carry has no ordinal`() {
        val downloaded = subtitle(5, origin = "downloaded", format = "srt", delivery = "text")
        assertNull(embeddedBitmapOrdinal(listOf(subtitle(1, streamIndex = 2), downloaded), downloaded))
    }

    // nativeBitmapGroupIndex

    @Test
    fun `finds a bitmap group behind the extraction-time cues mime`() {
        val groups = listOf(
            TextTrackInfo("text/vtt", null, "en"),
            TextTrackInfo("application/x-media3-cues", "application/vobsub", "nl"),
        )
        assertEquals(1, nativeBitmapGroupIndex(groups, ordinal = 0, language = "nl"))
    }

    @Test
    fun `finds a bitmap group declared with its own mime`() {
        val groups = listOf(TextTrackInfo("application/pgs", null, "en"))
        assertEquals(0, nativeBitmapGroupIndex(groups, ordinal = 0, language = "en"))
    }

    @Test
    fun `language wins over position when it singles a track out`() {
        val groups = listOf(
            TextTrackInfo("application/vobsub", null, "eng"),
            TextTrackInfo("application/vobsub", null, "nld"),
        )
        assertEquals(1, nativeBitmapGroupIndex(groups, ordinal = 0, language = "nl"))
    }

    @Test
    fun `position decides between two tracks of the same language`() {
        val groups = listOf(
            TextTrackInfo("application/vobsub", null, "en"),
            TextTrackInfo("application/vobsub", null, "en"),
        )
        assertEquals(1, nativeBitmapGroupIndex(groups, ordinal = 1, language = "en"))
    }

    @Test
    fun `position decides when the container declares no language`() {
        val groups = listOf(
            TextTrackInfo("text/vtt", null, "en"),
            TextTrackInfo("application/vobsub", null, null),
        )
        assertEquals(1, nativeBitmapGroupIndex(groups, ordinal = 0, language = "en"))
    }

    @Test
    fun `no bitmap group means no match`() {
        val groups = listOf(TextTrackInfo("text/vtt", null, "en"))
        assertNull(nativeBitmapGroupIndex(groups, ordinal = 0, language = "en"))
        assertNull(nativeBitmapGroupIndex(emptyList(), ordinal = 0, language = "en"))
    }

    @Test
    fun `an ordinal past the end of the container's bitmap tracks has no match`() {
        val groups = listOf(TextTrackInfo("application/vobsub", null, "de"))
        assertNull(nativeBitmapGroupIndex(groups, ordinal = 2, language = "en"))
    }
}
