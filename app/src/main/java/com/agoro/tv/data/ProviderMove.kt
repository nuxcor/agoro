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
