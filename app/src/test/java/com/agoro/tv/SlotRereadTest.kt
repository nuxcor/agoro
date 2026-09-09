package com.agoro.tv

import com.agoro.tv.data.namesFixture
import com.agoro.tv.data.reReadSlots
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The pipes move, and the catalogue does not.
 *
 * Every name and every stream id below was read off the panel: 1025280 and
 * 1025279 are the UEFA pack's first two pipes, and between 8 September 13:40
 * and 9 September 17:52 seven of that pack's 37 pipes were re-pointed at a new
 * fixture. A row built from the older list is a claim about the past.
 */
class SlotRereadTest {

    /** The UEFA pack, as the panel had it on the 8th and again on the 9th. */
    private val yesterday = mapOf(
        1025280 to "UEFA  | 01 - Freiburg vs Motherwell",
        1025279 to "UEFA  | 02 - Celje  vs Slovan Bratislava 8:00 pm",
        1025278 to "UEFA  | 03 - Olympique Lyonnais vs Fenerbahe 8:00 pm",
    )
    private val today = mapOf(
        1025280 to "UEFA | 01-  Barcelona  vs Feyenoord  5:45pm",
        1025279 to "UEFA  | 02 - Stuttgart  vs Viking  5:45pm",
        1025278 to "UEFA  | 03 - UCL Goals Show 8:00pm",
    )
    private val uefaPack = today.keys.toList()

    @Test
    fun `a pipe that still names the match is kept`() {
        assertEquals(
            listOf(1025280),
            reReadSlots(
                "Barcelona", "Feyenoord Rotterdam",
                candidates = listOf(1025280),
                fresh = today,
                inFetchedCategory = { it in uefaPack },
                alsoConsider = uefaPack,
            ),
        )
    }

    /**
     * The report this exists for. The row was built from yesterday's list, so
     * it points at the pipe that carried Celje v Slovan — which is today the
     * Stuttgart game. Pressing Barcelona opened Stuttgart.
     */
    @Test
    fun `a pipe that has moved to another fixture is dropped, and the one that has it is found`() {
        // Yesterday's parse would have put Celje v Slovan on 1025279; the row
        // that inherits that id is asking for a match that pipe no longer has.
        assertEquals(
            listOf(1025280),
            reReadSlots(
                "Barcelona", "Feyenoord Rotterdam",
                candidates = listOf(1025279),
                fresh = today,
                inFetchedCategory = { it in uefaPack },
                alsoConsider = uefaPack,
            ),
        )
    }

    @Test
    fun `the pipe that still has the match keeps its place at the front`() {
        assertEquals(
            listOf(1025280, 1025279),
            reReadSlots(
                "Barcelona", "Feyenoord Rotterdam",
                candidates = listOf(1025280, 1025279),
                fresh = today + mapOf(1025279 to "UEFA | 02 - Barcelona vs Feyenoord 5:45pm"),
                inFetchedCategory = { it in uefaPack },
                alsoConsider = uefaPack,
            ),
        )
    }

    /**
     * A panel that says nothing has not said the row is wrong. The catalogue
     * is all there is then, and it is what the app has always played from.
     */
    @Test
    fun `silence from the panel changes nothing`() {
        assertEquals(
            listOf(1025279, 1025280),
            reReadSlots(
                "Barcelona", "Feyenoord Rotterdam",
                candidates = listOf(1025279, 1025280),
                fresh = emptyMap(),
                inFetchedCategory = { true },
                alsoConsider = uefaPack,
            ),
        )
    }

    @Test
    fun `a slot from a category nobody re-read is left alone, one that vanished is not`() {
        val other = 1501737 // Paramount+ 01, a category not fetched here
        assertEquals(
            listOf(other, 1025280),
            reReadSlots(
                "Barcelona", "Feyenoord Rotterdam",
                // 1025278 was re-read and is now the goals show; 1025277 is in
                // a re-read category and did not come back at all.
                candidates = listOf(other, 1025278, 1025277),
                fresh = today,
                inFetchedCategory = { it in uefaPack || it == 1025277 },
                alsoConsider = uefaPack,
            ),
        )
    }

    /** Yesterday's names, read today, agree with nothing — which is the point. */
    @Test
    fun `the same ids answered differently a day earlier`() {
        assertEquals(
            emptyList<Int>(),
            reReadSlots(
                "Barcelona", "Feyenoord Rotterdam",
                candidates = listOf(1025280),
                fresh = yesterday,
                inFetchedCategory = { it in uefaPack },
                alsoConsider = uefaPack,
            ),
        )
    }

