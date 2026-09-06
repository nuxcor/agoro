package com.agoro.tv

import com.agoro.tv.ui.screens.GuideBackAction
import com.agoro.tv.ui.screens.guideBackAction
import org.junit.Assert.assertEquals
import org.junit.Test

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
            guideBackAction(awayFromNow = false, gridHoldsFocus = true, shellOwnsFocus = false),
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
            guideBackAction(awayFromNow = true, gridHoldsFocus = true, shellOwnsFocus = false),
        )
        assertEquals(
            GuideBackAction.JumpToNow,
            guideBackAction(awayFromNow = true, gridHoldsFocus = false, shellOwnsFocus = false),
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
            guideBackAction(awayFromNow = false, gridHoldsFocus = false, shellOwnsFocus = false),
        )
    }

    /**
     * The rung that predates this change and was wrong before it. The guide's
     * BACK handler is composed after the shell's, so it is offered the key
     * first — and while the nav rail is open over the guide, a viewer who had
     * wandered in time got jumpToNow on a screen they could not see, the rail
     * did not close, and the Live tab could not be left.
     */
    @Test
    fun `an open rail outranks everything the guide would do`() {
        assertEquals(
            GuideBackAction.LeaveToShell,
            guideBackAction(awayFromNow = true, gridHoldsFocus = true, shellOwnsFocus = true),
        )
        assertEquals(
            GuideBackAction.LeaveToShell,
            guideBackAction(awayFromNow = false, gridHoldsFocus = true, shellOwnsFocus = true),
        )
    }
}
