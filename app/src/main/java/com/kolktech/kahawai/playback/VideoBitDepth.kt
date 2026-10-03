package com.kolktech.kahawai.playback

import android.media.MediaCodecInfo.CodecProfileLevel

/** Bits per component supported by the advertised decoder profiles, independently of HDR display.
 * Android describes AV1 Main8/Main10 as "4:2:0 8-bit" / "4:2:0 10-bit":
 * https://developer.android.com/reference/android/media/MediaCodecInfo.CodecProfileLevel
 * An incomplete profile inventory must not turn MIME support into unrestricted depth.
 * VP9 profiles 2/3 cover 10 or 12 bits; advertise only 10 without a separate 12-bit probe.
 */
internal fun decoderBitDepth(codec: String, profiles: List<Int>): Int {
    val highDepth = when (codec) {
        "h264" -> setOf(CodecProfileLevel.AVCProfileHigh10, CodecProfileLevel.AVCProfileHigh422, CodecProfileLevel.AVCProfileHigh444)
        "hevc" -> setOf(CodecProfileLevel.HEVCProfileMain10, CodecProfileLevel.HEVCProfileMain10HDR10, CodecProfileLevel.HEVCProfileMain10HDR10Plus)
        "vp9" -> setOf(CodecProfileLevel.VP9Profile2, CodecProfileLevel.VP9Profile3, CodecProfileLevel.VP9Profile2HDR, CodecProfileLevel.VP9Profile3HDR, CodecProfileLevel.VP9Profile2HDR10Plus, CodecProfileLevel.VP9Profile3HDR10Plus)
        "av1" -> setOf(CodecProfileLevel.AV1ProfileMain10, CodecProfileLevel.AV1ProfileMain10HDR10, CodecProfileLevel.AV1ProfileMain10HDR10Plus)
        else -> emptySet()
    }
    return if (profiles.any { it in highDepth }) 10 else 8
}
