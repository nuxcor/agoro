package com.agoro.tv.data

/**
 * Two sources for one episode row, and the rule for which of them wins.
 *
 * The panel is the source of record, because the panel is what the stream
 * actually IS: it knows this file runs 47 minutes because it measured it, and
 * it knows this episode is the one behind this URL. TMDB knows what the
 * episode is CALLED and what a frame of it looks like — which is the half the
 * panel usually leaves blank.
 *
 * So every rule here fills a hole and none of them prefers a source. That
 * asymmetry is the whole design: a "better source wins" rule would eventually
 * paint TMDB's runtime for a 22-minute broadcast cut over the panel's
 * 47-minute one, on a row whose Play button starts the 47-minute file.
 */
object EpisodeFacts {

    /**
     * One season's episodes with every hole TMDB can fill, filled.
     *
     * Returns the SAME list when nothing moved. The caller assigns this into
     * Compose state, and a new-but-equal list there is a whole season of rows
     * recomposed to draw exactly what they were already drawing.
     */
    fun fill(
        episodes: List<Episode>,
        season: Int,
        tmdb: Map<Int, TmdbEpisode>,
    ): List<Episode> {
        if (tmdb.isEmpty()) return episodes
        var changed = false
        val out = episodes.map { episode ->
            if (episode.season != season) return@map episode
            fill(episode, tmdb[episode.episodeNum]).also { if (it !== episode) changed = true }
        }
        return if (changed) out else episodes
    }

    /**
     * One episode, panel first.
     *
     * The single place a TMDB value displaces a panel value is artwork from
     * the mirror that paints "4K UltraHD" banners across everything it serves
     * — the same exception the detail pages already make, for the same
     * reason. See [ArtworkUrl.isDoctored].
     */
    fun fill(episode: Episode, tmdb: TmdbEpisode?): Episode {
        if (tmdb == null) return episode

        // Blank, not null: ContentRepository.cleanTitles stores "" for an
        // episode the panel named nothing, so "" is the hole. A title that
        // survived cleaning is a name the provider chose, and stands.
        //
        // TMDB's own name goes back through EpisodeTitle for one reason: TMDB
        // fills unnamed episodes with the literal "Episode 5", and stored raw
        // that reaches the row as "5. Episode 5".
        val title = episode.title.takeIf { it.isNotBlank() }
            ?: tmdb.name?.let { EpisodeTitle.clean(it, seriesName = null) }
            ?: episode.title

        val poster = when {
            episode.poster == null -> tmdb.stillUrl
            ArtworkUrl.isDoctored(episode.poster) -> tmdb.stillUrl ?: episode.poster
            else -> episode.poster
        }

        // Through PlotText like every other synopsis in the app. TMDB's are
        // single-language, but the function is idempotent and the rule is
        // "every plot that reaches a screen has been through it".
        val plot = episode.plot?.takeIf { it.isNotBlank() }
            ?: PlotText.preferred(tmdb.overview)

        val runtime = episode.runtimeMinutes ?: tmdb.runtimeMinutes
        val air = episode.airDate ?: tmdb.airDate

        return if (
            title == episode.title && poster == episode.poster &&
            plot == episode.plot && runtime == episode.runtimeMinutes &&
            air == episode.airDate
        ) {
            episode
        } else {
            episode.copy(
                title = title,
                poster = poster,
                plot = plot,
                runtimeMinutes = runtime,
                airDate = air,
            )
        }
    }

    /**
     * A date normalised to "YYYY-MM-DD", or null.
     *
     * Panels write "2024-03-12", "2024-03-12 00:00:00", "" and "0000-00-00"
     * into the same field, and TMDB writes "" for an episode with no
     * announced date. Only the shape survives, and only with a year that is
     * a year — "0000-00-00" formatted as a date reads as a rendering fault.
     */
    fun airDate(raw: String?): String? {
        val head = raw?.trim()?.substringBefore(' ')?.substringBefore('T') ?: return null
        if (head.length != 10 || head[4] != '-' || head[7] != '-') return null
        val year = head.substring(0, 4).toIntOrNull() ?: return null
        val month = head.substring(5, 7).toIntOrNull() ?: return null
        val day = head.substring(8, 10).toIntOrNull() ?: return null
        // 1900 rather than 0: a broadcast date before that is a corrupt field,
        // not a very old programme.
        if (year < 1900 || month !in 1..12 || day !in 1..31) return null
        return head
    }

    /**
     * Whole minutes out of anything a panel calls a duration: "02:01:00",
     * "42:00", "42".
     *
     * Null for "00:00:00" and for anything unparseable — that is the rule
     * `prettyDuration` already had, moved here so the parser gets it too.
     * Seconds round to the nearest minute.
     */
    fun minutes(raw: String?): Int? {
        val text = raw?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val parts = text.split(':')
        val total = when (parts.size) {
            // A bare number is minutes, not seconds: it is what panels write
            // in `episode_run_time`, and reading "42" as 42 seconds would put
            // a runtime of 1m on every episode of the shows that use it.
            1 -> parts[0].toIntOrNull()?.takeIf { it >= 0 }?.let { it * 60 }
            2 -> {
                val m = parts[0].toIntOrNull() ?: return null
                val s = parts[1].toIntOrNull() ?: return null
                m * 60 + s
            }
            3 -> {
                val h = parts[0].toIntOrNull() ?: return null
                val m = parts[1].toIntOrNull() ?: return null
                val s = parts[2].toIntOrNull() ?: return null
                h * 3600 + m * 60 + s
            }
            else -> null
        } ?: return null
        if (total <= 0) return null
        return ((total + 30) / 60).takeIf { it > 0 }
    }

    /** Whole minutes out of an Xtream `duration_secs`. */
    fun minutesOfSeconds(raw: String?): Int? {
        val secs = raw?.trim()?.toDoubleOrNull()?.toInt() ?: return null
        if (secs <= 0) return null
        return ((secs + 30) / 60).takeIf { it > 0 }
    }
}
