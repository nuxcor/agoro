@file:OptIn(androidx.tv.material3.ExperimentalTvMaterial3Api::class)

package com.agoro.tv.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Box
import androidx.compose.ui.focus.FocusRequester
import com.agoro.tv.ui.components.dpadLongPress
import com.agoro.tv.ui.components.requestFocusRetrying
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.ui.Alignment
import com.agoro.tv.ui.theme.Space
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Row
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Search
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.OutlinedTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.agoro.tv.MainViewModel
import com.agoro.tv.data.LiveChannel
import com.agoro.tv.data.Movie
import com.agoro.tv.data.Series
import com.agoro.tv.ui.components.ChannelShelfCard
import com.agoro.tv.ui.components.ContextMenu
import com.agoro.tv.ui.components.MenuAction
import com.agoro.tv.ui.components.NuxFieldDefaults
import com.agoro.tv.ui.components.StatusPane
import com.agoro.tv.ui.components.dpadFieldNavigation
import com.agoro.tv.ui.components.rememberClockFormat
import com.agoro.tv.ui.components.PosterCard
import com.agoro.tv.ui.components.ShelfRingRoom
import com.agoro.tv.ui.components.shelfRingRoom
import com.agoro.tv.ui.components.SectionTitle
import com.agoro.tv.ui.components.WideItem
import com.agoro.tv.ui.theme.NuxColors
import com.agoro.tv.data.isFavorite

/**
 * How wide the search bar gets, however wide the panel is.
 *
 * A search bar is a thing you type into, not a banner. Run edge to edge it
 * read as a form, and the three letters you had typed sat alone at the left
 * of 800dp of empty box.
 */
private val SEARCH_BAR_WIDTH = 620.dp

/** A capsule. A search box is the one control that is a shape people know. */
private val SEARCH_BAR_SHAPE = androidx.compose.foundation.shape.RoundedCornerShape(28.dp)

/**
 * The bar's own colours.
 *
 * Separate from [NuxFieldDefaults], which the dialogs share and which is right
 * for them — a form field in a dialog wants a visible outline at rest. This
 * one wants none at rest, and a WHITE ring on focus: the theme's rule is that
 * focus is white and gold means brand, and the shared defaults ring this in
 * gold on the one screen that is nothing but a text box.
 */
@androidx.compose.runtime.Composable
private fun searchBarColors() = androidx.compose.material3.OutlinedTextFieldDefaults.colors(
    focusedTextColor = NuxColors.OnSurface,
    unfocusedTextColor = NuxColors.OnSurface,
    focusedContainerColor = NuxColors.SurfaceRaised,
    unfocusedContainerColor = NuxColors.SurfaceVariant,
    focusedBorderColor = NuxColors.FocusBorder,
    unfocusedBorderColor = androidx.compose.ui.graphics.Color.Transparent,
    cursorColor = NuxColors.Primary,
)


