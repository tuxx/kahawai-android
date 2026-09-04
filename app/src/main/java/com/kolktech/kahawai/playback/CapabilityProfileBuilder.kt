@file:OptIn(UnstableApi::class)

package com.kolktech.kahawai.playback

import android.content.Context
import android.hardware.display.DisplayManager
import android.media.MediaCodecList
import android.media.MediaFormat
import android.os.Build
import android.view.Display
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.common.util.Util
import androidx.media3.exoplayer.audio.AudioCapabilities
import com.kolktech.kahawai.data.network.dto.CapabilityProfile
import com.kolktech.kahawai.data.network.dto.TargetDuration
import com.kolktech.kahawai.data.network.dto.VideoCap
import kotlin.math.max
import kotlin.math.roundToInt

/// Probes the device for a [CapabilityProfile], the same way
/// web/src/capabilities.ts probes the browser (`MediaSource.isTypeSupported`)
/// rather than hardcoding a claim — HUB-14 negotiation is only as good
/// as what the client actually reports. Built once per process; decoder
/// support and the display don't change while the app is running.
object CapabilityProfileBuilder {
    private val VIDEO_CODECS = listOf(
        "h264" to MediaFormat.MIMETYPE_VIDEO_AVC,
        "hevc" to MediaFormat.MIMETYPE_VIDEO_HEVC,
        "vp9" to MediaFormat.MIMETYPE_VIDEO_VP9,
        "av1" to MediaFormat.MIMETYPE_VIDEO_AV1,
    )
    /// Hub codec name (crates/kahawai-media/src/lib.rs `normalize_audio_codec`)
    /// to every MIME that means it here. Several to one where the hub draws
    /// no distinction: it calls every DTS variant `dts` and every E-AC3
    /// `eac3`, so being able to play any one of them is enough to claim the
    /// name — a source it would call `dts` may well be plain DTS.
    ///
    /// `pcm` is deliberately absent. It needs no decoder and has nothing to
    /// pass through, so neither probe below can answer for it, and claiming
    /// it on a guess would send LPCM over the wire (a Blu-ray remux's 24-bit
    /// tracks are several Mbps) for a client that may not take it.
    private val AUDIO_CODECS: List<Pair<String, List<String>>> = listOf(
        "aac" to listOf(MimeTypes.AUDIO_AAC),
        "mp3" to listOf(MimeTypes.AUDIO_MPEG),
        "mpeg-audio" to listOf(MimeTypes.AUDIO_MPEG_L1, MimeTypes.AUDIO_MPEG_L2),
        "opus" to listOf(MimeTypes.AUDIO_OPUS),
        "flac" to listOf(MimeTypes.AUDIO_FLAC),
        "vorbis" to listOf(MimeTypes.AUDIO_VORBIS),
        "ac3" to listOf(MimeTypes.AUDIO_AC3),
        "eac3" to listOf(MimeTypes.AUDIO_E_AC3, MimeTypes.AUDIO_E_AC3_JOC),
        "ac4" to listOf(MimeTypes.AUDIO_AC4),
        "dts" to listOf(
            MimeTypes.AUDIO_DTS,
            MimeTypes.AUDIO_DTS_HD,
            MimeTypes.AUDIO_DTS_EXPRESS,
        ),
        "truehd" to listOf(MimeTypes.AUDIO_TRUEHD),
    )

    fun build(context: Context): CapabilityProfile {
        val decodedMimes = MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos
            .asSequence()
            .filter { !it.isEncoder }
            .flatMap { it.supportedTypes.asSequence() }
            .toSet()

        val video = VIDEO_CODECS.filter { (_, mime) -> mime in decodedMimes }.map { (name, _) -> VideoCap(codec = name) }
        // Passthrough is read for the sink that is actually connected — the
        // platform's surround settings plus what the HDMI device reports —
        // so this answer follows the receiver the box is plugged into today,
        // which is why the profile is rebuilt per session rather than cached.
        val caps = AudioCapabilities.getCapabilities(context)
        val audioSupport = audioSupport(
            decodable = decodedMimes,
            passthroughChannels = { mime -> passthroughChannels(caps, mime) },
            decodeMaxChannels = caps.maxChannelCount,
        )

        val display = context.getSystemService(DisplayManager::class.java)
            ?.getDisplay(Display.DEFAULT_DISPLAY)
        val hdr = display?.let(::supportsHdr) == true

        return CapabilityProfile(
            containers = listOf("mp4", "matroska"),
            video = video,
            audio = audioSupport.codecs,
            // The widest of the two routes — see [audioSupport]. This used
            // to be 0 ("unlimited"), on the grounds that Android downmixes
            // multichannel itself so a cap could only force an avoidable
            // re-encode. That holds for the DECODE route and not for
            // passthrough: bitstreaming has no downmix step, and a track
            // wider than the receiver takes is silence unless a decoder
            // exists to fall back to. Reporting the real ceiling also
            // spares a stereo TV the bandwidth of 5.1 it was going to fold
            // down anyway.
            maxAudioChannels = audioSupport.maxChannels,
            maxHeight = maxDisplayHeight(context, display),
            maxFps = display?.refreshRate?.roundToInt(),
            hdr = hdr,
            maxBandwidthKbps = null,
            // Faithful ASS (libass, ass-kt) and bitmap-overlay (PGS/VobSub
            // display-set) rendering are both wired into the player now —
            // claim the richest tier the hub can offer instead of forcing
            // everything through flattened VTT or a burned-in encode.
            assRender = true,
            graphicsOverlay = true,
            // Media3's TextRenderer reads WebVTT natively — the only
            // format every text rung (converted SRT, flattened ASS,
            // OCR) is delivered as.
            vttRender = true,
            // ExoPlayer times out an idle HLS playlist at 3.5x the
            // declared EXT-X-TARGETDURATION (HUB-17) — the old fixed
            // 2s constant is what caused that hang, so this client
            // needs the measured, keyframe-bound truth rather than
            // `Ignore`.
            targetDuration = TargetDuration.Accurate,
        )
    }

