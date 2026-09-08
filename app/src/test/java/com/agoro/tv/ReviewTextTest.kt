package com.agoro.tv

import com.agoro.tv.data.ReviewText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * These are the shapes real TMDB reviews arrive in. The detail page used to
 * print all of them verbatim.
 */
class ReviewTextTest {

    private val long = "and this is the padding that carries the review past the useful-length floor so it survives."

    @Test fun `strips html tags`() {
        val out = ReviewText.clean("<em>Dune</em> is a triumph of craft over story, $long")
        assertEquals("Dune is a triumph of craft over story, $long", out)
    }

    @Test fun `strips a bare url`() {
        val out = ReviewText.clean("Full review at https://someblog.example.com/2024/dune-part-two — but briefly, $long")
        assertTrue(out!!, !out.contains("http"))
        assertTrue(out, out.contains("but briefly"))
    }

    @Test fun `unescapes entities`() {
        val out = ReviewText.clean("Villeneuve &amp; Fraser shoot it like a documentary; it&#39;s stunning. $long")
        assertTrue(out!!, out.startsWith("Villeneuve & Fraser"))
        assertTrue(out, out.contains("it's stunning"))
    }

    @Test fun `strips markdown emphasis but keeps the words`() {
        val out = ReviewText.clean("**Dune** is _essential_ viewing, honestly, $long")
        assertTrue(out!!, out.startsWith("Dune is essential viewing"))
    }

    @Test fun `collapses whitespace and newlines`() {
        val out = ReviewText.clean("Line one.\n\n   Line two. $long")
        assertEquals("Line one. Line two. $long", out)
    }

    @Test fun `a review that was only a link is dropped`() {
        assertNull(ReviewText.clean("https://someblog.example.com/dune"))
        assertNull(ReviewText.clean("<p>Read more: <a href=\"https://x.example\">here</a></p>"))
    }

    @Test fun `blank and null are dropped`() {
        assertNull(ReviewText.clean(null))
        assertNull(ReviewText.clean("   \n  "))
    }

    @Test fun `a fragment too short to read is dropped`() {
        assertNull(ReviewText.clean("Loved it."))
    }

    @Test fun `long reviews are cut on a word boundary`() {
        val out = ReviewText.clean("word ".repeat(200))!!
        assertTrue(out, out.length <= ReviewText.MAX_LENGTH + 1)
        assertTrue(out, out.endsWith("…"))
        assertTrue(out, !out.contains("wor…"))
    }

    @Test fun `tags are stripped before truncation, not after`() {
        // The tag sits past 280 chars: truncating first would leave it whole.
        val out = ReviewText.clean("a".repeat(300) + " <strong>shouted</strong>")!!
        assertTrue(out, !out.contains("<"))
    }

    @Test fun `plain prose is untouched apart from trimming`() {
        val plain = "A patient, unshowy film that trusts its audience more than most, $long"
        assertEquals(plain, ReviewText.clean("  $plain  "))
    }

    // --- bodies taken verbatim from TMDB, 2026-09-08 -------------------------

    @Test fun `real review - a link and its house-style preamble`() {
        val out = ReviewText.clean(
            "FULL SPOILER-FREE REVIEW @ https://talkingfilms.net/dune-part-two-review/\r\n\r\n" +
                "\"Dune: Part Two surpasses even the highest expectations, establishing itself " +
                "as an unquestionable technical masterpiece of blockbuster filmmaking."
        )!!
        assertTrue(out, !out.contains("http"))
        assertTrue(out, out.startsWith("FULL SPOILER-FREE REVIEW \"Dune: Part Two"))
    }

    @Test fun `real review - em tags around every film title`() {
        val out = ReviewText.clean(
            "As anticipated, a thrilling watch!\r\n\r\nI enjoyed <em>'Dune'</em>, though remember " +
                "thinking it was obviously a complete set-up to a sequel and that this would " +
                "only improve upon its predecessor."
        )!!
        assertTrue(out, !out.contains("<"))
        assertTrue(out, out.contains("I enjoyed 'Dune', though"))
    }

    @Test fun `real review - a whole html article`() {
        val out = ReviewText.clean(
            "<article>\r\n  <h1>Inception: A Mind-Bending Masterpiece</h1>\r\n\r\n" +
                "  <h2>Christopher Nolan's Visionary Dreamscape</h2>\r\n  <p>Nolan's " +
                "\"Inception\" (2010) stands as a testament to original storytelling.</p>"
        )!!
        assertTrue(out, !out.contains("<") && !out.contains(">"))
        assertTrue(out, out.startsWith("Inception: A Mind-Bending Masterpiece"))
    }

    @Test fun `real review - a markdown blockquote`() {
        val out = ReviewText.clean(
            "> **Oppenheimer:** I feel like I have blood on my hands, sir.\r\n> \r\n" +
                "> **Truman:** You think anyone in Hiroshima gives a damn who built the bomb?" +
                "\r\n\r\nan incredibly well-written and well-acted piece of drama"
        )!!
        assertTrue(out, !out.contains(">"))
        assertTrue(out, !out.contains("*"))
        assertTrue(out, out.startsWith("Oppenheimer: I feel like"))
    }

    @Test fun `real review - a bold marker that never closes cleanly`() {
        // Note the space inside the closing "** " — the pair cannot match.
        val out = ReviewText.clean(
            "**OPPENHEIMER IS \"NOT FOR EVERYONE\" AS STATED BY NOLAN HIMSELF. ** But those " +
                "who have ample knowledge of physics will find this film a masterpiece."
        )!!
        assertTrue(out, !out.contains("*"))
        assertTrue(out, out.startsWith("OPPENHEIMER IS"))
    }

    @Test fun `real review - single underscore italics`() {
        val out = ReviewText.clean(
            "_The Super Mario Bros. Movie_ is like Fruit Stripe Gum. It's super colorful and " +
                "eyecatching, but it seems to instantly lose its flavor and charm."
        )!!
        assertTrue(out, !out.contains("_"))
        assertTrue(out, out.startsWith("The Super Mario Bros. Movie is like"))
    }

    @Test fun `real review - b and i tags mixed`() {
        val out = ReviewText.clean(
            "<b>INT. GANGSTERS IN OKLAHOMA - DAY</b>\r\n\r\n\r\nFilm students, film lovers, " +
                "and reviewers rejoice! <i>Martin Scorsese's</i> latest film is excellent!"
        )!!
        assertTrue(out, !out.contains("<"))
        assertTrue(out, out.contains("Martin Scorsese's latest film is excellent!"))
    }

    @Test fun `real review - a trailing visit-here link`() {
        val out = ReviewText.clean(
            "While beautifully mounted, scored and acted, Scorsese's latest feature suffers by " +
                "failing to provide motivations for its characters.\r\n\r\nFor full review, " +
                "visit: https://www.thehindu.com/entertainment/movies/killers-review"
        )!!
        assertTrue(out, !out.contains("http"))
        assertTrue(out, out.endsWith("For full review, visit"))
    }

    @Test fun `snake_case survives the italic strip`() {
        val out = ReviewText.clean(
            "The variable named some_long_name is never explained, and honestly that is the " +
                "least of this film's problems as far as I am concerned."
        )!!
        assertTrue(out, out.contains("some_long_name"))
    }
}
