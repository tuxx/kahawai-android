package com.kolktech.kahawai.data.network

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import retrofit2.HttpException
import retrofit2.Response
import java.io.IOException

class ApiErrorsSessionGoneTest {

    private fun http(code: Int) = HttpException(
        Response.error<Unit>(code, "".toResponseBody("text/plain".toMediaType())),
    )

    /// The hub answers 404 for a session it has reaped, and for one whose
    /// teardown has already begun (begin_report refuses it). Retrying the
    /// same session can never succeed, which is what separates this from a
    /// blip worth swallowing.
    @Test
    fun `a gone session is recognised`() {
        assertTrue(http(404).isSessionGone())
        assertTrue(http(410).isSessionGone())
    }

    @Test
    fun `transient and unrelated failures are not a gone session`() {
        assertFalse(http(500).isSessionGone())
        assertFalse(http(503).isSessionGone())
        assertFalse(http(401).isSessionGone())
        assertFalse(IOException("network dropped").isSessionGone())
    }

    /// The two conditions must not be confused: one sends the viewer back
    /// to login, the other starts a new playback session.
    @Test
    fun `an auth failure is not a gone session`() {
        assertTrue(http(401).isAuthError())
        assertFalse(http(404).isAuthError())
    }
}
