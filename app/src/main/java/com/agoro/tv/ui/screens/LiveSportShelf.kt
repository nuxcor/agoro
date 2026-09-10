package com.agoro.tv.ui.screens

import com.agoro.tv.data.EpgProgram
import com.agoro.tv.data.LiveChannel
import com.agoro.tv.data.SportsEvent
import com.agoro.tv.data.SportsParser

/**
 * A fixture that has kicked off, paired with the slot carrying it.
 *
 * The pair rather than the event alone because the card is a channel card:
 * the slot brings the logo, the quality badge and the long-press menu, and the
 * fixture brings the only thing the slot cannot say — who is playing.
 */
internal class LiveFixture(
    val event: SportsEvent,
    val slot: LiveChannel,
) {
    /**
     * The fixture as the card's "now playing".
     *
     * A synthesised programme rather than a new card shape: the shelf card
     * already draws a title and a progress bar from one of these, and a match
     * IS what is on that slot right now. The two-hour window is a guess and is
     * only ever used to fill the bar — long enough that a football match does
     * not read as finished at the whistle, short enough that the bar still
     * moves. A fixture with no trusted kick-off gets no bar at all rather than
     * a made-up one.
     */
    /**
     * How far through the match, for the card's bar. Null when no kick-off is
     * trusted — a made-up bar is worse than none, because the bar is the one
     * thing on the card that claims to be measured.
     */
    fun progress(nowMs: Long): Float? {
        val start = event.startMs ?: return null
        return ((nowMs - start).toFloat() / FIXTURE_WINDOW_MS).coerceIn(0f, 1f)
    }

    fun asProgram(): EpgProgram? {
        val start = event.startMs ?: return null
        return EpgProgram(
            id = "fixture:${event.streamId}",
            title = event.title,
            description = event.league.takeIf { it.isNotBlank() },
            startMs = start,
            endMs = start + FIXTURE_WINDOW_MS,
            hasArchive = false,
        )
    }
}

/** How long a fixture is assumed to run, for the progress bar only. */
private const val FIXTURE_WINDOW_MS = 2 * 60 * 60 * 1000L

/** At most this many cards; a shelf is a glance, not a schedule. */
private const val SHELF_LIMIT = 20

/**
 * The Home shelf's contents: fixtures under way, each with the slot that
 * carries it.
 *
 * [fixtures] is what the Sport tab is listing — SportsParser.upcoming's rows,
 * not the raw parse — so this can only ever be a SUBSET of that tab. It used
 * to be handed the parse instead, and the two screens then disagreed on
 * everything the tab's pipeline decides and this one does not: which slot of
 * six carries a match, whose clock to believe when the packs disagree, and
 * whether a slot claiming "LIVE" with no kick-off is still worth believing.
 * A fixture on Home that is not on the Sport tab is a bug however it is
 * reached, and the cheapest way not to reach it is not to have a second
 * pipeline.
 *
 * Pure and separate from the composable because every rule in it is a
 * judgement that can be wrong in a way no screenshot would show — a shelf of
 * the same match six times, a card whose slot is not in this bundle and plays
 * nothing, a fixture that has not started. The Sport tab's own pipeline is
 * hard to stand up off a real provider, so this is the half that CAN be
 * tested, and it is the half with the decisions in it.
 *
 * What it does NOT re-litigate is the window: [SportsParser.upcoming] has
 * already dropped everything more than a match-length past kick-off, whatever
 * ESPN's state field says, so a long game — an NFL overtime, a cup tie that
 * goes to penalties — leaves this shelf at the three-hour mark exactly as it
 * leaves the Sport tab. Agreeing with the tab is worth more than being right
 * about the tail of a long game, and showing a match that finished at
 * lunchtime is the fault that was actually reported.
 */
internal fun liveSportShelf(
    fixtures: List<SportsEvent>?,
    events: List<LiveChannel>,
    nowMs: Long,
): List<LiveFixture> {
    if (fixtures.isNullOrEmpty() || events.isEmpty()) return emptyList()
    val slots = events.associateBy { it.xtreamId }
    return fixtures.asSequence()
        // isOnNow, not isLive: isLive has no upper bound, so a match that
        // kicked off stays 'live' until the schedule forgets it. That is a
        // mis-styled badge on the Sport tab and a finished match sitting on
        // this shelf for hours.
        .filter { it.isOnNow(nowMs) }
        // The slot is what actually plays, so a fixture whose slot is not in
        // this bundle has nothing behind it and must not draw a card.
        .mapNotNull { event -> slots[event.streamId]?.let { LiveFixture(event, it) } }
        // One card per slot: packs list the same match on several feeds, and a
        // shelf of the same fixture six times is not a shelf.
        .distinctBy { it.slot.id }
        // And then one card per MATCH, which is not the same thing. Club
        // Brugge v Aston Villa stood on Home twice, once as "AU (STAN 09)" and
        // once as "UEFA Champio…" — two slots, two ids, one game. The Sport
        // tab folds these with bestPerFixture; a shelf that is a glance rather
        // than a schedule has more reason to, not less. fixtureKey prefers the
        // schedule's identity, which is what makes two spellings one match.
        .distinctBy { SportsParser.fixtureKey(it.event) }
        .toList()
        // A kick-off first, when the shelf has to choose. upcoming() sorts
        // the live group by `startMs ?: 0L`, which puts the rows that have no
        // clock at ALL at the head of it — a slot whose only claim to being
        // on is the word LIVE in its name. On the Sport tab that costs them a
        // position under a league heading; here it decides who is inside
        // SHELF_LIMIT, and twenty unverifiable rows could fill the shelf on a
        // busy evening and push out every match with a confirmed time.
        // Stable, so within each group upcoming's own order survives.
        .sortedBy { it.event.startMs == null }
        .take(SHELF_LIMIT)
}
