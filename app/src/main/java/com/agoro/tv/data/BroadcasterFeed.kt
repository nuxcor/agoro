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
 * The channels showing this fixture right now, best-known first.
 *
 * Both sides have to match, either way round — the guide and the pack do not
 * agree on which leads, and one side matching is how Manchester United takes a
 * Manchester City tie. Club spellings go through [SportsParser.sameClub], the
 * same tolerance the schedule matcher uses, because a guide writes "Inter"
 * where ESPN writes "Internazionale" exactly as the packs do.
 */
internal fun broadcastersFor(
    home: String,
    away: String,
    index: List<BroadcasterFeed>,
): List<LiveChannel> = index.filter { feed ->
    (SportsParser.sameClub(feed.home, home) && SportsParser.sameClub(feed.away, away)) ||
        (SportsParser.sameClub(feed.home, away) && SportsParser.sameClub(feed.away, home))
}.map { it.channel }
