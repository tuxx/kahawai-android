package com.kolktech.kahawai.ui.player.subtitle

import android.graphics.Paint
import android.graphics.RectF
import android.util.Log
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize
import androidx.media3.common.Player
import com.kolktech.kahawai.data.network.ApiClient
import com.kolktech.kahawai.data.network.dto.SubtitleTrack
import com.kolktech.kahawai.data.repository.PlaybackRepository
import com.kolktech.kahawai.ui.player.SubtitleSession
import io.github.peerless2012.ass.Ass
import io.github.peerless2012.ass.AssFrame
import io.github.peerless2012.ass.AssTexType
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Request
import java.util.concurrent.TimeUnit
import kotlin.coroutines.coroutineContext

private const val TAG = "AssSubtitleOverlay"

/// Faithful ASS/SSA rendering (libass via io.github.peerless2012:ass-kt),
/// mirroring the web client's JASSUB integration
/// (web/src/views/Player.tsx:470-562). Driven directly against the
/// ass-kt primitives (Ass/AssTrack/AssRender) rather than through
/// ass-media's AssHandler/AssRenderersFactory: that module auto-detects
/// ASS tracks off ExoPlayer's own Tracks/Format events, which only fire
/// for tracks muxed into the played container. The hub's ASS tracks are
/// the opposite — an out-of-band streamed `.ass` tap keyed to a track id,
/// never muxed into the HLS stream — so there's no such event to hook;
/// header/dialogue are read straight off our own HTTP stream instead,
/// same as JASSUB is fed on the web.
/// Whether a [renderFrame] answer replaces what is on screen.
///
/// The two "nothing to draw" answers are not the same thing. `changed == 0`
/// is libass's detect_change saying THIS frame is identical to the last one,
/// so the previous images stay up — that is the cheap no-op the render loop
/// runs at display rate. A null frame is the opposite: there is nothing to
/// show at this timestamp at all, and the previous images must come DOWN.
///
/// Keeping the last frame on null is what made a cue linger past its end
/// timestamp until the next one happened to replace it. The library's own
/// view does exactly this (AssSubtitleCanvasView assigns a null frame
/// straight through and only skips on `changed == 0`); this pipeline draws
/// into Compose instead of that view, so it has to make the same call
/// itself.
internal fun assFrameShouldApply(result: AssFrame?): Boolean = result == null || result.changed != 0

