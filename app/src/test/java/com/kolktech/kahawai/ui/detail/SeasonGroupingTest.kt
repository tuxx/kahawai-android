package com.kolktech.kahawai.ui.detail

import com.kolktech.kahawai.data.network.dto.Item
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SeasonGroupingTest {
    private fun episode(id: String, season: Int?) =
        Item(id = id, kind = "episode", title = id, libraryId = "lib1", season = season)

    @Test
    fun `a multi-season show groups its episodes per season in hub order`() {
        val groups = groupSeasons(
            listOf(episode("s0e1", 0), episode("s1e1", 1), episode("s1e2", 1), episode("s2e1", 2)),
        )

        assertEquals(listOf(0, 1, 2), groups.map { it.first })
        assertEquals(listOf("s1e1", "s1e2"), groups[1].second.map { it.id })
    }

    @Test
    fun `episodes with no season number get a group of their own`() {
        val groups = groupSeasons(listOf(episode("s1e1", 1), episode("e11", null)))

        assertEquals(listOf(1, null), groups.map { it.first })
    }

    @Test
    fun `a single season stays a flat list`() {
        assertTrue(groupSeasons(listOf(episode("s1e1", 1), episode("s1e2", 1))).isEmpty())
        assertTrue(groupSeasons(listOf(episode("e1", null), episode("e2", null))).isEmpty())
    }

    @Test
    fun `album tracks are never grouped`() {
        val track = Item(id = "t1", kind = "track", title = "t1", libraryId = "lib1")

        assertTrue(groupSeasons(listOf(track, track.copy(id = "t2"))).isEmpty())
    }
}
