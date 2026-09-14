package com.agoro.tv

import com.agoro.tv.data.Episode
import com.agoro.tv.data.mergeEpisodeCopies
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The provider lists one show once per pack, and the packs are not the same
 * content. Measured against a live panel: of eighteen duplicated series, six
 * disagreed about how many episodes they carried, and the highest rung was the
 * fullest copy in only two of those six.
 *
 * "Yellowjackets" is the case that decides the design. The copy the fold keeps
 * carries 19 episodes and the copy it folds away carries 29 — so a fold that
 * answered "why do I see duplicates" by discarding the others would have
 * hidden ten episodes to do it.
 */
class EpisodeMergeTest {

    private fun ep(season: Int, number: Int, from: String) = Episode(
        id = "$from:$season-$number", title = "E$number", season = season,
        episodeNum = number, url = "http://x/$from/$season/$number",
    )

    @Test
    fun `a copy missing a season gets it from the other`() {
        val kept = listOf(ep(1, 1, "4k"), ep(1, 2, "4k"))
        val sibling = listOf(ep(1, 1, "en"), ep(1, 2, "en"), ep(2, 1, "en"), ep(2, 2, "en"))
        val out = mergeEpisodeCopies(listOf(kept, sibling))
        assertEquals(4, out.size)
        assertEquals(listOf(1, 1, 2, 2), out.map { it.season })
    }

    /**
     * Keyed on season and number, never on the panel's stream id — two copies
     * describe the SAME episode with two different ids, and keying on the id
     * would turn a two-season show listed three times into six seasons of
     * nonsense.
     */
    @Test
    fun `the same episode from two copies is one row`() {
        val out = mergeEpisodeCopies(
            listOf(listOf(ep(1, 1, "a")), listOf(ep(1, 1, "b")), listOf(ep(1, 1, "c"))),
        )
        assertEquals(1, out.size)
    }

    /** And the copy whose card the viewer is looking at is the one they get. */
    @Test
    fun `the kept copy wins any episode both carry`() {
        val out = mergeEpisodeCopies(
            listOf(listOf(ep(1, 1, "kept")), listOf(ep(1, 1, "other"), ep(1, 2, "other"))),
        )
        assertEquals("kept:1-1", out.first().id)
        assertEquals("other:1-2", out.last().id)
    }

    /** A union assembled from partial lists has no order of its own. */
    @Test
    fun `the union comes back in season and episode order`() {
        val out = mergeEpisodeCopies(
            listOf(listOf(ep(2, 2, "a"), ep(1, 3, "a")), listOf(ep(1, 1, "b"), ep(2, 1, "b"))),
        )
        assertEquals(
            listOf(1 to 1, 1 to 3, 2 to 1, 2 to 2),
            out.map { it.season to it.episodeNum },
        )
    }

    /** One copy is the overwhelming case and must not pay for any of this. */
    @Test
    fun `a single copy is handed straight back`() {
        val one = listOf(ep(1, 2, "a"), ep(1, 1, "a"))
        assertEquals(one, mergeEpisodeCopies(listOf(one)))
    }
}
