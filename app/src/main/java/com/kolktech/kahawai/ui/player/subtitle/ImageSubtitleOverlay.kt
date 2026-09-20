@file:OptIn(UnstableApi::class)

package com.kolktech.kahawai.ui.player.subtitle

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Paint
import android.graphics.RectF
import android.util.Base64
import android.util.Log
import androidx.annotation.OptIn
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.AspectRatioFrameLayout
import com.kolktech.kahawai.data.network.ApiClient
import com.kolktech.kahawai.data.network.dto.SubtitleTrack
import com.kolktech.kahawai.ui.player.SubtitleSession
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.job
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.Request
import java.util.concurrent.TimeUnit
import kotlin.coroutines.coroutineContext

private const val TAG = "ImageSubtitleOverlay"

/// The redraw loop below always prefers the newest set whose startMs has
/// passed (`lastOrNull { it.startMs <= t }`). A small trailing window here
/// used to assume the tap arrives roughly paced with playback (a handful
/// of sets ahead at most) — wrong: the hub's remux pipeline decodes the
/// whole PGS track's display sets as fast as it can, independent of
/// playback position, and streams a session's entire backlog in one burst
/// on connect. With a window sized for "a few sets ahead", that burst
/// evicted every set near the actual (still-near-zero) playback position
/// before the redraw loop ever got to look at them — subtitles either
/// never appeared or only flashed briefly if a live one barely survived
/// the eviction race. The window has to be sized to hold a whole session's
/// worth of cues instead, not just the redraw poll's lookahead — bounded
/// only as a backstop against a runaway/corrupt stream, comfortably above
/// even a dense feature-length track's cue count (each cue is a start +
/// a later empty clear, so ~2 entries per line of dialogue).
private const val MAX_BUFFERED_SETS = 4096

/// One line of the session's `subs-{id}.jsonl` tap for an IMAGE
/// (PGS/VobSub) track — a decoded display set. Composition space (`cw`,
/// `ch`) is authored against the source's own subtitle-composition
/// resolution, which need not match the video's pixel resolution (see
/// [contentRectFor]'s scale note).
@Serializable
private data class ImageSubObjectWire(val x: Int, val y: Int, val png: String)

@Serializable
private data class ImageDisplaySetWire(val s: Long, val cw: Int, val ch: Int, val o: List<ImageSubObjectWire>)

private class Positioned(val x: Int, val y: Int, val bitmap: Bitmap)
private class DisplaySet(val startMs: Long, val compWidth: Int, val compHeight: Int, val objects: List<Positioned>)

/// Shared with AssSubtitleOverlay — both composite bitmaps positioned
/// relative to the video's own displayed area, not the surrounding
/// (possibly letterboxed) container.
internal data class ContentRect(val left: Float, val top: Float, val width: Float, val height: Float)

private val subtitleJson = Json { ignoreUnknownKeys = true }

/// The video's displayed content box within a `width`x`height` container,
/// given the PlayerView's current resize mode — mirrors what ExoPlayer's
/// own AspectRatioFrameLayout does to the video surface, so overlay
/// bitmaps land in the same place the video itself is drawn regardless of
/// Fit/Zoom(crop)/Fill(stretch).
internal fun contentRectFor(containerW: Float, containerH: Float, videoW: Float, videoH: Float, resizeMode: Int): ContentRect {
    if (videoW <= 0f || videoH <= 0f || containerW <= 0f || containerH <= 0f) {
        return ContentRect(0f, 0f, containerW, containerH)
    }
    if (resizeMode == AspectRatioFrameLayout.RESIZE_MODE_FILL) {
        return ContentRect(0f, 0f, containerW, containerH)
    }
    val containerAspect = containerW / containerH
    val videoAspect = videoW / videoH
    // ZOOM fills the container (overflow clipped by the Canvas's own
    // bounds); FIT letterboxes — same formula, opposite comparison.
    val fill = resizeMode == AspectRatioFrameLayout.RESIZE_MODE_ZOOM
    val w: Float
    val h: Float
    if (containerAspect > videoAspect == fill) {
        w = containerW
        h = containerW / videoAspect
    } else {
        h = containerH
        w = containerH * videoAspect
    }
    return ContentRect((containerW - w) / 2f, (containerH - h) / 2f, w, h)
}

