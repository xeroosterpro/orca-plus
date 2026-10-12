package com.wholphinplus.sources.cinema

import com.wholphinplus.sources.HomeCollection
import com.wholphinplus.sources.SourceHook
import com.wholphinplus.sources.core.TmdbClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import timber.log.Timber
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Collections in search (owner, 2026-10-11: "search results will show MDBList/collections"): the
 * server's own collections whose name matches, TMDB film series ("Harry Potter Collection"), and
 * MDBList's public lists (through the Orca+ cloud, so no key is needed). Each is kept only when the
 * library has some of its titles; the library index counts them without asking the server.
 */
internal class CollectionSearch(
    private val hook: SourceHook,
    private val repo: CinemaRepository,
    private val tmdb: TmdbClient,
) {
    enum class Source(
        val label: String,
    ) {
        SERVER("On your server"),
        SERIES("Film series"),
        LIST("List"),
    }

    /** One collection in the results. [have]: its titles in the library (null: not counted yet). */
    data class Hit(
        val key: String,
        val source: Source,
        val name: String,
        val by: String? = null,
        val total: Int? = null,
        val have: Int? = null,
        val picture: String? = null,
        val serverId: UUID? = null,
        val refs: List<Pair<Int, Boolean>> = emptyList(),
        /** MDBList: the list's page, which is also how it's added as a Home row. */
        val listUrl: String? = null,
        /** A collection you saved ([HomeCollection.id]): its titles are the ones it matched. */
        val savedId: String? = null,
    ) {
        /** The link it's saved under (a Home row's url): the list, the film series or the server's collection. */
        val saveUrl: String?
            get() =
                listUrl
                    ?: key.removePrefix("series:").takeIf { source == Source.SERIES }?.toIntOrNull()?.let { com.wholphinplus.sources.core.ListSource.tmdbCollection(it).url }
                    ?: serverId?.let { com.wholphinplus.sources.core.ListSource.serverCollection(it.toString()).url }
    }

    private val http = com.wholphinplus.sources.core.OrcaHttp.client(connect = 6, read = 12)
    private val json = Json { ignoreUnknownKeys = true }
    private val listRefs = ConcurrentHashMap<Long, List<Pair<Int, Boolean>>>()
    private val seriesParts = ConcurrentHashMap<Int, List<Pair<Int, Boolean>>>()

    /** Collections for [query], server's first, then film series, then lists. Never throws. */
    suspend fun find(query: String): List<Hit> =
        coroutineScope {
            val q = query.trim()
            val server = async { guard("server") { server(q) } }
            val series = async { guard("TMDB collections") { series(q) } }
            val lists = async { guard("MDBList lists") { lists(q) } }
            server.await() + series.await() + lists.await()
        }

    /** A page of a collection's titles in the library, in its own order: [count] from [from]. */
    suspend fun titles(
        hit: Hit,
        from: Int,
        count: Int,
    ): List<CinemaItem> =
        when {
            hit.savedId != null -> repo.titlesById(hook.collections.lists.value.firstOrNull { it.id == hit.savedId }?.itemIds.orEmpty(), limit = count, from = from)
            hit.serverId != null -> repo.serverCollectionTitles(hit.serverId, limit = count, from = from)
            else -> repo.titlesFor(hit.refs, limit = count, from = from)
        }

    /**
     * Your saved collections and lists (not Orca+'s charts), as tiles: My List shows them. A
     * collection's titles are the ones it matched in the library at its last refresh.
     */
    fun saved(lists: List<HomeCollection>): List<Hit> =
        lists.filter { !it.hidden && com.wholphinplus.sources.core.ListSource.parse(it.url).let { s -> s != null && s !is com.wholphinplus.sources.core.ListSource.OrcaChart && s !is com.wholphinplus.sources.core.ListSource.TopStreaming } }
            .map { c ->
                val source =
                    when (com.wholphinplus.sources.core.ListSource.parse(c.url)) {
                        is com.wholphinplus.sources.core.ListSource.TmdbCollection -> Source.SERIES
                        is com.wholphinplus.sources.core.ListSource.ServerCollection -> Source.SERVER
                        else -> Source.LIST
                    }
                Hit("saved:${c.id}", source, c.name, have = c.itemIds.size, picture = c.picture, listUrl = c.url.takeIf { source == Source.LIST }, savedId = c.id)
            }

    private suspend fun guard(
        what: String,
        block: suspend () -> List<Hit>,
    ): List<Hit> =
        withTimeoutOrNull(TIMEOUT_MS) {
            try {
                block()
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                Timber.w(e, "Search collections: %s failed", what)
                emptyList()
            }
        }.orEmpty()

    // The server's collections: matched by name on the TV (Silo ignores a search on box sets)
    private suspend fun server(q: String): List<Hit> {
        val words = words(q)
        if (words.isEmpty()) return emptyList()
        val found =
            repo.serverCollections().filter { c ->
                val name = words(c.title)
                words.all { w -> name.any { it.startsWith(w) } }
            }.take(MAX_SERVER)
        return coroutineScope {
            found.map { c ->
                async {
                    // Its newest title's picture: the server's own collection art is a poster or a logo
                    val first = repo.serverCollectionTitles(c.id, limit = 1).firstOrNull()
                    Hit("server:${c.id}", Source.SERVER, c.title, picture = first?.backdropUrl ?: first?.cardUrl, serverId = c.id)
                }
            }.awaitAll()
        }
    }

    private suspend fun series(q: String): List<Hit> {
        if (!tmdb.available) return emptyList()
        val found = withContext(Dispatchers.IO) { tmdb.searchCollections(q) }.take(MAX_SERIES)
        return coroutineScope {
            found.map { c ->
                async {
                    val refs = seriesParts[c.id] ?: withContext(Dispatchers.IO) { tmdb.collectionParts(c.id) }.map { it.id to false }.also { seriesParts[c.id] = it }
                    val have = repo.countFor(refs)
                    Hit("series:${c.id}", Source.SERIES, c.name, total = refs.size, have = have, picture = c.backdropUrl(), refs = refs)
                }
            }.awaitAll()
        }.filter { (it.have ?: 1) >= 1 }
    }

    private suspend fun lists(q: String): List<Hit> {
        if (!com.wholphinplus.sources.sync.ProfileSync.AVAILABLE) return emptyList()
        val base = com.wholphinplus.sources.sync.ProfileSync.ENDPOINT
        val found =
            (getJson("$base/v1/lists/search".toHttpUrl().newBuilder().addQueryParameter("q", q).build().toString()) as? JsonArray)
                ?.filterIsInstance<JsonObject>().orEmpty()
                .take(MAX_LISTS_ASKED)
        return coroutineScope {
            found.map { o ->
                async {
                    val id = o.long("id") ?: return@async null
                    val refs = listRefs[id] ?: itemsOf(base, id).also { listRefs[id] = it }
                    val have = repo.countFor(refs)
                    val user = o.str("user")
                    val slug = o.str("slug")
                    Hit(
                        "list:$id",
                        Source.LIST,
                        cleanName(o.str("name")),
                        total = o.long("items")?.toInt() ?: refs.size,
                        have = have,
                        refs = refs,
                        listUrl = if (user.isNotBlank() && slug.isNotBlank()) "https://mdblist.com/lists/$user/$slug" else null,
                    )
                }
            }.awaitAll().filterNotNull()
        }
            // Lists with a few of the library's titles. Several people's "John Wick", "John Wick
            // Collection" and "John Wick Universe" are one shelf: the one with most of them here
            .filter { (it.have ?: MIN_LIST_HAVE) >= MIN_LIST_HAVE }
            .groupBy { shelf(it.name) }.values.map { same -> same.maxBy { it.have ?: 0 } }
            .take(MAX_LISTS)
            .let { hits -> withPictures(hits) }
    }

    /** Each list's first library title's picture (lists have none of their own). */
    private suspend fun withPictures(hits: List<Hit>): List<Hit> =
        coroutineScope {
            hits.map { h -> async { h.copy(picture = repo.titlesFor(h.refs.take(12), limit = 1).firstOrNull()?.let { it.backdropUrl ?: it.cardUrl }) } }.awaitAll()
        }

    private suspend fun itemsOf(
        base: String,
        id: Long,
    ): List<Pair<Int, Boolean>> =
        ((getJson("$base/v1/lists/$id") as? JsonObject)?.get("items") as? JsonArray)
            ?.filterIsInstance<JsonObject>().orEmpty()
            .mapNotNull { o -> o.long("t")?.toInt()?.let { it to (o.str("k") == "s") } }

    private suspend fun getJson(url: String) =
        withContext(Dispatchers.IO) {
            http.newCall(Request.Builder().url(url).header("Accept", "application/json").get().build()).execute().use { r ->
                if (!r.isSuccessful) throw java.io.IOException("Orca+ cloud answered HTTP ${r.code}")
                json.parseToJsonElement(r.body.string())
            }
        }

    private fun JsonObject.str(name: String): String = (this[name] as? kotlinx.serialization.json.JsonPrimitive)?.takeUnless { it is kotlinx.serialization.json.JsonNull }?.content.orEmpty()

    private fun JsonObject.long(name: String): Long? = str(name).toLongOrNull()

    /** A list's name without the words that only say it's a list: "John Wick Universe" → "john wick". */
    private fun shelf(name: String): String = words(name).filterNot { it in SHELF_WORDS }.joinToString(" ").ifBlank { name.lowercase() }

    private fun words(s: String): List<String> = s.lowercase().split(Regex("[^\\p{L}\\p{N}+]+")).filter { it.isNotBlank() }

    companion object {
        private const val TIMEOUT_MS = 9_000L
        private const val MAX_SERVER = 3
        private const val MAX_SERIES = 4
        private const val MAX_LISTS_ASKED = 18
        private const val MAX_LISTS = 12
        private const val MIN_LIST_HAVE = 2
        private val SMALL_WORDS = setOf("a", "an", "and", "of", "the", "in", "on", "to", "for", "or", "vs")

        /**
         * A list's name as a shelf label: without its maker's notes in brackets ("Britbox Movies 2023
         * (BBC IPLAYER,BBC Earth added)" → "Britbox Movies 2023"), tags ("[Service] britbox"), emoji,
         * and in title case when it was typed all lower or all upper case.
         */
        internal fun cleanName(raw: String): String {
            // A name typed as a link slug ("britbox-movies") becomes words
            var n = if (' ' !in raw.trim() && (raw.contains('-') || raw.contains('_'))) raw.replace('-', ' ').replace('_', ' ') else raw
            n = n.replace(Regex("\\[[^\\]]*\\]|\\([^)]*\\)|\\{[^}]*\\}"), " ")
            n = n.replace(Regex("[^\\p{L}\\p{N}\\s&'’:.,!?+/-]"), " ").replace(Regex("\\s+"), " ").trim().trim('-', '/', ',', ':', '.').trim()
            if (n.isBlank()) return raw.trim()
            val letters = n.filter { it.isLetter() }
            if (letters.isNotEmpty() && (letters == letters.lowercase() || letters == letters.uppercase())) {
                n = n.lowercase().split(" ").joinToString(" ") { w -> if (w in SMALL_WORDS) w else w.replaceFirstChar { it.titlecase() } }.replaceFirstChar { it.titlecase() }
            }
            return n
        }

        private val SHELF_WORDS = setOf("collection", "collections", "universe", "franchise", "saga", "the", "complete", "list")
    }
}
