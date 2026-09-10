package com.agoro.tv

import com.agoro.tv.data.LiveChannel
import com.agoro.tv.data.SportsEvent
import com.agoro.tv.data.SportsParser
import com.agoro.tv.ui.screens.fixtureHero
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

    private fun slot(id: Int, name: String = "PPV $id") = LiveChannel(
        id = "live:$id",
        name = name,
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
        league: String = "Premier League",
    ) = SportsEvent(
        streamId = streamId,
        league = league,
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
    fun `a finished match leaves the shelf`() {
        // The bug this exists for: isLive has no upper bound, so a match that
        // kicked off stayed "live" until the schedule forgot it — Getafe v
        // Celta Vigo sat on Home hours after full time.
        val over = fixture(1).copy(state = "post")
        assertTrue(liveSportShelf(listOf(over), listOf(slot(1)), now).isEmpty())
    }

    @Test
    fun `ESPN's verdict beats the clock in both directions`() {
        // Says it is on, however long ago it started.
        val long = fixture(1, startMs = now - 8 * 60 * 60_000).copy(state = "in")
        assertEquals(1, liveSportShelf(listOf(long), listOf(slot(1)), now).size)
        // And says it is not, however recently.
        val notYet = fixture(2, startMs = now - 60_000).copy(state = "post")
        assertTrue(liveSportShelf(listOf(notYet), listOf(slot(2)), now).isEmpty())
    }

    @Test
    fun `without a verdict it falls back to a bounded window`() {
        // Slots the schedule never placed, and fixtures published before the
        // state field existed.
        val recent = fixture(1, startMs = now - 30 * 60_000)
        assertEquals(1, liveSportShelf(listOf(recent), listOf(slot(1)), now).size)
        val stale = fixture(2, startMs = now - 5 * 60 * 60_000)
        assertTrue(liveSportShelf(listOf(stale), listOf(slot(2)), now).isEmpty())
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
        // Forty DIFFERENT matches. They used to be forty copies of Arsenal v
        // Chelsea, which measured the cap against a list the shelf now folds
        // to one card — the fixture-level dedupe is the right answer to that
        // input, so the cap has to be asked with the input it is about.
        val many = (1..40).map { fixture(it, home = "Club $it", away = "Town $it") }
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

    // --- one card per MATCH, and the bar the card draws --------------------

    /**
     * The duplicate Home was actually drawing. Club Brugge v Aston Villa stood
     * on the shelf twice — once as "AU (STAN 09)" and once as "UEFA Champio…"
     * — two slots, two ids, one game. distinctBy(slot.id) cannot see it.
     */
    @Test
    fun `the same match on two slots makes one card`() {
        val out = liveSportShelf(
            listOf(
                fixture(1, "Club Brugge", "Aston Villa"),
                fixture(2, "Club Brugge", "Aston Villa"),
            ),
            listOf(slot(1), slot(2)),
            now,
        )
        assertEquals(1, out.size)
        assertEquals("the first slot survives", "live:1", out.single().slot.id)
    }

    /** Two spellings of one club are still one match, via the schedule's key. */
    @Test
    fun `one match spelled two ways makes one card`() {
        val key = "CLUB BRUGGE|ASTON VILLA"
        val out = liveSportShelf(
            listOf(
                fixture(1, "Club Brugge", "Aston Villa").copy(scheduleKey = key),
                fixture(2, "Brugge", "Villa").copy(scheduleKey = key),
            ),
            listOf(slot(1), slot(2)),
            now,
        )
        assertEquals(1, out.size)
    }

    /** Different matches are not folded, however close together they sit. */
    @Test
    fun `two different matches make two cards`() {
        val out = liveSportShelf(
            listOf(
                fixture(1, "Club Brugge", "Aston Villa"),
                fixture(2, "AEK Athens", "LASK Linz"),
            ),
            listOf(slot(1), slot(2)),
            now,
        )
        assertEquals(2, out.size)
    }

    @Test
    fun `the bar says how far into the match you are joining`() {
        // Half an hour into the two-hour window the card assumes.
        val card = liveSportShelf(listOf(fixture(1)), listOf(slot(1)), now).single()
        assertEquals(0.25f, card.progress(now)!!, 0.001f)
    }

    @Test
    fun `a fixture with no trusted kick-off draws no bar`() {
        // A made-up bar is worse than none: the bar is the one thing on the
        // card that claims to be measured.
        val card = liveSportShelf(
            listOf(fixture(1, startMs = null).copy(live = true)), listOf(slot(1)), now,
        ).single()
        assertNull(card.progress(now))
    }

    @Test
    fun `the bar never runs past the end of its window`() {
        val card = liveSportShelf(listOf(fixture(1)), listOf(slot(1)), now).single()
        assertEquals(1f, card.progress(now + 10 * 60 * 60 * 1000L)!!, 0.001f)
    }

    // --- the header above the shelf ---------------------------------------
    //
    // "remove the ugly NEXT LINE", 2026-09-09, with a photo of Home headed
    // "Next | Liverpool vs. Atlético Madrid | all | 09-09-2026 | 1…" — the
    // SOCCER PPV slot's own name, cut off mid-field, over a card that read
    // "Liverpool v Atletico Madrid / Champions League". Slot names below are
    // verbatim from player_api the same evening.

    private fun ucl(streamId: Int) =
        fixture(streamId, "Liverpool", "Atletico Madrid", league = "Champions League")

    @Test
    fun `the header names the match, not the pipe`() {
        val slot = slot(
            1940144,
            "Live | Liverpool vs. Atlético Madrid | all | 8K EXCLUSIVE | US: SOCCER PPV 13",
        )
        val card = liveSportShelf(listOf(ucl(1940144)), listOf(slot), now).single()
        val hero = fixtureHero(card)
        assertEquals("Liverpool v Atletico Madrid", hero.title)
        assertTrue(hero.chips.contains("Champions League"))
    }

    /** Another pack, another paragraph, and the header is the same line. */
    @Test
    fun `every pack's marketing text is left on the slot`() {
        val slot = slot(
            1896469,
            "LIVE | LIVERPOOL - ATLÉTICO MADRID | Wed 09 Sep 18:00 UTC (UK) | " +
                "8K EXCLUSIVE | UK: MAX PPV 17",
        )
        val hero = fixtureHero(liveSportShelf(listOf(ucl(1896469)), listOf(slot), now).single())
        assertEquals("Liverpool v Atletico Madrid", hero.title)
        assertTrue(hero.chips.first() == "Live")
    }

    /**
     * A fixture has no synopsis and its slot's guide entry is more of the
     * same marketing text, so the hero asks for none — the plot line under
     * the header stays empty rather than filling with the pack's blurb.
     */
    @Test
    fun `the header asks for no synopsis`() {
        val slot = slot(1, "Live | Liverpool vs. Atlético Madrid | all | US: SOCCER PPV 13")
        val hero = fixtureHero(liveSportShelf(listOf(ucl(1)), listOf(slot), now).single())
        assertNull(hero.plot)
        assertNull(hero.plotKey)
    }

    // --- the pipeline, end to end ---------------------------------------------
    //
    // Everything above hands this function rows by hand. What actually reaches
    // it in the app is SportsParser.upcoming's output — see
    // [MainViewModel.sportRows] — and the bug that put a finished match on
    // Home was precisely that it USED to be handed the raw parse instead. So
    // these run the real pipeline, in the real order.

    /** Home's shelf, built the way the app builds it. */
    private fun homeShelf(
        parsed: List<SportsEvent>,
        slots: List<LiveChannel>,
        at: Long = now,
    ) = liveSportShelf(SportsParser.upcoming(parsed, at, cueMinutes = 60), slots, at)

    @Test
    fun `a match that finished hours ago is on neither screen`() {
        // Reported 2026-09-09: Napoli v Arsenal on Home, absent from Sport.
        // Kick-off 19:00Z, ESPN read "in" when the schedule was generated at
        // 20:54Z mid-match, and the shelf was still showing it at 03:35Z.
        // The state field is exactly as it arrives from fixtures.json, and it
        // must not be able to hold the card open: the schedule publishes every
        // six hours and is cached for six more, so an "in" can be half a day
        // old.
        val kickOff = now - 8 * 60 * 60_000 - 35 * 60_000
        val napoli = fixture(1, "Napoli", "Arsenal", startMs = kickOff, league = "Champions League")
            .copy(state = "in")
        assertTrue(SportsParser.upcoming(listOf(napoli), now, 60).isEmpty())
        assertTrue(homeShelf(listOf(napoli), listOf(slot(1))).isEmpty())
    }

    @Test
    fun `a match being played right now still reaches the shelf`() {
        // The other half of the same claim: the window that drops the finished
        // one has to leave the live one alone, or the fix is just an empty
        // shelf.
        val playing = fixture(1, startMs = now - 40 * 60_000).copy(state = "in")
        assertEquals(1, homeShelf(listOf(playing), listOf(slot(1))).size)
    }

    @Test
    fun `the shelf is a subset of what the Sport tab lists`() {
        // The invariant the two screens now share. Everything here is on one
        // slot each so nothing folds; what separates the lists is the shelf's
        // own two rules — kicked off, and a stream behind it — and nothing
        // else.
        val rows = listOf(
            fixture(1, "Napoli", "Arsenal", startMs = now - 9 * 60 * 60_000),
            fixture(2, "Getafe", "Celta Vigo", startMs = now - 45 * 60_000),
            fixture(3, "Ajax", "PSV", startMs = now + 30 * 60_000),
            fixture(4, "Chelsea", "Fulham", startMs = now - 20 * 60_000),
        )
        val slots = listOf(slot(1), slot(2), slot(3))
        val tab = SportsParser.upcoming(rows, now, cueMinutes = 60)
        val home = homeShelf(rows, slots)
        val listed = tab.map { it.streamId }.toSet()
        assertTrue(home.all { it.event.streamId in listed })
        // Concretely: the finished one is on neither, the one that has not
        // kicked off is on the tab only, the one with no slot behind it is on
        // the tab only, and only the match being played makes a card.
        assertEquals(listOf(2), home.map { it.event.streamId })
        assertEquals(setOf(2, 3, 4), listed)
    }

    @Test
    fun `a clockless LIVE row cannot crowd out the matches that have a time`() {
        // upcoming() sorts the live group by `startMs ?: 0L`, so rows with no
        // kick-off at all lead it. On the Sport tab that costs them a position
        // under a heading; here it decides who is inside the cap, and a busy
        // evening could fill the shelf with rows whose only claim to being on
        // is the word LIVE in a slot name.
        val timeless = (1..25).map {
            fixture(it, "Home $it", "Away $it", startMs = null).copy(live = true)
        }
        val played = fixture(99, "Arsenal", "Chelsea", startMs = now - 30 * 60_000)
        val slots = (1..25).map { slot(it) } + slot(99)
        val out = homeShelf(timeless + played, slots)
        assertEquals(SHELF_CAP, out.size)
        assertEquals("Arsenal v Chelsea", out.first().event.title)
    }
}

/** [liveSportShelf]'s own cap, mirrored so a change to it fails this file. */
private const val SHELF_CAP = 20
