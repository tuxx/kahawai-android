package com.kolktech.kahawai.data.repository

import com.kolktech.kahawai.data.network.ApiClient
import com.kolktech.kahawai.data.network.dto.CapabilityProfile
import com.kolktech.kahawai.data.network.dto.TargetDuration
import com.kolktech.kahawai.testutil.buildTestApiService
import io.mockk.every
import io.mockk.mockkObject
import io.mockk.unmockkObject
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import retrofit2.HttpException

class CatalogRepositoryTest {

    private lateinit var server: MockWebServer
    private lateinit var repository: CatalogRepository

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

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        repository = CatalogRepository({ buildTestApiService(server) })
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `libraries deserializes snake_case response and hits the right path`() = runTest {
        server.enqueue(
            MockResponse().setBody(
                """[{"id":"1","name":"Movies","media_type":"movies","collection_ids":["c1"]}]""",
            ),
        )

        val result = repository.libraries()

        assertEquals(1, result.size)
        assertEquals("Movies", result[0].name)
        assertEquals("movies", result[0].mediaType)
        assertEquals(listOf("c1"), result[0].collectionIds)
        assertEquals("/api/v1/catalogue/libraries", server.takeRequest().path)
    }

    @Test
    fun `items is library-scoped and maps the identity shape onto a row`() = runTest {
        server.enqueue(
            MockResponse().setBody(
                """
                {
                  "items":[{
                    "id":"i1","kind":"movie","media_type":"movies","title":"Arrival",
                    "representative_id":"c1:1","copy_ids":["c1:1","c2:9"],
                    "metadata":{"description":{"release_date":"2016-11-11"},"provenance":{}},
                    "played":false
                  }],
                  "total":1,"limit":20,"offset":0
                }
                """.trimIndent(),
            ),
        )

        val result = repository.items(library = "lib1", q = "arrival", sort = "title", limit = 20, offset = 0)

        assertEquals(1, result.total)
        val row = result.items[0]
        assertEquals("Arrival", row.title)
        // The library is navigation context the response never repeats.
        assertEquals("lib1", row.libraryId)
        // `sources` is how many copies back the identity.
        assertEquals(2, row.sources)
        assertEquals("2016-11-11", row.premiered)
        assertEquals(
            "/api/v1/catalogue/libraries/lib1/items?q=arrival&sort=title&limit=20&offset=0",
            server.takeRequest().path,
        )
    }

    @Test
    fun `item maps a child onto the flat row the UI reads`() = runTest {
        server.enqueue(
            MockResponse().setBody(
                """
                {
                  "id":"show1","kind":"series","media_type":"series","title":"Show",
                  "representative_id":"c1:1","copy_ids":["c1:1"],
                  "metadata":{"description":{},"provenance":{}},
                  "child":{
                    "id":"child1:ep1","parent_id":"show1","representative_id":"c1:5",
                    "title":"Pilot","position":{"kind":"episode","season":1,"episode":1},
                    "source_count":1,"metadata":{"description":{},"provenance":{}}
                  },
                  "parent_title":"Show","sources":[],"chapters":[],"copies":[],
                  "resume_position_ms":120000,"resume_duration_ms":2400000,"played":false
                }
                """.trimIndent(),
            ),
        )

        val result = repository.item("lib1", "child1:ep1")

        assertEquals("/api/v1/catalogue/libraries/lib1/items/child1:ep1", server.takeRequest().path)
        // A child reports as an episode even though the identity is a series.
        assertEquals("episode", result.kind)
        assertEquals("Pilot", result.title)
        assertEquals(1, result.season)
        assertEquals(1, result.episode)
        assertEquals("show1", result.parentId)
        assertEquals("Show", result.parentTitle)
        assertEquals("lib1", result.libraryId)
        assertEquals(120000L, result.resumePositionMs)
    }

    @Test
    fun `queryItem sends QUERY method with request body and parses negotiated`() = runTest {
        server.enqueue(
            MockResponse().setBody(
                """
                {
                  "id":"i1","kind":"movie","media_type":"movies","title":"Arrival",
                  "representative_id":"c1:1","copy_ids":["c1:1"],
                  "metadata":{"description":{},"provenance":{}},
                  "sources":[],"chapters":[],"copies":[],"segments":[],
                  "subtitle_source":{"media_entry_id":"e1","source_version":"v1"},
                  "negotiated":{
                    "mode":"direct","cost":"free","subtitles":[],
                    "streams":{"video":"direct","audio":"direct","subtitles":[]},
                    "target_duration_secs":6
                  }
                }
                """.trimIndent(),
            ),
        )

        val result = repository.queryItem("lib1", "i1", profile, audioTrack = 1, videoTrack = 0, subtitleTrack = 7L)

        val recorded = server.takeRequest()
        assertEquals("QUERY", recorded.method)
        assertEquals("/api/v1/catalogue/libraries/lib1/items/i1", recorded.path)
        val body = recorded.body.readUtf8()
        assertTrue(body.contains("\"audio_track\":1"))
        assertTrue(body.contains("\"subtitle_track\":7"))
        assertEquals("direct", result.negotiated?.mode)
        assertEquals(6, result.negotiated?.targetDurationSecs)
        // What a subtitle search has to be bound to.
        assertEquals("e1", result.subtitleSource?.mediaEntryId)
    }

