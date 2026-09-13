package com.agoro.tv.ui.components

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.agoro.tv.R
import com.agoro.tv.ui.theme.NuxColors

/**
 * The app's one way of saying "not yet".
 *
 * There were two before, and neither was the app's: a stock
 * [androidx.compose.material3.CircularProgressIndicator] under the word
 * "Loading…" on every pane that waited, and the player's own sweep under a
 * channel name. A Material spinner is the most anonymous object a TV app can
 * put on screen — it belongs to the toolkit, not to this product — and the
 * word beside it only ever restated what the spinner already said.
 *
 * So: the mark breathes, a light runs the line beneath it, and nothing is
 * written. Identity plus motion. The same gesture in the player and on every
 * pane, because from the couch they are one event: the picture is not here
 * yet.
 */
@Composable
fun BrandLoader(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        androidx.compose.foundation.Image(
            painter = painterResource(R.drawable.ic_logo),
            contentDescription = null,
            contentScale = ContentScale.Fit,
            // Sized by HEIGHT: ic_logo is cropped to its own ink and carries
            // its 55:76 aspect in the viewport, so pinning the width instead
            // would squash the mark on any box whose density rounds badly.
            modifier = Modifier
                .height(56.dp)
                .breathe(),
        )
        SweepTrack()
    }
}

/**
 * The waiting pulse: a slow swell in size and light, reversing.
 *
 * Reverse rather than restart, and slow enough to read as breathing rather
 * than blinking — a mark that snaps back to its small state every cycle reads
 * as a fault indicator. The range is deliberately narrow: this runs under
 * content a viewer is waiting on, and a logo pumping between half and double
 * size is an animation about itself.
 *
 * Everything is read INSIDE [graphicsLayer], so a frame of this costs a draw
 * and nothing else. Read as a `scale()` or `alpha()` parameter instead, the
 * animated value lands in composition and every frame recomposes, re-measures
 * and re-lays-out whatever is around it — on a 2GB box, while a stream is
 * being opened, that is the worst possible moment to be doing layout.
 */
@Composable
fun Modifier.breathe(): Modifier {
    val motion = rememberInfiniteTransition(label = "breathe")
    val pulse by motion.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            tween(1_300, easing = FastOutSlowInEasing),
            RepeatMode.Reverse,
        ),
        label = "pulse",
    )
    return graphicsLayer {
        val s = 0.94f + 0.08f * pulse
        scaleX = s
        scaleY = s
        alpha = 0.62f + 0.38f * pulse
    }
}

/**
 * A light travelling a thin line, left to right, restarting.
 *
 * Lives here rather than in the player because it is no longer the player's:
 * it runs under the brand mark on every waiting pane, under the channel logo
 * on a tune, and on its own — no mark, no words, nothing at all — in the
 * middle of the screen when a stream stalls long enough to be worth
 * answering.
 *
 * A single direction, not a bounce: a scanner reads as retro, one direction
 * reads as progress. The glow starts fully off the left edge and exits fully
 * right, so the loop point is invisible.
 */
@Composable
fun SweepTrack(
    modifier: Modifier = Modifier,
    /**
     * The track's own width, because the glow's travel is computed from it —
     * a caller that tried to narrow the track with `Modifier.width()` would
     * find the inner width winning and the glow still travelling the full
     * distance, sliding past the edge it was supposed to stop at.
     */
    width: Dp = 200.dp,
) {
    val motion = rememberInfiniteTransition(label = "sweepTrack")
    val sweep by motion.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            tween(1_100, easing = FastOutSlowInEasing),
            RepeatMode.Restart,
        ),
        label = "sweep",
    )
    val trackWidth = width
    // Proportional, so a short track gets a short glow rather than one wider
    // than the line it runs along.
    val glowWidth = trackWidth * 0.36f
    Box(
        modifier = modifier
            .width(trackWidth)
            .height(3.dp)
            .clip(RoundedCornerShape(2.dp))
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
                    Brush.horizontalGradient(
                        listOf(Color.Transparent, NuxColors.Primary, Color.Transparent)
                    )
                ),
        )
    }
}
