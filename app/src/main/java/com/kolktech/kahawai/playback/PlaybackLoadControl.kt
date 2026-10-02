@file:OptIn(androidx.media3.common.util.UnstableApi::class)

package com.kolktech.kahawai.playback

import androidx.annotation.OptIn

import androidx.media3.common.C
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.LoadControl
import androidx.media3.exoplayer.trackselection.ExoTrackSelection
import androidx.media3.exoplayer.upstream.DefaultAllocator

internal class PlaybackLoadControl(
    private val maxHeapBytes: Long,
    allocator: DefaultAllocator,
) : DefaultLoadControl(
    allocator,
    /* minBufferMs = */ 10_000,
    /* minBufferForLocalPlaybackMs = */ 10_000,
    /* maxBufferMs = */ DEFAULT_MAX_BUFFER_MS,
    /* maxBufferForLocalPlaybackMs = */ DEFAULT_MAX_BUFFER_MS,
    /* bufferForPlaybackMs = */ 1_000,
    /* bufferForPlaybackForLocalPlaybackMs = */ 1_000,
    /* bufferForPlaybackAfterRebufferMs = */ 1_500,
    /* bufferForPlaybackAfterRebufferForLocalPlaybackMs = */ 1_500,
    /* targetBufferBytes = */ C.LENGTH_UNSET,
    /* prioritizeTimeOverSizeThresholds = */ false,
    /* prioritizeTimeOverSizeThresholdsForLocalPlayback = */ false,
    /* backBufferDurationMs = */ 0,
    /* retainBackBufferFromKeyframe = */ false,
    /* playerTargetBufferBytes = */ emptyMap(),
) {
    @Volatile
    var targetBytes: Int = playbackBufferTarget(maxHeapBytes, DEFAULT_MIN_BUFFER_SIZE)
        private set

    override fun calculateTargetBufferBytes(
        parameters: LoadControl.Parameters,
        trackSelectionArray: Array<out ExoTrackSelection?>,
    ): Int {
        // Media3 derives the target from the selected tracks. Cap it rather
        // than replacing its smaller audio-only and local-playback targets.
        // "Only used when targetBufferBytes is LENGTH_UNSET."
        // https://github.com/androidx/media/blob/1.11.1/libraries/exoplayer/src/main/java/androidx/media3/exoplayer/DefaultLoadControl.java
        return playbackBufferTarget(maxHeapBytes, super.calculateTargetBufferBytes(parameters, trackSelectionArray))
            .also { targetBytes = it }
    }
}
