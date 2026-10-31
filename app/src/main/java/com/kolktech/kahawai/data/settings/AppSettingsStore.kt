package com.kolktech.kahawai.data.settings

import android.content.Context

/// Local, device-only display/player preferences. Distinct from per-user
/// prefs stored on the hub (`/api/v1/prefs`) — these never leave the device.
class AppSettingsStore(context: Context) {
    private val prefs = context.getSharedPreferences("kahawai_app_settings", Context.MODE_PRIVATE)

    var reserveNotchSpace: Boolean
        get() = prefs.getBoolean(KEY_RESERVE_NOTCH_SPACE, true)
        set(value) = prefs.edit().putBoolean(KEY_RESERVE_NOTCH_SPACE, value).apply()

    /// BCP-47 tag for the app's own display language. Only "en" is
    /// selectable today; stored so a picker with more entries can be
    /// dropped in later without a migration.
    var appLanguage: String
        get() = prefs.getString(KEY_APP_LANGUAGE, "en") ?: "en"
        set(value) = prefs.edit().putString(KEY_APP_LANGUAGE, value).apply()

    /// Master switch — off disables every quick gesture below regardless
    /// of their own individual toggle.
    var playerGesturesEnabled: Boolean
        get() = prefs.getBoolean(KEY_PLAYER_GESTURES_ENABLED, true)
        set(value) = prefs.edit().putBoolean(KEY_PLAYER_GESTURES_ENABLED, value).apply()

    var volumeBrightnessGestureEnabled: Boolean
        get() = prefs.getBoolean(KEY_VOLUME_BRIGHTNESS_GESTURE_ENABLED, true)
        set(value) = prefs.edit().putBoolean(KEY_VOLUME_BRIGHTNESS_GESTURE_ENABLED, value).apply()

    var seekGestureEnabled: Boolean
        get() = prefs.getBoolean(KEY_SEEK_GESTURE_ENABLED, true)
        set(value) = prefs.edit().putBoolean(KEY_SEEK_GESTURE_ENABLED, value).apply()

    var zoomGestureEnabled: Boolean
        get() = prefs.getBoolean(KEY_ZOOM_GESTURE_ENABLED, true)
        set(value) = prefs.edit().putBoolean(KEY_ZOOM_GESTURE_ENABLED, value).apply()

    var rememberBrightnessLevel: Boolean
        get() = prefs.getBoolean(KEY_REMEMBER_BRIGHTNESS_LEVEL, false)
        set(value) = prefs.edit().putBoolean(KEY_REMEMBER_BRIGHTNESS_LEVEL, value).apply()

    /// Only meaningful while [rememberBrightnessLevel] is on. -1 means
    /// "never saved yet" so the player falls back to the system level.
    var lastBrightnessLevel: Float
        get() = prefs.getFloat(KEY_LAST_BRIGHTNESS_LEVEL, -1f)
        set(value) = prefs.edit().putFloat(KEY_LAST_BRIGHTNESS_LEVEL, value).apply()

    var seekBackMs: Long
        get() = prefs.getLong(KEY_SEEK_BACK_MS, 5_000L)
        set(value) = prefs.edit().putLong(KEY_SEEK_BACK_MS, value).apply()

    var seekForwardMs: Long
        get() = prefs.getLong(KEY_SEEK_FORWARD_MS, 15_000L)
        set(value) = prefs.edit().putLong(KEY_SEEK_FORWARD_MS, value).apply()

    /// HUB-37. Off by default: skipping is a strong assumption about
    /// what the viewer wants during exactly the segment they might still
    /// be watching (a title sequence with a plot-relevant cold open,
    /// say), so it stays opt-in rather than applied the moment the hub
    /// finds a boundary. The web client offers no equivalent — it only
    /// ever shows the manual Skip button.
    var autoSkipIntrosOutros: Boolean
        get() = prefs.getBoolean(KEY_AUTO_SKIP_INTROS_OUTROS, false)
        set(value) = prefs.edit().putBoolean(KEY_AUTO_SKIP_INTROS_OUTROS, value).apply()

    /// The player's stats-for-nerds overlay, switched from its side panel.
    /// Kept across sessions: whoever turns it on is usually watching for
    /// something over more than one video.
    var statsForNerds: Boolean
        get() = prefs.getBoolean(KEY_STATS_FOR_NERDS, false)
        set(value) = prefs.edit().putBoolean(KEY_STATS_FOR_NERDS, value).apply()

    private companion object {
        const val KEY_RESERVE_NOTCH_SPACE = "reserve_notch_space"
        const val KEY_APP_LANGUAGE = "app_language"
        const val KEY_PLAYER_GESTURES_ENABLED = "player_gestures_enabled"
        const val KEY_VOLUME_BRIGHTNESS_GESTURE_ENABLED = "volume_brightness_gesture_enabled"
        const val KEY_SEEK_GESTURE_ENABLED = "seek_gesture_enabled"
        const val KEY_ZOOM_GESTURE_ENABLED = "zoom_gesture_enabled"
        const val KEY_REMEMBER_BRIGHTNESS_LEVEL = "remember_brightness_level"
        const val KEY_LAST_BRIGHTNESS_LEVEL = "last_brightness_level"
        const val KEY_SEEK_BACK_MS = "seek_back_ms"
        const val KEY_SEEK_FORWARD_MS = "seek_forward_ms"
        const val KEY_AUTO_SKIP_INTROS_OUTROS = "auto_skip_intros_outros"
        const val KEY_STATS_FOR_NERDS = "stats_for_nerds"
    }
}
