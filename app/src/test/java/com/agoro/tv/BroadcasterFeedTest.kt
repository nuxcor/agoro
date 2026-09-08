package com.agoro.tv

import com.agoro.tv.data.LiveChannel
import com.agoro.tv.data.broadcasterIndex
import com.agoro.tv.data.broadcastersFor
import org.junit.Assert.assertEquals
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

    private val tnt1 = ch(1527617, "UK: TNT SPORT 1")
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

    @Test
    fun `the guide finds the channel carrying the match`() {
        val got = broadcastersFor("Real Madrid", "Inter Milan", index)
        assertEquals(listOf(tnt1.id), got.map { it.id })
    }

    /**
     * The guide writes "Inter" and the row carries "Inter Milan" — a shorter
     * spelling inside a longer one is the same club, which is the tolerance
     * the schedule matcher already uses.
     */
    @Test
    fun `a shorter spelling in the guide is the same club`() {
        val got = broadcastersFor("Real Madrid", "Inter Milan", index)
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
        val got = broadcastersFor("Real Madrid", "Internazionale", index)
        assertTrue(got.isEmpty())
    }

    /** The guide and the pack do not agree on which side leads. */
    @Test
    fun `either way round is the same fixture`() {
        val got = broadcastersFor("Inter Milan", "Real Madrid", index)
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
        val got = broadcastersFor("Real Madrid", "Inter Milan", index)
        assertTrue(got.none { it.id == tnt2.id || it.id == tnt3.id })
    }

    /** One side is never enough — that is how a derby takes the wrong tie. */
    @Test
    fun `one matching side does not match the fixture`() {
        val got = broadcastersFor("Real Madrid", "Manchester City", index)
        assertTrue(got.isEmpty())
    }

    @Test
    fun `a channel with no guide entry is skipped`() {
        val blind = broadcasterIndex(channels) { null }
        assertTrue(blind.isEmpty())
        assertTrue(broadcastersFor("Real Madrid", "Inter", blind).isEmpty())
    }

    /** A programme that is not a fixture never enters the index at all. */
    @Test
    fun `the index holds only guide entries that read as a fixture`() {
        assertEquals(
            listOf(tnt1.id, tnt2.id, tnt3.id),
            index.map { it.channel.id },
        )
    }
}
