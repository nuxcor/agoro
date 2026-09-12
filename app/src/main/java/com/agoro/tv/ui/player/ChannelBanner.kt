@file:OptIn(androidx.tv.material3.ExperimentalTvMaterial3Api::class)

package com.agoro.tv.ui.player

import androidx.compose.animation.core.animateFloat
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Star
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.agoro.tv.MainViewModel
import com.agoro.tv.data.LiveChannel
import com.agoro.tv.data.PlayableItem
import com.agoro.tv.ui.theme.NuxColors
import com.agoro.tv.ui.theme.NuxShape
import java.util.Date
import kotlinx.coroutines.delay
import com.agoro.tv.data.isFavorite

/**
 * TiviMate-style channel banner on the bottom gradient: logo on the left,
 * number and name / now-programme / progress / next in the middle, clock and
 * status chips on the right. Shown on every channel change so zapping is
 * never blind. The gradient itself belongs to the scaffold's bottom column,
 * which stacks this above the transport bar — their spacing is layout, not a
 * hardcoded lift.
 */
@Composable
internal fun ChannelBanner(
    vm: MainViewModel,
    item: PlayableItem?,
    channel: LiveChannel?,
    isLive: Boolean,
    showKeyHints: Boolean = false,
    /**
     * True while a zap chain is still running. The logo slot stays, empty,
     * so the banner doesn't reflow — but no image is asked for: a run
     * through twenty channels used to start twenty Coil requests, one per
     * channel skimmed, for logos that were on screen for a tenth of a second.
     */
    logoDeferred: Boolean = false,
    /**
     * "2 of 6 · ESPN+ PPV 39" while a fixture is playing on one of several
     * feeds, null otherwise.
     *
     * A fixture's sources are separate pipes and one of them being the wrong
     * match is routine, so the banner has to say which is up — and the heading
     * has to be the MATCH, not whichever channel is carrying it, or a viewer
     * who steps to the next feed watches the name of the thing they pressed
     * disappear.
     */
    feedLabel: String? = null,
) {
    val nowNextMap by vm.nowNext.collectAsState()
    val favorites by vm.favorites.collectAsState()
    var nowMs by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(30_000)
            nowMs = System.currentTimeMillis()
        }
    }
    val nowNext = channel?.id?.let { nowNextMap[it] }
    val timeFmt = com.agoro.tv.ui.components.rememberClockFormat()

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 40.dp, end = 40.dp, top = 24.dp, bottom = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        // --- left: logo card ----------------------------------------------
        if (isLive && channel != null) {
            Box(
                modifier = Modifier
                    .clip(PlayerTheme.ChipShape)
                    .background(PlayerTheme.RowFill)
                    .padding(6.dp),
            ) {
                // Fit, not the Crop default: channel logos are arbitrary aspect
                // ratios and Crop fills the box by slicing the sides off — a
                // wide wordmark came out reading "CTRUM EWS". Crop is right for
                // posters, which is why it is the default, but never for logos.
                if (logoDeferred) {
                    Spacer(Modifier.size(width = 78.dp, height = 46.dp))
                } else {
                    com.agoro.tv.ui.components.Artwork(
                        imageUrl = channel.logo,
                        title = channel.displayName,
                        modifier = Modifier.size(width = 78.dp, height = 46.dp),
                        contentScale = androidx.compose.ui.layout.ContentScale.Fit,
                    )
                }
            }
        }

        // --- middle: name, now, progress, next ---------------------------
        Column(modifier = Modifier.weight(1f)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                // The number the digit keys tune by, beside the name it means —
                // dim, because the name is what the viewer is reading.
                channel?.number?.let { number ->
                    Text(
                        text = number.toString(),
                        style = MaterialTheme.typography.titleMedium,
                        color = NuxColors.OnSurfaceDim,
                        maxLines = 1,
                    )
                }
                Text(
                    text = if (feedLabel != null) {
                        item?.title?.takeIf { it.isNotBlank() } ?: channel?.displayName.orEmpty()
                    } else {
                        channel?.displayName ?: item?.title.orEmpty()
                    },
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                    color = NuxColors.OnSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (channel != null && channel.isFavorite(favorites)) {
                    // An icon, not a ★ typed into a Text: TV system fonts
                    // regularly have no glyph for it and draw a tofu box
                    // beside the channel name. Components.kt made the same
                    // move for the rating star.
                    Icon(
                        Icons.Default.Star,
                        contentDescription = null,
                        tint = NuxColors.Primary,
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
            val current = nowNext?.now
            if (current != null) {
                Spacer(Modifier.height(4.dp))
                Text(
                    text = "${timeFmt.format(Date(current.startMs))} – " +
                        "${timeFmt.format(Date(current.endMs))}   ${current.title}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = NuxColors.OnSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(6.dp))
                val progress = ((nowMs - current.startMs).toFloat() /
                    (current.endMs - current.startMs).coerceAtLeast(1)).coerceIn(0f, 1f)
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(3.dp)
                        .clip(NuxShape.Track)
                        .background(PlayerTheme.TrackBackground)
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxHeight()
                            .fillMaxWidth(progress)
                            .background(NuxColors.Primary)
                    )
                }
            } else if (!item?.subtitle.isNullOrBlank()) {
                Spacer(Modifier.height(4.dp))
                Text(
                    text = item?.subtitle.orEmpty(),
                    style = MaterialTheme.typography.bodyMedium,
                    color = NuxColors.OnSurfaceDim,
                    maxLines = 1,
                )
            }
            if (feedLabel != null) {
                Spacer(Modifier.height(4.dp))
                Text(
                    // The switcher that changes it lives in the options menu,
                    // and it is not a thing anyone would think to look for
                    // there, so the line that raises the question also says
                    // where the answer is — on the key that actually opens it.
                    // A tap of OK opens the channel list (see
                    // playerKeyAction); it is the HOLD that reaches the
                    // options, and a banner that teaches the wrong key sends
                    // the viewer somewhere else every time they believe it.
                    //
                    // No "1080p50" after it either. What the stream turned out
                    // to be is a badge, and the badges live in one place — the
                    // end of the transport row, see [StreamBadges] — because a
                    // banner is up on every zap and a resolution in the corner
                    // of every channel is a readout nobody asked for.
                    text = "Feed $feedLabel  ·  Hold OK to try another",
                    style = MaterialTheme.typography.labelMedium,
                    color = NuxColors.OnSurfaceDim,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            nowNext?.next?.let { next ->
                Spacer(Modifier.height(4.dp))
                Text(
                    text = "Next  ${timeFmt.format(Date(next.startMs))}  ${next.title}",
                    style = MaterialTheme.typography.labelMedium,
                    color = NuxColors.OnSurfaceDim,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (showKeyHints) {
                Spacer(Modifier.height(8.dp))
                // The map the player actually has. It used to read "OK
                // Options · ◀ Channels", and OK has opened the CHANNEL LIST
                // since the day the select key was given to browsing — the
                // options are the hold, or MENU (see playerKeyAction). A hint
                // line is the one piece of copy a viewer takes literally, so
                // being wrong in it costs more than not having it; when it
                // changed, KEY_HINTS_VERSION went up so the people who had
                // already learned the wrong thing are taught the right one.
                //
                // The arrows are icons. ▲▼ rendered as a pair of tofu boxes
                // on exactly the televisions this line exists for, and the
                // left arrow was pointing at a key that is no longer the
                // interesting one.
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    KeyHint("OK Channels")
                    KeyHintDot()
                    KeyHint("Hold OK Options")
                    KeyHintDot()
                    Icon(
                        Icons.Default.KeyboardArrowUp,
                        contentDescription = null,
                        tint = NuxColors.OnSurfaceDim,
                        modifier = Modifier.size(18.dp),
                    )
                    Icon(
                        Icons.Default.KeyboardArrowDown,
                        contentDescription = null,
                        tint = NuxColors.OnSurfaceDim,
                        modifier = Modifier.size(18.dp),
                    )
                    KeyHint("Change channel")
                    KeyHintDot()
                    KeyHint("INFO More")
                }
            }
        }

        // --- right: the clock --------------------------------------------
        // The stream badges stood under it — what is actually being decoded,
        // rather than what the stream name advertises. The banner is up on
        // every zap, though, so on a walk down a category they flickered past
        // in the corner of every channel; asked for out of the picture, they
        // now sit at the end of the transport row with the buttons. See
        // [StreamBadges]. (No engine-name chip here either: which decoder is
        // playing is diagnostics, and lives in the options sheet.)
        Text(
            text = timeFmt.format(Date(nowMs)),
            style = MaterialTheme.typography.labelLarge,
            color = NuxColors.OnSurface,
            maxLines = 1,
        )
    }
}

/** One phrase of the banner's key hints. */
@Composable
private fun KeyHint(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall,
        color = NuxColors.OnSurfaceDim,
        maxLines = 1,
    )
}

/** The separator between them, so the spacing is the Row's and not a string's. */
@Composable
private fun KeyHintDot() {
    Text(
        text = "·",
        style = MaterialTheme.typography.labelSmall,
        color = NuxColors.OnSurfaceDim,
    )
}

/**
 * The intentional "connecting" screen every tune opens on, so opening a
 * stream is never a flat black void waiting for the first frame — a fresh
 * open from a poster or a fixture, a pick from the channel-list panel, a zap.
 * A soft brand-gold glow breathing over the dark video canvas, under the
 * [TuneCard]'s name and sweep. The player clears its surface to a black
 * shutter on every re-tune, so there is never a live frame beneath this to
 * hide.
 *
 * The breath is read inside graphicsLayer, so each frame is a draw and
 * nothing recomposes; the glow is one radial brush drawn once.
 */
@Composable
internal fun TuningBackdrop(modifier: Modifier = Modifier) {
    val motion = androidx.compose.animation.core.rememberInfiniteTransition(label = "tuneBg")
    val breath by motion.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = androidx.compose.animation.core.infiniteRepeatable(
            androidx.compose.animation.core.tween(
                2_600,
                easing = androidx.compose.animation.core.FastOutSlowInEasing,
            ),
            androidx.compose.animation.core.RepeatMode.Reverse,
        ),
        label = "breath",
    )
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(PlayerTheme.VideoCanvas),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                // Breathe the glow's size and strength together, about the
                // centre, so it reads as a slow pulse rather than a flicker.
                .graphicsLayer {
                    val scale = 0.92f + 0.16f * breath
                    scaleX = scale
                    scaleY = scale
                    alpha = 0.55f + 0.45f * breath
                }
                .background(
                    androidx.compose.ui.graphics.Brush.radialGradient(
                        colors = listOf(
                            NuxColors.Primary.copy(alpha = 0.22f),
                            NuxColors.PrimaryDim.copy(alpha = 0.10f),
                            androidx.compose.ui.graphics.Color.Transparent,
                        ),
                    )
                ),
        )
    }
}

