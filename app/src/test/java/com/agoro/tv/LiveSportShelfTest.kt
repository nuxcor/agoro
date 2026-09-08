package com.agoro.tv

import com.agoro.tv.data.LiveChannel
import com.agoro.tv.data.SportsEvent
import com.agoro.tv.ui.screens.liveSportShelf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What reaches Home's live-sport shelf.
 *
 * The Sport tab's fixture pipeline needs a real provider to stand up, so this
 * is the half that can be tested without one — and it is the half with the
 * judgements in it: what counts as under way, what has a stream behind it, and
 * what is the same match listed twice.
 */
class LiveSportShelfTest {

    private val now = 1_700_000_000_000L

    private fun slot(id: Int) = LiveChannel(
        id = "live:$id",
        name = "PPV $id",
        logo = null,
        url = "http://x/$id.ts",
        categoryId = "ppv",
        xtreamId = id,
    )

    private fun fixture(
        streamId: Int,
        home: String = "Arsenal",
        away: String = "Chelsea",
        startMs: Long? = now - 30 * 60_000,
    ) = SportsEvent(
        streamId = streamId,
        league = "Premier League",
        home = home,
        away = away,
        startMs = startMs,
        live = false,
    )

    @Test
    fun `a kicked-off fixture with a slot behind it makes a card`() {
        val out = liveSportShelf(listOf(fixture(1)), listOf(slot(1)), now)
        assertEquals(1, out.size)
        assertEquals("Arsenal v Chelsea", out.single().event.title)
        assertEquals("live:1", out.single().slot.id)
    }

    @Test
    fun `a fixture that has not started is not on now`() {
        val later = fixture(1, startMs = now + 45 * 60_000)
        assertTrue(liveSportShelf(listOf(later), listOf(slot(1)), now).isEmpty())
    }

    @Test
    fun `a fixture whose slot is not in this bundle draws nothing`() {
        // The slot is what plays. A card with no stream behind it is a button
        // that opens a black screen.
        assertTrue(liveSportShelf(listOf(fixture(99)), listOf(slot(1)), now).isEmpty())
    }

    @Test
    fun `the same match on several feeds is one card`() {
        // Packs list a match once per feed; six copies is not a shelf.
        val dupes = listOf(fixture(1), fixture(1), fixture(1))
        assertEquals(1, liveSportShelf(dupes, listOf(slot(1)), now).size)
    }

    @Test
    fun `distinct matches each keep a card`() {
        val out = liveSportShelf(
            listOf(fixture(1), fixture(2, home = "Liverpool", away = "Everton")),
            listOf(slot(1), slot(2)),
            now,
        )
        assertEquals(listOf("Arsenal v Chelsea", "Liverpool v Everton"), out.map { it.event.title })
    }

    @Test
    fun `the shelf is capped`() {
        val many = (1..40).map { fixture(it) }
        val slots = (1..40).map { slot(it) }
        assertEquals(20, liveSportShelf(many, slots, now).size)
    }

    @Test
    fun `no fixtures and no slots are both simply empty`() {
        assertTrue(liveSportShelf(null, listOf(slot(1)), now).isEmpty())
        assertTrue(liveSportShelf(emptyList(), listOf(slot(1)), now).isEmpty())
        assertTrue(liveSportShelf(listOf(fixture(1)), emptyList(), now).isEmpty())
    }

    @Test
    fun `the card's programme spans the match, and a timeless fixture gets none`() {
        val prog = liveSportShelf(listOf(fixture(1)), listOf(slot(1)), now).single().asProgram()!!
        assertEquals("Arsenal v Chelsea", prog.title)
        assertEquals("Premier League", prog.description)
        assertEquals(2 * 60 * 60 * 1000L, prog.endMs - prog.startMs)

        // No trusted kick-off means no progress bar rather than a made-up one.
        // isLive falls back to the parse-time flag for these.
        val timeless = SportsEvent(
            streamId = 2, league = "Boxing", home = "Fury", away = "Usyk",
            startMs = null, live = true,
        )
        val card = liveSportShelf(listOf(timeless), listOf(slot(2)), now).single()
        assertNull(card.asProgram())
    }
}