    @Test
    fun `children folds the separate watch map back onto each child`() = runTest {
        server.enqueue(
            MockResponse().setBody(
                """
                {
                  "children":[
                    {"id":"child1:ep1","parent_id":"show1","representative_id":"c1:5","title":"Pilot",
                     "position":{"kind":"episode","season":1,"episode":1},"source_count":1,
                     "metadata":{"description":{},"provenance":{}}},
                    {"id":"child1:ep2","parent_id":"show1","representative_id":"c1:6","title":"Two",
                     "position":{"kind":"episode","season":1,"episode":2},"source_count":1,
                     "metadata":{"description":{},"provenance":{}}}
                  ],
                  "groups":[],"total":2,"limit":50,"offset":0,
                  "watch":{"child1:ep1":{"played":true,"resume_position_ms":0}}
                }
                """.trimIndent(),
            ),
        )

        val result = repository.children("lib1", "show1")

        assertEquals("/api/v1/catalogue/libraries/lib1/items/show1/children", server.takeRequest().path)
        assertEquals(listOf("child1:ep1", "child1:ep2"), result.map { it.id })
        assertTrue(result[0].played)
        // No watch row means unwatched, not missing.
        assertEquals(false, result[1].played)
        assertEquals(listOf(1, 2), result.map { it.episode })
    }

    @Test
    fun `continue watching rows carry the library and point at the episode`() = runTest {
        server.enqueue(
            MockResponse().setBody(
                """
                {
                  "items":[{
                    "id":"show1","kind":"series","media_type":"series","title":"Show",
                    "representative_id":"c1:1","copy_ids":["c1:1"],
                    "metadata":{"description":{},"provenance":{}},
                    "library_id":"lib1","parent_title":"Show",
                    "resume_position_ms":90000,"resume_duration_ms":2400000,"played":false,
                    "child":{
                      "id":"child1:ep3","parent_id":"show1","representative_id":"c1:7","title":"Three",
                      "position":{"kind":"episode","season":1,"episode":3},"source_count":1,
                      "metadata":{"description":{},"provenance":{}}
                    }
                  }],
                  "total":1,"limit":10,"offset":0
                }
                """.trimIndent(),
            ),
        )

        val result = repository.continueWatching(limit = 10)

        assertEquals("/api/v1/catalogue/continue-watching?limit=10", server.takeRequest().path)
        val row = result.items[0]
        // The row is the resumable episode, not the series it belongs to.
        assertEquals("child1:ep3", row.id)
        assertEquals("episode", row.kind)
        assertEquals("Three", row.title)
        assertEquals("Show", row.parentTitle)
        assertEquals("lib1", row.libraryId)
        assertEquals(90000L, row.resumePositionMs)
    }

    @Test
    fun `search fans out across libraries because the hub has no global item route`() = runTest {
        server.enqueue(
            MockResponse().setBody(
                """[{"id":"lib1","name":"Movies","media_type":"movies"},""" +
                    """{"id":"lib2","name":"Series","media_type":"series"}]""",
            ),
        )
        val page = { id: String, title: String ->
            """
            {"items":[{"id":"$id","kind":"movie","media_type":"movies","title":"$title",
              "representative_id":"c:1","copy_ids":["c:1"],
              "metadata":{"description":{},"provenance":{}},"played":false}],
             "total":1,"limit":20,"offset":0}
            """.trimIndent()
        }
        server.enqueue(MockResponse().setBody(page("i1", "Arrival")))
        server.enqueue(MockResponse().setBody(page("i2", "Arrival II")))

        val result = repository.search("arrival", limit = 20)

        assertEquals(2, result.items.size)
        assertEquals(2, result.total)
        // Each row still knows which library answered for it.
        assertEquals(setOf("lib1", "lib2"), result.items.map { it.libraryId }.toSet())
        val paths = List(3) { server.takeRequest().path }
        assertTrue(paths[0] == "/api/v1/catalogue/libraries")
        assertEquals(
            setOf(
                "/api/v1/catalogue/libraries/lib1/items?q=arrival&limit=20",
                "/api/v1/catalogue/libraries/lib2/items?q=arrival&limit=20",
            ),
            paths.drop(1).toSet(),
        )
    }

