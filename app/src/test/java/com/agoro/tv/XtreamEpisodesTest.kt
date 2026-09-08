package com.agoro.tv

import com.agoro.tv.data.XtreamClient
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Xtream panels emit `get_series_info`'s `episodes` container in several
 * shapes; every one of them must parse, because a shape mismatch used to
 * come out as a silent "No episodes found".
 */
class XtreamEpisodesTest {

    private val client = XtreamClient(OkHttpClient(), "http://example.com", "u", "p")

    private fun parse(json: String) = client.parseEpisodes(Json.parseToJsonElement(json))

    @Test
    fun `map of season to array parses`() {
        val eps = parse(
            """{"episodes":{"1":[{"id":"11","title":"E1","episode_num":"1"}],
                "2":[{"id":"21","title":"E2","episode_num":"1"}]}}"""
        )
        assertEquals(2, eps.size)
        assertEquals(listOf(1, 2), eps.map { it.season })
    }

    @Test
    fun `array of arrays parses`() {
        val eps = parse(
            """{"episodes":[[{"id":"11","season":"1","episode_num":"1"}],
                [{"id":"21","season":"2","episode_num":"1"}]]}"""
        )
        assertEquals(2, eps.size)
    }

    @Test
    fun `flat array of episode objects parses`() {
        val eps = parse(
            """{"episodes":[{"id":"11","season":"1","episode_num":"1"},
                {"id":"12","season":"1","episode_num":"2"}]}"""
        )
        assertEquals(2, eps.size)
        assertEquals(listOf(1, 2), eps.map { it.episodeNum })
    }

    @Test
    fun `map of season to map of episode objects parses`() {
        val eps = parse(
            """{"episodes":{"3":{"1":{"id":"31","episode_num":"1"},
                "2":{"id":"32","episode_num":"2"}}}}"""
        )
        assertEquals(2, eps.size)
        // Season only present as the container key — it must be carried over.
        assertEquals(listOf(3, 3), eps.map { it.season })
    }

    @Test
    fun `episode_id and stream_id are accepted as the id field`() {
        val eps = parse(
            """{"episodes":[{"episode_id":"7","episode_num":"1"},
                {"stream_id":"8","episode_num":"2"}]}"""
        )
        assertEquals(2, eps.size)
        assertEquals(listOf("ep:7", "ep:8"), eps.map { it.id })
    }

    @Test
    fun `numeric ids build the stream url`() {
        val eps = parse("""{"episodes":[{"id":42,"episode_num":1,"container_extension":"mkv"}]}""")
        assertEquals("http://example.com/series/u/p/42.mkv", eps.single().url)
    }

    @Test
    fun `episodes nested inside season objects are found`() {
        // Stalker-derived backends (IPTVEditor) leave the top-level container
        // empty and carry the arrays inside each season object.
        val eps = parse(
            """{"episodes":{},"seasons":[
                {"season_number":1,"name":"S1","episodes":[
                    {"id":"11","episode_num":"1"},{"id":"12","episode_num":"2"}]},
                {"season_number":2,"episodes":[{"id":"21","episode_num":"1"}]}
            ]}"""
        )
        assertEquals(3, eps.size)
        assertEquals(listOf(1, 1, 2), eps.map { it.season })
    }

    @Test
    fun `an object without episodes is genuinely empty`() {
        assertTrue(parse("""{"info":{}}""").isEmpty())
        assertTrue(parse("""{"episodes":null}""").isEmpty())
    }