    /// `resources.displayMetrics` is the *rendered* UI size, not the HDMI
    /// output size. Android TV boxes routinely compose the UI at 1080p and
    /// let SurfaceFlinger hardware-scale to a 2160p link — an NVIDIA Shield
    /// on a 4K panel reports 1920x1080 there while decoding and outputting
    /// 4K video perfectly well, so probing it under-claims by a factor of
    /// two and the hub transcodes down for nothing. Media3's
    /// `Util.getCurrentDisplayModeSize` reads the physical mode instead
    /// (`Display.Mode.getPhysicalWidth/Height` on API 28+, the
    /// `sys.display-size`/`vendor.display-size` TV properties below that),
    /// which is the same size ExoPlayer itself uses to pick a video track.
    /// Still the larger dimension, so the answer does not depend on which
    /// way a handset happens to be held.
    private fun maxDisplayHeight(context: Context, display: Display?): Int? {
        val size = runCatching {
            if (display != null) {
                Util.getCurrentDisplayModeSize(context, display)
            } else {
                Util.getCurrentDisplayModeSize(context)
            }
        }.getOrNull()
        if (size != null && size.x > 0 && size.y > 0) return max(size.x, size.y)
        val metrics = context.resources.displayMetrics
        return max(metrics.widthPixels, metrics.heightPixels)
    }

    /// What this box can play, and how wide.
    ///
    /// Two routes reach the speakers and they answer differently. A DECODER
    /// plays anything it supports and Android folds the result down to
    /// whatever the sink takes, so its ceiling is the PCM output's. PASSTHROUGH
    /// bitstreams the track untouched to a receiver: no decode, no downmix,
    /// so a track wider than that receiver accepts is silence — its ceiling is
    /// per codec, and `MediaCodecList` knows nothing about it.
    ///
    /// A codec is claimed when EITHER route can play it: a union, not an
    /// intersection, because claiming a codec only means "do not re-encode
    /// this for me", and media3 picks the route itself ([DefaultAudioSink]
    /// prefers passthrough and falls back to decoding). Reported without
    /// this, a Shield wired to a receiver had every AC3 film re-encoded to
    /// AAC — a codec it can bitstream — which also dragged those sessions
    /// onto the hub's transcode path instead of a plain remux.
    ///
    /// The channel ceiling is the widest either route reaches, so it never
    /// under-claims the one that can actually carry the track.
    internal fun audioSupport(
        decodable: Set<String>,
        passthroughChannels: (String) -> Int,
        decodeMaxChannels: Int,
    ): AudioSupport {
        var passthroughCeiling = 0
        val codecs = AUDIO_CODECS.filter { (_, mimes) ->
            mimes.any { mime ->
                val channels = passthroughChannels(mime)
                if (channels > passthroughCeiling) passthroughCeiling = channels
                channels > 0 || mime in decodable
            }
        }.map { (name, _) -> name }
        // A decoder's ceiling only counts for codecs there is a decoder for;
        // with none, the box plays nothing but what it can bitstream.
        val decodeCeiling = if (AUDIO_CODECS.any { (_, m) -> m.any { it in decodable } }) decodeMaxChannels else 0
        return AudioSupport(codecs = codecs, maxChannels = max(decodeCeiling, passthroughCeiling))
    }

    /// [CapabilityProfile.audio] and [CapabilityProfile.maxAudioChannels],
    /// which are one answer: both follow from the same two routes.
    internal data class AudioSupport(val codecs: List<String>, val maxChannels: Int)

    /// The widest layout this sink will bitstream for [mime], 0 when it will
    /// not. Probed downwards rather than asked, because the question the API
    /// answers is per Format, not per codec.
    private fun passthroughChannels(caps: AudioCapabilities, mime: String): Int =
        CHANNEL_PROBES.firstOrNull { channels ->
            runCatching {
                caps.isPassthroughPlaybackSupported(probeFormat(mime, channels), AudioAttributes.DEFAULT)
            }.getOrDefault(false)
        } ?: 0

    /// Widest first: the first hit is the ceiling. 7.1, 5.1, stereo — the
    /// layouts these codecs actually ship in.
    private val CHANNEL_PROBES = listOf(8, 6, 2)

    /// A stand-in stream for the passthrough question, which is asked about
    /// a Format rather than a MIME. 48 kHz throughout: the rate these codecs
    /// almost always arrive at, and not something a receiver's answer turns
    /// on the way channel count is.
    private fun probeFormat(mime: String, channels: Int): Format =
        Format.Builder()
            .setSampleMimeType(mime)
            .setChannelCount(channels)
            .setSampleRate(48_000)
            .build()

    /// `Display.Mode.getSupportedHdrTypes()` (API 34+) replaced the
    /// deprecated `Display.HdrCapabilities` path, but minSdk is 26 — the
    /// deprecated call is still the only option below API 34.
    private fun supportsHdr(display: Display): Boolean {
        if (Build.VERSION.SDK_INT >= 34) {
            return display.mode.supportedHdrTypes.isNotEmpty()
        }
        @Suppress("DEPRECATION")
        return display.hdrCapabilities?.supportedHdrTypes?.isNotEmpty() == true
    }
}
