package com.kolktech.kahawai.playback

import org.junit.Assert.assertEquals
import org.junit.Test

class PlaybackBufferBudgetTest {
    private val mib = 1024 * 1024

    @Test
    fun `a 192 MiB heap gives video a 64 MiB loading target`() {
        assertEquals(64 * mib, playbackBufferTarget(192L * mib, 138 * mib))
    }

    @Test
    fun `audio-only and local targets are never increased`() {
        assertEquals(13 * mib, playbackBufferTarget(192L * mib, 13 * mib))
        assertEquals(20 * mib, playbackBufferTarget(192L * mib, 20 * mib))
    }

    @Test
    fun `the budget follows the ordinary heap and does not request a large heap`() {
        assertEquals(32 * mib, playbackBufferTarget(96L * mib, 138 * mib))
        assertEquals(128 * mib, playbackBufferTarget(384L * mib, 138 * mib))
        assertEquals(138 * mib, playbackBufferTarget(512L * mib, 138 * mib))
    }
}
