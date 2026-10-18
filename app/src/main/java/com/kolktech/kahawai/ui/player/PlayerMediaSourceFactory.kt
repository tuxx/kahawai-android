package com.kolktech.kahawai.ui.player

import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.Timeline
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.exoplayer.drm.DrmSessionManagerProvider
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.exoplayer.hls.playlist.DefaultHlsPlaylistTracker
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.MergingMediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy
import androidx.media3.extractor.ExtractorsFactory
import androidx.media3.extractor.text.DefaultSubtitleParserFactory
import androidx.media3.extractor.text.SubtitleExtractor
import androidx.annotation.OptIn
import com.kolktech.kahawai.playback.PlaybackExtractorsFactory

/// See [buildPlayerMediaSourceFactory]. Media3's own default is
/// 3.5 (DefaultHlsPlaylistTracker.DEFAULT_PLAYLIST_STUCK_TARGET_DURATION_COEFFICIENT,
/// ~7s at the hub's 2s segment target duration).
///
/// History: 30.0 (~60s) still failed on a deep seek into a large file —
/// slow pipeline restart, not a dead one — which for a while meant this
/// was disabled outright (Double.MAX_VALUE, overflowing the comparison
/// to +Infinity). That traded the false positive for an unbounded one: a
/// session whose pipeline actually died (worker crash) hung forever with
/// no PlaylistStuckException and no onPlayerError. A hub-side fix has
/// since resolved the slow-restart latency that 60s wasn't covering, so
/// the wide margin is no longer needed: 5.0 (~10s) fails fast — a couple
/// of segment intervals of slack for ordinary jitter, not minutes — while
/// still being finite. Each seek-restart rebuilds the MediaItem via
/// attach() (PlayerViewModel.handleSeek -> attach), which means a fresh
/// HlsMediaSource/DefaultHlsPlaylistTracker per seek, so this window
/// always starts counting from that restart. If slow restarts reappear
/// (a different hub, a heavier transcode), raise this again rather than
/// re-disabling it outright. (now - lastSnapshotChangeMs) >
/// targetDurationMs * COEFFICIENT.
private const val HLS_PLAYLIST_STUCK_COEFFICIENT = 5.0

