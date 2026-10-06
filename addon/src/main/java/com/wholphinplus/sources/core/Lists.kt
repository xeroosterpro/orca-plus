package com.wholphinplus.sources.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.Locale

/** One title on a Trakt/MDBList list. */
data class ListEntry(
    val type: TmdbType,
    val title: String,
    val year: Int?,
    val tmdbId: Int?,
    val imdbId: String?,
    val tvdbId: Int?,
) {
    val key: String get() = "${type.name}:" + (tmdbId?.toString() ?: imdbId ?: "${title.lowercase(Locale.US)}:$year")
}

data class FetchedList(
    val name: String,
    val entries: List<ListEntry>,
)

/** The kinds of list link we understand. */
sealed interface ListSource {
    val url: String

    data class MdbList(
        override val url: String,
        val user: String,
        val slug: String,
    ) : ListSource

    /** apiPath is relative to https://api.trakt.tv/, e.g. "users/bob/lists/scifi". */
    data class Trakt(
        override val url: String,
        val apiPath: String,
        val nameHint: String,
    ) : ListSource

    /** One Orca+ chart: {cloud}/v1/charts#{chart id} */
    data class OrcaChart(
        override val url: String,
        val base: String,
        val id: String,
    ) : ListSource

    /** One Top Streaming catalog: https://top-streaming.stream/{account}/catalog/{type}/{id}.json */
    data class TopStreaming(
        override val url: String,
    ) : ListSource {
        /** The id the Orca+ cloud gives this same catalog ("ts-{type}-{id}"), so your own row can stand in for it. */
        val chartId: String get() = url.split('/').takeLast(2).let { (type, file) -> "ts-$type-${file.removeSuffix(".json")}" }
    }

    companion object {
        /** Parse a list link from mdblist.com or trakt.tv; null if it isn't one. */
        fun parse(raw: String): ListSource? {
            val text = raw.trim().let { if (it.startsWith("http", true)) it else "https://$it" }
            val url = text.toHttpUrlOrNull() ?: return null
            val host = url.host.lowercase(Locale.US).removePrefix("www.")
            val seg = url.pathSegments.filter { it.isNotBlank() }
            return when {
                host == "mdblist.com" && seg.size >= 3 && seg[0] == "lists" ->
                    MdbList("https://mdblist.com/lists/${seg[1]}/${seg[2]}", seg[1], seg[2])

                host.endsWith("trakt.tv") && seg.size >= 4 && seg[0] == "users" && seg[2] == "lists" ->
                    Trakt("https://trakt.tv/users/${seg[1]}/lists/${seg[3]}", "users/${seg[1]}/lists/${seg[3]}", prettify(seg[3]))

                host.endsWith("trakt.tv") && seg.size >= 3 && seg[0] == "users" && seg[2] in setOf("watchlist", "favorites") ->
                    Trakt("https://trakt.tv/users/${seg[1]}/${seg[2]}", "users/${seg[1]}/${seg[2]}", "${seg[1]}'s ${seg[2]}")

                seg == listOf("v1", "charts") && !url.fragment.isNullOrBlank() && url.scheme == "https" ->
                    OrcaChart(text, text.substringBefore("/v1/charts"), url.fragment!!)

                host == TOP_STREAMING_HOST && seg.size == 4 && seg[1] == "catalog" && seg[3].endsWith(".json") ->
                    TopStreaming("https://$TOP_STREAMING_HOST/${seg[0]}/catalog/${seg[2]}/${seg[3]}")

                host.endsWith("trakt.tv") && seg.size >= 2 && seg[0] == "lists" ->
                    Trakt("https://trakt.tv/lists/${seg[1]}", "lists/${seg[1]}", prettify(seg[1]))

                else -> null
            }
        }

        const val TOP_STREAMING_HOST = "top-streaming.stream"

        fun prettify(slug: String): String =
            slug
                .split('-', '_')
                .filter { it.isNotBlank() }
                .joinToString(" ") { w -> w.replaceFirstChar { it.titlecase(Locale.US) } }
                .ifBlank { slug }
    }
}

