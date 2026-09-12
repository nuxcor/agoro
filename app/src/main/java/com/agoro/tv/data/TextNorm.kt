package com.agoro.tv.data

/**
 * Unicode normalization for names nobody typed by hand.
 *
 * Providers decorate stream names with modifier letters and superscript
 * digits — "ESPN ᵁᴴᴰ ³⁸⁴⁰ᴾ", "YAHOO SPORTS NETWORK ᴿᴬᵂ ⁶⁰ᶠᵖˢ". Unicode
 * classifies those glyphs as LETTERS (category Lm) and NUMBERS (category
 * No), so every ASCII-anchored cleanup rule in this package walked straight
 * past them and `Char.isLetterOrDigit` waved them into identity keys:
 * "ESPN ᴴᴰ", "ESPN ᵁᴴᴰ ³⁸⁴⁰ᴾ" and "ESPN HD" were three channels with three
 * keys, three guide lookups and no merge between them.
 *
 * NFD — what [EpgMatcher.fold] used to normalize with — only decomposes
 * accents. These glyphs carry COMPATIBILITY decompositions, so NFKD is what
 * folds them back into "UHD 3840P" and "RAW 60fps" where the existing
 * regexes can finally see them.
 */
object TextNorm {

    /**
     * The decoration blocks, deliberately narrow rather than all of Lm/No:
     * ²³¹, modifier small letters, the phonetic-extension capitals, and the
     * superscript block. Japanese prolonged-sound ー (U+30FC) and
     * the modifier apostrophe ʼ (U+02BC, "Hawaiʻi") are Lm too and belong to
     * real names, so a blanket `\p{Lm}` would quietly mangle them — hence
     * the two split ranges through the modifier block rather than one.
     */
    // Ends at U+207F, not U+209F: the block continues into SUBSCRIPTS, which
    // are not provider decoration. "H₂O TV" came out as "H O TV".
    private const val DECOR = "²³¹ʰ-ʸˠ-ˤᴬ-ᶿ\u2070-\u207F"

    /** A run of decoration, plus the spaces between adjacent runs. */
    private val decorRun = Regex("""[$DECOR]+(?:\s+[$DECOR]+)*""")

    private val multiSpace = Regex("""\s{2,}""")

    /**
     * Drops decorative superscript runs outright: "ESPN ᵁᴴᴰ ³⁸⁴⁰ᴾ" → "ESPN",
     * "YAHOO SPORTS NETWORK ᴿᴬᵂ" → "YAHOO SPORTS NETWORK".
     *
     * Dropping the run beats decomposing it and then matching "raw"/"60fps"
     * as words: the tokens only ever mean decoration when they arrive as
     * superscript, and a channel genuinely called "WWE RAW" writes it in
     * plain ASCII. Quality still survives the drop — [QualityTag.of] reads
     * the tier off [compat] before this runs.
     */
    fun stripDecoration(text: String): String {
        if (isPlain(text)) return text
        return text.replace(decorRun, " ").replace(multiSpace, " ").trim()
    }

    /**
     * NFKD with case preserved, for the one caller that must READ the
     * decoration rather than drop it.
     */
    fun compat(text: String): String {
        if (isPlain(text)) return text
        return java.text.Normalizer.normalize(text, java.text.Normalizer.Form.NFKD)
    }

    /**
     * Cheap guard for the overwhelmingly common case. This runs per channel
     * across playlists in the thousands; without it every plain-ASCII name
     * pays for a Normalizer pass or a regex scan that can only hand it back
     * unchanged.
     */
    private fun isPlain(text: String): Boolean {
        for (c in text) if (c.code >= 0x80) return false
        return true
    }

    /**
     * Broadcaster accessibility flags, written into the XMLTV title itself:
     * "**Visually Signed**The Highland Vet", "[S]Bargain Hunt", "(R) Pointless".
     *
     * They are markers about the broadcast, not part of the programme's name,
     * and they arrived on screen verbatim — asterisks and all — in the Home
     * hero, which is where most viewers meet a live channel. A TV app shows
     * the programme; the flag is not information a viewer acts on.
     */
    /**
     * The parenthesised repeat marker is the one form that can hide inside an
     * ordinary word, so it alone is case-sensitive and fenced on the right.
     * Unfenced it edited real titles: this provider carries a film called
     * "Best F(r)iends: Volume 2", which came out as "Best F iends".
     *
     * The fence is a lookahead only, and the case matters. What follows the
     * marker is the whole signal: a flag is the last thing in its token
     * ("CP24 HD (R)", "CTV2 LONDON HD(R)") while a word carries on through it
     * ("F(r)iends"). A lookbehind as well would have been the obvious guess
     * and it is wrong — it saves "F(r)iends" by also sparing every "HD(R)".
     */
    private val programmeFlag = Regex(
        """\*\*[^*]{1,24}\*\*|(?i:\[(?:S|AD|HD|R|N|SL)])|\((?:R|N)\)(?![A-Za-z])""",
    )

    private val titleSpace = Regex("""\s{2,}""")

    /**
     * A programme title as a viewer should read it. Never returns blank: a
     * title that is nothing BUT flags keeps its original text, because an
     * empty cell says less than a strange one.
     */
    fun cleanProgrammeTitle(raw: String): String {
        if (raw.isEmpty()) return raw
        // This runs once per programme while parsing an XMLTV file that carries
        // tens of thousands of them, and the overwhelming majority carry no
        // flag at all. A flag cannot exist without one of these three opening
        // characters, so the cheap scan buys every ordinary title its way out
        // before the regex is built a matcher.
        if (raw.indexOf('*') < 0 && raw.indexOf('[') < 0 && raw.indexOf('(') < 0) return raw
        // The flags always sit at an edge and run together with the title
        // ("**Visually Signed**The Highland Vet" has no space after them), so
        // the replacement is a space and the collapse afterwards is required.
        val stripped = raw.replace(programmeFlag, " ")
            .replace(titleSpace, " ")
            .trim()
            .trim('-', '–', ':', '·')
            .trim()
        return stripped.ifEmpty { raw.trim() }
    }

    /**
     * Titles that mean "this channel sent no schedule", dressed as programmes.
     *
     * The panel fills gaps with a two-hour entry called "TV Guide unavailable",
     * and the guide gave it everything a real programme gets: an ON NOW chip,
     * a progress bar and "29 minutes left" counting down to a show that does
     * not exist. Nothing downstream may treat one of these as a programme —
     * no countdown, no reminder, no catch-up, no synopsis.
     */
    private val placeholderTitles = setOf(
        "tv guide unavailable",
        "guide unavailable",
        "epg unavailable",
        "no information",
        "no info",
        "no information available",
        "information not available",
        "no programme data",
        "no program data",
        "no listings",
        "not available",
        "unavailable",
    )

    fun isProgrammePlaceholder(title: String?): Boolean {
        val t = title?.trim()?.lowercase() ?: return true
        if (t.isEmpty()) return true
        return t in placeholderTitles
    }
}