    /**
     * The panel as it actually read at 2026-09-09 20:22 Amsterdam, verbatim.
     *
     * Fetched from player_api on the live line, not typed from memory — the
     * double spaces, the "01-" with no space after it, the "vs." with a full
     * stop and the accent on Atlético are all the provider's own. They are the
     * point: every one of them is a place the match could fail to be
     * recognised, and the whole re-read rests on recognising it.
     *
     * The same three fixtures appear in three categories under three sets of
     * ids — UEFA PPV UK (993xxx), UEFA PPV US (1025xxx) and SOCCER PPV
     * (1940xxx) — which is why a fixture has feeds to step through at all.
     */
    private val measured = mapOf(
        1025280 to "UEFA | 01-  Barcelona  vs Feyenoord  5:45pm",
        1025279 to "UEFA  | 02 - Stuttgart  vs Viking  5:45pm",
        1025278 to "UEFA  | 03 - UCL Goals Show 8:00pm",
        1025277 to "UEFA  | 04  - Liverpool vs Atletico Madrid  8:00pm",
        1025276 to "UEFA  | 05 - Napoli vs Arsenal  8:00pm",
        1025273 to "UEFA  | 08 -",
        1025281 to "####### UEFA PPV #######",
        1940147 to "Live | Barcelona vs. Feyenoord | all | 8K EXCLUSIVE | US: SOCCER PPV 10",
        1940146 to "Live | Stuttgart vs. Viking FK | all | 8K EXCLUSIVE | US: SOCCER PPV 11",
        1940144 to "Live | Liverpool vs. Atlético Madrid | all | 8K EXCLUSIVE | US: SOCCER PPV 13",
    )

    /**
     * A fixture finds its pipes across both packs, and takes nothing else.
     *
     * The club names never agree: the schedule says Feyenoord Rotterdam, one
     * pack says Feyenoord, the other says Feyenoord with two spaces round it.
     */
    @Test
    fun `both packs' pipes for the match are found, in the real panel's names`() {
        assertEquals(
            listOf(1025280, 1940147),
            reReadSlots(
                "Barcelona", "Feyenoord Rotterdam",
                candidates = listOf(1025280),
                fresh = measured,
                inFetchedCategory = { it in measured },
                alsoConsider = measured.keys.toList(),
            ),
        )
    }

    /** "Viking" in one pack is "Viking FK" in the other; both are the match. */
    @Test
    fun `a club named longer in one pack still matches`() {
        assertEquals(
            listOf(1025279, 1940146),
            reReadSlots(
                "Stuttgart", "Viking",
                candidates = listOf(1025279),
                fresh = measured,
                inFetchedCategory = { it in measured },
                alsoConsider = measured.keys.toList(),
            ),
        )
    }

    /** Atlético carries an accent in one pack and not the other. */
    @Test
    fun `an accented club matches its unaccented spelling`() {
        assertEquals(
            listOf(1025277, 1940144),
            reReadSlots(
                "Liverpool", "Atletico Madrid",
                candidates = listOf(1025277),
                fresh = measured,
                inFetchedCategory = { it in measured },
                alsoConsider = measured.keys.toList(),
            ),
        )
    }

    /**
     * What a pack puts in the pipes that are not a match: a highlights show, a
     * numbered slot with nothing after the dash, and the header row the
     * provider uses as a divider. None of them is a fixture, and a re-read
     * that took one would put a studio show up under a match title.
     */
    @Test
    fun `the goals show, the empty slot and the header row are not fixtures`() {
        for (id in listOf(1025278, 1025273, 1025281)) {
            assertEquals(
                "id $id should name no fixture: ${measured[id]}",
                false,
                namesFixture(measured.getValue(id), "Barcelona", "Feyenoord Rotterdam"),
            )
        }
    }

    /**
     * The other four ties on the same night, against the wrong match. A pack
     * whose pipes all read as football is exactly where a loose rule would
     * find the match everywhere.
     */
    @Test
    fun `a night of ties does not match the wrong one`() {
        val napoliArsenal = listOf(1025276)
        assertEquals(
            napoliArsenal,
            reReadSlots(
                "Napoli", "Arsenal",
                candidates = napoliArsenal,
                fresh = measured,
                inFetchedCategory = { it in measured },
                alsoConsider = measured.keys.toList(),
            ),
        )
    }
}
