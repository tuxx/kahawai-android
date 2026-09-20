package com.kolktech.kahawai.playback

import com.kolktech.kahawai.data.network.dto.ClientAudioStream
import com.kolktech.kahawai.data.network.dto.Pref
import com.kolktech.kahawai.data.network.dto.SubtitleTrack
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TrackChoiceTest {

    private fun track(
        id: Long,
        language: String? = "en",
        delivery: String = "text",
        format: String = "srt",
    ) = SubtitleTrack(
        id = id,
        itemId = "ep1",
        origin = "embedded",
        format = format,
        language = language,
        delivery = delivery,
    )

    /// Exact track choices are scoped to the physical SOURCE, not the item
    /// — `source:<collection_item_id>:<source_id>`, the same spelling the hub
    /// migrated these rows to and the web client writes.
    private val sourceScope = sourcePreferenceScope("copy1", 3)

    private fun resolve(prefs: List<Pref>, tracks: List<SubtitleTrack>, mediaType: String = "movie") =
        resolveSubtitleTrack(prefs, seriesId = "show1", sourceScope = sourceScope, mediaType = mediaType, tracks = tracks)

    // sourcePreferenceScope

    @Test
    fun `the exact-choice scope names the copy and the source`() {
        // `source:<collection_item_id>:<source_id>` — the spelling migration
        // 0080 wrote and web/src/domain/source.ts builds. A numeric source id
        // alone is not enough: they are reused after a deletion.
        assertEquals("source:copy1:3", sourcePreferenceScope("copy1", 3))
        assertNull(sourcePreferenceScope(null, 3))
        assertNull(sourcePreferenceScope("copy1", null))
        assertNull(sourcePreferenceScope("", 3))
    }

    @Test
    fun `an exact pref under the old item scope is ignored`() {
        // What the mediadb rewrite changed: these rows used to hang off the
        // item id, and reading them there now silently resurrects a choice
        // the hub has re-keyed.
        val prefs = listOf(Pref("ep1", "subs.track", "7"))
        val tracks = listOf(track(7, "en"), track(8, "nl"))
        assertNull(resolve(prefs, tracks))
    }

    /// The player route encodes "no subtitle" as a sentinel and everything
    /// else as the id itself. -1 cannot be that sentinel: the hub issues
    /// negative ids for embedded tracks, so -1 and -2 are real picks and a
    /// `>= 0` test threw every embedded selection away at the route
    /// boundary. See Routes.NO_SUBTITLE_TRACK.
    @Test
    fun `negative track ids are real picks, not a no-subtitle sentinel`() {
        val embedded = listOf(track(-1, "en"), track(-2, "en"), track(1, "fr"))
        // Every id the hub can issue must survive a round trip that only
        // treats the reserved sentinel as "none".
        for (id in embedded.map { it.id }) {
            assertEquals(id, id.takeIf { it != Long.MIN_VALUE })
        }
        assertNull(Long.MIN_VALUE.takeIf { it != Long.MIN_VALUE })
    }

    @Test
    fun `a negative track id round-trips through the exact pref`() {
        // The hub numbers embedded streams negatively; parsing must be signed.
        val prefs = listOf(Pref("source:copy1:3", "subs.track", "-2"))
        val tracks = listOf(track(-2, "en"), track(1, "nl"))
        assertEquals(-2L, resolve(prefs, tracks)?.id)
        assertEquals("-2", rememberedSubsTrackValue(track(-2, "en")))
    }

    // resolveSubtitleTrack

    @Test
    fun `this item's exact track beats every wishlist`() {
        val prefs = listOf(
            Pref("source:copy1:3", "subs.track", "7"),
            Pref("show1", "subs", "nl"),
            Pref("", "subs.movie", "de"),
        )
        val tracks = listOf(track(7, "en"), track(8, "nl"), track(9, "de"))
        assertEquals(7L, resolve(prefs, tracks)?.id)
    }

    @Test
    fun `an exact track this item doesn't have falls back to the wishlist`() {
        // Auto-advance carries the previous episode's track id; ids don't
        // survive the file boundary, the remembered language does.
        val prefs = listOf(Pref("source:copy1:3", "subs.track", "999"), Pref("show1", "subs", "nl"))
        val tracks = listOf(track(1, "en"), track(2, "nl"))
        assertEquals(2L, resolve(prefs, tracks)?.id)
    }

    @Test
    fun `an exact track this client can't be served is ignored`() {
        val prefs = listOf(Pref("source:copy1:3", "subs.track", "1"), Pref("show1", "subs", "en"))
        val tracks = listOf(track(1, "en", delivery = "none"), track(2, "en"))
        assertEquals(2L, resolve(prefs, tracks)?.id)
    }

    @Test
    fun `a title watched without subtitles opens without them`() {
        val prefs = listOf(Pref("show1", "subs", "off"), Pref("", "subs.movie", "en"))
        assertNull(resolve(prefs, listOf(track(1, "en"))))
    }

    @Test
    fun `the series memory beats the account's list`() {
        val prefs = listOf(Pref("show1", "subs", "ja"), Pref("", "subs.movie", "en"))
        val tracks = listOf(track(1, "en"), track(2, "ja"))
        assertEquals(2L, resolve(prefs, tracks)?.id)
    }

    @Test
    fun `the account's list is tried in order, for this media type`() {
        val prefs = listOf(Pref("", "subs.movie", "de, nl,en"), Pref("", "subs.show", "ja"))
        val tracks = listOf(track(1, "en"), track(2, "nl"))
        assertEquals(2L, resolve(prefs, tracks)?.id)
        assertNull(resolve(prefs, listOf(track(3, "ja"))))
    }

    @Test
    fun `nothing remembered and nothing preferred opens without subtitles`() {
        assertNull(resolve(emptyList(), listOf(track(1, "en"))))
    }

    // pickSubtitleTrack

    @Test
    fun `within a language the best reading wins over listing order`() {
        val tracks = listOf(
            track(1, "en", delivery = "text"),
            track(2, "en", delivery = "overlay"),
            track(3, "en", delivery = "ass", format = "ass"),
        )
        assertEquals(3L, pickSubtitleTrack(listOf("en"), tracks)?.id)
        assertEquals(2L, pickSubtitleTrack(listOf("en"), tracks.take(2))?.id)
    }

    @Test
    fun `a language wish never auto-picks a burn or a bitmap track`() {
        val tracks = listOf(
            track(1, "en", delivery = "burn"),
            track(2, "en", delivery = "overlay", format = "pgs"),
        )
        assertNull(pickSubtitleTrack(listOf("en"), tracks))
        assertNull(pickSubtitleTrack(listOf("any"), tracks))
    }

    @Test
    fun `two letters are a match, and 'any' takes the best there is`() {
        val tracks = listOf(track(1, "eng"), track(2, "nl-BE", delivery = "ass", format = "ass"))
        assertEquals(1L, pickSubtitleTrack(listOf("en-GB"), tracks)?.id)
        assertEquals(2L, pickSubtitleTrack(listOf("any"), tracks)?.id)
    }

    // resolveAudioTrack

    private fun stream(language: String?) = ClientAudioStream(codec = "aac", channels = 2, language = language)

    private fun audio(
        prefs: List<Pref>,
        streams: List<ClientAudioStream>,
        originalLanguage: String? = null,
        mediaType: String = "movie",
    ) = resolveAudioTrack(prefs, "show1", sourceScope, mediaType, originalLanguage, streams)

    @Test
    fun `this item's pinned index beats every wishlist`() {
        // Two English tracks - feature and commentary - is the case no
        // language can express.
        val prefs = listOf(Pref("source:copy1:3", "audio.track", "#1"), Pref("show1", "audio", "en"))
        assertEquals(1, audio(prefs, listOf(stream("en"), stream("en"))))
    }

    @Test
    fun `a pinned index the file doesn't have falls through`() {
        val prefs = listOf(Pref("source:copy1:3", "audio.track", "#9"), Pref("show1", "audio", "ja"))
        assertEquals(1, audio(prefs, listOf(stream("en"), stream("ja"))))
    }

    @Test
    fun `the series is heard in its remembered language, whatever the order`() {
        val prefs = listOf(Pref("show1", "audio", "ja"))
        assertEquals(2, audio(prefs, listOf(stream("en"), stream("de"), stream("ja"))))
        // Two letters are a match, so a three-letter code still is - as long
        // as it starts the same way. `ja` and `jpn` are the one pair that
        // don't, here exactly as in the web client this ports.
        assertEquals(1, audio(listOf(Pref("show1", "audio", "en")), listOf(stream("de"), stream("eng"))))
        assertEquals(0, audio(listOf(Pref("show1", "audio", "ja")), listOf(stream("jpn"))))
    }

    @Test
    fun `a series that remembers an index uses it`() {
        assertEquals(1, audio(listOf(Pref("show1", "audio", "#1")), listOf(stream(null), stream(null))))
    }

    @Test
    fun `the account's list is tried in order, then original as the backstop`() {
        val prefs = listOf(Pref("", "audio.movie", "de,nl"))
        assertEquals(1, audio(prefs, listOf(stream("en"), stream("nl"))))
        // Nothing on the list is in the file: 'original' is the implicit
        // final entry of every audio wishlist.
        assertEquals(1, audio(prefs, listOf(stream("en"), stream("ja")), originalLanguage = "ja"))
    }

    @Test
    fun `nothing remembered and nothing matching opens on the first track`() {
        assertEquals(0, audio(emptyList(), listOf(stream("en"), stream("nl"))))
        assertEquals(0, audio(emptyList(), emptyList()))
    }

    @Test
    fun `an audio pick remembers its language, or its index when it has none`() {
        assertEquals("ja", rememberedAudioValue(stream("JA"), 2))
        assertEquals("#2", rememberedAudioValue(stream(null), 2))
        assertEquals("#0", rememberedAudioValue(null, 0))
    }

    // needsMediaType

    @Test
    fun `the media type is only worth looking up for an account list`() {
        assertEquals(false, needsMediaType(emptyList()))
        assertEquals(false, needsMediaType(listOf(Pref("show1", "subs", "en"), Pref("source:copy1:3", "subs.track", "1"))))
        assertEquals(true, needsMediaType(listOf(Pref("", "subs.movie", "en"))))
        assertEquals(true, needsMediaType(listOf(Pref("", "audio.movie", "ja"))))
    }

    // what a pick writes

    @Test
    fun `a pick remembers its language, a track without one 'any', and none 'off'`() {
        assertEquals("nl", rememberedSubsValue(track(1, "NL")))
        assertEquals("any", rememberedSubsValue(track(1, null)))
        assertEquals("off", rememberedSubsValue(null))
        assertEquals("1", rememberedSubsTrackValue(track(1)))
        assertEquals("", rememberedSubsTrackValue(null))
    }
}
