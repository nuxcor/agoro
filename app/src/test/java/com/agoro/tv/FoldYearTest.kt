package com.agoro.tv

import com.agoro.tv.data.Series
import com.agoro.tv.ui.screens.foldSeriesVariants
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * One show, listed once per pack, and the packs disagree about the year.
 *
 * The provider ships each series under several prefixes — `EN -`, `NF -`,
 * `4K-NF -`, `NF-DO -` — and only some of them write the year into the name.
 * The fold keys on name AND year, so copies that disagree about it key apart
 * and every one of them reaches the Series tab. Over this panel's own 8,598
 * series, 879 titles were still duplicated after that fold, covering 1,785
 * entries; a viewer saw "Trash Truck" four times and, opening the wrong copy,
 * got whichever subset of episodes that pack carried.
 *
 * A copy with no year is the same show with less metadata — but only when
 * there is exactly one show it could be.
 */
class FoldYearTest {

    private fun series(id: String, year: Int?, quality: String? = null, name: String = "Trash Truck") =
        Series(id = id, name = name, poster = null, categoryId = "c", year = year, quality = quality)

    @Test
    fun `a yearless copy folds into the one that has a year`() {
        val out = listOf(
            series("a", null),
            series("b", null),
            series("c", 2020),
        ).foldSeriesVariants()
        assertEquals(1, out.size)
        assertEquals("c", out.single().id)
        assertEquals(2020, out.single().year)
    }

    /**
     * Dynasty is the case that proves the rule needs a limit: the panel
     * carries 2017, 1981 and a yearless copy, and the first two are genuinely
     * different programmes. Two candidates means the yearless one cannot be
     * placed, so it is left alone rather than guessed onto one of them.
     */
    @Test
    fun `two candidate years leave the yearless copy where it is`() {
        val out = listOf(
            series("remake", 2017, name = "Dynasty"),
            series("original", 1981, name = "Dynasty"),
            series("bare", null, name = "Dynasty"),
        ).foldSeriesVariants()
        assertEquals(3, out.size)
    }

    /**
     * And the protection this must not break. "The Office (US)" and "The
     * Office (UK)" arrive with the region stripped into one name and no year
     * on either, so nothing distinguishes them — and with no dated copy to
     * anchor to, neither is folded away.
     */
    @Test
    fun `two yearless copies with nothing to anchor to both survive`() {
        val out = listOf(
            series("us", null, name = "The Office"),
            series("uk", null, name = "The Office"),
        ).foldSeriesVariants()
        assertEquals(2, out.size)
    }

    /**
     * The best rung still wins, because the fold decides which STREAM a
     * viewer gets and that was never the year's business. Lucifer is the real
     * case: the 4K copy carried no year and the plain one carried 2016, so
     * the survivor has to be the 4K stream wearing the other's year.
     */
    @Test
    fun `the better rung survives and takes the year with it`() {
        val out = listOf(
            series("4k", null, quality = "4K", name = "Lucifer"),
            series("plain", 2016, name = "Lucifer"),
        ).foldSeriesVariants()
        assertEquals(1, out.size)
        assertEquals("4k", out.single().id)
        assertEquals(2016, out.single().year)
    }
}