/**
 * What tuning looks like: the channel's name breathing over the dimmed last
 * frame, with a light sweeping a thin line beneath it — identity plus motion,
 * no card, no logo tile, no spinner. The boxy scrim card this replaces put a
 * letterboxed logo and a stock spinner in the middle of every channel change,
 * which read as chrome interrupting the picture rather than the picture
 * changing. Shown from the moment a tune is requested until the new stream
 * renders; mid-stream stalls get only a corner chip.
 */
@Composable
internal fun TuneCard(
    channel: LiveChannel?,
    item: PlayableItem?,
    modifier: Modifier = Modifier,
    /** Why this is taking a moment, when it is more than an ordinary tune. */
    note: String? = null,
) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = channel?.displayName ?: item?.title.orEmpty(),
            style = MaterialTheme.typography.headlineMedium.copy(
                fontWeight = FontWeight.SemiBold,
                // Legibility without a scrim card: the text floats over
                // whatever frame the zap left behind. A hard offset shadow,
                // not a blur: the blurred one was re-rasterised on every
                // frame of the sweep below, for the whole of every tune.
                shadow = androidx.compose.ui.graphics.Shadow(
                    color = androidx.compose.ui.graphics.Color.Black.copy(alpha = 0.7f),
                    offset = androidx.compose.ui.geometry.Offset(0f, 2f),
                ),
            ),
            color = NuxColors.OnSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.widthIn(max = 720.dp),
        )
        if (note != null) {
            Spacer(Modifier.height(8.dp))
            Text(
                text = note,
                style = MaterialTheme.typography.bodyMedium,
                color = NuxColors.OnSurfaceDim,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.widthIn(max = 720.dp),
            )
        }
        Spacer(Modifier.height(14.dp))
        SweepTrack()
    }
}

