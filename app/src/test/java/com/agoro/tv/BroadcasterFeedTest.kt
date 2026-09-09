package com.agoro.tv

import com.agoro.tv.data.BroadcasterFeed
import com.agoro.tv.data.EpgMatcher
import com.agoro.tv.data.LiveChannel
import com.agoro.tv.data.broadcasterIndex
import com.agoro.tv.data.broadcastersFor
import com.agoro.tv.data.fixtureSources
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Guide entries taken verbatim from the EPG on 2026-09-08, each one checked
 * against a frame grabbed off the channel the same minute. TNT 4 carrying the
 * snooker is the case that matters most: it is what a static
 * league-to-channel map would have got wrong.
 */
class BroadcasterFeedTest {

    private fun ch(id: Int, name: String) = LiveChannel(
        id = "live:$id", name = name, logo = null,
        url = "http://x/$id.ts", categoryId = "uk-sports", xtreamId = id,
    )

    private val tnt1 = ch(1562525, "UK: TNT SPORT 1")
    private val tnt2 = ch(1562524, "UK: TNT SPORT 2")
    private val tnt3 = ch(1562523, "UK: TNT SPORT 3")
    private val tnt4 = ch(1562522, "UK: TNT SPORT 4")

    private val guide = mapOf(
        tnt1.id to "Live UCL: Real Madrid v Inter",
        tnt2.id to "Live UCL: Dortmund v Villarreal",
        tnt3.id to "Live UCL: Lille v Real Betis",
        tnt4.id to "Live Snooker: English Open",
    )

    private val channels = listOf(tnt1, tnt2, tnt3, tnt4)
    private val index = broadcasterIndex(channels) { guide[it.id] }

    /** Nothing known about a binding is no reason to doubt it. */
    private fun carrying(home: String, away: String, idx: List<BroadcasterFeed> = index) =
        broadcastersFor(home, away, idx) { true }

    @Test
    fun `the guide finds the channel carrying the match`() {
        val got = carrying("Real Madrid", "Inter Milan").own
        assertEquals(listOf(tnt1.id), got.map { it.id })
    }

    /**
     * The guide writes "Inter" and the row carries "Inter Milan" — a shorter
     * spelling inside a longer one is the same club, which is the tolerance
     * the schedule matcher already uses.
     */
    @Test
    fun `a shorter spelling in the guide is the same club`() {
        val got = carrying("Real Madrid", "Inter Milan").own
        assertEquals(listOf(tnt1.id), got.map { it.id })
    }

    /**
     * But NOT by prefix. "Inter" and "Internazionale" share no whole word, and
     * they are deliberately not matched: prefix matching would let "Man" take
     * both Manchester clubs. ESPN's spelling never reaches here anyway —
     * applySchedule keeps the roster's names on the row precisely so the
     * crest index and the guide both still resolve.
     */
    @Test
    fun `spellings that share no whole word are not guessed at`() {
        val got = carrying("Real Madrid", "Internazionale").own
        assertTrue(got.isEmpty())
    }

    /** The guide and the pack do not agree on which side leads. */
    @Test
    fun `either way round is the same fixture`() {
        val got = carrying("Inter Milan", "Real Madrid").own
        assertEquals(listOf(tnt1.id), got.map { it.id })
    }

    /**
     * The case a static league-to-channel map gets wrong. TNT 4 is a TNT
     * sports channel on a Champions League night and is showing snooker.
     */
    @Test
    fun `a sports channel showing another sport matches nothing`() {
        assertTrue(index.none { it.channel.id == tnt4.id })
    }

    @Test
    fun `another tie on a sister channel is not this fixture`() {
        val got = carrying("Real Madrid", "Inter Milan").own
        assertTrue(got.none { it.id == tnt2.id || it.id == tnt3.id })
    }

    /** One side is never enough — that is how a derby takes the wrong tie. */
    @Test
    fun `one matching side does not match the fixture`() {
        val got = carrying("Real Madrid", "Manchester City").own
        assertTrue(got.isEmpty())
    }

    @Test
    fun `a channel with no guide entry is skipped`() {
        val blind = broadcasterIndex(channels) { null }
        assertTrue(blind.isEmpty())
        assertTrue(carrying("Real Madrid", "Inter", blind).isEmpty())
    }

    /** A programme that is not a fixture never enters the index at all. */
    @Test
    fun `the index holds only guide entries that read as a fixture`() {
        assertEquals(
            listOf(tnt1.id, tnt2.id, tnt3.id),
            index.map { it.channel.id },
        )
    }

    // --- 2026-09-09, Liverpool v Atlético Madrid --------------------------
    //
    // "liverpool vs madrid is on tudn, a spanish station, with no match
    // playing, tnt is carying it though".
    //
    // Every string below was fetched rather than typed: the channel names and
    // their epg ids from player_api, the guide titles and the display-name
    // alternates from the packs themselves, and both pipes were opened at
    // 20:48 UTC — "US: TUDN ZONA" was a Radio MARCA studio show at h264
    // 1080p30, "NOW: TNT SPORT 1" was LIV 2-1 ATM at 89:41, h264 1080p50.
    //
    // Swept over the whole kept line-up, these were the ONLY two channels
    // whose guide named this fixture, and the app opened TUDN ZONA because it
    // sits at position 378 against TNT's 5,596.
    //
    // The alternates are epg6's, which is the list the app holds: XmltvMerger
    // folds them with putIfAbsent and epg6 is fetched first. The fuller lists
    // in epg14 and epg15 never reach this — see EpgMatcherTest.

