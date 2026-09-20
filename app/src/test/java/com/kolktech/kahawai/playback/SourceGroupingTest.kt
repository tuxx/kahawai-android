package com.kolktech.kahawai.playback

import com.kolktech.kahawai.data.network.dto.ItemSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SourceGroupingTest {

    private fun source(
        sourceId: Int,
        part: Int = 1,
        parts: Int = 1,
        available: Boolean = true,
        hostName: String? = "living-room",
        moduleId: String = "mh_01",
    ) = ItemSource(
        sourceId = sourceId,
        moduleId = moduleId,
        collectionId = "movies",
        collectionItemId = "copy$sourceId",
        pathRel = "part$part.mkv",
        part = part,
        parts = parts,
        available = available,
        hostName = hostName,
    )

    /// The distinction the flat list destroyed: seven parts of one film read
    /// as one choice, seven encodes read as seven.
    @Test
    fun `parts of one work group into a single choice, in part order`() {
        val works = groupSources(
            listOf(
                source(1, part = 3, parts = 3),
                source(1, part = 1, parts = 3),
                source(1, part = 2, parts = 3),
            ),
        )

        assertEquals(1, works.size)
        assertEquals(listOf(1, 2, 3), works[0].parts.map { it.part })
        assertTrue(works[0].whole)
    }

    @Test
    fun `different source ids are alternatives`() {
        val works = groupSources(listOf(source(1), source(2), source(3)))

        assertEquals(listOf(1, 2, 3), works.map { it.sourceId })
        assertTrue(works.all { it.whole })
    }

    @Test
    fun `a work missing a part is not whole`() {
        val works = groupSources(listOf(source(1, part = 1, parts = 3), source(1, part = 2, parts = 3)))

        assertFalse(works[0].whole)
        assertEquals(3, works[0].expectedParts)
    }

    /// One unreachable part stalls the whole work partway through, so the
    /// work is only playable while every part is.
    @Test
    fun `a work is unavailable when any part is`() {
        val works = groupSources(
            listOf(
                source(1, part = 1, parts = 2, available = true),
                source(1, part = 2, parts = 2, available = false),
            ),
        )

        assertFalse(works[0].available)
    }

    @Test
    fun `location keeps the stable id visible alongside a display name`() {
        assertEquals("living-room (mh_01) · movies", groupSources(listOf(source(1))).first().location())
    }

    @Test
    fun `location falls back to the module id when the host has no name of its own`() {
        val works = groupSources(listOf(source(1, hostName = null)))
        assertEquals("mh_01 · movies", works.first().location())

        // A host whose display name IS its id should not print it twice.
        val same = groupSources(listOf(source(1, hostName = "mh_01")))
        assertEquals("mh_01 · movies", same.first().location())
    }
}
