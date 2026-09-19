package com.kolktech.kahawai.data.repository

import com.kolktech.kahawai.data.network.ApiClient
import com.kolktech.kahawai.testutil.buildTestAdminApiService
import com.kolktech.kahawai.testutil.buildTestApiService
import io.mockk.every
import io.mockk.mockkObject
import io.mockk.unmockkObject
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import retrofit2.HttpException

class AdminRepositoryTest {

    private lateinit var server: MockWebServer
    private lateinit var repository: AdminRepository

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        repository = AdminRepository()
        mockkObject(ApiClient)
        every { ApiClient.adminApiService() } returns buildTestAdminApiService(server)
        // Library listing is the client-facing catalogue route now, so this
        // repository reaches for both services.
        every { ApiClient.apiService() } returns buildTestApiService(server)
    }

    @After
    fun tearDown() {
        unmockkObject(ApiClient)
        server.shutdown()
    }

    @Test
    fun `pendingEnrollments unwraps envelope and hits GET path`() = runTest {
        server.enqueue(
            MockResponse().setBody(
                """{"pending":[{"csr_fingerprint":"fp1","module_type":"mediahost","module_id":"m1","name":"Living Room"}]}""",
            ),
        )

        val result = repository.pendingEnrollments()

        assertEquals(1, result.size)
        assertEquals("Living Room", result[0].name)
        val recorded = server.takeRequest()
        assertEquals("GET", recorded.method)
        assertEquals("/admin/v1/enrollments", recorded.path)
    }

    @Test
    fun `approve posts code and returns approved id`() = runTest {
        server.enqueue(MockResponse().setBody("""{"approved":"sat-1"}"""))

        val result = repository.approve("ABCD")

        val recorded = server.takeRequest()
        assertEquals("POST", recorded.method)
        assertEquals("/admin/v1/enrollments/approve", recorded.path)
        assertTrue(recorded.body.readUtf8().contains("\"code\":\"ABCD\""))
        assertEquals("sat-1", result)
    }

    @Test
    fun `satellites unwraps envelope and hits GET path`() = runTest {
        server.enqueue(
            MockResponse().setBody(
                """{"satellites":[{"module_id":"m1","module_type":"mediahost","name":"Living Room","cert_fingerprint":"cf1","connected":true,"disabled":false}]}""",
            ),
        )

        val result = repository.satellites()

        assertEquals(1, result.size)
        assertEquals("Living Room", result[0].name)
        assertEquals("/admin/v1/satellites", server.takeRequest().path)
    }

    @Test
    fun `deleteSatellite sends DELETE to the satellite path`() = runTest {
        server.enqueue(MockResponse().setResponseCode(200))

        repository.deleteSatellite("sat1")

        val recorded = server.takeRequest()
        assertEquals("DELETE", recorded.method)
        assertEquals("/admin/v1/satellites/sat1", recorded.path)
    }

    @Test
    fun `setSatelliteDisabled posts disabled flag`() = runTest {
        server.enqueue(MockResponse().setResponseCode(200))
        repository.setSatelliteDisabled("sat1", disabled = true)
        var recorded = server.takeRequest()
        assertEquals("/admin/v1/satellites/sat1/disabled", recorded.path)
        assertTrue(recorded.body.readUtf8().contains("\"disabled\":true"))

        server.enqueue(MockResponse().setResponseCode(200))
        repository.setSatelliteDisabled("sat1", disabled = false)
        recorded = server.takeRequest()
        assertTrue(recorded.body.readUtf8().contains("\"disabled\":false"))
    }

    /// The admin surface has no library listing of its own any more: the
    /// client-facing catalogue route carries `collection_ids`.
    @Test
    fun `libraries reads the client-facing catalogue route`() = runTest {
        server.enqueue(
            MockResponse().setBody(
                """[{"id":"lib1","name":"Movies","media_type":"movies","collection_ids":["c1"]}]""",
            ),
        )

        val result = repository.libraries()

        assertEquals(1, result.size)
        assertEquals("Movies", result[0].name)
        assertEquals(listOf("c1"), result[0].collectionIds)
        assertEquals("/api/v1/catalogue/libraries", server.takeRequest().path)
    }

    @Test
    fun `createLibrary posts name and mediaType and returns new id`() = runTest {
        server.enqueue(
            MockResponse().setBody("""{"id":"lib-9","name":"Movies","media_type":"movies","collection_ids":[]}"""),
        )

        val result = repository.createLibrary("Movies", "movies")

        val recorded = server.takeRequest()
        assertEquals("POST", recorded.method)
        assertEquals("/admin/v1/catalogue/libraries", recorded.path)
        val body = recorded.body.readUtf8()
        assertTrue(body.contains("\"name\":\"Movies\""))
        assertTrue(body.contains("\"media_type\":\"movies\""))
        assertEquals("lib-9", result)
    }

    @Test
    fun `deleteLibrary sends DELETE to the library path`() = runTest {
        server.enqueue(MockResponse().setResponseCode(200))

        repository.deleteLibrary("lib1")

        val recorded = server.takeRequest()
        assertEquals("DELETE", recorded.method)
        assertEquals("/admin/v1/catalogue/libraries/lib1", recorded.path)
    }

    /// Membership is SET, not patched — attach and detach are both this
    /// one call with a different list.
    @Test
    fun `setCollections puts the whole membership`() = runTest {
        server.enqueue(MockResponse().setResponseCode(200))

        repository.setCollections("lib1", listOf("c1", "c2"))

        val recorded = server.takeRequest()
        assertEquals("PUT", recorded.method)
        assertEquals("/admin/v1/catalogue/libraries/lib1/collections", recorded.path)
        assertTrue(recorded.body.readUtf8().contains("\"collection_ids\":[\"c1\",\"c2\"]"))
    }

    @Test
    fun `refreshLibrary posts and returns asked and offline counts`() = runTest {
        server.enqueue(MockResponse().setBody("""{"asked":5,"offline":2,"unsupported":1}"""))

        val result = repository.refreshLibrary("lib1")

        val recorded = server.takeRequest()
        assertEquals("POST", recorded.method)
        assertEquals("/admin/v1/catalogue/libraries/lib1/refresh", recorded.path)
        assertEquals(5, result.asked)
        assertEquals(2, result.offline)
        assertEquals(1, result.unsupported)
    }

    @Test
    fun `collections unwraps envelope and hits GET path`() = runTest {
        server.enqueue(
            MockResponse().setBody(
                """[{"id":"c1","media_type":"movies","mediahost_id":"m1","remote_id":"r1",""" +
                    """"connected":true,"scanning":false,"snapshot":false,"file_count":12,"version":3,""" +
                    """"epoch":"e1","roots":[]}]""",
            ),
        )

        val result = repository.collections()

        assertEquals(1, result.size)
        // A collection is one opaque id now, not a (module, collection) pair.
        assertEquals("c1", result[0].id)
        assertEquals("m1", result[0].mediahostId)
        assertEquals(12, result[0].fileCount)
        assertEquals("/admin/v1/catalogue/collections", server.takeRequest().path)
    }

    @Test
    fun `providers returns full provider config including chains map`() = runTest {
        server.enqueue(
            MockResponse().setBody(
                """
                {
                  "tmdb":{"configured":true},"fanart":{"configured":false},"theaudiodb":{"premium_key_configured":false},"tvdb":{"configured":false},"anidb":{"configured":true},
                  "chains":{"video":{"order":["tmdb","tvdb"],"default":["tmdb"]}}
                }
                """.trimIndent(),
            ),
        )

        val result = repository.providers()

        assertEquals("/admin/v1/providers", server.takeRequest().path)
        assertTrue(result.tmdb.configured)
        assertFalse(result.tvdb.configured)
        assertEquals(listOf("tmdb", "tvdb"), result.chains["video"]?.order)
    }

    @Test
    fun `setChain posts order for the given mediaType path segment`() = runTest {
        server.enqueue(MockResponse().setResponseCode(200))

        repository.setChain("video", listOf("tmdb", "tvdb"))

        val recorded = server.takeRequest()
        assertEquals("/admin/v1/providers/chains/video", recorded.path)
        assertTrue(recorded.body.readUtf8().contains("\"order\":[\"tmdb\",\"tvdb\"]"))
    }

    @Test
    fun `setTmdbKey posts api key`() = runTest {
        server.enqueue(MockResponse().setBody("""{"saved":true}"""))

        repository.setTmdbKey("key123")

        val recorded = server.takeRequest()
        assertEquals("/admin/v1/providers/tmdb", recorded.path)
        assertTrue(recorded.body.readUtf8().contains("\"api_key\":\"key123\""))
    }

    @Test
    fun `setTvdbKey posts api key and optional pin`() = runTest {
        server.enqueue(MockResponse().setBody("""{"saved":true}"""))
        repository.setTvdbKey("key1", "1234")
        var recorded = server.takeRequest()
        assertTrue(recorded.body.readUtf8().contains("\"pin\":\"1234\""))

        server.enqueue(MockResponse().setBody("""{"saved":true}"""))
        repository.setTvdbKey("key1", null)
        recorded = server.takeRequest()
        // A null pin equals SetTvdbRequest's default, so kotlinx.serialization
        // (encodeDefaults = false, unset here) omits the field entirely
        // rather than encoding "pin":null.
        assertFalse(recorded.body.readUtf8().contains("\"pin\""))
    }

    @Test
    fun `setAnidb returns verified true with no error on success`() = runTest {
        server.enqueue(MockResponse().setBody("""{"saved":true,"verified":true}"""))

        val result = repository.setAnidb("user", "pass", null)

        assertTrue(result.verified)
        assertNull(result.error)
    }

    @Test
    fun `setAnidb returns verified false with an error message on failure`() = runTest {
        server.enqueue(MockResponse().setBody("""{"saved":true,"verified":false,"error":"bad credentials"}"""))

        val result = repository.setAnidb("user", "pass", "udpkey")

        assertFalse(result.verified)
        assertEquals("bad credentials", result.error)
        assertTrue(server.takeRequest().body.readUtf8().contains("\"udp_api_key\":\"udpkey\""))
    }

    @Test
    fun `enrichStatus deserializes running counts`() = runTest {
        server.enqueue(MockResponse().setBody("""{"running":true,"matched":10,"weak":2,"missed":1}"""))

        val result = repository.enrichStatus()

        assertEquals("/admin/v1/enrich", server.takeRequest().path)
        assertTrue(result.running)
        assertEquals(10, result.matched)
    }

    @Test
    fun `enrichRun posts and returns started flag`() = runTest {
        server.enqueue(MockResponse().setBody("""{"started":true}"""))

        val result = repository.enrichRun()

        assertEquals("POST", server.takeRequest().method)
        assertTrue(result.started)
    }

    @Test
    fun `sessions unwraps envelope and hits GET path`() = runTest {
        server.enqueue(
            MockResponse().setBody(
                """{"sessions":[{"session_id":"s1","mode":"direct","module_id":"m1","idle_secs":30}]}""",
            ),
        )

        val result = repository.sessions()

        assertEquals(1, result.size)
        assertEquals("s1", result[0].sessionId)
        assertEquals("/admin/v1/sessions", server.takeRequest().path)
    }

    @Test
    fun `endSession sends DELETE to the session path`() = runTest {
        server.enqueue(MockResponse().setResponseCode(200))

        repository.endSession("s1")

        val recorded = server.takeRequest()
        assertEquals("DELETE", recorded.method)
        assertEquals("/admin/v1/sessions/s1", recorded.path)
    }

    @Test
    fun `a 404 from any call propagates as HttpException`() {
        server.enqueue(MockResponse().setResponseCode(404).setBody("not found"))

        val exception = assertThrows(HttpException::class.java) {
            runBlocking { repository.deleteSatellite("missing") }
        }
        assertEquals(404, exception.code())
    }
}
