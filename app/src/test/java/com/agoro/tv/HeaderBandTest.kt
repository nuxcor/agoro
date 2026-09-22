package com.agoro.tv

import androidx.compose.ui.unit.dp
import com.agoro.tv.ui.screens.ITEM_PADDING_V
import com.agoro.tv.ui.screens.ROW_PADDING_BOTTOM
import com.agoro.tv.ui.screens.ROW_PADDING_TOP
import com.agoro.tv.ui.theme.HEADER_BAND_HEIGHT
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The header band's composition, held by a test instead of by a paragraph.
 *
 * 78dp is the most over-subscribed number in the app. It is the wash's height,
 * the content lane's top padding, and the first term of the guide's entire row
 * budget — so an edit that spends it twice shows up as a clipped channel row
 * two files away, with nothing pointing back here.
 *
 * This became worth pinning the moment the gold rule under the selected tab
 * was removed. That freed 8dp (a 4dp rule and its 4dp pad) and the obvious
 * move was to bank it as a 70dp band — which would have pulled the bar and the
 * category strip 8dp CLOSER TOGETHER, worsening the exact complaint the change
 * was answering. The dp was spent on the Row's bottom padding instead (6 -> 14),
 * so the seam between the two rows went from 0dp to 8 and the band did not
 * move at all. These assertions are what stops the next reader banking it.
 */
class HeaderBandTest {

    /** The pill: one 28sp line of titleMedium plus [ITEM_PADDING_V] each side. */
    private val labelLine = 28.dp
    private val pill = labelLine + ITEM_PADDING_V + ITEM_PADDING_V

    @Test
    fun `the band is exactly what its parts add up to`() {
        assertEquals(HEADER_BAND_HEIGHT, ROW_PADDING_TOP + pill + ROW_PADDING_BOTTOM)
    }

    /**
     * The band must not shrink. It is the lane's top padding, so a shorter one
     * does not give the guide a row — it just moves the whole page up and
     * closes the gap to the strip.
     */
    @Test
    fun `the band did not shrink when the rule was removed`() {
        assertEquals(78.dp, HEADER_BAND_HEIGHT)
    }

    /**
     * The seam. With the rule gone, the only thing separating the bar from the
     * category strip below it is this padding, and at the old 6dp the two rows
     * met almost flush — which is what "it reads as one block" was.
     */
    @Test
    fun `there is real air under the words`() {
        assertTrue(
            "the seam is ${ROW_PADDING_BOTTOM.value}dp; below ~12 the two rows read as one block",
            ROW_PADDING_BOTTOM >= 12.dp,
        )
    }

    /** The top padding doubles as the app's top safe inset; overscan eats less than this. */
    @Test
    fun `the top padding clears overscan`() {
        assertTrue(ROW_PADDING_TOP >= 16.dp)
    }
}