    @Test
    fun `a non-object response is a failure, not an empty series`() {
        // Portals answer unknown ids (and broken proxies answer everything)
        // with 200 and a bare array — that must reach the retryable error
        // path, not render as "No episodes found".
        for (bad in listOf("""[]""", """"error"""")) {
            try {
                parse(bad)
                org.junit.Assert.fail("expected IOException for $bad")
            } catch (expected: java.io.IOException) {
            }
        }
    }

    @Test
    fun `episodes are sorted by season then number`() {
        val eps = parse(
            """{"episodes":[{"id":"1","season":"2","episode_num":"1"},
                {"id":"2","season":"1","episode_num":"2"},
                {"id":"3","season":"1","episode_num":"1"}]}"""
        )
        assertEquals(listOf("ep:3", "ep:2", "ep:1"), eps.map { it.id })
    }

    // --- the `info` block ----------------------------------------------------
    //
    // Every fixture above is the outer object only, which is how the metadata
    // path came to be entirely untested: these ten cases would all still pass
    // if poster, durationText and plot were deleted from the parse.

    @Test
    fun `an info name beats the file-shaped title`() {
        val eps = parse(
            """{"episodes":{"1":[{"id":"11","episode_num":"1",
                "title":"Lady in the Lake - S01E01 - x",
                "info":{"name":"Did you know Seahorses are fish?"}}]}}"""
        )
        assertEquals("Did you know Seahorses are fish?", eps.single().title)
    }

    @Test
    fun `a still comes from movie_image, cover_big or cover`() {
        val eps = parse(
            """{"episodes":{"1":[
                {"id":"1","episode_num":"1","info":{"movie_image":"https://image.tmdb.org/t/p/original/a.jpg"}},
                {"id":"2","episode_num":"2","info":{"cover_big":"https://image.tmdb.org/t/p/original/b.jpg"}},
                {"id":"3","episode_num":"3","info":{"cover":"https://image.tmdb.org/t/p/original/c.jpg"}}]}}"""
        )
        assertEquals(3, eps.size)
        // The w300 still rung, never the 2:3 poster crop.
        assertTrue(eps.all { it.poster!!.contains("w300") })
    }

    @Test
    fun `an array-valued movie_image is still a still`() {
        val eps = parse(
            """{"episodes":{"1":[{"id":"11","episode_num":"1",
                "info":{"movie_image":["https://image.tmdb.org/t/p/original/a.jpg"]}}]}}"""
        )
        assertTrue(eps.single().poster!!.contains("w300"))
    }

    @Test
    fun `duration_secs and duration both give runtime minutes`() {
        val eps = parse(
            """{"episodes":{"1":[
                {"id":"1","episode_num":"1","info":{"duration_secs":"2520"}},
                {"id":"2","episode_num":"2","info":{"duration":"00:42:00"}}]}}"""
        )
        assertEquals(listOf(42, 42), eps.map { it.runtimeMinutes })
    }

    @Test
    fun `a zero duration is not a runtime`() {
        val eps = parse(
            """{"episodes":{"1":[{"id":"11","episode_num":"1","info":{"duration":"00:00:00"}}]}}"""
        )
        // Null, not 0 — "the panel does not know" and "it is zero long" are
        // different answers, and only one of them may reach a row.
        assertEquals(null, eps.single().runtimeMinutes)
    }

    @Test
    fun `an air date is read from any of the keys and normalised`() {
        val eps = parse(
            """{"episodes":{"1":[
                {"id":"1","episode_num":"1","info":{"releasedate":"2024-03-12 00:00:00"}},
                {"id":"2","episode_num":"2","info":{"release_date":"2024-03-13"}},
                {"id":"3","episode_num":"3","info":{"air_date":"2024-03-14"}}]}}"""
        )
        assertEquals(listOf("2024-03-12", "2024-03-13", "2024-03-14"), eps.map { it.airDate })
    }

    @Test
    fun `a zero air date is no air date`() {
        val eps = parse(
            """{"episodes":{"1":[{"id":"11","episode_num":"1","info":{"releasedate":"0000-00-00"}}]}}"""
        )
        assertEquals(null, eps.single().airDate)
    }

    @Test
    fun `added is never mistaken for an air date`() {
        // `added` is when the panel ingested the file. Labelling every episode
        // of a 2003 show with last Tuesday is worse than carrying no date.
        val eps = parse(
            """{"episodes":{"1":[{"id":"11","episode_num":"1","added":"1786116510","info":{}}]}}"""
        )
        assertEquals(null, eps.single().airDate)
    }

    @Test
    fun `a plot falls back to overview and description`() {
        val eps = parse(
            """{"episodes":{"1":[
                {"id":"1","episode_num":"1","info":{"overview":"From overview."}},
                {"id":"2","episode_num":"2","info":{"description":"From description."}}]}}"""
        )
        assertEquals(listOf("From overview.", "From description."), eps.map { it.plot })
    }

    @Test
    fun `an episode with no info block parses exactly as before`() {
        val eps = parse("""{"episodes":{"1":[{"id":"11","title":"E1","episode_num":"1"}]}}""")
        val ep = eps.single()
        assertEquals("E1", ep.title)
        assertEquals(null, ep.poster)
        assertEquals(null, ep.runtimeMinutes)
        assertEquals(null, ep.airDate)
        assertEquals(null, ep.plot)
    }
}
