package com.agoro.tv

import androidx.compose.ui.focus.FocusRequester
import com.agoro.tv.data.EpgProgram
import com.agoro.tv.ui.screens.GuideCellFocus
import com.agoro.tv.ui.screens.MIN_CELL_MINUTES
import com.agoro.tv.ui.screens.cellIndexFor
import com.agoro.tv.ui.screens.layoutGuideRow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The row layout and the focus registry are the two halves of the grid that
 * only fail visibly on a TV: a drifted row looks like bad guide data, and a
 * wrong vertical landing looks like flaky focus. Both are pure, so both are
 * pinned here.
 */
class GuideGridTest {

    private val windowStart = 1_000_000_000_000L
    private val hour = 3_600_000L
    private val minute = 60_000L
    private val windowEnd = windowStart + 30 * hour

    private fun program(startMin: Long, endMin: Long, id: String = "$startMin") = EpgProgram(
        id = id,
        title = "P$id",
        description = null,
        startMs = windowStart + startMin * minute,
        endMs = windowStart + endMin * minute,
        hasArchive = false,
    )

    private fun totalMinutes(layout: com.agoro.tv.ui.screens.GuideRowLayout): Float =
        layout.cells.fold(0f) { acc, c -> acc + c.gapMinutesBefore + c.widthMinutes } +
            layout.tailMinutes

    @Test
    fun `back-to-back programmes fill the window exactly`() {
        val layout = layoutGuideRow(
            listOf(program(0, 60), program(60, 120), program(120, 180)),
            windowStart, windowEnd,
        )
        assertEquals(3, layout.cells.size)
        assertEquals(30 * 60f, totalMinutes(layout), 0.01f)
    }

    @Test
    fun `short programmes borrow width and the row still adds up`() {
        // A 5-minute bulletin gets MIN_CELL_MINUTES; the debt comes out of the
        // long programme after it, so the row total never drifts off the ruler.
        val layout = layoutGuideRow(
            listOf(program(0, 5), program(5, 125)),
            windowStart, windowEnd,
        )
        assertEquals(MIN_CELL_MINUTES, layout.cells[0].widthMinutes, 0.01f)
        assertEquals(120f - (MIN_CELL_MINUTES - 5f), layout.cells[1].widthMinutes, 0.01f)
        assertEquals(30 * 60f, totalMinutes(layout), 0.01f)
    }

    @Test
    fun `sub-minute programmes are dropped, not given cells`() {
        val layout = layoutGuideRow(
            listOf(program(0, 60), program(60, 60, id = "zero"), program(60, 120)),
            windowStart, windowEnd,
        )
        assertEquals(listOf("0", "60"), layout.cells.map { it.program.id })
    }

    @Test
    fun `gaps in the data stay gaps`() {
        val layout = layoutGuideRow(
            listOf(program(0, 60), program(90, 150)),
            windowStart, windowEnd,
        )
        assertEquals(30f, layout.cells[1].gapMinutesBefore, 0.01f)
        assertEquals(30 * 60f, totalMinutes(layout), 0.01f)
    }

    @Test
    fun `a programme straddling the window edge is clamped`() {
        val layout = layoutGuideRow(
            listOf(program(-120, 60)), // started two hours before the window
            windowStart, windowEnd,
        )
        assertEquals(windowStart, layout.cells[0].clampedStartMs)
        assertEquals(60f, layout.cells[0].widthMinutes, 0.01f)
    }

    // --- vertical landing --------------------------------------------------

    private fun cell(startMin: Long, endMin: Long) = GuideCellFocus(
        windowStart + startMin * minute,
        windowStart + endMin * minute,
        FocusRequester(),
    )

    @Test
    fun `vertical moves land on the programme covering the anchor`() {
        val row = listOf(cell(0, 30), cell(30, 120), cell(120, 150))
        assertEquals(1, cellIndexFor(row, windowStart + 45 * minute))
        assertEquals(2, cellIndexFor(row, windowStart + 120 * minute))
    }

    @Test
    fun `an anchor past the row's data lands on the nearest cell`() {
        // This row's guide stops early; focus should land on its last
        // programme rather than bouncing back to the row it came from.
        val row = listOf(cell(0, 30), cell(30, 60))
        assertEquals(1, cellIndexFor(row, windowStart + 5 * hour))
        assertTrue(cellIndexFor(row, windowStart - hour) == 0)
    }


    /**
     * A row with nothing long enough to repay the debt. Eighteen ten-minute
     * programmes in a three-hour window: every one is under MIN_CELL_MINUTES,
     * so each borrowed and none ever paid back, the row measured 288 minutes
     * against a 180-minute ruler, and the cell under the NOW line was an hour
     * and a half from what was on. The rows share one scroll, so it pulled the
     * rest of the grid out of line too.
     */
    @Test
    fun `a row of short programmes never measures wider than its window`() {
        val programs = (0 until 18).map { program(it * 10L, (it + 1) * 10L) }
        val layout = layoutGuideRow(programs, windowStart, windowStart + 180 * minute)
        val drawn = layout.cells.fold(0f) { acc, c -> acc + c.gapMinutesBefore + c.widthMinutes }
        assertTrue(
            "row measured $drawn minutes against a 180-minute window",
            drawn <= 180f + com.agoro.tv.ui.screens.MAX_BORROWED_MINUTES,
        )
        assertTrue("the tail is never negative", layout.tailMinutes >= 0f)
        assertEquals(18, layout.cells.size)
    }

    /** The short ones are still widened until the debt reaches its bound. */
    @Test
    fun `short programmes are still widened while the debt is affordable`() {
        val layout = layoutGuideRow(
            listOf(program(0L, 5L), program(5L, 10L), program(10L, 180L)),
            windowStart, windowStart + 180 * minute,
        )
        assertEquals(com.agoro.tv.ui.screens.MIN_CELL_MINUTES, layout.cells[0].widthMinutes, 0.01f)
        assertEquals(com.agoro.tv.ui.screens.MIN_CELL_MINUTES, layout.cells[1].widthMinutes, 0.01f)
        // The long one pays the debt back, so the row still ends on the ruler.
        val drawn = layout.cells.fold(0f) { acc, c -> acc + c.gapMinutesBefore + c.widthMinutes }
        assertEquals(180f, drawn + layout.tailMinutes, 0.01f)
    }
}
