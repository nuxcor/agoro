package com.agoro.tv.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.URLEncoder

data class TmdbInfo(
    val rating: Double?,
    val voteCount: Int?,
    val overview: String?,
    val posterUrl: String?,
    val backdropUrl: String?,
    /** "author — excerpt" strings, at most three. */
    val reviews: List<String>,
    /** Top-billed actors, comma-separated. */
    val cast: String?,
    val director: String?,
    /**
     * The id the search matched. Read all along and thrown away, and it is
     * the fallback that makes episode enrichment work for M3U shows and for
     * the 2.3% of series the panel ships no `tmdb` for.
     */
    val tmdbId: Int?,
)

/**
 * One episode as TMDB has it.
 *
 * Every field independently nullable: TMDB has a name for nearly everything,
 * a still for most things and a runtime for rather less, and a partial answer
 * is still worth merging.
 */
data class TmdbEpisode(
    val episodeNum: Int,
    val name: String?,
    val overview: String?,
    val stillUrl: String?,
    val airDate: String?,
    val runtimeMinutes: Int?,
)

/**
 * Minimal TMDB client used to enrich movies/series with ratings, overviews
 * and review excerpts. Requires a user-supplied API key (Settings).
 */
class TmdbClient(private val http: OkHttpClient, private val apiKey: String) {

    private val json = Json { ignoreUnknownKeys = true }

    private suspend fun get(url: String): JsonObject? = withContext(Dispatchers.IO) {
        runCatching {
            val request = Request.Builder().url(url).header("User-Agent", "Agoro/1.0").build()
            http.newCall(request).execute().use { resp ->
                if (!resp.isSuccessful) return@use null
                json.parseToJsonElement(resp.body!!.string()).jsonObject
            }
        }.getOrNull()
    }

    private fun JsonObject.str(key: String) = (this[key] as? JsonPrimitive)?.contentOrNull
    private fun JsonObject.dbl(key: String) = str(key)?.toDoubleOrNull()
    private fun JsonObject.int(key: String) = str(key)?.toDoubleOrNull()?.toInt()

    /**
     * The best match for a provider title, or null when TMDB simply has no
     * such title. Throws [Unreachable] when the request itself failed — the
     * two are different answers, and a caller that caches results must not
     * record an outage as "this film has no artwork".
     */
    private suspend fun searchFirst(kind: String, title: String, year: Int?): JsonObject? {
        // Provider titles arrive as "EN - Avengers (2019) 4K HEVC" — searched
        // verbatim, TMDB finds nothing and the details pane silently stays
        // bare. Search with the cleaned title; if the year makes the search
        // too narrow (release-date mismatches are common), retry without it.
        val cleaned = searchTitle(title).ifBlank { title }
        val effectiveYear = year ?: yearIn(title)
        val q = URLEncoder.encode(cleaned, "UTF-8")
        val yearParam = effectiveYear?.let {
            if (kind == "movie") "&year=$it" else "&first_air_date_year=$it"
        } ?: ""
        var search = get(
            "https://api.themoviedb.org/3/search/$kind?api_key=$apiKey&query=$q$yearParam"
        ) ?: throw Unreachable()
        if ((search["results"] as? JsonArray).isNullOrEmpty() && yearParam.isNotEmpty()) {
            search = get(
                "https://api.themoviedb.org/3/search/$kind?api_key=$apiKey&query=$q"
            ) ?: throw Unreachable()
        }
        return (search["results"] as? JsonArray)?.firstOrNull() as? JsonObject
    }

    /**
     * Poster and backdrop only — the cheap half of [lookup], one request
     * instead of two, for filling a catalogue whose provider shipped no art.
     * An empty result means TMDB has no such title and is worth remembering;
     * [Unreachable] means ask again later.
     */
    suspend fun art(kind: String, title: String, year: Int?): ArtEntry {
        val first = searchFirst(kind, title, year) ?: return ArtEntry.empty
        return ArtEntry(poster = first.posterUrl(), backdrop = first.backdropUrl())
    }

    /** kind: "movie" or "tv". */
    suspend fun lookup(kind: String, title: String, year: Int?): TmdbInfo? {
        val first = runCatching { searchFirst(kind, title, year) }.getOrNull() ?: return null
        val id = first.int("id") ?: return null

        val reviews = get("https://api.themoviedb.org/3/$kind/$id/reviews?api_key=$apiKey")
            ?.let { root ->
                (root["results"] as? JsonArray).orEmpty().mapNotNull { el ->
                    val obj = el as? JsonObject ?: return@mapNotNull null
                    val author = obj.str("author") ?: "Anonymous"
                    // Through ReviewText, not a whitespace collapse: these are
                    // web-form bodies full of tags, markdown and the URL of
                    // the blog they were copied from, and all of it used to
                    // reach the screen verbatim.
                    val content = ReviewText.clean(obj.str("content"))
                        ?: return@mapNotNull null
                    "$author — $content"
                }.take(3)
            } ?: emptyList()

        // TV credits list directors per-episode, so for series this usually
        // yields cast only — the provider's director field fills that gap.
        val credits = get("https://api.themoviedb.org/3/$kind/$id/credits?api_key=$apiKey")
        val cast = (credits?.get("cast") as? JsonArray).orEmpty()
            .mapNotNull { (it as? JsonObject)?.str("name") }
            .take(6)
            .takeIf { it.isNotEmpty() }
            ?.joinToString(", ")
        val director = (credits?.get("crew") as? JsonArray).orEmpty()
            .filterIsInstance<JsonObject>()
            .filter { it.str("job") == "Director" }
            .mapNotNull { it.str("name") }
            .distinct()
            .take(2)
            .takeIf { it.isNotEmpty() }
            ?.joinToString(", ")

        return TmdbInfo(
            rating = first.dbl("vote_average")?.takeIf { it > 0 },
            voteCount = first.int("vote_count"),
            overview = first.str("overview")?.takeIf { it.isNotBlank() },
            posterUrl = first.posterUrl(),
            backdropUrl = first.backdropUrl(),
            reviews = reviews,
            cast = cast,
            director = director,
            tmdbId = id,
        )
    }