@Composable
fun SearchTab(
    vm: MainViewModel,
    onOpenMovie: (Movie) -> Unit,
    onOpenSeries: (Series) -> Unit,
    onPlay: () -> Unit,
    /** BACK returns to the tab search was opened from, not to Home. */
    onBack: () -> Unit = {},
) {
    var query by rememberSaveable { mutableStateOf("") }
    val recentSearches by vm.recentSearches.collectAsState()
    // Recorded only once the typing stops AND the query found something. A
    // keystroke on the way to a word is not a search, and neither is a
    // misspelling that matched nothing — a history of those is worse than none.
    LaunchedEffect(query) {
        val trimmed = query.trim()
        if (trimmed.length < 2) return@LaunchedEffect
        kotlinx.coroutines.delay(1_200)
        vm.recordSearch(trimmed)
    }
    // Search IS a drawer destination, but BACK still returns to the tab it
    // was opened from rather than opening the drawer again — reaching search
    // from Series and being handed the menu loses the shelf you were standing
    // in. (The IME's own BACK closes the keyboard first, as it should.)
    androidx.activity.compose.BackHandler(onBack = onBack)
    var statusMessage by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(statusMessage) {
        if (statusMessage != null) {
            kotlinx.coroutines.delay(4_000)
            statusMessage = null
        }
    }
    val contentState by vm.content.collectAsState()
    val visible by vm.displayChannels.collectAsState()
    val favorites by vm.favorites.collectAsState()
    val nowNext by vm.nowNext.collectAsState()
    var menuChannel by remember { mutableStateOf<LiveChannel?>(null) }
    // Hold OK on a film, the same two words Home and Movies offer. Shows get
    // none: which episode a series card means is a question only the detail
    // screen answers, so OK already does the only thing there is to do.
    var menuMovie by remember { mutableStateOf<Movie?>(null) }
    /**
     * What the focused card is, for the pinned hero below the query field.
     *
     * A State holder read only by [SearchHeroSlot], never in this scope: read
     * here it would recompose the whole tab — every shelf and every composed
     * card — on each poster the cursor passes over.
     *
     * Search is the one poster surface that had no hero, and [PosterCard] is
     * captionless on purpose (the title lives in the hero on Home and in the
     * browse grids). So the screen where confirming a title match matters most
     * was the only one that never printed one. Channels and programmes clear
     * it: their rows carry their own names, and a stale film title over them
     * would be describing something that is no longer focused.
     */
    val shownHero = remember { mutableStateOf<HeroInfo?>(null) }
    // The card the menu was opened on keeps a requester after the menu
    // closes, so focus can come back to it: left to Compose, a dismissed
    // menu dropped focus onto the query field and the keyboard popped up
    // over the results.
    var menuOrigin by remember { mutableStateOf<String?>(null) }
    val menuOriginFocus = remember { FocusRequester() }
    // Focus comes back in the SAME FRAME the menu unmounts, not after a
    // wall-clock wait. This used to delay 120ms before asking, and in that
    // window Compose had already reseated focus on the nearest node — the
    // query field, keyboard and all — so a quick press after closing typed
    // into it. The request cannot be made before the menu is gone: the
    // dialog scaffold cancels any focus exit while it stands. Armed by the
    // dismissal (a plain holder: nothing in composition reads it) and run by
    // the effect keyed on the menu state once the frame that removed it has
    // applied, before the next key event can arrive. The retries are the
    // bounded fallback for a shelf still recomposing; a hidden channel
    // leaves the shelf, so a refusal there just means the card is gone.
    val returnFocusPending = remember { booleanArrayOf(false) }
    LaunchedEffect(menuChannel, menuMovie) {
        if (menuChannel != null || menuMovie != null || !returnFocusPending[0]) {
            return@LaunchedEffect
        }
        returnFocusPending[0] = false
        menuOriginFocus.requestFocusRetrying(retries = 5, intervalMs = 60)
    }
    var results by remember { mutableStateOf(MainViewModel.SearchResults()) }
    // Debounced off-main-thread search so typing stays smooth on huge playlists.
    LaunchedEffect(query, contentState, visible) {
        kotlinx.coroutines.delay(250)
        results = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
            val base = vm.search(query)
            val allowed = visible.mapTo(HashSet()) { it.id }
            base.copy(channels = base.channels.filter { it.id in allowed })
        }
        // The old result's name must not stand over the new results.
        shownHero.value = null
    }

    val timeFmt = rememberClockFormat()
    val dayFmt = remember { java.text.SimpleDateFormat("EEE", java.util.Locale.getDefault()) }
    /** "9:00 AM", "Tomorrow 9:00 AM", "Mon 9:00 AM" — the day only when it is not today. */
    fun airTime(startMs: Long): String {
        val time = timeFmt.format(java.util.Date(startMs))
        val cal = java.util.Calendar.getInstance()
        val today = cal.get(java.util.Calendar.DAY_OF_YEAR)
        cal.timeInMillis = startMs
        val day = cal.get(java.util.Calendar.DAY_OF_YEAR)
        return when (day - today) {
            0 -> time
            1 -> "Tomorrow $time"
            else -> "${dayFmt.format(java.util.Date(startMs))} $time"
        }
    }
    Box(modifier = Modifier.fillMaxSize()) {
    Column(
        modifier = Modifier.fillMaxSize(),
    ) {
        // Search is entered deliberately — it is a row in the drawer — so the
        // query field takes focus on arrival instead of stranding it wherever
        // the drawer's dismissal dropped it.
        val fieldFocus = com.agoro.tv.ui.components.rememberInitialFocus(Unit)

        // Voice is the only humane way to type on a remote: the alternative is
        // walking a D-pad around a grid of letters. Handed to the system
        // recogniser rather than run in-process, so the app holds no
        // RECORD_AUDIO and the prompt is the one the viewer already knows.
        val context = androidx.compose.ui.platform.LocalContext.current
        val voiceIntent = remember {
            android.content.Intent(android.speech.RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(
                    android.speech.RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                    android.speech.RecognizerIntent.LANGUAGE_MODEL_FREE_FORM,
                )
                putExtra(android.speech.RecognizerIntent.EXTRA_PROMPT, "Say a channel, film or show")
            }
        }
        // Offered only where something can answer it. Plenty of TV boxes ship
        // without a recogniser, and a mic that opens nothing is worse than no
        // mic: it is a control the viewer has to learn is broken.
        val canSpeak = remember {
            runCatching {
                voiceIntent.resolveActivity(context.packageManager) != null
            }.getOrDefault(false)
        }
        val speech = androidx.activity.compose.rememberLauncherForActivityResult(
            androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()
        ) { result ->
            val said = result.data
                ?.getStringArrayListExtra(android.speech.RecognizerIntent.EXTRA_RESULTS)
                ?.firstOrNull()
                ?.trim()
                .orEmpty()
            // A cancelled prompt returns nothing: leave what was typed alone
            // rather than clearing the field the viewer may have been editing.
            if (said.isNotEmpty()) query = said
        }

        Row(
            modifier = Modifier
                // NOT the full panel. A search bar is a thing you type into,
                // not a banner: run edge to edge on a 960dp canvas it read as
                // a form to fill in, and the eye had to cross 800dp of empty
                // box to find the three letters in it.
                .widthIn(max = SEARCH_BAR_WIDTH)
                // Coming back UP to the query row is leaving the shelves, so
                // the hero stops describing a poster — same rule the channel
                // and programme rows follow, from the other direction. Without
                // it a film's name stood over the field being edited, naming
                // something about to stop being a result at all.
                .onFocusChanged { if (it.hasFocus) shownHero.value = null },
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Space.s),
        ) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                // A PLACEHOLDER, not a label. The floating label notched
                // itself into the top border and sat there permanently once
                // there was text, so the bar wore a caption cut through its
                // own outline — the single ugliest thing on the screen, and
                // pure Material desktop convention that a TV never needed.
                // The placeholder says the same words and then gets out of
                // the way.
                placeholder = {
                    androidx.compose.material3.Text(
                        "Search channels, films and shows",
                        color = NuxColors.OnSurfaceDim,
                    )
                },
                shape = SEARCH_BAR_SHAPE,
                singleLine = true,
                // A magnifier at the leading edge. The field used to be a bare
                // outlined box running the width of the panel, which on a TV
                // reads as a form to fill in rather than the thing you came
                // here to do — and it was the one search surface in the app
                // with no search mark on it at all.
                leadingIcon = {
                    androidx.tv.material3.Icon(
                        androidx.compose.material.icons.Icons.Default.Search,
                        contentDescription = null,
                        tint = NuxColors.OnSurfaceDim,
                    )
                },
                modifier = Modifier
                    .weight(1f)
                    .focusRequester(fieldFocus)
                    .dpadFieldNavigation(),
                // Its own colours rather than NuxFieldDefaults, which the
                // dialogs share and which is right for them: a form field in
                // a dialog wants a visible outline at rest. This one wants
                // none — and its focused border is WHITE, because the theme's
                // own rule is that focus is white and gold means brand. The
                // shared defaults ring it in gold, which on the one screen
                // that is nothing but a text box made the box the loudest
                // thing in the app.
                colors = searchBarColors(),
            )
            if (canSpeak) {
                androidx.tv.material3.OutlinedButton(
                    onClick = {
                        // Never let a missing or broken recogniser take the
                        // screen down; the field still works without it.
                        runCatching { speech.launch(voiceIntent) }
                    },
                ) {
                    // tv-material3's Icon, not Compose Material3's — this was
                    // the one place in the app that used the other one, and it
                    // is why the mic came out dark and hard to see. Material3's
                    // Icon tints from ITS OWN LocalContentColor, which nothing
                    // here provides: the app themes with tv-material3, so the
                    // M3 hierarchy is never set up and the icon fell back to a
                    // near-black default instead of inheriting the button's
                    // content colour.
                    androidx.tv.material3.Icon(
                        Icons.Default.Mic,
                        contentDescription = "Search by voice",
                    )
                }
            }
        }
        // Pinned above the results, one line tall, exactly as the browse grids
        // pin theirs: the shelves scroll, so a focused poster's name has to
        // live somewhere that does not scroll away with it. A fixed height, so
        // the results below do not shift as the hero fills in and empties.
        //
        // Only where there are posters to describe. Channel and programme rows
        // carry their own names, and the two empty states have nothing to
        // name — reserving the band there is 52dp of blank above a message.
        if (results.movies.isNotEmpty() || results.series.isNotEmpty()) {
            Box(modifier = Modifier.fillMaxWidth().height(BROWSE_HERO_HEIGHT)) {
                SearchHeroSlot(shownHero)
            }
        } else {
            // The band replaces the plain gap that used to sit here, so the
            // other states still need one — without it a channel shelf, or the
            // "no results" pane, butted straight against the query field.
            Spacer(Modifier.height(20.dp))
        }

        val empty = results.channels.isEmpty() && results.movies.isEmpty() &&
            results.series.isEmpty() && results.programs.isEmpty()
        when {
            // Before there is a query: what you searched for before, if
            // anything. Typing on a remote is walking a D-pad around a grid of
            // letters, so the cheapest search is one already typed — and this
            // was previously a full-screen pane whose entire content was an
            // instruction to type more.
            query.trim().length < 2 && recentSearches.isNotEmpty() -> Column {
                SectionTitle("Recent searches")
                LazyRow(
                    modifier = Modifier.focusRestorer().shelfRingRoom(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    contentPadding = PaddingValues(horizontal = ShelfRingRoom),
                ) {
                    itemsIndexed(recentSearches, key = { _, q -> q }) { _, past ->
                        CategoryItem(
                            name = past,
                            selected = false,
                            onClick = { query = past },
                            // Held down, a chip forgets itself. A history you
                            // cannot edit is one bad search away from being
                            // someone else's business on a shared TV. Through
                            // the modifier because CategoryItem takes no
                            // long-press of its own, and dpadLongPress is the
                            // app's clock-based one — many remotes send no key
                            // repeat for tv-material's version to count.
                            modifier = Modifier
                                .dpadLongPress { vm.forgetSearch(past) },
                        )
                    }
                }
            }

            query.trim().length < 2 -> StatusPane(
                title = "Search your library",
                // No instruction to type: the field's own label already names
                // what is searchable, and a screen whose only content is
                // "type more" says nothing the cursor did not.
                icon = androidx.compose.material.icons.Icons.Default.Search,
            )

            empty -> StatusPane(
                title = "No results for \"${query.trim()}\"",
                message = "Check the spelling, or try a shorter word.",
                icon = androidx.compose.material.icons.Icons.Default.Search,
            )

            else -> LazyColumn(
                verticalArrangement = Arrangement.spacedBy(20.dp),
                contentPadding = PaddingValues(bottom = 32.dp),
            ) {
                if (results.movies.isNotEmpty()) {
                    item(key = "movies") {
                        Column {
                            SectionTitle("Movies", results.movies.size)
                            LazyRow(
                                modifier = Modifier.focusRestorer().shelfRingRoom(),
                                horizontalArrangement = Arrangement.spacedBy(14.dp),
                                contentPadding = PaddingValues(horizontal = ShelfRingRoom),
                            ) {
                                itemsIndexed(results.movies, key = { _, m -> m.id }) { _, movie ->
                                    PosterCard(
                                        title = movie.name,
                                        imageUrl = borrowedArt(vm, movie.artRef(), movie.poster),
                                        year = movie.year,
                                        modifier = if (movie.id == menuOrigin) {
                                            Modifier.focusRequester(menuOriginFocus)
                                        } else Modifier,
                                        onClick = { onOpenMovie(movie) },
                                        onLongClick = {
                                            menuOrigin = movie.id
                                            menuMovie = movie
                                        },
                                        onFocus = { shownHero.value = movie.toHero() },
                                    )
                                }
                            }
                        }
                    }
                }
                if (results.series.isNotEmpty()) {
                    item(key = "series") {
                        Column {
                            SectionTitle("Series", results.series.size)
                            LazyRow(
                                modifier = Modifier.focusRestorer().shelfRingRoom(),
                                horizontalArrangement = Arrangement.spacedBy(14.dp),
                                contentPadding = PaddingValues(horizontal = ShelfRingRoom),
                            ) {
                                itemsIndexed(results.series, key = { _, s -> s.id }) { _, series ->
                                    PosterCard(
                                        title = series.name,
                                        imageUrl = borrowedArt(vm, series.artRef(), series.poster),
                                        year = series.year,
                                        onClick = { onOpenSeries(series) },
                                        onFocus = {
                                            shownHero.value = series.toHero()
                                            vm.prefetchEpisodes(series)
                                        },
                                    )
                                }
                            }
                        }
                    }
                }
                if (results.channels.isNotEmpty()) {
                    // A shelf, matching the two above it and Home's rows. As a
                    // stack of full-width text rows this was the one place a
                    // channel arrived without its logo at a readable size, and
                    // without what is on it right now — the two things that
                    // tell you whether it is the channel you meant.
                    item(key = "channels") {
                        Column {
                            SectionTitle("Live channels", results.channels.size)
                            LazyRow(
                                modifier = Modifier.focusRestorer().shelfRingRoom(),
                                horizontalArrangement = Arrangement.spacedBy(14.dp),
                                contentPadding = PaddingValues(horizontal = ShelfRingRoom),
                            ) {
                                itemsIndexed(
                                    results.channels,
                                    key = { _, c -> c.id },
                                ) { index, channel ->
                                    ChannelShelfCard(
                                        channel = channel,
                                        now = nowNext[channel.id]?.now,
                                        modifier = if (channel.id == menuOrigin) {
                                            Modifier.focusRequester(menuOriginFocus)
                                        } else Modifier,
                                        onClick = {
                                            vm.playChannels(results.channels, index)
                                            onPlay()
                                        },
                                        onLongClick = {
                                            menuOrigin = channel.id
                                            menuChannel = channel
                                        },
                                        // The card names itself; a film title
                                        // left standing above it would be
                                        // describing the wrong thing.
                                        onFocus = { shownHero.value = null },
                                    )
                                }
                            }
                        }
                    }
                }
                if (results.programs.isNotEmpty()) {
                    item(key = "programs-title") { SectionTitle("On TV", results.programs.size) }
                    itemsIndexed(
                        results.programs,
                        key = { _, hit -> "${hit.channel.id}:${hit.program.startMs}" },
                    ) { _, hit ->
                        val airing = System.currentTimeMillis() in
                            hit.program.startMs until hit.program.endMs
                        WideItem(
                            title = hit.program.title,
                            subtitle = "${hit.channel.displayName} • " + airTime(hit.program.startMs),
                            // The same vocabulary as the guide's header chip,
                            // so OK does what the row says it does.
                            badge = if (airing) "ON NOW" else "OK to remind",
                            imageUrl = hit.channel.logo,
                            onFocus = { shownHero.value = null },
                            onClick = {
                                // The guide's rule: a programme on now plays,
                                // one still to come is remembered. Tuning a
                                // channel eight hours before the thing you
                                // searched for is not watching it.
                                val now = System.currentTimeMillis()
                                if (hit.program.startMs <= now) {
                                    vm.playChannels(listOf(hit.channel), 0)
                                    onPlay()
                                } else {
                                    vm.scheduleReminder(hit.channel, hit.program)
                                    statusMessage = "Reminder set: ${hit.program.title}"
                                }
                            },
                        )
                    }
                }
            }
        }
    }
    com.agoro.tv.ui.components.ToastBadge(
        message = statusMessage,
        modifier = Modifier
            .align(Alignment.BottomEnd)
            .padding(bottom = Space.m, end = Space.m),
    )
    }
    menuMovie?.let { movie ->
        ContextMenu(
            title = movie.name,
            actions = listOf(
                MenuAction("Play") { vm.playMovie(movie); onPlay() },
                MenuAction("Details") { onOpenMovie(movie) },
            ),
            onDismiss = {
                returnFocusPending[0] = true
                menuMovie = null
            },
        )
    }
    menuChannel?.let { channel ->
        val isFav = channel.isFavorite(favorites)
        ContextMenu(
            title = channel.displayName,
            actions = listOf(
                MenuAction("Play") {
                    // By id: value equality on LiveChannel drifts with the
                    // merge's fallbackUrls - see GuideTab.
                    val index = results.channels.indexOfFirst { it.id == channel.id }.coerceAtLeast(0)
                    vm.playChannels(results.channels, index)
                    onPlay()
                },
                MenuAction(if (isFav) "Remove from favorites" else "Add to favorites") {
                    vm.toggleFavorite(channel)
                },
                MenuAction("Hide this channel") { vm.toggleHidden(channel) },
            ),
            onDismiss = {
                // Arm the return first, then unmount: the effect above runs
                // in the frame the menu leaves and finds the flag set.
                returnFocusPending[0] = true
                menuChannel = null
            },
        )
    }
}

/**
 * The hero reads its own State, so a focus move recomposes this and nothing
 * else — not the tab, its shelves, or the cards in them. Same split as
 * [HomeHeroSlot] and the browse grids' [BrowseHeroSlot].
 */
@Composable
private fun SearchHeroSlot(hero: androidx.compose.runtime.State<HeroInfo?>) {
    BrowseHero(hero.value)
}
