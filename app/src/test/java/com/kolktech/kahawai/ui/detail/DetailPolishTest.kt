package com.kolktech.kahawai.ui.detail

import com.kolktech.kahawai.data.network.dto.Chapter
import com.kolktech.kahawai.data.network.dto.Item
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DetailPolishTest {
    private fun episode(id: String, season: Int? = 1, played: Boolean = false, resumeMs: Long? = null) =
        Item(id = id, kind = "episode", title = id, libraryId = "lib1", season = season, played = played, resumePositionMs = resumeMs)

    @Test
    fun `next up is the episode left partway through`() {
        val next = nextUpEpisode(listOf(episode("e1", played = true), episode("e2"), episode("e3", resumeMs = 60_000)))

        assertEquals("e3", next?.id)
    }

    @Test
    fun `next up follows the furthest watched episode`() {
        val next = nextUpEpisode(listOf(episode("e1"), episode("e2", played = true), episode("e3"), episode("e4")))

        assertEquals("e3", next?.id)
    }

    @Test
    fun `next up wraps back to a skipped episode once the end is watched`() {
        val next = nextUpEpisode(listOf(episode("e1"), episode("e2", played = true)))

        assertEquals("e1", next?.id)
    }

    @Test
    fun `nothing is next once every episode is watched`() {
        assertNull(nextUpEpisode(listOf(episode("e1", played = true), episode("e2", played = true))))
    }

    @Test
    fun `specials are next only where there is nothing else`() {
        assertEquals("s1e1", nextUpEpisode(listOf(episode("s0e1", season = 0), episode("s1e1")))?.id)
        assertEquals("s0e1", nextUpEpisode(listOf(episode("s0e1", season = 0)))?.id)
    }

    @Test
    fun `numbered or timestamp chapters say nothing`() {
        val generic = listOf("Chapter 01", "chapter 2", "Ch. 3", "04", "00:12:30.000", "", null)

        assertFalse(hasNamedChapters(generic.mapIndexed { index, title -> Chapter(index * 1000L, title = title) }))
    }

    @Test
    fun `one real chapter name is worth showing`() {
        assertTrue(hasNamedChapters(listOf(Chapter(0, title = "Chapter 1"), Chapter(1000, title = "The Heist"))))
    }

    @Test
    fun `initials take the first and last name`() {
        assertEquals("JD", initials("Jane Q. Doe"))
        assertEquals("C", initials("Cher"))
        assertEquals("", initials("  "))
    }

    @Test
    fun `episode code reads like the player title`() {
        assertEquals("S2:E5", episodeCode(episode("x", season = 2).copy(episode = 5)))
        assertEquals("E5", episodeCode(episode("x", season = null).copy(episode = 5)))
        assertNull(episodeCode(episode("x")))
    }
}
