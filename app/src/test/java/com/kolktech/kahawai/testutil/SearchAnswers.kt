package com.kolktech.kahawai.testutil

/// [itemsPage] (a `CatalogueItems` body) as `GET /catalogue/search` answers
/// it for one library: every listing is that one endpoint now.
fun searchAnswer(library: String, itemsPage: String): String =
    """{"libraries":[{"library":{"id":"$library","name":"$library","media_type":"movies"},"items":$itemsPage}]}"""

/// Whether [path] is a `/catalogue/search` request scoped to [library].
fun isSearchOf(path: String?, library: String): Boolean =
    path != null && path.startsWith("/api/v1/catalogue/search?") && "libraries=$library" in path
