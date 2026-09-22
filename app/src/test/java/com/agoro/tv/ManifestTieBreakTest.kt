package com.agoro.tv

import com.agoro.tv.data.ManifestRepository
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Which manifest wins when the bundled asset and the cached remote copy claim
 * the same content version.
 *
 * This one comparison switched curation off on a real box for weeks, and no
 * test could see it because deciding it needed an Android context, assets and
 * a cache directory. It is one line of logic; it is pulled out and held here.
 *
 * THE FAULT. `generated` is described in ManifestRepository as "the content
 * version", and for a builder run it is. For a hand edit it is not: it is the
 * builder's run time, and editing the shipped asset by hand leaves it exactly
 * where it was. The provider host was hand edited three times —
 * pro.dzidzi.online, then pro.business-cdn-8k.com, then cf.dzidzi.online —
 * all under one unchanged stamp of 2026-09-11T01:39:33.
 *
 * With a tie keeping the cache, a box that fetched the remote copy while it
 * still named the first host could never stop serving it. The stamps are
 * equal for ever, so the stale copy wins every time, and it lives in cacheDir
 * where an app update does not clear it. That box ran a manifest naming one
 * provider against a build dialling another, curation refused to apply, and
 * updating the app could not fix it because the APK was never the stale part.
 */
class ManifestTieBreakTest {

    private fun stamp(version: Int, generated: String) =
        ManifestRepository.Stamp(version, generated)

    /**
     * The regression. Equal stamps must go to the asset: it ships inside the
     * build and is guaranteed to be the one that build was made against.
     */
    @Test
    fun `a tie goes to the bundled asset`() {
        val same = "2026-09-11T01:39:33+00:00"
        assertTrue(
            "a cached copy must not outlive the asset it ties with",
            ManifestRepository.assetWinsTie(stamp(1, same), stamp(1, same)),
        )
    }

    /** A genuinely newer cache still wins — the remote is how a fix arrives between releases. */
    @Test
    fun `a newer cache still beats the asset`() {
        assertFalse(
            ManifestRepository.assetWinsTie(
                stamp(1, "2026-09-11T01:39:33+00:00"),
                stamp(1, "2026-09-22T13:45:00+00:00"),
            ),
        )
    }

    /** And a newer asset still beats a stale cache, which is what an app update is. */
    @Test
    fun `a newer asset beats a stale cache`() {
        assertTrue(
            ManifestRepository.assetWinsTie(
                stamp(1, "2026-09-22T13:45:00+00:00"),
                stamp(1, "2026-09-11T01:39:33+00:00"),
            ),
        )
    }

    /**
     * Schema first, then the build stamp — the order [load] has always used.
     * A newer schema on the asset wins even against a later-generated cache,
     * because the app can only read the schema it was built for.
     */
    @Test
    fun `schema outranks the build stamp`() {
        assertTrue(
            ManifestRepository.assetWinsTie(
                stamp(2, "2026-09-01T00:00:00+00:00"),
                stamp(1, "2026-09-22T13:45:00+00:00"),
            ),
        )
        assertFalse(
            ManifestRepository.assetWinsTie(
                stamp(1, "2026-09-22T13:45:00+00:00"),
                stamp(2, "2026-09-01T00:00:00+00:00"),
            ),
        )
    }
}
