package com.kolktech.kahawai.ui.player.subtitle

import java.net.ServerSocket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CancellableResponseTest {
    @Test
    fun `cancellation closes an idle growing body without waiting for another cue`() = runBlocking {
        ServerSocket(0).use { server ->
            val receivedCue = CountDownLatch(1)
            val releaseServer = CountDownLatch(1)
            val serving = thread(isDaemon = true) {
                server.accept().use { socket ->
                    val input = socket.getInputStream().bufferedReader()
                    while (!input.readLine().isNullOrEmpty()) { }
                    socket.getOutputStream().apply {
                        write("HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\n\r\n6\r\nfirst\n\r\n".toByteArray())
                        flush()
                    }
                    releaseServer.await(5, TimeUnit.SECONDS)
                }
            }
            val call = OkHttpClient.Builder().readTimeout(0, TimeUnit.SECONDS).build()
                .newCall(Request.Builder().url("http://localhost:${server.localPort}").build())
            val reader = async(Dispatchers.IO) {
                call.useCancellableResponse { response ->
                    val source = response.body.source()
                    assertEquals("first", source.readUtf8Line())
                    receivedCue.countDown()
                    source.readUtf8Line()
                }
            }
            try {
                assertTrue(receivedCue.await(3, TimeUnit.SECONDS))
                withTimeout(2_000) { reader.cancelAndJoin() }
                assertTrue(call.isCanceled())
            } finally {
                call.cancel()
                releaseServer.countDown()
                serving.join(3_000)
            }
        }
    }

    @Test
    fun `a normal response finishes without leaving a cancellation watcher`() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("complete"))
            val call = OkHttpClient().newCall(Request.Builder().url(server.url("/")).build())
            assertEquals("complete", withTimeout(2_000) {
                call.useCancellableResponse { it.body.string() }
            })
        }
    }
}
