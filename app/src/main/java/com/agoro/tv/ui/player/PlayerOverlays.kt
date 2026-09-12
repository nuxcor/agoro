@file:OptIn(androidx.tv.material3.ExperimentalTvMaterial3Api::class)

package com.agoro.tv.ui.player

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import com.agoro.tv.MainViewModel
import com.agoro.tv.data.EpgProgram
import com.agoro.tv.data.LiveChannel
import com.agoro.tv.player.PlaybackFault
import com.agoro.tv.player.PlayerEngine
import com.agoro.tv.player.Track
import com.agoro.tv.ui.components.focusTrap
import com.agoro.tv.ui.components.requestFocusRetrying
import com.agoro.tv.ui.theme.NuxColors
import com.agoro.tv.ui.theme.NuxFocus
import com.agoro.tv.ui.theme.NuxShape
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
internal fun PlayerBadge(text: String, color: Color) {
    Box(
        modifier = Modifier
            .clip(PlayerTheme.ChipShape)
            .background(NuxColors.Scrim)
            .padding(horizontal = 12.dp, vertical = 6.dp),
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelLarge,
            color = color,
            // A corner pill, not a paragraph: "Recording scheduled: <long
            // programme title>" wrapped into three lines over the picture.
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.widthIn(max = 420.dp),
        )
    }
}

/**
 * Channel-number entry readout: the digits collected so far, large, in a
 * scrim pill — sized to be read from the couch mid-type, where [PlayerBadge]'s
 * label type is annotation-sized. Never a focus target: digits arrive through
 * the scaffold's key routing, and the pill must not disturb whatever chrome
 * is up. [dim] is the "No channel 481" verdict — an answer, not an error.
 */
@Composable
internal fun DigitEntryPill(text: String, dim: Boolean = false) {
    Box(
        modifier = Modifier
            .clip(PlayerTheme.PillShape)
            .background(NuxColors.Scrim)
            .padding(horizontal = 22.dp, vertical = 10.dp),
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.SemiBold),
            color = if (dim) NuxColors.OnSurfaceDim else NuxColors.OnSurface,
            maxLines = 1,
        )
    }
}

