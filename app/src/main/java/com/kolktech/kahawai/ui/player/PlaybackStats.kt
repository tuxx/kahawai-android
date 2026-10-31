@file:OptIn(androidx.media3.common.util.UnstableApi::class)

package com.kolktech.kahawai.ui.player

import android.media.MediaFormat
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.video.VideoFrameMetadataListener
import com.kolktech.kahawai.data.network.dto.ClientAudioStream
import com.kolktech.kahawai.data.network.dto.PlaybackStreams
import java.util.Locale
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.sqrt

/// What the stats overlay needs that the player doesn't keep itself:
/// bytes off the wire (a TransferListener on the data source, so it counts
/// a direct session's long-running byte range as it goes, not only when a
/// load completes), decoder names, the bandwidth estimate, and when each
/// video frame was actually scheduled onto the display.
///
/// Transfer callbacks arrive on loader threads and frame callbacks on the
/// playback thread; the analytics ones on the main thread.
internal class PlaybackStatsTracker :
    TransferListener,
    AnalyticsListener,
    VideoFrameMetadataListener {

    private val bytes = AtomicLong()
    val bytesTransferred: Long get() = bytes.get()

    var videoDecoderName: String? = null
        private set
    var audioDecoderName: String? = null
        private set
    var bandwidthEstimateBps: Long = 0
        private set

    // The release times of the last FRAME_WINDOW frames, oldest first
    // once the ring has wrapped.
    private val releaseTimesNs = LongArray(FRAME_WINDOW)
    private var frameCount = 0

    override fun onTransferInitializing(source: DataSource, dataSpec: DataSpec, isNetwork: Boolean) = Unit
    override fun onTransferStart(source: DataSource, dataSpec: DataSpec, isNetwork: Boolean) = Unit
    override fun onTransferEnd(source: DataSource, dataSpec: DataSpec, isNetwork: Boolean) = Unit

    override fun onBytesTransferred(source: DataSource, dataSpec: DataSpec, isNetwork: Boolean, bytesTransferred: Int) {
        if (isNetwork) bytes.addAndGet(bytesTransferred.toLong())
    }

    override fun onVideoDecoderInitialized(
        eventTime: AnalyticsListener.EventTime,
        decoderName: String,
        initializedTimestampMs: Long,
        initializationDurationMs: Long,
    ) {
        videoDecoderName = decoderName
    }

    override fun onAudioDecoderInitialized(
        eventTime: AnalyticsListener.EventTime,
        decoderName: String,
        initializedTimestampMs: Long,
        initializationDurationMs: Long,
    ) {
        audioDecoderName = decoderName
    }

    override fun onBandwidthEstimate(
        eventTime: AnalyticsListener.EventTime,
        totalLoadTimeMs: Int,
        totalBytesLoaded: Long,
        bitrateEstimate: Long,
    ) {
        bandwidthEstimateBps = bitrateEstimate
    }

    override fun onVideoFrameAboutToBeRendered(
        presentationTimeUs: Long,
        releaseTimeNs: Long,
        format: Format,
        mediaFormat: MediaFormat?,
    ) {
        synchronized(releaseTimesNs) {
            releaseTimesNs[frameCount % FRAME_WINDOW] = releaseTimeNs
            frameCount++
        }
    }

    /// Frame pacing over the last [FRAME_WINDOW] frames; null until there
    /// are enough of them to say anything.
    fun frameTiming(): FrameTiming? {
        val times = synchronized(releaseTimesNs) {
            val n = minOf(frameCount, FRAME_WINDOW)
            LongArray(n) { releaseTimesNs[(frameCount - n + it) % FRAME_WINDOW] }
        }
        return frameTimingOf(times)
    }

    /// Forgets the frame history, so a pause or seek doesn't count as one
    /// long frame.
    fun resetFrames() {
        synchronized(releaseTimesNs) { frameCount = 0 }
    }
}

private const val FRAME_WINDOW = 120

// Anything longer is a pause, a seek or a rebuffer rather than a frame.
private const val MAX_FRAME_INTERVAL_NS = 500_000_000L

internal data class FrameTiming(val meanMs: Double, val jitterMs: Double, val maxMs: Double)

