package com.kolktech.kahawai.playback

/**
 * Stop media loading at one third of the actual Java heap or the smaller
 * track-derived target. Observed video playback used 183 MiB of a 192 MiB
 * heap; the default video/audio target alone was about 138 MiB. The remaining
 * two thirds cover app state, compressed subtitle timelines, allocation/GC
 * headroom and chunks already in flight (observed HLS chunks exceeded 30 MiB).
 *
 * Consumed media is rebuilt by downloading it again. Reducing buffered bytes
 * trades network interruption tolerance for allocation headroom at playback;
 * startup and rebuffer duration thresholds remain unchanged. A load target is
 * not a hard ceiling: an in-flight chunk can still exceed it.
 */
internal fun playbackBufferTarget(maxHeapBytes: Long, trackTargetBytes: Int): Int {
    require(maxHeapBytes > 0 && trackTargetBytes > 0)
    return minOf(trackTargetBytes.toLong(), (maxHeapBytes / 3).coerceAtLeast(1)).toInt()
}
