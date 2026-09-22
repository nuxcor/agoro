@file:OptIn(
    androidx.tv.material3.ExperimentalTvMaterial3Api::class,
    androidx.compose.ui.ExperimentalComposeUiApi::class,
)

package com.agoro.tv.ui.screens

import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
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
import androidx.tv.material3.Border
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
internal val ITEM_PADDING_H = 16.dp
internal val ITEM_PADDING_V = 9.dp

/** Above the words. This is also the app's top safe inset — see Theme.kt. */
internal val ROW_PADDING_TOP = 18.dp

/**
 * Below the words, and it is load-bearing at 14.
 *
 * It was 6 while a 4dp gold rule and its 4dp pad sat under each tab. The rule
 * is gone (see [TopNavItem]) and those eight dp are spent HERE rather than
 * reclaimed as a shorter band: a shorter band would pull the bar and the
 * category strip eight dp closer together, which is the exact complaint this
 * change answers. Spent here it does the opposite — the seam between the two
 * rows goes from 0dp to 8 — and [HEADER_BAND_HEIGHT] is still exactly 78, so
 * the wash, the content lane's top padding and the guide's whole row budget
 * are all untouched. [HeaderBandTest] holds the arithmetic.
 */
internal val ROW_PADDING_BOTTOM = 14.dp

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

/**
 * How long a lost strip focus must stay lost before the chrome believes it.
 *
 * A LazyRow reports hasFocus=false for one frame as focus moves between two
 * children, so without this the chrome flickers on every press along the
 * strip. Matched to the sustained-loss window LiveTab already uses for the
 * guide's entry tick, and to NuxMotion.FastMs.
 */
internal const val NAV_CHROME_BLIP_MS = 120L

/**
 * How long the strip stays up waiting for a focus request to land, before the
 * shell gives up and drops it again. Longer than requestFocusRetrying's own
 * ladder (8 tries, 60ms apart) so a slow compose is not cut off, short enough
 * that a failed request does not leave the navigation stranded on screen.
 */
internal const val NAV_CHROME_REQUEST_MS = 800L

/** The presses that count as "the viewer has started moving". */
internal val NAV_CHROME_MOVE_KEYS = setOf(
    Key.DirectionUp, Key.DirectionDown, Key.DirectionLeft, Key.DirectionRight,
    Key.DirectionCenter, Key.Enter,
)

/**
 * How much of the navigation is on screen.
 *
 * The two rows at the top cost 132dp of a 540dp canvas — a quarter of the
 * screen — permanently, on an app that is live TV before it is a library and
 * whose guide gets only four channel rows because of it. And neither row is
 * doing anything while the viewer is scanning that grid: you pick a tab and
 * stay, you pick a shelf and then look. Chrome should cost in proportion to
 * how often it is used, and this pair was the least-used and most expensive
 * thing on screen.
 *
 * So it steps out of the way, and the escalation upward mirrors the ladder
 * BACK already walks downward (see [guideBackAction]): grid, then strip, then
 * bar. Nothing about the D-pad changes — every route that reaches the bar
 * today reaches it unchanged. This only decides what is DRAWN.
 *
 * Why [movedSinceArrival] is a term. The shell parks launch focus in the
 * CONTENT on purpose, so the app boots one OK away from watching. A purely
 * focus-driven rule would therefore retract the navigation on boot, before
 * the viewer has pressed anything — a menu that vanishes unbidden, which is
 * the drawer's failure mode reintroduced. Pinning both rows until the first
 * move also means the viewer has SEEN the bar before it can ever go away,
 * which is what makes the UP that brings it back discoverable rather than
 * folklore.
 */
internal enum class NavChrome {
    /** Focus is in content. Neither row is drawn. */
    Hidden,

    /** Focus is on a tab's own top-edge control. The strip is drawn. */
    Strip,

    /** Focus is in the bar, or nothing has moved yet. Both rows are drawn. */
    Full,
    ;

    val barVisible get() = this == Full
    val stripVisible get() = this != Hidden
}

/**
 * One rule, in one place, for the same reason [guideBackAction] is: the ORDER
 * is the thing that breaks, and an order expressed as nested ifs across two
 * files is an order nobody can check.
 *
 * Tabs with no top-edge control of their own — Home, Sport, Settings, Search —
 * never report [stripFocused], so this collapses to a one-rung escalation for
 * them, which is exactly what they do today. Deliberately NOT scoped to
 * strip-bearing tabs: a bar whose presence depends on which tab you are on is
 * a rule no viewer can name, and it reads as a bug.
 */
