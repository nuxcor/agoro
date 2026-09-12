package com.agoro.tv

import com.agoro.tv.data.TextNorm
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Two faults the guide's own data carries, both of which reached the screen.
 *
 * Broadcasters write accessibility flags into the XMLTV title itself —
 * "**Visually Signed**The Highland Vet" — and the Home hero printed the
 * asterisks verbatim, on the first screen most viewers ever see. Separately
 * the panel fills schedule gaps with a two-hour entry called "TV Guide
 * unavailable", and the guide dressed it as a programme: an ON NOW chip, a
 * progress bar and "29 minutes left" counting down to a show that does not
 * exist.
 */
class ProgrammeTitleTest {

    @Test
    fun `paired asterisk flags leave the programme name`() {
        assertEquals(
            "The Highland Vet",
            TextNorm.cleanProgrammeTitle("**Visually Signed**The Highland Vet"),
        )
        assertEquals("Pointless", TextNorm.cleanProgrammeTitle("**New**Pointless"))
        // A flag at the end is as common as one at the start.
        assertEquals("Bargain Hunt", TextNorm.cleanProgrammeTitle("Bargain Hunt **Subtitled**"))
    }

    @Test
    fun `bracketed broadcast flags go too`() {
        assertEquals("Bargain Hunt", TextNorm.cleanProgrammeTitle("[S]Bargain Hunt"))
        assertEquals("Homes Under the Hammer", TextNorm.cleanProgrammeTitle("(R) Homes Under the Hammer"))
        assertEquals("Countryfile", TextNorm.cleanProgrammeTitle("[AD][S] Countryfile"))
    }

    /**
     * The repeat marker is the one flag that can hide inside a word, and the
     * obvious guard for it is wrong. Fencing the marker on BOTH sides saves
     * "Best F(r)iends" by also sparing every "HD(R)" — a shape this provider
     * ships seven of. Only what FOLLOWS the marker separates the two.
     */
    @Test
    fun `the repeat marker is a token, not a letter pair inside a word`() {
        assertEquals(
            "Best F(r)iends: Volume 2",
            TextNorm.cleanProgrammeTitle("Best F(r)iends: Volume 2"),
        )
        assertEquals("CP24 HD", TextNorm.cleanProgrammeTitle("CP24 HD (R)"))
        assertEquals("CTV2 LONDON HD", TextNorm.cleanProgrammeTitle("CTV2 LONDON HD(R)"))
        assertEquals("CP24 HD BACKUP", TextNorm.cleanProgrammeTitle("CP24 HD (R) BACKUP"))
    }

    @Test
    fun `ordinary titles are handed back untouched`() {
        // The cheap guard must not mangle the overwhelmingly common case, and
        // brackets that carry meaning are not flags.
        assertEquals("The First 48", TextNorm.cleanProgrammeTitle("The First 48"))
        assertEquals("Match of the Day", TextNorm.cleanProgrammeTitle("Match of the Day"))
        assertEquals(
            "Storage Wars (Australia)",
            TextNorm.cleanProgrammeTitle("Storage Wars (Australia)"),
        )
    }

    @Test
    fun `a title that is nothing but flags keeps its original text`() {
        // An empty cell says less than a strange one.
        assertEquals("**New**", TextNorm.cleanProgrammeTitle("**New**"))
    }

    @Test
    fun `the panel's filler entries are not programmes`() {
        assertTrue(TextNorm.isProgrammePlaceholder("TV Guide unavailable"))
        assertTrue(TextNorm.isProgrammePlaceholder("tv guide unavailable"))
        assertTrue(TextNorm.isProgrammePlaceholder("No information"))
        assertTrue(TextNorm.isProgrammePlaceholder("  Unavailable  "))
        assertTrue(TextNorm.isProgrammePlaceholder(""))
        assertTrue(TextNorm.isProgrammePlaceholder(null))
    }

    @Test
    fun `real programmes are not mistaken for filler`() {
        assertFalse(TextNorm.isProgrammePlaceholder("The First 48"))
        assertFalse(TextNorm.isProgrammePlaceholder("News"))
        // Close to the filler wording, but a real broadcast.
        assertFalse(TextNorm.isProgrammePlaceholder("The Unavailable Man"))
    }
}
