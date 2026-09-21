package com.agoro.tv

import com.agoro.tv.data.SportsEvent
import com.agoro.tv.ui.screens.FixtureLine
import com.agoro.tv.ui.screens.OTHER_FIXTURES
import com.agoro.tv.ui.screens.fixtureLines
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Sport tab's grouping, held by a test now that it is a function rather
 * than a block inside a composable.
 *
 * Two properties, and only one of them is obvious. Leagues come back in the
 * MANIFEST's order so the sports a viewer follows sit where they were last
 * time — that one is visible on screen. The other is not: every fixture must
 * appear exactly once, and the failure mode when it does not is a match that
 * silently is not drawn, with no count anywhere saying a row has gone.
 */
class FixtureLinesTest {

    private var nextId = 1

    private fun event(league: String, home: String = "Home", away: String = "Away") =
        SportsEvent(
            streamId = nextId++,
            league = league,
            home = home,
            away = away,
            startMs = null,
            live = false,
        )

    private fun headings(lines: List<FixtureLine>) =
        lines.filterIsInstance<FixtureLine.Header>().map { it.league }

    private fun fixtures(lines: List<FixtureLine>) =
        lines.filterIsInstance<FixtureLine.Fixture>().map { it.event }

    @Test
    fun `leagues come back in the manifest's order, not the fixture list's`() {
        val order = listOf("Premier League", "La Liga", "Serie A")
        // Deliberately the wrong way round on the way in.
        val lines = fixtureLines(
            listOf(event("Serie A"), event("Premier League"), event("La Liga")),
            order,
        )
        assertEquals(order, headings(lines))
    }

    @Test
    fun `a league with no fixtures gets no heading`() {
        val lines = fixtureLines(
            listOf(event("Premier League")),
            listOf("Premier League", "La Liga", "Serie A"),
        )
        assertEquals(listOf("Premier League"), headings(lines))
    }

    /**
     * The regression this whole catch-all exists for. A fixture whose
     * competition is in no heading used to fall through the loop and never be
     * drawn — "Antwerp vs Club Brugge" is a real match, and the fix that took
     * a wrong Champions League badge off it would have taken the match off the
     * screen entirely.
     */
    @Test
    fun `a fixture no heading claims lands under Other`() {
        val stray = event("", home = "Antwerp", away = "Club Brugge")
        val lines = fixtureLines(
            listOf(event("Premier League"), stray),
            listOf("Premier League"),
        )
        assertEquals(listOf("Premier League", OTHER_FIXTURES), headings(lines))
        assertTrue(stray in fixtures(lines))
    }

    @Test
    fun `Other is absent when every fixture is claimed`() {
        val lines = fixtureLines(
            listOf(event("Premier League"), event("La Liga")),
            listOf("Premier League", "La Liga"),
        )
        assertTrue(OTHER_FIXTURES !in headings(lines))
    }

    /**
     * The invariant. Not "nothing is lost" and not "nothing is doubled" — both
     * at once, because a catch-all that re-adds a claimed fixture is the same
     * bug wearing the opposite sign, and a LazyColumn keyed on the stream id
     * would then measure two items under one key and take the tab down.
     */
    @Test
    fun `every fixture appears exactly once`() {
        val all = listOf(
            event("Premier League"), event("Premier League"),
            event("La Liga"),
            event("Kabaddi"), event(""),
        )
        val lines = fixtureLines(all, listOf("Premier League", "La Liga", "Serie A"))
        val drawn = fixtures(lines)
        assertEquals(all.size, drawn.size)
        assertEquals(all.toSet(), drawn.toSet())
        assertEquals("a fixture was drawn twice", drawn.size, drawn.distinct().size)
    }

    /** Every heading must own at least one row, or it is a dead D-pad press. */
    @Test
    fun `no heading is left standing on its own`() {
        val lines = fixtureLines(
            listOf(event("Premier League"), event("")),
            listOf("Premier League", "La Liga"),
        )
        var lastWasHeader = false
        for (line in lines) {
            if (line is FixtureLine.Header) {
                assertTrue("two headings in a row: ${headings(lines)}", !lastWasHeader)
                lastWasHeader = true
            } else lastWasHeader = false
        }
        assertTrue("the list ends on a heading", lines.lastOrNull() is FixtureLine.Fixture)
    }

    /**
     * A league the manifest happens to call "Other" must not collide with the
     * catch-all. Two Header("Other") items measure under one LazyColumn slot
     * id, which throws out of subcompose — the crash the category strip's key
     * scheme was rewritten to avoid.
     */
    @Test
    fun `a league named Other cannot collide with the catch-all`() {
        val claimed = event(OTHER_FIXTURES)
        val stray = event("")
        val lines = fixtureLines(listOf(claimed, stray), listOf(OTHER_FIXTURES))
        val keys = lines.map { line ->
            when (line) {
                is FixtureLine.Header -> "h:${line.league}"
                is FixtureLine.Fixture -> "f:${line.league}:${line.event.streamId}"
            }
        }
        assertEquals("duplicate keys: $keys", keys.size, keys.toSet().size)
        assertEquals(1, headings(lines).count { it == OTHER_FIXTURES })
        // And neither fixture is lost to the guard.
        assertEquals(setOf(claimed, stray), fixtures(lines).toSet())
    }

    /**
     * Two fixtures identical in every field but the stream id are two
     * fixtures. Claiming by value would fold them into one and silently drop a
     * row — the trap the codebase records for LiveChannel.
     */
    @Test
    fun `fixtures equal in every field but the id are both drawn`() {
        val a = SportsEvent(1, "Premier League", "Home", "Away", null, false)
        val b = SportsEvent(2, "Premier League", "Home", "Away", null, false)
        val lines = fixtureLines(listOf(a, b), listOf("Premier League"))
        assertEquals(2, fixtures(lines).size)
    }

    @Test
    fun `no fixtures at all produces no lines`() {
        assertTrue(fixtureLines(emptyList(), listOf("Premier League")).isEmpty())
    }

    /**
     * The keys the LazyColumn builds from these lines must be unique — the
     * same rule the guide's category strip is held to, and for the same
     * reason: two items under one slot id throws out of subcompose.
     */
    @Test
    fun `the list's own keys are unique`() {
        val lines = fixtureLines(
            listOf(event("Premier League"), event("Premier League"), event("")),
            listOf("Premier League"),
        )
        val keys = lines.map { line ->
            when (line) {
                is FixtureLine.Header -> "h:${line.league}"
                is FixtureLine.Fixture -> "f:${line.league}:${line.event.streamId}"
            }
        }
        assertEquals("duplicate keys: $keys", keys.size, keys.toSet().size)
    }
}
