package com.agoro.tv.data

/**
 * A TMDB review, made fit to print on a television.
 *
 * Review bodies are whatever the author typed into a web form: HTML tags,
 * markdown emphasis, escaped entities and — constantly — the full URL of the
 * blog the review was copied from. The app used to collapse whitespace and
 * print the rest, so a detail page carried `<em>'Dune'</em>` and a
 * seventy-character link wrapped across three lines.
 *
 * None of it survives here. A link is unusable on a remote, and markup is the
 * clearest possible signal that nobody looked at the screen.
 */
object ReviewText {

    private val TAG = Regex("<[^>]{0,200}>")
    /**
     * A link and whatever introduced it. "FULL SPOILER-FREE REVIEW @ <url>"
     * is one reviewer's house style on every film they cover, and cutting
     * only the URL leaves the screen reading "REVIEW @" before the actual
     * review starts.
     */
    private val URL = Regex("""(?:\s+@|\s*:)?\s*https?://\S+""")
    /** Markdown quote and heading markers, which need the lines still intact. */
    private val LINE_MARKER = Regex("""(?m)^[ \t]*(?:>+|#{1,6})[ \t]*""")
    /** Bare **bold** and *italic* — the marks, not the words. */
    private val STARS = Regex("""(\*{1,3})(?=\S)(.*?)(?<=\S)\1""")
    /**
     * _italic_ and __bold__, but never snake_case: an underscore with a word
     * character on the outside of it is part of a name, not emphasis.
     */
    private val UNDERSCORES = Regex("""(?<!\w)(_{1,3})(?=\S)(.*?)(?<=\S)\1(?!\w)""")
    /**
     * Emphasis markers the paired passes could not close. Reviewers write
     * "**SHOUTED SENTENCE. ** But then" — a space inside the closing marker —
     * and a pair that never closes leaves its stars on the screen.
     */
    private val STRAY_STARS = Regex("""\*{2,}""")
    private val SPACE = Regex("""\s+""")
    /**
     * A tag becomes a space, so "<em>'Dune'</em>, though" would reach the
     * screen as "'Dune' , though". Punctuation closes up against the word it
     * belongs to once the markup between them is gone.
     */
    private val LOOSE_PUNCTUATION = Regex(""" +([,.;:!?%)\]}…])""")
    private val LOOSE_OPENER = Regex("""([(\[{]) +""")
    /** Left behind once a link is cut out of "(see [here](...))" style prose. */
    private val EMPTY_BRACKETS = Regex("""[\[(]\s*[\])]""")

    private val ENTITIES = listOf(
        "&nbsp;" to " ", "&amp;" to "&", "&lt;" to "<", "&gt;" to ">",
        "&quot;" to "\"", "&#39;" to "'", "&apos;" to "'", "&hellip;" to "…",
        "&mdash;" to "—", "&ndash;" to "–", "&rsquo;" to "'", "&lsquo;" to "'",
        "&ldquo;" to "\u201C", "&rdquo;" to "\u201D",
    )

    /**
     * How much of a review is worth a line on screen.
     *
     * Below this what is left is a fragment — usually all that survived a
     * review that was a link and a sentence — and a quotation mark around
     * three words reads as a rendering fault rather than an opinion.
     */
    const val MIN_USEFUL = 40

    /** At most this much of one review; the rest is an ellipsis. */
    const val MAX_LENGTH = 280

    /**
     * The printable text of a review, or null when nothing useful survives.
     *
     * Cleaned BEFORE truncating, deliberately: cutting at 280 characters first
     * can slice a tag in half, and then the strip has nothing to match and the
     * fragment reaches the screen.
     */
    fun clean(raw: String?): String? {
        if (raw.isNullOrBlank()) return null
        var text: String = raw
        ENTITIES.forEach { (from, to) -> text = text.replace(from, to, ignoreCase = true) }
        // Before the whitespace collapse, because these anchor to line starts.
        text = LINE_MARKER.replace(text, " ")
        text = TAG.replace(text, " ")
        text = URL.replace(text, " ")
        text = STARS.replace(text) { it.groupValues[2] }
        text = UNDERSCORES.replace(text) { it.groupValues[2] }
        text = STRAY_STARS.replace(text, "")
        text = EMPTY_BRACKETS.replace(text, " ")
        text = SPACE.replace(text, " ").trim()
        text = LOOSE_PUNCTUATION.replace(text, "$1")
        text = LOOSE_OPENER.replace(text, "$1")
        // Trailing punctuation orphaned by a cut-out link: "as I said here: ."
        text = text.trimEnd(' ', ':', '-', '—', '–', ',')
        if (text.length < MIN_USEFUL) return null
        return if (text.length > MAX_LENGTH) {
            // On a word, not mid-syllable. A hard cut reads as data damage.
            val cut = text.take(MAX_LENGTH)
            val lastSpace = cut.lastIndexOf(' ')
            (if (lastSpace > MAX_LENGTH - 40) cut.take(lastSpace) else cut)
                .trimEnd(' ', ',', ';', ':', '.') + "…"
        } else {
            text
        }
    }
}
