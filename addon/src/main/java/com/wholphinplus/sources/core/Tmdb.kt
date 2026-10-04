// Contains code adapted from a third-party project under the Apache License 2.0 and modified
// for Orca+. See NOTICE and LICENSES/Apache-2.0.txt.
package com.wholphinplus.sources.core

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.text.Normalizer
import java.util.Locale

enum class TmdbType { MOVIE, TV }

data class TmdbItem(
    val id: Int,
    val type: TmdbType,
    val title: String,
    val year: Int?,
    val overview: String,
    val posterPath: String?,
    val backdropPath: String?,
    val rating: Double,
    val popularity: Double,
    val voteCount: Int,
) {
    val key: String get() = "${type.name}:$id"

    fun posterUrl(width: Int = 342): String? = posterPath?.let { "https://image.tmdb.org/t/p/w$width$it" }

    fun backdropUrl(width: Int = 1280): String? = backdropPath?.let { "https://image.tmdb.org/t/p/w$width$it" }
}

data class TmdbPerson(
    val id: Int,
    val name: String,
    val knownFor: List<TmdbItem>,
)

data class TmdbSearch(
    val items: List<TmdbItem>,
    val people: List<TmdbPerson>,
    /** Set for smart queries ("Best Horror Movies", "Similar to …"): the row title. */
    val interpretation: String? = null,
)

data class TmdbSeason(
    val number: Int,
    val name: String,
    val episodeCount: Int,
)

data class TmdbEpisode(
    val season: Int,
    val number: Int,
    val name: String,
    val overview: String,
    val stillPath: String?,
)

data class TmdbIds(
    val imdb: String?,
    val tvdb: Int?,
)

