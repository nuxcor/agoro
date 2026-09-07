package com.agoro.tv

import com.agoro.tv.player.TransferGaps
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The rule behind the log line that is meant to settle "live breaks for a few
 * seconds".
 *
 * It exists to be read in a capture from the box, so the one thing it must not
 * do is cry wolf: a healthy live stream is throttled by the load control every
 * few seconds by design, and a diagnostic that fires on the design teaches
 * nothing. The quiet shapes get as many tests as the loud one.
 */
class TransferGapsTest {

    private val report = 6_000L

    @Test
    fun `a connection read steadily reports nothing`() {
        val gaps = TransferGaps(report)
        gaps.started(0L)
        // Bytes every 30ms, as a live loader actually reads them.
        for (tick in 1..500) assertNull(gaps.bytes(tick * 30L))
    }

    @Test
    fun `the designed throttle window is silent`() {
        val gaps = TransferGaps(report)
        gaps.started(0L)
        assertNull(gaps.bytes(1_000L))
        // Four seconds is maxBufferMs minus minBufferMs on live: the load
        // control parking the loader, not a fault.
        assertNull(gaps.bytes(5_000L))
        assertNull(gaps.bytes(5_030L))
    }

    @Test
    fun `a silence past the threshold is reported once`() {
        val gaps = TransferGaps(report)
        gaps.started(0L)
        assertNull(gaps.bytes(1_000L))
        assertEquals(9_000L, gaps.bytes(10_000L))
        // The gap closed; reading on says nothing more about it.
        assertNull(gaps.bytes(10_030L))
        assertNull(gaps.bytes(10_060L))
    }

    @Test
    fun `the threshold itself reports`() {
        val gaps = TransferGaps(report)
        gaps.started(0L)
        assertEquals(6_000L, gaps.bytes(6_000L))
    }

    @Test
    fun `a connection that dies in the silence reports the silence`() {
        val gaps = TransferGaps(report)
        gaps.started(0L)
        assertNull(gaps.bytes(2_000L))
        // Nothing arrives, then the source closes: this is the shape a server
        // dropping an unread client leaves behind.
        assertEquals(12_000L, gaps.ended(14_000L))
    }

    @Test
    fun `a connection closed while reading normally reports nothing`() {
        val gaps = TransferGaps(report)
        gaps.started(0L)
        assertNull(gaps.bytes(2_000L))
        // Zapping away, or the ladder swapping the url.
        assertNull(gaps.ended(2_100L))
    }

    @Test
    fun `a body that opens and then says nothing is reported`() {
        val gaps = TransferGaps(report)
        // The panel accepted the request and sent headers. What follows is
        // silence, and the clock runs from the response, not the first byte.
        gaps.started(0L)
        assertEquals(30_000L, gaps.bytes(30_000L))
    }

    @Test
    fun `a reopened connection starts clean`() {
        val gaps = TransferGaps(report)
        gaps.started(0L)
        assertNull(gaps.bytes(1_000L))
        assertEquals(20_000L, gaps.ended(21_000L))
        // The reconnect's own long wait belongs to the old connection.
        gaps.started(60_000L)
        assertNull(gaps.bytes(60_030L))
    }

    @Test
    fun `the first reading is a baseline, never a verdict`() {
        val gaps = TransferGaps(report)
        // A listener that somehow sees bytes before it saw the connection
        // open still has to start somewhere, and it starts silently.
        assertNull(gaps.bytes(50_000L))
        // From that baseline it measures as normal.
        assertEquals(70_000L, gaps.ended(120_000L))
    }
}
