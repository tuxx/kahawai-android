package com.kolktech.kahawai.playback

/// Keeping a play session alive while the viewer is still there. Ported from
/// `keepSessionAlive` in web/src/domain/keepalive.ts.
///
/// The hub reaps a session after 90s with no fetch and no progress ping
/// (HUB-18). That is right for an abandoned session and wrong for a present
/// one: a player stops fetching far more often than it stops existing — a
/// pause with a full buffer reads no bytes at all. So the ping goes out
/// whether or not the playhead is moving.
///
/// Bounded, because the reaper is right about the case it was built for:
/// someone who paused and walked away must not hold a transcoder slot all
/// night. A position that never moves pings for [idleLimitMs] and then
/// stops — and starts again by itself if the viewer comes back, since a
/// moving position resets the count.
///
/// A class rather than a loop so the half-hour bound can be checked without
/// waiting half an hour (see SessionKeepaliveTest), the same reason the web
/// version takes its ping as a parameter.
internal class SessionKeepalive(
    private val intervalMs: Long,
    private val idleLimitMs: Long,
) {
    private var lastPositionMs: Long = Long.MIN_VALUE
    private var stalledMs: Long = 0

    /// One tick. True when this position should be reported.
    fun shouldPing(positionMs: Long): Boolean {
        if (positionMs == lastPositionMs) {
            stalledMs += intervalMs
            return stalledMs < idleLimitMs
        }
        lastPositionMs = positionMs
        stalledMs = 0
        return true
    }
}