@Composable
fun AssSubtitleOverlay(
    player: Player,
    itemId: String,
    repo: PlaybackRepository,
    track: SubtitleTrack,
    subtitleSession: SubtitleSession,
    resizeMode: Int,
    /// How much of the bottom of the screen is spoken for right now — the
    /// controls, while they're up. Cues that would land under it are lifted
    /// clear, the same way they're lifted out of a Zoom(crop) overflow.
    bottomInsetPx: Float = 0f,
    modifier: Modifier = Modifier,
) {
    var containerSize by remember { mutableStateOf(IntSize.Zero) }
    var frame by remember(subtitleSession.epoch, track.id) { mutableStateOf<AssFrame?>(null) }
    var frameSize by remember(subtitleSession.epoch, track.id) { mutableStateOf(IntSize.Zero) }
    // resizeMode is a plain parameter, not MutableState — a coroutine
    // that captures it directly only ever sees the value from when it
    // launched. The render loop below lives inside a LaunchedEffect keyed
    // ONLY on (epoch, track.id) (see the ass/assRender lifecycle note
    // ahead), so it needs rememberUpdatedState to keep seeing live
    // resize-mode toggles without restarting the loop. Timing needs no
    // such plumbing: the render loop reads the absolute clock off
    // [player] directly (see the renderFrame call), which also picks up
    // PlayerViewModel.syncOrigin's live offset corrections for free.
    val currentResizeMode by rememberUpdatedState(resizeMode)

    // ass/assRender's entire native lifecycle — create, feed fonts/track,
    // render, release — now lives inside ONE coroutine, rather than
    // remember{} + a separate DisposableEffect(onDispose { ...release() }).
    // The old split let release() run on the main thread (as soon as a
    // subtitle switch changed the remember key) while a renderFrame()
    // call issued moments earlier could still be executing on a
    // Dispatchers.Default thread — freeing the native handle out from
    // under an in-flight native call is a use-after-free, which crashes
    // the whole process (SIGSEGV/native abort) with nothing catchable as
    // a Kotlin exception — matching reports of the app dying with no
    // AndroidRuntime entry in logcat after several subtitle switches
    // (every switch/seek-restart tears down and recreates ass/assRender,
    // so repeating it is repeated rolls of the dice on hitting that
    // window). coroutineScope{} below guarantees BOTH children (font/tap
    // loading and the render loop) have fully finished — including
    // letting an in-flight native call return, not just cancelling it —
    // before the `finally` frees anything.
    LaunchedEffect(subtitleSession.epoch, track.id) {
        val ass = Ass()
        val assRender = ass.createRender()
        try {
            coroutineScope {
                launch {
                    try {
                        val fonts = repo.fonts(subtitleSession.sessionId).fonts
                        // Fetched concurrently, not one at a time: a
                        // styled ASS track (karaoke/anime content
                        // especially) can embed a dozen+ fonts, and
                        // createTrack()/the .ass tap connection below
                        // both wait on this finishing.
                        val loaded = fonts.mapIndexed { index, name ->
                            async(Dispatchers.IO) {
                                name to fetchBytes(
                                    "${ApiClient.baseUrl().trimEnd('/')}/api/v1/playback/sessions/" +
                                        "${subtitleSession.sessionId}/fonts/$index",
                                )
                            }
                        }.awaitAll()
                        loaded.forEach { (name, bytes) ->
                            if (bytes != null) ass.addFont(name, bytes) else Log.w(TAG, "font $name failed to load")
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        Log.w(TAG, "font list failed for item=$itemId", e)
                    }

                    // Cancellation is cooperative: if the font list came
                    // back empty, the block above returns without ever
                    // suspending again after repo.fonts() resolves, so a
                    // cancel() requested in that exact window has no
                    // suspension point left to surface it as an
                    // exception. ensureActive() forces the check
                    // explicitly before touching the native track.
                    ensureActive()
                    val assTrack = ass.createTrack()
                    assRender.setTrack(assTrack)

                    withContext(Dispatchers.IO) {
                        val client = ApiClient.authenticatedOkHttpClient().newBuilder()
                            // Long-lived, growing stream (header, then
                            // Dialogue lines as the demux pass reaches
                            // them) — must not time out while idle.
                            .readTimeout(0, TimeUnit.SECONDS)
                            .build()

                        // Connects to [url] and feeds assTrack; returns
                        // whether a full header ([Script Info].../
                        // Events "Format:" line) was ever seen. Retried
                        // up to [maxAttempts] times, 700ms apart, but
                        // only while nothing has been fed yet: a
                        // reconnect re-serves the stream from its start,
                        // so retrying past that point would feed
                        // duplicate events.
                        suspend fun feedFrom(url: String, maxAttempts: Int): Boolean {
                            var attempt = 0
                            var fedAnything = false
                            while (isActive) {
                                val call = client.newCall(Request.Builder().url(url).build())
                                // execute()/readUtf8Line() are blocking calls
                                // that don't check coroutine cancellation
                                // between reads — only Call.cancel() actually
                                // interrupts a stalled read, by closing the
                                // socket. Without this hook, a cancelled
                                // coroutine leaves this readTimeout(0) connection
                                // open until the hub happens to send another
                                // line or closes it itself.
                                val cancelHandle = coroutineContext.job.invokeOnCompletion { call.cancel() }
                                try {
                                    call.execute().use { response ->
                                        if (!response.isSuccessful) {
                                            Log.w(TAG, ".ass tap http ${response.code} url=$url track=${track.id} attempt=$attempt")
                                            return@use
                                        }
                                        val source = response.body.source()
                                        val header = StringBuilder()
                                        var sawEvents = false
                                        var headerDone = false
                                        // Accumulate from the very start
                                        // ([Script Info], [V4+ Styles], ...)
                                        // through the Events "Format:" line —
                                        // libass needs all of it, same span
                                        // JASSUB's subContent covers on the web.
                                        while (isActive && !headerDone) {
                                            val line = source.readUtf8Line() ?: break
                                            header.append(line).append('\n')
                                            if (!sawEvents && line.trim().equals("[Events]", ignoreCase = true)) sawEvents = true
                                            if (sawEvents && line.trim().startsWith("Format:", ignoreCase = true)) headerDone = true
                                        }
                                        if (!headerDone) return@use
                                        fedAnything = true
                                        assTrack.readBuffer(header.toString().toByteArray())
                                        // Each subsequent Dialogue line is
                                        // re-fed with the section marker so
                                        // libass's line-oriented parser stays in
                                        // [Events] context — mirrors JASSUB's
                                        // `processData('[Events]\n' + lines)`,
                                        // just per-line instead of batched
                                        // (dialogue lines never embed a newline,
                                        // so this is equally correct).
                                        while (isActive) {
                                            val line = source.readUtf8Line() ?: break
                                            if (line.isBlank()) continue
                                            assTrack.readBuffer("[Events]\n$line\n".toByteArray())
                                        }
                                    }
                                } catch (e: CancellationException) {
                                    throw e
                                } catch (e: Exception) {
                                    Log.w(TAG, ".ass tap failed url=$url track=${track.id} attempt=$attempt", e)
                                } finally {
                                    cancelHandle.dispose()
                                }
                                if (fedAnything || ++attempt >= maxAttempts) break
                                delay(700)
                            }
                            return fedAnything
                        }

                        // The live session tap only exists for
                        // Remux/Transcode sessions with an embedded
                        // track: the hub's session_file handler no-ops
                        // it for Mode::Direct (raw byte-range file, no
                        // server-side pipeline to tap from) and can only
                        // translate the public track id to an internal
                        // pipeline key for origin=="embedded" tracks.
                        // Mirrors web/src/views/Player.tsx's `isHls &&
                        // selected.origin === 'embedded'` gate — falling
                        // straight through to the whole-file endpoint
                        // otherwise, same as the web client's fallback.
                        val fedFromSession = subtitleSession.isHls &&
                            track.origin == "embedded" &&
                            feedFrom(url = "${subtitleSession.streamBaseUrl}subs-${track.id}.ass", maxAttempts = 3)
                        if (!fedFromSession) {
                            // Whole-file extraction, streamed as it's
                            // produced — no live pipeline to race, so a
                            // single attempt is enough (same as the web
                            // client's fallback `feed()` call).
                            feedFrom(
                                url = "${ApiClient.baseUrl().trimEnd('/')}/api/v1/playback/sessions/" +
                                    "${subtitleSession.sessionId}/subtitles/${track.id}.ass",
                                maxAttempts = 1,
                            )
                        }
                    }
                }

                // Drives redraws off the player clock. renderFrame is a
                // synchronized native call over potentially-uncached
                // glyphs — run it off the main thread, mirroring the
                // library's own AssExecutor.
                //
                // Paced by the display frame clock, not a coarse poll:
                // JASSUB on the web renders every video frame, and ASS
                // timing needs that — a delay(200) loop shows every
                // event up to 200ms late (visible lag against the
                // audio) and plays \t/\move/karaoke at 5fps. libass
                // caches rasterized glyphs, so a frame where nothing
                // changed is a cheap no-op (changed == 0); when a render
                // does run long, this sequential loop just skips
                // display frames rather than queueing.
                launch {
                    while (isActive) {
                        withFrameMillis { }
                        val videoSize = player.videoSize
                        if (videoSize.width > 0 && videoSize.height > 0 && containerSize.width > 0 && containerSize.height > 0) {
                            val rect = contentRectFor(
                                containerW = containerSize.width.toFloat(),
                                containerH = containerSize.height.toFloat(),
                                videoW = videoSize.width.toFloat(),
                                videoH = videoSize.height.toFloat(),
                                resizeMode = currentResizeMode,
                            )
                            val w = rect.width.toInt().coerceAtLeast(2)
                            val h = rect.height.toInt().coerceAtLeast(2)
                            if (frameSize.width != w || frameSize.height != h) {
                                assRender.setStorageSize(videoSize.width, videoSize.height)
                                assRender.setFrameSize(w, h)
                                frameSize = IntSize(w, h)
                            }
                            // Absolute video time in every mode: [player]
                            // is the ViewModel's ForwardingPlayer, whose
                            // getCurrentPosition() folds in offsetMs
                            // (including syncOrigin's live correction)
                            // itself. Adding the session offset here too —
                            // as this did before the ForwardingPlayer
                            // reported absolute time — double-counted it,
                            // desyncing subtitles after every seek/resume.
                            val t = player.currentPosition
                            val result = withContext(Dispatchers.Default) { assRender.renderFrame(t, AssTexType.BITMAP_ALPHA) }
                            if (assFrameShouldApply(result)) frame = result
                        }
                    }
                }
            }
        } finally {
            // Only reached once coroutineScope{} above has confirmed
            // BOTH children are fully done — no renderFrame()/readBuffer()
            // call can still be in flight on another thread past this
            // point, so releasing here can't race a native call the way
            // a separate DisposableEffect could.
            assRender.release()
            ass.release()
        }
    }

    Canvas(
        modifier = modifier
            .fillMaxSize()
            .onSizeChanged { containerSize = it },
    ) {
        val f = frame ?: return@Canvas
        if (frameSize.width <= 0 || frameSize.height <= 0) return@Canvas
        val videoSize = player.videoSize
        val rect = contentRectFor(size.width, size.height, videoSize.width.toFloat(), videoSize.height.toFloat(), resizeMode)
        val scaleX = rect.width / frameSize.width
        val scaleY = rect.height / frameSize.height
        drawIntoCanvas { canvas ->
            val paint = Paint()
            val positioned = f.images.orEmpty().mapNotNull { tex ->
                val bitmap = tex.bitmap ?: return@mapNotNull null
                Triple(
                    tex,
                    bitmap,
                    RectF(
                        rect.left + tex.x * scaleX,
                        rect.top + tex.y * scaleY,
                        rect.left + (tex.x + bitmap.width) * scaleX,
                        rect.top + (tex.y + bitmap.height) * scaleY,
                    ),
                )
            }
            shiftBottomOverflowIntoView(positioned.map { it.third }, size.height, bottomInsetPx)
            positioned.forEach { (tex, bitmap, dst) ->
                // ASS colors pack RGB in the upper 3 bytes and alpha
                // INVERTED (0 = opaque) in the low byte; tex.bitmap is an
                // alpha-only mask, tinted via Paint.color — same formula
                // as AssSubtitleCanvasView.onDraw (lib_ass_media).
                val r = (tex.color shr 24) and 0xFF
                val g = (tex.color shr 16) and 0xFF
                val b = (tex.color shr 8) and 0xFF
                val a = 0xFF - (tex.color and 0xFF)
                paint.color = (a shl 24) or (r shl 16) or (g shl 8) or b
                canvas.nativeCanvas.drawBitmap(bitmap, null, dst, paint)
            }
        }
    }
}

private fun fetchBytes(url: String): ByteArray? {
    ApiClient.authenticatedOkHttpClient().newCall(Request.Builder().url(url).build()).execute().use { response ->
        if (!response.isSuccessful) return null
        return response.body.bytes()
    }
}
