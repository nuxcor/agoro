package com.agoro.tv.data

/**
 * The broadcaster's own channel, offered as a source for a fixture.
 *
 * The sport pipeline reads only PPV slots — ManifestCuration puts a channel in
 * `events` when its section is PPV and nowhere else — so TNT Sports 1 could
 * carry Real Madrid v Inter at 1080p50 all evening while the fixture row had
 * nothing better than a slot named for the match that was playing tennis.
 * Measured on 2026-09-08: every SOCCER PPV slot checked was black, tennis, or
 * a different fixture; every broadcaster channel was the match at h264
 * 1080p50.
 *
 * A channel is matched by its GUIDE, never by its name. "TNT Sport 1" says
 * nothing about tonight, and a static league-to-channel map is wrong the
 * moment a broadcaster moves a tie: on that same night TNT 1 had Real Madrid,
 * TNT 2 had Dortmund, TNT 3 had Lille and TNT 4 had the snooker. The guide
 * distinguishes those and nothing else does.
 */
internal class BroadcasterFeed(
    val channel: LiveChannel,
    /** The two sides the guide entry named, for the match below. */
    val home: String,
    val away: String,
)

/**
 * Every channel whose guide entry reads as a fixture, read ONCE.
 *
 * Built per emission and scanned per fixture, rather than reading the guide
 * again for every fixture: [SportsParser.readFixture] is regex work, a
 * catalogue holds hundreds of channels and a shelf holds tens of fixtures, and
 * the box this runs on has 2GB of RAM. The same reason [SportsParser
 * .worthParsing] exists.
 */
internal fun broadcasterIndex(
    channels: List<LiveChannel>,
    /** The channel's current programme title, or null when nothing is known. */
    nowTitle: (LiveChannel) -> String?,
): List<BroadcasterFeed> = channels.mapNotNull { channel ->
    val title = nowTitle(channel)?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
    val sides = SportsParser.readFixture(title) ?: return@mapNotNull null
    BroadcasterFeed(channel, sides.first, sides.second)
}

/**
 * The channels showing this fixture right now, split by whether the guide
 * entry that says so is the channel's OWN.
 *
 * [own] is the evidence a fixture row should lead on. [family] is a channel
 * wearing a broader relative's schedule — TUDN's on "TUDN ZONA" — which is a
 * claim about the parent and no claim at all about this pipe; see
 * [EpgMatcher.wearsAnothersSchedule] and [fixtureSources] for where it lands.
 *
 * Split rather than filtered. On 2026-09-09 exactly two channels in the
 * line-up read as Liverpool v Atlético Madrid — "US: TUDN ZONA", playing a
 * Spanish radio show, and "NOW: TNT SPORT 1", showing the match at 1080p50 —
 * and the app opened the first because it sits 5,218 rows earlier in the
 * catalogue, which is the only thing that ordered these before now. But a
 * doubted feed is still a feed, and the viewer has to be able to reach it.
 */
internal class Broadcasters(
    val own: List<LiveChannel>,
    val family: List<LiveChannel>,
) {
    fun isEmpty() = own.isEmpty() && family.isEmpty()
}

/**
 * The channels showing this fixture right now, matched by [sameFixture] — the
 * same rule a slot name is read against.
 *
 * [ownGuide] is asked only of the channels that survive the match, not of
 * every fixture-shaped entry in the index. This runs on the press, on the main
 * thread, and on a Champions League night the index holds dozens of channels
 * while a fixture is carried by two or three; asking the guide for the names
 * of all of them was work for answers nobody reads.
 */
internal fun broadcastersFor(
    home: String,
    away: String,
    index: List<BroadcasterFeed>,
    ownGuide: (LiveChannel) -> Boolean,
): Broadcasters {
    val carrying = index.filter { feed -> sameFixture(feed.home, feed.away, home, away) }
        .map { it.channel }
    val (own, family) = carrying.partition(ownGuide)
    return Broadcasters(own = own, family = family)
}

