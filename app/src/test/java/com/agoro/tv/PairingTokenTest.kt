package com.agoro.tv

import com.agoro.tv.data.PairingServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The pairing URL is the only thing between a neighbour on the same Wi-Fi
 * and the TV's login. It used to be six guessable digits.
 */
class PairingTokenTest {

    private fun server() = PairingServer { _, _, _, _ -> }

    @Test
    fun `token is 128 bits, URL-safe`() {
        val token = server().tokenForTest()
        assertEquals(22, token.length)
        assertTrue(token.all { it.isLetterOrDigit() || it == '-' || it == '_' })
    }

    @Test
    fun `every server draws its own token`() {
        assertNotEquals(server().tokenForTest(), server().tokenForTest())
    }

    @Test
    fun `only the exact token path is served`() {
        val s = server()
        val t = s.tokenForTest()
        assertTrue(s.pathAllowed("/t/$t"))
        assertTrue(s.pathAllowed("/t/$t?x=1"))
        assertFalse("prefix must not pass", s.pathAllowed("/t/${t}extra"))
        assertFalse(s.pathAllowed("/t/${t.dropLast(1)}"))
        assertFalse(s.pathAllowed("/t/"))
        assertFalse(s.pathAllowed("/"))
        assertFalse(s.pathAllowed("/x/$t"))
    }
}
