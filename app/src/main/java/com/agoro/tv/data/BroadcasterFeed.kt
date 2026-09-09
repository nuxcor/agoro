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
    /**
     * Whether the guide entry is this channel's own, or its family's.
     *
     * See [EpgMatcher.wearsAnothersSchedule]. False means the schedule read
     * here belongs to a broader channel of the same name — TUDN's on "TUDN
     * ZONA" — which is a claim about what the parent is showing and no claim
     * at all about this pipe.
     */
    val ownGuide: Boolean,
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
    /**
     * The names the guide channel this one is bound to goes by, for
     * [EpgMatcher.wearsAnothersSchedule]. Empty where nothing is known, which
     * reads as "no reason to doubt it" — the same silence-is-not-a-verdict
     * rule [reReadSlots] applies to a category nobody asked about.
     */
    guideNames: (LiveChannel) -> List<String>,
): List<BroadcasterFeed> = channels.mapNotNull { channel ->
    val title = nowTitle(channel)?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
    val sides = SportsParser.readFixture(title) ?: return@mapNotNull null
    BroadcasterFeed(
        channel, sides.first, sides.second,
        ownGuide = !EpgMatcher.wearsAnothersSchedule(channel.name, guideNames(channel)),
    )
}

/**
 * The channels showing this fixture right now, best-known first.
 *
 * Matched by [sameFixture], the same rule a slot name is read against.
 *
 * A channel whose guide entry is its FAMILY's rather than its own goes last.
 * On 2026-09-09 exactly two channels in the line-up read as Liverpool v
 * Atlético Madrid: "US: TUDN ZONA", wearing TUDN's schedule and playing a
 * Spanish radio show, and "NOW: TNT SPORT 1", which was showing the match at
 * 1080p50. The app led on the first because it sits 5,218 rows earlier in the
 * catalogue, which is the only thing that ordered these before now. Sorted,
 * not filtered, and stably: a doubtful guide entry is still the best evidence
 * available when it is the only one, and the rest of the ladder is untouched.
 */
internal fun broadcastersFor(
    home: String,
    away: String,
    index: List<BroadcasterFeed>,
): List<LiveChannel> = index.filter { feed ->
    sameFixture(feed.home, feed.away, home, away)
}.sortedBy { if (it.ownGuide) 0 else 1 }.map { it.channel }

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
