@file:OptIn(
    androidx.tv.material3.ExperimentalTvMaterial3Api::class,
    androidx.compose.ui.ExperimentalComposeUiApi::class,
)

package com.agoro.tv.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.focus.onFocusEvent
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.LiveTv
import androidx.compose.material.icons.filled.Lock
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.nativeKeyCode
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
import com.agoro.tv.MainViewModel
import kotlinx.coroutines.launch
import com.agoro.tv.data.ContentBundle
import com.agoro.tv.data.LiveChannel
import com.agoro.tv.data.TextNorm
import com.agoro.tv.ui.components.ContextMenu
import com.agoro.tv.ui.components.MenuAction
import com.agoro.tv.ui.components.requestFocusRetrying
import com.agoro.tv.ui.components.StatusAction
import com.agoro.tv.ui.components.StatusPane
import com.agoro.tv.ui.theme.NuxColors
import com.agoro.tv.ui.theme.NuxFocus
import com.agoro.tv.ui.theme.NuxShape
import com.agoro.tv.ui.theme.Space
import kotlinx.coroutines.delay
import com.agoro.tv.data.isFavorite

// --- channel-number jump ------------------------------------------------------

/**
 * Channel-number entry for a channel list: collects digits, then jumps.
 *
 * Parks focus on the row it scrolls to, which is the part that was missing.
 * Scrolling alone left the focused row where it was — off-screen — so the next
 * D-pad press moved from *there* and the list snapped straight back to where it
 * started. The jump appeared to work and then undid itself on the following
 * press, which reads as the feature being broken rather than as focus being in
 * the wrong place.
 */
@Stable
internal class ChannelJump(val listState: LazyListState) {
    val focusRequester = FocusRequester()
    var digits by mutableStateOf("")
    var targetIndex by mutableIntStateOf(-1)

    /** Hand-off from the digit collector to the executor. A state counter, so
     *  the executor effect restarts per jump; the number itself is a plain
     *  field because nothing needs to observe it. */
    var jumpTick by mutableIntStateOf(0)
    var jumpNumber: Int = -1
}

@Composable
internal fun rememberChannelJump(
    channels: List<LiveChannel>,
): ChannelJump {
    val listState = rememberLazyListState()
    val jump = remember(listState) { ChannelJump(listState) }
    // Two effects, not one: this collector clears digits, and an effect keyed
    // on digits cancels itself the moment it does that — the scroll and the
    // focus retries below were dying at their first suspension point, leaving
    // exactly the scrolled-but-not-focused snap-back this class exists to fix.
    LaunchedEffect(jump.digits) {
        if (jump.digits.isEmpty()) return@LaunchedEffect
        delay(1_200)
        val typed = jump.digits.toIntOrNull()
        // No suspension after this write: cancellation is cooperative, so the
        // hand-off still runs.
        jump.digits = ""
        if (typed != null) {
            jump.jumpNumber = typed
            jump.jumpTick++
        }
    }
    LaunchedEffect(jump.jumpTick, channels) {
        if (jump.jumpTick == 0) return@LaunchedEffect
        // By number only — numbers are positions over the whole list and
        // this list may be a category's slice of it, where "the fifth row"
        // is not channel 5.
        val target = channels.indexOfFirst { it.number == jump.jumpNumber }
        if (target !in channels.indices) return@LaunchedEffect
        jump.targetIndex = target
        jump.listState.scrollToItem(target)
        // The row composes a frame after the scroll; retry briefly.
        jump.focusRequester.requestFocusRetrying()
    }
    return jump
}

/** Collects digit presses into [jump]. Goes on the list that scrolls. */
internal fun Modifier.channelJumpKeys(jump: ChannelJump): Modifier =
    this.onPreviewKeyEvent { event ->
        if (event.type != KeyEventType.KeyDown) {
            return@onPreviewKeyEvent false
        }
        val code = event.key.nativeKeyCode
        if (code in android.view.KeyEvent.KEYCODE_0..android.view.KeyEvent.KEYCODE_9) {
            jump.digits += (code - android.view.KeyEvent.KEYCODE_0).toString()
            true
        } else false
    }

/** The "Channel 205" readout while digits are still being collected. */
@Composable
internal fun ChannelJumpBadge(digits: String, modifier: Modifier = Modifier) {
    if (digits.isEmpty()) return
    Box(
        modifier = modifier
            .background(NuxColors.Scrim, NuxShape.Row)
            .padding(horizontal = 18.dp, vertical = 10.dp),
    ) {
        Text(
            "Channel $digits",
            style = MaterialTheme.typography.titleMedium,
            color = NuxColors.Primary,
        )
    }
}