/**
 * The player's one indeterminate indicator: a light travelling a thin line,
 * left to right, restarting.
 *
 * It is the motion under the tune card's channel name, and on its own — with
 * no card, no words, nothing else at all — it is what a stall that has lasted
 * long enough to be worth answering shows in the middle of the screen. The
 * same gesture in both places on purpose: from the couch they are one event,
 * the picture is not here yet, and a player with two different waiting
 * animations is two different apps.
 *
 * A single direction, not a bounce: a scanner reads as retro, one direction
 * reads as progress. The glow starts fully off the left edge and exits fully
 * right, so the loop point is invisible.
 */
@Composable
internal fun SweepTrack(modifier: Modifier = Modifier) {
    val motion = androidx.compose.animation.core.rememberInfiniteTransition(label = "sweepTrack")
    val sweep by motion.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = androidx.compose.animation.core.infiniteRepeatable(
            androidx.compose.animation.core.tween(
                1_100,
                easing = androidx.compose.animation.core.FastOutSlowInEasing,
            ),
            androidx.compose.animation.core.RepeatMode.Restart,
        ),
        label = "sweep",
    )
    val trackWidth = 200.dp
    val glowWidth = 72.dp
    Box(
        modifier = modifier
            .width(trackWidth)
            .height(3.dp)
            .clip(androidx.compose.foundation.shape.RoundedCornerShape(2.dp))
            .background(NuxColors.OnSurface.copy(alpha = 0.16f)),
    ) {
        Box(
            modifier = Modifier
                // Read inside graphicsLayer, so each frame of the sweep is a
                // draw and nothing more — as a Modifier.offset(x = …)
                // parameter the animated value was read in composition, and
                // every frame recomposed, re-measured and re-laid-out the card.
                .graphicsLayer {
                    translationX = (trackWidth + glowWidth).toPx() * sweep - glowWidth.toPx()
                }
                .width(glowWidth)
                .fillMaxHeight()
                .background(
                    androidx.compose.ui.graphics.Brush.horizontalGradient(
                        listOf(
                            androidx.compose.ui.graphics.Color.Transparent,
                            NuxColors.Primary,
                            androidx.compose.ui.graphics.Color.Transparent,
                        )
                    )
                ),
        )
    }
}
