package com.wholphinplus.sources.core

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.text.Normalizer
import java.time.LocalDate
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.cancellation.CancellationException

private const val IMAGES = "https://image.tmdb.org/t/p/w"

enum class TmdbType {
    MOVIE,
    TV,
}

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

    fun posterUrl(width: Int = 342): String? = posterPath?.let { "$IMAGES$width$it" }

    fun backdropUrl(width: Int = 1280): String? = backdropPath?.let { "$IMAGES$width$it" }
}

data class TmdbPerson(
    val id: Int,
    val name: String,
    val knownFor: List<TmdbItem>,
)

/** [interpretation] is set for smart queries and shown as the row title. */
data class TmdbSearch(
    val items: List<TmdbItem>,
    val people: List<TmdbPerson>,
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

/** Artwork and facts for one title. Cached as JSON on the device, so keep the field names. */
@Serializable
data class TitleArt(
    val logo: String? = null,
    val titledBackdrop: String? = null,
    val cleanBackdrop: String? = null,
    val services: List<String> = emptyList(),
    val tmdbScore: Double? = null,
    val released: String? = null,
) {
    fun logoUrl(): String? = logo?.let { "${IMAGES}500$it" }

    // Cards are drawn ~416 px wide: 500 px is enough (w780 was ~2x the bytes)
    fun cardUrl(): String? = titledBackdrop?.let { "${IMAGES}500$it" }

    fun cleanCardUrl(): String? = cleanBackdrop?.let { "${IMAGES}500$it" }

    fun serviceUrls(): List<String> = services.map { "${IMAGES}154$it" }
}

/**
 * A title's facts for Description tags (Settings → Home & Look): TMDB's details, plus the director
 * when asked for. Money in US dollars, 0 when TMDB doesn't know it.
 */
@kotlinx.serialization.Serializable
data class TitleFacts(
    val tagline: String = "",
    val budget: Long = 0,
    val revenue: Long = 0,
    val studio: String = "",
    val language: String = "",
    val status: String = "",
    val seasons: Int = 0,
    val episodes: Int = 0,
    val makers: List<String> = emptyList(),
)

/** One of a title's cast ([cast]). */
data class TmdbCastMember(
    val id: Int,
    val name: String,
    val character: String,
    val profilePath: String?,
) {
    fun profileUrl(width: Int = 185): String? = profilePath?.let { "$IMAGES$width$it" }
}

/** An actor's page ([person]). Dates as TMDB writes them ("1974-01-30"). */
data class TmdbPersonDetails(
    val id: Int,
    val name: String,
    val biography: String,
    val department: String,
    val birthday: String?,
    val deathday: String?,
    val placeOfBirth: String?,
    val profilePath: String?,
) {
    /** A portrait for the actor's page (TMDB's profile sizes are w45, w185, h632). */
    fun portraitUrl(): String? = profilePath?.let { "https://image.tmdb.org/t/p/h632$it" }
}

data class TmdbIds(
    val imdb: String?,
    val tvdb: Int?,
)

/**
 * TMDB v3. With the user's own key it calls TMDB directly; without one it goes through
 * [proxy], which adds a key on the server side. [apiKey] is read on every request.
 */
class TmdbClient(
    private val http: OkHttpClient,
    private val apiKey: () -> String,
    private val language: String = "en-US",
    private val proxy: String? = null,
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val networkLogos = ConcurrentHashMap<Int, String>()

    val hasKey: Boolean get() = apiKey().isNotBlank()

    val available: Boolean get() = hasKey || proxy != null

    suspend fun search(query: String): TmdbSearch {
        val q = query.trim()
        if (q.isEmpty()) return TmdbSearch(emptyList(), emptyList())
        SmartQuery.parse(q)?.let { return runSmart(it) }

        // Titles like WALL·E use a middle dot where people type a dash: search both, dotted first
        val results =
            coroutineScope {
                val dotted =
                    if ('-' in q) {
                        async {
                            try {
                                multiSearch(q.replace('-', '·'))
                            } catch (e: CancellationException) {
                                throw e
                            } catch (e: Exception) {
                                emptyList()
                            }
                        }
                    } else {
                        null
                    }
                val plain = async { multiSearch(q) }
                dotted?.await().orEmpty() + plain.await()
            }.distinctBy { it.string("media_type") to it.string("id") }

        val items =
            results
                .filter { it.string("media_type") == "movie" || it.string("media_type") == "tv" }
                .mapNotNull { toItem(it, mixedType(it)) }

        val topPeople =
            results
                .filter { it.string("media_type") == "person" && it.string("name").isNotBlank() }
                .sortedByDescending { it.double("popularity") }
                .take(3)
        val people =
            coroutineScope {
                topPeople
                    .map { p ->
                        async {
                            val knownFor =
                                p
                                    .objects("known_for")
                                    .filter { it.string("poster_path").isNotBlank() }
                                    .mapNotNull { toItem(it, mixedType(it)) }
                            val titles =
                                if (knownFor.size < 3) {
                                    try {
                                        credits(p.int("id"))
                                    } catch (e: CancellationException) {
                                        throw e
                                    } catch (e: Exception) {
                                        knownFor
                                    }
                                } else {
                                    knownFor
                                }
                            TmdbPerson(p.int("id") ?: 0, p.string("name"), titles)
                        }
                    }.awaitAll()
            }.filter { it.knownFor.isNotEmpty() }

        return TmdbSearch(rank(q, items), people)
    }

    private fun multiSearch(text: String): List<JsonObject> = get("search/multi", "query" to text, "include_adult" to "false").objects("results")

    private fun credits(personId: Int?): List<TmdbItem> =
        get("person/$personId/combined_credits")
            .objects("cast")
            .filter { it.string("poster_path").isNotBlank() }
            .mapNotNull { toItem(it, mixedType(it)) }
            .distinctBy { it.key }
            .sortedWith(compareByDescending<TmdbItem> { it.voteCount }.thenByDescending { it.popularity })
            .take(20)

    /**
     * A title's cast, billing order. A show's from its aggregate credits (everyone across its
     * seasons, the most-seen first), each with the part they played most.
     */
    fun cast(
        tv: Boolean,
        id: Int,
    ): List<TmdbCastMember> =
        if (tv) {
            get("tv/$id/aggregate_credits").objects("cast").mapNotNull { o ->
                val pid = o.int("id") ?: return@mapNotNull null
                val role = o.objects("roles").maxByOrNull { it.int("episode_count") ?: 0 }?.string("character").orEmpty()
                TmdbCastMember(pid, o.string("name"), role, o.string("profile_path").ifBlank { null })
            }
        } else {
            get("movie/$id/credits").objects("cast").mapNotNull { o ->
                val pid = o.int("id") ?: return@mapNotNull null
                TmdbCastMember(pid, o.string("name"), o.string("character"), o.string("profile_path").ifBlank { null })
            }
        }.filter { it.name.isNotBlank() }

    fun person(id: Int): TmdbPersonDetails {
        val o = get("person/$id")
        return TmdbPersonDetails(
            id = id,
            name = o.string("name"),
            biography = o.string("biography"),
            department = o.string("known_for_department"),
            birthday = o.string("birthday").ifBlank { null },
            deathday = o.string("deathday").ifBlank { null },
            placeOfBirth = o.string("place_of_birth").ifBlank { null },
            profilePath = o.string("profile_path").ifBlank { null },
        )
    }

    /**
     * Everything [id] was in (acted, directed, wrote or created), most popular first. Talk, news
     * and awards shows (appearances as themselves) are left out.
     */
    fun personTitles(id: Int): List<TmdbItem> {
        val o = get("person/$id/combined_credits")
        val crewJobs = setOf("Director", "Screenplay", "Writer", "Creator", "Novel")
        val talk = setOf(10767, 10763)
        return (o.objects("cast") + o.objects("crew").filter { it.string("job") in crewJobs })
            .filter { c -> c.array("genre_ids").none { (it as? kotlinx.serialization.json.JsonPrimitive)?.content?.toIntOrNull() in talk } }
            .mapNotNull { toItem(it, mixedType(it)) }
            .distinctBy { it.key }
            .sortedByDescending { it.popularity }
    }

    /** A person's TMDB id from their name (an exact match among the people search finds), or null. */
    fun findPerson(name: String): Int? {
        val want = rankKey(name)
        return multiSearch(name)
            .filter { it.string("media_type") == "person" && rankKey(it.string("name")) == want }
            .maxByOrNull { it.double("popularity") }
            ?.int("id")
    }

    fun externalIds(item: TmdbItem): TmdbIds {
        val o = get("${segment(item.type)}/${item.id}/external_ids")
        return TmdbIds(imdb = o.string("imdb_id").ifBlank { null }, tvdb = o.int("tvdb_id"))
    }

    /** Recommendations, topped up with "similar" titles when TMDB has few. */
    fun recommendations(
        type: TmdbType,
        id: Int,
    ): List<TmdbItem> {
        val base = "${segment(type)}/$id"
        val recommended = get("$base/recommendations").objects("results").mapNotNull { toItem(it, type) }
        if (recommended.size >= 12) return recommended
        val similar =
            try {
                get("$base/similar").objects("results").mapNotNull { toItem(it, type) }
            } catch (e: Exception) {
                emptyList()
            }
        return (recommended + similar).distinctBy { it.key }
    }

    /** Logo, backdrops, streaming services, score and release date in one request. */
    fun titleArt(
        tv: Boolean,
        id: Int,
    ): TitleArt {
        // en for artwork with the title on it, null for artwork without text
        val o =
            get(
                "${if (tv) "tv" else "movie"}/$id",
                "append_to_response" to "images,watch/providers",
                "include_image_language" to "en,null",
            )
        val images = o.obj("images") ?: JsonObject(emptyMap())
        val logos = images.objects("logos")
        val backdrops = images.objects("backdrops")
        val english = { it: JsonObject -> it.string("iso_639_1") == "en" }

        val services =
            try {
                serviceLogos(o)
            } catch (e: Exception) {
                emptyList()
            }
        val votes = o.int("vote_count") ?: 0
        return TitleArt(
            logo = bestImage(logos.filter(english)) ?: bestImage(logos),
            titledBackdrop = bestImage(backdrops.filter(english)),
            cleanBackdrop = bestImage(backdrops.filter { it.string("iso_639_1").isBlank() }),
            services = services,
            tmdbScore = o.string("vote_average").toDoubleOrNull()?.takeIf { it > 0 && votes >= 10 },
            released = o.string(if (tv) "first_air_date" else "release_date").ifBlank { null },
        )
    }

    /**
     * [TitleFacts] in one request (a movie's director in a second, only when [director]): the
     * plain details call, which the cloud's TMDB proxy allows without appends.
     */
    fun facts(
        tv: Boolean,
        id: Int,
        director: Boolean,
    ): TitleFacts {
        val o = get("${if (tv) "tv" else "movie"}/$id")
        val makers =
            if (tv) {
                o.objects("created_by").map { it.string("name") }.filter { it.isNotBlank() }.take(2)
            } else if (director) {
                runCatching { get("movie/$id/credits").objects("crew").filter { it.string("job") == "Director" }.map { it.string("name") }.distinct().take(2) }.getOrDefault(emptyList())
            } else {
                emptyList()
            }
        return TitleFacts(
            tagline = o.string("tagline").trim(),
            budget = o.long("budget") ?: 0,
            revenue = o.long("revenue") ?: 0,
            // A show's own network, else the studio that made it
            studio = (if (tv) o.objects("networks").firstOrNull()?.string("name") else null)?.ifBlank { null } ?: o.objects("production_companies").firstOrNull()?.string("name").orEmpty(),
            language = o.string("original_language"),
            status = o.string("status"),
            seasons = o.int("number_of_seasons") ?: 0,
            episodes = o.int("number_of_episodes") ?: 0,
            makers = makers,
        )
    }

    /** Highest voted; the first one wins a tie. */
    private fun bestImage(list: List<JsonObject>): String? = list.maxByOrNull { it.double("vote_average") }?.string("file_path")

    /**
     * Up to two wordmarks: the show's own networks first, then the streaming services that carry
     * it here. Provider logos are square app icons, so each provider is shown as its TMDB network.
     */
    private fun serviceLogos(o: JsonObject): List<String> {
        val own =
            o
                .objects("networks")
                .filter { it.string("logo_path").isNotBlank() }
                .map { it.int("id") to it.string("logo_path") }

        val results = o.obj("watch/providers")?.obj("results")
        val country = Locale.getDefault().country.uppercase(Locale.ROOT).ifBlank { "US" }
        val region = results?.obj(country) ?: results?.obj("US")
        val streaming =
            region
                ?.objects("flatrate")
                .orEmpty()
                .sortedBy { it.int("display_priority") ?: Int.MAX_VALUE }
                .mapNotNull { p -> p.int("provider_id")?.let { PROVIDER_NETWORKS[it] } }
                .distinct()
                .mapNotNull { network -> networkLogo(network)?.let { network to it } }

        return (own + streaming).distinctBy { it.first }.map { it.second }.take(2)
    }

    private fun networkLogo(network: Int): String? {
        networkLogos[network]?.let { return it }
        val logo =
            try {
                get("network/$network").string("logo_path").ifBlank { null }
            } catch (e: Exception) {
                null
            } ?: return null
        networkLogos[network] = logo
        return logo
    }

    fun seasons(tvId: Int): List<TmdbSeason> =
        get("tv/$tvId")
            .objects("seasons")
            .mapNotNull { s ->
                val number = s.int("season_number") ?: return@mapNotNull null
                TmdbSeason(number, s.string("name").ifBlank { "Season $number" }, s.int("episode_count") ?: 0)
            }
            // Specials last
            .sortedWith(compareBy<TmdbSeason> { it.number == 0 }.thenBy { it.number })

    fun episodes(
        tvId: Int,
        season: Int,
    ): List<TmdbEpisode> =
        get("tv/$tvId/season/$season")
            .objects("episodes")
            .mapNotNull { e ->
                val number = e.int("episode_number") ?: return@mapNotNull null
                TmdbEpisode(
                    season = season,
                    number = number,
                    name = e.string("name"),
                    overview = e.string("overview"),
                    stillPath = e.string("still_path").ifBlank { null },
                )
            }

    // ---- Smart queries ----

    private suspend fun runSmart(query: SmartQuery): TmdbSearch {
        val found =
            if (query.similarTo != null) {
                similarTo(query.similarTo)
            } else {
                coroutineScope {
                    val movies = if (query.movies) async { discover(query, TmdbType.MOVIE) } else null
                    val shows = if (query.tv) async { discover(query, TmdbType.TV) } else null
                    alternate(movies?.await().orEmpty(), shows?.await().orEmpty())
                }
            }
        val items = query.limit?.let { found.take(it) } ?: found
        return TmdbSearch(items, emptyList(), query.interpretation)
    }

    /** The first title hit decides between movie and show, not the word in the query. */
    private fun similarTo(title: String): List<TmdbItem> {
        val hit =
            get("search/multi", "query" to title)
                .objects("results")
                .firstOrNull { it.string("media_type") == "movie" || it.string("media_type") == "tv" }
                ?: return emptyList()
        val type = mixedType(hit)
        val id = hit.int("id") ?: return emptyList()
        return get("${segment(type)}/$id/recommendations").objects("results").mapNotNull { toItem(it, type) }
    }

    private fun discover(
        query: SmartQuery,
        type: TmdbType,
    ): List<TmdbItem> {
        val tv = type == TmdbType.TV
        val sort = if (tv) query.sort.replace("primary_release_date", "first_air_date") else query.sort
        // Date sorts leave out titles that aren't out yet
        val today = if ("date" in sort) LocalDate.now().toString() else null
        return get(
            "discover/${segment(type)}",
            "sort_by" to sort,
            "with_genres" to query.genreId?.let { if (tv) SmartQuery.tvGenre(it) else it },
            // TMDB's anime keyword, so Western cartoons stay out
            "with_keywords" to if (query.anime) "210024" else null,
            "vote_count.gte" to query.minVotes?.toString(),
            (if (tv) "first_air_date.lte" else "primary_release_date.lte") to today,
        ).objects("results").mapNotNull { toItem(it, type) }
    }

    private fun alternate(
        first: List<TmdbItem>,
        second: List<TmdbItem>,
    ): List<TmdbItem> {
        val out = ArrayList<TmdbItem>(first.size + second.size)
        for (i in 0 until maxOf(first.size, second.size)) {
            first.getOrNull(i)?.let(out::add)
            second.getOrNull(i)?.let(out::add)
        }
        return out
    }

    // ---- Plumbing ----

    private fun get(
        path: String,
        vararg params: Pair<String, String?>,
    ): JsonObject {
        val key = apiKey()
        val base =
            when {
                key.isNotBlank() -> "https://api.themoviedb.org/3"
                proxy != null -> proxy
                else -> throw IllegalStateException("No TMDB API key")
            }
        val url =
            "$base/$path".toHttpUrl().newBuilder().apply {
                if (key.isNotBlank()) addQueryParameter("api_key", key)
                addQueryParameter("language", language)
                for ((name, value) in params) if (value != null) addQueryParameter(name, value)
            }
        val request =
            Request
                .Builder()
                .url(url.build())
                .header("Accept", "application/json")
                .get()
                .build()
        val body =
            http.newCall(request).execute().use { r ->
                if (!r.isSuccessful) throw ServerRequestException(r.code, "TMDB answered HTTP ${r.code}")
                r.body.string()
            }
        return json.parseToJsonElement(body) as? JsonObject ?: throw IllegalStateException("TMDB answer is not an object")
    }

    private fun segment(type: TmdbType): String = if (type == TmdbType.TV) "tv" else "movie"

    private fun mixedType(o: JsonObject): TmdbType = if (o.string("media_type") == "tv") TmdbType.TV else TmdbType.MOVIE

    private fun JsonObject.double(name: String): Double = string(name).toDoubleOrNull() ?: 0.0

    private fun toItem(
        o: JsonObject,
        type: TmdbType,
    ): TmdbItem? {
        val id = o.int("id") ?: return null
        val tv = type == TmdbType.TV
        val title = o.string(if (tv) "name" else "title").ifBlank { return null }
        return TmdbItem(
            id = id,
            type = type,
            title = title,
            year = o.string(if (tv) "first_air_date" else "release_date").take(4).toIntOrNull(),
            overview = o.string("overview"),
            posterPath = o.string("poster_path").ifBlank { null },
            backdropPath = o.string("backdrop_path").ifBlank { null },
            rating = o.double("vote_average"),
            popularity = o.double("popularity"),
            voteCount = o.int("vote_count") ?: 0,
        )
    }

    companion object {
        // TMDB watch provider id -> the TMDB network whose wordmark stands for it
        private val PROVIDER_NETWORKS: Map<Int, Int> =
            buildMap {
                fun map(
                    network: Int,
                    vararg providers: Int,
                ) = providers.forEach { put(it, network) }
                map(213, 8, 175, 1796)
                map(1024, 9, 119, 613, 2100)
                map(2552, 350, 2243)
                map(2739, 337, 508)
                map(3186, 1899, 384, 1825)
                map(453, 15)
                map(4330, 531, 2303, 2616, 582, 633, 1853)
                map(3353, 386, 387, 2553)
                map(1112, 283, 1968)
                map(174, 526, 528, 635, 1854, 80)
                map(318, 43, 1794, 1855, 634)
                map(359, 289, 2061)
            }

        private val marks = Regex("\\p{M}+")
        private val apostrophes = Regex("['’]")
        private val separators = Regex("[^\\p{L}\\p{N}]+")

        /** Keeps non-Latin letters and leading articles, unlike the server matcher's key. */
        internal fun rankKey(text: String): String =
            Normalizer
                .normalize(text, Normalizer.Form.NFD)
                .replace(marks, "")
                .lowercase(Locale.ROOT)
                .replace("&", " and ")
                .replace(apostrophes, "")
                .replace(separators, " ")
                .trim()

        private fun dropArticle(text: String): String {
            val article = listOf("the ", "an ", "a ").firstOrNull { text.startsWith(it) } ?: return text
            return text.removePrefix(article)
        }

        /** Exact title matches first, then looser ones; TMDB's own order inside each tier. */
        fun rank(
            query: String,
            items: List<TmdbItem>,
        ): List<TmdbItem> {
            val q = rankKey(query)
            val words = q.split(' ').filter { it.isNotEmpty() }
            fun tier(item: TmdbItem): Int {
                val t = rankKey(item.title)
                return when {
                    t == q -> 0
                    dropArticle(t) == dropArticle(q) -> 1
                    t.startsWith("$q ") -> 2
                    " $t ".contains(" $q ") -> 3
                    words.isNotEmpty() && t.split(' ').toSet().containsAll(words) -> 4
                    t.startsWith(q) -> 5
                    t.contains(q) -> 6
                    else -> 7
                }
            }
            return items.distinctBy { it.key }.sortedBy(::tier)
        }
    }
}

/** A browse request such as "top 10 horror movies" or "shows like Severance". */
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
        private const val BY_VOTES = "vote_average.desc"
        private const val BY_DATE = "primary_release_date.desc"
        private const val BY_POPULARITY = "popularity.desc"

        // In lookup order: the first name found in the query wins
        private val GENRES =
            listOf(
                "horror" to "27",
                "comedy" to "35",
                "action" to "28",
                "drama" to "18",
                "thriller" to "53",
                "sci-fi" to "878",
                "sci fi" to "878",
                "scifi" to "878",
                "science fiction" to "878",
                "romance" to "10749",
                "animation" to "16",
                "documentary" to "99",
                "crime" to "80",
                "fantasy" to "14",
                "adventure" to "12",
                "mystery" to "9648",
                "war" to "10752",
                "western" to "37",
                "family" to "10751",
                "history" to "36",
            )

        private val similar = Regex("(?:movies|movie|shows|show|series|films|film)\\s+like\\s+(.+)")
        private val browse =
            Regex(
                "(?:top(?:\\s+\\d+)?(?:\\s+rated)?|best|popular|trending|new|latest)" +
                    "(?:\\s+(?:" + GENRES.joinToString("|") { Regex.escape(it.first) } + "))?" +
                    "\\s+(?:movies|movie|tv shows|tv show|shows|series|films|film|anime)",
            )
        // A lead and a genre with no kind ("best sci-fi", "new horror"): movies and series both
        // (owner's sweep 2026-10-09: "best sci-fi" found nothing, "best sci-fi movies" worked)
        private val browseGenre =
            Regex(
                "(?:top(?:\\s+\\d+)?(?:\\s+rated)?|best|popular|trending|new|latest)" +
                    "\\s+(?:" + GENRES.joinToString("|") { Regex.escape(it.first) } + ")",
            )
        private val topCount = Regex("top\\s+(\\d+)")

        /** The whole query must match, so a title that merely contains "best" stays a normal search. */
        fun parse(query: String): SmartQuery? {
            val q = query.lowercase(Locale.ROOT).trim()

            similar.matchEntire(q)?.let { m ->
                val title = m.groupValues[1].trim()
                val tv = q.startsWith("show") || q.startsWith("series")
                return SmartQuery(
                    interpretation = "Similar to \"${title.capitalized()}\"",
                    movies = !tv,
                    tv = tv,
                    anime = false,
                    genreId = null,
                    sort = BY_POPULARITY,
                    minVotes = null,
                    limit = null,
                    similarTo = title,
                )
            }

            if (!browse.matches(q) && !browseGenre.matches(q)) return null
            val genre = GENRES.firstOrNull { it.first in q }
            val anime = "anime" in q
            val isTv = "show" in q || "series" in q
            val isMovie = "movie" in q || "film" in q
            val limit = topCount.find(q)?.groupValues?.get(1)?.toIntOrNull()
            val sort =
                when {
                    "best" in q || "top rated" in q || limit != null -> BY_VOTES
                    "new" in q || "latest" in q -> BY_DATE
                    else -> BY_POPULARITY
                }
            val lead =
                when {
                    limit != null -> "Top $limit"
                    sort == BY_VOTES -> "Best"
                    sort == BY_DATE -> "Newest"
                    else -> "Popular"
                }
            val what =
                when {
                    anime -> "Anime"
                    isTv && !isMovie -> "Series"
                    isMovie && !isTv -> "Movies"
                    else -> "Movies & Series"
                }
            return SmartQuery(
                interpretation = listOfNotNull(lead, genre?.first?.capitalized(), what).joinToString(" "),
                movies = !anime && (isMovie || !isTv),
                tv = anime || isTv || !isMovie,
                anime = anime,
                genreId = genre?.second,
                sort = sort,
                minVotes = if (sort == BY_VOTES) 500 else null,
                limit = limit,
                similarTo = null,
            )
        }

        /** TMDB's TV genres use other ids for some movie genres. */
        fun tvGenre(movieGenre: String): String =
            when (movieGenre) {
                "28", "12" -> "10759"
                "878", "14" -> "10765"
                "10752" -> "10768"
                "53", "27" -> "9648"
                else -> movieGenre
            }

        private fun String.capitalized(): String = replaceFirstChar { it.uppercaseChar() }
    }
}