/**
 * A fixture's sources in the order the row should offer them.
 *
 * The broadcaster's own channel leads, because a channel showing the match on
 * its own schedule is the best evidence this app has — measured 2026-09-08,
 * every SOCCER PPV slot checked was black, tennis, or a different fixture
 * while TNT carried the match at 1080p50.
 *
 * A channel wearing its FAMILY's schedule does not lead, and it does not
 * outrank the slots either. It used to sit at the head whenever it was the
 * only broadcaster, which is the reported bug with one channel removed: press
 * Liverpool v Atlético Madrid on a night when TNT is not in the line-up and
 * the row would open the Spanish radio show again, with a PPV slot named for
 * the fixture sitting behind it. Between a pipe whose own name claims THIS
 * match and a pipe whose relative's schedule claims it, the first is the
 * better guess — and by the time this is asked the slots have been re-read
 * against the panel's current names, which the family binding never is.
 *
 * Takes the three groups already labelled, so the one thing it decides is the
 * one thing it is named for; pure and outside the view model so that decision
 * is testable without one.
 */
internal fun <T> fixtureSources(own: List<T>, slots: List<T>, family: List<T>): List<T> =
    own + slots + family

/**
 * Whether two already-parsed pairs of sides are the same fixture.
 *
 * Both sides have to match, either way round — the guide and the pack do not
 * agree on which leads, and one side matching is how Manchester United takes a
 * Manchester City tie. Club spellings go through [SportsParser.sameClub], the
 * same tolerance the schedule matcher uses, because a guide writes "Inter"
 * where ESPN writes "Internazionale" exactly as the packs do.
 *
 * Takes the sides rather than the strings they came from, so [broadcasterIndex]
 * can keep parsing each guide title once and [namesFixture] can parse a slot
 * name on demand — two callers with different parse costs, one rule.
 */
internal fun sameFixture(
    aHome: String,
    aAway: String,
    bHome: String,
    bAway: String,
): Boolean =
    (SportsParser.sameClub(aHome, bHome) && SportsParser.sameClub(aAway, bAway)) ||
        (SportsParser.sameClub(aHome, bAway) && SportsParser.sameClub(aAway, bHome))

/**
 * Whether a slot or guide entry names THIS fixture.
 *
 * Reads the sides out of the name and asks [sameFixture], which is the rule
 * [broadcastersFor] applies to the guide — the two are asking the same
 * question of two different lists, and a fixture the guide would match but a
 * slot would not is a bug in one of them, not a difference between them.
 */
internal fun namesFixture(name: String, home: String, away: String): Boolean {
    val sides = SportsParser.readFixture(name) ?: return false
    return sameFixture(sides.first, sides.second, home, away)
}

/**
 * The fixture's slots, re-read against what the panel calls them NOW.
 *
 * A PPV slot is a pipe, and the provider re-points it: stream 1025280 carried
 * Freiburg v Motherwell on 8 September and Barcelona v Feyenoord on the 9th.
 * The app's catalogue can be twelve hours old, so the row's ids are a claim
 * about what those pipes carried when the list was fetched — and when the
 * provider shuffles a matchday between two pipes, pressing one fixture opens
 * the other. That is the "sometimes it shows the Barcelona game, another time
 * it's showing Stuttgart" report, and no ranking can fix it: the row is
 * pointing at the wrong pipe.
 *
 * @param fresh what the panel calls each stream right now, for the categories
 *   that answered. EMPTY means the panel said nothing, which is not evidence
 *   about anything — the candidates are returned untouched.
 * @param inFetchedCategory whether a stream id belongs to a category that was
 *   actually re-read. An id from a category nobody asked about is unjudged and
 *   is kept; an id from a category that WAS re-read and did not come back has
 *   gone from the panel.
 * @param alsoConsider every stream id the app holds for the categories that
 *   were re-read, so a pipe that has MOVED onto this fixture can be found.
 */
internal fun reReadSlots(
    home: String,
    away: String,
    candidates: List<Int>,
    fresh: Map<Int, String>,
    inFetchedCategory: (Int) -> Boolean,
    alsoConsider: List<Int>,
): List<Int> {
    if (fresh.isEmpty()) return candidates
    val kept = candidates.filter { id ->
        val name = fresh[id]
        when {
            // Named, and still this match: the pipe has not moved.
            name != null -> namesFixture(name, home, away)
            // Re-read and absent: the pipe is gone from the panel.
            inFetchedCategory(id) -> false
            // Never asked about — a category this fixture does not live in,
            // or one that failed. Silence is not a verdict.
            else -> true
        }
    }
    // And the pipe the match moved TO. Only ids the app already holds: a slot
    // it has never seen has no url, no logo and no place in the bundle.
    val found = alsoConsider.filter { it !in kept && namesFixture(fresh[it].orEmpty(), home, away) }
    return kept + found
}
