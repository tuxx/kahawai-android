@file:OptIn(UnstableApi::class)

package com.kolktech.kahawai.ui.player

import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.source.LoadEventInfo
import androidx.media3.exoplayer.source.MediaLoadData
import java.io.IOException

/// TEMPORARY diagnostics for the stall that lands ~33:06 into every
/// pipeline, after about 992 segments, regardless of where in the media
/// the pipeline started.
///
/// At the stall the player holds a complete VOD playlist (1350 segments,
/// ENDLIST, 45:15 timeline) and never requests segment 992. EventLogger
/// prints `loading true/false` and the renderer going not-ready, but not
/// the loader's own decisions, so it cannot distinguish the last two
/// possibilities:
///
///  * a load IS started for the next chunk and never completes (a hung
///    request would show as started with no matching completed/error),
///  * or no load is ever started, meaning the chunk source decided there
///    is nothing left to fetch.
///
/// Those need different fixes, so print every load with its URI and the
/// media range it covers. Remove once the cause is known.
internal const val LOAD_PROBE_TAG = "LoadProbe"

internal class LoadProbe : AnalyticsListener {

    private fun describe(info: LoadEventInfo, data: MediaLoadData): String {
        val name = info.uri.lastPathSegment ?: info.uri.toString()
        return "$name type=${data.dataType} track=${data.trackType} " +
            "media=[${data.mediaStartTimeMs}..${data.mediaEndTimeMs}] bytes=${info.bytesLoaded}"
    }

    override fun onLoadStarted(
        eventTime: AnalyticsListener.EventTime,
        loadEventInfo: LoadEventInfo,
        mediaLoadData: MediaLoadData,
    ) {
        Log.i(LOAD_PROBE_TAG, "started pos=${eventTime.currentPlaybackPositionMs} ${describe(loadEventInfo, mediaLoadData)}")
    }

    override fun onLoadCompleted(
        eventTime: AnalyticsListener.EventTime,
        loadEventInfo: LoadEventInfo,
        mediaLoadData: MediaLoadData,
    ) {
        Log.i(LOAD_PROBE_TAG, "completed pos=${eventTime.currentPlaybackPositionMs} ${describe(loadEventInfo, mediaLoadData)} ms=${loadEventInfo.loadDurationMs}")
    }

    override fun onLoadCanceled(
        eventTime: AnalyticsListener.EventTime,
        loadEventInfo: LoadEventInfo,
        mediaLoadData: MediaLoadData,
    ) {
        Log.w(LOAD_PROBE_TAG, "CANCELED pos=${eventTime.currentPlaybackPositionMs} ${describe(loadEventInfo, mediaLoadData)}")
    }

    override fun onLoadError(
        eventTime: AnalyticsListener.EventTime,
        loadEventInfo: LoadEventInfo,
        mediaLoadData: MediaLoadData,
        error: IOException,
        wasCanceled: Boolean,
    ) {
        Log.e(
            LOAD_PROBE_TAG,
            "ERROR pos=${eventTime.currentPlaybackPositionMs} ${describe(loadEventInfo, mediaLoadData)} " +
                "canceled=$wasCanceled ${error.javaClass.simpleName}: ${error.message}",
        )
    }
}