// --- Live TV -----------------------------------------------------------------

@Composable
internal fun LiveTab(
    vm: MainViewModel,
    bundle: ContentBundle,
    onPlay: () -> Unit,
    onOpenSettings: () -> Unit = {},
) {
    if (bundle.channels.isEmpty()) {
        NoLiveChannelsPane(onOpenSettings)
        return
    }
    val favorites by vm.favorites.collectAsState()
    var menuChannel by remember { mutableStateOf<LiveChannel?>(null) }
    var scheduleChannel by remember { mutableStateOf<LiveChannel?>(null) }
    // What the host has to say for itself. The guide keeps its own toast for
    // things that happen inside the grid; this one answers the context menu,
    // whose actions take a row away from under the viewer.
    var hostMessage by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(hostMessage) {
        if (hostMessage != null) {
            delay(4_000)
            hostMessage = null
        }
    }
    // Filtering/merging happens off the main thread in the ViewModel.
    val allVisible by vm.displayChannels.collectAsState()
    val recents by vm.recentChannels.collectAsState()
    // The same list, built the same way, as the strip the guide draws — this
    // one exists to resolve the SELECTED id (see [resolveCategoryId]), and the
    // two disagreeing is what would make a locked category unselectable: the
    // strip offers it, the viewer enters the PIN, and this list — not knowing
    // the category exists — quietly falls back to the first shelf.
    val pin by vm.parentalPin.collectAsState()
    val unlocked by vm.parentalUnlocked.collectAsState()
    val lockedIds = remember(bundle, pin, unlocked) {
        lockedCategoryIds(bundle) { vm.isLockedCategory(it) }
    }
    // Built from the list that is GATED on having channels, not from the
    // bundle's categories: a shelf whose every channel has been hidden used to
    // keep its chip, and OK on it swapped in an empty grid with no copy in it,
    // a header that fell back to the word "Guide", and nothing below for DOWN
    // to land on — so focus stayed on the chip and the press looked like the
    // app ignoring the remote. Locked categories are the exception the gate
    // takes: their channels are filtered out until the PIN, and dropping them
    // would take the PIN prompt's only door with them.
    //
    // This is the only place it is built. The guide takes it as a parameter.
    val categories = remember(bundle, recents, allVisible, lockedIds) {
        liveCategoryList(bundle, allVisible, recents, keepWhenEmpty = lockedIds)
    }
    // Keyed on the PLAYLIST, which is the identity the old key was groping
    // for. It was the channel count, and a count is not an identity:
    // refreshIfStale runs on every ON_RESUME and the in-app cycle is hourly, so
    // a provider adding or dropping one channel discarded the saved value and
    // dumped the viewer back on the first chip with the grid re-filtered under
    // them.
    //
    // No key at all was the other half-answer. Xtream category ids are bare
    // numbers, so "12" is Sports on one panel and something else entirely on
    // the next — and because that id DOES exist in the new list,
    // resolveCategoryId happily keeps it and opens on a category the viewer
    // never chose. The source id separates the two cases: a refresh keeps your
    // place, a different playlist starts fresh.
    val activeSource by vm.activeSource.collectAsState()
    // Nothing chosen yet — see [CATEGORY_NONE]. resolveCategoryId below turns
    // that into the first shelf on offer, and keeps doing so until the viewer
    // picks one.
    //
    // This read CATEGORY_ALL, which worked only by accident: All is a shelf
    // this app deliberately does not offer, so it was never in the list and
    // the fallback ran every time. Saying "the first shelf" outright is the
    // point; resolving it once and storing THAT is not, and is a different
    // thing entirely — the first composition has no channels yet, so it would
    // freeze a list that does not have Recent in it.
    var selectedCategory by rememberSaveable(activeSource?.id) {
        mutableStateOf(CATEGORY_NONE)
    }
    // Recent and Favorites come and go as the viewer watches and stars things,
    // so the selection can outlive the category it names.
    val activeCategory = resolveCategoryId(selectedCategory, categories)
    // Ordering is applied in the ViewModel from the Settings preference.
    // Needed here (not just inside the guide) because the schedule sheet and
    // the context menu play from this list.
    val allView by vm.allChannelsView.collectAsState()
    // Grouped once off the main thread, so a category switch is a lookup
    // and not a filter over every channel — see LiveCategoryIndex.
    val byCategory by vm.channelsByCategory.collectAsState()
    val channels = remember(allVisible, activeCategory, favorites, recents, allView, byCategory) {
        channelsInCategory(
            activeCategory, allVisible, favorites, recents,
            allChannels = allView, byCategory = byCategory,
        )
    }
    val epgState by vm.epgState.collectAsState()

    // Focus discipline for the guide, both directions:
    // - Entering from the rail redirects to a programme cell (via the tick),
    //   instead of Compose's geometric landing — which picked the day pager
    //   or a clipped sliver cell with no visible ring.
    // - Leaving is LEFT-only (to the rail) — a DOWN from the day pager used
    //   to land geometrically on the rail, where the dwell then switched the
    //   whole screen to another tab.
    var entryFocusTick by remember { mutableStateOf(0) }
    // Focus-entry detection via the subtree's focus state, not
    // focusProperties.onEnter: Compose's directional (2D) search treats
    // group boundaries as transparent and never calls onEnter, so that hook
    // silently missed every D-pad entry. hasFocus on the wrapper flips when
    // any descendant takes focus — that edge IS the entry, whatever caused
    // it; the tick then redirects to a programme cell (one frame late at
    // worst).
    var guideHasFocus by remember { mutableStateOf(false) }
    val focusScope = rememberCoroutineScope()
    var guideLossJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }
    Box(
        modifier = Modifier
            .fillMaxSize()
            .onFocusEvent { state ->
                // Sustained-loss discipline: moving focus BETWEEN two children
                // of this subtree reports a one-frame hasFocus=false blip, and
                // treating that as a fresh entry made the tick yank focus back
                // to the grid — the category chips could receive focus for one
                // frame and never keep it. Only a real exit (>120ms outside)
                // re-arms the entry redirect.
                if (state.hasFocus) {
                    guideLossJob?.cancel()
                    guideLossJob = null
                    if (!guideHasFocus) {
                        guideHasFocus = true
                        entryFocusTick++
                    }
                } else if (guideHasFocus) {
                    guideLossJob = focusScope.launch {
                        kotlinx.coroutines.delay(120)
                        guideHasFocus = false
                    }
                }
            },
    ) {
    // One surface. The grid IS the channel list — its channel column carries
    // the logos, numbers, keypad jump and hold-OK menu the list view used to
    // own, and every channel's schedule sits beside it instead of behind a
    // toggle. Two views of the same channels meant the same press did
    // different things depending on a switch set weeks ago.
    val gridHandle = remember { GuideGridHandle() }
    // Held by the host so the guide and the "What's on" sheet agree about what
    // is already set — see [GuideReminders].
    val reminders = remember { GuideReminders() }
    // Overlays here are in-layout: when one closes, the row that held focus
    // is simply gone and Compose reseats focus on the nearest thing it can
    // find — the first category chip, whose dwell then switched the whole
    // guide. Hand focus back to the row the menu was opened on instead.
    //
    // In the SAME FRAME the overlay unmounts, not after a wall-clock wait.
    // This used to delay 120ms, and in that window the reseating had already
    // happened — so a quick press after closing drove the chip, not the
    // guide. The request cannot be made before the overlay is gone: the
    // dialog scaffold cancels any focus exit while it stands. Armed by the
    // dismissal (a plain holder: nothing in composition reads it) and run by
    // the effect below once the frame that removed the overlay has applied,
    // before the next key event can arrive. focusAnchor verifies the landing
    // and retries for a row still composing, which is the bounded fallback.
    val returnFocusPending = remember { booleanArrayOf(false) }
    fun refocusGridOnClose() {
        returnFocusPending[0] = true
    }
    LaunchedEffect(menuChannel, scheduleChannel) {
        // The menu's "What's on" closes the menu and opens the sheet in one
        // press; the return waits for the sheet, and the flag stays armed.
        if (menuChannel != null || scheduleChannel != null) return@LaunchedEffect
        if (!returnFocusPending[0]) return@LaunchedEffect
        returnFocusPending[0] = false
        gridHandle.focusAnchor()
    }
    fun playFromHost(channel: LiveChannel) {
        gridHandle.beforePlay()
        // By id - see the note in GuideTab: value equality drifts as the
        // merge learns qualities, and a miss silently starts at channel one.
        vm.playChannels(channels, channels.indexOfFirst { it.id == channel.id }.coerceAtLeast(0))
        onPlay()
    }
    GuideTab(
        entryFocusTick = entryFocusTick,
        vm = vm,
        // No bundle: it was the key of the two derivations that moved up here,
        // and nothing in the guide read it afterwards. ContentBundle is
        // unstable (it holds Lists), so passing one it does not use made the
        // whole tab non-skippable — every republish, on every ON_RESUME and
        // every hourly refresh, recomposed the entire guide for a value it
        // ignored.
        onPlay = onPlay,
        categoryId = activeCategory,
        onCategoryId = { selectedCategory = it },
        // Derived once, here, and handed down. The guide used to build both
        // of these again from the same inputs — see GuideTab's KDoc.
        categories = categories,
        channels = channels,
        lockedIds = lockedIds,
        hasAnyChannels = allVisible.isNotEmpty(),
        onChannelLongPress = { menuChannel = it },
        onOpenSettings = onOpenSettings,
        gridHandle = gridHandle,
        reminders = reminders,
    )
    scheduleChannel?.let { channel ->
        // Read from the guide table rather than the resident window: this
        // sheet puts a line of synopsis under every title, and the window
        // deliberately carries none. One channel's worth, one query.
        // Null until the query lands: an empty initial value showed "No guide
        // data for this channel" for a frame or two on every open, on a sheet
        // that is only offered when there is guide data.
        val programs by androidx.compose.runtime.produceState<List<com.agoro.tv.data.EpgProgram>?>(
            initialValue = null,
            channel.id,
            epgState,
        ) {
            val from = System.currentTimeMillis() - 3600_000L
            value = vm.scheduleFor(channel, from, from + 8L * 24 * 3600_000)
        }
        ChannelSchedule(
            channel = channel,
            programs = programs,
            nowMs = System.currentTimeMillis(),
            onWatch = {
                scheduleChannel = null
                playFromHost(channel)
            },
            onSelectProgram = { program ->
                // Same rules as the guide: what a programme offers depends on
                // whether it is on now or still to come.
                //
                // "Started already" rather than "on now", because the list is
                // filtered against a clock that ticks every 30 seconds while
                // this reads the real one. In the seconds after a programme
                // ends the row still says ON NOW, and treating that press as a
                // future one set a reminder for something already over. Both
                // cases play the channel.
                val now = System.currentTimeMillis()
                if (program.startMs <= now) {
                    scheduleChannel = null
                    playFromHost(channel)
                    null
                } else if (reminders.isSet(program.id)) {
                    // Same rule the guide follows: a second press takes the
                    // reminder back rather than arming the same alarm again.
                    if (vm.cancelReminder(channel, program)) {
                        reminders.unmark(program.id)
                        "Reminder removed"
                    } else {
                        "Reminder already set"
                    }
                } else {
                    vm.scheduleReminder(channel, program)
                    reminders.mark(program.id)
                    "Reminder set: ${TextNorm.cleanProgrammeTitle(program.title)}"
                }
            },
            onDismiss = {
                // Arm the return first, then unmount: the effect above runs
                // in the frame the sheet leaves and finds the flag set.
                refocusGridOnClose()
                scheduleChannel = null
            },
        )
    }
    menuChannel?.let { channel ->
        val isFav = channel.isFavorite(favorites)
        // Counted the way the schedule sheet counts, which is not the same as
        // "has any programmes at all": the parsed window keeps 30 hours of
        // finished ones, while the sheet lists only what has yet to end.
        val hasSchedule = remember(channel.id, epgState) {
            val now = System.currentTimeMillis()
            // Placeholders are not programmes — the gate and the sheet have
            // to agree on that, or "What's on" opens on "No guide data for
            // this channel", which is the empty sheet this gate exists to
            // prevent. The panel's filler block is what reached here.
            vm.programsFor(channel).any {
                it.endMs > now && !TextNorm.isProgrammePlaceholder(it.title)
            }
        }
        ContextMenu(
            title = channel.displayName,
            actions = buildList {
                add(MenuAction("Play") { playFromHost(channel) })
                // Offered only when there is something to show — otherwise this
                // is a menu row that opens an empty sheet.
                if (hasSchedule) {
                    add(MenuAction("What's on") { scheduleChannel = channel })
                }
                add(
                    MenuAction(if (isFav) "Remove from favorites" else "Add to favorites") {
                        vm.toggleFavorite(channel)
                    }
                )
                // Destructive, and it says where the channel went.
                //
                // It sat in the same type and colour as Play directly above
                // it, and the only feedback was the row disappearing — which
                // from a seat reads as the app losing a channel rather than as
                // the press doing what it said. Nothing here is irreversible,
                // so the toast names the one place that reverses it.
                add(
                    MenuAction("Hide this channel", destructive = true) {
                        vm.toggleHidden(channel)
                        hostMessage = "Channel hidden — restore it in Settings"
                    }
                )
            },
            onDismiss = {
                refocusGridOnClose()
                menuChannel = null
            },
        )
    }
    // Over the guide rather than above it, for the reason the guide's own
    // toast is: as a sibling in a column it would push the whole screen down
    // for four seconds and snap it back.
    com.agoro.tv.ui.components.ToastBadge(
        message = hostMessage,
        modifier = Modifier
            .align(Alignment.BottomEnd)
            .padding(bottom = Space.m, end = Space.m),
    )
    }
}

