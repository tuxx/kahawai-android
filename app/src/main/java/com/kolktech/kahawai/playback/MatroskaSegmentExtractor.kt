@file:OptIn(androidx.media3.common.util.UnstableApi::class)

package com.kolktech.kahawai.playback

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.extractor.Extractor
import androidx.media3.extractor.ExtractorInput
import androidx.media3.extractor.ForwardingExtractorInput
import androidx.media3.extractor.PositionHolder
import androidx.media3.extractor.mkv.MatroskaExtractor
import androidx.media3.extractor.text.SubtitleParser
import java.io.EOFException

/// Preserve Media3's demuxer, cue seeks and final sample flushing, but give
/// it EOF at a finite Segment's end. Some files contain zero padding after
/// that end; Media3 otherwise tries to parse it as another EBML element.
/// The source and its HTTP byte ranges remain unchanged.
/// https://github.com/androidx/media/blob/1.11.1/libraries/extractor/src/main/java/androidx/media3/extractor/mkv/MatroskaExtractor.java
internal class MatroskaSegmentExtractor(
    subtitleParserFactory: SubtitleParser.Factory,
    flags: Int,
) : Extractor {
    private var segmentEnd: Long? = null
    private val delegate = object : MatroskaExtractor(subtitleParserFactory, flags) {
        override fun startMasterElement(id: Int, contentPosition: Long, contentSize: Long) {
            super.startMasterElement(id, contentPosition, contentSize)
            if (id == 0x18538067) segmentEnd = finiteSegmentEnd(contentPosition, contentSize)
        }
    }

    override fun sniff(input: ExtractorInput) = delegate.sniff(input)
    override fun init(output: androidx.media3.extractor.ExtractorOutput) = delegate.init(output)
    override fun seek(position: Long, timeUs: Long) = delegate.seek(position, timeUs)
    override fun release() = delegate.release()
    override fun getUnderlyingImplementation(): Extractor = delegate

    override fun read(input: ExtractorInput, seekPosition: PositionHolder): Int =
        delegate.read(SegmentExtractorInput(input) { segmentEnd }, seekPosition)
}

internal fun finiteSegmentEnd(position: Long, size: Long): Long? {
    // All-one VINT sizes denote an unknown length. Conservatively leave
    // these unbounded even if an overlong encoding could denote a finite
    // value: the protected callback does not expose the VINT width.
    if (position < 0 || size < 0 || size > Long.MAX_VALUE - position) return null
    if ((1..8).any { size == (1L shl (7 * it)) - 1 }) return null
    return position + size
}

/// The boundary can become known during the same read() that parses the
/// Segment header, and survives HTTP reopens and cue/user seeks.
internal class SegmentExtractorInput(
    input: ExtractorInput,
    private val end: () -> Long?,
) : ForwardingExtractorInput(input) {
    private fun available(position: Long, length: Int): Int =
        end()?.let { (it - position).coerceIn(0, length.toLong()).toInt() } ?: length

    private fun canReadFully(position: Long, length: Int, allowEndOfInput: Boolean): Boolean {
        val remaining = available(position, length)
        if (remaining == length) return true
        if (remaining == 0 && allowEndOfInput) return false
        throw EOFException("Read exceeds declared Matroska Segment end")
    }

    override fun getLength(): Long {
        val boundary = end() ?: return super.getLength()
        val physical = super.getLength()
        return if (physical == C.LENGTH_UNSET.toLong()) boundary else minOf(physical, boundary)
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        val count = available(position, length)
        return if (length > 0 && count == 0) C.RESULT_END_OF_INPUT else super.read(buffer, offset, count)
    }

    override fun readFully(target: ByteArray, offset: Int, length: Int, allowEndOfInput: Boolean): Boolean =
        canReadFully(position, length, allowEndOfInput) && super.readFully(target, offset, length, allowEndOfInput)

    override fun readFully(target: ByteArray, offset: Int, length: Int) {
        readFully(target, offset, length, false)
    }

    override fun skip(length: Int): Int {
        val count = available(position, length)
        return if (length > 0 && count == 0) C.RESULT_END_OF_INPUT else super.skip(count)
    }

    override fun skipFully(length: Int, allowEndOfInput: Boolean): Boolean =
        canReadFully(position, length, allowEndOfInput) && super.skipFully(length, allowEndOfInput)

    override fun skipFully(length: Int) {
        skipFully(length, false)
    }

    override fun peek(target: ByteArray, offset: Int, length: Int): Int {
        val count = available(peekPosition, length)
        return if (length > 0 && count == 0) C.RESULT_END_OF_INPUT else super.peek(target, offset, count)
    }

    override fun peekFully(target: ByteArray, offset: Int, length: Int, allowEndOfInput: Boolean): Boolean =
        canReadFully(peekPosition, length, allowEndOfInput) && super.peekFully(target, offset, length, allowEndOfInput)

    override fun peekFully(target: ByteArray, offset: Int, length: Int) {
        peekFully(target, offset, length, false)
    }

    override fun advancePeekPosition(length: Int, allowEndOfInput: Boolean): Boolean =
        canReadFully(peekPosition, length, allowEndOfInput) && super.advancePeekPosition(length, allowEndOfInput)

    override fun advancePeekPosition(length: Int) {
        advancePeekPosition(length, false)
    }
}
