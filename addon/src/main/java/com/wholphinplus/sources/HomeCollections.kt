package com.wholphinplus.sources

import android.content.Context
import com.wholphinplus.sources.core.ListClient
import com.wholphinplus.sources.core.ListEntry
import com.wholphinplus.sources.core.ListSource
import com.wholphinplus.sources.core.PlayRequest
import com.wholphinplus.sources.core.TmdbType
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import timber.log.Timber
import java.util.UUID
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/** A Trakt/MDBList list shown as a home row. */
@Serializable
data class HomeCollection(
    val id: String,
    val url: String,
    val name: String,
    val showOnHome: Boolean = true,
    /** Main-server item ids of the list's titles that are in the library, in list order. */
    val itemIds: List<String> = emptyList(),
    val listSize: Int = 0,
    val refreshedAt: Long = 0L,
    val error: String? = null,
    /** Orca+ charts: "movie", "series" or null (both). */
    val kind: String? = null,
    /** Orca+ charts: the Cinema pages it starts on (RowsPage names); null for your own lists. */
    val pages: List<String>? = null,
    /** Orca+ charts: their place on each page they start on (page name → position). */
    val order: Map<String, Int>? = null,
    /** Orca+ charts shown only on a Services or Genres page, never offered as a row of their own. */
    val hidden: Boolean = false,
    /** Each of [itemIds]' place in the list (1 = first), so a Top 10 keeps its real numbers. */
    val ranks: List<Int> = emptyList(),
) {
    /** Main-server item id (dashes or not) → its place in the list; empty for rows saved before ranks. */
    fun rankById(): Map<String, Int> = if (ranks.size != itemIds.size) emptyMap() else itemIds.zip(ranks).associate { (id, r) -> id.replace("-", "").lowercase() to r }

    /** Jellyfin "tag" put on this row's request; the [ProgressOverlay] swaps it for [itemIds]. */
    val tag: String get() = TAG_PREFIX + id

    companion object {
        const val TAG_PREFIX = "wholphinplus-list-"
    }
}

/**
 * Custom home rows from Trakt and MDBList links. Each list is matched to the main server
 * (title search confirmed by TMDB/IMDb id) and refreshed every few hours, so the row follows the
 * list as it changes.
 */
/** What the interceptor needs from the collections: the library ids behind a row's tag. */
fun interface CollectionLookup {
    fun itemIdsForTag(tag: String): List<String>?
}