/// What the UI (PlayerView / its built-in controller) should hold.
/// Media3's default HLS playlist-stuck tolerance
/// (DefaultHlsPlaylistTracker.DEFAULT_PLAYLIST_STUCK_TARGET_DURATION_COEFFICIENT,
/// 3.5×) is tuned for a live encoder that's already running and just
/// pauses briefly — at the hub's 2s segment target-duration, that's
/// only ~7s before ExoPlayer gives up with a PlaylistStuckException.
/// A seek-restart here isn't a brief pause: the hub tears down and
/// restarts the WHOLE remux/transcode pipeline at a new position
/// first (confirmed by seeking far ahead in the field — the hub's
/// own seek accepted the request and reported a new part_base_ms,
/// but nothing had reached the playlist by the time ExoPlayer gave
/// up), which can legitimately take longer than 7s, especially
/// seeking deep into a large file. 30× (~60s here) gives the hub
/// realistic room to finish restarting while still eventually
/// failing loudly — surfacing as PlayerState.Error, not a silent
/// black screen — if something really is broken.
///
/// DefaultMediaSourceFactory has no hook to reach into the HLS
/// delegate it builds internally, so this constructs the HLS branch
/// by hand and falls back to a plain DefaultMediaSourceFactory for
/// "direct" mode's progressive/byte-range files.
///
/// DefaultMediaSourceFactory.createMediaSource() does two things for
/// any content type: builds the content MediaSource via whichever
/// delegate matches, THEN separately wraps each of MediaItem's own
/// sideloaded subtitleConfigurations (Text-delivery VTT — see
/// PlayerViewModel.textDeliverySubtitleConfigs()) and merges them all with
/// MergingMediaSource. That merging step lives in
/// DefaultMediaSourceFactory itself, NOT in HlsMediaSource.Factory
/// — calling hlsFactory.createMediaSource(mediaItem) directly (as
/// this did before) skips it entirely, silently dropping every
/// Text-delivery subtitle track for remux/transcode sessions. Ass/
/// Overlay delivery is unaffected either way (out-of-band HTTP taps,
/// never routed through MediaItem at all), which is why this
/// regression was easy to miss.
///
/// The wrapping MUST be the modern ProgressiveMediaSource +
/// SubtitleExtractor arrangement (what DefaultMediaSourceFactory
/// itself builds — confirmed by disassembling its 1.10.0
/// createMediaSource), NOT the legacy SingleSampleMediaSource this
/// used before: SingleSampleMediaSource delivers the raw VTT bytes
/// as one text/vtt sample, and Media3 1.10's TextRenderer ships
/// with legacy sample decoding DISABLED — selecting such a track
/// doesn't quietly show nothing, it throws ("Legacy decoding is
/// disabled, can't handle text/vtt samples") and takes the whole
/// playback down with it. SubtitleExtractor instead parses the VTT
/// at load time into application/x-media3-cues samples, the only
/// thing the renderer accepts.
@OptIn(UnstableApi::class)
internal fun buildPlayerMediaSourceFactory(dataSourceFactory: DataSource.Factory): MediaSource.Factory {
    val hlsFactory = HlsMediaSource.Factory(dataSourceFactory)
        .setPlaylistTrackerFactory { dsFactory, errorPolicy, parserFactory, cmcdConfig, executor ->
            DefaultHlsPlaylistTracker(dsFactory, errorPolicy, parserFactory, cmcdConfig, HLS_PLAYLIST_STUCK_COEFFICIENT, executor)
        }
    val subtitleParserFactory = DefaultSubtitleParserFactory()
    val progressiveFactory = DefaultMediaSourceFactory(dataSourceFactory, PlaybackExtractorsFactory())
    fun subtitleSource(config: MediaItem.SubtitleConfiguration): MediaSource {
        val format = Format.Builder()
            .setSampleMimeType(config.mimeType)
            .setLanguage(config.language)
            .setSelectionFlags(config.selectionFlags)
            .setRoleFlags(config.roleFlags)
            .setLabel(config.label)
            .setId(config.id)
            .build()
        // Unlike DefaultMediaSourceFactory we can't defer the fetch
        // until the track is selected (its lazy-single-track hook is
        // package-private), so each sideloaded VTT is fetched at
        // prepare() — they're small text files, an acceptable cost.
        val extractorsFactory = ExtractorsFactory {
            arrayOf(SubtitleExtractor(subtitleParserFactory.create(format), format))
        }
        return ProgressiveMediaSource.Factory(dataSourceFactory, extractorsFactory)
            .createMediaSource(MediaItem.fromUri(config.uri))
    }
    return object : MediaSource.Factory {
        override fun createMediaSource(mediaItem: MediaItem): MediaSource {
            if (mediaItem.localConfiguration?.mimeType != MimeTypes.APPLICATION_M3U8) {
                return progressiveFactory.createMediaSource(mediaItem)
            }
            val content = hlsFactory.createMediaSource(mediaItem)
            val subtitleConfigs = mediaItem.localConfiguration?.subtitleConfigurations.orEmpty()
            if (subtitleConfigs.isEmpty()) return content
            val subtitleSources = subtitleConfigs.map { subtitleSource(it) }
            return MergingMediaSource(content, *subtitleSources.toTypedArray())
        }

        override fun getSupportedTypes(): IntArray = intArrayOf(C.CONTENT_TYPE_HLS, C.CONTENT_TYPE_OTHER)

        override fun setDrmSessionManagerProvider(drmSessionManagerProvider: DrmSessionManagerProvider): MediaSource.Factory {
            hlsFactory.setDrmSessionManagerProvider(drmSessionManagerProvider)
            progressiveFactory.setDrmSessionManagerProvider(drmSessionManagerProvider)
            return this
        }

        override fun setLoadErrorHandlingPolicy(loadErrorHandlingPolicy: LoadErrorHandlingPolicy): MediaSource.Factory {
            hlsFactory.setLoadErrorHandlingPolicy(loadErrorHandlingPolicy)
            progressiveFactory.setLoadErrorHandlingPolicy(loadErrorHandlingPolicy)
            return this
        }
    }
}

/// See PlayerViewModel's ForwardingPlayer.getCurrentTimeline() override.
/// Timeline has no public ForwardingTimeline helper in this Media3
/// version, but its surface is small (six abstract methods) — plain
/// delegation for everything, with getWindow()/getPeriod() additionally
/// overwriting the delegate-populated durationUs before returning it.
@OptIn(UnstableApi::class)
internal class DurationOverrideTimeline(
    private val delegate: Timeline,
    private val totalDurationUs: Long,
) : Timeline() {
    override fun getWindowCount(): Int = delegate.windowCount

    override fun getWindow(windowIndex: Int, window: Window, defaultPositionProjectionUs: Long): Window {
        delegate.getWindow(windowIndex, window, defaultPositionProjectionUs)
        window.durationUs = totalDurationUs
        window.isSeekable = true
        window.isDynamic = false
        return window
    }

    override fun getPeriodCount(): Int = delegate.periodCount

    override fun getPeriod(periodIndex: Int, period: Period, setIds: Boolean): Period {
        delegate.getPeriod(periodIndex, period, setIds)
        period.durationUs = totalDurationUs
        return period
    }

    override fun getIndexOfPeriod(uid: Any): Int = delegate.getIndexOfPeriod(uid)

    override fun getUidOfPeriod(periodIndex: Int): Any = delegate.getUidOfPeriod(periodIndex)
}
