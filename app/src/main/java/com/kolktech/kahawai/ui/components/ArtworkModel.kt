package com.kolktech.kahawai.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import coil3.request.ImageRequest

/// A hub artwork URL stays the same while the art behind it changes (the
/// hub keys its own cache off the resolved source), so the client cache
/// key carries the [source] the description names: it changes, and the
/// image reloads, only when new art actually arrives. Until then a return
/// to the screen is served from Coil's caches without a flash.
@Composable
fun rememberArtworkModel(url: String?, source: String): Any? {
    if (url == null) return null
    val context = LocalContext.current
    return remember(context, url, source) {
        val key = "$url#$source"
        ImageRequest.Builder(context)
            .data(url)
            .memoryCacheKey(key)
            .diskCacheKey(key)
            .build()
    }
}
