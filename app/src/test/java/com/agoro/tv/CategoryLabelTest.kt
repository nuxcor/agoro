package com.agoro.tv

import com.agoro.tv.ui.screens.categoryLabel
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * One casing rule for every strip that names a shelf.
 *
 * The rule itself is old; what it never had was a test, and what it never had
 * was every caller. It lived beside the browse strip while Movies and Series
 * were its only users, so the guide's strip, the player's guide and the
 * player's channel list all drew the provider's own casing — the same shelf
 * reading "Streaming Networks" in one place and "Streaming networks" in
 * another. It lives in LiveCategories.kt now, with the rest of the shared
 * vocabulary.
 *
 * The rule is narrower than "sentence case", and the narrowness is the point:
 * only a PLAIN Title-Case word is lowered, and the first word is only given a
 * capital when it had none at all. Everything below is one of those two edges.
 */
class CategoryLabelTest {

    @Test
    fun `plain title case comes down to a sentence`() {
        assertEquals("Top rated", categoryLabel("Top Rated"))
        assertEquals("Streaming networks", categoryLabel("Streaming Networks"))
        assertEquals("New releases", categoryLabel("New Releases"))
        assertEquals("African cinema", categoryLabel("African Cinema"))
    }

    /** The common shelves are one word and come through untouched. */
    @Test
    fun `a single title-case word is already a sentence`() {
        assertEquals("News", categoryLabel("News"))
        assertEquals("Entertainment", categoryLabel("Entertainment"))
        assertEquals("Sports", categoryLabel("Sports"))
    }

    /**
     * The edge that matters most. The FIRST word keeps whatever case it
     * arrived with unless it had no capital at all — which is what stops
     * "PPV & Events" becoming "Ppv & events", the failure the rule was written
     * against. Note that "Events" still comes down: it is a plain word in a
     * later position, and lowering it is the whole job.
     */
    @Test
    fun `a shout in first position is not turned into a name`() {
        assertEquals("PPV & events", categoryLabel("PPV & Events"))
        assertEquals("UK", categoryLabel("UK"))
        assertEquals("4K", categoryLabel("4K"))
    }

    /**
     * A word carrying a digit or its own punctuation is not a plain word, so
     * it is never lowered wherever it sits.
     */
    @Test
    fun `digits and hyphenated spellings are left alone`() {
        assertEquals("Movies 24/7", categoryLabel("Movies 24/7"))
        assertEquals("Sci-Fi", categoryLabel("Sci-Fi"))
        // Leading "24/7" has no capital to take, so it passes through — and
        // the plain word after it still comes down.
        assertEquals("24/7 channels", categoryLabel("24/7 Channels"))
    }

    /**
     * A brand that spells itself with a small first letter keeps it: "iPlayer"
     * carries a capital already, so the first-word branch does not touch it.
     */
    @Test
    fun `a brand that spells itself is not rewritten`() {
        assertEquals("iPlayer", categoryLabel("iPlayer"))
        assertEquals("iPlayer extra", categoryLabel("iPlayer Extra"))
    }

    @Test
    fun `an all-lowercase first word gets the sentence's capital`() {
        assertEquals("News", categoryLabel("news"))
        assertEquals("Live sport", categoryLabel("live sport"))
    }

    /** Nothing to case. The guard exists so the first-word branch can't throw. */
    @Test
    fun `empty and blank names come back untouched`() {
        assertEquals("", categoryLabel(""))
        assertEquals("   ", categoryLabel("   "))
    }
}