/// Zoom(crop)'s content rect overflows the container vertically, so
/// bottom-anchored subtitles — laid out against the video frame — land
/// below the visible screen edge. Shifts them back up just enough to be
/// fully visible. One shared delta for every shifted rect, not a per-rect
/// clamp: a rendered line is several overlapping bitmaps (ASS fill/
/// outline layers) or stacked rows (two-line PGS), and clamping each to
/// the same bound would collapse them onto each other. Only rects in the
/// bottom half of the screen move — top-anchored signs stay anchored to
/// the video frame and crop with it, same as burn-in. No-op outside Zoom:
/// Fit/Fill keep every rect inside the container, so delta is zero.
///
/// [bottomInsetPx] raises the line they have to clear without moving the
/// half they're measured against: the controls take room off the bottom of
/// the screen, they don't make the screen shorter.
internal fun shiftBottomOverflowIntoView(dsts: List<RectF>, containerH: Float, bottomInsetPx: Float = 0f) {
    val half = containerH / 2f
    val clearBelow = containerH - bottomInsetPx
    var delta = 0f
    for (d in dsts) if (d.centerY() > half) delta = maxOf(delta, d.bottom - clearBelow)
    if (delta <= 0f) return
    for (d in dsts) if (d.centerY() > half) d.offset(0f, -delta)
}

