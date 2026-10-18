package com.kolktech.kahawai.ui.player.subtitle

import java.util.TreeMap

internal data class SubtitlePng(val x: Int, val y: Int, val bytes: ByteArray)
internal data class ImageSubtitleCue(
    val startMs: Long,
    val compWidth: Int,
    val compHeight: Int,
    val objects: List<SubtitlePng>,
) {
    val compressedBytes: Long get() = objects.sumOf { it.bytes.size.toLong() }

    fun sameContent(other: ImageSubtitleCue): Boolean =
        startMs == other.startMs && compWidth == other.compWidth && compHeight == other.compHeight &&
            objects.size == other.objects.size && objects.zip(other.objects).all { (a, b) ->
                a.x == b.x && a.y == b.y && a.bytes.contentEquals(b.bytes)
            }
}

internal data class ImageSubtitleWindow(val current: ImageSubtitleCue?, val upcoming: ImageSubtitleCue?)
internal data class ImageSubtitleStorage(val cues: Int, val compressedBytes: Long)

/**
 * Keep the entire compressed timeline: the server can send all cues in a burst,
 * and both early playback and backward seeks still need them. Re-decoding a PNG
 * is local work; re-fetching the timeline would depend on the server and network.
 * Only the current and next visible cue need decoded pixels. The upcoming cue
 * is prepared off the presentation thread before its timestamp is reached.
 */
internal class ImageSubtitleTimeline {
    private val cues = TreeMap<Long, ImageSubtitleCue>()
    private var compressedBytes = 0L

    @Synchronized
    fun put(cue: ImageSubtitleCue) {
        val old = cues[cue.startMs]
        // A reconnect replays the backlog. Keep identity and the decoded cache
        // when bytes match; the last record wins for corrections at one time.
        if (old?.sameContent(cue) == true) return
        cues[cue.startMs] = cue
        compressedBytes += cue.compressedBytes - (old?.compressedBytes ?: 0)
    }

    @Synchronized
    fun currentAt(positionMs: Long): ImageSubtitleCue? = cues.floorEntry(positionMs)?.value

    @Synchronized
    fun windowAt(positionMs: Long): ImageSubtitleWindow = ImageSubtitleWindow(
        current = cues.floorEntry(positionMs)?.value,
        upcoming = cues.tailMap(positionMs, false).values.firstOrNull { it.objects.isNotEmpty() },
    )

    @Synchronized
    fun storage(): ImageSubtitleStorage = ImageSubtitleStorage(cues.size, compressedBytes)
}

/** Single decoder worker owns this cache; UI frames may still reference the previous current cue. */
internal class DecodedSubtitleWindow<T>(private val decode: (ImageSubtitleCue) -> T) {
    private val decoded = mutableMapOf<ImageSubtitleCue, T>()
    val size: Int get() = decoded.size
    fun values(): Collection<T> = decoded.values

    fun update(window: ImageSubtitleWindow): T? {
        val wanted = listOfNotNull(window.current, window.upcoming)
        decoded.keys.retainAll(wanted.toSet())
        for (cue in wanted) decoded.getOrPut(cue) { decode(cue) }
        return window.current?.let { decoded[it] }
    }
}
