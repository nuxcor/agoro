package com.agoro.tv

import com.agoro.tv.data.LiveChannel
import com.agoro.tv.data.SportsParser
import com.agoro.tv.data.broadcasterIndex
import com.agoro.tv.data.isNflPrimeTime
import com.agoro.tv.data.isUkSkySports
import com.agoro.tv.data.skyWhenCarried
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZonedDateTime

/**
 * Sunday, Monday and Thursday Night Football play on Sky Sports when Sky has
 * the feed (2026-10-02); every other game, and those Sky lacks, play as before.
 */
class SkyNflTest {

    private fun et(iso: String) = ZonedDateTime.parse(iso).toInstant().toEpochMilli()

    @Test
    fun `Sunday, Monday and Thursday night, on the NFL's own clock`() {
        // Sunday Night Football — Monday 01:20 in London.
        assertTrue(isNflPrimeTime("NFL", et("2026-10-04T20:20-04:00[America/New_York]")))
        // Monday Night Football, and the early game of a doubleheader.
        assertTrue(isNflPrimeTime("NFL", et("2026-10-05T20:15-04:00[America/New_York]")))
        assertTrue(isNflPrimeTime("NFL", et("2026-10-05T19:15-04:00[America/New_York]")))
        // Thursday Night Football.
        assertTrue(isNflPrimeTime("NFL", et("2026-10-08T20:15-04:00[America/New_York]")))
        // The Sunday afternoon slate is not.
        assertFalse(isNflPrimeTime("NFL", et("2026-10-04T13:00-04:00[America/New_York]")))
        assertFalse(isNflPrimeTime("NFL", et("2026-10-04T16:25-04:00[America/New_York]")))
        // Nor a Saturday night, nor another sport in the same slot.
        assertFalse(isNflPrimeTime("NFL", et("2026-12-19T20:15-05:00[America/New_York]")))
        assertFalse(isNflPrimeTime("Premier League", et("2026-10-04T20:20-04:00[America/New_York]")))
        // No kick-off: cannot be placed in prime time, so left alone.
        assertFalse(isNflPrimeTime("NFL", null))
    }

    @Test
    fun `UK Sky Sports in every tier the panel files it under, and no other Sky`() {
        listOf(
            "UK: SKY SPORTS MAIN EVENTS ᴴᴰ ◉",
            "VIP: SKY SPORTS MIX ᴿᴬᵂ",
            "NOW: SKY SPORTS+ ᴴᴰ",
            "UK: SKY SPORTS+ EVENT 12",
            "UK: SKY SPORT MAIN EVENT ᴿᴬᵂ",
            "4K: SKY SPORTS MAIN EVENTS ᵁᴴᴰ ³⁸⁴⁰ᴾ",
        ).forEach { assertTrue(it, isUkSkySports(it)) }
        listOf(
            "DE: SKY SPORT 1 ᴿᴬᵂ",
            "IT: SKY SPORT UNO ᴿᴬᵂ",
            "NZ: SKY SPORT 1 ᴿᴬᵂ",
            "NFL  | 02 - 49ers at Rams",
            "UK: TNT SPORT 1",
        ).forEach { assertFalse(it, isUkSkySports(it)) }
    }

    @Test
    fun `only Sky when Sky has the game, everything when it does not`() {
        val withSky = listOf("NFL  | 03 - Chiefs at Bills", "UK: SKY SPORTS MAIN EVENT ᴿᴬᵂ",
            "US: ESPN+ PPV 12", "VIP: SKY SPORTS MIX ᴿᴬᵂ")
        assertEquals(
            listOf("UK: SKY SPORTS MAIN EVENT ᴿᴬᵂ", "VIP: SKY SPORTS MIX ᴿᴬᵂ"),
            skyWhenCarried(withSky, emptyList()) { it },
        )
        val noSky = listOf("NFL  | 03 - Chiefs at Bills", "US: ESPN+ PPV 12")
        assertEquals(noSky, skyWhenCarried(noSky, emptyList()) { it })
    }

    @Test
    fun `a Sky channel on a borrowed guide cannot make the row Sky-only`() {
        // It wears a relative's schedule, so it says nothing about what that
        // pipe is showing. Counting it stripped NBC's own channel and every
        // slot, and left the viewer on a pipe with nothing behind it.
        val trusted = listOf("US: NBC", "NFL  | 01 - Bears at Packers")
        val doubted = listOf("VIP: SKY SPORTS MAIN EVENT ᴿᴬᵂ")
        assertEquals(trusted + doubted, skyWhenCarried(trusted, doubted) { it })
        // Behind a trusted Sky source it still rides along, as Sky.
        val withSky = trusted + "UK: SKY SPORTS MIX ᴿᴬᵂ"
        assertEquals(
            listOf("UK: SKY SPORTS MIX ᴿᴬᵂ", "VIP: SKY SPORTS MAIN EVENT ᴿᴬᵂ"),
            skyWhenCarried(withSky, doubted) { it },
        )
    }

    @Test
    fun `a guide title in the shape Sky writes reads as a fixture`() {
        // The broadcaster lookup finds Sky's channel by its guide entry, so
        // that entry has to parse. Shape, not a verbatim capture: there was no
        // NFL in Sky's listings the day this was written.
        val main = LiveChannel(
            id = "live:1", name = "UK: SKY SPORTS MAIN EVENT ᴿᴬᵂ", logo = null,
            url = "http://x/1.ts", categoryId = "uk", xtreamId = 1,
        )
        val mix = main.copy(id = "live:2", name = "UK: SKY SPORTS MIX ᴿᴬᵂ", xtreamId = 2)
        val index = broadcasterIndex(listOf(main, mix)) {
            if (it === main) "Live NFL: Kansas City Chiefs @ Buffalo Bills" else "NFL: Chiefs v Bills"
        }
        assertEquals(2, index.size)
        assertEquals("Buffalo Bills", index[0].away)
        // A slot name's "@" is still a date, not a side.
        assertNull(SportsParser.readFixture("Carabao Cup @ Aug 27 2:20 PM"))
    }
}
