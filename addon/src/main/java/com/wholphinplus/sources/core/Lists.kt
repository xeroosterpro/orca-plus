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

                host.endsWith("trakt.tv") && seg.size >= 2 && seg[0] == "lists" ->
                    Trakt("https://trakt.tv/lists/${seg[1]}", "lists/${seg[1]}", prettify(seg[1]))

                else -> null
            }
        }

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
        val key = traktClientId().ifBlank { error("Trakt links need a Trakt client ID (Settings → Orca+ → Home collections)") }
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
