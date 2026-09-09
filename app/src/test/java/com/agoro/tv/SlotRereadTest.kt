package com.agoro.tv

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
}
