package com.kolktech.kahawai.playback

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionRecoveryTest {
    @Test
    fun `an unplayable source does not open a replacement session`() {
        assertFalse(SessionRecovery().tryBegin())
    }

    @Test
    fun `replacement failures before READY cannot keep reopening sessions`() {
        val recovery = SessionRecovery()
        recovery.onReady()
        assertTrue(recovery.tryBegin())
        repeat(100) { assertFalse(recovery.tryBegin()) }
    }

    @Test
    fun `a later session loss gets a retry after recovered playback reaches READY`() {
        val recovery = SessionRecovery()
        recovery.onReady()
        assertTrue(recovery.tryBegin())
        recovery.onReady()
        assertTrue(recovery.tryBegin())
        assertFalse(recovery.tryBegin())
    }
}
