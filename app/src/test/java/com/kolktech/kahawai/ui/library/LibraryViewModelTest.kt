package com.kolktech.kahawai.ui.library

import app.cash.turbine.test
import com.kolktech.kahawai.data.repository.CatalogRepository
import com.kolktech.kahawai.testutil.MainDispatcherRule
import com.kolktech.kahawai.testutil.buildTestApiService
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

class LibraryViewModelTest {

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

    @Test
    fun `load succeeds and requests the given library`() = runTest {
        server.enqueue(
            MockResponse().setBody(
                """{"items":[{"id":"i1","kind":"movie","media_type":"movies","title":"Arrival","representative_id":"c1:1","copy_ids":["c1:1"],"metadata":{"description":{},"provenance":{}},"played":false}],"total":1,"limit":40,"offset":0}""",
            ),
        )

        val viewModel = LibraryViewModel(repo(), libraryId = "lib1")

        viewModel.state.test {
            var item = awaitItem()
            if (item is LibraryState.Loading) item = awaitItem()
            val loaded = item as LibraryState.Loaded
            assertEquals(1, loaded.items.size)
        }
        assertTrue(server.takeRequest().path!!.startsWith("/api/v1/catalogue/libraries/lib1/items"))
    }

    @Test
    fun `load failure with 401 marks state as auth error`() = runTest {
        server.enqueue(MockResponse().setResponseCode(401))

        val viewModel = LibraryViewModel(repo(), libraryId = "lib1")

        viewModel.state.test {
            var item = awaitItem()
            if (item is LibraryState.Loading) item = awaitItem()
            val error = item as LibraryState.Error
            assertTrue(error.isAuthError)
        }
    }

    @Test
    fun `loadMore appends items and clears the loadingMore flag`() = runTest {
        server.enqueue(
            MockResponse().setBody(
                """
                {"items":[{"id":"i1","kind":"movie","media_type":"movies","title":"One","representative_id":"c1:1","copy_ids":["c1:1"],"metadata":{"description":{},"provenance":{}},"played":false},{"id":"i2","kind":"movie","media_type":"movies","title":"Two","representative_id":"c1:1","copy_ids":["c1:1"],"metadata":{"description":{},"provenance":{}},"played":false}],
                 "total":3,"limit":40,"offset":0}
                """.trimIndent(),
            ),
        )
        val viewModel = LibraryViewModel(repo(), libraryId = "lib1")

        viewModel.state.test {
            var item = awaitItem()
            if (item is LibraryState.Loading) item = awaitItem()
            val loaded = item as LibraryState.Loaded
            assertEquals(2, loaded.items.size)

            server.enqueue(
                MockResponse().setBody(
                    """{"items":[{"id":"i3","kind":"movie","media_type":"movies","title":"Three","representative_id":"c1:1","copy_ids":["c1:1"],"metadata":{"description":{},"provenance":{}},"played":false}],"total":3,"limit":40,"offset":2}""",
                ),
            )
            viewModel.loadMore()

            val loadingMore = awaitItem() as LibraryState.Loaded
            assertTrue(loadingMore.loadingMore)

            val appended = awaitItem() as LibraryState.Loaded
            assertEquals(3, appended.items.size)
            assertFalse(appended.loadingMore)
        }
    }

    @Test
    fun `loadMore is a no-op once every item is already loaded`() = runTest {
        server.enqueue(
            MockResponse().setBody(
                """{"items":[{"id":"i1","kind":"movie","media_type":"movies","title":"One","representative_id":"c1:1","copy_ids":["c1:1"],"metadata":{"description":{},"provenance":{}},"played":false}],"total":1,"limit":40,"offset":0}""",
            ),
        )
        val viewModel = LibraryViewModel(repo(), libraryId = "lib1")

        viewModel.state.test {
            var item = awaitItem()
            if (item is LibraryState.Loading) item = awaitItem()
            assertTrue(item is LibraryState.Loaded)

            viewModel.loadMore()

            expectNoEvents()
        }
        assertEquals(1, server.requestCount)
    }
}
