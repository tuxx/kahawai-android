package com.kolktech.kahawai.playback

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionKeepaliveTest {

    private val interval = 10_000L
    private val limit = 30 * 60_000L

    private fun keepalive() = SessionKeepalive(interval, limit)

    @Test
    fun `a moving position always pings`() {
        val k = keepalive()
        var position = 0L
        repeat(500) {
            position += interval
            assertTrue(k.shouldPing(position))
        }
    }

    /// The case that lost twelve minutes of progress: paused longer than the
    /// hub's 90s idle timeout. The ping has to keep going, or the session is
    /// reaped out from under a viewer who is still sitting there.
    @Test
    fun `a paused position keeps pinging well past the hub's idle timeout`() {
        val k = keepalive()
        // 90s is the reaper's window; check a pause many times longer.
        repeat(60) { assertTrue(k.shouldPing(4_053_148L)) }
    }

    /// But not forever — a viewer who walked away must not hold a
    /// transcoder slot all night.
    @Test
    fun `pinging stops once nothing has moved for the idle limit`() {
        val k = keepalive()
        // The first call pings because the position changed; every one after
        // adds a whole interval to the stall, so the limit is reached one
        // tick later than the bare division suggests.
        val ticks = (limit / interval).toInt()
        repeat(ticks) { assertTrue(k.shouldPing(1_000L)) }
        assertFalse(k.shouldPing(1_000L))
        assertFalse(k.shouldPing(1_000L))
    }

    @Test
    fun `coming back resumes the ping`() {
        val k = keepalive()
        repeat((limit / interval).toInt() + 5) { k.shouldPing(1_000L) }
        assertFalse(k.shouldPing(1_000L))

        // The viewer pressed play again.
        assertTrue(k.shouldPing(2_000L))
        assertTrue(k.shouldPing(3_000L))
    }
}
