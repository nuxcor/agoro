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
 * ON A BRANDED BUILD THE ANSWER IS ALWAYS YES, and that is the change that
 * matters here. Such a build dials exactly one panel, carries that panel's
 * manifest inside the same APK, and rewrites every stored source to its own
 * address on read ([followProviderHost]). The manifest and the build ship
 * together and are versioned together, so they cannot be for different
 * catalogues — there is nothing left for a hostname to decide.
 *
 * It used to decide it anyway, by comparing the source's host against the
 * host the manifest names, and that comparison was a bad proxy for the
 * question. Everything the manifest does is keyed on the panel's STREAM IDS.
 * A provider moving from one domain to another does not change a single
 * stream id — the catalogue is identical — yet the compare went false and
 * curation stopped dead: no sections, no drops, no shelf order, no artwork,
 * and the viewer got the provider's own 18,780 channels with every DirecTV
 * re-stream and Tubi loop in them. So the check failed exactly when nothing
 * about the catalogue had changed, which is the worst possible time, and it
 * did it in silence.
 *
 * On an UNBRANDED build the viewer types their own address and may point at
 * any provider at all, so the host is the only signal there is and the
 * compare stays. A blank [manifestHost] there means the manifest names no
 * provider and can claim no catalogue.
 */
internal fun curationApplies(
    sourceUrl: String,
    manifestHost: String,
    providerHost: String,
): Boolean {
    if (providerHost.isNotBlank()) return true
    val host = manifestHost.takeIf { it.isNotBlank() } ?: return false
    return sourceUrl.contains(host, ignoreCase = true)
}
