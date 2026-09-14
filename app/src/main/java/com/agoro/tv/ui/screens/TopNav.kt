@file:OptIn(
    androidx.tv.material3.ExperimentalTvMaterial3Api::class,
    androidx.compose.ui.ExperimentalComposeUiApi::class,
)

package com.agoro.tv.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.LiveTv
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SportsSoccer
import androidx.compose.material.icons.filled.SystemUpdateAlt
import androidx.compose.material.icons.filled.VideoLibrary
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import com.agoro.tv.ui.theme.NuxColors
import com.agoro.tv.ui.theme.NuxFocus
import com.agoro.tv.ui.theme.NuxShape
import com.agoro.tv.ui.theme.Space

/**
 * How tall the whole header band is, marker included.
 *
 * Every dp of it comes off the content below, which is why it is as small as
 * a 10-foot label allows: the guide reads its rows out of what is left, and
 * this is the only navigation in the app that costs the content anything.
 */
private val ITEM_PADDING_H = 16.dp
private val ITEM_PADDING_V = 9.dp

/** The gold rule under the tab you are on. Drawn in its own fixed-height slot. */
private val MARKER_HEIGHT = 4.dp
private val MARKER_WIDTH = 26.dp

/**
 * The header's own type scale — titleMedium, a step above the labelLarge it
 * used to be.
 *
 * The band was drawn as small as a 10-foot label allows, because every dp of
 * it comes off the content. That is the right instinct and it was taken one
 * step too far: this is the app's primary navigation, the thing a viewer
 * looks at first and from across a room, and 16sp read as a toolbar rather
 * than as the top level of the app. It is worth the twelve dp.
 */
private val LABEL_STYLE
    @Composable get() = MaterialTheme.typography.titleMedium

/** Beside a word, so it defers to it. */
private val ICON_WITH_LABEL_SIZE = 22.dp

/** Alone in its control, so it carries the weight a word would: Settings. */
private val ICON_ONLY_SIZE = 28.dp

enum class HomeTab(val label: String, val icon: ImageVector) {
    // Enum order is header order, left to right. The ordinal is also the index
    // of a control's FocusRequester, so this order is the LEFT/RIGHT order and
    // the order BACK aims at — see [TopNav].
    //
    // Home leads, as a word. It carried the app's mark for a while — the logo
    // WAS the Home tab, on the argument that the app's own symbol names the
    // app's own destination — and the mark is gone from the header now by the
    // owner's call (2026-09-13). What it was really doing was asking the eye
    // to decode a glyph in the one position where a word would have been read
    // outright, and the leading edge is the position that can least afford it.
    // The mark still opens the app on the splash and sits on the sign-in form,
    // which is where a brand belongs.
    //
    // Search follows, keeping its magnifier AHEAD of its word rather than
    // instead of it. The argument for Search leading is a real one: it is an
    // ACTION rather than a place — you go to Home, to Movies, to Live, but you
    // don't go to Search, you use it — and the top-left is where a television
    // puts that shape.
    //
    // So the six destinations on the left are six words, which is the whole
    // point of the row: a glyph has to be DECODED where a word is simply read,
    // and a television has no tooltip to fall back on.
    //
    // Settings is last, pushed to the far right, and is the one control with
    // no word — see [TopNavItem.labelled] for why its position earns that.
    Home("Home", Icons.Default.Home),
    Search("Search", Icons.Default.Search),
    // "Live", not "TV". The other three destinations name a kind of thing to
    // watch, and "TV" names the medium that contains all three — the one label
    // in the row that was not parallel with its neighbours. This enum has
    // called it Live all along.
    Live("Live", Icons.Default.LiveTv),
    // Beside TV because that is what it is — live, just organised by fixture
    // instead of by channel.
    //
    // "Sports", plural, by the viewer's own call. This read "Sport" on the
    // reasoning that the TV strip already has a Sports SHELF of channels, and
    // two things in the app wearing the same word would look like one place.
    // That collision is real but it is not what a viewer trips over: they
    // never see the two side by side, and the singular reads as a category
    // label where every other tab is a plain name for a place. Recorded here
    // because the argument still stands and someone will make it again.
    Sport("Sports", Icons.Default.SportsSoccer),
    Movies("Movies", Icons.Default.Movie),
    Series("Series", Icons.Default.VideoLibrary),
    Settings("Settings", Icons.Default.Settings),
}