/// PGS/VobSub ("overlay" delivery): the session tap streams decoded
/// display sets — {"s","cw","ch","o":[{"x","y","png"}]} — composited here
/// on a Compose Canvas, mirroring web/src/views/Player.tsx:339-468.
/// "raster" tracks (HUB-32d) share this same wire shape but are fetched
/// whole from an item-scoped endpoint instead — see the source-selection
/// comment in the LaunchedEffect below. The
/// video-pixel-space intermediate step the web client uses (canvas
/// backed at `videoWidth`x`videoHeight`, then CSS-scaled onto the
/// letterboxed box) collapses here into one direct scale from
/// composition space to the displayed content rect, since intermediate
/// video-pixel-space cancels out algebraically for Fit/Zoom's uniform
/// scaling; Fill's non-uniform stretch uses independent x/y scale, same
/// as how it stretches the video itself.
@Composable
fun ImageSubtitleOverlay(
    player: Player,
    track: SubtitleTrack,
    subtitleSession: SubtitleSession,
    resizeMode: Int,
    /// How much of the bottom of the screen is spoken for right now — the
    /// controls, while they're up. Cues that would land under it are lifted
    /// clear, the same way they're lifted out of a Zoom(crop) overflow.
    bottomInsetPx: Float = 0f,
    modifier: Modifier = Modifier,
) {
    val sets = remember(subtitleSession.epoch, track.id) { mutableStateOf(emptyList<DisplaySet>()) }
    var currentSet by remember(subtitleSession.epoch, track.id) { mutableStateOf<DisplaySet?>(null) }
    var containerSize by remember { mutableStateOf(IntSize.Zero) }
    LaunchedEffect(subtitleSession.epoch, track.id) {
        // A "raster" track (HUB-32d: a typeset ASS script rendered to
        // display sets) is served item-level and whole — no session
        // involved, same NDJSON shape as a PGS/VobSub tap — so it's
        // fetched once, like AssSubtitleOverlay's own whole-file
        // fallback. A real embedded PGS/VobSub track only exists as a
        // SESSION tap, and only while that session actually has a
        // remux/transcode pipeline to tap from: the hub's session_file
        // handler no-ops the tap outright for Mode::Direct sessions (a
        // raw byte-range file has no server-side pipeline), the same
        // gate AssSubtitleOverlay applies for its own session tap. There
        // is no whole-file fallback for a genuine embedded image track
        // — the hub's item-scoped `.jsonl` route only answers for
        // origin=="raster" (crates/kahawai-hub/src/api.rs
        // item_subtitle_file) — so Direct mode simply has no source for
        // it; connecting anyway would just retry a tap that provably
        // never produces anything. That case doesn't reach this
        // composable any more (PlayerScreen routes it to media3's own
        // in-container decoding — see isNativeBitmapPick), so what's left
        // here is genuinely sourceless: an unrecognised origin, or a
        // direct session serving a container media3 can't demux the track
        // out of.
        val (url, maxAttempts) = when {
            track.origin == "raster" ->
                "${ApiClient.baseUrl().trimEnd('/')}/api/v1/playback/sessions/" +
                    "${subtitleSession.sessionId}/subtitles/${track.id}.jsonl" to 1
            subtitleSession.isHls && track.origin == "embedded" ->
                "${subtitleSession.streamBaseUrl}subs-${track.id}.jsonl" to 3
            else -> {
                Log.w(
                    TAG,
                    "overlay delivery has no source for track=${track.id} origin=${track.origin} isHls=${subtitleSession.isHls}",
                )
                return@LaunchedEffect
            }
        }
        withContext(Dispatchers.IO) {
            val client = ApiClient.authenticatedOkHttpClient().newBuilder()
                // This is a long-lived, growing stream — must not time
                // out while idle waiting for the next display set.
                .readTimeout(0, TimeUnit.SECONDS)
                .build()
            // A seek-restart tears the hub's pipeline down before
            // recreating the tap files, and this connect (fired off the
            // epoch bump) can race that window — a one-shot miss here
            // used to mean subtitles silently never came back until the
            // next seek or track switch. Retry the CONNECTION a few
            // times (same 3x/700ms shape as syncOrigin) for the session
            // tap while nothing has arrived yet. The whole-file raster
            // fetch has nothing to race (item-level, complete before the
            // first byte), so it gets a single attempt.
            //
            // Once the session tap HAS delivered at least one line, an
            // EOF still isn't reliably "done": the hub's session_file
            // handler (crates/kahawai-hub/src/api.rs) has no concept of
            // "track exhausted" at all — a PGS track with no more cues
            // just leaves the connection open and silent. Every real
            // close after data has flowed is either the whole session
            // ending (which tears this LaunchedEffect down anyway via a
            // fresh epoch on the next attach()) or a transient failure —
            // a transcoder RPC timeout/reschedule for Mode::Transcode
            // sessions — that the hub's own truncation-branch comment
            // frames as "the client reconnects". So the session tap
            // retries forever on EOF once isActive; unlike
            // AssSubtitleOverlay's .ass feedFrom (which can't safely
            // re-feed duplicate Dialogue lines into libass on a
            // from-scratch reconnect), re-received display sets here are
            // harmless — MAX_BUFFERED_SETS just re-settles to the same
            // tail. The raster fetch keeps its old one-shot behavior:
            // it's a complete file, not a growing tap, so there's never
            // anything more to reconnect for.
            var attempt = 0
            var gotAnything = false
            while (isActive) {
                val call = client.newCall(Request.Builder().url(url).build())
                // execute()/readUtf8Line() are blocking calls that don't
                // check coroutine cancellation between reads — only
                // Call.cancel() actually interrupts a stalled read, by
                // closing the socket. Without this hook (which
                // AssSubtitleOverlay's tap already had), every seek's
                // epoch bump stranded the previous connection — and the
                // IO thread blocked on it — until the hub happened to
                // close its end.
                val cancelHandle = coroutineContext.job.invokeOnCompletion { call.cancel() }
                try {
                    call.execute().use { response ->
                        if (!response.isSuccessful) {
                            Log.w(TAG, "subs tap http ${response.code} for track=${track.id} attempt=$attempt")
                            return@use
                        }
                        val source = response.body.source()
                        val acc = ArrayDeque<DisplaySet>()
                        while (isActive) {
                            val line = source.readUtf8Line() ?: break
                            if (line.isBlank()) continue
                            gotAnything = true
                            try {
                                val wire = subtitleJson.decodeFromString<ImageDisplaySetWire>(line)
                                val objects = wire.o.mapNotNull { obj ->
                                    val bytes = Base64.decode(obj.png, Base64.DEFAULT)
                                    val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                                    bitmap?.let { Positioned(obj.x, obj.y, it) }
                                }
                                acc.addLast(DisplaySet(wire.s, wire.cw, wire.ch, objects))
                                while (acc.size > MAX_BUFFERED_SETS) acc.removeFirst()
                                sets.value = acc.toList()
                            } catch (e: Exception) {
                                // Partial/malformed line — same tolerance as
                                // the web client's try/catch per-line.
                            }
                        }
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.w(TAG, "subs tap failed for track=${track.id} attempt=$attempt", e)
                } finally {
                    cancelHandle.dispose()
                }
                val giveUp = if (gotAnything) maxAttempts == 1 else ++attempt >= maxAttempts
                if (giveUp) break
                delay(700)
            }
        }
    }

    // Redraw cadence matches the web client's setInterval(draw, 200) —
    // well inside PGS timing tolerance.
    LaunchedEffect(subtitleSession.epoch, track.id) {
        while (isActive) {
            // Absolute video time in every mode: [player] is the
            // ViewModel's ForwardingPlayer, whose getCurrentPosition()
            // folds in offsetMs (including syncOrigin's live correction)
            // itself. Adding the session offset here too — as this did
            // before the ForwardingPlayer reported absolute time —
            // double-counted it, desyncing subtitles after every
            // seek/resume.
            val t = player.currentPosition
            currentSet = sets.value.lastOrNull { it.startMs <= t }
            delay(200)
        }
    }

    Canvas(
        modifier = modifier
            .fillMaxSize()
            .onSizeChanged { containerSize = it },
    ) {
        val set = currentSet ?: return@Canvas
        if (set.compWidth <= 0 || set.compHeight <= 0) return@Canvas
        val videoSize = player.videoSize
        val rect = contentRectFor(
            containerW = containerSize.width.toFloat(),
            containerH = containerSize.height.toFloat(),
            videoW = videoSize.width.toFloat(),
            videoH = videoSize.height.toFloat(),
            resizeMode = resizeMode,
        )
        // Uniform-by-width for Fit/Zoom, matching the burn path's own
        // compose(): composition space need not share the video's aspect
        // ratio (e.g. a 3840x1600 scope frame authored against 1920x1080
        // subs), and scaling the two axes independently would distort
        // text width vs burn-in/mpv. Fill already stretches the video
        // non-uniformly, so matching it with independent axes is correct
        // there.
        val scaleX = rect.width / set.compWidth
        val scaleY = if (resizeMode == AspectRatioFrameLayout.RESIZE_MODE_FILL) rect.height / set.compHeight else scaleX
        drawIntoCanvas { canvas ->
            val paint = Paint()
            val dsts = set.objects.map { obj ->
                val w = obj.bitmap.width * scaleX
                val h = obj.bitmap.height * scaleY
                // Clamp into the displayed frame, mirroring the web
                // client's draw() (and the burn path): composition space
                // (cw x ch, e.g. 1920x1080) can be TALLER than the video
                // frame it plays over (a 2.39:1 scope film), and the
                // width-uniform scale maps bottom-anchored rows to y
                // coordinates past the picture's bottom edge — off
                // screen entirely in landscape, where the frame already
                // fills the display's height. Burn-in clamps such rows
                // back onto the picture; without this the overlay showed
                // only the top line of two-line PGS subtitles.
                val x = (obj.x * scaleX).coerceIn(0f, (rect.width - w).coerceAtLeast(0f))
                val y = (obj.y * scaleY).coerceIn(0f, (rect.height - h).coerceAtLeast(0f))
                RectF(rect.left + x, rect.top + y, rect.left + x + w, rect.top + y + h)
            }
            shiftBottomOverflowIntoView(dsts, size.height, bottomInsetPx)
            set.objects.forEachIndexed { index, obj ->
                canvas.nativeCanvas.drawBitmap(obj.bitmap, null, dsts[index], paint)
            }
        }
    }
}
