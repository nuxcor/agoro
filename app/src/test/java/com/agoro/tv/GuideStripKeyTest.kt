package com.agoro.tv

import com.agoro.tv.data.Category
import com.agoro.tv.ui.screens.GuideBackAction
import com.agoro.tv.ui.screens.groupByRegion
import com.agoro.tv.ui.screens.guideBackAction
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The category strip is a LazyRow, and a LazyRow measuring two items under one
 * slot id throws out of subcompose — which takes Live TV to the launcher, not
 * to an error state. So the keys are held to being unique here rather than
 * discovered on a television.
 */
class GuideStripKeyTest {

    private fun cats(vararg ids: String) =
        ids.map { Category(id = it, name = it.substringAfter('|').lowercase().replaceFirstChar(Char::titlecase) + " · " + it.substringBefore('|')) }

    private fun keys(vararg ids: String) = groupByRegion(cats(*ids)).map { it.key }

    /**
     * The regression. A provider that files section-major repeats a region,
     * and the heading is now the bare territory code, so a label-derived key
     * collided.
     */
    @Test
    fun `a territory named by itself gets a chip and no heading above it`() {
        // DStv holds one shelf, so ManifestCuration labels it "DStv" with no
        // genre half. A heading would then read "DStv" directly above a chip
        // reading "DStv".
        val entries = groupByRegion(
            listOf(
                Category(id = "ENTERTAINMENT", name = "Entertainment"),
                Category(id = "AFR|ENTERTAINMENT", name = "DStv"),
                Category(id = "STREAMING", name = "Streaming Networks"),
            )
        )
        assertEquals(
            listOf("Entertainment", "DStv", "Streaming Networks"),
            entries.map { it.label },
        )
    }

    @Test
    fun `a suffixed territory still gets its heading`() {
        val entries = groupByRegion(
            listOf(
                Category(id = "NEWS", name = "News"),
                Category(id = "AFR|NEWS", name = "News · DStv"),
                Category(id = "AFR|SPORTS", name = "Sports · DStv"),
            )
        )
        assertEquals(listOf("News", "DStv", "News", "Sports"), entries.map { it.label })
    }

    @Test
    fun `a region that appears twice does not reuse a key`() {
        val k = keys("US|NEWS", "UK|NEWS", "US|SPORTS")
        assertEquals("every strip key must be unique: $k", k.size, k.toSet().size)
    }

    @Test
    fun `keys stay unique across many alternating regions`() {
        val k = keys(
            "US|SPORTS", "UK|SPORTS", "CA|SPORTS",
            "US|NEWS", "UK|NEWS", "CA|NEWS",
            "US|KIDS", "UK|KIDS", "CA|KIDS",
        )
        assertEquals("every strip key must be unique: $k", k.size, k.toSet().size)
    }

    /** The ordinary region-major case must keep working too. */
    @Test
    fun `contiguous regions open one group each`() {
        val entries = groupByRegion(cats("US|NEWS", "US|SPORTS", "UK|NEWS"))
        assertEquals(2, entries.count { it is com.agoro.tv.ui.screens.StripEntry.Group })
        val k = entries.map { it.key }
        assertEquals(k.size, k.toSet().size)
    }

    /** Categories with no territory open no group and cannot collide. */
    @Test
    fun `region-less categories open no group`() {
        val entries = groupByRegion(listOf(Category(id = "recent", name = "Recent")))
        assertEquals(0, entries.count { it is com.agoro.tv.ui.screens.StripEntry.Group })
    }
}

/**
 * What BACK means in the guide, and in which order.
 *
 * Reported 2026-09-05 as "am in locals and want to go to news": the category
 * strip is reachable by UP, but only from the grid's TOP ROW, and the two
 * shortcuts that skip the walk — channel paging and the number-key jump — are
 * both absent from a Chromecast with Google TV remote. BACK is the only key
 * left that every remote carries.
 */
class GuideBackActionTest {

    @Test
    fun `back reaches the category strip from inside the grid`() {
        assertEquals(
            GuideBackAction.CategoryStrip,
            guideBackAction(awayFromNow = false, gridHoldsFocus = true),
        )
    }

    /**
     * The rung that must not be displaced. "First BACK returns to now when the
     * viewer has wandered" predates the strip route, and a viewer reading
     * tomorrow's schedule is further from home than one reading today's
     * Locals.
     */
    @Test
    fun `returning to now outranks the strip`() {
        assertEquals(
            GuideBackAction.JumpToNow,
            guideBackAction(awayFromNow = true, gridHoldsFocus = true),
        )
        assertEquals(
            GuideBackAction.JumpToNow,
            guideBackAction(awayFromNow = true, gridHoldsFocus = false),
        )
    }

    /**
     * And the case that keeps the rail: focus on the strip itself, or anywhere
     * outside the grid, and this handler stands down so the shell's BACK opens
     * the nav rail — which is what BACK did on this tab before the strip rung
     * existed, one press later.
     */
    @Test
    fun `outside the grid the shell keeps back`() {
        assertEquals(
            GuideBackAction.LeaveToShell,
            guideBackAction(awayFromNow = false, gridHoldsFocus = false),
        )
    }
}
