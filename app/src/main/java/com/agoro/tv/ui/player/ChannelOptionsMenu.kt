@file:OptIn(androidx.tv.material3.ExperimentalTvMaterial3Api::class)

package com.agoro.tv.ui.player

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AspectRatio
import androidx.compose.material.icons.filled.Bedtime
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.HighQuality
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material.icons.filled.Subtitles
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import com.agoro.tv.player.HIGHEST_QUALITY
import com.agoro.tv.player.Track
import com.agoro.tv.ui.components.focusTrap
import com.agoro.tv.ui.components.requestFocusRetrying
import com.agoro.tv.ui.theme.NuxColors
import com.agoro.tv.ui.theme.NuxFocus

/**
 * TiviMate-style options: everything you might do to *this* stream, on a
 * compact right-side panel summoned by MENU or a long press of OK. This is
 * what slimmed the transport bar down to transport + navigation — actions
 * moved here where they're named, not iconified.
 *
 * It is the player's ONE settings surface now. Picture quality, aspect, speed
 * and the sleep timer used to live on the tracks sheet as well, two of them in
 * a second interaction model (a row of chips there, a cycling row here), so
 * the same question had two answers depending on which panel the viewer had
 * found. One control, one place; the tracks sheet is audio and subtitles and
 * nothing else, and it is reached from the row here that names it.
 *
 * Films reach this panel too. The Options button and the long press used to
 * open the tracks sheet directly on VOD, which is why speed and sleep had to
 * be on it — the channel menu was unreachable from a film. They open this,
 * now, on both.
 */