/// Mean, standard deviation and worst interval between consecutive frame
/// release times. A dropped frame shows up as one interval twice as long.
internal fun frameTimingOf(releaseTimesNs: LongArray): FrameTiming? {
    val intervals = (1 until releaseTimesNs.size)
        .map { releaseTimesNs[it] - releaseTimesNs[it - 1] }
        .filter { it in 1..MAX_FRAME_INTERVAL_NS }
        .map { it / 1_000_000.0 }
    if (intervals.size < 2) return null
    val mean = intervals.average()
    val variance = intervals.sumOf { (it - mean) * (it - mean) } / intervals.size
    return FrameTiming(mean, sqrt(variance), intervals.max())
}

/// One reading of the player for the stats overlay. Counters here are
/// running totals; [statsLines] turns two readings into rates.
internal data class PlaybackStatsSnapshot(
    val atNs: Long,
    val mode: String?,
    val streams: PlaybackStreams?,
    val videoFormat: Format?,
    val audioFormat: Format?,
    val sourceAudio: ClientAudioStream?,
    val subtitle: String?,
    val videoDecoder: String?,
    val audioDecoder: String?,
    val renderedFrames: Int,
    val droppedFrames: Int,
    val skippedFrames: Int,
    val maxConsecutiveDropped: Int,
    val frameOffsetUs: Long,
    val frameOffsetCount: Int,
    val bytesTransferred: Long,
    val bandwidthEstimateBps: Long,
    val fileBitrateBps: Long?,
    val bufferedMs: Long,
    val frameTiming: FrameTiming?,
)

/// What the overlay knows about the screen it's drawn on.
internal data class DisplayStats(val refreshRateHz: Float?, val viewWidth: Int, val viewHeight: Int)

internal data class StatLine(val label: String, val value: String)

