package com.kolktech.kahawai.ui.search

import androidx.lifecycle.viewModelScope
import app.cash.turbine.test
import com.kolktech.kahawai.data.repository.CatalogRepository
import com.kolktech.kahawai.testutil.MainDispatcherRule
import com.kolktech.kahawai.testutil.buildTestApiService
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/// [SearchViewModel]'s `init{}` starts a `_query.debounce(300).collectLatest {}`
/// loop that runs for as long as `viewModelScope` lives — nothing here
/// ever plays the role of `onCleared()`, and [MainDispatcherRule]'s test
/// dispatcher shares its scheduler with `runTest`, so that pending
/// `delay(300)` is not inert: it really does fire once virtual time
/// crosses 300ms, which `awaitItem()`'s idle-driven auto-advance reaches
/// during ordinary test execution, not just at the end.
///
/// The tests below all trigger the FIRST search for a query through
/// [SearchViewModel.onQueryChange] itself (not [SearchViewModel.retry])
/// and wait for it to fully resolve before doing anything else. That
/// matters, not just style: `distinctUntilChanged` only lets the
/// debounced collector fire once per actual query value, so letting that
/// one firing happen and complete un-raced is what guarantees it goes
/// provably inert for the rest of the test. Triggering the first search
/// via `retry()` instead (as this file used to) leaves that firing
/// pending, and calling `retry()`/`refresh()` a moment later races it —
/// a real, observed flake (intermittent `ClassCastException` on an
/// `awaitItem()` that received an extra, unasked-for `Loading`, plus
/// cross-test "Dispatchers.Main was accessed... unset" crashes from the
/// resulting leaked, unconsumed request). It also matches how these are
/// actually called in the app: [SearchViewModel.retry] only ever follows
/// an error the debounced search already produced, never a change still
/// in flight.
///
/// [tearDown] cancelling `viewModelScope` is defense in depth on top of
/// that, not a substitute for it — without the fix above it, that
/// collector could still fire mid-test, before teardown ever runs.
class SearchViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private lateinit var server: MockWebServer
    private var viewModel: SearchViewModel? = null

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        viewModel?.viewModelScope?.cancel()
        server.shutdown()
    }

    private fun repo() = CatalogRepository({ buildTestApiService(server) })

    private fun newViewModel(): SearchViewModel = SearchViewModel(repo()).also { viewModel = it }

    /// Search has no cross-library route on the hub, so it lists the
    /// libraries and asks each one. That makes response order a race, so
    /// these route by path instead of enqueuing a FIFO queue.
    private fun itemsBody(title: String) =
        """{"items":[{"id":"i1","kind":"movie","media_type":"movies","title":"$title","representative_id":"c1:1","copy_ids":["c1:1"],"metadata":{"description":{},"provenance":{}},"played":false}],"total":1,"limit":60,"offset":0}"""

    private val oneLibrary =
        """[{"id":"lib1","name":"Movies","media_type":"movies"}]"""

    private fun searchDispatcher(vararg itemPages: String) {
        val pages = itemPages.toMutableList()
        server.dispatcher = object : okhttp3.mockwebserver.Dispatcher() {
            override fun dispatch(request: okhttp3.mockwebserver.RecordedRequest): MockResponse = when {
                request.path == "/api/v1/catalogue/libraries" -> MockResponse().setBody(oneLibrary)
                request.path?.startsWith("/api/v1/catalogue/libraries/lib1/items") == true ->
                    if (pages.isEmpty()) {
                        MockResponse().setResponseCode(500).setBody("boom")
                    } else {
                        MockResponse().setBody(pages.removeAt(0))
                    }
                else -> MockResponse().setResponseCode(404)
            }
        }
    }

    @Test
    fun `retry with a blank query resets to idle without a network call`() = runTest {
        val vm = newViewModel()

        vm.state.test {
            assertEquals(SearchState.Idle, awaitItem())
            vm.onQueryChange("")
            vm.retry()
            expectNoEvents()
        }
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `a query change searches and reports results`() = runTest {
        searchDispatcher(itemsBody("Arrival"))
        val vm = newViewModel()

        vm.state.test {
            assertEquals(SearchState.Idle, awaitItem())
            vm.onQueryChange("arrival")

            assertEquals(SearchState.Loading, awaitItem())
            val loaded = awaitItem() as SearchState.Loaded
            assertEquals("Arrival", loaded.items[0].title)
        }
    }

    @Test
    fun `a query change failing with 401 reports auth error`() = runTest {
        // An auth failure must survive the per-library fan-out rather than
        // being swallowed into an empty result set.
        server.dispatcher = object : okhttp3.mockwebserver.Dispatcher() {
            override fun dispatch(request: okhttp3.mockwebserver.RecordedRequest) =
                if (request.path == "/api/v1/catalogue/libraries") {
                    MockResponse().setBody(oneLibrary)
                } else {
                    MockResponse().setResponseCode(401)
                }
        }
        val vm = newViewModel()

        vm.state.test {
            assertEquals(SearchState.Idle, awaitItem())
            vm.onQueryChange("arrival")

            assertEquals(SearchState.Loading, awaitItem())
            val error = awaitItem() as SearchState.Error
            assertTrue(error.isAuthError)
        }
    }

    @Test
    fun `retry after a failure re-issues the same query`() = runTest {
        searchDispatcher()
        val vm = newViewModel()

        vm.state.test {
            assertEquals(SearchState.Idle, awaitItem())
            vm.onQueryChange("arrival")
            assertEquals(SearchState.Loading, awaitItem())
            awaitItem() as SearchState.Error

            searchDispatcher(itemsBody("Arrival"))
            vm.retry()

            assertEquals(SearchState.Loading, awaitItem())
            val loaded = awaitItem() as SearchState.Loaded
            assertEquals("Arrival", loaded.items[0].title)
        }
    }

    @Test
    fun `refresh re-runs the current query while results are already loaded`() = runTest {
        searchDispatcher(itemsBody("Arrival"))
        val vm = newViewModel()

        vm.state.test {
            assertEquals(SearchState.Idle, awaitItem())
            vm.onQueryChange("arrival")
            assertEquals(SearchState.Loading, awaitItem())
            awaitItem() as SearchState.Loaded

            searchDispatcher(itemsBody("Arrival 2"))
            vm.refresh()

            val refreshed = awaitItem() as SearchState.Loaded
            assertEquals("Arrival 2", refreshed.items[0].title)
        }
    }
}
