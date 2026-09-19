package com.kolktech.kahawai.data.repository

import com.kolktech.kahawai.data.network.dto.CapabilityProfile
import com.kolktech.kahawai.data.network.dto.TargetDuration
import com.kolktech.kahawai.testutil.buildTestApiService
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class PlaybackRepositoryTest {

    private lateinit var server: MockWebServer
    private lateinit var repository: PlaybackRepository

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
        repository = PlaybackRepository(buildTestApiService(server))
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `startSession posts request and deserializes mode`() = runTest {
        server.enqueue(
            MockResponse().setBody(
                """
                {"session_id":"s1","mode":"direct","content_type":"video/mp4","stream_url":"http://hub/s1",
                 "part_base_ms":0,"effective_start_ms":5000,"source_id":3,"source_fingerprint":"fp1",
                 "streams":{"video":"direct","audio":"direct","subtitles":[]}}
                """.trimIndent(),
            ),
        )

        val result = repository.startSession("item1", "lib1", profile, startMs = 5000, audioTrack = 1)

        val recorded = server.takeRequest()
        assertEquals("POST", recorded.method)
        assertEquals("/api/v1/playback/sessions", recorded.path)
        val body = recorded.body.readUtf8()
        assertTrue(body.contains("\"start_ms\":5000"))
        // The hub rejects a start with no library, and a non-zero start is
        // a resume.
        assertTrue(body.contains("\"library_id\":\"lib1\""))
        assertTrue(body.contains("\"resume\":true"))
        assertEquals("direct", result.mode)
        assertEquals("s1", result.sessionId)
        // What the next resume has to send back so the hub can tell
        // whether the position still refers to the same file.
        assertEquals("fp1", result.sourceFingerprint)
        assertEquals(5000L, result.effectiveStartMs)
    }

    @Test
    fun `seek posts to session path with position`() = runTest {
        server.enqueue(MockResponse().setBody("""{"part_base_ms":12000}"""))

        val result = repository.seek("s1", positionMs = 12000, subtitleTrack = 3L)

        val recorded = server.takeRequest()
        assertEquals("/api/v1/playback/sessions/s1/seek", recorded.path)
        assertTrue(recorded.body.readUtf8().contains("\"position_ms\":12000"))
        assertEquals(12000L, result.partBaseMs)
    }

    @Test
    fun `subtitles returns negotiated subtitle list`() = runTest {
        server.enqueue(
            MockResponse().setBody(
                """
                {
                  "id":"i1","kind":"movie","media_type":"movies","title":"Arrival",
                  "representative_id":"c1:1","copy_ids":["c1:1"],
                  "metadata":{"description":{},"provenance":{}},
                  "sources":[],"chapters":[],"copies":[],"segments":[],
                  "negotiated":{"mode":"direct","cost":"free","target_duration_secs":6,"subtitles":[
                    {"id":1,"item_id":"i1","origin":"embedded","format":"srt","delivery":"text",
                     "note":"","deletable":false,"machine":false}
                  ]}
                }
                """.trimIndent(),
            ),
        )

        val result = repository.subtitles("lib1", "i1", profile)

        assertEquals(1, result.size)
        assertEquals("srt", result[0].format)
    }

    @Test
    fun `subtitles returns empty list when negotiated is absent`() = runTest {
        server.enqueue(
            MockResponse().setBody(
                """
                {"id":"i1","kind":"movie","media_type":"movies","title":"Arrival",
                 "representative_id":"c1:1","copy_ids":["c1:1"],
                 "metadata":{"description":{},"provenance":{}},
                 "sources":[],"chapters":[],"copies":[],"segments":[]}
                """.trimIndent(),
            ),
        )

        val result = repository.subtitles("lib1", "i1", profile)

        assertEquals(emptyList<Any>(), result)
    }

    @Test
    fun `reportProgress posts position`() = runTest {
        server.enqueue(MockResponse().setResponseCode(200))

        repository.reportProgress("s1", 30000)

        val recorded = server.takeRequest()
        assertEquals("/api/v1/playback/sessions/s1/progress", recorded.path)
        assertTrue(recorded.body.readUtf8().contains("\"position_ms\":30000"))
    }

    @Test
    fun `endSession issues DELETE to session path`() = runTest {
        server.enqueue(MockResponse().setResponseCode(200))

        repository.endSession("s1")

        val recorded = server.takeRequest()
        assertEquals("DELETE", recorded.method)
        assertEquals("/api/v1/playback/sessions/s1", recorded.path)
    }
}
