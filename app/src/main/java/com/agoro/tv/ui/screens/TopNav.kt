@file:OptIn(
    androidx.tv.material3.ExperimentalTvMaterial3Api::class,
    androidx.compose.ui.ExperimentalComposeUiApi::class,
)

package com.agoro.tv.ui.screens

import androidx.compose.foundation.Image
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
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import com.agoro.tv.R
import com.agoro.tv.ui.theme.NuxColors
import com.agoro.tv.ui.theme.NuxFocus
import com.agoro.tv.ui.theme.NuxShape
import com.agoro.tv.ui.theme.Space

/**
 * How tall the whole header band is, brand mark and marker included.
 *
 * Every dp of it comes off the content below, which is why it is as small as
 * a 10-foot label allows: the guide reads its rows out of what is left, and
 * this is the only navigation in the app that costs the content anything.
 */
private val ITEM_PADDING_H = 14.dp
private val ITEM_PADDING_V = 7.dp

/** The gold rule under the tab you are on. Drawn in its own fixed-height slot. */
private val MARKER_HEIGHT = 3.dp
private val MARKER_WIDTH = 20.dp

enum class HomeTab(val label: String, val icon: ImageVector) {
    // Enum order is header order, left to right.
    //
    // Search leads, as an icon rather than a word — the shape everyone already
    // reads, in the corner every TV puts it. It is not a destination in the
    // way the others are: you go to Home, to Movies, to Live TV, but you don't
    // go to Search, you use it. An icon says that where a word sitting in the
    // same run as "Movies" and "Series" claimed to be one of them.
    //
    // Home still LANDS the app; leading the row and being the landing are
    // different jobs. Settings is last and gets pushed to the far right — see
    // [TopNav].
    Search("Search", Icons.Default.Search),
    Home("Home", Icons.Default.Home),
    Live("TV", Icons.Default.LiveTv),
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
 * icon-and-label stacks, and why the whole band comes to about 60dp.
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
        // No standalone brand mark. It sat beside Home saying the same thing
        // twice — a logo that does nothing next to a tab that goes home — and
        // spent the leading edge of the header on decoration. Home IS the mark
        // now: it is the app's own destination, so the app's own symbol is the
        // right label for it, and one control does what two were doing.
        items.forEachIndexed { index, item ->
            // Everything above the catalogue on the left, the app itself on
            // the right. The drawer said this with a divider; a header says it
            // with the gap, which costs no pixels and reads at ten feet.
            if (item == HomeTab.Settings) Spacer(Modifier.weight(1f))
            TopNavItem(
                label = item.label,
                selected = item == selected,
                // Two of these are symbols rather than words. Search
                // because it is an action and not a place; Home because the
                // app's own mark says "the front of the app" better than the
                // word does, and saying both was the redundancy.
                icon = item.icon.takeIf { item == HomeTab.Search },
                brand = item == HomeTab.Home,
                labelled = item != HomeTab.Search && item != HomeTab.Home,
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
     * Only Search does this. A row of icons would be a puzzle at ten feet —
     * the words are what make the header readable — but the one control that
     * is an action rather than a place earns the shape instead.
     */
    labelled: Boolean = true,
    /**
     * Draw the app's own mark instead of an icon or a word. Home only.
     *
     * Through [Icon] rather than [Image] so it takes the row's content colour:
     * a tab dims when it is not the one you are on, and a brand mark that
     * stayed gold through that would be the only control on the header not
     * saying where you are. ic_logo, not ic_splash — the splash copy is padded
     * into a square for its circular mask and draws at about 59% of the size
     * asked for. Sized by height; the 55:76 viewport carries the aspect.
     */
    brand: Boolean = false,
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
                if (brand) {
                    Icon(
                        painter = painterResource(R.drawable.ic_logo),
                        contentDescription = label,
                        modifier = Modifier.height(22.dp).width(16.dp),
                    )
                }
                if (icon != null) {
                    Icon(
                        icon,
                        // The label still has to exist for anyone not reading
                        // the screen; it just isn't drawn.
                        contentDescription = if (labelled) null else label,
                        modifier = Modifier.size(if (labelled) 18.dp else 22.dp),
                    )
                }
                if (labelled) Text(
                    text = label,
                    style = MaterialTheme.typography.labelLarge,
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