/**
 * The app's navigation: a header across the top, always on screen.
 *
 * It replaced a summoned drawer, and the reason is the drawer's one real
 * failing — it was invisible. A viewer who did not already know that BACK
 * summoned a menu had no way to find out, which is why the drawer needed a
 * one-time coach mark telling them so. A header needs no such line: where you
 * are and where else you can go are both simply on screen.
 *
 * What it costs is the only thing worth weighing: a band of vertical space
 * the content no longer has. That is why the labels are text and not
 * icon-and-label stacks, and why the whole band comes to about 78dp.
 *
 * Travel highlights, OK or DOWN commits. There is no select-on-travel: it
 * would recompose a whole grid on every LEFT press, and the box this runs on
 * has 2GB of RAM. LEFT/RIGHT are handled by index rather than left to the
 * geometric search, which proved unreliable inside an overlaid focus group
 * and has nothing to work out for a fixed horizontal run anyway.
 */
@Composable
internal fun TopNav(
    selected: HomeTab,
    /** Highlight moved to another tab, but nothing is committed yet. */
    onSelect: (HomeTab) -> Unit,
    /**
     * One requester per control, owned by the shell so it can aim BACK at the
     * tab you are on.
     *
     * Hoisted, and it has to stay hoisted. When this list lived here and only
     * the SELECTED item carried the shell's requester, committing a tab moved
     * that requester from one item to another — and adding a FocusRequester to
     * a node's modifier chain resets that node's focus. Focus was dropped, the
     * window's default placement put it on the first focusable in the tree
     * (the Home tab), the header's own onFocusChanged then reported that it
     * held focus, and the content refused every hand-off from that point on.
     * Committing Live TV left the ring sitting on Home, permanently.
     *
     * One requester per index, attached once, never moved.
     */
    itemFocus: List<FocusRequester>,
    onHeaderFocusChanged: (Boolean) -> Unit,
    /**
     * What the update control should say, or null when there is nothing to
     * offer and it does not exist.
     *
     * A labelled control at the end of the header, never a dot on the gear.
     * The dot was the entire nudge and it pointed at a screen rather than at
     * the thing — a viewer who saw it had to know that a mark on a gear meant
     * a new version, then go and find it. "Update to 2.40.0" says both, and
     * it costs nothing when there is no update because then it is not there.
     */
    updateLabel: String? = null,
    onUpdate: () -> Unit = {},
) {
    val items = remember { HomeTab.entries }
    val lastIndex = items.lastIndex + if (updateLabel != null) 1 else 0
    var focusedIndex by remember { mutableStateOf(items.indexOf(selected)) }

    // Ordinal order IS visual order, Settings included, so an index step and a
    // step across the screen are the same move even though a spacer sits in
    // between.
    fun commit(index: Int) {
        if (index <= items.lastIndex) onSelect(items[index]) else onUpdate()
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .focusGroup()
            .onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                when (event.key) {
                    Key.DirectionRight -> {
                        itemFocus[(focusedIndex + 1).coerceAtMost(lastIndex)]
                            .requestFocus(); true
                    }
                    Key.DirectionLeft -> {
                        itemFocus[(focusedIndex - 1).coerceAtLeast(0)]
                            .requestFocus(); true
                    }
                    // DOWN commits as OK does, and that is the whole reason it
                    // is here rather than left to the focus search. Walking to
                    // Movies and pressing DOWN plainly means "go to Movies";
                    // letting the search take it would have dropped the viewer
                    // into whatever tab was still on screen underneath.
                    Key.DirectionDown -> {
                        commit(focusedIndex); true
                    }
                    else -> false
                }
            }
            .onFocusChanged { onHeaderFocusChanged(it.hasFocus) }
            .padding(start = Space.gutter, end = Space.gutter, top = 18.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Space.xs),
    ) {
        // No brand mark anywhere in this row — not standing alone, which was
        // decoration on the most valuable pixels in the app, and not standing
        // in for the word "Home" either. See [HomeTab].
        items.forEachIndexed { index, item ->
            // Everything above the catalogue on the left, the app itself on
            // the right. The drawer said this with a divider; a header says it
            // with the gap, which costs no pixels and reads at ten feet.
            if (item == HomeTab.Settings) Spacer(Modifier.weight(1f))
            TopNavItem(
                label = item.label,
                selected = item == selected,
                // Every destination is a word. Two of them lead that word with
                // a symbol — Home with the app's own mark, Search with the
                // magnifier — because those two earn a glyph, not because they
                // can do without the word.
                icon = item.icon.takeIf {
                    item == HomeTab.Search || item == HomeTab.Settings
                },
                labelled = item != HomeTab.Settings,
                onClick = { commit(index) },
                modifier = Modifier
                    .focusRequester(itemFocus[index])
                    .onFocusChanged { if (it.isFocused) focusedIndex = index },
            )
        }
        // After Settings, and only while there is something to install. Never
        // "selected" — it is an action, not a destination, and marking it the
        // way a tab is marked would say the viewer is somewhere they are not.
        if (updateLabel != null) {
            TopNavItem(
                label = updateLabel,
                selected = false,
                accent = true,
                icon = Icons.Default.SystemUpdateAlt,
                onClick = onUpdate,
                modifier = Modifier
                    .focusRequester(itemFocus[items.size])
                    .onFocusChanged { if (it.isFocused) focusedIndex = items.size },
            )
        }
    }
}

