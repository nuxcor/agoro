@file:OptIn(
    androidx.tv.material3.ExperimentalTvMaterial3Api::class,
    androidx.compose.ui.ExperimentalComposeUiApi::class,
)

package com.agoro.tv.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.unit.dp
import androidx.tv.material3.OutlinedButton
import androidx.tv.material3.Text
import com.agoro.tv.MainViewModel
import com.agoro.tv.data.Category
import com.agoro.tv.data.ContentBundle
import com.agoro.tv.ui.components.MetaChip
import com.agoro.tv.ui.components.ScreenTitle
import com.agoro.tv.ui.components.WideItem
import com.agoro.tv.ui.theme.Space

@Composable
internal fun ChannelManager(vm: MainViewModel, bundle: ContentBundle, onClose: () -> Unit) {
    val hidden by vm.hidden.collectAsState()
    // Without this, BACK falls through to Home's handlers and starts the
    // app-exit sequence while the manager is still open — the one place left
    // in the app where BACK didn't mean "go back".
    BackHandler(onBack = onClose)
    Column(modifier = Modifier.fillMaxSize()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            ScreenTitle("Manage channels", modifier = Modifier.weight(1f))
            OutlinedButton(onClick = onClose) { Text("Done") }
        }
        // No instruction line. It told the viewer what OK does — which the
        // rows then told them again, one by one, in a subtitle apiece — and
        // an app that has to explain its own OK key on a screen with two
        // columns and one action is describing itself rather than showing a
        // list. What the screen has to do is make a hidden channel LOOK
        // hidden; see the rows below.
        Spacer(Modifier.height(14.dp))

        // This is the screen for finding one unwanted channel among the few
        // thousand the app is built to handle, and it had neither of the two
        // things Live TV uses to cross that many rows. It works on
        // bundle.channels rather than displayChannels on purpose: hidden
        // channels have to be listed here or there is no way to unhide one.
        var selectedCategory by rememberSaveable { mutableStateOf(CATEGORY_ALL) }
        val categories = remember(bundle) {
            // Cased here because this screen does NOT go through
            // liveCategoryList — it works on bundle.channels so a hidden
            // channel stays listable, and so it builds its own list. Same
            // rule, applied at the one place that has to know it.
            listOf(Category(id = CATEGORY_ALL, name = "All channels")) +
                bundle.liveCategories.map { it.copy(name = categoryLabel(it.name)) }
        }
        val activeCategory = resolveCategoryId(selectedCategory, categories)
        // No dwell-select. This was the last one left in the app: Live TV, the
        // guide, Movies, Shows and the player's guide all moved to OK-selects,
        // on the reasoning the guide writes down — two category controls
        // disagreeing about whether resting counts as choosing is exactly the
        // inconsistency that reads as a bug. It bit hardest here, where the
        // whole job is travelling a few hundred categories to find one channel,
        // so every name passed over re-filtered the list beside it.
        // Through the shared filter, not a second copy of it. This screen
        // wrote its own two-line version — the same two rules [channelsInCategory]
        // already states — and it was the last caller of the All branch, so
        // once the live surfaces stopped asking for All that branch had only
        // tests looking at it. No index and no merged list is passed, which is
        // deliberate: this screen works on bundle.channels because a hidden
        // channel has to be listed here or there is no way to unhide one, and
        // both defaults then resolve to exactly the filter this replaced.
        val channels = remember(bundle, activeCategory) {
            channelsInCategory(activeCategory, bundle.channels, emptySet(), emptyList())
        }
        val jump = rememberChannelJump(channels)
        // The Settings list this replaces held focus; without an arrival
        // target the first press was spent on Compose's own guess (the
        // bottom-right row, for UP). Land on the category column, which is
        // where the work starts.
        val arrival = com.agoro.tv.ui.components.rememberInitialFocus(Unit)

        Box(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxSize(),
            horizontalArrangement = Arrangement.spacedBy(Space.m),
        ) {
            LazyColumn(
                modifier = Modifier
                    .width(190.dp)
                    .fillMaxHeight()
                    .focusRestorer(),
                verticalArrangement = Arrangement.spacedBy(Space.xs),
                contentPadding = PaddingValues(bottom = Space.l),
            ) {
                itemsIndexed(categories, key = { _, c -> c.id }) { index, category ->
                    CategoryItem(
                        name = category.name,
                        selected = category.id == activeCategory,
                        onClick = { selectedCategory = category.id },
                        modifier = if (index == 0) {
                            Modifier.fillMaxWidth().focusRequester(arrival)
                        } else Modifier.fillMaxWidth(),
                    )
                }
            }
            LazyColumn(
                state = jump.listState,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .focusRestorer()
                    .channelJumpKeys(jump),
                verticalArrangement = Arrangement.spacedBy(6.dp),
                contentPadding = PaddingValues(bottom = 32.dp),
            ) {
                itemsIndexed(channels, key = { _, c -> c.id }) { index, channel ->
                    val isHidden = channel.url in hidden
                    // A hidden channel LOOKS hidden: dimmed, with one word at
                    // the trailing edge. This is the only screen whose entire
                    // job is spotting hidden channels, and a hidden row used
                    // to differ from a shown one by the wording of a 14sp
                    // subtitle — so scanning a few hundred rows meant reading
                    // every one of them.
                    //
                    // No subtitle on a shown row, and no quality badge on any
                    // of them. "Shown — OK to hide" is an instruction wearing
                    // a fact's clothes, and "FHD" is the provider's own claim
                    // about a stream, which has nothing to do with whether
                    // the viewer wants the channel in their list.
                    val hiddenMark: (@Composable () -> Unit)? =
                        if (isHidden) {
                            { MetaChip("Hidden") }
                        } else null
                    WideItem(
                        title = channel.displayName,
                        imageUrl = channel.logo,
                        trailing = hiddenMark,
                        modifier = (if (index == jump.targetIndex) {
                            Modifier.focusRequester(jump.focusRequester)
                        } else {
                            Modifier
                        }).alpha(if (isHidden) 0.5f else 1f),
                        onClick = { vm.toggleHidden(channel) },
                    )
                }
            }
        }
        ChannelJumpBadge(jump.digits, Modifier.align(Alignment.TopEnd))
        }
    }
}
