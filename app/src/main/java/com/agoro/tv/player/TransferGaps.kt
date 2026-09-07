package com.agoro.tv.player

/**
 * How long an open connection has gone without a byte, and whether that gap
 * is worth saying out loud.
 *
 * ExoPlayer does not read a live stream continuously. `DefaultLoadControl`
 * stops loading the moment the buffer reaches its maximum, and while it is
 * stopped `ProgressiveMediaPeriod`'s loadable parks on a condition variable
 * BETWEEN reads — `loadCondition.block()` sits immediately before every
 * `extractor.read` — so the connection stays open with its body undrained
 * until the buffer falls back to the minimum. The distance between those two
 * numbers is how long a live socket sits unread, and a provider re-streaming
 * into a fixed send buffer is entitled to drop a client that has stopped
 * collecting.
 *
 * That is a theory about someone else's server and the app cannot ask it. What
 * it can do is measure its own side: if the connection goes quiet for longer
 * than the load control was ever going to make it, either the panel stopped
 * sending or the player stopped reading, and either way it belongs in the log
 * beside the rebuffer it explains.
 *
 * Free of Android and of media3, because it is a rule about a sequence of
 * timestamps rather than about a socket — which is what makes it checkable.
 * The listener that feeds it lives with the data source it watches, in
 * [PlayerPool].
 */
internal class TransferGaps(
    /** Gaps at least this long are reported; anything shorter is by design. */
    private val reportMs: Long,
) {

    /** When a byte last arrived; [NEVER] before the first and after a close. */
    private var lastByteAtMs = NEVER

    /**
     * Opens a connection, and starts the clock at the response rather than at
     * the first byte — so a body that is accepted and then says nothing for
     * half a minute is measured from where it went quiet, not from where it
     * finally spoke.
     */
    fun started(nowMs: Long) {
        lastByteAtMs = nowMs
    }

    /**
     * Records bytes arriving, and returns the gap they just closed if it ran
     * past [reportMs] — otherwise null.
     */
    fun bytes(nowMs: Long): Long? = measure(nowMs, open = true)

    /**
     * Records the connection ending, and returns the gap that was still open
     * when it did if that ran past [reportMs].
     *
     * This is where the interesting one lands. A connection that ends while
     * nothing has arrived for many seconds ended in the silence, not in the
     * playing, and that is the difference between a server that dropped an
     * idle client and a line that ran out of throughput.
     */
    fun ended(nowMs: Long): Long? = measure(nowMs, open = false)

    private fun measure(nowMs: Long, open: Boolean): Long? {
        val since = lastByteAtMs
        lastByteAtMs = if (open) nowMs else NEVER
        if (since == NEVER) return null
        val gap = nowMs - since
        return if (gap >= reportMs) gap else null
    }

    private companion object {
        const val NEVER = -1L
    }
}