/**
 * The one "there is no live TV here" screen.
 *
 * There were two, twenty lines apart, for conditions a viewer cannot tell
 * apart: the playlist carries no live streams at all, and every live stream it
 * carries is hidden or behind a PIN. They had different sentences and
 * different buttons ("Switch playlist" / "Open Settings") for what is one
 * destination, so which of two screens you got told you something about the
 * app's internals and nothing about what to do next.
 */
@Composable
internal fun NoLiveChannelsPane(onOpenSettings: () -> Unit) {
    StatusPane(
        title = "No live channels",
        message = "This playlist has no live TV to show.",
        icon = Icons.Default.LiveTv,
        primaryAction = StatusAction("Open Settings", onOpenSettings),
    )
}

@Composable
fun CategoryItem(
    name: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier.fillMaxWidth(),
    locked: Boolean = false,
    // No onBlur. It existed for dwell-select owners to cancel a pending
    // select, and there are none left in the app — the last of them was
    // Manage channels' category column.
    onFocus: () -> Unit = {},
) {
    Surface(
        onClick = onClick,
        modifier = modifier.onFocusChanged { if (it.isFocused) onFocus() },
        // 14dp: at 8dp these read as rectangles with the corners knocked
        // off, and at a full capsule they read as lozenges — more shape than
        // the word inside needs on something this wide and short.
        shape = ClickableSurfaceDefaults.shape(NuxShape.FilterChip),
        // Focus is a FILL, not an outline. A 2dp ring is a desktop idiom read
        // from 60cm; across a room the eye finds a solid shape long before it
        // finds a hairline. It also ends the argument about the ring's
        // corners — there is no ring.
        //
        // ONE STATE LANGUAGE, shared with the bar above ([TopNavItem]). The
        // two rows used to say opposite things one dp apart: up there
        // selection was transparent and focus a dim raised fill, down here
        // selection was FILLED and focus a solid white one. Exactly one state
        // fills now, and it is focus.
        //
        //   resting            dim text, no container
        //   selected           gold text, no container
        //   focused            white fill, dark text
        //   focused + selected white fill, gold text
        //
        // The selected container is gone, which is the header's own rule
        // finally applied here: "two filled states one lightness step apart is
        // one state at ten feet".
        colors = ClickableSurfaceDefaults.colors(
            containerColor = Color.Transparent,
            focusedContainerColor = NuxColors.FocusBorder,
            contentColor = if (selected) NuxColors.Primary else NuxColors.OnSurfaceDim,
            // Dark ON the fill: white text on a white chip is a blank pill.
            // Selected keeps gold, so a chip you are pointing at and a chip
            // you are on stay tellable apart — PrimaryDim, because full gold
            // on this fill is about 2.0:1 and reads as a smudge at ten feet.
            focusedContentColor =
                if (selected) NuxColors.PrimaryDim else NuxColors.Background,
        ),
        // No scale, matching the bar. The fill is the whole focus mark, and a
        // chip that grows 1.06 inside a clipping LazyRow is what forced the
        // ring room the strips carry — see [shelfRingRoom].
        scale = ClickableSurfaceDefaults.scale(
            focusedScale = NuxFocus.RowScale,
        ),
        border = ClickableSurfaceDefaults.border(
            focusedBorder = Border.None,
        ),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            // Wider than it is tall, which is what makes a pill read as one.
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
        ) {
            Text(
                text = name,
                style = MaterialTheme.typography.titleSmall,
                // Two lines: provider category names ("DREAMWORKS ANIMATION",
                // "PARAMOUNT PICTURES") truncated to gibberish on one.
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                // No weight: in the guide's horizontal chip row the incoming
                // width is unbounded, and a weighted child in an unbounded Row
                // measures at zero — every chip collapsed to an empty blob.
            )
            if (locked) {
                Icon(
                    Icons.Default.Lock,
                    contentDescription = "Locked category",
                    modifier = Modifier.size(16.dp),
                )
            }
        }
    }
}
