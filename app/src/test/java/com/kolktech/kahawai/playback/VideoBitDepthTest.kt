package com.kolktech.kahawai.playback

import android.media.MediaCodecInfo.CodecProfileLevel as P
import org.junit.Assert.assertEquals
import org.junit.Test
import com.kolktech.kahawai.data.network.apiJson
import com.kolktech.kahawai.data.network.dto.VideoCap
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class VideoBitDepthTest {
    @Test fun `capability depth uses the hub wire spelling`() {
        val wire = apiJson.parseToJsonElement(apiJson.encodeToString(VideoCap(codec = "hevc", maxBitDepth = 8))).jsonObject
        assertEquals("8", wire["max_bit_depth"]?.jsonPrimitive?.content)
    }

    @Test fun `main-only HEVC never claims ten bits`() {
        assertEquals(8, decoderBitDepth("hevc", listOf(P.HEVCProfileMain)))
    }
    @Test fun `missing and vendor profiles fall back to eight`() {
        assertEquals(8, decoderBitDepth("hevc", emptyList()))
        assertEquals(8, decoderBitDepth("hevc", listOf(-1)))
    }
    @Test fun `ten bit profiles include HDR variants and multiple decoders`() {
        for (p in listOf(P.HEVCProfileMain10, P.HEVCProfileMain10HDR10, P.HEVCProfileMain10HDR10Plus)) {
            assertEquals(10, decoderBitDepth("hevc", listOf(P.HEVCProfileMain, p)))
        }
        assertEquals(10, decoderBitDepth("h264", listOf(P.AVCProfileHigh10)))
        assertEquals(8, decoderBitDepth("h264", listOf(P.AVCProfileHigh)))
        assertEquals(10, decoderBitDepth("vp9", listOf(P.VP9Profile2)))
        assertEquals(10, decoderBitDepth("av1", listOf(P.AV1ProfileMain10)))
        assertEquals(8, decoderBitDepth("av1", listOf(P.AV1ProfileMain8)))
    }
}
