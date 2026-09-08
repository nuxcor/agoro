package com.agoro.tv.ui.screens

/**
 * The strings on the trailing edge of an episode row.
 *
 * Pure, and in the same package as [SeriesDetailScreen] so the call site
 * needs no import — the arrangement [upNext] already uses, and for the same
 * reason: this is the part with the rules in it, and rules want tests.
 */

/**
 * Today as "YYYY-MM-DD" — the one impure input, taken once per screen.
 *
 * A string, because [com.agoro.tv.data.Episode.airDate] is one: an air date
 * is a calendar day, it compares correctly lexicographically in this shape,
 * and comparing it this way needs no timezone and no date library. minSdk is
 * 23 here with no core-library desugaring, so `java.time` is not available
 * anyway.
 */
internal fun todayIso(nowMs: Long = System.currentTimeMillis()): String {
    val cal = java.util.Calendar.getInstance()
    cal.timeInMillis = nowMs
    return "%04d-%02d-%02d".format(
        cal.get(java.util.Calendar.YEAR),
        cal.get(java.util.Calendar.MONTH) + 1,
        cal.get(java.util.Calendar.DAY_OF_MONTH),
    )
}

private val MONTHS = arrayOf(
    "Jan", "Feb", "Mar", "Apr", "May", "Jun",
    "Jul", "Aug", "Sep", "Oct", "Nov", "Dec",
)

/** "54m", "1h 2m". Null for null and for zero. */
internal fun runtimeLabel(minutes: Int?): String? {
    val m = minutes?.takeIf { it > 0 } ?: return null
    val h = m / 60
    val rem = m % 60
    return when {
        h > 0 && rem > 0 -> "${h}h ${rem}m"
        h > 0 -> "${h}h"
        else -> "${rem}m"
    }
}

/**
 * "12 Mar 2024", or "12 Mar" for something that aired this year, or
 * "Airs 12 Mar" for something that has not aired yet.
 *
 * The year is dropped inside the current year because it is the half a
 * viewer already knows, and an episode list of a running show is mostly
 * this year — repeating "2026" down thirty rows spends the row's scarcest
 * space on its least surprising word.
 */
internal fun airLabel(iso: String?, todayIso: String): String? {
    val date = iso ?: return null
    if (date.length != 10) return null
    val year = date.substring(0, 4)
    val month = date.substring(5, 7).toIntOrNull()?.takeIf { it in 1..12 } ?: return null
    val day = date.substring(8, 10).toIntOrNull()?.takeIf { it in 1..31 } ?: return null
    val short = "$day ${MONTHS[month - 1]}"
    return when {
        // Lexicographic, which is exactly right for YYYY-MM-DD.
        date > todayIso -> "Airs $short${if (year != todayIso.take(4)) " $year" else ""}"
        year == todayIso.take(4) -> short
        else -> "$short $year"
    }
}

/**
 * The one line at the trailing edge of an episode row.
 *
 * It stays ONE line and one Text. The row's height invariant is that the
 * still is the tallest thing in it — a title plus three synopsis lines comes
 * to about 72dp against a 90dp still, and a fourth text line would push the
 * column past the picture and make rows different heights, which is the
 * arrangement EpisodeRow's own comment exists to prevent.
 *
 * Width is not the objection it looks like either: the longest thing it can
 * print is "12 Mar 2024 · 1h 2m", at 19 characters.
 *
 * Runtime goes LAST because the string is right-anchored, so the final token
 * is the one whose column stays stable down the list; dates are not ("1 Mar"
 * against "12 Mar 2024").
 *
 * A part-watched episode says nothing extra here. It used to read "Resume
 * from 1h 12m", which is the progress bar across the foot of the still
 * translated into words — and it cost the row the two facts the bar cannot
 * give: what the episode is called out to, and how long it runs. The bar
 * already says where you are, in the place you are already looking.
 */
internal fun episodeMeta(
    runtimeMinutes: Int?,
    airDate: String?,
    todayIso: String,
): String? {
    val parts = listOfNotNull(airLabel(airDate, todayIso), runtimeLabel(runtimeMinutes))
    return parts.takeIf { it.isNotEmpty() }?.joinToString(" · ")
}
