package com.kolktech.kahawai.playback

/// A replacement session is not a successful recovery until the player
/// reaches READY. prepare() returns before source loading can fail again.
internal class SessionRecovery {
    private var reachedReady = false
    private var attempted = false

    fun onReady() {
        reachedReady = true
        attempted = false
    }

    fun tryBegin(): Boolean {
        if (!reachedReady || attempted) return false
        attempted = true
        return true
    }
}