@Composable
internal fun CatchupOverlay(
    vm: MainViewModel,
    channel: LiveChannel,
    onPlay: (EpgProgram, String) -> Unit,
) {
    val scope = rememberCoroutineScope()
    var programs by remember(channel.id) { mutableStateOf<List<EpgProgram>?>(null) }
    val listFocus = remember { FocusRequester() }

    LaunchedEffect(channel.id) {
        val now = System.currentTimeMillis()
        val oldest = now - channel.archiveDays * 24L * 3600 * 1000
        programs = vm.epgFor(channel)
            .filter { it.hasArchive && it.endMs < now && it.startMs > oldest }
            .sortedByDescending { it.startMs }
    }
    // Focus opens on the newest programme — "what did I just miss" is the
    // question this sheet answers. There is no Close button to fall back to
    // any more: BACK closes every panel in this player, the panel is trapped
    // so nothing else can take the key, and a row that duplicates a key the
    // remote already has is a row in front of the thing the viewer came for.
    // While the archive is still loading, or genuinely empty, nothing here is
    // focusable — which is correct, because there is nothing to move to.
    LaunchedEffect(programs) {
        if (!programs.isNullOrEmpty()) listFocus.requestFocusRetrying()
    }

    val dayFmt = remember { SimpleDateFormat("EEE d MMM", Locale.getDefault()) }
    val clockFmt = com.agoro.tv.ui.components.rememberClockFormat()

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(PlayerTheme.ScrimStrong)
            // Contained, not merely grouped — see Modifier.focusTrap.
            .focusTrap()
            .padding(horizontal = 64.dp, vertical = 40.dp)
    ) {
        Column {
            Text(
                text = "Catch-up — ${channel.displayName}",
                style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.SemiBold),
                color = NuxColors.OnSurface,
            )
            Text(
                text = "${channel.archiveDays} day archive",
                style = MaterialTheme.typography.labelMedium,
                color = NuxColors.OnSurfaceDim,
            )
            Spacer(Modifier.height(18.dp))
            when {
                programs == null -> CircularProgressIndicator(color = NuxColors.Primary)
                programs!!.isEmpty() -> Text(
                    "No archived programmes found for this channel.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = NuxColors.OnSurfaceDim,
                )
                else -> LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier
                        .weight(1f, fill = false)
                        .focusRequester(listFocus)
                        .focusRestorer(),
                ) {
                    items(programs!!, key = { it.id }) { program ->
                        Surface(
                            onClick = {
                                scope.launch {
                                    val url = vm.catchupUrl(channel, program)
                                    if (url != null) onPlay(program, url)
                                }
                            },
                            modifier = Modifier.fillMaxWidth(),
                            shape = ClickableSurfaceDefaults.shape(PlayerTheme.PanelShape),
                            colors = ClickableSurfaceDefaults.colors(
                                containerColor = PlayerTheme.RowFill,
                                focusedContainerColor = NuxFocus.container,
                                contentColor = NuxColors.OnSurface,
                                focusedContentColor = NuxColors.OnSurface,
                            ),
                            scale = ClickableSurfaceDefaults.scale(focusedScale = NuxFocus.RowScale),
                            border = ClickableSurfaceDefaults.border(focusedBorder = NuxFocus.ring12),
                        ) {
                            Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) {
                                Text(
                                    text = program.title,
                                    style = MaterialTheme.typography.titleSmall,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Text(
                                    // A time RANGE — "11:00 AM – 12:00 PM".
                                    // The old third segment printed the raw
                                    // duration ("1:00:00") after the dash,
                                    // which read as a nonsense end time.
                                    text = "${dayFmt.format(Date(program.startMs))} • " +
                                        "${clockFmt.format(Date(program.startMs))}" +
                                        " – ${clockFmt.format(Date(program.endMs))}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = NuxColors.OnSurfaceDim,
                                )
                                if (!program.description.isNullOrBlank()) {
                                    Text(
                                        text = program.description,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = NuxColors.OnSurfaceDim,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * Audio and subtitles: two columns, over the picture rather than instead of it.
 *
 * This used to be one left-aligned settings list behind a 96% scrim — video
 * quality, aspect, speed, sleep timer, audio, subtitles, in that order, under
 * a line reading "Now decoding 1080p FHD". That is a preferences screen
 * wearing a player's clothes: the thing being watched vanished while the
 * viewer changed the language of it, and five of the seven things on offer
 * had nothing to do with the two they had opened it for.
 *
 * Everything that is not a TRACK moved into the channel options list, where
 * the rest of "what this app can do to this stream" already lives. What is
 * left is the one question this panel is ever opened to answer, laid out the
 * way every streaming service lays it out: a column each, side by side, on a
 * panel that takes the right of the screen and leaves the picture running.
 *
 * No codecs, no bitrates, no sentence explaining what "auto" means. The
 * engine's own label for a soundtrack is what a viewer recognises it by, and
 * nothing else here is the app's to say.
 */
@Composable
internal fun TracksOverlay(
    engine: PlayerEngine,
    onAudioSelected: (Track) -> Unit,
    onSubtitleSelected: (Track?) -> Unit,
) {
    var audio by remember { mutableStateOf(engine.audioTracks()) }
    var text by remember { mutableStateOf(engine.textTracks()) }
    val firstFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) { firstFocus.requestFocusRetrying() }

    // Tracks appear a beat after the stream opens, so keep looking while the
    // sheet is up rather than settling on "no subtitles" forever.
    LaunchedEffect(engine) {
        repeat(20) {
            delay(500)
            audio = engine.audioTracks()
            text = engine.textTracks()
        }
    }

    fun refresh() {
        audio = engine.audioTracks()
        text = engine.textTracks()
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .width(PlayerTheme.TracksPanelWidth)
                .fillMaxHeight()
                // ScrimMedium on a panel, not ScrimStrong over the whole
                // screen: the picture is what the viewer is choosing a
                // soundtrack FOR, and blanking it to ask the question is the
                // reason this sheet felt like leaving the film.
                .background(PlayerTheme.ScrimMedium)
                // Contained, not merely grouped — see Modifier.focusTrap.
                .focusTrap()
                .padding(horizontal = 24.dp, vertical = 28.dp),
            horizontalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            TrackColumn(heading = "Audio", modifier = Modifier.weight(1f)) {
                if (audio.isEmpty()) {
                    item(key = "audio-none") { TrackNote("This stream has one soundtrack.") }
                } else {
                    itemsIndexed(audio, key = { _, track -> "a:${track.id}" }) { index, track ->
                        TrackRow(
                            track = track,
                            modifier = if (index == 0) Modifier.focusRequester(firstFocus)
                            else Modifier,
                        ) {
                            engine.selectAudioTrack(track.id)
                            onAudioSelected(track)
                            refresh()
                        }
                    }
                }
            }
            TrackColumn(heading = "Subtitles", modifier = Modifier.weight(1f)) {
                if (text.isEmpty()) {
                    item(key = "subs-none") { TrackNote("This stream has no subtitles.") }
                } else {
                    item(key = "subs-off") {
                        TrackRow(
                            track = Track("off", "Off", selected = text.none { it.selected }),
                            // The anchor when the stream carries one soundtrack
                            // and several subtitle tracks, which is most films:
                            // the audio column has nothing focusable in it, and
                            // an arrival request that lands nowhere leaves the
                            // panel deaf to everything but BACK.
                            modifier = if (audio.isEmpty()) Modifier.focusRequester(firstFocus)
                            else Modifier,
                        ) {
                            engine.selectTextTrack(null)
                            onSubtitleSelected(null)
                            refresh()
                        }
                    }
                    items(text, key = { "t:${it.id}" }) { track ->
                        TrackRow(track = track) {
                            engine.selectTextTrack(track.id)
                            onSubtitleSelected(track)
                            refresh()
                        }
                    }
                }
            }
        }
    }
}

/** One of the two columns: its heading, and its own scrolling list of rows. */
@Composable
private fun TrackColumn(
    heading: String,
    modifier: Modifier = Modifier,
    content: LazyListScope.() -> Unit,
) {
    Column(modifier = modifier) {
        Text(
            text = heading,
            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
            color = NuxColors.OnSurface,
            maxLines = 1,
        )
        Spacer(Modifier.height(10.dp))
        LazyColumn(
            verticalArrangement = Arrangement.spacedBy(6.dp),
            content = content,
        )
    }
}

/**
 * What a column says when it has nothing to offer — a fact about the stream,
 * not a row. Deliberately not focusable: a single dead option is worse than a
 * sentence, because the remote stops on it and OK does nothing.
 */
@Composable
private fun TrackNote(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        color = NuxColors.OnSurfaceDim,
    )
}

@Composable
private fun TrackRow(track: Track, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Surface(
        // Still focusable when unsupported so it can be read, but selecting it
        // does nothing — pinning a rung the decoder rejects blacks out video.
        onClick = { if (track.supported) onClick() },
        modifier = modifier.fillMaxWidth(),
        shape = ClickableSurfaceDefaults.shape(PlayerTheme.ChipShape),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = if (track.selected) PlayerTheme.SelectionTint
            else PlayerTheme.RowFill,
            focusedContainerColor = NuxFocus.container,
            contentColor = when {
                !track.supported -> NuxColors.OnSurfaceDim
                track.selected -> NuxColors.FocusBorder
                else -> NuxColors.OnSurface
            },
            focusedContentColor = if (track.supported) NuxColors.OnSurface else NuxColors.OnSurfaceDim,
        ),
        // Was inheriting tv-material3's 1.1 default and drawing no ring at all,
        // so focus here was a background shift of about five points of lightness
        // on a full-width row that also grew 10%. Selection is the gold tint;
        // focus is the ring.
        scale = ClickableSurfaceDefaults.scale(focusedScale = NuxFocus.RowScale),
        border = ClickableSurfaceDefaults.border(focusedBorder = NuxFocus.ring8),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
        ) {
            // An icon, not a tick typed into the string. Plenty of TV system
            // fonts have no glyph for U+2713 and draw a tofu box instead, and
            // the two spaces that stood in for it on every unselected row put
            // the labels of one list at two different left edges.
            if (track.selected) {
                Icon(
                    Icons.Default.Check,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
            } else {
                Spacer(Modifier.width(18.dp))
            }
            Text(
                text = track.label,
                style = MaterialTheme.typography.labelLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/**
 * Actionable failure state. A corner toast leaves the user staring at a black
 * screen with nothing to press.
 *
 * The card only — the scrim under it is the scaffold's [FadingScrim]. The
 * two used to be one full-screen box inside the scale-and-fade, which made
 * the fade a screen-sized offscreen layer; sized to the card, the layer is
 * a 640dp dialog.
 */
@Composable
internal fun PlaybackErrorCard(
    title: String,
    message: String,
    canRetryTolerant: Boolean,
    hasNext: Boolean,
    /** Names the Next button: a channel on live, an episode in a box set. */
    isLive: Boolean = true,
    /**
     * The stream ENDED rather than broke — see [PlaybackFault.ENDED]. Every
     * rung of the ladder has been spent on a feed that closed cleanly, which
     * is what a match or a programme reaching its end looks like from inside
     * the player. The card stops calling that a failure and leads with the
     * way out; Retry is still there for the feed that dropped mid-programme,
     * because from here the two are the same event.
     */
    ended: Boolean = false,
    onRetry: () -> Unit,
    onRetryTolerant: () -> Unit,
    onNext: () -> Unit,
    onBack: () -> Unit,
) {
    // Whichever button leads — Retry, or Back on a stream that ended.
    val primaryFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) { primaryFocus.requestFocusRetrying() }
    Column(
        modifier = Modifier
            .widthIn(max = 640.dp)
            .clip(NuxShape.Dialog)
            .background(NuxColors.Surface)
            // Contained, not merely grouped — see Modifier.focusTrap.
            .focusTrap()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            // A fixed headline, and the NAME on its own line underneath.
            // "Can't play {title}" was titleLarge with no line limit, so a
            // fixture — "Real Madrid vs Manchester City — UEFA Champions
            // League" — set three lines of headline type across the card
            // before the card had said anything. The sentence is the same
            // length whatever is playing now, and the thing that varies is
            // bounded.
            text = if (ended) "The stream ended" else "Can't play this",
            style = MaterialTheme.typography.titleLarge,
            color = NuxColors.OnSurface,
        )
        if (title.isNotBlank()) {
            Spacer(Modifier.height(6.dp))
            Text(
                text = title,
                style = MaterialTheme.typography.labelLarge,
                color = NuxColors.OnSurfaceDim,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            )
        }
        Spacer(Modifier.height(8.dp))
        Text(
            // Its own sentence rather than [plainLanguage]'s: this is the one
            // case where the engine's words ("the stream ended") are the
            // headline, and the body's job is to say what that USUALLY means
            // without claiming to know which it was.
            text = if (ended) {
                "A programme or a fixture that has finished looks exactly " +
                    "like this — retry if you think it should still be on."
            } else plainLanguage(message),
            style = MaterialTheme.typography.bodyMedium,
            color = NuxColors.OnSurfaceDim,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )
        Spacer(Modifier.height(24.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            // A stream that ended leads with Back, and Back holds the focus:
            // retrying a fixture that is over is the least likely thing the
            // viewer wants, and the focused button is the one their OK is
            // already pointing at.
            if (ended) {
                androidx.tv.material3.Button(
                    onClick = onBack,
                    modifier = Modifier.focusRequester(primaryFocus),
                ) { Text("Back") }
                androidx.tv.material3.OutlinedButton(onClick = onRetry) { Text("Retry") }
            } else {
                androidx.tv.material3.Button(
                    onClick = onRetry,
                    modifier = Modifier.focusRequester(primaryFocus),
                ) { Text("Retry") }
            }
            if (canRetryTolerant) {
                // What it means to the viewer, not what it does to the engine.
                // "Try software decoding" is an instruction to a program: it
                // asks someone holding a remote to have an opinion about
                // demuxers, and the honest summary of the button is that there
                // is one more thing the app can try.
                androidx.tv.material3.OutlinedButton(onClick = onRetryTolerant) {
                    Text("Try another way")
                }
            }
            if (hasNext) {
                androidx.tv.material3.OutlinedButton(onClick = onNext) {
                    Text(if (isLive) "Next channel" else "Next episode")
                }
            }
            if (!ended) {
                androidx.tv.material3.OutlinedButton(onClick = onBack) { Text("Back") }
            }
        }
    }
}

/** Turns engine error codes into something a viewer can act on. */
private fun plainLanguage(raw: String): String = when {
    raw.contains("403", true) || raw.contains("AUTHENTICATION", true) ->
        "The provider refused the connection. Your account may be at its connection limit, or the stream is no longer available."
    // No "refresh the playlist in Settings": there is no such action in
    // Settings, and a card that sends a viewer looking for a button that does
    // not exist is worse than one that simply says what happened.
    raw.contains("404", true) || raw.contains("NOT_FOUND", true) ->
        "The provider no longer has this stream."
    raw.contains("TIMEOUT", true) || raw.contains("UNSPECIFIED_IO", true) ->
        "The stream didn't respond. This is usually the provider or the network."
    raw.contains("DECODER", true) || raw.contains("DECODING", true) ->
        "This TV's hardware couldn't decode the stream."
    // Already a sentence from the engine's own rewrite; make sure it reads as
    // one. The engine writes its messages to be embedded mid-line, so they
    // arrive lowercase — "your provider no longer offers this stream." under a
    // headline is a fragment, not a sentence.
    else -> raw.trim().trimEnd('.').let {
        if (it.isEmpty()) "The stream stopped."
        else it.replaceFirstChar(Char::uppercaseChar) + "."
    }
}

/**
 * What is on after this one: in the corner while the episode runs out, and
 * the same card counting itself down once it has ended.
 *
 * It replaced two half-measures — a one-line text badge in the top-right
 * status stack, too small and too late to be an offer, and a centred panel
 * that arrived after the picture had already gone. This is the shape every
 * streaming service converged on, for the reason they converged on it: a
 * still of what is next is what a viewer recognises an episode by, and the
 * corner is the one place on a 16:9 frame that is reliably not the picture.
 *
 * The hierarchy is the episode's NAME first, because it is the only thing
 * here the viewer does not already know. The series and the address go under
 * it in the dim, and "UP NEXT" is a small tracked eyebrow above — a label,
 * not a headline. The reference this was drawn from leads on the series name
 * instead; that reads well on a phone, where you may not know what is
 * playing, and reads as a repetition on a television forty minutes into an
 * episode of it.
 *
 * ONE card in two states, because it is one moment. [secondsLeft] null is the
 * peek: the episode is still playing and this is an offer to leave it early —
 * OK takes the next one, BACK puts the card away. Non-null is the offer at the
 * end — the episode has finished, the count is running, OK takes it now and
 * BACK stays on the last frame.
 *
 * The pill is in BOTH states, and it names its own key. The peek used to
 * carry neither, on the reasoning that a filled pill on a card no key
 * activates is a control that lies — which was right, and the wrong half to
 * fix. A card in the corner where every service puts its next-episode button
 * IS read as a button; the viewer pressed OK at it and got a transport bar.
 * Now the key does what the card looks like it does, and the card says so.
 *
 * The key OK presses is INSIDE the pill and the key BACK presses is the dim
 * text beside it, because the two answers used to take a full-width pill and
 * a line of their own under it — a dialog's worth of chrome, on a card whose
 * whole content is a title and two keys, sat in the corner of something the
 * viewer is still watching. Same two answers, one line.
 *
 * Still not a menu. There are exactly two answers and the remote has a key
 * for each.
 */
@Composable
internal fun UpNextCard(
    /** The episode's own name — the headline. */
    heading: String,
    /** "S1 E2  ·  Lady in the Lake" — the address, under the name. */
    meta: String,
    /** The next episode's still, 16:9. Null draws the monogram. */
    artwork: String?,
    /**
     * Seconds until it starts by itself, or null while the current episode is
     * still playing — the difference between a notice and an offer.
     */
    secondsLeft: Int?,
    /** [secondsLeft] as 0..1 of the whole count, for the draining track. */
    countdownFraction: Float = 0f,
    modifier: Modifier = Modifier,
) {
    PlayerCornerCard(
        eyebrow = "UP NEXT",
        heading = heading,
        meta = meta,
        artwork = artwork,
        // "Watch now" against a finished episode; "Play next" against one that
        // is still running, where "now" would be asking the viewer what they
        // think they are doing. The key is in the label because the pill no
        // longer has a line under it to name one.
        action = if (secondsLeft != null) "Watch now" else "Play next",
        actionIcon = Icons.Default.PlayArrow,
        // Both keys, both states — this one is the important one on the peek,
        // where the card arrived uninvited and the viewer needs to know it
        // can be sent away.
        hint = if (secondsLeft != null) "BACK to stay" else "BACK to hide",
        secondsLeft = secondsLeft,
        countdownFraction = countdownFraction,
        modifier = modifier,
    )
}

/**
 * The end of the playlist, in the same corner and the same shape as the offer
 * that would have followed it: a series finale, a film, a catch-up recording.
 *
 * It exists because the player had no answer for this at all. An ended
 * ExoPlayer is not playing, not buffering and not tuning — the exact state a
 * PAUSED one reports — so what a viewer got at the end of a season was the
 * last frame of the credits under a pause glyph, indefinitely, with nothing
 * saying the thing had finished and no way on but BACK.
 *
 * So it names what finished, and counts down to leaving. OK goes now, BACK
 * stays on the frame — the same two answers, on the same two keys, as the
 * up-next offer, because from where the viewer sits it is the same moment
 * with nothing queued behind it.
 */
@Composable
internal fun FinishedCard(
    /** What finished — the episode's name, or the film's. */
    heading: String,
    /** The address under it: "S3 E10  ·  The Show". */
    meta: String,
    /** Its own still or poster, 16:9. Null draws the monogram. */
    artwork: String?,
    /** Where OK goes — "Back to the show" for an episode. */
    action: String,
    /** Seconds until the player closes itself. */
    secondsLeft: Int,
    /** [secondsLeft] as 0..1 of the whole count, for the draining track. */
    countdownFraction: Float,
    modifier: Modifier = Modifier,
) {
    PlayerCornerCard(
        eyebrow = "FINISHED",
        heading = heading,
        meta = meta,
        artwork = artwork,
        action = action,
        actionIcon = Icons.AutoMirrored.Filled.Undo,
        // Not "BACK to hide": the card is the only thing on a screen where
        // nothing is playing, and hiding it leaves the viewer exactly where
        // this whole card exists to stop them being left.
        hint = "BACK to stay",
        secondsLeft = secondsLeft,
        countdownFraction = countdownFraction,
        modifier = modifier,
    )
}

/**
 * The card both of the above are: still, eyebrow, name, address, a pill
 * naming its key, and a count draining along the foot.
 *
 * One drawing, because they are one component in two moments — an episode
 * ending into another, and a show ending into nothing. Two copies of this
 * would drift the first time either was touched.
 */
@Composable
private fun PlayerCornerCard(
    eyebrow: String,
    heading: String,
    meta: String,
    artwork: String?,
    action: String,
    /**
     * Drawn between "OK" and the label, in place of the ▶ and ↩ that used to
     * be typed into the string — TV system fonts routinely have no glyph for
     * either and drew a tofu box in the middle of the one control on the card.
     */
    actionIcon: ImageVector,
    hint: String,
    secondsLeft: Int?,
    countdownFraction: Float,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .width(UP_NEXT_CARD_WIDTH)
            // Shadow before the background, so it falls outside the shape
            // rather than under a transparent fill. The card sits on video of
            // no known brightness: the shadow separates it from a light frame
            // and the hairline from a dark one, and between them it never
            // dissolves into whatever is behind it.
            .shadow(18.dp, UpNextShape, clip = false)
            .clip(UpNextShape)
            .background(NuxColors.Surface.copy(alpha = 0.97f))
            .border(1.dp, Color.White.copy(alpha = 0.14f), UpNextShape),
    ) {
        Row(
            modifier = Modifier.padding(UpNextPad),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            com.agoro.tv.ui.components.Artwork(
                imageUrl = artwork,
                title = heading,
                modifier = Modifier
                    .width(UpNextStill)
                    .aspectRatio(16f / 9f)
                    .clip(PlayerTheme.ChipShape),
                background = NuxColors.SurfaceVariant,
            )
            Spacer(Modifier.width(UpNextGap))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = eyebrow,
                    style = MaterialTheme.typography.labelSmall.copy(
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 1.6.sp,
                    ),
                    color = NuxColors.Primary,
                )
                Spacer(Modifier.height(5.dp))
                Text(
                    text = heading,
                    style = MaterialTheme.typography.titleSmall.copy(
                        fontWeight = FontWeight.Bold,
                    ),
                    color = NuxColors.OnSurface,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = meta,
                    style = MaterialTheme.typography.labelMedium,
                    color = NuxColors.OnSurfaceDim,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }

        // The whole foot on one line: what OK does, what BACK does, and how
        // long either has. The pill is sized to its own label rather than to
        // the card, which is what makes room for the other two — a full-width
        // pill with a hint line under it and a count beside it stacked three
        // bands of chrome under a two-line title.
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = UpNextPad),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(
                modifier = Modifier
                    .clip(PlayerTheme.PillShape)
                    .background(NuxColors.Primary)
                    .padding(horizontal = 14.dp, vertical = 7.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(
                    text = "OK",
                    style = MaterialTheme.typography.labelMedium.copy(
                        fontWeight = FontWeight.Bold,
                    ),
                    color = NuxColors.OnAccent,
                    maxLines = 1,
                )
                Icon(
                    actionIcon,
                    contentDescription = null,
                    tint = NuxColors.OnAccent,
                    modifier = Modifier.size(18.dp),
                )
                Text(
                    text = action,
                    style = MaterialTheme.typography.labelMedium.copy(
                        fontWeight = FontWeight.Bold,
                    ),
                    color = NuxColors.OnAccent,
                    maxLines = 1,
                )
            }
            Spacer(Modifier.width(12.dp))
            Text(
                text = hint,
                style = MaterialTheme.typography.labelSmall,
                color = NuxColors.OnSurfaceDim,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                // Takes the slack so the seconds land on the trailing edge,
                // and gives it back by ellipsizing if a long action label
                // ever leaves it none.
                modifier = Modifier.weight(1f),
            )
            if (secondsLeft != null) {
                Spacer(Modifier.width(10.dp))
                Text(
                    // Seconds live OUTSIDE the pill. Inside, the number moves
                    // as it narrows from two digits to one and takes the
                    // label with it; a pill whose text shuffles once a second
                    // is the thing the eye watches instead of the title.
                    text = "${secondsLeft}s",
                    style = MaterialTheme.typography.labelMedium.copy(
                        fontWeight = FontWeight.Bold,
                    ),
                    color = NuxColors.OnSurfaceDim,
                    maxLines = 1,
                )
            }
        }
        if (secondsLeft != null) {
            Spacer(Modifier.height(8.dp))
            // The same count as the number, drawn rather than read. It drains
            // along the foot of the card, which is the one edge where a
            // moving element cannot land on anything.
            Box(
                modifier = Modifier
                    .padding(horizontal = UpNextPad)
                    .fillMaxWidth()
                    .height(3.dp)
                    .clip(NuxShape.Track)
                    .background(Color.White.copy(alpha = 0.15f)),
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxHeight()
                        .fillMaxWidth(countdownFraction.coerceIn(0f, 1f))
                        .background(NuxColors.Primary),
                )
            }
        }
        Spacer(Modifier.height(UpNextPad))
    }
}

/**
 * The card's corner, shared by its shadow, its fill and its hairline.
 *
 * [NuxShape.Dialog], not a radius of its own: this is the player's other
 * large surface, and the tracks sheet 240 lines up is already drawn on it.
 */
private val UpNextShape = NuxShape.Dialog

/** The card's one inset — its row, its key line, its track and its foot. */
private val UpNextPad = 14.dp

/**
 * The still, and the gap between it and the words.
 *
 * The still is the card's adjustable part. It shrank from 172dp, then from
 * 124dp, because at either size it was the tallest thing in the row and set
 * the card's height from a thumbnail rather than from the text — 96dp is
 * still a recognisable frame at ten feet, and the words now govern. Both are
 * on the 4dp scale.
 */
private val UpNextStill = 96.dp
private val UpNextGap = 12.dp

/**
 * The measured quantity, and the reason the card is the width it is.
 *
 * Two lines of a real episode title have to fit beside the still, and the
 * longest title this catalogue carries — "It has to do with the search for
 * the marvelous" — is what set the number: at a narrower column it ran out
 * of room mid-phrase. 224dp is the room it needs at titleSmall.
 *
 * This is stored rather than the card's total width because the total is the
 * derived thing. When the card came down from 448dp, and again from 392dp,
 * the cost was taken off the still and the padding, and had the width stayed
 * the literal it was, the two of them would have quietly eaten the room
 * measured here — which is a whole word at a wrap boundary, not a few dp of
 * slack. Trade the still and the gap freely; this constant is the one that
 * cannot move without measuring a title against it again, which is why a
 * card asked to get smaller gave up height and chrome instead.
 */
private val UpNextTextColumn = 224.dp

/** Derived — never tune this directly, tune [UpNextStill]. */
private val UP_NEXT_CARD_WIDTH =
    UpNextTextColumn + UpNextStill + UpNextGap + UpNextPad * 2
