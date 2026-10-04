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
) {
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
                if (job?.isActive == true) return
                job =
                    scope.launch {
                        val now = System.currentTimeMillis()
                        // Titles not found are retried daily, so new library additions appear
                        if (now - prefs.getLong(MISS_RESET_KEY, 0L) > 24 * 60 * 60 * 1000L) {
                            forgetMisses()
                            prefs.edit().putLong(MISS_RESET_KEY, now).apply()
                        }
                        for (c in _lists.value.filter { (onlyId == null || it.id == onlyId) && now - it.refreshedAt > maxAgeMs }) {
                            _refreshing.value = c.name
                            refreshOne(hook, c)
                        }
                        _refreshing.value = null
                    }
            }
        }

        private suspend fun refreshOne(
            hook: SourceHook,
            c: HomeCollection,
        ) {
            val updated =
                try {
                    val source = ListSource.parse(c.url) ?: error("Not a Trakt or MDBList link")
                    val fetched = client.fetch(source)
                    val main = hook.mainConnection() ?: error("Not signed in")
                    val gate = Semaphore(6)
                    val ids =
                        coroutineScope {
                            fetched.entries.map { e -> async { gate.withPermit { match(hook, main, e) } } }.awaitAll()
                        }.filterNotNull().distinct()
                    saveMatches()
                    Timber.i("Home collection %s: %d of %d titles in the library", c.name, ids.size, fetched.entries.size)
                    c.copy(
                        name = if (c.name.isBlank() || c.name == PENDING_NAME) fetched.name else c.name,
                        itemIds = ids,
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
            val id =
                runCatching {
                    if (e.type == TmdbType.TV) hook.client.matchSeriesIds(main, request) else hook.client.matchItemIds(main, request)
                }.getOrNull()?.firstOrNull()
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
            private const val TRAKT_KEY = "trakt_client_id"
            private const val MISS_RESET_KEY = "miss_reset_at"
            const val PENDING_NAME = "Loading list…"
            const val STALE_MS = 6 * 60 * 60 * 1000L
        }
    }
