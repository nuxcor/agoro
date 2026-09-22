package com.agoro.tv

import androidx.compose.ui.unit.dp
import com.agoro.tv.ui.screens.HEADER_HEIGHT
import com.agoro.tv.ui.screens.NOTICE_BAR_COST
import com.agoro.tv.ui.screens.ROW_GAP
import com.agoro.tv.ui.screens.ROW_HEIGHT
import com.agoro.tv.ui.screens.STRIP_GAP
import com.agoro.tv.ui.theme.HEADER_BAND_HEIGHT
import com.agoro.tv.ui.theme.HEADER_RETRACTED_INSET
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

    /**
     * Laid out above the grid alongside the header.
     *
     * WHAT THIS TEST DOES NOT HOLD, stated because the first version of it
     * implied otherwise. The chip strip's 54dp and the ruler's 36dp are not
     * constants anywhere: the strip is CategoryItem's vertical padding plus
     * its type plus [STRIP_GAP], and the ruler is its label line plus a
     * spacer. Both are composed at layout time and neither can be imported,
     * so they are written here as the numbers GuideTab's KDoc asserts — which
     * means raising the chip padding still clips the fourth channel with
     * every assertion below green.
     *
     * What IS held is every term that is a named constant: the band, the
     * bottom gutter, the header, the notice cost, the row height and both
     * gaps. Those are the ones a change would actually move.
     */
    private val strip = 48.dp + STRIP_GAP
    private val ruler = 36.dp

    private val lane = canvas - HEADER_BAND_HEIGHT - Space.gutterVertical

    /**
     * The lane with the navigation retracted — both rows gone, the top inset
     * down to the app's safe margin. This is what the guide is browsed in;
     * the expanded lane above is only what it is ARRIVED in.
     */
    private val laneRetracted = canvas - HEADER_RETRACTED_INSET - Space.gutterVertical
    private val rowsAvailable = lane - strip - STRIP_GAP - ruler - HEADER_HEIGHT

    private fun rows(n: Int) = ROW_HEIGHT * n + ROW_GAP * (n - 1)

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
        val ceiling = lane - strip - STRIP_GAP - ruler - rows(4)
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
    fun `a fifth row does not fit while the bar is up`() {
        val headerForFive = lane - strip - STRIP_GAP - ruler - rows(5)
        assertTrue(
            "five rows would need a ${headerForFive.value}dp header, " +
                "which is under the ${NOTICE_BAR_COST.value}dp notice floor",
            headerForFive <= NOTICE_BAR_COST,
        )
    }

    /**
     * What the retraction is FOR. With both rows gone and the strip's 54dp
     * back in the lane, the guide holds six channel rows instead of four —
     * a fifty per cent increase on the surface this app exists for.
     */
    @Test
    fun `the retracted lane holds six channel rows`() {
        val avail = laneRetracted - ruler - HEADER_HEIGHT - STRIP_GAP
        assertTrue(
            "retracted lane leaves ${avail.value}dp; six rows need ${rows(6).value}dp",
            avail >= rows(6),
        )
    }

    /**
     * And it holds them by TWO dp. Written down because that is not comfort,
     * it is a warning: any growth in the guide header (104), the ruler (36) or
     * the gap costs the sixth row outright. If this starts failing, the honest
     * answer is five rows, not a smaller header.
     */
    @Test
    fun `the sixth row has almost no margin`() {
        val avail = laneRetracted - ruler - HEADER_HEIGHT - STRIP_GAP
        val spare = avail - rows(6)
        assertTrue("the sixth row now has ${spare.value}dp spare", spare < 8.dp)
        assertTrue("the sixth row no longer fits: ${spare.value}dp", spare >= 0.dp)
    }
}
