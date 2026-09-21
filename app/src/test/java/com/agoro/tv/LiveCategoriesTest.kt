package com.agoro.tv

import com.agoro.tv.data.Category
import com.agoro.tv.data.ContentBundle
import com.agoro.tv.data.LiveChannel
import com.agoro.tv.ui.screens.CATEGORY_ALL
import com.agoro.tv.ui.screens.CATEGORY_FAVORITES
import com.agoro.tv.ui.screens.CATEGORY_RECENT
import com.agoro.tv.ui.screens.LiveCategoryIndex
import com.agoro.tv.ui.screens.categoryLabel
import com.agoro.tv.ui.screens.channelsInCategory
import com.agoro.tv.ui.screens.CATEGORY_NONE
import com.agoro.tv.ui.screens.defaultCategoryId
import com.agoro.tv.ui.screens.liveCategoryList
import com.agoro.tv.ui.screens.resolveCategoryId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LiveCategoriesTest {

    private fun channel(id: String, categoryId: String? = "sport") =
        LiveChannel(id = id, name = "Channel $id", logo = null, url = "http://x/$id", categoryId = categoryId)

    private val sport = Category("sport", "Sport")
    private val news = Category("news", "News")
    private val channels = listOf(channel("1"), channel("2"), channel("3", "news"))
    private val bundle = ContentBundle(liveCategories = listOf(sport, news), channels = channels)

    @Test
    fun `All dedups cross-category duplicates only when asked`() {
        val dupes = listOf(
            LiveChannel(id = "a", name = "US| CNN HD", logo = null, url = "http://x/a", categoryId = "news"),
            LiveChannel(id = "b", name = "CNN FHD", logo = null, url = "http://x/b", categoryId = "sport",
                quality = "FHD"),
            LiveChannel(id = "c", name = "BBC", logo = null, url = "http://x/c", categoryId = "news"),
        )
        // The merge itself now runs off the main thread in the view model;
        // what this asserts is the contract between the two — All shows the
        // list it is handed, and nothing else is affected by it.
        val deduped = com.agoro.tv.data.QualityTag.mergeBestQuality(
            dupes,
            keyOf = { com.agoro.tv.data.EpgMatcher.normalizeKey(it.name) },
        )
        // One CNN survives (the FHD variant outranks the HD one) plus BBC.
        assertEquals(listOf("b", "c"), deduped.map { it.id })

        val all = channelsInCategory(CATEGORY_ALL, dupes, emptySet(), emptyList(), allChannels = deduped)
        assertEquals(listOf("b", "c"), all.map { it.id })
        // Merging off: All is the full list, which is also the default.
        assertEquals(3, channelsInCategory(CATEGORY_ALL, dupes, emptySet(), emptyList()).size)
        // Per-category views untouched even with dedup on.
        assertEquals(2, channelsInCategory("news", dupes, emptySet(), emptyList(), allChannels = deduped).size)
        // A favorited deduped-away variant still appears under Favorites.
        val favs = channelsInCategory(
            CATEGORY_FAVORITES, dupes, setOf("http://x/a"), emptyList(), allChannels = deduped,
        )
        assertEquals(listOf("a"), favs.map { it.id })
    }

    @Test
    fun `a starred feed survives being merged away`() {
        // The list reaching Favorites is already merged, so the variant the
        // viewer starred is frequently not in it — it lost, and lives on as
        // one of the winner's fallbackUrls. Read by url alone the favourite
        // vanished the moment the catalogue learned a sibling was better.
        val winner = LiveChannel(
            id = "b", name = "CNN 4K", logo = null, url = "http://x/b", categoryId = "news",
            fallbackUrls = listOf("http://x/a"),
        )
        val favs = channelsInCategory(
            CATEGORY_FAVORITES, listOf(winner), setOf("http://x/a"), emptyList(),
        )
        assertEquals(listOf("b"), favs.map { it.id })

        // And the same for a channel watched under a url since folded away.
        val recent = channelsInCategory(
            CATEGORY_RECENT, listOf(winner), emptySet(), listOf("http://x/a"),
        )
        assertEquals(listOf("b"), recent.map { it.id })
    }

    @Test
    fun `shortcuts are hidden until they hold something`() {
        // No browse-everything shelf in front of them: the curated territories
        // cover the catalogue, and the tab was a rung the viewer stepped over.
        val bare = liveCategoryList(bundle, channels, recents = emptyList())
        assertEquals(listOf("sport", "news"), bare.map { it.id })

        // Favorites is NOT among them, however many are starred: Home opens
        // on a Favorites shelf, and a second door cost a permanent chip on
        // every live surface.
        val withBoth = liveCategoryList(
            bundle, channels,
            recents = listOf("http://x/2"),
        )
        assertEquals(listOf(CATEGORY_RECENT, "sport", "news"), withBoth.map { it.id })
    }

    @Test
    fun `a recent channel no longer in the playlist does not conjure the shortcut`() {
        // Recents are stream URLs kept across reloads, so they outlive channels
        // that a refresh dropped.
        val list = liveCategoryList(
            bundle, channels,
            recents = listOf("http://x/gone"),
        )
        assertFalse(list.any { it.id == CATEGORY_RECENT })
    }

    @Test
    fun `recent keeps its own order, newest first`() {
        val recents = listOf("http://x/3", "http://x/1")
        val result = channelsInCategory(CATEGORY_RECENT, channels, emptySet(), recents)
        assertEquals(listOf("3", "1"), result.map { it.id })
    }

    @Test
    fun `recent drops urls with no matching channel`() {
        val recents = listOf("http://x/gone", "http://x/2")
        val result = channelsInCategory(CATEGORY_RECENT, channels, emptySet(), recents)
        assertEquals(listOf("2"), result.map { it.id })
    }

    @Test
    fun `other categories keep the order they were given`() {
        assertEquals(listOf("1", "2", "3"), channelsInCategory(CATEGORY_ALL, channels, emptySet(), emptyList()).map { it.id })
        assertEquals(listOf("3"), channelsInCategory("news", channels, emptySet(), emptyList()).map { it.id })
        assertEquals(
            listOf("2"),
            channelsInCategory(CATEGORY_FAVORITES, channels, setOf("http://x/2"), emptyList()).map { it.id },
        )
    }

    @Test
    fun `a pre-indexed category is a lookup, and a stale index is ignored`() {
        val index = LiveCategoryIndex.of(channels)
        assertEquals(
            listOf("3"),
            channelsInCategory("news", channels, emptySet(), emptyList(), byCategory = index).map { it.id },
        )
        assertTrue(channelsInCategory("ghost", channels, emptySet(), emptyList(), byCategory = index).isEmpty())
        // Built from a different list — the frame between displayChannels
        // landing and its index catching up — it must not answer for this one.
        val shorter = channels.take(2)
        assertEquals(
            emptyList<String>(),
            channelsInCategory("news", shorter, emptySet(), emptyList(), byCategory = index).map { it.id },
        )
        // The shortcuts never consult it.
        assertEquals(
            listOf("2"),
            channelsInCategory(CATEGORY_FAVORITES, channels, setOf("http://x/2"), emptyList(), byCategory = index)
                .map { it.id },
        )
    }

    @Test
    fun `a selection that stops existing falls back to the first shelf`() {
        val categories = liveCategoryList(bundle, channels, emptyList())
        // The last favorite was un-starred, so the shortcut it named is gone.
        // With no browse-everything shelf to retreat to, the first one stands in.
        assertEquals("sport", resolveCategoryId(CATEGORY_FAVORITES, categories))
        assertEquals("news", resolveCategoryId("news", categories))
    }

    @Test
    fun `every live channel is still reachable from some shelf`() {
        // The guarantee the browse-everything tab used to provide on its own:
        // dropping it must not strand a channel. Curation keeps a channel it
        // cannot classify, so this is the property that keeps it findable.
        val categories = liveCategoryList(bundle, channels, emptyList())
        val reachable = categories.flatMap {
            channelsInCategory(it.id, channels, emptySet(), emptyList())
        }.map { it.id }.toSet()
        assertEquals(channels.map { it.id }.toSet(), reachable)
    }

    @Test
    fun `both views of live tv agree on every id`() {
        // The guide and the channel list share one selected id; this is the
        // property that stopped them meaning different things.
        val categories = liveCategoryList(
            bundle, channels, recents = listOf("http://x/2"),
        )
        for (category in categories) {
            assertTrue(
                "no channels resolved for ${category.id}",
                channelsInCategory(category.id, channels, setOf("http://x/1"), listOf("http://x/2")).isNotEmpty(),
            )
        }
    }

    /**
     * Live opens on the first shelf on offer, and says so.
     *
     * The browse tab initialised its selection to CATEGORY_ALL — a shelf this
     * app deliberately does not offer — so resolveCategoryId fell through to
     * categories.first() and the opening category was whatever happened to be
     * leading. Same result, chosen by nobody. These pin that the explicit
     * route and the accidental one agree, so the change is provably a
     * clarification and not a behaviour change.
     */
    @Test
    fun `the stated default is the one the old fallback produced`() {
        val offered = liveCategoryList(bundle, channels, emptyList())
        assertEquals(resolveCategoryId(CATEGORY_ALL, offered), defaultCategoryId(offered))
        // With history, Recent leads — and both routes still agree.
        val withRecent = liveCategoryList(bundle, channels, listOf("http://x/2"))
        assertEquals(CATEGORY_RECENT, defaultCategoryId(withRecent))
        assertEquals(resolveCategoryId(CATEGORY_ALL, withRecent), defaultCategoryId(withRecent))
    }

    /**
     * THE COLD START, which is the case the first version of this got wrong.
     *
     * displayChannels is a stateIn with an empty initial value, so the first
     * composition of Live TV sees no channels: every shelf is gated on having
     * some, and liveCategoryList's ifEmpty fallback hands back the ungated
     * bundle list — which has no Recent chip in it. The real list lands a
     * moment later and Recent appears at the front.
     *
     * A default that is RESOLVED once and stored freezes that first answer,
     * and because it is a real id resolveCategoryId then keeps it: Live opened
     * on News instead of on the channel you were last watching. The sentinel
     * is never a real id, so the fallback keeps running until the viewer picks
     * something — which is what "opens on the first shelf" has to mean when
     * the list arrives in two stages.
     */
    @Test
    fun `the default follows the list as it fills, not the first frame of it`() {
        // Frame one: no channels yet, so the gate lets nothing through and the
        // ungated bundle list stands in. No Recent.
        val coldStart = liveCategoryList(bundle, emptyList(), emptyList())
        assertFalse("Recent cannot exist before any channel does",
            coldStart.any { it.id == CATEGORY_RECENT })
        val frozen = resolveCategoryId(CATEGORY_NONE, coldStart)
        assertEquals(sport.id, frozen)

        // The catalogue and the recents land.
        val warm = liveCategoryList(bundle, channels, listOf("http://x/2"))
        assertEquals(CATEGORY_RECENT, warm.first().id)

        // The sentinel still resolves to the front of the CURRENT list.
        assertEquals(CATEGORY_RECENT, resolveCategoryId(CATEGORY_NONE, warm))
        // Whereas a default resolved on frame one and stored is a real id, so
        // it survives and pins the tab to the wrong shelf. This is the bug.
        assertEquals(frozen, resolveCategoryId(frozen, warm))
        assertNotEquals(CATEGORY_RECENT, resolveCategoryId(frozen, warm))
    }

    /**
     * Every surface that reads this list gets a cased name, without knowing
     * the rule exists.
     *
     * This is the invariant, and it is the one that was missing. Moving
     * categoryLabel into the shared file did not stop the drift: the rule
     * still had to be REMEMBERED by each of the six display sites, and four
     * forgot — the guide's own header printed the provider's casing in the
     * corner while the chip six lines below it printed the app's, on one
     * screen at the same time. Casing the Category where it is BUILT is what
     * makes that unrepresentable.
     */
    @Test
    fun `the list is cased on the way out, so no reader has to know the rule`() {
        val shouty = ContentBundle(
            liveCategories = listOf(
                Category("a", "Streaming Networks"),
                Category("b", "Top Rated"),
                Category("c", "PPV & Events"),
                Category("d", "Movies 24/7"),
            ),
            channels = listOf(
                channel("1", "a"), channel("2", "b"), channel("3", "c"), channel("4", "d"),
            ),
        )
        val names = liveCategoryList(shouty, shouty.channels, emptyList()).map { it.name }
        assertEquals(
            listOf("Streaming networks", "Top rated", "PPV & events", "Movies 24/7"),
            names,
        )
        // And it is what categoryLabel would have produced at each call site,
        // so nothing changes on screen except that it now always happens.
        for (cat in shouty.liveCategories) {
            assertEquals(categoryLabel(cat.name), names[shouty.liveCategories.indexOf(cat)])
        }
    }

    /** A chosen shelf is never displaced by the list changing under it. */
    @Test
    fun `a real choice outranks the default`() {
        val offered = liveCategoryList(bundle, channels, listOf("http://x/2"))
        assertEquals(news.id, resolveCategoryId(news.id, offered))
    }

    /** No categories at all: neither route may throw, and neither may invent one. */
    @Test
    fun `an empty category list has no default`() {
        assertEquals("", defaultCategoryId(emptyList()))
        assertEquals("", resolveCategoryId(CATEGORY_ALL, emptyList()))
    }

    /**
     * The browse-everything shelf is never offered. This is the invariant the
     * default rests on: if All ever came back as a chip, defaultCategoryId
     * would start returning it and Live would silently open on the whole
     * catalogue again.
     */
    @Test
    fun `all channels is never offered as a shelf`() {
        for (recents in listOf(emptyList(), listOf("http://x/2"))) {
            val offered = liveCategoryList(bundle, channels, recents)
            assertFalse(
                "All came back as a chip",
                offered.any { it.id == CATEGORY_ALL },
            )
        }
        // Including the ungated fallback, where the bundle's own list stands in
        // because nothing matched.
        val orphan = ContentBundle(
            liveCategories = listOf(sport, news),
            channels = listOf(channel("9", "nothing-matches-this")),
        )
        assertFalse(
            "All came back through the ifEmpty fallback",
            liveCategoryList(orphan, orphan.channels, emptyList())
                .any { it.id == CATEGORY_ALL },
        )
    }

    /**
     * Manage channels is the one screen that still asks for All, and it now
     * asks through the shared function rather than its own copy of the filter.
     * It passes no merged list and no index, so both branches must come back
     * byte-identical to the two lines it used to run.
     */
    @Test
    fun `the manager's filter and the shared one are the same filter`() {
        assertEquals(
            channels,
            channelsInCategory(CATEGORY_ALL, channels, emptySet(), emptyList()),
        )
        assertEquals(
            channels.filter { it.categoryId == "news" },
            channelsInCategory("news", channels, emptySet(), emptyList()),
        )
    }
}