class TmdbClient(
    private val http: OkHttpClient,
    private val apiKey: () -> String,
    private val language: String = "en-US",
) {
    private val json = Json { ignoreUnknownKeys = true }

    val hasKey: Boolean get() = apiKey().isNotBlank()

    /** Smart browse queries, else multi search ranked by title match. */
    suspend fun search(query: String): TmdbSearch {
        val q = query.trim()
        if (q.isEmpty()) return TmdbSearch(emptyList(), emptyList())
        SmartQuery.parse(q)?.let { return smart(it) }
        val response = get("search/multi", "query" to q, "include_adult" to "false")
        val results = response.objects("results")
        val items =
            results
                .filter { it.string("media_type") in setOf("movie", "tv") }
                .mapNotNull { it.toItem(if (it.string("media_type") == "tv") TmdbType.TV else TmdbType.MOVIE) }
        val people =
            results
                .filter { it.string("media_type") == "person" && it.string("name").isNotBlank() }
                .sortedByDescending { it.string("popularity").toDoubleOrNull() ?: 0.0 }
                .take(3)
                .map { p ->
                    val known =
                        p
                            .objects("known_for")
                            .filter { it.string("poster_path").isNotBlank() }
                            .mapNotNull { it.toItem(if (it.string("media_type") == "tv") TmdbType.TV else TmdbType.MOVIE) }
                    val credits = if (known.size >= 3) known else runCatching { personCredits(p.int("id") ?: 0) }.getOrDefault(known)
                    TmdbPerson(p.int("id") ?: 0, p.string("name"), credits)
                }.filter { it.knownFor.isNotEmpty() }
        return TmdbSearch(rank(q, items), people)
    }

    private fun personCredits(personId: Int): List<TmdbItem> =
        get("person/$personId/combined_credits")
            .objects("cast")
            .filter { it.string("poster_path").isNotBlank() }
            .mapNotNull { it.toItem(if (it.string("media_type") == "tv") TmdbType.TV else TmdbType.MOVIE) }
            .distinctBy { it.key }
            .sortedWith(compareByDescending<TmdbItem> { it.voteCount }.thenByDescending { it.popularity })
            .take(20)

    private suspend fun smart(sq: SmartQuery): TmdbSearch {
        val items =
            if (sq.similarTo != null) {
                val first = get("search/multi", "query" to sq.similarTo).objects("results").firstOrNull { it.string("media_type") in setOf("movie", "tv") }
                if (first == null) {
                    emptyList()
                } else {
                    val type = if (first.string("media_type") == "tv") "tv" else "movie"
                    get("$type/${first.int("id")}/recommendations").objects("results").mapNotNull {
                        it.toItem(if (type == "tv") TmdbType.TV else TmdbType.MOVIE)
                    }
                }
            } else {
                coroutineScope {
                    val movies = if (sq.movies) async { discover(TmdbType.MOVIE, sq) } else null
                    val tv = if (sq.tv) async { discover(TmdbType.TV, sq) } else null
                    interleave(movies?.await().orEmpty(), tv?.await().orEmpty())
                }
            }
        return TmdbSearch(sq.limit?.let { items.take(it) } ?: items, emptyList(), sq.interpretation)
    }

    private fun discover(
        type: TmdbType,
        sq: SmartQuery,
    ): List<TmdbItem> {
        val path = if (type == TmdbType.TV) "discover/tv" else "discover/movie"
        val genre = if (type == TmdbType.TV) sq.genreId?.let(SmartQuery::tvGenre) else sq.genreId
        val sort =
            if (type == TmdbType.TV) {
                sq.sort.replace("primary_release_date", "first_air_date")
            } else {
                sq.sort
            }
        val params =
            buildList {
                add("sort_by" to sort)
                genre?.let { add("with_genres" to it) }
                if (sq.anime) add("with_keywords" to "210024")
                sq.minVotes?.let { add("vote_count.gte" to it.toString()) }
                if (sort.contains("date")) add((if (type == TmdbType.TV) "first_air_date.lte" else "primary_release_date.lte") to java.time.LocalDate.now().toString())
            }
        return get(path, *params.toTypedArray()).objects("results").mapNotNull { it.toItem(type) }
    }

    fun externalIds(item: TmdbItem): TmdbIds {
        val o = get("${if (item.type == TmdbType.TV) "tv" else "movie"}/${item.id}/external_ids")
        return TmdbIds(o.string("imdb_id").ifBlank { null }, o.int("tvdb_id"))
    }

    fun seasons(tvId: Int): List<TmdbSeason> =
        get("tv/$tvId")
            .objects("seasons")
            .mapNotNull { s ->
                val n = s.int("season_number") ?: return@mapNotNull null
                TmdbSeason(n, s.string("name").ifBlank { "Season $n" }, s.int("episode_count") ?: 0)
            }.sortedBy { if (it.number == 0) Int.MAX_VALUE else it.number } // specials last

    fun episodes(
        tvId: Int,
        season: Int,
    ): List<TmdbEpisode> =
        get("tv/$tvId/season/$season").objects("episodes").mapNotNull { e ->
            TmdbEpisode(
                season = season,
                number = e.int("episode_number") ?: return@mapNotNull null,
                name = e.string("name"),
                overview = e.string("overview"),
                stillPath = e.string("still_path").ifBlank { null },
            )
        }

    private fun JsonObject.toItem(type: TmdbType): TmdbItem? {
        val id = int("id") ?: return null
        val title = (if (type == TmdbType.TV) string("name") else string("title")).ifBlank { return null }
        val date = if (type == TmdbType.TV) string("first_air_date") else string("release_date")
        return TmdbItem(
            id = id,
            type = type,
            title = title,
            year = date.take(4).toIntOrNull(),
            overview = string("overview"),
            posterPath = string("poster_path").ifBlank { null },
            backdropPath = string("backdrop_path").ifBlank { null },
            rating = string("vote_average").toDoubleOrNull() ?: 0.0,
            popularity = string("popularity").toDoubleOrNull() ?: 0.0,
            voteCount = int("vote_count") ?: 0,
        )
    }

    private fun get(
        path: String,
        vararg params: Pair<String, String>,
    ): JsonObject {
        val key = apiKey().ifBlank { error("No TMDB API key") }
        val url =
            "https://api.themoviedb.org/3/$path"
                .toHttpUrl()
                .newBuilder()
                .addQueryParameter("api_key", key)
                .addQueryParameter("language", language)
                .apply { params.forEach { (k, v) -> addQueryParameter(k, v) } }
                .build()
        http.newCall(Request.Builder().url(url).header("Accept", "application/json").build()).execute().use { r ->
            if (!r.isSuccessful) throw ServerRequestException(r.code, "TMDB answered HTTP ${r.code}")
            return json.parseToJsonElement(r.body.string()) as JsonObject
        }
    }

    companion object {
        private val accents = Regex("\\p{M}+")
        private val punctuation = Regex("[^\\p{L}\\p{N}]+")
        private val leadingArticle = Regex("^(the|an|a) ")

        private fun normalize(value: String): String =
            Normalizer
                .normalize(value, Normalizer.Form.NFD)
                .replace(accents, "")
                .lowercase(Locale.ROOT)
                .replace("&", " and ")
                .replace("'", "")
                .replace("’", "")
                .replace(punctuation, " ")
                .trim()

        /** Title-match tiers, keeping TMDB's relevance order within a tier. */
        fun rank(
            query: String,
            items: List<TmdbItem>,
        ): List<TmdbItem> {
            val q = normalize(query)
            val words = q.split(' ').filter { it.isNotEmpty() }
            return items.distinctBy { it.key }.sortedBy { item ->
                val t = normalize(item.title)
                when {
                    t == q -> 0
                    t.replace(leadingArticle, "") == q.replace(leadingArticle, "") -> 1
                    t.startsWith("$q ") -> 2
                    " $t ".contains(" $q ") -> 3
                    words.isNotEmpty() && words.all { it in t.split(' ') } -> 4
                    t.startsWith(q) -> 5
                    t.contains(q) -> 6
                    else -> 7
                }
            }
        }

        private fun interleave(
            a: List<TmdbItem>,
            b: List<TmdbItem>,
        ): List<TmdbItem> =
            buildList {
                for (i in 0 until maxOf(a.size, b.size)) {
                    a.getOrNull(i)?.let(::add)
                    b.getOrNull(i)?.let(::add)
                }
            }
    }
}

