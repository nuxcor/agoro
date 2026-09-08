package com.agoro.tv.data

import kotlinx.serialization.Serializable

/**
 * Where you got to in a film or an episode, in a shape that can travel.
 *
 * [updatedAtMs] is the whole reason this type exists. The app has always
 * stored progress as three parallel `Map<String, Long>`s with no notion of
 * WHEN anything was written, which is fine for one box and useless for two:
 * merging two of those can only be "pick a side", and picking a side throws
 * away whatever the other TV did.
 */
@Serializable
data class ProgressEntry(
    /** How far in, in ms. */
    val positionMs: Long,
    /** Total length, for the progress bar. Zero when it was never recorded. */
    val durationMs: Long = 0,
    /** When it was finished, or 0 for "not finished". */
    val watchedAtMs: Long = 0,
    /** When this device last touched it. The merge is decided on this. */
    val updatedAtMs: Long = 0,
)

/**
 * The blob that crosses the wire, versioned so the shape can change later.
 *
 * Keyed by [ProgressSync.syncKey] — "movie:101" — and never by the stream
 * URL, which carries the provider's username and password in its path.
 */
@Serializable
data class ProgressPayload(
    val version: Int = 1,
    val entries: Map<String, ProgressEntry> = emptyMap(),
)

/**
 * Continuing a film on the other TV.
 *
 * Everything here is pure: the identity, what is eligible to travel, and how
 * two devices' histories become one. The transport is somebody else's problem
 * precisely so this part can be tested, because this is the part that loses
 * data when it is wrong.
 */
object ProgressSync {

    /** Films and series only. Live has no "where I got to" worth carrying. */
    private val SYNCED_KINDS = listOf("/movie/", "/series/")

    /**
     * The most entries that travel, newest first.
     *
     * A cap, because this is a household's watch history and not an archive —
     * and because the whole blob is read and written as one value, so its size
     * is the cost of every sync.
     */
    const val MAX_ENTRIES = 500

    /**
     * Whether a stream's progress is worth syncing.
     *
     * The Xtream URL says what kind of thing it is: `/live/`, `/movie/` or
     * `/series/`. Live is excluded on purpose — resuming a live channel means
     * "join it now", so a position from another TV an hour ago is not just
     * useless, it is wrong.
     */
    fun syncable(url: String): Boolean = SYNCED_KINDS.any { url.contains(it) }

    /**
     * What a title is called ON THE WIRE — "movie:101", "series:202".
     *
     * NEVER the URL, and this is the whole reason the function exists.
     * Xtream puts the credentials in the PATH of every stream:
     *
     *     http://host/movie/<username>/<password>/101.mkv
     *
     * The app stores progress keyed by that URL, which is fine while it never
     * leaves the box. Sending it would have uploaded the provider username and
     * password, in the clear, as map keys — to hold a number saying how far
     * into a film somebody is. The stream id is all the other TV needs, it is
     * the same id on both, and it is not a secret.
     *
     * Null for anything that is not a film or an episode.
     */
    fun syncKey(url: String): String? {
        val kind = SYNCED_KINDS.firstOrNull { url.contains(it) } ?: return null
        val id = url.substringAfterLast('/').substringBeforeLast('.')
        if (id.isBlank()) return null
        return kind.trim('/') + ":" + id
    }

    /**
     * Wire name → the local URL that answers to it.
     *
     * The map back, built from URLs this box already holds. A title watched
     * only on the OTHER TV has no local URL yet and simply does not appear —
     * the caller reconstructs those from the catalogue, which is the only
     * place that knows the file extension.
     */
    fun urlsBySyncKey(urls: Iterable<String>): Map<String, String> =
        urls.mapNotNull { url -> syncKey(url)?.let { it to url } }.toMap()

    /**
     * The account this progress belongs to, as an opaque id.
     *
     * A salted hash of host and username, and deliberately NOT the password:
     * the server has no business holding a credential, and it does not need
     * one to tell two of your TVs apart from everybody else's. The salt is the
     * app's, so the id cannot be produced by someone who merely knows a
     * username.
     *
     * Same account on two boxes gives the same id — which is the entire trick,
     * and it works because Xtream stream URLs embed the account too, so the
     * KEYS already match across devices with no rewriting.
     */
    fun accountId(host: String, username: String, salt: String): String {
        val normalised = host.trim().lowercase().removeSuffix("/") + "\n" +
            username.trim().lowercase()
        val digest = java.security.MessageDigest.getInstance("SHA-256")
            .digest((salt + "\n" + normalised).toByteArray())
        return digest.joinToString("") { "%02x".format(it) }
    }

    /**
     * Two histories into one, entry by entry.
     *
     * PER TITLE, never per device. Taking the "newer device" wholesale is what
     * makes sync stories end in "it deleted everything I'd watched": the two
     * TVs have almost entirely different entries, and only the handful in both
     * are actually in conflict.
     *
     * The tie-break — larger position wins when the timestamps match — is for
     * histories written before [ProgressEntry.updatedAtMs] existed, where
     * everything carries 0. Further in is the better guess at more recent, and
     * guessing wrong there costs a viewer a few minutes rather than a film.
     */
    fun merge(
        local: Map<String, ProgressEntry>,
        remote: Map<String, ProgressEntry>,
    ): Map<String, ProgressEntry> {
        if (remote.isEmpty()) return local
        if (local.isEmpty()) return remote
        val out = LinkedHashMap<String, ProgressEntry>(local)
        for ((url, incoming) in remote) {
            val mine = out[url]
            out[url] = when {
                mine == null -> incoming
                incoming.updatedAtMs > mine.updatedAtMs -> incoming
                mine.updatedAtMs > incoming.updatedAtMs -> mine
                incoming.positionMs > mine.positionMs -> incoming
                else -> mine
            }
        }
        return out
    }

    /**
     * What this device should send: syncable entries, newest first, capped.
     *
     * Sorted before the cap so the 500 that travel are the 500 most recently
     * touched, rather than whichever 500 the map happened to iterate first.
     */
    fun outgoing(entries: Map<String, ProgressEntry>): ProgressPayload =
        ProgressPayload(
            entries = entries.asSequence()
                .sortedByDescending { it.value.updatedAtMs }
                .take(MAX_ENTRIES)
                .associate { it.key to it.value },
        )

    /**
     * The three parallel maps the app already keeps, as one set of entries.
     *
     * [updatedAt] is its own map for the same reason [PlayerPrefs.resumeDurations]
     * is: bolting a field onto the stored shape would make every existing
     * install fail to decode its own saved positions and lose them. Entries
     * predating it simply carry 0 and lose every tie-break, which is the right
     * outcome — a device that has been keeping timestamps knows more.
     */
    fun entriesOf(
        positions: Map<String, Long>,
        durations: Map<String, Long>,
        watched: Map<String, Long>,
        updatedAt: Map<String, Long>,
    ): Map<String, ProgressEntry> {
        val urls = positions.keys + watched.keys
        return urls.asSequence()
            .mapNotNull { url -> syncKey(url)?.let { it to url } }
            .associate { (key, url) ->
                key to ProgressEntry(
                    positionMs = positions[url] ?: 0,
                    durationMs = durations[url] ?: 0,
                    watchedAtMs = watched[url] ?: 0,
                    updatedAtMs = updatedAt[url] ?: 0,
                )
            }
    }
}
