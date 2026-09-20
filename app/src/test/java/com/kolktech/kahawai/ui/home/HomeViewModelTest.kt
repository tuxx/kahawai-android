package com.kolktech.kahawai.ui.home

import app.cash.turbine.test
import com.kolktech.kahawai.data.repository.CatalogRepository
import com.kolktech.kahawai.testutil.MainDispatcherRule
import com.kolktech.kahawai.testutil.buildTestApiService
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

class HomeViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun repo() = CatalogRepository({ buildTestApiService(server) })

    /// [HomeViewModel.fetchHome] fires the continue-watching and libraries
    /// requests concurrently (real sockets, real threads — this is an
    /// integration-style test), so which one a plain FIFO
    /// [okhttp3.mockwebserver.QueueDispatcher] hands the next enqueued
    /// response to is a genuine race, not a fixed order. Routing by path
    /// instead makes each response deterministic regardless of arrival
    /// order.
    private fun routeBy(vararg routes: Pair<(RecordedRequest) -> Boolean, MockResponse>) =
        object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse =
                routes.firstOrNull { (matches, _) -> matches(request) }?.second
                    ?: MockResponse().setResponseCode(404)
        }

    private val noneInProgress = MockResponse().setBody("""{"items":[],"total":0,"limit":12,"offset":0}""")
    private val oneLibrary =
        MockResponse().setBody("""[{"id":"lib1","name":"Movies","media_type":"movies"}]""")

    private fun libraryItems(title: String) = MockResponse().setBody(
        """{"items":[{"id":"i1","kind":"movie","media_type":"movies","title":"$title","representative_id":"c1:1","copy_ids":["c1:1"],"metadata":{"description":{},"provenance":{}},"played":false}],"total":1,"limit":20,"offset":0}""",
    )

    private val emptyUpNext = MockResponse().setBody("""{"items":[],"total":0,"limit":12,"offset":0}""")

    private fun homeDispatcher(libraryResponse: MockResponse) = routeBy(
        { r: RecordedRequest -> r.path?.startsWith("/api/v1/catalogue/continue-watching") == true } to noneInProgress,
        { r: RecordedRequest -> r.path?.startsWith("/api/v1/catalogue/up-next") == true } to emptyUpNext,
        { r: RecordedRequest -> r.path == "/api/v1/catalogue/libraries" } to oneLibrary,
        { r: RecordedRequest -> r.path?.startsWith("/api/v1/catalogue/libraries/lib1/items") == true } to libraryResponse,
    )

    @Test
    fun `load succeeds and produces one row per non-empty library`() = runTest {
        server.dispatcher = homeDispatcher(libraryItems("Arrival"))

        val viewModel = HomeViewModel(repo())

        viewModel.state.test {
            var item = awaitItem()
            if (item is HomeState.Loading) item = awaitItem()
            val loaded = item as HomeState.Loaded
            assertEquals(1, loaded.rows.size)
            assertEquals("Arrival", loaded.rows[0].items[0].title)
        }
    }

    @Test
    fun `load surfaces up-next episodes alongside library rows`() = runTest {
        val upNext = MockResponse().setBody(
            """
            {"items":[{
              "id":"show1","kind":"series","media_type":"series","title":"Show",
              "representative_id":"c1:1","copy_ids":["c1:1"],
              "metadata":{"description":{},"provenance":{}},"played":false,
              "library_id":"lib1","parent_title":"Show",
              "child":{"id":"child1:e2","parent_id":"show1","representative_id":"c1:2","title":"Ep 2",
                "position":{"kind":"episode","season":1,"episode":2},"source_count":1,
                "metadata":{"description":{},"provenance":{}}}
            }],"total":1,"limit":12,"offset":0}
            """.trimIndent(),
        )
        server.dispatcher = routeBy(
            { r: RecordedRequest -> r.path?.startsWith("/api/v1/catalogue/continue-watching") == true } to noneInProgress,
            { r: RecordedRequest -> r.path?.startsWith("/api/v1/catalogue/up-next") == true } to upNext,
            { r: RecordedRequest -> r.path == "/api/v1/catalogue/libraries" } to oneLibrary,
            { r: RecordedRequest -> r.path?.startsWith("/api/v1/catalogue/libraries/lib1/items") == true }
                to libraryItems("Arrival"),
        )

        val viewModel = HomeViewModel(repo())

        viewModel.state.test {
            var item = awaitItem()
            if (item is HomeState.Loading) item = awaitItem()
            val loaded = item as HomeState.Loaded
            assertEquals(1, loaded.upNext.size)
            assertEquals("Ep 2", loaded.upNext[0].title)
        }
    }

    @Test
    fun `load failure with 401 marks state as auth error`() = runTest {
        server.dispatcher = routeBy({ _: RecordedRequest -> true } to MockResponse().setResponseCode(401).setBody("unauthorized"))

        val viewModel = HomeViewModel(repo())

        viewModel.state.test {
            var item = awaitItem()
            if (item is HomeState.Loading) item = awaitItem()
            val error = item as HomeState.Error
            assertTrue(error.isAuthError)
        }
    }

    @Test
    fun `load failure with a generic error is not an auth error`() = runTest {
        server.dispatcher = routeBy({ _: RecordedRequest -> true } to MockResponse().setResponseCode(500).setBody("boom"))

        val viewModel = HomeViewModel(repo())

        viewModel.state.test {
            var item = awaitItem()
            if (item is HomeState.Loading) item = awaitItem()
            val error = item as HomeState.Error
            assertFalse(error.isAuthError)
        }
    }

    @Test
    fun `refresh re-fetches rows while already loaded`() = runTest {
        server.dispatcher = homeDispatcher(libraryItems("Arrival"))
        val viewModel = HomeViewModel(repo())

        viewModel.state.test {
            var item = awaitItem()
            if (item is HomeState.Loading) item = awaitItem()
            assertTrue(item is HomeState.Loaded)

            server.dispatcher = homeDispatcher(libraryItems("Arrival 2"))
            viewModel.refresh(showIndicator = true)

            val refreshing = awaitItem() as HomeState.Loaded
            assertTrue(refreshing.isRefreshing)

            val refreshed = awaitItem() as HomeState.Loaded
            assertEquals("Arrival 2", refreshed.rows[0].items[0].title)
        }
    }

    @Test
    fun `refresh failure leaves previously loaded rows untouched`() = runTest {
        server.dispatcher = homeDispatcher(libraryItems("Arrival"))
        val viewModel = HomeViewModel(repo())

        viewModel.state.test {
            var item = awaitItem()
            if (item is HomeState.Loading) item = awaitItem()
            val loaded = item as HomeState.Loaded

            server.dispatcher = routeBy({ _: RecordedRequest -> true } to MockResponse().setResponseCode(500))
            viewModel.refresh(showIndicator = true)

            val refreshing = awaitItem() as HomeState.Loaded
            assertTrue(refreshing.isRefreshing)

            val settled = awaitItem() as HomeState.Loaded
            assertEquals(loaded, settled)
            expectNoEvents()
        }
    }
}
