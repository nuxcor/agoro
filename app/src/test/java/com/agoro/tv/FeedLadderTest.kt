package com.agoro.tv

import com.agoro.tv.data.PlayableItem
import com.agoro.tv.data.feedsOf
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The list behind the feed switcher.
 *
 * A fixture is carried by several pipes and the names on them are the only
 * evidence about what each one is showing — evidence that is routinely wrong,
 * which is why the viewer needs to see which one is up and step off it. That
 * makes the ladder something the UI reads, not just something the failure
 * path walks, and it has to include the source already playing.
 */
class FeedLadderTest {

    private val fixture = PlayableItem(
        url = "http://p/live/u/p/1.ts",
        title = "Barcelona v Feyenoord",
        fallbackUrls = listOf("http://p/live/u/p/2.ts", "http://p/live/u/p/3.ts"),
        fallbackTitles = listOf(
            "Barcelona v Feyenoord · Spanish commentary",
            "Barcelona v Feyenoord",
        ),
        sourceNames = listOf("TNT Sports 3", "ESPN+ PPV 39 · Spanish commentary", "UEFA 04"),
        sourceChannelIds = listOf("tnt3", "", "1025277"),
    )

    @Test
    fun `the source playing is the head of the ladder, not missing from it`() {
        val feeds = feedsOf(fixture)
        assertEquals(3, feeds.size)
        assertEquals("http://p/live/u/p/1.ts", feeds[0].url)
        assertEquals("TNT Sports 3", feeds[0].label)
        assertEquals("Barcelona v Feyenoord", feeds[0].title)
    }

    @Test
    fun `each rung keeps its own title, which is not the same on every one`() {
        val feeds = feedsOf(fixture)
        assertEquals("Barcelona v Feyenoord · Spanish commentary", feeds[1].title)
        assertEquals("ESPN+ PPV 39 · Spanish commentary", feeds[1].label)
        assertEquals("UEFA 04", feeds[2].label)
    }

    /**
     * The banner hangs its logo, its now/next and its star off the channel
     * id, so the id has to travel with the stream — a fixture's rungs are
     * different channels, not one channel twice.
     */
    @Test
    fun `the channel id follows the rung, and is null where a slot has none`() {
        val feeds = feedsOf(fixture)
        assertEquals("tnt3", feeds[0].channelId)
        assertEquals(null, feeds[1].channelId)
        assertEquals("1025277", feeds[2].channelId)
    }

    /**
     * Every caller but the fixture one leaves these empty, and a channel's
     * alternates are one stream at several qualities — nothing to name.
     */
    @Test
    fun `an unnamed ladder still counts`() {
        val channel = PlayableItem(
            url = "http://p/live/u/p/9.ts",
            title = "TNT Sports 1",
            fallbackUrls = listOf("http://p/live/u/p/10.ts"),
        )
        val feeds = feedsOf(channel)
        assertEquals(2, feeds.size)
        // The title stands on every rung, which is right for a channel.
        assertEquals("TNT Sports 1", feeds[1].title)
        assertEquals(listOf("Feed 1", "Feed 2"), feeds.map { it.label })
    }

    @Test
    fun `a single source is a ladder of one`() {
        assertEquals(1, feedsOf(PlayableItem(url = "http://p/1.ts", title = "One")).size)
    }
}
