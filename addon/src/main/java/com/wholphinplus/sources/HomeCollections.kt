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
import kotlinx.serialization.json.decodeFromStream
import kotlinx.serialization.json.encodeToStream
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

@OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
@Singleton
class HomeCollections
    @Inject
    constructor(
        @param:ApplicationContext context: Context,
    ) : CollectionLookup {
        override fun itemIdsForTag(tag: String): List<String>? = byTag(tag)?.itemIds

        private val prefs = context.getSharedPreferences("wholphinplus_collections", Context.MODE_PRIVATE)
        private val appContext = context.applicationContext
        private val json = Json { ignoreUnknownKeys = true }

        // The lists live in a file of their own: with lists of up to 1,000 titles they're several
        // MB, too much for a preferences file (read whole at start, rewritten on every change)
        private val listsFile = java.io.File(context.filesDir, "home_lists_v1.json")
        private val writer = java.util.concurrent.Executors.newSingleThreadExecutor()

        /** The main server's library by TMDB/IMDb id: lists match by id, no search per title. */
        private val index = LibraryIndex(java.io.File(context.filesDir, "library_index_v1.bin"))

        /** Whose library an index is: the main server and the signed-in user. */
        private fun ownerOf(c: com.wholphinplus.sources.core.ServerConnection) = c.serverUrl.trimEnd('/') + "|" + c.userId

        /** The main server's library index as it stands (null before it's first read). */
        internal fun indexFor(hook: SourceHook): LibraryIndex.Snapshot? = hook.mainConnection()?.let { index.peek(ownerOf(it)) }

        /** [indexFor], or the index saved on the device when it isn't in memory yet (a cold start). */
        internal suspend fun savedIndexFor(hook: SourceHook): LibraryIndex.Snapshot? = hook.mainConnection()?.let { index.saved(ownerOf(it)) }

        /** A full read of the library in progress ("Getting your library ready"), null when none. */
        internal val indexProgress: kotlinx.coroutines.flow.StateFlow<LibraryIndex.Progress?> get() = index.progress
        // What's kept on the device is read off the main thread: this is built while the app
        // starts (Hilt), and the lists file is several MB. Anything that needs it waits for it
        // (the first screen comes later; OkHttp threads may wait)
        private val loaded = java.util.concurrent.CountDownLatch(1)

        private fun ready() {
            if (loaded.count > 0) loaded.await()
        }

        private val listsFlow = MutableStateFlow<List<HomeCollection>>(emptyList())
        private val _lists: MutableStateFlow<List<HomeCollection>> get() = ready().let { listsFlow }
        private val listsView = listsFlow.asStateFlow()
        val lists: StateFlow<List<HomeCollection>> get() = ready().let { listsView }

        private val traktFlow = MutableStateFlow("")
        private val _traktClientId: MutableStateFlow<String> get() = ready().let { traktFlow }
        private val traktView = traktFlow.asStateFlow()
        val traktClientId: StateFlow<String> get() = ready().let { traktView }

        /** Item ids by entry key, so a title shared by several lists is matched once. */
        private val matchesMap = java.util.concurrent.ConcurrentHashMap<String, String>()
        private val matchCache: java.util.concurrent.ConcurrentHashMap<String, String> get() = ready().let { matchesMap }

        val client = ListClient(OkHttpClient.Builder().readTimeout(30, TimeUnit.SECONDS).build()) { _traktClientId.value }

        fun setTraktClientId(id: String) {
            prefs.edit().putString(TRAKT_KEY, id.trim()).apply()
            _traktClientId.value = id.trim()
        }

        private val topStreamingFlow = MutableStateFlow("")
        private val _topStreaming: MutableStateFlow<String> get() = ready().let { topStreamingFlow }
        private val topStreamingView = topStreamingFlow.asStateFlow()

        /** The Top Streaming account ID whose catalogs are offered as rows ("" = none). */
        val topStreamingAccount: StateFlow<String> get() = ready().let { topStreamingView }

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

        private val cloudPagesFlow = MutableStateFlow(com.wholphinplus.sources.core.CloudPages())
        private val _cloudPages: MutableStateFlow<com.wholphinplus.sources.core.CloudPages> get() = ready().let { cloudPagesFlow }
        private val cloudPagesView = cloudPagesFlow.asStateFlow()

        /** The Services and Genres pages and where each tab shows their tiles (kept, so they show offline). */
        val cloudPages: StateFlow<com.wholphinplus.sources.core.CloudPages> get() = ready().let { cloudPagesView }

        init {
            Thread({
                try {
                    listsFlow.value = load()
                    traktFlow.value = prefs.getString(TRAKT_KEY, "").orEmpty()
                    topStreamingFlow.value = prefs.getString(TOP_STREAMING_KEY, "").orEmpty()
                    cloudPagesFlow.value =
                        runCatching { json.decodeFromString(com.wholphinplus.sources.core.CloudPages.serializer(), prefs.getString(PAGES_KEY, null)!!) }
                            .getOrElse { com.wholphinplus.sources.core.CloudPages() }
                    matchesMap.putAll(loadMatches())
                } catch (e: Throwable) {
                    Timber.w(e, "Reading the lists failed")
                } finally {
                    loaded.countDown()
                }
            }, "orca-lists-load").start()
        }

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
        fun isChart(c: HomeCollection): Boolean = com.wholphinplus.sources.sync.ProfileSync.AVAILABLE && c.url.startsWith(com.wholphinplus.sources.sync.ProfileSync.ENDPOINT + "/v1/charts#")

        /** Bumped after a refresh changed any row, so the Cinema pages reload. */
        private val _changed = MutableStateFlow(0)
        val changed: StateFlow<Int> = _changed.asStateFlow()

        /** Which lists each bump of [changed] was about (null: anything may have changed). */
        private val changes = java.util.ArrayDeque<Pair<Int, Set<String>?>>()

        /**
         * The lists (by [HomeCollection.id]) whose titles changed after [since] (a value of
         * [changed]); null when it can't be told (charts came or went, or too long ago). A page
         * whose own lists aren't in it needn't reload: a 6-hourly refresh announces each group
         * of lists, and every announcement used to reload the open tab.
         */
        fun changedSince(since: Int): Set<String>? =
            synchronized(changes) {
                val now = _changed.value
                if (since >= now) return emptySet()
                val wanted = changes.filter { it.first > since }
                if (wanted.size < now - since) return null
                wanted.fold(emptySet<String>()) { acc, c -> acc + (c.second ?: return null) }
            }

        private fun bump(ids: Set<String>?) {
            synchronized(changes) {
                val n = _changed.value + 1
                changes.addLast(n to ids)
                while (changes.size > 50) changes.removeFirst()
                _changed.value = n
            }
        }

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
            // A cloud answer that would drop most of this TV's charts is a fault (a bad deploy, a
            // half-built file), not a change: rows left now would also leave every page arranged
            // with them. Keep what's here until a sound answer comes
            val known = _lists.value.count { isChart(it) }
            if (all.isEmpty() || (known >= 20 && all.size < known / 2)) {
                Timber.w("Orca+ charts: the cloud sent %d charts for %d here; keeping these", all.size, known)
                return
            }
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
            // An answer without the Services / Genres pages (a half-built file) keeps the ones here:
            // taking it would take every tab's tile rows away until the next sound answer
            val pages = client.cloudPages().takeIf { it.pages.isNotEmpty() || _cloudPages.value.pages.isEmpty() } ?: _cloudPages.value
            if (pages != _cloudPages.value) {
                prefs.edit().putString(PAGES_KEY, json.encodeToString(com.wholphinplus.sources.core.CloudPages.serializer(), pages)).apply()
                _cloudPages.value = pages
            }
            if (kept != current || added.isNotEmpty() || pages.tiles != tilesBefore) {
                save(kept + added)
                // Charts came, went or moved pages: the Cinema pages rebuild with them
                com.wholphinplus.sources.cinema.CinemaCaches.homeChanged()
                bump(null)
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

        /**
         * Lists, charts and their accounts, for a cloud profile: what each list is, not what it
         * matched (that's this library's, up to 1,000 titles a list, and every TV redoes it).
         * Charts go too: page layouts name them by their id on this TV.
         */
        fun snapshot(): com.wholphinplus.sources.sync.ListsState =
            com.wholphinplus.sources.sync.ListsState(forSync(_lists.value), _traktClientId.value, _topStreaming.value)

        /** A cloud profile's lists, each keeping what this TV already matched for it. */
        fun restore(s: com.wholphinplus.sources.sync.ListsState) {
            if (s.traktClientId != _traktClientId.value) setTraktClientId(s.traktClientId)
            if (s.topStreaming != _topStreaming.value) {
                prefs.edit().putString(TOP_STREAMING_KEY, s.topStreaming).apply()
                _topStreaming.value = s.topStreaming
            }
            val merged = restored(_lists.value, s.lists)
            if (merged != _lists.value) save(merged)
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

        private val writePending = java.util.concurrent.atomic.AtomicBoolean(false)

        private fun save(list: List<HomeCollection>) {
            _lists.value = list
            // Off the caller's thread, and changes close together written once (a refresh updates
            // ~150 lists one after another; the file is several MB). The newest always wins
            // While a refresh runs (~150 lists, one after another) at most every 30 s, not every 2:
            // each write encodes the whole multi-MB set
            if (writePending.compareAndSet(false, true)) {
                writer.execute {
                    Thread.sleep(if (_refreshing.value != null) REFRESH_WRITE_DELAY_MS else WRITE_DELAY_MS)
                    writePending.set(false)
                    runCatching {
                        val tmp = java.io.File(listsFile.path + ".tmp")
                        java.io.FileOutputStream(tmp).use { out ->
                            val buffered = out.buffered()
                            json.encodeToStream(kotlinx.serialization.builtins.ListSerializer(HomeCollection.serializer()), _lists.value, buffered)
                            buffered.flush()
                            // On the disk before it replaces the old file (a power cut mid-write)
                            out.fd.sync()
                        }
                        if (!tmp.renameTo(listsFile)) error("Couldn't replace ${listsFile.name}")
                    }.onFailure { Timber.w(it, "Saving the lists failed") }
                }
            }
        }

        private fun load(): List<HomeCollection> {
            runCatching {
                if (listsFile.exists()) return listsFile.inputStream().buffered().use { json.decodeFromStream(kotlinx.serialization.builtins.ListSerializer(HomeCollection.serializer()), it) }
            }.onFailure { Timber.w(it, "Reading the lists failed") }
            // Before the lists had their own file: the preferences copy, moved over once
            val old = runCatching { prefs.getString(KEY, null)?.let { json.decodeFromString<List<HomeCollection>>(it) } }.getOrNull().orEmpty()
            if (old.isNotEmpty()) {
                runCatching { listsFile.writeText(json.encodeToString(old)) }.onSuccess { prefs.edit().remove(KEY).apply() }
            }
            return old
        }

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
                        var before = _lists.value.associate { it.id to it.itemIds }
                        // Rows changed: the Cinema pages load them now (they show what they gained);
                        // only when titles changed, saying which lists did (see changedSince)
                        fun announce() {
                            val after = _lists.value.associate { it.id to it.itemIds }
                            if (after == before) return
                            val ids = (after.keys + before.keys).filterTo(HashSet()) { after[it] != before[it] }
                            before = after
                            com.wholphinplus.sources.cinema.CinemaCaches.rowsRefreshed()
                            bump(ids)
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
                        // Kids-only charts wait until the Kids tab is on (or one is switched on elsewhere)
                        val layouts = hook.store.pageLayouts.value.values
                        fun waits(c: HomeCollection) =
                            isChart(c) && c.pages == listOf("KIDS") && !hook.store.kidsTab.value &&
                                layouts.none { l -> l.rows.any { it.on && it.ref == c.id } }
                        // The library index: read in the background the first time (minutes on a big
                        // server); meanwhile big lists match their first titles by search
                        val owner = hook.mainConnection()?.let(::ownerOf)
                        // Another server or user: what the lists matched (item ids) and the
                        // searches remembered are the old library's, so every list matches again
                        // (Not on the first run after this was added: the lists are this owner's)
                        val savedOwner = prefs.getString(OWNER_KEY, null)
                        val newOwner = owner != null && savedOwner != null && owner != savedOwner
                        if (newOwner) {
                            matchCache.clear()
                            saveMatches()
                        }
                        if (owner != null && owner != savedOwner) prefs.edit().putString(OWNER_KEY, owner).apply()
                        val indexJob = async { owner?.let { index.ensure(hook, it) } }
                        val partial = mutableListOf<String>()
                        val stale = _lists.value.filter { (onlyId == null || it.id == onlyId) && (newOwner || now - it.refreshedAt > maxAgeMs) && (onlyId != null || !waits(it)) }.sortedWith(compareBy({ group(it) }, { it.order?.get("HOME") ?: Int.MAX_VALUE }))
                        stale.forEachIndexed { i, c ->
                            if (i > 0 && group(c) != group(stale[i - 1])) announce()
                            // Never while something plays or Orca+ isn't on screen: a refresh is
                            // hundreds of requests, which competed with the video for the network
                            // ("Refresh now" for one list doesn't wait)
                            if (onlyId == null) com.wholphinplus.sources.cinema.Conductor.whenIdle()
                            _refreshing.value = c.name
                            if (!refreshOne(hook, c, indexJob)) partial += c.id
                        }
                        // Lists matched only in part (no index yet): all of them, once it's in
                        if (partial.isNotEmpty() && indexJob.await() != null) {
                            announce()
                            _lists.value.filter { it.id in partial }.forEach { c ->
                                com.wholphinplus.sources.cinema.Conductor.whenIdle()
                                _refreshing.value = c.name
                                refreshOne(hook, c, indexJob)
                            }
                        }
                        _refreshing.value = null
                        announce()
                        // Once a run, not once a list: searches remembered, and the cloud's charts
                        // kept without their 60k titles until a list needs them again
                        saveMatches()
                        client.trimCharts()
                    }
            }
        }

        /** Matches [c] to the library; false when only its first titles could be (no index yet). */
        private suspend fun refreshOne(
            hook: SourceHook,
            c: HomeCollection,
            indexJob: kotlinx.coroutines.Deferred<LibraryIndex.Snapshot?>? = null,
        ): Boolean {
            var whole = true
            val updated =
                try {
                    val source = ListSource.parse(c.url) ?: error("Not a Trakt, MDBList, Top Streaming or Orca+ chart link")
                    val all = client.fetch(source)
                    // The cloud keeps a chart's last good titles, so one sent empty is a fault: taken,
                    // it would empty the row (and take it off every page) for six hours
                    if (all.entries.isEmpty() && c.itemIds.isNotEmpty() && source is ListSource.OrcaChart) error("The Orca+ cloud sent no titles for this chart")
                    val main = hook.mainConnection() ?: error("Not signed in")
                    val idx = indexJob?.takeIf { it.isCompleted }?.await()
                    // Without the index a long list would be minutes of searches: its first titles now
                    val fetched = if (idx == null && all.entries.size > SEARCH_CAP) all.copy(entries = all.entries.take(SEARCH_CAP)).also { whole = false } else all
                    // The index answers at once (a miss there is a miss); only titles it can't answer
                    // (no index yet, a title without ids, a server whose titles carry none) are
                    // searched for on the server, six at a time
                    val matched = arrayOfNulls<String>(fetched.entries.size)
                    val toSearch = ArrayList<Int>()
                    fetched.entries.forEachIndexed { i, e ->
                        val part = idx?.let { if (e.type == TmdbType.TV) it.series else it.movies }?.takeIf { it.size > 0 }
                        if (part != null && (e.tmdbId != null || e.imdbId != null)) matched[i] = part.find(e.tmdbId, e.imdbId) else toSearch += i
                    }
                    // Titles the server couldn't be asked about (it didn't answer) aren't misses
                    val failed = java.util.concurrent.atomic.AtomicInteger()
                    if (toSearch.isNotEmpty()) {
                        val gate = Semaphore(6)
                        coroutineScope {
                            toSearch.map { i ->
                                async {
                                    gate.withPermit {
                                        matched[i] =
                                            try {
                                                search(hook, main, fetched.entries[i])
                                            } catch (ex: Exception) {
                                                if (ex is kotlinx.coroutines.CancellationException) throw ex
                                                failed.incrementAndGet()
                                                null
                                            }
                                    }
                                }
                            }.awaitAll()
                        }
                    }
                    // Each title found, with its place in the list
                    val found = matched.withIndex().mapNotNull { (i, id) -> id?.let { it to i + 1 } }.distinctBy { it.first }
                    val ids = found.map { it.first }
                    // A refresh that hit server errors never shrinks the row: keep it as it was
                    if (failed.get() > 0 && ids.size < c.itemIds.size) error("The server didn't answer for ${failed.get()} titles")
                    Timber.i("Home collection %s: %d of %d titles in the library%s", c.name, ids.size, all.entries.size, if (whole) "" else " (first ${fetched.entries.size} so far)")
                    c.copy(
                        name = if (c.name.isBlank() || c.name == PENDING_NAME) fetched.name else c.name,
                        itemIds = ids,
                        ranks = found.map { it.second },
                        listSize = all.entries.size,
                        refreshedAt = System.currentTimeMillis(),
                        error = null,
                    )
                } catch (e: Exception) {
                    if (e is kotlinx.coroutines.CancellationException) throw e
                    Timber.w(e, "Home collection %s failed", c.url)
                    // No network (or the server not answering): not "refreshed", so the next Home
                    // load tries again, and so does the network coming back. Stamped, the problem
                    // stayed on Settings → Your lists for six hours after the TV woke up
                    val offline = isNetwork(e)
                    if (offline) retryWhenOnline(hook)
                    c.copy(error = e.message ?: e.javaClass.simpleName, refreshedAt = if (offline) c.refreshedAt else System.currentTimeMillis())
                }
            // The list may have been edited meanwhile: keep the user's latest name/visibility
            _lists.value.firstOrNull { it.id == c.id }?.let { current ->
                update(updated.copy(showOnHome = current.showOnHome, name = if (current.name == c.name) updated.name else current.name))
            }
            return whole
        }

        private val waitingForNetwork = java.util.concurrent.atomic.AtomicBoolean(false)

        /**
         * Once the TV is back online (it had none when a list failed), lists that failed for want of
         * it are fetched again. With a network up (a site down) the next Home load retries them.
         */
        private fun retryWhenOnline(hook: SourceHook) {
            val cm = appContext.getSystemService(android.net.ConnectivityManager::class.java) ?: return
            runCatching {
                val caps = cm.activeNetwork?.let { cm.getNetworkCapabilities(it) }
                if (caps?.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true) return
                if (!waitingForNetwork.compareAndSet(false, true)) return
                cm.registerDefaultNetworkCallback(
                    object : android.net.ConnectivityManager.NetworkCallback() {
                        override fun onCapabilitiesChanged(
                            network: android.net.Network,
                            capabilities: android.net.NetworkCapabilities,
                        ) {
                            if (!capabilities.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_VALIDATED)) return
                            if (!waitingForNetwork.compareAndSet(true, false)) return
                            runCatching { cm.unregisterNetworkCallback(this) }
                            scope.launch {
                                // Name lookups can trail the network by a moment after a wake
                                kotlinx.coroutines.delay(NETWORK_SETTLE_MS)
                                if (_lists.value.any { it.error != null }) refreshStale(hook)
                            }
                        }
                    },
                )
            }.onFailure {
                waitingForNetwork.set(false)
                Timber.w(it, "Can't watch for the network coming back")
            }
        }

        /** One title found on the server by a search (remembered, misses too, for a day). */
        private suspend fun search(
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
            private const val OWNER_KEY = "matches_owner"
            private const val REFRESH_WRITE_DELAY_MS = 30_000L
            const val PENDING_NAME = "Loading list…"
            const val STALE_MS = 6 * 60 * 60 * 1000L
            private const val NETWORK_SETTLE_MS = 3_000L

            /** A failure for want of a network or an answer (worth trying again soon), not a bad list. */
            internal fun isNetwork(e: Throwable): Boolean =
                generateSequence(e) { it.cause }.take(8).any { it is java.io.IOException || (it is com.wholphinplus.sources.core.ServerRequestException && (it.statusCode >= 500 || it.statusCode == 429)) } ||
                    e.message?.startsWith("The server didn't answer") == true

            private const val WRITE_DELAY_MS = 2_000L

            /** [lists] as a cloud profile carries them: without what this library matched. */
            fun forSync(lists: List<HomeCollection>): List<HomeCollection> = lists.map { it.copy(itemIds = emptyList(), ranks = emptyList(), listSize = 0, refreshedAt = 0L, error = null) }

            /**
             * A profile's [incoming] lists over this TV's [local] ones: each keeps what this TV
             * matched for it (by id, else the same link); the rest match at the next refresh.
             */
            fun restored(
                local: List<HomeCollection>,
                incoming: List<HomeCollection>,
            ): List<HomeCollection> {
                val byId = local.associateBy { it.id }
                val byUrl = local.associateBy { it.url }
                return incoming.map { r ->
                    (byId[r.id]?.takeIf { it.url == r.url } ?: byUrl[r.url])
                        ?.let { l -> r.copy(itemIds = l.itemIds, ranks = l.ranks, listSize = l.listSize, refreshedAt = l.refreshedAt, error = l.error) }
                        ?: r
                }
            }

            /** Titles of a list matched by search while there's no library index yet. */
            private const val SEARCH_CAP = 120

            /** Fewest library titles an Orca+ chart row shows with ([MIN_TOP] for a numbered Top 10). */
            const val MIN_ROW = 5
            const val MIN_TOP = 3
        }
    }
