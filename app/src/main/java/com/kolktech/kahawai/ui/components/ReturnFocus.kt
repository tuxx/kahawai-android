package com.kolktech.kahawai.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.focus.FocusRequester

/// Puts D-pad focus back on the card that was opened, when back returns to
/// a grid. A screen under another one in the back stack loses its
/// composition, so without this, focus on return goes to whichever card
/// asks for it first (or, when that one has scrolled away, to the top
/// bar's first button).
///
/// The opened card's key is saved with the back-stack entry. The "already
/// restored" flag is not, so it resets each time the screen comes back,
/// and the card asks for focus once per return rather than every time it
/// scrolls back into view.
@Stable
class ReturnFocus internal constructor(private val opened: MutableState<String?>) {
    private var restored by mutableStateOf(false)
    private val requester = FocusRequester()

    /// Whether a card was opened from here. While one was, the grid's own
    /// first-card focus stays out of the way.
    val hasTarget: Boolean get() = opened.value != null

    fun onOpen(key: String) {
        opened.value = key
    }

    /// The requester for the card under [key], when it's the one to
    /// return to and focus hasn't gone back to it yet.
    fun requesterFor(key: String): FocusRequester? = requester.takeIf { !restored && opened.value == key }

    fun onFocused(key: String) {
        if (key == opened.value) restored = true
    }
}

@Composable
fun rememberReturnFocus(): ReturnFocus {
    val opened = rememberSaveable { mutableStateOf<String?>(null) }
    return remember(opened) { ReturnFocus(opened) }
}
