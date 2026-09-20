package com.kolktech.kahawai.data.network

import androidx.media3.common.PlaybackException
import androidx.media3.datasource.HttpDataSource
import retrofit2.HttpException

/// `HttpException.message` is just the status line ("HTTP 409
/// Conflict") — the hub puts the actual reason in the response body
/// (`format!("{e:#}")` throughout crates/kahawai-hub/src/api.rs, e.g.
/// "no source is currently available (mediahost offline)"), which is
/// what a user needs to see.
///
/// [PlaybackException] has the same problem one layer down: ExoPlayer's
/// top-level message for ANY failure opening/reading the media source —
/// a hub 4xx/5xx, a network drop, an unsupported container — is always
/// the literal string "Source error" (`ExoPlaybackException.createForSource`),
/// with the real reason buried in the cause chain. Walking it mirrors the
/// HttpException case above: an [HttpDataSource.InvalidResponseCodeException]
/// carries the hub's response body the same way Retrofit's errorBody does.
fun Throwable.readableMessage(): String {
    if (this is HttpException) {
        val body = response()?.errorBody()?.string()?.trim()
        if (!body.isNullOrEmpty()) return body
    }
    if (this is PlaybackException) {
        return rootPlaybackCause().readablePlaybackMessage()
    }
    return message ?: "Unknown error"
}

private fun Throwable.rootPlaybackCause(): Throwable {
    var deepest = this
    while (true) {
        deepest = deepest.cause ?: return deepest
    }
}

private fun Throwable.readablePlaybackMessage(): String {
    if (this is HttpDataSource.InvalidResponseCodeException) {
        val body = responseBody.toString(Charsets.UTF_8).trim()
        if (!body.isNullOrEmpty()) return body
        return "HTTP $responseCode" + (responseMessage?.let { " $it" } ?: "")
    }
    return message ?: "Unknown error"
}

/// A 401 that survived [com.kolktech.kahawai.data.network.TokenAuthenticator]'s
/// refresh attempt — the refresh token was itself missing/expired, so no
/// amount of retrying this request will help. The UI's only real recovery
/// is sending the user back to the login screen.
fun Throwable.isAuthError(): Boolean = this is HttpException && code() == 401

/// The hub no longer has the playback session this request names — it was
/// reaped, or is already tearing down (`session_gone`, and `begin_report`
/// refusing a session whose teardown has begun). Distinct from a transient
/// failure because retrying the SAME session can never succeed: nothing but
/// a new session will do.
fun Throwable.isSessionGone(): Boolean =
    this is HttpException && (code() == 404 || code() == 410)