    private val tudnZona = ch(1860592, "US: TUDN ZONA ᴿᴬᵂ")
    private val nowTnt1 = ch(1527617, "NOW: TNT SPORT 1")

    /** `tudn.us`, and it is TUDN's schedule, not TUDN Zona's. */
    private val tudnGuideNames = listOf("TUDN", "Tudn")

    /** `tntsports1.uk`, spelled plural where the provider spells it singular. */
    private val tnt1GuideNames = listOf(
        "TNT Sports 1", "TNT Sports 1 (1080p50)", "TNT Sports 1 (1080p25)",
        "TNT Sports 1 (720p25)", "TNT Sports 1 (576p25)", "TNT Sports 1 (480p25)",
    )

    private val septemberNinth = broadcasterIndex(listOf(tudnZona, nowTnt1)) {
        when (it.id) {
            tudnZona.id -> "Fútbol UEFA Champions League : Liverpool vs. Atlético Madrid ᴸᶦᵛᵉ"
            else -> "Live UCL: Liverpool v Atletico"
        }
    }

    /** The rule as the app asks it, off the names above. */
    private fun asShipped(channel: LiveChannel): Boolean = !EpgMatcher.wearsAnothersSchedule(
        channel.name,
        if (channel.id == tudnZona.id) tudnGuideNames else tnt1GuideNames,
    )

    /** The report, and the fix: the channel that had the match leads. */
    @Test
    fun `the channel wearing its family's guide does not lead a fixture`() {
        val got = broadcastersFor("Liverpool", "Atlético Madrid", septemberNinth, ::asShipped)
        assertEquals(listOf(nowTnt1.id), got.own.map { it.id })
        assertEquals(listOf(tudnZona.id), got.family.map { it.id })
    }

    /** Split, not filtered — a doubted feed is still a feed to step onto. */
    @Test
    fun `the doubted channel stays in the ladder`() {
        val got = broadcastersFor("Liverpool", "Atlético Madrid", septemberNinth, ::asShipped)
        assertFalse(got.isEmpty())
        assertTrue(got.family.any { it.id == tudnZona.id })
    }

    /**
     * The accented and unaccented spellings are one club, and the guide's
     * "Atletico" is the same side as the schedule's "Atlético Madrid".
     */
    @Test
    fun `both packs' spellings reach the same fixture`() {
        val got = broadcastersFor("Liverpool", "Atletico Madrid", septemberNinth, ::asShipped)
        assertEquals(listOf(nowTnt1.id), got.own.map { it.id })
        assertEquals(listOf(tudnZona.id), got.family.map { it.id })
    }

    /**
     * With nothing to doubt, the order is left exactly as the catalogue had
     * it: this rule demotes a family binding, it does not re-rank channels.
     */
    @Test
    fun `two channels with their own guides keep the catalogue's order`() {
        val got = broadcastersFor("Liverpool", "Atlético Madrid", septemberNinth) { true }
        assertEquals(listOf(tudnZona.id, nowTnt1.id), got.own.map { it.id })
        assertTrue(got.family.isEmpty())
    }

    // --- the order the row offers them in ---------------------------------

    /**
     * The reported bug with one channel removed. On a night when TNT is not in
     * the line-up, the only broadcaster is the one wearing TUDN's schedule —
     * and it used to lead anyway, opening the radio show while a slot named
     * for the fixture sat behind it.
     */
    @Test
    fun `a doubted broadcaster does not outrank the slots named for the fixture`() {
        val withoutTnt = broadcasterIndex(listOf(tudnZona)) {
            "Fútbol UEFA Champions League : Liverpool vs. Atlético Madrid ᴸᶦᵛᵉ"
        }
        val got = broadcastersFor("Liverpool", "Atlético Madrid", withoutTnt, ::asShipped)
        assertTrue("no channel is trusted to lead", got.own.isEmpty())
        assertEquals(
            listOf("SOCCER PPV 13", "UEFA 04", tudnZona.id),
            fixtureSources(
                own = got.own.map { it.id },
                slots = listOf("SOCCER PPV 13", "UEFA 04"),
                family = got.family.map { it.id },
            ),
        )
    }

    /** And a broadcaster on its own guide still leads every slot. */
    @Test
    fun `the broadcaster's own channel leads the slots`() {
        assertEquals(
            listOf("tnt", "SOCCER PPV 13", "tudn zona"),
            fixtureSources(
                own = listOf("tnt"),
                slots = listOf("SOCCER PPV 13"),
                family = listOf("tudn zona"),
            ),
        )
    }
}
