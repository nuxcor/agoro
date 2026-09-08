package com.agoro.tv

import com.agoro.tv.ui.screens.airLabel
import com.agoro.tv.ui.screens.episodeMeta
import com.agoro.tv.ui.screens.runtimeLabel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The one right-anchored line on an episode row. */
class EpisodeMetaTest {

    private val today = "2026-09-07"

    @Test
    fun `a part-watched episode still reads as itself`() {
        // "Resume from 1h 12m" used to take this line alone. It was the
        // progress bar across the still, said again in words, and it cost the
        // row the date and the runtime.
        assertEquals("20 Jan 2008 · 58m", episodeMeta(58, "2008-01-20", today))
    }

    @Test
    fun `runtime and air date share the line, runtime last`() {
        // Right-anchored, so the final token is the one whose column stays
        // stable down the list — and dates are not ("1 Mar" vs "12 Mar 2024").
        assertEquals(
            "20 Jan 2008 · 58m",
            episodeMeta(runtimeMinutes = 58, airDate = "2008-01-20", todayIso = today),
        )
    }

    @Test
    fun `an episode from this year drops the year`() {
        assertEquals("3 Mar · 58m", episodeMeta(58, "2026-03-03", today))
    }

    @Test
    fun `an unaired episode says so`() {
        assertEquals("Airs 20 Dec", airLabel("2026-12-20", today))
        assertEquals("Airs 3 Jan 2027", airLabel("2027-01-03", today))
    }

    @Test
    fun `either half alone still fills the line`() {
        assertEquals("58m", episodeMeta(58, null, today))
        assertEquals("20 Jan 2008", episodeMeta(null, "2008-01-20", today))
    }

    @Test
    fun `nothing known is no line at all`() {
        assertNull(episodeMeta(null, null, today))
    }

    @Test
    fun `runtime labels read as hours and minutes`() {
        assertEquals("58m", runtimeLabel(58))
        assertEquals("1h 2m", runtimeLabel(62))
        assertEquals("2h", runtimeLabel(120))
        assertNull(runtimeLabel(0))
        assertNull(runtimeLabel(null))
    }

    @Test
    fun `the worst case is no wider than what the slot already shipped`() {
        // The layout argument for keeping this to one line.
        val worst = episodeMeta(62, "2008-12-20", today)!!
        assertTrue(worst, worst.length <= 20)
    }
}