@Singleton
class HomeCollections
    @Inject
    constructor(
        @param:ApplicationContext context: Context,
    ) : CollectionLookup {
        override fun itemIdsForTag(tag: String): List<String>? = byTag(tag)?.itemIds

        private val prefs = context.getSharedPreferences("wholphinplus_collections", Context.MODE_PRIVATE)
        private val json = Json { ignoreUnknownKeys = true }
        private val _lists = MutableStateFlow(load())
        val lists: StateFlow<List<HomeCollection>> = _lists.asStateFlow()

        private val _traktClientId = MutableStateFlow(prefs.getString(TRAKT_KEY, "").orEmpty())
        val traktClientId: StateFlow<String> = _traktClientId.asStateFlow()

        /** Item ids by entry key, so a title shared by several lists is matched once. */
        private val matchCache = java.util.concurrent.ConcurrentHashMap<String, String>(loadMatches())

        val client = ListClient(OkHttpClient.Builder().readTimeout(30, TimeUnit.SECONDS).build()) { _traktClientId.value }

        fun setTraktClientId(id: String) {
            prefs.edit().putString(TRAKT_KEY, id.trim()).apply()
            _traktClientId.value = id.trim()
        }

        private val _topStreaming = MutableStateFlow(prefs.getString(TOP_STREAMING_KEY, "").orEmpty())

        /** The Top Streaming account ID whose catalogs are offered as rows ("" = none). */
        val topStreamingAccount: StateFlow<String> = _topStreaming.asStateFlow()

        /**
         * Saves a Top Streaming account (its ID, or any link with it in) and returns the ID;
         * blank removes it and its rows.
         */
        fun setTopStreamingAccount(raw: String): String {
            val id = ACCOUNT.find(raw)?.value?.lowercase().orEmpty()
            prefs.edit().putString(TOP_STREAMING_KEY, id).apply()
            _topStreaming.value = id
            if (id.isEmpty()) save(_lists.value.filterNot { isTopStreaming(it) })
            return id
        }

        private val _cloudPages = MutableStateFlow(runCatching { json.decodeFromString(com.wholphinplus.sources.core.CloudPages.serializer(), prefs.getString(PAGES_KEY, null)!!) }.getOrElse { com.wholphinplus.sources.core.CloudPages() })

        /** The Services and Genres pages and where each tab shows their tiles (kept, so they show offline). */
        val cloudPages: StateFlow<com.wholphinplus.sources.core.CloudPages> = _cloudPages.asStateFlow()

        /** The list behind a page's chart: the cloud's own, or your Top Streaming row standing in for it. */
        fun forChart(chartId: String): HomeCollection? {
            val url = com.wholphinplus.sources.sync.ProfileSync.ENDPOINT + "/v1/charts#" + chartId
            return _lists.value.firstOrNull { it.url == url }
                ?: _lists.value.firstOrNull { isTopStreaming(it) && (com.wholphinplus.sources.core.ListSource.parse(it.url) as? com.wholphinplus.sources.core.ListSource.TopStreaming)?.chartId == chartId }
        }

        /**
         * Whether a list has enough titles in the library to make a row: an Orca+ chart needs
         * [MIN_ROW] ([MIN_TOP] for a Top 10), so a small library doesn't get rows of one or two
         * cards (they join once the library has more); your own lists show with any.
         */
        fun enough(c: HomeCollection): Boolean = c.itemIds.size >= (if (!isChart(c)) 1 else minFor(c.name))

        /** Fewest library titles a chart named [name] shows with. */
        fun minFor(name: String): Int = if (com.wholphinplus.sources.cinema.CinemaRepository.isTopList(name)) MIN_TOP else MIN_ROW

        /** An Orca+ chart (the cloud's default rows: Trakt, TMDB, streaming Top 10s). */
        fun isChart(c: HomeCollection): Boolean = c.url.startsWith(com.wholphinplus.sources.sync.ProfileSync.ENDPOINT + "/v1/charts#")

        /** Bumped after a refresh changed any row, so the Cinema pages reload. */
        private val _changed = MutableStateFlow(0)
        val changed: StateFlow<Int> = _changed.asStateFlow()

        /**
         * Brings the Orca+ charts in line with the cloud's: new ones join (on the pages the cloud
         * suggests; on the classic home when Home is one of them), gone ones leave. The shared
         * Top 10s step aside when you have your own Top Streaming account, and your own rows take
         * their places (the same chart); your other Top 10s start off.
         */
        fun syncCharts() {
            val tilesBefore = _cloudPages.value.tiles
            val own = _topStreaming.value.isNotBlank()
            val all = client.charts()
            val shared = all.filter { it.source == "top-streaming" }.associateBy { it.id }
            val charts = all.filter { !(own && it.source == "top-streaming") }
            val urls = charts.associateBy { it.url }
            val current = _lists.value
            val have = current.map { it.url }.toSet()
            val kept =
                current.filter { !isChart(it) || it.url in urls }.map { c ->
                    urls[c.url]?.let { ch -> c.copy(kind = ch.kind, pages = ch.pages, order = ch.order, name = ch.name, hidden = ch.hidden) }
                        ?: standIn(c, shared).takeIf { own }
                        ?: c
                }
            val added =
                charts.filter { it.url !in have }.map { ch ->
                    HomeCollection(UUID.randomUUID().toString().take(8), ch.url, ch.name, showOnHome = "HOME" in ch.pages, kind = ch.kind, pages = ch.pages, order = ch.order, hidden = ch.hidden)
                }
            val pages = client.cloudPages()
            if (pages != _cloudPages.value) {
                prefs.edit().putString(PAGES_KEY, json.encodeToString(com.wholphinplus.sources.core.CloudPages.serializer(), pages)).apply()
                _cloudPages.value = pages
            }
            if (kept != current || added.isNotEmpty() || pages.tiles != tilesBefore) {
                save(kept + added)
                // Charts came, went or moved pages: the Cinema pages rebuild with them
                com.wholphinplus.sources.cinema.CinemaCaches.homeChanged()
                _changed.value++
            }
        }

        /** Your own Top Streaming row, placed where the cloud places the same chart (null for other lists). */
        private fun standIn(
            c: HomeCollection,
            shared: Map<String, com.wholphinplus.sources.core.ListClient.Chart>,
        ): HomeCollection? {
            val source = com.wholphinplus.sources.core.ListSource.parse(c.url) as? com.wholphinplus.sources.core.ListSource.TopStreaming ?: return null
            val pages = shared[source.chartId]?.pages.orEmpty()
            val order = shared[source.chartId]?.order.orEmpty()
            // The first time only: after that its Home switch is yours
            val home = if (c.pages == null) "HOME" in pages else c.showOnHome
            return c.copy(pages = pages, order = order, showOnHome = home)
        }

        /** A Top Streaming chart or Orca+ chart of shows (true) or movies (false); null for mixed charts and other lists. */
        fun chartSeries(c: HomeCollection): Boolean? =
            when {
                c.kind != null -> c.kind == "series"
                !isTopStreaming(c) || c.url.contains("overall") -> null
                c.url.contains("/catalog/series/") -> true
                c.url.contains("/catalog/movie/") -> false
                else -> null
            }

        fun isTopStreaming(c: HomeCollection): Boolean = c.url.contains(com.wholphinplus.sources.core.ListSource.TOP_STREAMING_HOST)

        /**
         * Brings the Top Streaming rows in line with the account's catalogs (as picked on its
         * website): new ones join switched off, ones no longer offered go, the rest keep their
         * name and switch. Returns how many catalogs there are.
         */
        fun syncTopStreaming(): Int {
            val account = _topStreaming.value.ifBlank { return 0 }
            val catalogs = client.topStreamingCatalogs(account)
            val urls = catalogs.map { it.url }.toSet()
            val current = _lists.value
            val have = current.map { it.url }.toSet()
            val added = catalogs.filter { it.url !in have }.map { HomeCollection(UUID.randomUUID().toString().take(8), it.url, it.name, showOnHome = false) }
            save(current.filter { !isTopStreaming(it) || it.url in urls } + added)
            return catalogs.size
        }

        /** Lists, charts and their accounts, for a cloud profile. */
        fun snapshot(): com.wholphinplus.sources.sync.ListsState =
            com.wholphinplus.sources.sync.ListsState(_lists.value, _traktClientId.value, _topStreaming.value)

        /** A cloud profile's lists (their matches came with them, so rows show at once). */
        fun restore(s: com.wholphinplus.sources.sync.ListsState) {
            if (s.traktClientId != _traktClientId.value) setTraktClientId(s.traktClientId)
            if (s.topStreaming != _topStreaming.value) {
                prefs.edit().putString(TOP_STREAMING_KEY, s.topStreaming).apply()
                _topStreaming.value = s.topStreaming
            }
            if (s.lists != _lists.value) save(s.lists)
        }

        fun byTag(tag: String): HomeCollection? = _lists.value.firstOrNull { it.tag == tag }

        fun add(collection: HomeCollection) = save(_lists.value.filterNot { it.id == collection.id } + collection)

        fun update(collection: HomeCollection) = save(_lists.value.map { if (it.id == collection.id) collection else it })

        fun remove(id: String) = save(_lists.value.filterNot { it.id == id })

        fun move(
            id: String,
            delta: Int,
        ) {
            val list = _lists.value.toMutableList()
            val i = list.indexOfFirst { it.id == id }
            val j = i + delta
            if (i < 0 || j !in list.indices) return
            list.add(j, list.removeAt(i))
            save(list)
        }

        private fun save(list: List<HomeCollection>) {
            prefs.edit().putString(KEY, json.encodeToString(list)).apply()
            _lists.value = list
        }

        private fun load(): List<HomeCollection> =
            runCatching { prefs.getString(KEY, null)?.let { json.decodeFromString<List<HomeCollection>>(it) } }.getOrNull().orEmpty()

        private fun loadMatches(): Map<String, String> =
            runCatching { prefs.getString(MATCH_KEY, null)?.let { json.decodeFromString<Map<String, String>>(it) } }.getOrNull().orEmpty()

        private fun saveMatches() = prefs.edit().putString(MATCH_KEY, json.encodeToString(HashMap(matchCache))).apply()

        // ------------------------------------------------------------ refresh

        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        private var job: Job? = null
        private val _refreshing = MutableStateFlow<String?>(null)

        /** Name of the list being refreshed, for the settings screen. */
        val refreshing: StateFlow<String?> = _refreshing.asStateFlow()

        /** Refresh lists older than [maxAgeMs] in the background (one run at a time). */
        fun refreshStale(
            hook: SourceHook,
            maxAgeMs: Long = STALE_MS,
            onlyId: String? = null,
        ) {
            synchronized(this) {
                val running = job?.takeIf { it.isActive }
                // A background refresh is running: skip; "Refresh now" (one list) runs after it
                if (running != null && onlyId == null) return
                job =
                    scope.launch {
                        running?.join()
                        val now = System.currentTimeMillis()
                        // Titles not found are retried daily, so new library additions appear
                        if (now - prefs.getLong(MISS_RESET_KEY, 0L) > 24 * 60 * 60 * 1000L) {
                            forgetMisses()
                            prefs.edit().putLong(MISS_RESET_KEY, now).apply()
                        }
                        // Catalogs picked or dropped on the Top Streaming website follow here
                        runCatching { syncTopStreaming() }.onFailure { Timber.w(it, "Top Streaming catalogs unavailable") }
                        runCatching { syncCharts() }.onFailure { Timber.w(it, "Orca+ charts unavailable") }
                        var before = _lists.value.map { it.itemIds }
                        // Rows changed: the Cinema pages load them now (they show what they gained)
                        fun announce() {
                            val after = _lists.value.map { it.itemIds }
                            if (after == before) return
                            before = after
                            com.wholphinplus.sources.cinema.CinemaCaches.homeChanged()
                            _changed.value++
                        }
                        // Home's rows first, then the other tabs', then rows that start off and the
                        // Services / Genres pages' charts (~2 min on a first start); the pages show
                        // each group as soon as it's matched
                        fun group(c: HomeCollection) =
                            when {
                                c.hidden -> 3
                                if (isChart(c)) c.pages?.contains("HOME") == true else c.showOnHome -> 0
                                !c.pages.isNullOrEmpty() -> 1
                                else -> 2
                            }
                        val stale = _lists.value.filter { (onlyId == null || it.id == onlyId) && now - it.refreshedAt > maxAgeMs }.sortedWith(compareBy({ group(it) }, { it.order?.get("HOME") ?: Int.MAX_VALUE }))
                        stale.forEachIndexed { i, c ->
                            if (i > 0 && group(c) != group(stale[i - 1])) announce()
                            _refreshing.value = c.name
                            refreshOne(hook, c)
                        }
                        _refreshing.value = null
                        announce()
                    }
            }
        }

        private suspend fun refreshOne(
            hook: SourceHook,
            c: HomeCollection,
        ) {
            val updated =
                try {
                    val source = ListSource.parse(c.url) ?: error("Not a Trakt, MDBList, Top Streaming or Orca+ chart link")
                    val fetched = client.fetch(source)
                    val main = hook.mainConnection() ?: error("Not signed in")
                    val gate = Semaphore(6)
                    // Titles the server couldn't be asked about (it didn't answer) aren't misses
                    val failed = java.util.concurrent.atomic.AtomicInteger()
                    // Each title found, with its place in the list
                    val found =
                        coroutineScope {
                            fetched.entries.mapIndexed { i, e ->
                                async {
                                    gate.withPermit {
                                        try {
                                            match(hook, main, e)?.let { it to i + 1 }
                                        } catch (ex: Exception) {
                                            if (ex is kotlinx.coroutines.CancellationException) throw ex
                                            failed.incrementAndGet()
                                            null
                                        }
                                    }
                                }
                            }.awaitAll()
                        }.filterNotNull().distinctBy { it.first }
                    val ids = found.map { it.first }
                    saveMatches()
                    // A refresh that hit server errors never shrinks the row: keep it as it was
                    if (failed.get() > 0 && ids.size < c.itemIds.size) error("The server didn't answer for ${failed.get()} titles")
                    Timber.i("Home collection %s: %d of %d titles in the library", c.name, ids.size, fetched.entries.size)
                    c.copy(
                        name = if (c.name.isBlank() || c.name == PENDING_NAME) fetched.name else c.name,
                        itemIds = ids,
                        ranks = found.map { it.second },
                        listSize = fetched.entries.size,
                        refreshedAt = System.currentTimeMillis(),
                        error = null,
                    )
                } catch (e: Exception) {
                    if (e is kotlinx.coroutines.CancellationException) throw e
                    Timber.w(e, "Home collection %s failed", c.url)
                    c.copy(error = e.message ?: e.javaClass.simpleName, refreshedAt = System.currentTimeMillis())
                }
            // The list may have been edited meanwhile: keep the user's latest name/visibility
            _lists.value.firstOrNull { it.id == c.id }?.let { current ->
                update(updated.copy(showOnHome = current.showOnHome, name = if (current.name == c.name) updated.name else current.name))
            }
        }

        private suspend fun match(
            hook: SourceHook,
            main: com.wholphinplus.sources.core.ServerConnection,
            e: ListEntry,
        ): String? {
            matchCache[e.key]?.let { return it.ifBlank { null } }
            val request = PlayRequest(e.title, e.year, e.imdbId, e.tmdbId, e.tvdbId)
            // Throws when the server doesn't answer: that's not a miss, so nothing is remembered
            val id =
                (if (e.type == TmdbType.TV) hook.client.matchSeriesIds(main, request) else hook.client.matchItemIds(main, request))
                    .firstOrNull()
            // Remember misses too (as ""), so a 500-title list doesn't re-search every refresh;
            // misses are forgotten daily and on "Refresh now".
            matchCache[e.key] = id.orEmpty()
            return id
        }

        /** "Refresh now": forget remembered misses so newly added library titles are found. */
        fun forgetMisses() {
            matchCache.entries.removeIf { it.value.isBlank() }
            saveMatches()
        }

        fun newCollection(url: String): HomeCollection = HomeCollection(UUID.randomUUID().toString().take(8), url.trim(), PENDING_NAME)

        companion object {
            private const val KEY = "lists_v1"
            private const val MATCH_KEY = "matches_v1"
            private const val PAGES_KEY = "cloud_pages_v1"
            private const val TRAKT_KEY = "trakt_client_id"
            private const val TOP_STREAMING_KEY = "top_streaming_account"
            private val ACCOUNT = Regex("""[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}""")
            private const val MISS_RESET_KEY = "miss_reset_at"
            const val PENDING_NAME = "Loading list…"
            const val STALE_MS = 6 * 60 * 60 * 1000L

            /** Fewest library titles an Orca+ chart row shows with ([MIN_TOP] for a numbered Top 10). */
            const val MIN_ROW = 5
            const val MIN_TOP = 3
        }
    }