@Composable
internal fun ChannelOptionsMenu(
    channelName: String,
    isLive: Boolean,
    isFavoritable: Boolean,
    isFavorite: Boolean,
    hasCatchup: Boolean,
    aspectLabel: String,
    sleepLabel: String,
    /** "1.5x" on a film, null on live, where speed is not a thing to change. */
    speedLabel: String?,
    canHide: Boolean,
    /**
     * The stream's video rungs, engine order. Empty where the stream offers no
     * choice, which is most of them — a single rung is not a decision.
     */
    videoRungs: List<Track>,
    /**
     * The rung on screen: null for adaptive, [HIGHEST_QUALITY] for the pinned
     * top, otherwise a rung's own id.
     */
    videoSelection: String?,
    /**
     * True where the stream has alternate soundtracks or any subtitles at all.
     * A row that opens a panel with nothing in it is a dead end with a focus
     * trap around it.
     */
    canChooseTracks: Boolean,
    /**
     * "2 of 6 · ESPN+ PPV 39", or null where this item has one source and
     * there is nothing to switch between.
     */
    feedLabel: String?,
    onFavoriteToggle: () -> Unit,
    onCatchup: () -> Unit,
    onTracks: () -> Unit,
    onVideoSelect: (String?) -> Unit,
    onAspectCycle: () -> Unit,
    onSpeedCycle: () -> Unit,
    onSleepCycle: () -> Unit,
    onNextFeed: () -> Unit,
    onHide: () -> Unit,
) {
    val firstFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) { firstFocus.requestFocusRetrying() }
    // One anchor, named once. The rule used to be spelled out at each row that
    // might be top — "focusRequester if the row above me is absent" — and a
    // fourth row that can lead is a fourth place for that to be got wrong.
    // "aspect" is the backstop because it is the only row that is always here.
    val firstRow = when {
        feedLabel != null -> "feed"
        isFavoritable -> "favorite"
        canChooseTracks -> "tracks"
        else -> "aspect"
    }
    fun anchor(key: String) =
        if (key == firstRow) Modifier.focusRequester(firstFocus) else Modifier

    Box(modifier = Modifier.fillMaxSize()) {
        Box(
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .width(PlayerTheme.CategoryWidth)
                .fillMaxHeight()
                .background(PlayerTheme.ScrimStrong)
                // The D-pad stays on the panel; see Modifier.focusTrap. LEFT
                // out of this column used to find the player's own root Box,
                // which is full-screen and focusable, and the Options layer
                // ignores nearly every key — so the menu sat there deaf.
                .focusTrap()
                .padding(start = 18.dp, end = 18.dp, top = 22.dp, bottom = 22.dp),
        ) {
            Column {
                Text(
                    text = channelName,
                    style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.SemiBold),
                    color = NuxColors.OnSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = if (isLive) "Channel options" else "Playback options",
                    style = MaterialTheme.typography.labelMedium,
                    color = NuxColors.OnSurfaceDim,
                )
                Spacer(Modifier.height(12.dp))
                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                    modifier = Modifier.fillMaxHeight(),
                ) {
                    // First, where it exists. A viewer opens this menu during a
                    // fixture for one reason — the picture is not the match
                    // they pressed — and that is the row that answers it.
                    if (feedLabel != null) {
                        item(key = "feed") {
                            OptionRow(
                                icon = Icons.Default.SwapHoriz,
                                label = "Try another feed",
                                value = feedLabel,
                                onClick = onNextFeed,
                                modifier = anchor("feed"),
                            )
                        }
                    }
                    if (isFavoritable) {
                        item(key = "favorite") {
                            OptionRow(
                                icon = if (isFavorite) Icons.Default.Star else Icons.Default.StarBorder,
                                label = if (isFavorite) "Remove favorite" else "Favorite",
                                iconTint = if (isFavorite) NuxColors.Primary else NuxColors.OnSurface,
                                onClick = onFavoriteToggle,
                                modifier = anchor("favorite"),
                            )
                        }
                    }
                    if (hasCatchup) {
                        item(key = "catchup") {
                            OptionRow(
                                icon = Icons.Default.History,
                                label = "Catch-up",
                                onClick = onCatchup,
                            )
                        }
                    }
                    if (canChooseTracks) {
                        item(key = "tracks") {
                            OptionRow(
                                icon = Icons.Default.Subtitles,
                                // What is behind it, said plainly. "Playback
                                // options" was the name of a sheet that held
                                // six unrelated things; it holds two now, and
                                // they are both in the label.
                                label = "Audio and subtitles",
                                onClick = onTracks,
                                modifier = anchor("tracks"),
                            )
                        }
                    }
                    item(key = "aspect") {
                        OptionRow(
                            icon = Icons.Default.AspectRatio,
                            label = "Aspect ratio",
                            value = aspectLabel,
                            onClick = onAspectCycle,
                            modifier = anchor("aspect"),
                        )
                    }
                    if (speedLabel != null) {
                        item(key = "speed") {
                            OptionRow(
                                icon = Icons.Default.Speed,
                                label = "Speed",
                                value = speedLabel,
                                onClick = onSpeedCycle,
                            )
                        }
                    }
                    item(key = "sleep") {
                        OptionRow(
                            icon = Icons.Default.Bedtime,
                            label = "Sleep timer",
                            value = sleepLabel,
                            onClick = onSleepCycle,
                        )
                    }
                    // Last of the settings, because it is the one a viewer
                    // opens this menu for least often and the one with the
                    // most rows. Rungs are LISTED rather than cycled: an
                    // unsupported rendition has to be visible to explain why a
                    // channel sold as UHD looks soft, and a cycling row can
                    // only ever show the one it landed on.
                    if (videoRungs.isNotEmpty()) {
                        item(key = "quality-header") {
                            Spacer(Modifier.height(8.dp))
                            Text(
                                text = "Picture quality",
                                style = MaterialTheme.typography.labelMedium,
                                color = NuxColors.OnSurfaceDim,
                                modifier = Modifier.padding(start = 12.dp, bottom = 4.dp),
                            )
                        }
                        item(key = "quality-auto") {
                            OptionRow(
                                icon = Icons.Default.HighQuality,
                                // No "— adapt to bandwidth" after it. Auto is
                                // a word every streaming app uses for exactly
                                // this and nobody has ever needed the footnote.
                                label = "Auto",
                                selected = videoSelection == null,
                                onClick = { onVideoSelect(null) },
                            )
                        }
                        item(key = "quality-highest") {
                            OptionRow(
                                icon = Icons.Default.HighQuality,
                                label = "Highest available",
                                selected = videoSelection == HIGHEST_QUALITY,
                                onClick = { onVideoSelect(HIGHEST_QUALITY) },
                            )
                        }
                        items(videoRungs.size, key = { "v:${videoRungs[it].id}" }) { index ->
                            val rung = videoRungs[index]
                            OptionRow(
                                icon = Icons.Default.HighQuality,
                                // "1080p", and nothing else. The engine's own
                                // label is "1080p FHD  H264  — this TV can't
                                // decode it": a tier the number already says, a
                                // codec name, and a sentence of explanation, on
                                // a row whose whole job is to be one of several
                                // numbers to compare. Dim is how a rung says it
                                // cannot be had.
                                label = rungLabel(rung.label),
                                selected = rung.selected,
                                enabled = rung.supported,
                                // Still focusable when it can't be played, so
                                // it can be read; selecting it does nothing,
                                // because pinning a rung the decoder rejects
                                // blacks the picture out.
                                onClick = { if (rung.supported) onVideoSelect(rung.id) },
                            )
                        }
                    }
                    if (canHide) {
                        item(key = "hide") {
                            Spacer(Modifier.height(8.dp))
                            OptionRow(
                                icon = Icons.Default.VisibilityOff,
                                label = "Hide this channel",
                                onClick = onHide,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun OptionRow(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    iconTint: androidx.compose.ui.graphics.Color = NuxColors.OnSurface,
    value: String? = null,
    /** A row that is one of a set, and is the one in force. */
    selected: Boolean = false,
    /** False where the stream offers this but the box cannot play it. */
    enabled: Boolean = true,
) {
    Surface(
        onClick = onClick,
        modifier = modifier.fillMaxWidth(),
        shape = ClickableSurfaceDefaults.shape(PlayerTheme.ChipShape),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = if (selected) PlayerTheme.SelectionTint else PlayerTheme.RowFill,
            focusedContainerColor = NuxFocus.container,
            contentColor = when {
                !enabled -> NuxColors.OnSurfaceDim
                selected -> NuxColors.FocusBorder
                else -> NuxColors.OnSurface
            },
            focusedContentColor = if (enabled) NuxColors.OnSurface else NuxColors.OnSurfaceDim,
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = NuxFocus.RowScale),
        border = ClickableSurfaceDefaults.border(focusedBorder = NuxFocus.ring8),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
        ) {
            // The tick REPLACES the row's own icon rather than joining it: a
            // set of quality rungs all carrying the same glyph tells the eye
            // nothing, and the one that changes is the one worth drawing.
            if (selected) {
                Icon(
                    Icons.Default.Check,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
            } else if (icon != null) {
                Icon(icon, contentDescription = null, tint = iconTint, modifier = Modifier.size(18.dp))
            }
            Text(
                text = label,
                style = MaterialTheme.typography.labelLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            if (value != null) {
                Text(
                    text = value,
                    style = MaterialTheme.typography.labelMedium,
                    color = NuxColors.Primary,
                    maxLines = 1,
                )
            }
        }
    }
}

/**
 * "1080p" out of the engine's "1080p FHD  H264  — this TV can't decode it".
 *
 * [com.agoro.tv.player.qualityLabel] builds that string for two readers and
 * the other one wants the detail; the rung list wants a number it can be
 * compared with, which is the first token and never anything after it.
 */
private fun rungLabel(raw: String): String =
    raw.trim().substringBefore(' ').ifBlank { raw.trim() }
