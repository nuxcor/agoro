package com.agoro.tv.ui.screens

import com.agoro.tv.data.Category
import com.agoro.tv.data.ContentBundle
import com.agoro.tv.data.LiveChannel
import com.agoro.tv.data.answersTo
import com.agoro.tv.data.isFavorite

/**
 * The category vocabulary of Live TV, in one place because it has two views.
 *
 * The list and the guide each used to build this for themselves and hold their
 * own selection, so picking a category in one and switching put you back on
 * "All" in the other, and every pseudo-category had to be added twice. They
 * share a selected id now; sharing the list and the filtering is what stops
 * that id from meaning two different things.
 */

/**
 * Every channel, unfiltered. No longer offered as a shelf — the curated
 * territories cover the catalogue and a browse-everything tab in front of them
 * was a rung the viewer stepped over. [ChannelManager] still selects it, where
 * seeing the whole list at once is the entire job.
 */
const val CATEGORY_ALL = "__all__"
const val CATEGORY_FAVORITES = "__fav__"
const val CATEGORY_RECENT = "__recent__"

/**
 * The categories to offer, given what the playlist has and what the viewer has
 * done. Favorites and Recent appear only once they hold something: an empty
 * shortcut is a dead end that still costs a D-pad press to skip.
 */
internal fun liveCategoryList(
    bundle: ContentBundle,
    channels: List<LiveChannel>,
    favorites: Set<String>,
    recents: List<String>,
    /**
     * Ids to keep even when [channels] holds none of theirs. Exactly one
     * thing needs it: a parental-locked category, whose channels are filtered
     * out of the visible list until the PIN is entered. Gated like the rest it
     * would disappear from the strip, and the PIN prompt would lose its only
     * door. See [lockedCategoryIds].
     */
    keepWhenEmpty: Set<String> = emptySet(),
): List<Category> = buildList {
    // No Favorites chip here. Home already opens on a Favorites shelf, and a
    // second way in cost a permanent chip on every live surface - the guide,
    // the player's list, the player's guide - to hold a handful of channels
    // the viewer lands among anyway. [CATEGORY_FAVORITES] stays: Home's row
    // resolves through the same function.
    // Gated the same way the category itself resolves. Checking url alone
    // hid the chip while [channelsInCategory] would have filled it.
    if (channels.any { ch -> recents.any { ch.answersTo(it) } }) {
        add(Category(id = CATEGORY_RECENT, name = "Recent"))
    }
    // A category with nothing visible left in it is never offered.
    //
    // The chips came from the bundle and the rows come from the VISIBLE
    // channels, so hiding the last channel of a shelf left a chip that opened
    // an empty grid: no rows for DOWN to land on, so focus stayed on the chip,
    // and the header — which names the focused programme — fell back to the
    // word "Guide". The same gate Recent above has always used, applied to the
    // rest of the strip.
    //
    // One pass over the channels rather than a filter per category: this runs
    // on every emission of the visible list, which a playlist of thousands
    // re-emits each time a stream's real quality is learned.
    val populated = HashSet<String>()
    for (channel in channels) channel.categoryId?.let { populated += it }
    val offered = bundle.liveCategories.filter { it.id in populated || it.id in keepWhenEmpty }
    // Nothing matched at all — a playlist whose channels carry category ids
    // its own category list doesn't name. The gate would then leave no chips
    // and nothing for [resolveCategoryId] to fall back to, which is a worse
    // screen than the one this fixes, so the ungated list stands in.
    addAll(offered.ifEmpty { bundle.liveCategories })
}

/**
 * The categories a PIN stands in front of.
 *
 * Shared rather than spelled out at each call site because it is half of one
 * rule: these ids are the ones [liveCategoryList] must keep despite having no
 * visible channels, and the ones the strip draws a lock on. Computed apart,
 * the two halves drift and a locked category either vanishes or opens without
 * being asked for the PIN.
 */
internal fun lockedCategoryIds(bundle: ContentBundle, isLocked: (String?) -> Boolean): Set<String> =
    bundle.liveCategories.filter { isLocked(it.name) }.map { it.id }.toSet()

/**
 * The channels in a category. Recent keeps its own order — most recently
 * watched first — rather than the playlist's, which is the whole point of it;
 * every other category keeps the order it was given.
 */