/** Reads Trakt and MDBList lists. MDBList needs no key; Trakt needs the user's own client ID. */
class ListClient(
    private val http: OkHttpClient,
    private val traktClientId: () -> String,
) {
    private val json = Json { ignoreUnknownKeys = true }

    fun fetch(source: ListSource): FetchedList =
        when (source) {
            is ListSource.MdbList -> mdblist(source)
            is ListSource.Trakt -> trakt(source)
            is ListSource.TopStreaming -> topStreaming(source)
            is ListSource.OrcaChart -> FetchedList(source.id, chartsFrom(source.base).firstOrNull { it.id == source.id }?.entries ?: error("The Orca+ cloud no longer has this chart"))
        }

    /** An Orca+ chart, as the cloud serves it. */
    class Chart(
        val id: String,
        val url: String,
        val name: String,
        val kind: String?,
        val pages: List<String>,
        val order: Map<String, Int>,
        val source: String,
        val entries: List<ListEntry>,
        /** Shown only on a Services or Genres page, never offered as a row of its own. */
        val hidden: Boolean = false,
    )

    @Volatile private var chartsCache: Pair<Long, List<Chart>>? = null

    /** The Orca+ cloud's charts (remembered for half an hour, so one refresh asks once). */
    fun charts(): List<Chart> = chartsFrom(com.wholphinplus.sources.sync.ProfileSync.ENDPOINT)

    /** The Services and Genres pages and where each tab shows their tiles, from the same file as [charts]. */
    fun cloudPages(): CloudPages {
        charts()
        return pagesCache ?: CloudPages()
    }

    @Volatile private var pagesCache: CloudPages? = null

    @Synchronized
    private fun chartsFrom(base: String): List<Chart> {
        chartsCache?.takeIf { System.currentTimeMillis() - it.first < 30 * 60 * 1000 }?.let { return it.second }
        // The TV's country picks the rows that depend on it (services, new releases, Top 10s)
        val region = java.util.Locale.getDefault().country.uppercase(java.util.Locale.US).ifBlank { "US" }
        val raw = get("$base/v1/charts?region=$region", emptyMap())
        val o = json.parseToJsonElement(raw) as? JsonObject ?: error("The Orca+ cloud didn't answer")
        pagesCache = runCatching { json.decodeFromString(CloudPages.serializer(), raw) }.getOrElse { CloudPages() }
        val charts =
            (o["charts"] as? JsonArray).orEmpty().filterIsInstance<JsonObject>().mapNotNull { c ->
                val id = c.string("id").ifBlank { return@mapNotNull null }
                Chart(
                    id = id,
                    url = "$base/v1/charts#$id",
                    name = c.string("name"),
                    kind = c.string("kind").ifBlank { null },
                    pages = (c["pages"] as? JsonArray).orEmpty().mapNotNull { (it as? kotlinx.serialization.json.JsonPrimitive)?.content },
                    order = (c["order"] as? JsonObject).orEmpty().mapNotNull { (k, v) -> (v as? kotlinx.serialization.json.JsonPrimitive)?.content?.toIntOrNull()?.let { k to it } }.toMap(),
                    source = c.string("source"),
                    hidden = (c["hidden"] as? kotlinx.serialization.json.JsonPrimitive)?.content == "true",
                    entries =
                        (c["items"] as? JsonArray).orEmpty().filterIsInstance<JsonObject>().mapNotNull { i ->
                            ListEntry(
                                type = if (i.string("t") == "series") TmdbType.TV else TmdbType.MOVIE,
                                title = i.string("title").ifBlank { return@mapNotNull null },
                                year = i.int("year"),
                                tmdbId = i.int("tmdb"),
                                imdbId = i.string("imdb").ifBlank { null },
                                tvdbId = null,
                            )
                        },
                )
            }
        chartsCache = System.currentTimeMillis() to charts
        return charts
    }

    /** A Top Streaming catalog: its titles in chart order, by IMDb id (or TMDB id). */
    private fun topStreaming(s: ListSource.TopStreaming): FetchedList {
        val o = json.parseToJsonElement(get(s.url, emptyMap())) as? JsonObject ?: error("Top Streaming didn't return a catalog")
        val metas = o["metas"] as? JsonArray ?: error("Top Streaming didn't return a catalog")
        val entries =
            metas.filterIsInstance<JsonObject>().mapNotNull { m ->
                val id = m.string("id")
                ListEntry(
                    type = if (m.string("type") == "series") TmdbType.TV else TmdbType.MOVIE,
                    title = m.string("name").ifBlank { return@mapNotNull null },
                    year = Regex("""\d{4}""").find(m.string("releaseInfo"))?.value?.toIntOrNull(),
                    tmdbId = id.removePrefix("tmdb:").takeIf { id.startsWith("tmdb:") }?.toIntOrNull(),
                    imdbId = id.takeIf { it.startsWith("tt") },
                    tvdbId = null,
                )
            }
        return FetchedList("", entries)
    }

    /** One catalog an account's Top Streaming setup offers. */
    data class TopStreamingCatalog(
        val url: String,
        val name: String,
    )

    /**
     * The catalogs a Top Streaming account offers (as picked on its website), named for rows:
     * "Hulu Top 10 Movies", "Hulu Top 10 Shows", "Viki Top 10"; the country is added when
     * the account follows more than one.
     */
    fun topStreamingCatalogs(account: String): List<TopStreamingCatalog> {
        val base = "https://${ListSource.TOP_STREAMING_HOST}/$account"
        val o = json.parseToJsonElement(get("$base/manifest.json", emptyMap())) as? JsonObject ?: error("Top Streaming didn't answer")
        val catalogs = (o["catalogs"] as? JsonArray).orEmpty().filterIsInstance<JsonObject>()
        // "🔴 Hulu - Top 10 United States": the platform, then the chart and country
        fun platform(raw: String) = raw.substringBefore(" - ").dropWhile { !it.isLetterOrDigit() }.trim().let { if (it == "Amazon Prime Video") "Prime Video" else it }
        fun country(raw: String) = raw.substringAfter(" - ", "").substringAfter("Top 10 ", "").substringBefore(" - ").trim()
        val countries = catalogs.map { country(it.string("name")) }.filter { it.isNotBlank() }.toSet()
        return catalogs.mapNotNull { c ->
            val type = c.string("type").ifBlank { return@mapNotNull null }
            val id = c.string("id").ifBlank { return@mapNotNull null }
            val raw = c.string("name")
            val chart =
                when {
                    id.contains("overall") -> "Top 10"
                    type == "series" -> "Top 10 Shows"
                    else -> "Top 10 Movies"
                }
            val where = country(raw).takeIf { countries.size > 1 && it.isNotBlank() }?.let { " ($it)" }.orEmpty()
            TopStreamingCatalog("$base/catalog/$type/$id.json", "${platform(raw).ifBlank { id }} $chart$where")
        }
    }

    private fun mdblist(s: ListSource.MdbList): FetchedList {
        val body = get("${s.url}/json", emptyMap())
        val arr = json.parseToJsonElement(body) as? JsonArray ?: error("MDBList didn't return a list (is it public?)")
        val entries =
            arr.filterIsInstance<JsonObject>().mapNotNull { o ->
                val type = if (o.string("mediatype").equals("show", true)) TmdbType.TV else TmdbType.MOVIE
                ListEntry(
                    type = type,
                    title = o.string("title").ifBlank { return@mapNotNull null },
                    year = o.int("release_year"),
                    tmdbId = o.int("id"),
                    imdbId = o.string("imdb_id").ifBlank { null },
                    tvdbId = o.int("tvdbid"),
                )
            }
        return FetchedList(ListSource.prettify(s.slug), entries)
    }

    private fun trakt(s: ListSource.Trakt): FetchedList {
        val key = traktClientId().ifBlank { error("Trakt links need a Trakt client ID (Settings → Home & Look → Your lists)") }
        val headers = mapOf("trakt-api-version" to "2", "trakt-api-key" to key, "Content-Type" to "application/json")
        val name =
            if (s.apiPath.endsWith("watchlist") || s.apiPath.endsWith("favorites")) {
                s.nameHint
            } else {
                runCatching { (json.parseToJsonElement(get("https://api.trakt.tv/${s.apiPath}", headers)) as JsonObject).string("name") }
                    .getOrNull()
                    ?.ifBlank { null } ?: s.nameHint
            }
        val itemsPath = if (s.apiPath.endsWith("watchlist") || s.apiPath.endsWith("favorites")) s.apiPath else "${s.apiPath}/items"
        val arr = json.parseToJsonElement(get("https://api.trakt.tv/$itemsPath?limit=1000", headers)) as? JsonArray ?: error("Trakt didn't return a list")
        val entries =
            arr.filterIsInstance<JsonObject>().mapNotNull { o ->
                val (type, media) =
                    when (o.string("type")) {
                        "movie" -> TmdbType.MOVIE to o.obj("movie")
                        "show" -> TmdbType.TV to o.obj("show")
                        else -> return@mapNotNull null // seasons, episodes, people
                    }
                val m = media ?: return@mapNotNull null
                val ids = m.obj("ids")
                ListEntry(
                    type = type,
                    title = m.string("title").ifBlank { return@mapNotNull null },
                    year = m.int("year"),
                    tmdbId = ids?.int("tmdb"),
                    imdbId = ids?.string("imdb")?.ifBlank { null },
                    tvdbId = ids?.int("tvdb"),
                )
            }
        return FetchedList(name, entries)
    }

    private fun get(
        url: String,
        headers: Map<String, String>,
    ): String {
        val req =
            Request
                .Builder()
                .url(url)
                .header("Accept", "application/json")
                .header("User-Agent", "Orca+")
                .apply { headers.forEach { (k, v) -> header(k, v) } }
                .build()
        http.newCall(req).execute().use { r ->
            if (!r.isSuccessful) {
                val hint =
                    when (r.code) {
                        401, 403 -> " (private list, or wrong Trakt client ID)"
                        404 -> " (list not found)"
                        else -> ""
                    }
                throw ServerRequestException(r.code, "List site answered HTTP ${r.code}$hint")
            }
            return r.body.string()
        }
    }
}

/** One row of a Services or Genres page: a chart, under the name the page gives it. */
@kotlinx.serialization.Serializable
data class CloudPageRow(
    val chart: String,
    val name: String,
)

/** A service (Hulu…) or genre opened from its tile: Movies rows and Shows rows. */
@kotlinx.serialization.Serializable
data class CloudPage(
    val id: String,
    /** "service" or "genre". */
    val kind: String,
    val name: String,
    /** A service's wordmark. */
    val logo: String? = null,
    /** A genre's picture. */
    val picture: String? = null,
    val movies: List<CloudPageRow> = emptyList(),
    val shows: List<CloudPageRow> = emptyList(),
)

/** Every page, and per tab ("HOME"…) where its Services / Genres rows sit in the cloud's lineup. */
@kotlinx.serialization.Serializable
data class CloudPages(
    val pages: List<CloudPage> = emptyList(),
    val tiles: Map<String, Map<String, Int>> = emptyMap(),
)
