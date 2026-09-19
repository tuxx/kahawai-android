package com.kolktech.kahawai.ui.admin

import app.cash.turbine.test
import com.kolktech.kahawai.R
import com.kolktech.kahawai.data.network.dto.CatalogueCollection
import com.kolktech.kahawai.data.network.dto.AdminSession
import com.kolktech.kahawai.data.network.dto.LibrarySummary
import com.kolktech.kahawai.data.network.dto.EnrichStatusResponse
import com.kolktech.kahawai.data.network.dto.PendingEnrollment
import com.kolktech.kahawai.data.network.dto.ProviderConfiguration
import com.kolktech.kahawai.data.network.dto.TheAudioDbConfiguration
import com.kolktech.kahawai.data.network.dto.ProvidersResponse
import com.kolktech.kahawai.data.network.dto.RefreshLibraryResponse
import com.kolktech.kahawai.data.network.dto.Satellite
import com.kolktech.kahawai.data.network.dto.SetAnidbResponse
import com.kolktech.kahawai.data.repository.AdminRepository
import com.kolktech.kahawai.testutil.MainDispatcherRule
import com.kolktech.kahawai.testutil.httpException
import com.kolktech.kahawai.testutil.relaxedApplication
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

class AdminViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val repo = mockk<AdminRepository>(relaxed = true)
    private val application = relaxedApplication()

    private val pending = listOf(PendingEnrollment(csrFingerprint = "fp1", moduleType = "mediahost", moduleId = "m1", name = "Living Room"))
    private val satellites = listOf(
        Satellite(moduleId = "m1", moduleType = "mediahost", name = "Living Room", certFingerprint = "cf1", connected = true, disabled = false),
    )
    private val sessions = listOf(AdminSession(sessionId = "s1", mode = "direct", moduleId = "m1", idleSecs = 30))
    private val libraries = listOf(
        LibrarySummary(id = "lib1", name = "Movies", mediaType = "movies", collectionIds = listOf("c1")),
    )
    private val collections = listOf(
        CatalogueCollection(id = "c1", mediaType = "movies", mediahostId = "m1", remoteId = "r1", connected = true),
    )
    private val providers = ProvidersResponse(
        tmdb = ProviderConfiguration(true),
        tvdb = ProviderConfiguration(false),
        anidb = ProviderConfiguration(true),
        fanart = ProviderConfiguration(false),
        theaudiodb = TheAudioDbConfiguration(false),
    )
    private val enrich = EnrichStatusResponse(running = false, matched = 1, weak = 0, missed = 0)

    @Before
    fun setUp() {
        coEvery { repo.pendingEnrollments() } returns pending
        coEvery { repo.satellites() } returns satellites
        coEvery { repo.sessions() } returns sessions
        coEvery { repo.libraries() } returns libraries
        coEvery { repo.collections() } returns collections
        coEvery { repo.providers() } returns providers
        coEvery { repo.enrichStatus() } returns enrich
    }

    private fun vm() = AdminViewModel(application, repo)

    @Test
    fun `reload populates state with data from all seven repo calls`() = runTest {
        val viewModel = vm()

        viewModel.state.test {
            var item = awaitItem()
            if (item is AdminState.Loading) item = awaitItem()
            val loaded = item as AdminState.Loaded
            assertEquals(pending, loaded.data.pending)
            assertEquals(satellites, loaded.data.satellites)
            assertEquals(sessions, loaded.data.sessions)
            assertEquals(libraries, loaded.data.libraries)
            assertEquals(collections, loaded.data.collections)
            assertEquals(providers, loaded.data.providers)
            assertEquals(enrich, loaded.data.enrich)
        }
    }

    @Test
    fun `reload failure surfaces isAuthError on 401 from any call`() = runTest {
        coEvery { repo.satellites() } throws httpException(401)
        val viewModel = vm()

        viewModel.state.test {
            var item = awaitItem()
            if (item is AdminState.Loading) item = awaitItem()
            val error = item as AdminState.Error
            assertTrue(error.isAuthError)
        }
    }

    @Test
    fun `approve success sets a notice with the approved id sentinel`() = runTest {
        coEvery { repo.approve("code1") } returns "sat-9"
        val viewModel = vm()

        viewModel.notice.test {
            assertNull(awaitItem())
            viewModel.approve("code1")
            assertEquals("res:${R.string.admin_notice_approved}:sat-9", awaitItem())
        }
        coVerify(exactly = 2) { repo.satellites() }
    }

    @Test
    fun `approve failure sets an error notice and does not clear state`() = runTest {
        coEvery { repo.approve(any()) } throws httpException(500, "boom")
        val viewModel = vm()

        viewModel.state.test {
            var item = awaitItem()
            if (item is AdminState.Loading) item = awaitItem()
            assertTrue(item is AdminState.Loaded)
        }
        viewModel.notice.test {
            assertNull(awaitItem())
            viewModel.approve("code1")
            assertEquals("res:${R.string.admin_notice_error}:boom", awaitItem())
        }
    }

    @Test
    fun `deleteSatellite success sets a deleted notice with the id`() = runTest {
        val viewModel = vm()

        viewModel.notice.test {
            assertNull(awaitItem())
            viewModel.deleteSatellite("sat1")
            assertEquals("res:${R.string.admin_notice_deleted}:sat1", awaitItem())
        }
        coVerify(exactly = 1) { repo.deleteSatellite("sat1") }
    }

    @Test
    fun `setSatelliteDisabled success reloads without setting a notice`() = runTest {
        val viewModel = vm()

        viewModel.state.test {
            var item = awaitItem()
            if (item is AdminState.Loading) item = awaitItem()
            assertTrue(item is AdminState.Loaded)
        }
        viewModel.setSatelliteDisabled("sat1", true)
        coVerify(exactly = 1) { repo.setSatelliteDisabled("sat1", true) }
        assertNull(viewModel.notice.value)
    }

    @Test
    fun `createLibrary and deleteLibrary reload without a notice on success`() = runTest {
        val viewModel = vm()

        viewModel.createLibrary("Movies", "video")
        coVerify(exactly = 1) { repo.createLibrary("Movies", "video") }
        assertNull(viewModel.notice.value)

        viewModel.deleteLibrary("lib1")
        coVerify(exactly = 1) { repo.deleteLibrary("lib1") }
        assertNull(viewModel.notice.value)
    }

    /// Attach and detach are the same call with a different list now:
    /// membership is set wholesale, not patched.
    @Test
    fun `setCollections reloads without a notice on success`() = runTest {
        val viewModel = vm()

        viewModel.setCollections("lib1", listOf("c1", "c2"))
        coVerify(exactly = 1) { repo.setCollections("lib1", listOf("c1", "c2")) }
        assertNull(viewModel.notice.value)

        viewModel.setCollections("lib1", emptyList())
        coVerify(exactly = 1) { repo.setCollections("lib1", emptyList()) }
        assertNull(viewModel.notice.value)
    }

    @Test
    fun `refreshLibrary notice includes the offline suffix only when offline count is nonzero`() = runTest {
        coEvery { repo.refreshLibrary("lib1") } returns RefreshLibraryResponse(asked = 5, offline = 0)
        val viewModel = vm()

        viewModel.notice.test {
            assertNull(awaitItem())
            viewModel.refreshLibrary("lib1")
            assertEquals("res:${R.string.admin_notice_refresh}:5", awaitItem())
        }

        coEvery { repo.refreshLibrary("lib1") } returns RefreshLibraryResponse(asked = 5, offline = 3)
        viewModel.notice.test {
            awaitItem() // stale notice from the previous action
            viewModel.refreshLibrary("lib1")
            assertEquals(
                "res:${R.string.admin_notice_refresh}:5res:${R.string.admin_notice_refresh_offline_suffix}:3",
                awaitItem(),
            )
        }
    }

    @Test
    fun `setTmdbKey and setTvdbKey set their respective saved notices`() = runTest {
        val viewModel = vm()

        viewModel.notice.test {
            assertNull(awaitItem())
            viewModel.setTmdbKey("key1")
            assertEquals("res:${R.string.admin_notice_tmdb_saved}", awaitItem())
        }
        viewModel.notice.test {
            awaitItem() // stale notice from the previous action
            viewModel.setTvdbKey("key2", null)
            assertEquals("res:${R.string.admin_notice_tvdb_saved}", awaitItem())
        }
    }

    @Test
    fun `setAnidb notice reflects verified true`() = runTest {
        coEvery { repo.setAnidb("u", "p", null) } returns SetAnidbResponse(saved = true, verified = true)
        val viewModel = vm()

        viewModel.notice.test {
            assertNull(awaitItem())
            viewModel.setAnidb("u", "p", null)
            assertEquals("res:${R.string.admin_notice_anidb_verified}", awaitItem())
        }
    }

    @Test
    fun `setAnidb notice reflects verified false with the provided error`() = runTest {
        coEvery { repo.setAnidb("u", "p", null) } returns SetAnidbResponse(saved = true, verified = false, error = "bad creds")
        val viewModel = vm()

        viewModel.notice.test {
            assertNull(awaitItem())
            viewModel.setAnidb("u", "p", null)
            assertEquals("res:${R.string.admin_notice_anidb_failed}:bad creds", awaitItem())
        }
    }

    @Test
    fun `setAnidb notice falls back to the unknown-error sentinel when error is null`() = runTest {
        coEvery { repo.setAnidb("u", "p", null) } returns SetAnidbResponse(saved = true, verified = false, error = null)
        val viewModel = vm()

        viewModel.notice.test {
            assertNull(awaitItem())
            viewModel.setAnidb("u", "p", null)
            assertEquals("res:${R.string.admin_notice_anidb_failed}:res:${R.string.unknown}", awaitItem())
        }
    }

    @Test
    fun `enrichRun and endSession reload without a notice on success`() = runTest {
        val viewModel = vm()

        viewModel.enrichRun()
        coVerify(exactly = 1) { repo.enrichRun() }
        assertNull(viewModel.notice.value)

        viewModel.endSession("s1")
        coVerify(exactly = 1) { repo.endSession("s1") }
        assertNull(viewModel.notice.value)
    }

    @Test
    fun `setChain notice includes the mediaType`() = runTest {
        val viewModel = vm()

        viewModel.notice.test {
            assertNull(awaitItem())
            viewModel.setChain("video", listOf("tmdb", "tvdb"))
            assertEquals("res:${R.string.admin_notice_chain_applied}:video", awaitItem())
        }
        coVerify(exactly = 1) { repo.setChain("video", listOf("tmdb", "tvdb")) }
    }

    @Test
    fun `clearNotice resets notice to null`() = runTest {
        val viewModel = vm()

        viewModel.notice.test {
            assertNull(awaitItem())
            viewModel.deleteSatellite("sat1")
            assertEquals("res:${R.string.admin_notice_deleted}:sat1", awaitItem())
            viewModel.clearNotice()
            assertNull(awaitItem())
        }
    }
}
