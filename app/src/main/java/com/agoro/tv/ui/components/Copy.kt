package com.agoro.tv.ui.components

import java.text.SimpleDateFormat
import java.util.Locale

/**
 * The app's one way of writing a date, and its one rule for showing a rating.
 *
 * Both existed in four or five places with four or five answers. The guide
 * header wrote "Thu, 11 Sep 2026", the ruler wrote "EEE d MMM", the day chip
 * and the schedule sheet each rolled their own — and the year, which nobody
 * needs on a TV guide, rode along on two of them.
 */
object NuxFormat {

    /**
     * "Thu 11 Sep". No year: a guide reaches fourteen days, so the year is
     * noise on every single row of it, and a comma after the weekday is a
     * letterhead convention rather than a television one.
     */
    const val DAY_PATTERN = "EEE d MMM"

    fun dayFormat(): SimpleDateFormat = SimpleDateFormat(DAY_PATTERN, Locale.getDefault())

    /** 24- or 12-hour clock, whichever the box is set to. */
    const val CLOCK_PATTERN = "h:mm a"

    fun clockFormat(): SimpleDateFormat = SimpleDateFormat(CLOCK_PATTERN, Locale.getDefault())

    /**
     * Whether a provider rating is worth putting on screen.
     *
     * A 2026 series arrived showing "★ 1.0" off five votes. That is not a
     * rating, it is noise wearing a rating's clothes, and printing it tells a
     * viewer the app cannot tell the difference. Netflix never shows a score
     * it would not stand behind.
     *
     * Two gates, because they catch different faults: a rating at or below 1.0
     * is almost always an unrated title defaulting to the bottom of the scale,
     * and a handful of votes cannot hold up any score at all.
     */
    fun ratingWorthShowing(rating: Double?, voteCount: Int? = null): Boolean {
        val r = rating ?: return false
        if (r <= 1.0) return false
        if (voteCount != null && voteCount < MIN_VOTES) return false
        return true
    }

    /** Below this a score is one person's opinion, not a rating. */
    const val MIN_VOTES = 20
}
