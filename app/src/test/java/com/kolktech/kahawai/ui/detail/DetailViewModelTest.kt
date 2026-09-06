package com.kolktech.kahawai.ui.detail

import app.cash.turbine.test
import com.kolktech.kahawai.data.network.dto.CapabilityProfile
import com.kolktech.kahawai.data.network.dto.SubtitleTrack
import com.kolktech.kahawai.data.network.dto.TargetDuration
import com.kolktech.kahawai.data.repository.CatalogRepository
import com.kolktech.kahawai.data.repository.PreferencesRepository
import com.kolktech.kahawai.playback.CapabilityProfileBuilder
import com.kolktech.kahawai.testutil.MainDispatcherRule
import com.kolktech.kahawai.testutil.buildTestApiService
import com.kolktech.kahawai.testutil.relaxedApplication
import io.mockk.coEvery
import io.mockk.mockkObject
import io.mockk.unmockkObject
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

class DetailViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private lateinit var server: MockWebServer
    private val application = relaxedApplication()

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
        mockkObject(CapabilityProfileBuilder)
        coEvery { CapabilityProfileBuilder.build(any()) } returns profile
    }

    @After
    fun tearDown() {
        unmockkObject(CapabilityProfileBuilder)
        server.shutdown()
    }

    private fun repo() = CatalogRepository({ buildTestApiService(server) })

    /// No preference server here: the remembered subtitle pick is
    /// best-effort by design (see DetailViewModel.load), and letting it fail
    /// keeps these tests — and the enqueued response order they depend on —
    /// about the item itself. TrackChoiceTest covers the resolution.
    private fun vm(itemId: String = "item1") = DetailViewModel(
        application,
        repo(),
        itemId,
        libraryId = null,
        prefsRepo = PreferencesRepository({ error("no preference server in this test") }),
    )

    @Test
    fun `load succeeds for a leaf item with no children`() = runTest {
        server.enqueue(MockResponse().setBody("""{"id":"item1","kind":"movie","title":"Arrival"}"""))

        val viewModel = vm()

        viewModel.state.test {
            var item = awaitItem()
            if (item is DetailState.Loading) item = awaitItem()
            val loaded = item as DetailState.Loaded
            assertEquals("Arrival", loaded.detail.title)
            assertTrue(loaded.children.isEmpty())
        }
        assertEquals(1, server.requestCount)
    }

    @Test
    fun `load succeeds for a show and fetches children`() = runTest {
        server.enqueue(MockResponse().setBody("""{"id":"show1","kind":"show","title":"A Show"}"""))
        server.enqueue(
            MockResponse().setBody(
                """{"children":[{"id":"ep1","kind":"episode","title":"Pilot"},{"id":"ep2","kind":"episode","title":"Two"}]}""",
            ),
        )

        val viewModel = vm("show1")

        viewModel.state.test {
            var item = awaitItem()
            if (item is DetailState.Loading) item = awaitItem()
            val loaded = item as DetailState.Loaded
            assertEquals(listOf("ep1", "ep2"), loaded.children.map { it.id })
        }
        assertEquals(2, server.requestCount)
    }

    @Test
    fun `load populates subtitleTracks from negotiated subtitles`() = runTest {
        server.enqueue(
            MockResponse().setBody(
                """
                {
                  "id":"item1","kind":"movie","title":"Arrival",
                  "negotiated":{"mode":"direct","cost":"free","subtitles":[
                    {"id":1,"item_id":"item1","origin":"embedded","format":"srt","delivery":"text"}
                  ]}
                }
                """.trimIndent(),
            ),
        )

        val viewModel = vm()

        viewModel.state.test {
            var item = awaitItem()
            if (item is DetailState.Loading) item = awaitItem()
            val loaded = item as DetailState.Loaded
            assertEquals(1, loaded.subtitleTracks.size)
            assertEquals("srt", loaded.subtitleTracks[0].format)
        }
    }

    @Test
    fun `load failure surfaces isAuthError on 401`() = runTest {
        server.enqueue(MockResponse().setResponseCode(401).setBody("unauthorized"))

        val viewModel = vm()

        viewModel.state.test {
            var item = awaitItem()
            if (item is DetailState.Loading) item = awaitItem()
            val error = item as DetailState.Error
            assertTrue(error.isAuthError)
        }
    }

    @Test
    fun `load failure is not an auth error on 500`() = runTest {
        server.enqueue(MockResponse().setResponseCode(500).setBody("boom"))

        val viewModel = vm()

        viewModel.state.test {
            var item = awaitItem()
            if (item is DetailState.Loading) item = awaitItem()
            val error = item as DetailState.Error
            assertFalse(error.isAuthError)
        }
    }

    @Test
    fun `refresh updates detail and children while preserving track selections`() = runTest {
        server.enqueue(MockResponse().setBody("""{"id":"show1","kind":"show","title":"A Show"}"""))
        server.enqueue(
            MockResponse().setBody(
                """{"children":[{"id":"ep1","kind":"episode","title":"Pilot"}]}""",
            ),
        )
        val viewModel = vm("show1")

        viewModel.state.test {
            var item = awaitItem()
            if (item is DetailState.Loading) item = awaitItem()
            val loaded = item as DetailState.Loaded

            viewModel.selectAudioTrackIndex(2)
            val afterAudioSelect = awaitItem() as DetailState.Loaded
            assertEquals(2, afterAudioSelect.selectedAudioTrackIndex)

            server.enqueue(MockResponse().setBody("""{"id":"show1","kind":"show","title":"A Show Renamed"}"""))
            server.enqueue(
                MockResponse().setBody(
                    """{"children":[{"id":"ep1","kind":"episode","title":"Pilot"},{"id":"ep2","kind":"episode","title":"Two"}]}""",
                ),
            )
            viewModel.refresh()

            val refreshed = awaitItem() as DetailState.Loaded
            assertEquals("A Show Renamed", refreshed.detail.title)
            assertEquals(2, refreshed.children.size)
            assertEquals(2, refreshed.selectedAudioTrackIndex)
        }
    }

    @Test
    fun `refresh is a no-op before the initial load reaches Loaded`() = runTest {
        server.enqueue(MockResponse().setBody("""{"id":"item1","kind":"movie","title":"Arrival"}"""))
        val viewModel = vm()

        assertTrue(viewModel.state.value is DetailState.Loading)
        viewModel.refresh()
        assertTrue(viewModel.state.value is DetailState.Loading)

        viewModel.state.test {
            var item = awaitItem()
            if (item is DetailState.Loading) item = awaitItem()
            assertTrue(item is DetailState.Loaded)
        }
        assertEquals(1, server.requestCount)
    }

    @Test
    fun `refresh failure leaves previously loaded content untouched`() = runTest {
        server.enqueue(MockResponse().setBody("""{"id":"item1","kind":"movie","title":"Arrival"}"""))
        val viewModel = vm()

        viewModel.state.test {
            var item = awaitItem()
            if (item is DetailState.Loading) item = awaitItem()
            val loaded = item as DetailState.Loaded

            server.enqueue(MockResponse().setResponseCode(500))
            viewModel.refresh()

            expectNoEvents()
            assertEquals(loaded, viewModel.state.value)
        }
    }

    @Test
    fun `selectSubtitleTrack updates state only when already Loaded`() = runTest {
        server.enqueue(MockResponse().setBody("""{"id":"item1","kind":"movie","title":"Arrival"}"""))
        val viewModel = vm()
        val track = SubtitleTrack(id = 1, itemId = "item1", origin = "embedded", format = "srt", delivery = "text")

        // Not yet Loaded: no-op, doesn't throw.
        viewModel.selectSubtitleTrack(track)
        assertTrue(viewModel.state.value is DetailState.Loading)

        viewModel.state.test {
            var item = awaitItem()
            if (item is DetailState.Loading) item = awaitItem()
            assertTrue(item is DetailState.Loaded)

            viewModel.selectSubtitleTrack(track)
            val next = awaitItem() as DetailState.Loaded
            assertEquals(track, next.selectedSubtitleTrack)
        }
    }

    @Test
    fun `toggleWatched marks the item and clears its resume position`() = runTest {
        server.enqueue(
            MockResponse().setBody(
                """{"id":"item1","kind":"movie","title":"Arrival","played":false,"resume_position_ms":120000}""",
            ),
        )
        val viewModel = vm()

        viewModel.state.test {
            var item = awaitItem()
            if (item is DetailState.Loading) item = awaitItem()
            val loaded = item as DetailState.Loaded
            assertFalse(loaded.detail.played)

            server.enqueue(
                MockResponse().setBody(
                    """{"updated":[{"item_id":"item1","position_ms":0,"played":true,"play_count":1}]}""",
                ),
            )
            viewModel.toggleWatched()

            val inFlight = awaitItem() as DetailState.Loaded
            assertTrue(inFlight.watchedActionInFlight)

            val done = awaitItem() as DetailState.Loaded
            assertFalse(done.watchedActionInFlight)
            assertTrue(done.detail.played)
            assertEquals(1, done.detail.playCount)
            assertEquals(0L, done.detail.resumePositionMs)
        }
    }

    @Test
    fun `toggleWatched failure clears in-flight and surfaces a transient error`() = runTest {
        server.enqueue(MockResponse().setBody("""{"id":"item1","kind":"movie","title":"Arrival"}"""))
        val viewModel = vm()

        viewModel.state.test {
            var item = awaitItem()
            if (item is DetailState.Loading) item = awaitItem()
            item as DetailState.Loaded

            server.enqueue(MockResponse().setResponseCode(500))
            viewModel.toggleWatched()

            val inFlight = awaitItem() as DetailState.Loaded
            assertTrue(inFlight.watchedActionInFlight)

            val failed = awaitItem() as DetailState.Loaded
            assertFalse(failed.watchedActionInFlight)
            assertFalse(failed.detail.played)
        }
        assertTrue(viewModel.transientError.value != null)
    }

    @Test
    fun `toggleWatched ignores a second call while one is in flight`() = runTest {
        server.enqueue(MockResponse().setBody("""{"id":"item1","kind":"movie","title":"Arrival"}"""))
        val viewModel = vm()

        viewModel.state.test {
            var item = awaitItem()
            if (item is DetailState.Loading) item = awaitItem()
            item as DetailState.Loaded

            server.enqueue(
                MockResponse().setBody(
                    """{"updated":[{"item_id":"item1","position_ms":0,"played":true,"play_count":1}]}""",
                ),
            )
            viewModel.toggleWatched()
            awaitItem() as DetailState.Loaded // in-flight

            viewModel.toggleWatched() // no-op: already in flight

            val done = awaitItem() as DetailState.Loaded
            assertFalse(done.watchedActionInFlight)
            assertTrue(done.detail.played)
        }
        assertEquals(2, server.requestCount)
    }

    @Test
    fun `selectAudioTrackIndex updates state only when already Loaded`() = runTest {
        server.enqueue(MockResponse().setBody("""{"id":"item1","kind":"movie","title":"Arrival"}"""))
        val viewModel = vm()

        viewModel.selectAudioTrackIndex(3)
        assertTrue(viewModel.state.value is DetailState.Loading)

        viewModel.state.test {
            var item = awaitItem()
            if (item is DetailState.Loading) item = awaitItem()
            assertTrue(item is DetailState.Loaded)

            viewModel.selectAudioTrackIndex(3)
            val next = awaitItem() as DetailState.Loaded
            assertEquals(3, next.selectedAudioTrackIndex)
        }
    }
}
