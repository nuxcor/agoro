@file:OptIn(
    androidx.tv.material3.ExperimentalTvMaterial3Api::class,
    androidx.compose.ui.ExperimentalComposeUiApi::class,
)

package com.agoro.tv.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.input.key.nativeKeyCode
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.key
import androidx.compose.ui.focus.onFocusChanged
import kotlinx.coroutines.launch
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.draw.drawBehind
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.agoro.tv.MainViewModel
import com.agoro.tv.data.ContentState
import com.agoro.tv.data.Movie
import com.agoro.tv.data.Series
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.ui.graphics.graphicsLayer
import com.agoro.tv.ui.components.BackdropLayer
import com.agoro.tv.ui.components.animateToOrSnap
import com.agoro.tv.ui.components.StatusAction
import com.agoro.tv.ui.components.StatusPane
import com.agoro.tv.ui.components.requestFocusRetrying
import com.agoro.tv.ui.theme.HEADER_BAND_HEIGHT
import com.agoro.tv.ui.theme.HeaderWash
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import com.agoro.tv.ui.theme.NuxColors
import com.agoro.tv.ui.theme.NuxMotion
import com.agoro.tv.ui.theme.NuxShape
import com.agoro.tv.ui.theme.Space
import kotlinx.coroutines.delay

@Composable
fun HomeScreen(
    vm: MainViewModel,
    onOpenMovie: (Movie) -> Unit,
    onOpenSeries: (Series) -> Unit,
    onPlay: () -> Unit,
    onAddPlaylist: () -> Unit,
    onEditPlaylist: (String) -> Unit,
) {
    var tab by rememberSaveable { mutableStateOf(HomeTab.Home) }
    /**
     * The tab search was opened from, which is where Back out of it returns.
     *
     * Search used to be reachable only from Home's pill, so "Back goes Home"
     * and "Back goes where you came from" were the same sentence. They are
     * not any more — the browse strips have a Search chip and the remote's
     * search key opens it from anywhere — and sending a viewer who searched
     * from Shows back to Home loses them the shelf they were standing in.
     */
    var searchOrigin by rememberSaveable { mutableStateOf(HomeTab.Home) }
    val openSearch = {
        // Guarded, so a second press while search is already up cannot make
        // Search its own origin and strand Back on this screen.
        if (tab != HomeTab.Search) searchOrigin = tab
        tab = HomeTab.Search
    }
    // Non-content tabs also work while the playlist is loading or failed.
    val contentState by vm.content.collectAsState()
    val updateState by vm.updateState.collectAsState()
    // The Home lounge's focused-card hero, hoisted so its backdrop can draw
    // full-bleed across the content lane — outside the gutter-padded Box every
    // tab composes into. Debounced by the lounge before it lands here.
    var homeHero by remember { mutableStateOf<HeroInfo?>(null) }
    // The header is always composed and always visible. What this tracks is
    // whether it holds FOCUS — the content must not steal focus back while
    // the viewer is choosing a destination, and the guide stands its BACK
    // rungs down for the same reason.
    var headerFocused by remember { mutableStateOf(false) }
    // One requester per header control, owned here rather than by [TopNav] so
    // that none of them ever moves between nodes — see the parameter's doc for
    // the bug that costs. One spare, always allocated: sizing this to the
    // controls actually shown would rebuild every requester the moment a
    // background update check came back, and the rebuilt one the viewer was
    // standing on no longer points at the control holding focus.
    val navFocus = remember { List(HomeTab.entries.size + 1) { FocusRequester() } }
    val shellScope = rememberCoroutineScope()
    fun focusHeader() {
        // At the tab you are ON, which is where "out" means something.
        shellScope.launch { navFocus[tab.ordinal].requestFocusRetrying() }
    }
    // Hoisted above the Ready branch so a refresh cycle doesn't wipe tab state.
    val tabStateHolder = rememberSaveableStateHolder()

    // BACK from inside the content pane jumps focus to the header first; on
    // the header, BACK asks for confirmation instead of instantly quitting.
    var exitArmed by remember { mutableStateOf(false) }
    LaunchedEffect(exitArmed) {
        if (exitArmed) {
            delay(2_500)
            exitArmed = false
        }
    }
    val contentFocus = remember { FocusRequester() }
    // Whether anything in the content lane holds focus right now. The
    // parking loops below check it before every attempt: a tab that has
    // already seated focus on the card the viewer left (each tab's own
    // arrival logic) must not have it yanked to the pane's first focusable
    // by a shell retry that fires a frame later — which is exactly what
    // happened on every return from a detail page.
    var contentHasFocus by remember { mutableStateOf(false) }
    suspend fun parkInContent(retries: Int, intervalMs: Long): Boolean {
        repeat(retries) { attempt ->
            if (contentHasFocus) return true
            if (runCatching { contentFocus.requestFocus() }.getOrDefault(false)) return true
            if (attempt < retries - 1) delay(intervalMs)
        }
        return contentHasFocus
    }
    // Survives this screen leaving composition, which is what going to the
    // player does: coming back is a return, not a launch.
    var hasLaunched by rememberSaveable { mutableStateOf(false) }

    // Without this the first D-pad press lands wherever Compose's focus search
    // happens to go. Park it somewhere predictable — and retry, since the
    // target composes a frame later.
    //
    // Where depends on why we are here. Both cases park in the content —
    // coming back from the player, restored to the row that was focused when
    // the stream started: BACK out of a channel used to land on the nav,
    // several presses from the list it had just left, which is not going back.
    LaunchedEffect(Unit) {
        // A return is not a launch. Every tab seats its own arrival focus on
        // the card the viewer left, and this loop's first attempt used to
        // fire in the same dispatch — landing on the first focusable (the
        // Search pill, a strip chip) for a frame or three whenever the
        // remembered card was not in the first frame, before the tab's own
        // request hopped it back. On a return the shell only backstops: if
        // the tab has not seated anything after a beat, park as before.
        if (hasLaunched) {
            delay(150)
            if (!contentHasFocus) parkInContent(retries = 25, intervalMs = 80)
            return@LaunchedEffect
        }
        // Park on the CONTENT, always — on launch that is the Live guide,
        // whose entry redirect lands focus on the current programme, so the
        // app boots one OK away from watching. Parking on the nav was both
        // worse UX and fragile: the splash screen's dismissal re-runs the
        // window's default focus placement and could leave nothing focused
        // at all. A long retry window, deliberately: this races a COLD
        // start, where content composes many frames in — not one.
        if (!parkInContent(retries = 25, intervalMs = 80)) {
            // The loading pane has nothing to take focus, so a cold start
            // slower than the first window falls through to here. Stay
            // patient while the library lands; the header is the landing only
            // when content never produces anything focusable at all — and
            // unlike the drawer this was, landing there shows the viewer
            // nothing they were not already looking at.
            if (!parkInContent(retries = 100, intervalMs = 120)) {
                focusHeader()
            }
        }
        hasLaunched = true
    }

    // BACK from the content goes UP to the header — the same journey the
    // D-pad makes, so the two never disagree about where "out" is. On the
    // header it arms the exit instead of quitting on the spot.
    BackHandler(enabled = !headerFocused) {
        focusHeader()
    }
    BackHandler(enabled = headerFocused && !exitArmed) {
        exitArmed = true
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .onPreviewKeyEvent { event ->
                // The remote's own search button. The only entry point that
                // costs no screen and no D-pad travel, and the only one the
                // guide and Live get at all — both are too dense to spend a
                // chip on, and both are where a viewer is most likely to be
                // hunting for something by name.
                //
                // Consumed on BOTH edges: taking the down and letting the up
                // through leaves a stray KeyUp for whatever chip or cell is
                // focused underneath. Nothing else in this app wants this
                // key, so previewing it here — above the header, the tabs and
                // the guide — is the one place it cannot be swallowed first.
                //
                // Declined while the header holds focus: the viewer is
                // already choosing a destination and Search is one of them.
                // Not gated on text entry — Settings keeps its fields in
                // dialogs, which carry their own window and never see this
                // handler, and the only inline field in here belongs to
                // search itself.
                if (event.key == Key.Search && !headerFocused) {
                    if (event.type == KeyEventType.KeyDown) openSearch()
                    return@onPreviewKeyEvent true
                }
                false // everything else: observe only, never consume
            },
    ) {
    // The backdrop runs the full panel, BEHIND the header as well as the
    // content: the header is a band of text over the page, not a bar bolted
    // above it, and stopping the art at its lower edge would draw the seam
    // the design is trying not to have.
    Box(modifier = Modifier.fillMaxSize()) {
        if (tab == HomeTab.Home && contentState is ContentState.Ready) {
            BackdropLayer(
                borrowedArt(vm, homeHero?.art, homeHero?.backdrop, wide = true)
                    ?: homeHero?.poster,
                bleedX = 0.dp,
                bleedY = 0.dp,
            )
        }
        Box(
            modifier = Modifier
                .fillMaxSize()
                // The band's height as padding, not a sibling in a Column.
                // The browse tabs bleed their backdrop past their own bounds,
                // and a Column draws its children in order — so as a sibling
                // the content painted straight over the header.
                .padding(
                    start = Space.gutter,
                    end = Space.gutter,
                    top = HEADER_BAND_HEIGHT,
                    bottom = Space.gutterVertical,
                )
                .focusRequester(contentFocus)
                .onFocusChanged { contentHasFocus = it.hasFocus }
                .focusRestorer()
        ) {
            // No Crossfade: it keeps both tab trees composed and drawn into
            // offscreen layers — a visible hitch on TV hardware.
            //
            // Settings is composed from its saveable slot whatever the content
            // state is, rather than living inside the Ready branch with a second
            // copy stacked on top for the other states. Refresh sets Loading, so
            // the old shape tore Settings down mid-press and rebuilt a different
            // instance outside the state holder: scroll jumped to the top, D-pad
            // focus was lost, the counts and "Manage channels" vanished — then
            // all of it again in reverse when the load landed.
            val current = tab
            // One-shot entrance on tab switch: the tree still swaps instantly
            // (no Crossfade, see above) — only a short rise animates, so
            // focus targeting and the guide's registry see final layout on
            // frame one.
            //
            // A rise, and NOT a fade. Alpha on this Box put the entire
            // content lane — every shelf, poster and backdrop — through a
            // full-screen offscreen layer for fifteen frames, in the same
            // frames the new tab was composing its lists and fetching its
            // images. That is the costliest thing a TV GPU can be asked for
            // and it ran on every tab switch. A translation is free: it is
            // a transform on the display list, not a buffer.
            //
            // Nor on a return. Home leaves composition for every channel and
            // every detail page, so remember(current) was fresh on the way
            // back and the whole lounge rose into view again behind the
            // cut — the launch choreography replayed for a screen the viewer
            // had only stepped away from. A visit that has already launched
            // snaps straight to its resting place.
            val tabEntrance = remember(current) { Animatable(if (hasLaunched) 1f else 0f) }
            LaunchedEffect(current) {
                if (tabEntrance.value == 1f) return@LaunchedEffect
                try {
                    // Snap-on-timeout: an idle window can starve the frame
                    // clock, leaving animateTo suspended and the whole tab
                    // composed but painted at alpha 0 until the first key
                    // press. The wall-clock timeout doesn't need frames, and
                    // snapTo's value change is what restarts drawing.
                    tabEntrance.animateToOrSnap(
                        1f,
                        tween(NuxMotion.StandardMs, easing = NuxMotion.StandardEasing),
                        timeoutMs = NuxMotion.StandardMs + 500L,
                    )
                } finally {
                    // And whatever cancels this effect mid-flight (rapid
                    // dwell-driven tab hops), still land on visible.
                    kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) {
                        tabEntrance.snapTo(1f)
                    }
                }
            }
            Box(
                modifier = Modifier.graphicsLayer {
                    translationY = (1f - tabEntrance.value) * (NuxMotion.EntranceRise.toPx() * 0.66f)
                }
            ) {
            // While the header holds focus, the tab below must not grab it.
            // A pane that focuses itself on arrival would otherwise yank the
            // viewer out of the header mid-choice, leaving LEFT/RIGHT dead.
            // Re-armed the instant focus enters the content.
            androidx.compose.runtime.CompositionLocalProvider(
                com.agoro.tv.ui.components.LocalArrivalFocusAllowed provides !headerFocused,
                // How a tab hands UP back to the navigation above it. Every
                // tab already intercepts UP at its top edge; before the header
                // existed they all intercepted it and went nowhere.
                com.agoro.tv.ui.components.LocalTopNavFocus provides { focusHeader() },
            ) {
            tabStateHolder.SaveableStateProvider(current.name) {
                if (current == HomeTab.Settings) {
                    SettingsTab(
                        vm = vm,
                        bundle = (contentState as? ContentState.Ready)?.bundle,
                        onAddPlaylist = onAddPlaylist,
                        onEditPlaylist = onEditPlaylist,
                    )
                } else when (val state = contentState) {
                    is ContentState.Loading -> StatusPane(title = state.message, loading = true)
                    is ContentState.Error -> StatusPane(
                        title = "Couldn't load your playlist",
                        message = state.message,
                        primaryAction = StatusAction("Retry") { vm.refresh() },
                    )
                    is ContentState.Empty -> StatusPane(
                        title = "No playlist loaded",
                        message = "Connect your provider to start watching.",
                        primaryAction = StatusAction("Add playlist", onAddPlaylist),
                    )
                    is ContentState.Ready -> when (current) {
                        HomeTab.Home -> HomeLoungeTab(
                            vm,
                            state.bundle,
                            onOpenMovie,
                            onOpenSeries,
                            onPlay,
                            onHeroChange = { homeHero = it },
                            // Home's own pill and empty-state action come
                            // through here; routed so search seats its origin
                            // however it was reached.
                            onBrowse = { if (it == HomeTab.Search) openSearch() else tab = it },
                        )
                        HomeTab.Search -> SearchTab(
                            vm, onOpenMovie, onOpenSeries, onPlay,
                            onBack = { tab = searchOrigin },
                        )
                        HomeTab.Live -> LiveTab(vm, state.bundle, onPlay, onOpenSettings = { tab = HomeTab.Settings })
                        HomeTab.Sport -> SportTab(vm, state.bundle, onPlay, onBrowse = { tab = it })
                        HomeTab.Movies -> MoviesTab(
                            vm, state.bundle, onOpenMovie,
                            onPlay = onPlay,
                            onOpenSettings = { tab = HomeTab.Settings },
                        )
                        HomeTab.Series -> SeriesTab(
                            vm, state.bundle, onOpenSeries,
                            onOpenSettings = { tab = HomeTab.Settings },
                        )
                        HomeTab.Settings -> Unit // composed above, state-independent
                    }
                }
            }
            }
            }
        }
    }
    // Painted LAST, so it is never covered. A browse tab bleeds its backdrop
    // past its own bounds to reach the panel edge, and anything drawn after
    // the header wins the pixels — which is how the tab labels ended up
    // half-erased by a poster the first time this was a Column.
    //
    // The wash is a short vertical fade rather than a filled bar: the header
    // sits over the page's own artwork, 16sp labels need something to sit on,
    // and a bar would have a lower edge — the exact thing the nav redesign
    // took the drawer apart to avoid.
    Box(
    Modifier
        .fillMaxWidth()
        .height(HEADER_BAND_HEIGHT)
        .background(HeaderWash)
    )
    TopNav(
        selected = tab,
        // OK or DOWN commits: switch the tab and hand focus to the
        // content, which is the same order the drawer used and for the
        // same reason — the content refuses focus while the header holds
        // it (LocalArrivalFocusAllowed), so the gate has to drop first.
        onSelect = {
            if (it == HomeTab.Search) openSearch() else tab = it
            // The gate first: the content refuses focus while the header
            // holds it (LocalArrivalFocusAllowed), so the hand-off can only
            // land after this drops.
            headerFocused = false
            shellScope.launch {
                // WAIT before reaching for focus, and then check it stuck.
                //
                // Every tab seats its own arrival focus; the shell is only a
                // backstop. Parking in the same frame as the commit put focus
                // on the content Box while its subtree was still being
                // swapped for the new tab's — the park reported success, and
                // 70ms later the swap took focus down with it. The window's
                // default placement then chose the first focusable in the
                // tree, which is the header's own first tab, and the header
                // reporting focus re-armed the gate: committing Live TV left
                // the ring on Home and the content refusing focus for good.
                //
                // Twice, because the first pass can still be undone by a slow
                // tab, and the two-stage ladder because two seconds is enough
                // for a shelf of posters and not for the guide, which builds
                // a grid.
                repeat(2) {
                    delay(300)
                    if (contentHasFocus) return@launch
                    if (!parkInContent(retries = 25, intervalMs = 80)) {
                        parkInContent(retries = 60, intervalMs = 100)
                    }
                }
            }
        },
        itemFocus = navFocus,
        onHeaderFocusChanged = { headerFocused = it },
        // Only the three states where something can actually be done.
        // Checking and Error stay out of the header: a background check
        // that failed is not news, and Settings carries both in full.
        updateLabel = when (val u = updateState) {
            is com.agoro.tv.data.UpdateManager.State.Available ->
                "Update to ${u.version.removePrefix("v")}"
            is com.agoro.tv.data.UpdateManager.State.Downloading ->
                "Downloading… ${u.progressPercent}%"
            is com.agoro.tv.data.UpdateManager.State.Ready -> "Install update"
            else -> null
        },
        // The same call Settings' one button makes, so the two can never
        // disagree about what pressing means in a given state.
        onUpdate = { vm.downloadAndInstallUpdate() },
    )

    // Above everything, so the readout survives any pane that opens over the
    // content — otherwise it stops measuring exactly when the viewer is doing
    // the thing that feels slow.
    //
    // Debug builds only. It used to be a Settings row that asked the viewer
    // to read frame times back to me — an instrument parked where someone had
    // come to pick a channel order. The measurement still only means anything
    // on real hardware, so a debug build is how it gets taken.
    com.agoro.tv.ui.components.FrameStatsOverlay(
        enabled = com.agoro.tv.BuildConfig.DEBUG,
        // Bottom-end, not top: the header's right end is where Settings and
        // the update control live, and the readout sat straight on top of them.
        modifier = Modifier.align(Alignment.BottomEnd).padding(bottom = 4.dp, end = 4.dp),
    )
    androidx.compose.animation.AnimatedVisibility(
        visible = exitArmed,
        enter = androidx.compose.animation.fadeIn(
            tween(NuxMotion.StandardMs, easing = NuxMotion.StandardEasing)
        ) + androidx.compose.animation.slideInVertically(
            tween(NuxMotion.StandardMs, easing = NuxMotion.StandardEasing)
        ) { it / 2 },
        exit = androidx.compose.animation.fadeOut(
            tween(NuxMotion.FastMs, easing = NuxMotion.ExitEasing)
        ),
        modifier = Modifier.align(Alignment.BottomCenter),
    ) {
        Box(
            modifier = Modifier
                .padding(bottom = 24.dp)
                .background(NuxColors.Scrim, NuxShape.Row)
                .padding(horizontal = 18.dp, vertical = 10.dp),
        ) {
            Text(
                "Press BACK again to exit",
                style = MaterialTheme.typography.labelLarge,
                color = NuxColors.OnSurface,
            )
        }
    }
    }
}
