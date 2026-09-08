package com.agoro.tv

import com.agoro.tv.data.ProgressEntry
import com.agoro.tv.data.ProgressSync
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Continuing a film on the other TV.
 *
 * These are the rules that lose data when they are wrong, which is why they
 * live away from the network code and are tested without it.
 */
class ProgressSyncTest {

    private val movie = "http://p.tv/movie/u/p/101.mkv"
    private val episode = "http://p.tv/series/u/p/202.mkv"
    private val live = "http://p.tv/live/u/p/303.ts"

    /** What those look like once the credentials are off them. */
    private val KEY_MOVIE = "movie:101"
    private val KEY_EPISODE = "series:202"

    private fun at(pos: Long, t: Long) = ProgressEntry(positionMs = pos, updatedAtMs = t)

    // --- what is allowed to travel -------------------------------------------

    @Test
    fun `films and episodes sync, live does not`() {
        assertTrue(ProgressSync.syncable(movie))
        assertTrue(ProgressSync.syncable(episode))
        // Resuming live means "join it now". A position from the other TV an
        // hour ago is not merely useless, it is wrong.
        assertFalse(ProgressSync.syncable(live))
    }

    @Test
    fun `the wire never carries a URL, because the URL carries the password`() {
        // Xtream puts credentials in the PATH of every stream:
        //   http://host/movie/<username>/<password>/101.mkv
        // Keying the payload by that would have uploaded the provider login
        // in the clear, to store how far into a film somebody is.
        assertEquals("movie:101", ProgressSync.syncKey(movie))
        assertEquals("series:202", ProgressSync.syncKey(episode))
        assertNull(ProgressSync.syncKey(live))

        val entries = ProgressSync.entriesOf(
            positions = mapOf(movie to 100L),
            durations = emptyMap(), watched = emptyMap(), updatedAt = emptyMap(),
        )
        val wire = ProgressSync.outgoing(entries).entries.keys
        assertEquals(setOf("movie:101"), wire)
        assertTrue(wire.none { it.contains("/") })
        assertTrue(wire.none { it.contains("secret") })
    }

    @Test
    fun `the map back finds only what this box already holds`() {
        val back = ProgressSync.urlsBySyncKey(listOf(movie, episode, live))
        assertEquals(movie, back["movie:101"])
        assertEquals(episode, back["series:202"])
        // Live has no wire name at all, so it cannot round-trip.
        assertEquals(2, back.size)
    }

    @Test
    fun `the payload is capped at the most recently touched`() {
        val many = (1..600).associate { "movie:$it" to at(it.toLong(), it.toLong()) }
        val out = ProgressSync.outgoing(many)
        assertEquals(ProgressSync.MAX_ENTRIES, out.entries.size)
        // Newest first, so the cap keeps what matters rather than whatever the
        // map iterated first.
        assertTrue(out.entries.containsKey("movie:600"))
        assertFalse(out.entries.containsKey("movie:1"))
    }

    // --- identity ------------------------------------------------------------

    @Test
    fun `the same account on two boxes gets the same id`() {
        val a = ProgressSync.accountId("http://P.TV/", "Viewer", "salt")
        val b = ProgressSync.accountId("http://p.tv", "viewer", "salt")
        assertEquals(a, b)
    }

    @Test
    fun `different accounts, hosts and salts do not collide`() {
        val base = ProgressSync.accountId("http://p.tv", "viewer", "salt")
        assertNotEquals(base, ProgressSync.accountId("http://p.tv", "other", "salt"))
        assertNotEquals(base, ProgressSync.accountId("http://q.tv", "viewer", "salt"))
        // Without the app's salt, knowing a username is not enough to make one.
        assertNotEquals(base, ProgressSync.accountId("http://p.tv", "viewer", "other"))
    }

    @Test
    fun `the id carries neither the username nor the password`() {
        val id = ProgressSync.accountId("http://p.tv", "viewer", "salt")
        assertFalse(id.contains("viewer"))
        assertEquals(64, id.length) // plain SHA-256 hex, nothing else
    }

    // --- the merge -----------------------------------------------------------

    @Test
    fun `the newer write wins, whichever side it came from`() {
        assertEquals(
            at(900, 20),
            ProgressSync.merge(mapOf(KEY_MOVIE to at(100, 10)), mapOf(KEY_MOVIE to at(900, 20)))[KEY_MOVIE],
        )
        assertEquals(
            at(900, 20),
            ProgressSync.merge(mapOf(KEY_MOVIE to at(900, 20)), mapOf(KEY_MOVIE to at(100, 10)))[KEY_MOVIE],
        )
    }

    @Test
    fun `entries only one side has are kept, not dropped`() {
        // The failure this exists to prevent: taking the "newer device"
        // wholesale deletes everything watched on the other one.
        val merged = ProgressSync.merge(
            mapOf(KEY_MOVIE to at(100, 10)),
            mapOf(KEY_EPISODE to at(200, 5)),
        )
        assertEquals(setOf(KEY_MOVIE, KEY_EPISODE), merged.keys)
    }

    @Test
    fun `untimestamped histories fall back to further-in`() {
        // Everything written before updatedAtMs existed carries 0. Further in
        // is the better guess at more recent, and being wrong costs minutes.
        val merged = ProgressSync.merge(mapOf(KEY_MOVIE to at(100, 0)), mapOf(KEY_MOVIE to at(500, 0)))
        assertEquals(500, merged[KEY_MOVIE]!!.positionMs)
    }

    @Test
    fun `an empty side changes nothing`() {
        val mine = mapOf(KEY_MOVIE to at(100, 10))
        assertEquals(mine, ProgressSync.merge(mine, emptyMap()))
        assertEquals(mine, ProgressSync.merge(emptyMap(), mine))
    }

    @Test
    fun `finishing a title travels too`() {
        val finished = ProgressEntry(positionMs = 0, watchedAtMs = 999, updatedAtMs = 30)
        val merged = ProgressSync.merge(mapOf(KEY_MOVIE to at(100, 10)), mapOf(KEY_MOVIE to finished))
        assertEquals(999, merged[KEY_MOVIE]!!.watchedAtMs)
    }

    // --- assembling from what the app already stores --------------------------

    @Test
    fun `the parallel maps become entries, live filtered out`() {
        val entries = ProgressSync.entriesOf(
            positions = mapOf(movie to 100, live to 50),
            durations = mapOf(movie to 7200),
            watched = mapOf(episode to 888),
            updatedAt = mapOf(movie to 42),
        )
        assertEquals(setOf(KEY_MOVIE, KEY_EPISODE), entries.keys)
        assertEquals(ProgressEntry(100, 7200, 0, 42), entries[KEY_MOVIE])
        // Finished-only titles have no position and must still travel.
        assertEquals(888, entries[KEY_EPISODE]!!.watchedAtMs)
    }
}