    @Test
    fun `search survives one library failing`() = runTest {
        server.enqueue(
            MockResponse().setBody(
                """[{"id":"lib1","name":"Movies","media_type":"movies"},""" +
                    """{"id":"lib2","name":"Series","media_type":"series"}]""",
            ),
        )
        server.enqueue(
            MockResponse().setBody(
                """
                {"items":[{"id":"i1","kind":"movie","media_type":"movies","title":"Arrival",
                  "representative_id":"c:1","copy_ids":["c:1"],
                  "metadata":{"description":{},"provenance":{}},"played":false}],
                 "total":1,"limit":20,"offset":0}
                """.trimIndent(),
            ),
        )
        server.enqueue(MockResponse().setResponseCode(500).setBody("boom"))

        val result = repository.search("arrival", limit = 20)

        assertEquals(1, result.items.size)
        assertEquals("i1", result.items[0].id)
    }

    @Test
    fun `setWatched targets the library-scoped route`() = runTest {
        server.enqueue(
            MockResponse().setBody(
                """{"updated":[{"item_id":"i1","position_ms":0,"played":true}]}""",
            ),
        )

        val update = repository.setWatched("lib1", "i1", played = true)

        val recorded = server.takeRequest()
        assertEquals("PUT", recorded.method)
        assertEquals("/api/v1/catalogue/libraries/lib1/items/i1/watched", recorded.path)
        assertTrue(update.played)
        assertEquals(0L, update.positionMs)
    }

    @Test
    fun `subtitle search and download are bound to the rendition`() = runTest {
        server.enqueue(
            MockResponse().setBody(
                """
                {"candidates":[{"provider":"opensubtitles","file_id":"f1","language":"en",
                  "release_name":"SDH","downloads":12,"hash_match":true}],
                 "quota":{"remaining":9,"total":10,"per_account":true}}
                """.trimIndent(),
            ),
        )
        server.enqueue(MockResponse().setBody("""{"track_id":77,"quota":{"remaining":8,"per_account":true}}"""))
        val source = com.kolktech.kahawai.data.network.dto.SubtitleSource("e1", "v1")

        val candidates = repository.searchSubtitles("lib1", "i1", source, listOf("en"))
        val trackId = repository.downloadSubtitle("lib1", "i1", source, "f1", "en")

        assertEquals("f1", candidates[0].fileId)
        assertTrue(candidates[0].hashMatch)
        assertEquals(77L, trackId)
        val searchRequest = server.takeRequest()
        assertEquals("/api/v1/catalogue/libraries/lib1/items/i1/subtitles/search", searchRequest.path)
        assertTrue(searchRequest.body.readUtf8().contains("\"media_entry_id\":\"e1\""))
        assertEquals("/api/v1/catalogue/libraries/lib1/items/i1/subtitles/download", server.takeRequest().path)
    }

    /// The end of a series is a JSON `null` body on a 200, not a 404.
    @Test
    fun `nextItem returns null at the end of a series`() = runTest {
        server.enqueue(MockResponse().setBody("null"))

        assertNull(repository.nextItem("lib1", "child1:last"))
        assertEquals("/api/v1/catalogue/libraries/lib1/items/child1:last/next", server.takeRequest().path)
    }

    @Test
    fun `nextItem maps the child the hub points at`() = runTest {
        server.enqueue(
            MockResponse().setBody(
                """
                {"id":"child1:ep4","parent_id":"show1","representative_id":"c1:8","title":"Four",
                 "position":{"kind":"episode","season":1,"episode":4},"source_count":1,
                 "metadata":{"description":{},"provenance":{}}}
                """.trimIndent(),
            ),
        )

        val next = repository.nextItem("lib1", "child1:ep3")

        assertEquals("child1:ep4", next?.id)
        assertEquals(4, next?.episode)
        assertEquals("lib1", next?.libraryId)
    }

    @Test
    fun `item propagates HttpException on error response`() {
        server.enqueue(MockResponse().setResponseCode(404).setBody("not found"))

        val exception = assertThrows(HttpException::class.java) {
            kotlinx.coroutines.runBlocking { repository.item("lib1", "missing") }
        }
        assertEquals(404, exception.code())
    }

    @Test
    fun `artworkUrl is library-scoped and takes no version`() {
        mockkObject(ApiClient)
        every { ApiClient.baseUrlOrNull() } returns "http://hub.local/"
        try {
            assertEquals(
                "http://hub.local/api/v1/catalogue/libraries/lib1/items/i1/artwork?size=thumb",
                repository.artworkUrl("lib1", "i1", size = "thumb"),
            )
            assertEquals(
                "http://hub.local/api/v1/catalogue/libraries/lib1/items/i1/artwork",
                repository.artworkUrl("lib1", "i1", size = null),
            )
        } finally {
            unmockkObject(ApiClient)
        }
    }

    @Test
    fun `artworkUrl yields null rather than throwing while the server is unconfigured`() {
        mockkObject(ApiClient)
        every { ApiClient.baseUrlOrNull() } returns null
        try {
            assertNull(repository.artworkUrl("lib1", "i1", size = "card"))
        } finally {
            unmockkObject(ApiClient)
        }
    }
}
