package com.agoro.tv

import com.agoro.tv.ui.screens.NavChrome
import com.agoro.tv.ui.screens.navChromeLevel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * How much of the navigation is drawn.
 *
 * The rule lives in one function for the reason guideBackAction does: the
 * ORDER is the thing that breaks, and an order expressed as nested ifs across
 * two files is an order nobody can check. This is that check.
 */
class NavChromeTest {

    private fun level(header: Boolean = false, strip: Boolean = false, moved: Boolean = true) =
        navChromeLevel(headerFocused = header, stripFocused = strip, movedSinceArrival = moved)

    /**
     * THE ONE THAT MATTERS, and the reason `moved` is a term at all.
     *
     * The shell parks launch focus in the CONTENT on purpose, so the app boots
     * one OK away from watching. A purely focus-driven rule would therefore
     * retract the navigation on boot, before the viewer has touched anything —
     * a menu that vanishes unbidden, which is the drawer's failure mode
     * reintroduced. It also means the viewer has SEEN the bar before it can
     * ever go away, which is what makes the UP that brings it back
     * discoverable rather than folklore.
     */
    @Test
    fun `nothing retracts until the viewer has moved`() {
        assertEquals(NavChrome.Full, level(moved = false))
        assertEquals(NavChrome.Full, level(header = false, strip = false, moved = false))
    }

    @Test
    fun `focus in the bar draws both rows`() {
        assertEquals(NavChrome.Full, level(header = true))
        // Even if the strip reports focus too, which it can mid-handover.
        assertEquals(NavChrome.Full, level(header = true, strip = true))
    }

    @Test
    fun `focus in the strip draws the strip alone`() {
        assertEquals(NavChrome.Strip, level(strip = true))
        assertTrue(level(strip = true).stripVisible)
        assertFalse(level(strip = true).barVisible)
    }

    @Test
    fun `focus in content draws neither`() {
        assertEquals(NavChrome.Hidden, level())
        assertFalse(level().stripVisible)
        assertFalse(level().barVisible)
    }

    /**
     * Home, Sport, Settings and Search have no top-edge control, so they never
     * report one. They must still retract and still come back — a bar whose
     * presence depends on which tab you are on is a rule no viewer can name,
     * and it reads as a bug.
     */
    @Test
    fun `a tab with no strip still retracts and still returns`() {
        assertEquals(NavChrome.Hidden, level(strip = false))
        assertEquals(NavChrome.Full, level(header = true, strip = false))
    }

    /** The bar is never drawn without the strip: Full is both, Strip is one. */
    @Test
    fun `the bar never appears alone`() {
        for (chrome in NavChrome.entries) {
            if (chrome.barVisible) {
                assertTrue("$chrome draws the bar without the strip", chrome.stripVisible)
            }
        }
    }
}
