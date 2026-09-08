@file:OptIn(androidx.tv.material3.ExperimentalTvMaterial3Api::class)

package com.agoro.tv.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import com.agoro.tv.data.SportsEvent
import com.agoro.tv.ui.theme.NuxColors
import com.agoro.tv.ui.theme.NuxFocus
import com.agoro.tv.ui.theme.NuxShape

/** The badges, big enough to read across a room and still leave a gap between. */
private val CREST_SIZE = 58.dp

/**
 * A live fixture as a 16:9 shelf card: the two club badges, the clubs, the
 * competition, and how far into the match you would be joining.
 *
 * It replaced [ChannelShelfCard] on the Home sport shelf, and the reason is
 * what that card was actually drawing. A PPV slot is a pipe — the same stream
 * id carries a different match tomorrow — so its logo is the PACK's, not the
 * match's: a square "Stan Sport" mark letterboxed into a 16:9 frame with grey
 * either side, repeated identically on the next card because the next match
 * is on the same pack. Three cards, one picture, none of them saying who is
 * playing. The slot's NAME was no better as a title: "AU (STAN 09) | Club
 * Brugge v Aston Villa UEFA Champi…" is marketing text that truncates before
 * it reaches the fixture, which then had to be repeated underneath.
 *
 * The badges come off the schedule fixture, so they are the two clubs and
 * nothing else — see SportsParser.applySchedule. Where the schedule could not
 * place a slot they are absent and [Artwork] draws the club's initials, which
 * is still the match rather than the pack.
 */
@Composable
fun SportShelfCard(
    event: SportsEvent,
    /** "FHD", "HD" — what the slot advertises. Null when it says nothing. */
    quality: String?,
    /** 0f..1f through the match, or null when no kick-off is trusted. */
    progress: Float?,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null,
    onFocus: () -> Unit = {},
    /** Applied to the clickable surface — the node that takes focus. */
    modifier: Modifier = Modifier,
) {
    // Caption outside the clickable surface: inside it the focus glow pools
    // behind the text and reads as a stain instead of a halo. Same rule as
    // [ChannelShelfCard], and the two sit in one row on Home.
    val focused = remember { mutableStateOf(false) }
    Column(modifier = Modifier.width(240.dp)) {
        Surface(
            onClick = onClick,
            modifier = modifier
                .dpadLongPress(onLongClick)
                .focusHalo(NuxShape.Card, focused)
                .onFocusChanged {
                    focused.value = it.isFocused
                    if (it.isFocused) onFocus()
                },
            shape = ClickableSurfaceDefaults.shape(NuxShape.Card),
            colors = ClickableSurfaceDefaults.colors(
                containerColor = Color.Transparent,
                focusedContainerColor = Color.Transparent,
                contentColor = NuxColors.OnSurface,
                focusedContentColor = NuxColors.OnSurface,
            ),
            scale = ClickableSurfaceDefaults.scale(focusedScale = NuxFocus.CardScale),
            border = ClickableSurfaceDefaults.border(focusedBorder = NuxFocus.ring16),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(16f / 9f)
                    .clip(NuxShape.Card)
                    .background(NuxColors.SurfaceVariant),
                contentAlignment = Alignment.Center,
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    Crest(event.homeCrest, event.home)
                    Text(
                        text = "v",
                        style = MaterialTheme.typography.labelMedium,
                        color = NuxColors.OnSurfaceDim,
                    )
                    Crest(event.awayCrest, event.away)
                }
                quality?.let { tier ->
                    Text(
                        text = tier,
                        style = MaterialTheme.typography.labelMedium,
                        color = NuxColors.OnSurfaceDim,
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(6.dp)
                            .clip(NuxShape.Chip)
                            .background(NuxColors.Scrim)
                            .padding(horizontal = 8.dp, vertical = 2.dp),
                    )
                }
                if (progress != null && progress > 0f) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.BottomStart)
                            .fillMaxWidth()
                            .height(4.dp)
                            .background(Color.White.copy(alpha = 0.25f)),
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxHeight()
                                .fillMaxWidth(progress)
                                .background(NuxColors.Primary),
                        )
                    }
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        // The fixture leads, because the fixture is what the card is. The
        // slot's own name never appears: it is the pipe's label, and a viewer
        // choosing a match does not choose a pipe.
        Text(
            text = event.title,
            style = MaterialTheme.typography.titleSmall,
            // Explicit: outside the Surface, so it inherits no content colour
            // and tv-material3's default is black.
            color = NuxColors.OnSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 2.dp),
        )
        Text(
            text = event.league.ifBlank { "Live" },
            style = MaterialTheme.typography.labelMedium,
            color = NuxColors.OnSurfaceDim,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 2.dp),
        )
    }
}

/**
 * One club badge. Transparent behind it — the card already supplies the
 * container, and [Artwork]'s own slab would draw a second box inside the
 * first. Fit, not Crop: a crest cropped to a square is a crest with its
 * edges cut off.
 */
@Composable
private fun Crest(url: String?, club: String) {
    Artwork(
        imageUrl = url,
        title = club,
        contentScale = ContentScale.Fit,
        modifier = Modifier.size(CREST_SIZE),
        monogramStyle = MaterialTheme.typography.titleMedium,
        background = Color.Transparent,
    )
}
