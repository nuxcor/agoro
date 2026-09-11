package com.agoro.tv

import com.agoro.tv.data.PlayableItem
import com.agoro.tv.data.StallAction
import com.agoro.tv.data.carriesOtherEvents
import com.agoro.tv.data.feedPosition
import com.agoro.tv.data.feedsOf
import com.agoro.tv.data.stallAction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
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

    /** A plain channel: alternates, but nothing naming them. */
    private val channel = PlayableItem(
        url = "http://p/live/u/p/9.ts",
        title = "TNT Sports 1",
        fallbackUrls = listOf("http://p/live/u/p/10.ts"),
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

    @Test
    fun `only a fixture's alternates are other events`() {
        assertTrue("a fixture names each rung", carriesOtherEvents(fixture))
        assertFalse("a channel's rungs are the same channel", carriesOtherEvents(channel))
    }

    /**
     * Reported 2026-09-11, mid-game: "one moment its the correct game next its
     * not". Three stalls in a minute moved the ladder to the next pipe — a
     * boxing card — and said nothing.
     *
     * A fixture re-wraps first because that rung cannot change what is on
     * screen. A channel keeps the old order, where the re-wrap is the rung
     * that costs picture.
     */
    @Test
    fun `a fixture re-wraps before it abandons the pipe`() {
        assertEquals(
            StallAction.REWRAP,
            stallAction(otherEvents = true, canRewrap = true, canHop = true),
        )
        assertEquals(
            StallAction.HOP,
            stallAction(otherEvents = false, canRewrap = true, canHop = true),
        )
    }

    /**
     * The protection is only as available as the rung it prefers. A fixture
     * led by a broadcaster's own origin HLS has no Xtream form to re-wrap
     * into, so it hops on the first run of stalls exactly as before — stated
     * here so the limit is known rather than assumed away.
     */
    @Test
    fun `a fixture with nothing to re-wrap still hops`() {
        assertEquals(
            StallAction.HOP,
            stallAction(otherEvents = true, canRewrap = false, canHop = true),
        )
        assertEquals(
            StallAction.REWRAP,
            stallAction(otherEvents = false, canRewrap = true, canHop = false),
        )
        assertEquals(
            "both rungs spent, and the caller has to say so",
            StallAction.EXHAUSTED,
            stallAction(otherEvents = true, canRewrap = false, canHop = false),
        )
    }

    /**
     * The hop message and the switcher's message are one sentence, because a
     * viewer should not learn two vocabularies for the ladder moving. The
     * position carries the part the automatic case most needs — how many feeds
     * are left — and makes each message distinct, which the status toast needs
     * because it is keyed on the string.
     */
    @Test
    fun `a feed is positioned the same way whoever moved it`() {
        assertEquals("Feed 2 of 3 · ESPN+ PPV 39", feedPosition(1, 3, "ESPN+ PPV 39"))
        // A slot whose name reduced to nothing must not leave a dangling "·".
        assertEquals("Feed 2 of 3", feedPosition(1, 3, "  "))
        assertEquals("Feed 2 of 3", feedPosition(1, 3, null))
        assertNull("one feed is not a position", feedPosition(0, 1, "TNT Sports 1"))
        assertNull("nor is a rung off the end", feedPosition(3, 3, "TNT Sports 1"))
    }
}