internal fun channelsInCategory(
    categoryId: String,
    channels: List<LiveChannel>,
    favorites: Set<String>,
    recents: List<String>,
    /**
     * The All view's list: cross-category duplicates already collapsed, so a
     * channel living in five categories lists once. Per-category views stay
     * untouched — nothing vanishes from the shelf being browsed — and
     * Favorites/Recent filter the full list by url, so a deduped-away variant
     * the viewer starred still appears there.
     *
     * Passed in rather than computed here, and this is the whole point of the
     * parameter: collapsing it is a global regex pass over every channel, and
     * all four screens that call this ran it inside composition on the main
     * thread. It re-ran on every emission of displayChannels — including the
     * one that lands mid-playback each time a stream's real quality is
     * learned. [MainViewModel.allChannelsView] computes it once, off-thread.
     */
    allChannels: List<LiveChannel> = channels,
    /**
     * [channels] grouped by category, built once off the main thread by
     * [MainViewModel.channelsByCategory]. With it a provider category is a
     * map lookup; without it — or with one built from a different list, which
     * is what a cold start hands over for a frame — this filters as before.
     */
    byCategory: LiveCategoryIndex? = null,
): List<LiveChannel> = when (categoryId) {
    // ifEmpty, and not as a formality: allChannelsView is a flowOn hop
    // DOWNSTREAM of displayChannels, so on a cold start there is a window
    // where the catalogue has arrived but its merge has not. The "No live
    // channels" pane can't cover the gap because it tests displayChannels —
    // which is full. The unmerged list for one frame beats an empty grid that
    // the entry focus tick then fires against.
    //
    // This used to say All was "the default selection on all four screens",
    // which stopped being true when the browse-everything shelf came off the
    // strip. No live surface opens here now — each one opens on the first
    // shelf on offer (see [defaultCategoryId]).
    //
    // [ChannelManager] is the one screen that still asks for All, and asking
    // is all it does: it passes no merged list, so this branch hands back the
    // channels it was given. The ifEmpty above is for the callers that DO
    // pass one.
    CATEGORY_ALL -> allChannels.ifEmpty { channels }
    // Matched on the fallbacks too, not the url alone. [channels] arrives
    // MERGED, so the variant a viewer starred is frequently not in it - it
    // lost to a better one and was folded into that tile's fallbackUrls. Read
    // by url alone, a favourite silently disappeared the moment the catalogue
    // learned one of its siblings was the better feed, and the viewer's own
    // shelf emptied for a reason nothing on screen could explain.
    CATEGORY_FAVORITES -> channels.filter { it.isFavorite(favorites) }
    CATEGORY_RECENT -> {
        // Index the channels once: recents is capped small, but the channel
        // list routinely runs to thousands and this is recomputed on every
        // change to either. Every url a tile answers to is a key, so a
        // watched feed that has since been folded away still resolves.
        // Not putIfAbsent: that is an API 24 default method and minSdk is 23.
        val byUrl = HashMap<String, LiveChannel>(channels.size * 2)
        for (ch in channels) {
            if (ch.url !in byUrl) byUrl[ch.url] = ch
            for (alt in ch.fallbackUrls) if (alt !in byUrl) byUrl[alt] = ch
        }
        recents.mapNotNull { byUrl[it] }.distinctBy { it.id }
    }
    else ->
        if (byCategory != null && byCategory.channels === channels) {
            byCategory.byId[categoryId].orEmpty()
        } else {
            channels.filter { it.categoryId == categoryId }
        }
}

/**
 * A channel list grouped by provider category.
 *
 * Every category switch used to filter the whole list — thousands of
 * channels, on the main thread, under a chip the viewer had only rested on.
 * Keeps the list it was built from so a reader can tell whether the index is
 * for the channels it holds: the index is a flow hop downstream of the list,
 * so there is always a frame where the two disagree.
 */
class LiveCategoryIndex(
    val channels: List<LiveChannel>,
    val byId: Map<String, List<LiveChannel>>,
) {
    companion object {
        val empty = LiveCategoryIndex(emptyList(), emptyMap())

        fun of(channels: List<LiveChannel>) = LiveCategoryIndex(
            channels,
            channels.groupBy { it.categoryId.orEmpty() },
        )
    }
}

/**
 * A category the viewer selected can stop existing — the last favorite gets
 * un-starred, a playlist refresh drops a category, recents are cleared. Falls
 * back to the first category on offer rather than showing an empty grid under
 * a heading for something that is no longer there.
 */
internal fun resolveCategoryId(selected: String, categories: List<Category>): String =
    if (categories.any { it.id == selected }) selected
    else categories.firstOrNull()?.id.orEmpty()

/** The first category to show when nothing has been chosen yet. */
internal fun defaultCategoryId(categories: List<Category>): String =
    categories.firstOrNull()?.id.orEmpty()

/**
 * A category chip's label, in the app's own sentence case.
 *
 * Two strips disagreed with each other on the same shelf: the films said
 * "Top Rated" and the shows said "Top rated", because the two labels are
 * written in two places that have never been read side by side. Casing is
 * decided HERE so they cannot drift again.
 *
 * It lived beside the browse strip while those two were the only callers, and
 * that is exactly how the drift came back: Live's strip and the player's were
 * never routed through it, so the same shelf read "Streaming Networks" in the
 * guide and "Streaming networks" in Movies. It belongs in this file for the
 * reason the rest of this file exists — the category vocabulary is shared, and
 * a rule kept next to one of its callers is a rule the next caller will miss.
 *
 * Only a plain Title-Case word is lowered. Anything carrying a digit
 * ("24/7"), a short all-caps code ("PPV", "UK", "4K") or a spelling of its
 * own ("Sci-Fi") is left exactly as it arrived — those are names, and a
 * rule that cannot tell a name from a shout would turn "PPV & Events" into
 * "Ppv & events".
 */
internal fun categoryLabel(name: String): String {
    val words = name.trim().split(' ').filter { it.isNotEmpty() }
    if (words.isEmpty()) return name
    return words.mapIndexed { index, word ->
        when {
            // The first word carries the sentence's capital — given one only
            // when the whole word is lowercase, so a brand that spells itself
            // ("iPlayer") is not rewritten into something it is not.
            index == 0 -> if (word.none { it.isUpperCase() }) {
                word.replaceFirstChar { it.uppercase() }
            } else word
            isPlainTitleCase(word) -> word.lowercase()
            else -> word
        }
    }.joinToString(" ")
}

private fun isPlainTitleCase(word: String): Boolean =
    word.length >= 3 &&
        word[0].isUpperCase() &&
        word.all { it.isLetter() } &&
        word.drop(1).none { it.isUpperCase() }
