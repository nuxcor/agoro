package com.agoro.tv

import androidx.compose.ui.unit.dp
import com.agoro.tv.ui.screens.HEADER_HEIGHT
import com.agoro.tv.ui.screens.NOTICE_BAR_COST
import com.agoro.tv.ui.screens.ROW_HEIGHT
import com.agoro.tv.ui.theme.HEADER_BAND_HEIGHT
import com.agoro.tv.ui.theme.Space
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The guide's vertical budget, held by a test instead of by a paragraph.
 *
 * GuideTab.kt has always written the arithmetic down — the lane is what the
 * nav band and the bottom gutter leave, and four channel rows is the floor
 * under which a guide stops being a guide. What it could not do is notice when
 * someone spent the same dp twice. Every constant below is real and the
 * relationship between them is the thing that breaks silently.
 */
class GuideBudgetTest {

    /** The 960x540dp canvas the whole app is laid out against. */
    private val canvas = 540.dp

    /** Laid out above the grid alongside the header — see GuideTab's KDoc. */
    private val strip = 54.dp
    private val ruler = 36.dp
    private val headerGap = 6.dp

    private val lane = canvas - HEADER_BAND_HEIGHT - Space.gutterVertical
    private val rowsAvailable = lane - strip - headerGap - ruler - HEADER_HEIGHT

    private fun rows(n: Int) = ROW_HEIGHT * n + 6.dp * (n - 1)

    /**
     * Four channels or it is not a guide. The line GuideTab states, enforced.
     */
    @Test
    fun `the grid keeps four whole channel rows`() {
        assertTrue(
            "only ${rowsAvailable.value}dp left for rows, four need ${rows(4).value}dp",
            rowsAvailable >= rows(4),
        )
    }

    /**
     * The trap under the fifth-row proposal, and the reason it is not just a
     * matter of taste.
     *
     * A notice bar is paid for out of the header — `HEADER_HEIGHT -
     * NOTICE_BAR_COST` — so the header can never be as short as the notice
     * costs. That puts a hard floor of 54dp under it, which is ABOVE the 50dp
     * a fifth row would require: the "drop the synopsis" route does not fail
     * on judgement, it fails on arithmetic, and it fails on the routine case
     * of a playlist whose XMLTV 404s rather than on an exotic one.
     */
    @Test
    fun `a notice bar cannot eat more header than there is`() {
        assertTrue(
            "notice path is ${(HEADER_HEIGHT - NOTICE_BAR_COST).value}dp",
            HEADER_HEIGHT - NOTICE_BAR_COST > 0.dp,
        )
    }

    /**
     * And the ceiling from the other side: the header may not grow into the
     * fourth channel. Nothing above the grid may grow without something else
     * above it shrinking, which is a sentence the file has carried for a while
     * with nothing checking it.
     */
    @Test
    fun `the header cannot grow into the fourth channel`() {
        val ceiling = lane - strip - headerGap - ruler - rows(4)
        assertTrue(
            "header is ${HEADER_HEIGHT.value}dp, ceiling is ${ceiling.value}dp",
            HEADER_HEIGHT <= ceiling,
        )
    }

    /**
     * A fifth row is not available at any header height that also survives the
     * notice path. Stated as a test so the next person to want one reaches for
     * the lane rather than for the header's words — see GuideTab's KDoc.
     */
    @Test
    fun `a fifth row does not fit in this lane`() {
        val headerForFive = lane - strip - headerGap - ruler - rows(5)
        assertTrue(
            "five rows would need a ${headerForFive.value}dp header, " +
                "which is under the ${NOTICE_BAR_COST.value}dp notice floor",
            headerForFive <= NOTICE_BAR_COST,
        )
    }
}
