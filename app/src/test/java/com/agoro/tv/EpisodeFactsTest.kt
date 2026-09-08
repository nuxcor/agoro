package com.agoro.tv

import com.agoro.tv.data.Episode
import com.agoro.tv.data.EpisodeFacts
import com.agoro.tv.data.TmdbEpisode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The rule that decides which of two sources reaches an episode row.
 *
 * The asymmetry is the thing under test: every rule fills a hole and none of
 * them prefers a source, because a "better source wins" rule would eventually
 * paint TMDB's runtime for a broadcast cut over the panel's runtime for the
 * file the Play button actually starts.
 */
class EpisodeFactsTest {

    private fun ep(
        num: Int = 1,
        season: Int = 1,
        title: String = "",
        poster: String? = null,
        plot: String? = null,
        runtime: Int? = null,
        air: String? = null,
    ) = Episode(
        id = "ep:$num", title = title, season = season, episodeNum = num,
        url = "http://x/$num.mkv", poster = poster, plot = plot,
        runtimeMinutes = runtime, airDate = air,
    )

    private fun tmdb(
        num: Int = 1,
        name: String? = "Pilot",
        overview: String? = "A synopsis.",
        still: String? = "https://image.tmdb.org/t/p/w300/a.jpg",
        air: String? = "2008-01-20",
        runtime: Int? = 58,
    ) = TmdbEpisode(num, name, overview, still, air, runtime)

    @Test
    fun `a complete panel episode is returned untouched`() {
        val complete = ep(
            title = "Felina", poster = "https://p/still.jpg", plot = "Panel plot.",
            runtime = 55, air = "2013-09-29",
        )
        // The SAME instance: the caller assigns this into Compose state, and
        // an equal-but-new object there is a row recomposed to redraw itself.
        assertSame(complete, EpisodeFacts.fill(complete, tmdb()))
    }

    @Test
    fun `a blank title is the hole TMDB fills`() {
        assertEquals("Pilot", EpisodeFacts.fill(ep(title = ""), tmdb()).title)
    }

    @Test
    fun `a title the provider chose is never replaced`() {
        assertEquals("Panel's name", EpisodeFacts.fill(ep(title = "Panel's name"), tmdb()).title)
    }

    @Test
    fun `TMDB's own Episode N placeholder is not a name`() {
        // TMDB fills unnamed episodes with the literal "Episode 5". Stored
        // raw that reaches the row as "5. Episode 5".
        val filled = EpisodeFacts.fill(ep(num = 5, title = ""), tmdb(num = 5, name = "Episode 5"))
        assertEquals("", filled.title)
    }

    @Test
    fun `a missing still is filled and a good one is kept`() {
        assertTrue(EpisodeFacts.fill(ep(poster = null), tmdb()).poster!!.contains("w300"))
        assertEquals(
            "https://panel/still.jpg",
            EpisodeFacts.fill(ep(poster = "https://panel/still.jpg"), tmdb()).poster,
        )
    }

    @Test
    fun `a badged poster loses to TMDB's clean one`() {
        // photo-tmdb.com paints "4K UltraHD" onto everything it serves.
        val badged = ep(poster = "https://photo-tmdb.com/abc/still.jpg")
        assertTrue(EpisodeFacts.fill(badged, tmdb()).poster!!.contains("image.tmdb.org"))
    }

    @Test
    fun `a badged poster survives when TMDB has no still`() {
        val badged = ep(poster = "https://photo-tmdb.com/abc/still.jpg")
        val filled = EpisodeFacts.fill(badged, tmdb(still = null))
        assertEquals("https://photo-tmdb.com/abc/still.jpg", filled.poster)
    }

    @Test
    fun `runtime and air date fill only when absent`() {
        val filled = EpisodeFacts.fill(ep(), tmdb())
        assertEquals(58, filled.runtimeMinutes)
        assertEquals("2008-01-20", filled.airDate)
        val kept = EpisodeFacts.fill(ep(runtime = 47, air = "2009-02-02"), tmdb())
        assertEquals(47, kept.runtimeMinutes)
        assertEquals("2009-02-02", kept.airDate)
    }

    @Test
    fun `a season list is returned unchanged when nothing moved`() {
        val eps = listOf(ep(1, title = "A", poster = "p", plot = "x", runtime = 1, air = "2020-01-01"))
        assertSame(eps, EpisodeFacts.fill(eps, season = 1, tmdb = mapOf(1 to tmdb())))
    }

    @Test
    fun `an empty TMDB answer is returned unchanged`() {
        val eps = listOf(ep(1))
        assertSame(eps, EpisodeFacts.fill(eps, season = 1, tmdb = emptyMap()))
    }

    @Test
    fun `only the named season is touched`() {
        val eps = listOf(ep(num = 1, season = 1), ep(num = 1, season = 2))
        val out = EpisodeFacts.fill(eps, season = 1, tmdb = mapOf(1 to tmdb()))
        assertEquals("Pilot", out[0].title)
        assertEquals("", out[1].title)
    }

    // --- normalisers ---------------------------------------------------------

    @Test
    fun `air dates are normalised and junk is rejected`() {
        assertEquals("2024-03-12", EpisodeFacts.airDate("2024-03-12"))
        assertEquals("2024-03-12", EpisodeFacts.airDate("2024-03-12 00:00:00"))
        assertEquals("2024-03-12", EpisodeFacts.airDate("2024-03-12T18:00:00Z"))
        assertNull(EpisodeFacts.airDate(""))
        assertNull(EpisodeFacts.airDate("0000-00-00"))
        assertNull(EpisodeFacts.airDate("12/03/2024"))
        assertNull(EpisodeFacts.airDate(null))
    }

    @Test
    fun `minutes matches the arithmetic prettyDuration had`() {
        assertEquals(121, EpisodeFacts.minutes("02:01:00"))
        assertEquals(42, EpisodeFacts.minutes("42:00"))
        // A bare number is minutes, not seconds: it is what panels write in
        // episode_run_time, and read as seconds every episode would say 1m.
        assertEquals(42, EpisodeFacts.minutes("42"))
        assertNull(EpisodeFacts.minutes("00:00:00"))
        assertNull(EpisodeFacts.minutes("abc"))
        assertNull(EpisodeFacts.minutes(null))
    }

    @Test
    fun `duration_secs is seconds, not minutes`() {
        assertEquals(42, EpisodeFacts.minutesOfSeconds("2520"))
        assertEquals(1, EpisodeFacts.minutesOfSeconds("45"))
        assertNull(EpisodeFacts.minutesOfSeconds("0"))
        assertNull(EpisodeFacts.minutesOfSeconds(null))
    }
}
