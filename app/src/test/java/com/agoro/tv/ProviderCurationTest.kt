package com.agoro.tv

import com.agoro.tv.data.CatalogueManifest
import com.agoro.tv.data.PlaylistSource
import com.agoro.tv.data.XtreamClient
import com.agoro.tv.data.curationApplies
import com.agoro.tv.data.followProviderHost
import kotlinx.serialization.json.Json
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The build and the manifest must agree about which provider this app is for.
 *
 * These are two build-time constants set in two different places — the
 * manifest's host is baked into the asset by tools/manifest, the build's is a
 * CI secret — and nothing brought them together. They have already drifted
 * once: the provider moved, only one side was updated, and
 * [curationApplies] quietly started returning false. There is no error for
 * that and no log line. The app simply stops curating and shows the
 * provider's own 18,780 channels under the provider's own shelf names — every
 * DirecTV re-stream, every Tubi loop, every "### ENTERTAINMENT ###" separator
 * row — and the only symptom is a viewer saying there is a lot of junk.
 *
 * So it is compared here, where a mismatch fails the build instead of the
 * viewer.
 */
class ProviderCurationTest {

    private val manifest: CatalogueManifest by lazy {
        val file = File("src/main/assets/catalogue-manifest.json")
        assertTrue("shipped manifest is missing at ${file.absolutePath}", file.exists())
        Json { ignoreUnknownKeys = true; isLenient = true }
            .decodeFromString(CatalogueManifest.serializer(), file.readText())
    }

    /**
     * THE ONE THAT MATTERS. A branded build always curates.
     *
     * It dials exactly one panel and carries that panel's manifest in the same
     * APK, so the two cannot be for different catalogues and there is nothing
     * for a hostname to decide. This used to assert that the two hosts MATCH,
     * and that assertion described a rule which was itself the bug: everything
     * the manifest does is keyed on stream ids, a provider changing domain
     * changes no stream id, and yet the compare went false and curation
     * stopped dead. It failed exactly when nothing about the catalogue had
     * changed.
     *
     * Skipped when PROVIDER_HOST is blank — that is the unbranded build, and
     * its rule is the one below.
     */
    @Test
    fun `a branded build curates whatever address it dials`() {
        val providerHost = BuildConfig.PROVIDER_HOST
        if (providerHost.isBlank()) return
        val dialled = XtreamClient.normalize(providerHost)
        assertTrue(
            "a branded build must curate: it ships the manifest for the panel it dials",
            curationApplies(dialled, manifest.provider.host, providerHost),
        )
        // And it keeps curating after the provider moves house, which is the
        // whole point — same panel, same stream ids, different address.
        assertTrue(
            "a domain move must not switch curation off",
            curationApplies("http://somewhere.else.example.com", manifest.provider.host, providerHost),
        )
    }

    /**
     * The unbranded build is the one that still has to ask. Its viewer types
     * an address and may point at any provider at all, so the manifest's host
     * is the only signal there is.
     */
    @Test
    fun `an unbranded build still checks the host`() {
        assertTrue(curationApplies("http://pro.example.com:8080", "pro.example.com", ""))
        assertFalse(curationApplies("http://other.example.com", "pro.example.com", ""))
    }

    /** The manifest must name a provider at all, or it can claim no catalogue. */
    @Test
    fun `the shipped manifest names a provider`() {
        assertTrue(
            "manifest.provider.host is blank — curation can never apply",
            manifest.provider.host.isNotBlank(),
        )
    }

    /**
     * The rule itself, at the edges. A blank manifest host claims nothing —
     * it must never degrade into "matches everything", which would apply one
     * provider's drop list to another provider's catalogue.
     */
    @Test
    fun `a manifest naming no provider claims no catalogue`() {
        assertFalse(curationApplies("http://anything.example.com", "", ""))
        assertFalse(curationApplies("", "", ""))
    }

    @Test
    fun `the host is matched case-insensitively and inside a url`() {
        assertTrue(curationApplies("http://PRO.EXAMPLE.COM:8080", "pro.example.com", ""))
        assertTrue(curationApplies("http://pro.example.com", "PRO.EXAMPLE.COM", ""))
        assertFalse(curationApplies("http://other.example.com", "pro.example.com", ""))
    }

    /**
     * And the half that feeds it: a branded build rewrites a stored source to
     * its own address, so a source saved before the provider moved still ends
     * up matching. This is the path that makes the assertion above true on a
     * box that has been installed for months.
     */
    @Test
    fun `a branded build follows its own host before the rule is applied`() {
        val stored = PlaylistSource.Xtream(
            id = "1",
            name = "Provider",
            serverUrl = "http://old.example.com",
            username = "u",
            password = "p",
        )
        val followed = followProviderHost(stored, "pro.example.com") as PlaylistSource.Xtream
        assertTrue(curationApplies(followed.serverUrl, "pro.example.com", "pro.example.com"))
        // Unbranded: the address the viewer typed is never touched, and the
        // manifest correctly declines to claim it.
        val untouched = followProviderHost(stored, "") as PlaylistSource.Xtream
        assertFalse(curationApplies(untouched.serverUrl, "pro.example.com", ""))
    }
}