@Composable
private fun TopNavItem(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    /**
     * Teal rather than the header's usual dim grey. The same colour Settings
     * uses for "an update is available", and deliberately not gold: gold is
     * the selected tab, and a second gold control would read as two tabs open
     * at once.
     */
    accent: Boolean = false,
    icon: ImageVector? = null,
    /**
     * False draws the icon alone, with [label] left to the screen reader.
     *
     * Settings is the only one, and its position is what earns it: it sits
     * beyond the gap, on the far side of the header, where the app itself
     * lives rather than the catalogue. A gear there is read as the app's
     * settings by anyone who has used a television, and the six DESTINATIONS
     * on the left are all words — which is the distinction the row is making.
     */
    labelled: Boolean = true,
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = modifier) {
        Surface(
            onClick = onClick,
            shape = ClickableSurfaceDefaults.shape(NuxShape.FilterChip),
            colors = ClickableSurfaceDefaults.colors(
                // Transparent even when selected. Two filled states one
                // lightness step apart is one state at ten feet, and the
                // header has to say which tab you are ON and which you are
                // POINTING AT at the same time. Selection is the gold word
                // and the gold rule below it; focus is the fill and the white
                // ring. Two marks of different kinds, never two greys.
                containerColor = Color.Transparent,
                focusedContainerColor = NuxColors.SurfaceRaised,
                contentColor = when {
                    selected -> NuxColors.Primary
                    accent -> NuxColors.Secondary
                    else -> NuxColors.OnSurfaceDim
                },
                // Gold survives focus: BACK puts focus on the tab you are
                // already on, and with white-on-focus the header could not say
                // where you were until you moved off it.
                focusedContentColor = when {
                    selected -> NuxColors.Primary
                    accent -> NuxColors.Secondary
                    else -> NuxColors.OnSurface
                },
            ),
            scale = ClickableSurfaceDefaults.scale(focusedScale = NuxFocus.RowScale),
            border = ClickableSurfaceDefaults.border(focusedBorder = NuxFocus.ringChip),
        ) {
            Row(
                modifier = Modifier.padding(
                    horizontal = ITEM_PADDING_H,
                    vertical = ITEM_PADDING_V,
                ),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Space.s),
            ) {
                if (icon != null) {
                    Icon(
                        icon,
                        // The word beside it is what gets read out; alone, the
                        // icon has to carry the name itself.
                        contentDescription = if (labelled) null else label,
                        modifier = Modifier.size(
                            if (labelled) ICON_WITH_LABEL_SIZE else ICON_ONLY_SIZE,
                        ),
                    )
                }
                if (labelled) Text(
                    text = label,
                    style = LABEL_STYLE,
                    // One line, always. A header that grows a second line
                    // moves every tab beside it and shortens the content
                    // below; the update label is the only string here long
                    // enough to try, and it would rather be cut.
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        // A fixed slot, painted or not. Laying the rule out only when selected
        // would move every label 3dp on each tab change.
        Box(
            Modifier
                .padding(top = 4.dp)
                .height(MARKER_HEIGHT)
                .width(if (selected) MARKER_WIDTH else 0.dp)
                .background(NuxColors.Primary, NuxShape.Track),
        )
    }
}
