@file:OptIn(androidx.media3.common.util.UnstableApi::class, androidx.media3.common.util.ExperimentalApi::class)

package com.kolktech.kahawai.playback

import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.extractor.Extractor
import androidx.media3.extractor.ExtractorsFactory
import androidx.media3.extractor.mkv.MatroskaExtractor
import androidx.media3.extractor.text.DefaultSubtitleParserFactory
import androidx.media3.extractor.text.SubtitleParser

/// Keep the default format ordering and configuration, substituting only
/// the Matroska reader. Forward MediaSource's subtitle/HDR configuration
/// to both the defaults and the replacement.
internal class PlaybackExtractorsFactory : ExtractorsFactory {
    private val defaults = DefaultExtractorsFactory()
    private var subtitles: SubtitleParser.Factory = DefaultSubtitleParserFactory()
    private var transcodeText = true
    private var parseHagc = true

    private fun wrap(extractors: Array<Extractor>): Array<Extractor> = extractors.map {
        if (it is MatroskaExtractor) {
            val flags = (if (transcodeText) 0 else MatroskaExtractor.FLAG_EMIT_RAW_SUBTITLE_DATA) or
                (if (parseHagc) 0 else MatroskaExtractor.FLAG_DISABLE_HAGC_METADATA)
            MatroskaSegmentExtractor(subtitles, flags)
        } else it
    }.toTypedArray()

    override fun createExtractors(): Array<Extractor> = wrap(defaults.createExtractors())
    override fun createExtractors(uri: Uri, responseHeaders: Map<String, List<String>>): Array<Extractor> =
        wrap(defaults.createExtractors(uri, responseHeaders))

    override fun setSubtitleParserFactory(subtitleParserFactory: SubtitleParser.Factory): ExtractorsFactory = apply {
        subtitles = subtitleParserFactory
        defaults.setSubtitleParserFactory(subtitleParserFactory)
    }

    @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
    override fun experimentalSetTextTrackTranscodingEnabled(enabled: Boolean): ExtractorsFactory = apply {
        transcodeText = enabled
        defaults.experimentalSetTextTrackTranscodingEnabled(enabled)
    }

    override fun setParseHagcMetadata(enabled: Boolean): ExtractorsFactory = apply {
        parseHagc = enabled
        defaults.setParseHagcMetadata(enabled)
    }

    override fun experimentalSetCodecsToParseWithinGopSampleDependencies(flags: Int): ExtractorsFactory = apply {
        defaults.experimentalSetCodecsToParseWithinGopSampleDependencies(flags)
    }
}
