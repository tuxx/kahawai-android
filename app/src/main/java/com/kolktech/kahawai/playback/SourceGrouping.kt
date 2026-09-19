package com.kolktech.kahawai.playback

import com.kolktech.kahawai.data.network.dto.ItemSource

/// One work: the file, or the ordered set of files, that a single choice
/// plays. Ports `groupSources` from web/src/domain/source.ts.
///
/// A flat source list made one film split across seven numbered parts
/// indistinguishable from seven alternative encodes — both read as "7
/// sources" in an order that means nothing. `sourceId` is what tells them
/// apart: rows sharing one are parts of a single work, in `part` order; rows
/// with different ones are alternatives to choose between.
data class SourceWork(
    val sourceId: Int,
    val parts: List<ItemSource>,
    /// A work missing a part cannot play to the end, and calling it a
    /// source hides that completely.
    val whole: Boolean,
) {
    /// The part that describes the work: its streams, its location, its size.
    val first: ItemSource? get() = parts.firstOrNull()

    /// How many parts the work says it should have, which is not always how
    /// many arrived.
    val expectedParts: Int get() = first?.parts ?: parts.size

    /// Playable only while every part is reachable — one offline mediahost
    /// is enough to stall partway through.
    val available: Boolean get() = parts.isNotEmpty() && parts.all { it.available }
}

/// Group an item's sources into the works a viewer chooses between. Source
/// ids are opaque and stable only within one response — group on them, never
/// store them.
fun groupSources(sources: List<ItemSource>): List<SourceWork> =
    sources
        .groupBy { it.sourceId }
        .map { (id, parts) ->
            val ordered = parts.sortedBy { it.part }
            SourceWork(
                sourceId = id,
                parts = ordered,
                whole = ordered.size == (ordered.firstOrNull()?.parts ?: ordered.size),
            )
        }
        .sortedBy { it.sourceId }

/// "living-room (mh_01) · movies" — the host a work lives on and the
/// collection inside it. Two mediahosts can share a display name, so the
/// stable id stays visible when they differ. Ports `sourceLocation`.
fun SourceWork.location(): String {
    val source = first ?: return ""
    val host = source.moduleId
    val name = source.hostName
        ?.takeIf { it.isNotBlank() && it != host }
        ?.let { "$it ($host)" }
        ?: host
    return listOf(name, source.collectionId).filter { it.isNotBlank() }.joinToString(" · ")
}
