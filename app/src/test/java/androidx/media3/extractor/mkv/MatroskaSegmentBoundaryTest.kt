@file:OptIn(androidx.media3.common.util.UnstableApi::class)

package androidx.media3.extractor.mkv

import androidx.annotation.OptIn
import androidx.media3.extractor.DefaultExtractorInput
import androidx.media3.extractor.ExtractorInput
import com.kolktech.kahawai.playback.SegmentExtractorInput
import com.kolktech.kahawai.playback.finiteSegmentEnd
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.EOFException

/// Exercise the real Media3 EBML reader that threw on the SHIELD, rather
/// than replacing the parser with a mocked success/failure result.
class MatroskaSegmentBoundaryTest {
    private fun input(bytes: ByteArray, length: Long = bytes.size.toLong()): ExtractorInput {
        val stream = ByteArrayInputStream(bytes)
        return DefaultExtractorInput({ target, offset, count -> stream.read(target, offset, count) }, 0, length)
    }

    private fun parse(bytes: ByteArray, bounded: Boolean): Long {
        var end: Long? = null
        val raw = input(bytes)
        val source = if (bounded) SegmentExtractorInput(raw) { end } else raw
        val reader = DefaultEbmlReader()
        reader.init(object : EbmlProcessor {
            override fun getElementType(id: Int): Int =
                if (id == 0x18538067 || id == 0x1C53BB6B) EbmlProcessor.ELEMENT_TYPE_MASTER else EbmlProcessor.ELEMENT_TYPE_UNKNOWN
            override fun isLevel1Element(id: Int) = id == 0x1C53BB6B
            override fun startMasterElement(id: Int, contentPosition: Long, contentSize: Long) {
                if (id == 0x18538067) end = finiteSegmentEnd(contentPosition, contentSize)
            }
            override fun endMasterElement(id: Int) {}
            override fun integerElement(id: Int, value: Long) {}
            override fun floatElement(id: Int, value: Double) {}
            override fun stringElement(id: Int, value: String) {}
            override fun binaryElement(id: Int, size: Int, input: ExtractorInput) { throw AssertionError() }
        })
        while (reader.read(source)) {}
        return raw.position
    }

    // A finite Segment containing Cues with an empty Void element.
    private val valid = byteArrayOf(0x18, 0x53, 0x80.toByte(), 0x67, 0x87.toByte(), 0x1C, 0x53, 0xBB.toByte(), 0x6B, 0x82.toByte(), 0xEC.toByte(), 0x80.toByte())

    @Test
    fun `746 trailing zeros reproduce the original error and become clean EOF when bounded`() {
        val padded = valid + ByteArray(746)
        val error = assertThrows(IllegalStateException::class.java) { parse(padded, bounded = false) }
        assertEquals("No valid varint length mask found", error.message)
        assertEquals(valid.size.toLong(), parse(padded, bounded = true))
    }

    @Test
    fun `an ordinary finite segment still ends normally`() {
        assertEquals(valid.size.toLong(), parse(valid, bounded = true))
    }

    @Test
    fun `corruption inside the segment remains an error`() {
        val corrupt = valid.copyOf().also { it[10] = 0 }
        assertThrows(IllegalStateException::class.java) { parse(corrupt, bounded = true) }
    }

    @Test
    fun `truncated content remains an error`() {
        assertThrows(EOFException::class.java) { parse(valid.copyOf(valid.size - 1), bounded = true) }
    }

    @Test
    fun `unknown sizes and overflow do not invent a boundary`() {
        (1..8).forEach { assertNull(finiteSegmentEnd(52, (1L shl (7 * it)) - 1)) }
        assertNull(finiteSegmentEnd(52, -1))
        assertNull(finiteSegmentEnd(52, Long.MAX_VALUE))
        assertEquals(629493127L, finiteSegmentEnd(52, 629493075))
    }

    @Test
    fun `reads and peeks stop at boundary even with unknown HTTP length`() {
        val source = SegmentExtractorInput(input(byteArrayOf(1, 2, 3, 0, 0), -1)) { 3L }
        assertEquals(3L, source.length)
        assertEquals(3, source.peek(ByteArray(8), 0, 8))
        assertFalse(source.peekFully(ByteArray(1), 0, 1, true))
        source.resetPeekPosition()
        assertEquals(3, source.read(ByteArray(8), 0, 8))
        assertEquals(-1, source.read(ByteArray(1), 0, 1))
        assertEquals(-1, source.skip(1))
        assertFalse(source.readFully(ByteArray(1), 0, 1, true))
        assertThrows(EOFException::class.java) { source.readFully(ByteArray(1), 0, 1) }
    }

    @Test
    fun `partial mandatory reads cannot borrow bytes from outside the segment`() {
        val source = SegmentExtractorInput(input(ByteArray(8))) { 3L }
        assertThrows(EOFException::class.java) { source.readFully(ByteArray(4), 0, 4, true) }
        assertThrows(EOFException::class.java) { source.skipFully(4, true) }
        assertThrows(EOFException::class.java) { source.advancePeekPosition(4, true) }
    }
}