internal fun navChromeLevel(
    headerFocused: Boolean,
    stripFocused: Boolean,
    movedSinceArrival: Boolean,
): NavChrome = when {
    headerFocused || !movedSinceArrival -> NavChrome.Full
    stripFocused -> NavChrome.Strip
    else -> NavChrome.Hidden
}

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
    // Search follows, and it is a word now like the rest. It kept a magnifier
    // ahead of that word for a long time on the argument that it is an ACTION
    // rather than a place — you go to Home, to Movies, to Live, but you don't
    // go to Search, you use it. That argument is sound and it survives; what
    // carries it is the POSITION, on the leading edge, which is where a
    // television puts that shape. It never needed the glyph as well.
    //
    // So the six destinations on the left are six words, which is the whole
    // point of the row: a glyph has to be DECODED where a word is simply read,
    // and a television has no tooltip to fall back on. Settings is the only
    // mark in the row, and its position past the weighted gap is what earns
    // it — see [TopNavItem.labelled].
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
            .padding(
                start = Space.gutter,
                end = Space.gutter,
                top = ROW_PADDING_TOP,
                bottom = ROW_PADDING_BOTTOM,
            ),
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
                // Settings alone. Six destinations, six words — the row's
                // own argument, applied to the row: a glyph has to be DECODED
                // where a word is simply read, and a television has no
                // tooltip. Search's claim to a mark was that it is an ACTION
                // rather than a place, and that claim is carried by its
                // POSITION on the leading edge, exactly as the gear's is
                // carried by sitting past the weighted gap.
                icon = item.icon.takeIf { item == HomeTab.Settings },
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
            // ONE STATE LANGUAGE, and the category strip below now uses the
            // same one. These two rows sat one dp apart saying opposite
            // things: here selection was transparent and focus a dim raised
            // fill; down there selection was FILLED and focus a solid white
            // one. The same press produced different results one row apart,
            // which is what made the two of them read as a single confusing
            // block rather than as a bar over a filter.
            //
            //   resting            dim text, no container
            //   selected           gold text, no container
            //   focused            white fill, dark text
            //   focused + selected white fill, gold text
            //
            // The rule this file has always stated — "two filled states one
            // lightness step apart is one state at ten feet" — is finally true
            // of the whole app: exactly one state fills, and it is focus.
            colors = ClickableSurfaceDefaults.colors(
                containerColor = Color.Transparent,
                // The fill IS the focus mark. A raised grey was a lightness
                // step; this is the largest one the palette can make.
                focusedContainerColor = NuxColors.FocusBorder,
                contentColor = when {
                    selected -> NuxColors.Primary
                    accent -> NuxColors.Secondary
                    else -> NuxColors.OnSurfaceDim
                },
                // Gold survives focus, because BACK puts focus on the tab you
                // are already on and the header would otherwise stop saying
                // where you were until you moved off it.
                //
                // PrimaryDim, not Primary, and only here. Gold #D99A2E on the
                // white fill is about 2.0:1 — a smudge at ten feet rather than
                // a colour. PrimaryDim #9C6D1C is the palette's own darkened
                // gold, reads about 3.7:1 on that fill, and still reads as
                // GOLD rather than as grey. Resting-selected keeps full
                // Primary, where it sits on near-black at about 7.4:1.
                //
                // The update control does NOT keep its teal: #4FD1C5 on white
                // is about 1.5:1, and unlike selection it has nothing to
                // preserve — its own label says "Update to 2.40.0".
                focusedContentColor = when {
                    selected -> NuxColors.PrimaryDim
                    else -> NuxColors.Background
                },
            ),
            // No scale. These are words in a run spaced by Space.xs = 4dp, and
            // 1.06 on a ~120dp pill grows it seven dp — the whole gap — so a
            // solid white pill would butt against its neighbour's word. A
            // poster grows because it is a picture you are picking up; a word
            // that grows shoves its neighbours.
            scale = ClickableSurfaceDefaults.scale(focusedScale = NuxFocus.RowScale),
            // No ring. A 2dp #E6FFFFFF ring around a solid #E6FFFFFF fill is
            // the same colour as the thing it is drawn on.
            border = ClickableSurfaceDefaults.border(focusedBorder = Border.None),
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
        // No rule under the selected tab any more. It was the third mark
        // doing the second job — gold text already says which tab you are on,
        // and the strip below has never had a rule, so the two rows now mark
        // themselves the same way.
        //
        // Its 8dp (a 4dp rule and its 4dp pad) is NOT reclaimed as a shorter
        // band. That would pull the two rows eight dp CLOSER, which is the
        // complaint this change exists to answer. It is spent as air instead:
        // the Row's bottom padding went 6 -> 14 so the band is still exactly
        // 78, and the seam between the bar and the strip went from 0dp to 8.
        // See [ROW_PADDING_BOTTOM] — the 14 is load-bearing, and restoring
        // the rule means putting the 6 back with it.
    }
}
