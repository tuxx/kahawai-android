package com.kolktech.kahawai.playback

import com.kolktech.kahawai.data.network.dto.CapabilityProfile
import com.kolktech.kahawai.data.network.dto.ClientAudioStream
import com.kolktech.kahawai.data.network.dto.ItemDetail
import com.kolktech.kahawai.data.network.dto.ItemSource
import com.kolktech.kahawai.data.network.dto.MediaStreams
import com.kolktech.kahawai.data.network.dto.Negotiated
import com.kolktech.kahawai.data.network.dto.NegotiatedSource
import com.kolktech.kahawai.data.network.dto.Pref
import com.kolktech.kahawai.data.network.dto.TargetDuration
import com.kolktech.kahawai.data.repository.CatalogRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PlaybackNegotiationTest {

    private val profile = CapabilityProfile(
        containers = listOf("mp4"),
        video = emptyList(),
        audio = listOf("aac"),
        maxAudioChannels = 2,
        hdr = false,
        assRender = false,
        graphicsOverlay = false,
        targetDuration = TargetDuration.Accurate,
    )

    private fun stream(language: String?) =
        ClientAudioStream(codec = "aac", channels = 2, language = language)

    private fun source(sourceId: Int, copyId: String, audio: List<ClientAudioStream>) = ItemSource(
        sourceId = sourceId,
        moduleId = "m",
        collectionId = "c",
        collectionItemId = copyId,
        mediaEntryId = "entry$sourceId",
        pathRel = "f$sourceId.mkv",
        streams = MediaStreams(audio = audio),
    )

    private fun item(vararg sources: ItemSource, negotiated: Int? = 1) = ItemDetail(
        id = "film1",
        kind = "movie",
        title = "Film",
        libraryId = "lib1",
        sources = sources.toList(),
        negotiated = negotiated?.let {
            Negotiated(
                source = NegotiatedSource(sourceId = it, moduleId = "m", collectionId = "c", pathRel = "f$it.mkv"),
                mode = "direct",
                cost = "free",
            )
        },
    )

    @Test
    fun `each source is ranked on its own streams and its own remembered pick`() {
        // Copy 1 has Japanese second; copy 2 has it first. One index cannot
        // describe both, which is the whole reason the map exists.
        val detail = item(
            source(1, "copyA", listOf(stream("en"), stream("ja"))),
            source(2, "copyB", listOf(stream("ja"), stream("en"))),
        )
        val prefs = listOf(Pref("film1", "audio", "ja"))

        val map = sourceAudioTracks(detail, prefs, mediaType = "movies")

        assertEquals(mapOf("1" to 1, "2" to 0), map)
    }

    @Test
    fun `an exact pick is read from that source's own scope`() {
        val detail = item(
            source(1, "copyA", listOf(stream("en"), stream("en"))),
            source(2, "copyB", listOf(stream("en"), stream("en"))),
        )
        // Only copyA pinned a commentary track; copyB must not inherit it.
        val prefs = listOf(Pref("source:copyA:1", "audio.track", "#1"))

        val map = sourceAudioTracks(detail, prefs, mediaType = "movies")

        assertEquals(mapOf("1" to 1, "2" to 0), map)
    }

    @Test
    fun `a preview that already prefers index zero needs no second query`() = runTest {
        val repo = mockk<CatalogRepository>()
        val detail = item(source(1, "copyA", listOf(stream("en"))))

        val choice = negotiatePlayback(
            repo = repo,
            libraryId = "lib1",
            itemId = "film1",
            profile = profile,
            prefs = emptyList(),
            mediaType = "movies",
            preview = detail,
        )

        assertEquals(0, choice.audioTrack)
        assertEquals(1, choice.sourceId)
        // Pinning is by media entry: the numeric id only groups parts
        // within one response.
        assertEquals("entry1", choice.mediaEntryId)
        coVerify(exactly = 0) { repo.queryItem(any(), any(), any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun `a non-zero preference re-queries with the per-source map`() = runTest {
        val repo = mockk<CatalogRepository>()
        val detail = item(source(1, "copyA", listOf(stream("en"), stream("ja"))))
        val sent = slot<Map<String, Int>>()
        coEvery {
            repo.queryItem(any(), any(), any(), any(), any(), any(), any(), capture(sent))
        } returns detail

        val choice = negotiatePlayback(
            repo = repo,
            libraryId = "lib1",
            itemId = "film1",
            profile = profile,
            prefs = listOf(Pref("film1", "audio", "ja")),
            mediaType = "movies",
            preview = detail,
        )

        // The hub is told this source would be played with index 1, so it
        // can rank it on that rather than on index 0.
        assertEquals(mapOf("1" to 1), sent.captured)
        assertEquals(1, choice.audioTrack)
    }

    @Test
    fun `ranking that keeps changing gives up rather than looping`() = runTest {
        val repo = mockk<CatalogRepository>()
        // A scan that flips the stream order on every answer never settles.
        // Starts flipped so the first answer already disagrees with the preview.
        var flip = true
        coEvery { repo.queryItem(any(), any(), any(), any(), any(), any(), any(), any()) } answers {
            flip = !flip
            if (flip) {
                item(source(1, "copyA", listOf(stream("en"), stream("ja"))))
            } else {
                item(source(1, "copyA", listOf(stream("ja"), stream("en"))))
            }
        }

        val choice = negotiatePlayback(
            repo = repo,
            libraryId = "lib1",
            itemId = "film1",
            profile = profile,
            prefs = listOf(Pref("film1", "audio", "ja")),
            mediaType = "movies",
            preview = item(source(1, "copyA", listOf(stream("en"), stream("ja")))),
        )

        // Bounded, and it still yields something playable rather than
        // turning a racing scan into an error on the Play button.
        coVerify(exactly = 3) { repo.queryItem(any(), any(), any(), any(), any(), any(), any(), any()) }
        assertEquals(1, choice.sourceId)
    }

    @Test
    fun `nothing negotiable yields no pin and track zero`() = runTest {
        val repo = mockk<CatalogRepository>()
        val detail = item(negotiated = null)

        val choice = negotiatePlayback(
            repo = repo,
            libraryId = "lib1",
            itemId = "film1",
            profile = profile,
            prefs = emptyList(),
            mediaType = "movies",
            preview = detail,
        )

        assertNull(choice.sourceId)
        assertNull(choice.mediaEntryId)
        assertEquals(0, choice.audioTrack)
    }
}