    /**
     * One season's episodes, keyed by episode number.
     *
     * The whole cost of episode metadata: `/tv/{id}/season/{n}` answers with
     * the ENTIRE season — name, overview, still, air date and runtime for
     * every episode — so a season a viewer opens is one round trip, not one
     * per row.
     *
     * [tvId] is the panel's own id wherever it sent one, so there is no
     * search step and no chance of merging another show's episode names into
     * this one's rows.
     *
     * Unlike [art] this does NOT distinguish "TMDB has no such season" from
     * "TMDB could not be reached": both answer empty. That distinction earns
     * its keep in [art] because art answers are written to disk forever,
     * where recording an outage as a miss is permanent. These are held in
     * memory for the session, so a conflated outage costs panel-only rows
     * until the next launch — and in exchange this is capped at one request
     * per (show, season) per session, with no retry path to storm from.
     */
    suspend fun season(tvId: Int, season: Int): Map<Int, TmdbEpisode> {
        val root = get("https://api.themoviedb.org/3/tv/$tvId/season/$season?api_key=$apiKey")
            ?: return emptyMap()
        return (root["episodes"] as? JsonArray).orEmpty()
            .filterIsInstance<JsonObject>()
            .mapNotNull { obj ->
                val num = obj.int("episode_number") ?: return@mapNotNull null
                num to TmdbEpisode(
                    episodeNum = num,
                    name = obj.str("name")?.takeIf { it.isNotBlank() },
                    overview = obj.str("overview")?.takeIf { it.isNotBlank() },
                    stillUrl = obj.stillUrl(),
                    airDate = EpisodeFacts.airDate(obj.str("air_date")),
                    runtimeMinutes = obj.int("runtime")?.takeIf { it > 0 },
                )
            }
            .toMap()
    }

    // w500 upscaled into a 220x330dp poster on a 4K panel is visibly soft; the
    // backdrop fills 70% of the screen, so it gets the original. These are the
    // sizes TMDB serves for TV-sized layouts.
    private fun JsonObject.posterUrl() =
        str("poster_path")?.let { "https://image.tmdb.org/t/p/w780$it" }

    private fun JsonObject.backdropUrl() =
        str("backdrop_path")?.let { "https://image.tmdb.org/t/p/original$it" }

    // Through ArtworkUrl, which owns STILL_SIZE, rather than pasting a rung
    // here: two places naming the size is two places to drift, and the shape
    // mistake ArtworkUrl documents cost every episode row in the app once.
    private fun JsonObject.stillUrl() =
        str("still_path")?.let { ArtworkUrl.still("https://image.tmdb.org/t/p/original$it") }

    /** TMDB could not be reached. Distinct from "TMDB has no such title". */
    class Unreachable : java.io.IOException("TMDB unreachable")

    companion object {
        // Release/quality/codec noise that never belongs in a search query.
        private val junk = Regex(
            """(?i)\b(4k|uhd|fhd|full\s?hd|hd|sd|2160p|1080p|720p|480p|576p|hevc|h\.?26[45]|""" +
                """x26[45]|10\s?bit|hdr10?\+?|dolby\s?vision|dv|web[-\s]?dl|webrip|bluray|""" +
                """blu-ray|brrip|dvdrip|remux|multi|vostfr|dubbed|subbed|vod)\b"""
        )

        // "[EN]", "|FR|", "(MULTI)" style tags anywhere in the name.
        private val bracketTags = Regex("""[\[|(][^\])|]{0,20}[\])|]""")

        // "EN - ", "FR| ", "NL: " style prefixes.
        private val langPrefix = Regex("""^\s*[A-Z]{2,3}\s*[-:|•]\s*""")

        private val yearToken = Regex("""\b(19|20)\d{2}\b""")

        /** The four-digit year buried in a raw provider title, if any. */
        fun yearIn(rawTitle: String): Int? =
            yearToken.find(rawTitle)?.value?.toIntOrNull()

        /** Raw provider title reduced to something TMDB can actually match. */
        fun searchTitle(rawTitle: String): String =
            rawTitle
                // A bracket group carrying the year would erase it before
                // yearIn has run at the call site — the year is read from the
                // raw title, so stripping here is safe.
                .replace(bracketTags, " ")
                .replace(langPrefix, "")
                .replace(junk, " ")
                .replace(yearToken, " ")
                .replace(Regex("""\s[-–—:|•]+\s"""), " ")
                .replace(Regex("""\s{2,}"""), " ")
                .trim()
                .trimEnd('-', ':', '|', '•')
                .trim()
    }
}
