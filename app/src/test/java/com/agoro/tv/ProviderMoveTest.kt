package com.agoro.tv

import com.agoro.tv.data.PlaylistSource
import com.agoro.tv.data.followProviderHost
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * The provider moved and the box could not follow — 2026-09-20, reported as
 * "app is showing reconnecting". cf.dzidzi.online had been deleted from DNS
 * while the saved source still pointed at it.
 */
class ProviderMoveTest {

    private fun xtream(server: String) = PlaylistSource.Xtream(
        id = "1", name = "Provider", serverUrl = server, username = "u", password = "p",
    )

    @Test
    fun `a branded build re-points a source left on the old address`() {
        val moved = followProviderHost(xtream("http://cf.dzidzi.online"), "cmc.exchange-cdn.com")
        assertEquals("http://cmc.exchange-cdn.com", (moved as PlaylistSource.Xtream).serverUrl)
        assertEquals("the credentials are untouched", "u", moved.username)
        assertEquals("p", moved.password)
        assertEquals("and so is its identity", "1", moved.id)
    }

    @Test
    fun `a source already on the build's address is left alone`() {
        val same = xtream("http://cmc.exchange-cdn.com")
        assertSame(same, followProviderHost(same, "http://cmc.exchange-cdn.com/"))
    }

    /** The scheme and a trailing slash are spelling, not a different house. */
    @Test
    fun `spelling differences are not a move`() {
        val stored = xtream("http://cmc.exchange-cdn.com")
        assertSame(stored, followProviderHost(stored, "cmc.exchange-cdn.com"))
    }

    /** On an unbranded build the viewer owns the address. */
    @Test
    fun `an unbranded build never rewrites an address`() {
        val typed = xtream("http://my-own-panel.example:8080")
        assertSame(typed, followProviderHost(typed, ""))
    }

    @Test
    fun `an m3u playlist is not an account and is left alone`() {
        val m3u = PlaylistSource.M3u(id = "2", name = "List", url = "http://elsewhere/x.m3u")
        assertSame(m3u, followProviderHost(m3u, "cmc.exchange-cdn.com"))
    }

    @Test
    fun `a list follows in one pass`() {
        val moved = followProviderHost(
            listOf(xtream("http://cf.dzidzi.online"), xtream("http://cmc.exchange-cdn.com")),
            "cmc.exchange-cdn.com",
        )
        assertEquals(
            listOf("http://cmc.exchange-cdn.com", "http://cmc.exchange-cdn.com"),
            moved.map { (it as PlaylistSource.Xtream).serverUrl },
        )
    }
}