/** Smart browse queries: "top 10 horror movies", "new anime", "shows like Severance". */
internal data class SmartQuery(
    val interpretation: String,
    val movies: Boolean,
    val tv: Boolean,
    val anime: Boolean,
    val genreId: String?,
    val sort: String,
    val minVotes: Int?,
    val limit: Int?,
    val similarTo: String?,
) {
    companion object {
        private val similar = Regex("(?:movies?|shows?|series|films?)\\s+like\\s+(.+)", RegexOption.IGNORE_CASE)
        private val smart =
            Regex(
                "(?:top(?:\\s+\\d+)?(?:\\s+rated)?|best|popular|trending|new|latest)" +
                    "(?:\\s+(?:horror|comedy|action|drama|thriller|sci-fi|science fiction|romance|animation|" +
                    "documentary|crime|fantasy|adventure|mystery|war|western|family|history))?" +
                    "\\s+(?:movies?|tv shows?|shows|series|films?|anime)",
                RegexOption.IGNORE_CASE,
            )
        private val limitRegex = Regex("top\\s+(\\d+)")
        private val genres =
            linkedMapOf(
                "horror" to "27", "comedy" to "35", "action" to "28", "drama" to "18", "thriller" to "53",
                "sci-fi" to "878", "science fiction" to "878", "romance" to "10749", "animation" to "16",
                "documentary" to "99", "crime" to "80", "fantasy" to "14", "adventure" to "12",
                "mystery" to "9648", "war" to "10752", "western" to "37", "family" to "10751", "history" to "36",
            )

        /** Movie genre ids that differ on TMDB's TV side. */
        fun tvGenre(movieGenre: String): String =
            when (movieGenre) {
                "28", "12" -> "10759" // Action & Adventure
                "878", "14" -> "10765" // Sci-Fi & Fantasy
                "10752" -> "10768" // War & Politics
                "53", "27" -> "9648" // closest TV equivalent: Mystery
                else -> movieGenre
            }

        fun parse(raw: String): SmartQuery? {
            val q = raw.lowercase(Locale.ROOT).trim()
            similar.matchEntire(q)?.let { m ->
                val title = m.groupValues[1].trim()
                val tv = q.startsWith("show") || q.startsWith("series")
                return SmartQuery("Similar to \"${title.replaceFirstChar { it.uppercase() }}\"", !tv, tv, false, null, "popularity.desc", null, null, title)
            }
            if (!smart.matches(q)) return null
            val genre = genres.entries.firstOrNull { q.contains(it.key) }
            val anime = q.contains("anime")
            val isTv = q.contains("show") || q.contains("series")
            val isMovie = q.contains("movie") || q.contains("film")
            val limit = limitRegex.find(q)?.groupValues?.get(1)?.toIntOrNull()
            val sort =
                when {
                    q.contains("best") || q.contains("top rated") || limit != null -> "vote_average.desc"
                    q.contains("new") || q.contains("latest") -> "primary_release_date.desc"
                    else -> "popularity.desc"
                }
            val parts = mutableListOf<String>()
            when {
                limit != null -> parts += "Top $limit"
                sort == "vote_average.desc" -> parts += "Best"
                sort.contains("date") -> parts += "Newest"
                else -> parts += "Popular"
            }
            genre?.let { parts += it.key.replaceFirstChar { c -> c.uppercase() } }
            parts +=
                when {
                    anime -> "Anime"
                    isTv && !isMovie -> "Series"
                    isMovie && !isTv -> "Movies"
                    else -> "Movies & Series"
                }
            return SmartQuery(
                interpretation = parts.joinToString(" "),
                movies = !anime && (isMovie || !isTv),
                tv = anime || isTv || !isMovie,
                anime = anime,
                genreId = genre?.value,
                sort = sort,
                minVotes = if (sort == "vote_average.desc") 500 else null,
                limit = limit,
                similarTo = null,
            )
        }
    }
}
