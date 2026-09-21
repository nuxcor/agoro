package com.agoro.tv.data

/**
 * A build made for one provider follows that provider when it moves.
 *
 * 2026-09-20, from the box: "app is showing reconnecting". The address this
 * app was built with — `cf.dzidzi.online` — had been deleted from DNS
 * outright: NXDOMAIN on the ISP's resolver, on 1.1.1.1 and on 8.8.8.8, while
 * the domain itself stayed registered. The provider had moved, the build was
 * rebuilt with the new address, and the box still could not connect, because
 * a saved source keeps its own `serverUrl`.
 *
 * That rule is right where the viewer typed the address: a stored source is
 * the truth about itself, and an app that rewrites what someone entered is an
 * app that loses their playlist. It is wrong where the BUILD carries the
 * address. On a branded build the viewer never typed it, cannot see it
 * (Settings hides the host) and cannot change it — onboarding has no server
 * field and both "Add playlist" and the row that offers Remove are composed
 * only on the unbranded build. So a provider move left the one build that
 * ships with no way out of it: reconnecting for ever, and the only recovery
 * clearing the app's data.
 *
 * Netflix does not ask which server to use. Neither should this: where the
 * build states the address, the build is authoritative, and a source pointing
 * somewhere else is a source pointing at the provider's old house.
 *
 * Applied on READ rather than written back, so it heals every source on every
 * launch without a migration that can half-run, and so a build that is later
 * pointed somewhere else corrects itself the same way. The credentials, the
 * id and the name are untouched — only where to send them changes.
 */
internal fun followProviderHost(
    source: PlaylistSource,
    providerHost: String,
): PlaylistSource {
    // Unbranded: the viewer owns the address, and this must never touch it.
    if (providerHost.isBlank()) return source
    if (source !is PlaylistSource.Xtream) return source
    val want = XtreamClient.normalize(providerHost)
    val have = XtreamClient.normalize(source.serverUrl)
    if (want.equals(have, ignoreCase = true)) return source
    return source.copy(serverUrl = want)
}

/** [followProviderHost] over a whole list. */
internal fun followProviderHost(
    sources: List<PlaylistSource>,
    providerHost: String,
): List<PlaylistSource> =
    if (providerHost.isBlank()) sources else sources.map { followProviderHost(it, providerHost) }

/**
 * Whether the shipped manifest describes the catalogue this source serves.
 *
 * The rule is one line and it decides everything the manifest does: sections,
 * drops, shelf order, artwork, the collapse tiles. When it says no, the
 * bundle passes through uncurated and the viewer gets the provider's own
 * 18,780 channels under the provider's own shelf names — every DirecTV
 * re-stream, every Tubi FAST loop, every separator row.
 *
 * It is written down here, apart from its caller, because it is a comparison
 * between two build-time constants that are set in two different places and
 * have already drifted once. The manifest's host is baked into the asset by
 * tools/manifest; the build's is a CI secret. Nothing brought them together,
 * so when the provider moved and only one of them was updated, curation
 * silently stopped running — no error, no log, nothing on screen, just the
 * raw catalogue. [ProviderCurationTest] compares them now, and it fails the
 * build rather than the viewer.
 *
 * A blank [manifestHost] means the manifest names no provider and cannot
 * claim any catalogue.
 */
internal fun curationApplies(sourceUrl: String, manifestHost: String): Boolean {
    val host = manifestHost.takeIf { it.isNotBlank() } ?: return false
    return sourceUrl.contains(host, ignoreCase = true)
}