/// The overlay's rows. Labels are technical readouts, left untranslated
/// like the codec names beside them. Rates come from the change between
/// [previous] and [current]; the player's decoder counters start again
/// whenever a seek-restart re-attaches the stream, so a counter that went
/// backwards is read as counting from zero.
internal fun statsLines(previous: PlaybackStatsSnapshot, current: PlaybackStatsSnapshot, display: DisplayStats): List<StatLine> {
    val elapsedS = (current.atNs - previous.atNs) / 1e9
    fun rate(now: Long, before: Long): Double? =
        if (elapsedS <= 0.0) null else (if (now >= before) now - before else now) / elapsedS

    val video = current.videoFormat
    val audio = current.audioFormat
    val streams = current.streams

    return buildList {
        add(StatLine("Session", listOfNotNull(current.mode, streams?.cost?.replace('_', ' ')).joinToString(" · ").ifEmpty { "-" }))
        add(
            StatLine(
                "Video",
                listOfNotNull(
                    streams?.video?.ifEmpty { null },
                    video?.let { codecName(it.sampleMimeType, it.codecs) },
                    video?.takeIf { it.width > 0 }?.let { "${it.width}×${it.height}" },
                    video?.frameRate?.takeIf { it > 0 }?.let { "%.3f fps".format(Locale.ROOT, it) },
                ).joinToString(" · ").ifEmpty { "-" },
            ),
        )
        video?.colorInfo?.takeIf { it.isDataSpaceValid }?.let { color ->
            add(StatLine("Color", listOfNotNull(transferName(color.colorTransfer), primariesName(color.colorSpace)).joinToString(" · ")))
        }
        add(
            StatLine(
                "Audio",
                listOfNotNull(
                    streams?.audio?.ifEmpty { null },
                    audio?.let { codecName(it.sampleMimeType, it.codecs) },
                    audio?.channelCount?.takeIf { it > 0 }?.let { "${it}ch" },
                    audio?.sampleRate?.takeIf { it > 0 }?.let { "${it / 1000.0} kHz" },
                ).joinToString(" · ").ifEmpty { "-" },
            ),
        )
        // What the file itself carries, where the hub changed it.
        val source = current.sourceAudio
        if (source != null && streams?.audio == "transcode") {
            add(StatLine("  source", listOfNotNull(source.codec, "${source.channels}ch").joinToString(" · ")))
        }
        current.subtitle?.let { add(StatLine("Subtitles", it)) }
        add(StatLine("Decoders", listOfNotNull(current.videoDecoder?.let(::decoderLabel), current.audioDecoder?.let(::decoderLabel)).joinToString(" / ").ifEmpty { "-" }))

        val streamBps = video?.averageBitrate?.takeIf { it > 0 }?.toLong()
            ?: video?.bitrate?.takeIf { it > 0 }?.toLong()
            ?: current.fileBitrateBps
        val networkBps = rate(current.bytesTransferred, previous.bytesTransferred)?.times(8)
        add(
            StatLine(
                "Bitrate",
                listOfNotNull(
                    streamBps?.let { "${mbps(it.toDouble())} stream" },
                    networkBps?.let { "${mbps(it)} net" },
                    current.bandwidthEstimateBps.takeIf { it > 0 }?.let { "${mbps(it.toDouble())} est" },
                ).joinToString(" · ").ifEmpty { "-" },
            ),
        )
        add(StatLine("Buffer", "%.1f s".format(Locale.ROOT, current.bufferedMs / 1000.0)))

        val fps = rate(current.renderedFrames.toLong(), previous.renderedFrames.toLong())
        add(
            StatLine(
                "FPS",
                listOfNotNull(
                    fps?.let { "%.2f rendered".format(Locale.ROOT, it) },
                    display.refreshRateHz?.let { "display %.0f Hz".format(Locale.ROOT, it) },
                ).joinToString(" · ").ifEmpty { "-" },
            ),
        )
        current.frameTiming?.let {
            add(StatLine("Frame time", "%.1f ms ±%.1f · max %.1f".format(Locale.ROOT, it.meanMs, it.jitterMs, it.maxMs)))
        }
        val offsetCount = rate(current.frameOffsetCount.toLong(), previous.frameOffsetCount.toLong())
        val offsetUs = rate(current.frameOffsetUs, previous.frameOffsetUs)
        if (offsetCount != null && offsetUs != null && offsetCount > 0) {
            // How far ahead of its display time each frame left the decoder.
            add(StatLine("Decode lead", "%.1f ms".format(Locale.ROOT, offsetUs / offsetCount / 1000)))
        }
        val droppedPerS = rate(current.droppedFrames.toLong(), previous.droppedFrames.toLong())
        add(
            StatLine(
                "Dropped",
                buildString {
                    append(current.droppedFrames)
                    droppedPerS?.let { append(" (%.1f/s)".format(Locale.ROOT, it)) }
                    append(" · skipped ${current.skippedFrames} · max run ${current.maxConsecutiveDropped}")
                },
            ),
        )
        if (display.viewWidth > 0) add(StatLine("Viewport", "${display.viewWidth}×${display.viewHeight}"))
    }
}

private fun mbps(bps: Double): String = "%.1f Mbps".format(Locale.ROOT, bps / 1_000_000)

/// The codec string where the container gave one (avc1.640028), else the
/// short name of the MIME type.
private fun codecName(mimeType: String?, codecs: String?): String? =
    codecs?.takeIf { it.isNotBlank() } ?: mimeType?.substringAfter('/')

/// The decoder's name, marked when it's a software one: the platform's
/// own c2.android / OMX.google decoders, or Media3's FFmpeg extension.
internal fun decoderLabel(name: String): String {
    val software = name.startsWith("c2.android.") || name.startsWith("OMX.google.") || name.contains("ffmpeg", ignoreCase = true)
    return if (software) "$name (sw)" else name
}

private fun transferName(transfer: Int): String? = when (transfer) {
    C.COLOR_TRANSFER_ST2084 -> "PQ"
    C.COLOR_TRANSFER_HLG -> "HLG"
    C.COLOR_TRANSFER_SDR -> "SDR"
    C.COLOR_TRANSFER_SRGB -> "sRGB"
    C.COLOR_TRANSFER_LINEAR -> "linear"
    else -> null
}

private fun primariesName(space: Int): String? = when (space) {
    C.COLOR_SPACE_BT2020 -> "BT.2020"
    C.COLOR_SPACE_BT709 -> "BT.709"
    C.COLOR_SPACE_BT601 -> "BT.601"
    else -> null
}
