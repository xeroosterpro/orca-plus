package com.wholphinplus.sources

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.decodeFromStream
import kotlinx.serialization.json.encodeToStream
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import okhttp3.HttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Interceptor
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import timber.log.Timber
import java.time.Duration
import java.time.Instant
import java.time.OffsetDateTime
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Watch progress the main server doesn't know about: things watched on the extra servers, or
 * streamed from them in Orca+, and (for servers like Silo that ignore every client progress
 * write) what was watched from the main server itself. Instead of writing it there, this
 * interceptor sits on Wholphin's connection to the main server and merges it into the answers.
 * Continue Watching, Next Up, details pages and Resume all see it. Nothing is ever sent to the
 * main server.
 *
 * Kept per Jellyfin user (each user's own history; the cloud profile carries its user's only).
 * Progress saved before that (one list for the whole TV) belongs to the first user seen after the
 * update: the one signed in, so nothing is lost.
 */
@OptIn(ExperimentalSerializationApi::class)
@Singleton
class ProgressOverlay internal constructor(
    private val file: java.io.File?,
    private val legacy: android.content.SharedPreferences?,
    private val collections: CollectionLookup?,
    /** The signed-in user Orca+ already knows (cached at sign-in), for moving old progress to. */
    private val knownUser: () -> String? = { null },
) : Interceptor {
        /** Test and tool use: progress kept in memory only. */
        internal constructor(prefs: android.content.SharedPreferences?, collections: CollectionLookup?) : this(null, prefs, collections)

        /** Removed from Continue Watching ([dismiss]) on disk, next to the progress file. */
        private val dismissedFile: java.io.File? get() = file?.let { java.io.File(it.parentFile, "progress_dismissed.json") }

        @Inject
        constructor(
            @ApplicationContext context: Context,
            collections: HomeCollections,
        ) : this(
            java.io.File(context.filesDir, "progress_v2.json"),
            context.getSharedPreferences("wholphinplus_progress", Context.MODE_PRIVATE),
            collections,
            {
                context.getSharedPreferences("wholphinplus_main_user", Context.MODE_PRIVATE).getString("user_id", null)
            },
        )

        @Serializable
        data class Entry(
            val positionTicks: Long,
            val played: Boolean,
            /** Epoch millis. */
            val lastPlayed: Long,
            val seriesId: String? = null,
            val title: String = "",
            /**
             * The server itself has shown this title watched since it was marked here: it keeps
             * watched marks (Silo never does). Such a server answering "not watched" later means
             * it was marked unwatched elsewhere (Jellyfin leaves the last-played date as it was).
             */
            val serverSaw: Boolean = false,
        )

        private val json = Json { ignoreUnknownKeys = true }

        /**
         * Removed from Continue Watching, by user, then key ([norm] item id, or [SERIES_KEY] + show
         * id): when. A title stays out until it's played again after that (a show: any of its
         * episodes). Never deleted on a play, so two TVs merging (newest wins) can't bring one back.
         */
        private val dismissed = ConcurrentHashMap<String, ConcurrentHashMap<String, Long>>()

        /** Progress by user (normalized id; [LEGACY] for progress kept before users), then by item. */
        private val users = ConcurrentHashMap<String, ConcurrentHashMap<String, Entry>>()

        /** The user Wholphin is signed in as, from its own requests (null until the first one). */
        @Volatile private var current: String? = null

        // Read off the main thread: this is built while the app starts (Hilt, for the API client)
        private val loaded = java.util.concurrent.CountDownLatch(1)
        private val writer = java.util.concurrent.Executors.newSingleThreadExecutor { r -> Thread(r, "orca-progress").apply { isDaemon = true } }
        private val writePending = java.util.concurrent.atomic.AtomicBoolean(false)

        init {
            if (file == null && legacy == null) {
                loaded.countDown()
            } else {
                writer.execute {
                    try {
                        load()
                    } finally {
                        loaded.countDown()
                    }
                }
            }
        }

        private fun ready() {
            if (loaded.count > 0) loaded.await()
        }

        /** Wholphin's signed-in user ([userId] as the server writes it); old progress moves to the first one. */
        fun setUser(userId: String?) {
            val u = userId?.takeIf { it.isNotBlank() }?.let(::norm) ?: return
            if (current == u) return
            ready()
            current = u
            bucket(u)
        }

        /** [user]'s progress (made, and given the progress kept before users, the first time). */
        private fun bucket(user: String?): ConcurrentHashMap<String, Entry> {
            val u = user?.let(::norm) ?: current ?: LEGACY
            users[u]?.let { if (u == LEGACY || users[LEGACY].isNullOrEmpty()) return it }
            synchronized(users) {
                val b = users.getOrPut(u) { ConcurrentHashMap() }
                if (u != LEGACY) {
                    users.remove(LEGACY)?.takeIf { it.isNotEmpty() }?.let { old ->
                        old.forEach { (k, e) -> if ((b[k]?.lastPlayed ?: Long.MIN_VALUE) < e.lastPlayed) b[k] = e }
                        Timber.i("Progress overlay: %d titles kept from before users moved to the signed-in user", old.size)
                        save()
                    }
                }
                return b
            }
        }

        /** Record progress for a main-server item ([user]: the signed-in one). Older information never replaces newer. */
        fun record(
            mainItemId: String,
            entry: Entry,
            user: String? = null,
        ) {
            ready()
            val b = bucket(user)
            val key = norm(mainItemId)
            val now = b[key]
            if (now != null && now.lastPlayed > entry.lastPlayed) return
            b[key] = entry
            seriesCache.clear()
            prune(b)
            save()
            _changes.value += 1
        }

        private val _changes = kotlinx.coroutines.flow.MutableStateFlow(0L)

        /** Bumped whenever a play is recorded: Continue Watching's titles or order changed. */
        val changes: kotlinx.coroutines.flow.StateFlow<Long> = _changes

        /** When anything of [seriesId] was last played here (an episode's show), or null. */
        fun seriesLastPlayed(seriesId: String): Long? {
            ready()
            val s = norm(seriesId)
            return bucket(null).values.filter { e -> e.seriesId?.let(::norm) == s }.maxOfOrNull { it.lastPlayed }
        }

        private fun userKey(user: String?): String = user?.let(::norm) ?: current ?: LEGACY

        private fun dismissals(user: String? = null): ConcurrentHashMap<String, Long> {
            val u = userKey(user)
            val d = dismissed.getOrPut(u) { ConcurrentHashMap() }
            // Like the progress: what was kept before a user was known belongs to the first one
            if (u != LEGACY) dismissed.remove(LEGACY)?.forEach { (k, at) -> d.merge(k, at, ::maxOf) }
            return d
        }

        /**
         * "Remove from Continue Watching": [itemId] (and, for an episode, its show [seriesId]) stays
         * out of Continue Watching and Next Up until something of it is played after [at].
         */
        fun dismiss(
            itemId: String,
            seriesId: String? = null,
            at: Long = System.currentTimeMillis(),
            user: String? = null,
        ) {
            ready()
            val d = dismissals(user)
            d.merge(norm(itemId), at, ::maxOf)
            seriesId?.takeIf { it.isNotBlank() }?.let { d.merge(SERIES_KEY + norm(it), at, ::maxOf) }
            pruneDismissed(d)
            saveDismissed()
            _changes.value += 1
        }

        /**
         * "Mark as watched" from Continue Watching: kept here (the main server may ignore it), so the
         * title leaves the row and a show moves on to its next episode.
         */
        fun markWatched(
            itemId: String,
            seriesId: String? = null,
            title: String = "",
            at: Long = System.currentTimeMillis(),
            user: String? = null,
        ) {
            ready()
            val before = bucket(user)[norm(itemId)]
            record(itemId, Entry(0L, played = true, lastPlayed = maxOf(at, (before?.lastPlayed ?: 0L) + 1), seriesId = seriesId ?: before?.seriesId, title = title.ifBlank { before?.title.orEmpty() }), user)
        }

        /** Whether [itemId] (of show [seriesId]), last played at [lastPlayed] by the server's account, is out of Continue Watching. */
        fun isDismissed(
            itemId: String,
            seriesId: String?,
            lastPlayed: Long = 0L,
        ): Boolean {
            ready()
            val d = dismissals()
            if (d.isEmpty()) return false
            val own = maxOf(lastPlayed, bucket(null)[norm(itemId)]?.lastPlayed ?: 0L)
            val show = seriesId?.let { maxOf(own, seriesLastPlayed(it) ?: 0L) } ?: own
            return hiddenBy(d, norm(itemId), seriesId?.let(::norm), own, show)
        }

        /** [user]'s removals from Continue Watching, for their cloud profile. */
        fun dismissedSnapshot(user: String? = null): Map<String, Long> {
            ready()
            return HashMap(dismissals(user))
        }

        /** Removals from [user]'s cloud profile; per key the later one stays. */
        fun absorbDismissed(
            more: Map<String, Long>,
            user: String? = null,
        ) {
            ready()
            val d = dismissals(user)
            var changed = false
            more.forEach { (k, at) ->
                val now = d[k]
                if (now == null || now < at) {
                    d[k] = at
                    changed = true
                }
            }
            if (changed) {
                pruneDismissed(d)
                saveDismissed()
                _changes.value += 1
            }
        }

        /** Every remembered position of [user] (the signed-in one when null), for their cloud profile. */
        fun snapshot(user: String? = null): Map<String, Entry> {
            ready()
            return HashMap(bucket(user))
        }

        /** Positions from [user]'s cloud profile; per title the newer one stays. */
        fun absorb(
            more: Map<String, Entry>,
            user: String? = null,
        ) {
            ready()
            val b = bucket(user)
            var changed = false
            more.forEach { (id, e) ->
                val key = norm(id)
                val now = b[key]
                if (now == null || now.lastPlayed < e.lastPlayed) {
                    b[key] = e
                    changed = true
                }
            }
            if (changed) {
                seriesCache.clear()
                prune(b)
                save()
                _changes.value += 1
            }
        }

        /** Whether [mainItemId] is marked watched here. */
        fun isWatched(mainItemId: String): Boolean {
            ready()
            return bucket(null)[norm(mainItemId)]?.played == true
        }

        fun lastPlayed(mainItemId: String): Long? {
            ready()
            return bucket(null)[norm(mainItemId)]?.lastPlayed
        }

        // ------------------------------------------------------------ interceptor

        override fun intercept(chain: Interceptor.Chain): Response {
            ready()
            val request = chain.request()
            userOf(request.url)?.let(::setUser)
            listRow(chain)?.let { return it }
            val response = chain.proceed(request)
            noteMark(request, response)
            val entries = bucket(null)
            if (request.method != "GET" || !response.isSuccessful) return response
            val path = request.url.encodedPath
            val isNextUp = path.endsWith("/Shows/NextUp", ignoreCase = true)
            // One show's Next Up (its title page): checked against the show's own history even
            // with nothing kept here
            val oneSeries = if (isNextUp) param(request.url, "seriesId") else null
            val gone = dismissals()
            if (entries.isEmpty() && oneSeries == null && gone.isEmpty()) return response
            // Asked without watch data (the library index's 1,000-title pages): nothing to merge,
            // and copying and scanning them would undo what makes them light
            if ((request.url.queryParameter("enableUserData") ?: request.url.queryParameter("EnableUserData")).equals("false", true)) return response
            val type = response.body.contentType()
            if (type?.subtype?.contains("json") != true) return response
            // /UserItems/Resume (Jellyfin 10.9+, what Wholphin calls) or /Users/{id}/Items/Resume
            val isResume = path.endsWith("Items/Resume", ignoreCase = true)
            val text = response.body.string()
            val rebuilt = { body: String -> response.newBuilder().body(body.toResponseBody(type)).build() }
            if (!isResume && !isNextUp && !mentionsAny(text, entries)) return rebuilt(text)
            return try {
                var root = json.parseToJsonElement(text)
                if (isResume) root = mergeResume(chain, request, root, entries)
                if (isNextUp) root = mergeNextUp(chain, request, root, entries)
                if (oneSeries != null) root = latestForSeries(chain, request, root, entries)
                // Removed from Continue Watching (not one show's own Next Up: its page still offers it)
                if ((isResume || (isNextUp && oneSeries == null)) && gone.isNotEmpty()) root = withoutDismissed(root, entries, gone)
                val patched = patch(root, entries)
                // Nothing of ours in it after all: the server's own text, not a re-encoded copy
                if (patched === root && !isResume && !isNextUp) rebuilt(text) else rebuilt(patched.toString())
            } catch (e: Exception) {
                Timber.w(e, "Progress overlay skipped %s", path)
                rebuilt(text)
            }
        }

        /** The user a request is made for: its userId parameter, or the id after /Users/ in the path. */
        private fun userOf(url: HttpUrl): String? {
            url.queryParameterNames.firstOrNull { it.equals("userId", true) }?.let { n -> url.queryParameter(n)?.takeIf { it.isNotBlank() }?.let { return it } }
            val segs = url.pathSegments
            val i = segs.indexOfFirst { it.equals("Users", true) }
            return if (i >= 0 && i + 1 < segs.size) segs[i + 1].takeIf { ID_CHARS.matches(it) } else null
        }

        /**
         * "Mark as watched / unwatched" (POST / DELETE …/PlayedItems/{id}): kept here too. A server
         * that ignores progress writes (Silo) ignores these as well, and an older position kept
         * here would otherwise undo the mark.
         */
        private fun noteMark(
            request: Request,
            response: Response,
        ) {
            val method = request.method
            if (method != "POST" && method != "DELETE") return
            if (response.code == 401 || response.code == 403 || response.code >= 500) return
            val segs = request.url.pathSegments
            val i = segs.indexOfLast { it.equals("UserPlayedItems", true) || it.equals("PlayedItems", true) }
            if (i < 0 || i + 1 >= segs.size) return
            val id = segs[i + 1].takeIf { ID_CHARS.matches(it) } ?: return
            val before = bucket(null)[norm(id)]
            record(id, Entry(0L, played = method == "POST", lastPlayed = System.currentTimeMillis(), seriesId = before?.seriesId, title = before?.title.orEmpty()))
        }

        /** The server shows [id] watched too: remembered quietly (no Continue Watching change). */
        private fun serverSaw(
            id: String,
            entry: Entry,
        ) {
            val b = bucket(null)
            val key = norm(id)
            if (b[key] === entry || b[key] == entry) {
                b[key] = entry.copy(serverSaw = true)
                save()
            }
        }

        /** [id] was marked unwatched on its server after Orca+ saw it watched there: kept as a newer "not watched". */
        private fun unwatchedElsewhere(
            id: String,
            entry: Entry,
        ) {
            Timber.i("Progress overlay: %s was marked unwatched on the server; the watched mark here is dropped", id)
            record(id, Entry(0L, played = false, lastPlayed = maxOf(System.currentTimeMillis(), entry.lastPlayed + 1), seriesId = entry.seriesId, title = entry.title))
        }

        /**
         * A home row for a Trakt/MDBList collection asks for items tagged with the collection's
         * tag. Answer with the matched library items instead, in list order, honouring paging.
         */
        private fun listRow(chain: Interceptor.Chain): Response? {
            val request = chain.request()
            if (request.method != "GET" || collections == null) return null
            val url = request.url
            val tagParam = url.queryParameterNames.firstOrNull { it.equals("tags", true) } ?: return null
            val tag = url.queryParameterValues(tagParam).flatMap { it.orEmpty().split('|', ',') }
                .firstOrNull { it.startsWith(HomeCollection.TAG_PREFIX) } ?: return null
            val ids = collections.itemIdsForTag(tag).orEmpty()
            val start = (url.queryParameter("startIndex") ?: url.queryParameter("StartIndex"))?.toIntOrNull() ?: 0
            // At most 200 an answer (a list can hold 1,000; TotalRecordCount says how many, so a
            // pager asks again), fetched 100 ids a request so the address stays short
            val limit = (limitOf(request) ?: MAX_LIST_PAGE).coerceIn(0, MAX_LIST_PAGE)
            val page = ids.drop(start).take(limit)
            val items =
                if (page.isEmpty()) {
                    emptyList()
                } else {
                    val byId =
                        page.chunked(100).flatMap { chunk ->
                            val rewritten =
                                url
                                    .newBuilder()
                                    .apply {
                                        listOf(tagParam, "startIndex", "StartIndex", "limit", "Limit", "sortBy", "SortBy", "sortOrder", "SortOrder", "includeItemTypes", "IncludeItemTypes")
                                            .forEach { removeAllQueryParameters(it) }
                                    }.addQueryParameter("ids", chunk.joinToString(",") { dashed(it) })
                                    .build()
                            listItems(chain, request, rewritten)
                        }.associateBy { norm((it["Id"] as? JsonPrimitive)?.content.orEmpty()) }
                    val entries = bucket(null)
                    page.mapNotNull { byId[norm(it)] }.map { patch(it, entries) as JsonObject }
                }
            val body =
                JsonObject(
                    mapOf(
                        "Items" to JsonArray(items),
                        "TotalRecordCount" to JsonPrimitive(ids.size),
                        "StartIndex" to JsonPrimitive(start),
                    ),
                ).toString()
            return Response
                .Builder()
                .request(request)
                .protocol(okhttp3.Protocol.HTTP_1_1)
                .code(200)
                .message("OK")
                .body(body.toResponseBody(JSON_TYPE))
                .build()
        }

        /**
         * Overwrite UserData wherever an item we know newer progress for appears. Parts with nothing
         * of ours come back as the same objects (no copy of a 500-title answer per request).
         */
        private fun patch(
            element: JsonElement,
            entries: Map<String, Entry>,
        ): JsonElement =
            when (element) {
                is JsonArray -> {
                    var out: ArrayList<JsonElement>? = null
                    element.forEachIndexed { i, v ->
                        val p = patch(v, entries)
                        if (p !== v && out == null) out = ArrayList<JsonElement>(element.size).apply { addAll(element.subList(0, i)) }
                        out?.add(p)
                    }
                    out?.let { JsonArray(it) } ?: element
                }
                is JsonObject -> {
                    var out: LinkedHashMap<String, JsonElement>? = null
                    for ((k, v) in element) {
                        if (v !is JsonArray && v !is JsonObject) continue
                        val p = patch(v, entries)
                        if (p !== v) (out ?: LinkedHashMap(element).also { out = it })[k] = p
                    }
                    val patched = out?.let { JsonObject(it) } ?: element
                    val id = (element["Id"] as? JsonPrimitive)?.content
                    val entry = id?.let { entries[norm(it)] }
                    if (entry != null && patched["UserData"] is JsonObject) withUserData(patched, entry) else patched
                }
                else -> element
            }

        private fun withUserData(
            item: JsonObject,
            entry: Entry,
        ): JsonObject {
            val data = item["UserData"]!!.jsonObject
            if (entry.played) {
                val id = (item["Id"] as? JsonPrimitive)?.content
                when ((data["Played"] as? JsonPrimitive)?.content) {
                    "true" -> if (!entry.serverSaw && id != null) serverSaw(id, entry)
                    // Marked unwatched elsewhere, on a server that keeps marks: that wins here
                    // too (watch history never expires, so the old mark would undo it for good)
                    "false" -> if (entry.serverSaw && id != null) {
                        unwatchedElsewhere(id, entry)
                        return item
                    }
                }
            }
            val serverLast = (data["LastPlayedDate"] as? JsonPrimitive)?.content?.let(::parseMillis) ?: 0L
            if (serverLast >= entry.lastPlayed) return item
            val runtime = (item["RunTimeTicks"] as? JsonPrimitive)?.content?.toLongOrNull()
            val newData =
                JsonObject(
                    data +
                        mapOf(
                            "PlaybackPositionTicks" to JsonPrimitive(if (entry.played) 0L else entry.positionTicks),
                            "Played" to JsonPrimitive(entry.played),
                            "LastPlayedDate" to JsonPrimitive(Instant.ofEpochMilli(entry.lastPlayed).toString()),
                        ) +
                        (
                            if (runtime != null && runtime > 0 && !entry.played) {
                                mapOf("PlayedPercentage" to JsonPrimitive(entry.positionTicks * 100.0 / runtime))
                            } else {
                                emptyMap()
                            }
                        ),
                )
            return JsonObject(item + ("UserData" to newData))
        }

        private fun mergeResume(
            chain: Interceptor.Chain,
            request: Request,
            root: JsonElement,
            entries: Map<String, Entry>,
        ): JsonElement {
            val obj = root as? JsonObject ?: return root
            val items = (obj["Items"] as? JsonArray)?.filterIsInstance<JsonObject>() ?: return root
            val present = items.mapNotNull { (it["Id"] as? JsonPrimitive)?.content?.let(::norm) }.toSet()
            val cutoff = System.currentTimeMillis() - RECENT.toMillis()
            val excludeEpisodes = request.url.queryParameterValues("excludeItemTypes").any { it?.contains("Episode", true) == true }
            val wanted =
                entries
                    .filter { (id, e) -> !e.played && e.positionTicks > 0 && e.lastPlayed > cutoff && id !in present }
                    .filter { (_, e) -> !(excludeEpisodes && e.seriesId != null) }
                    // The most recently watched, when there are more than fit
                    .entries
                    .sortedByDescending { it.value.lastPlayed }
                    .take(MAX_INJECT)
                    .map { it.key }
            val fetched = if (wanted.isEmpty()) emptyList() else fetchItems(chain, request, wanted)
            // An episode kept here is only offered while it's where the show was left: later
            // episodes watched since (on another device too) make it old news, and Continue
            // Watching, the billboard and the show's page would each name another episode
            val extra =
                fetched.mapNotNull { item ->
                    val p = patch(item, entries) as JsonObject
                    if (p["SeriesId"] == null) return@mapNotNull p
                    val now = latestFrom(chain, request, p, entries)
                    if (now === p) p else now.takeIf(::inProgress)
                }
            Timber.i("Progress overlay: Continue Watching %d from server, %d wanted, %d added (%d known)", items.size, wanted.size, extra.size, entries.size)
            // A half-watched episode of a show with a later one finished since (here): left off past it
            val finishedAt = HashMap<String, Long>()
            entries.values.filter { it.played && it.seriesId != null }.forEach { e -> finishedAt.merge(norm(e.seriesId!!), e.lastPlayed, ::maxOf) }
            val merged =
                (items.map { patch(it, entries) as JsonObject } + extra)
                    .distinctBy { (it["Id"] as? JsonPrimitive)?.content?.let(::norm) }
                    .filterNot { item -> str(item, "SeriesId")?.let { finishedAt[norm(it)] }?.let { it > lastPlayedOf(item) } == true }
                    // Watched elsewhere since: drop from Continue Watching
                    .filterNot { (it["UserData"] as? JsonObject)?.get("Played")?.let { p -> (p as JsonPrimitive).content == "true" } == true }
                    .sortedByDescending { lastPlayedOf(it) }
            val limit = limitOf(request) ?: merged.size
            return JsonObject(obj + ("Items" to JsonArray(merged.take(limit))))
        }

        /** [root]'s Items without the titles removed from Continue Watching ([dismiss]). */
        private fun withoutDismissed(
            root: JsonElement,
            entries: Map<String, Entry>,
            gone: Map<String, Long>,
        ): JsonElement {
            val obj = root as? JsonObject ?: return root
            val items = (obj["Items"] as? JsonArray)?.filterIsInstance<JsonObject>() ?: return root
            val seriesLast = HashMap<String, Long>()
            entries.values.forEach { e -> e.seriesId?.let { seriesLast.merge(norm(it), e.lastPlayed, ::maxOf) } }
            val kept =
                items.filterNot { item ->
                    val id = str(item, "Id")?.let(::norm) ?: return@filterNot false
                    val series = str(item, "SeriesId")?.let(::norm)
                    val own = maxOf(lastPlayedOf(item), entries[id]?.lastPlayed ?: 0L)
                    hiddenBy(gone, id, series, own, maxOf(own, series?.let { seriesLast[it] } ?: 0L))
                }
            if (kept.size == items.size) return root
            Timber.i("Progress overlay: %d removed from Continue Watching left out", items.size - kept.size)
            return JsonObject(obj + ("Items" to JsonArray(kept)))
        }

        private fun mergeNextUp(
            chain: Interceptor.Chain,
            request: Request,
            root: JsonElement,
            entries: Map<String, Entry>,
        ): JsonElement {
            val obj = root as? JsonObject ?: return root
            val items = (obj["Items"] as? JsonArray)?.filterIsInstance<JsonObject>() ?: return root
            val cutoff = System.currentTimeMillis() - RECENT.toMillis()
            // One show's Next Up (its title page) is about that show only: another show's
            // episode put first here made its page offer "Resume" of a different show
            val only = param(request.url, "seriesId")?.let(::norm)
            // Latest episode finished elsewhere, per series
            val finished =
                entries
                    .filter { (_, e) -> e.played && e.seriesId != null && e.lastPlayed > cutoff && (only == null || norm(e.seriesId) == only) }
                    .entries
                    .groupBy { it.value.seriesId!! }
                    .mapValues { (_, list) -> list.maxBy { it.value.lastPlayed } }
            if (finished.isEmpty()) return root
            val bySeries = items.associateBy { (it["SeriesId"] as? JsonPrimitive)?.content?.let(::norm) }
            val replacements =
                finished.entries
                    .sortedByDescending { it.value.value.lastPlayed }
                    .take(MAX_INJECT)
                    .mapNotNull { (seriesId, done) ->
                        val serverEntry = bySeries[norm(seriesId)]
                        // The server's own Next Up is newer than what we know: keep it
                        if (serverEntry != null && lastPlayedOf(serverEntry) >= done.value.lastPlayed) return@mapNotNull null
                        nextEpisode(chain, request, seriesId, done.key)?.let { norm(seriesId) to it }
                    }.toMap()
            if (replacements.isEmpty()) return root
            val kept = items.filterNot { (it["SeriesId"] as? JsonPrimitive)?.content?.let(::norm) in replacements.keys }
            val limit = limitOf(request) ?: (kept.size + replacements.size)
            return JsonObject(obj + ("Items" to JsonArray((replacements.values + kept).take(limit))))
        }

        private fun nextEpisode(
            chain: Interceptor.Chain,
            request: Request,
            seriesId: String,
            finishedEpisodeId: String,
        ): JsonObject? {
            val url =
                serverBase(request.url)
                    .addPathSegments("Shows/$seriesId/Episodes")
                    .copyQuery(request.url, "userId", "fields", "Fields", "enableImageTypes", "imageTypeLimit")
                    .addQueryParameter("startItemId", dashed(finishedEpisodeId))
                    .addQueryParameter("limit", "2")
                    .addQueryParameter("enableUserData", "true")
                    .build()
            val episodes = getItems(chain, request, url)
            return episodes.getOrNull(1)?.takeIf { (it["UserData"] as? JsonObject)?.get("Played")?.toString() != "true" }
        }

        /**
         * One show's Next Up, by where it was really left: a server can answer with an episode
         * left half-watched long ago although later ones were watched since (Silo did: the show's
         * page said "Resume S2:E7" while Continue Watching had moved on to S2:E14).
         */
        private fun latestForSeries(
            chain: Interceptor.Chain,
            request: Request,
            root: JsonElement,
            entries: Map<String, Entry>,
        ): JsonElement {
            val obj = root as? JsonObject ?: return root
            val first = (obj["Items"] as? JsonArray)?.firstOrNull() as? JsonObject ?: return root
            val now = latestFrom(chain, request, patch(first, entries) as JsonObject, entries)
            if (str(now, "Id") == str(first, "Id")) return root
            Timber.i("Progress overlay: Next Up for one show moved on from a half-watched episode to where it was left")
            return JsonObject(obj + ("Items" to JsonArray(listOf(now))))
        }

        /**
         * [episode] while it's half-watched and nothing after it was watched later; otherwise
         * where the show was really left ([nextByLatest]): a later half-watched episode, or the one
         * after the latest finished. Finished or unknown: [episode] itself (the same object).
         */
        private fun latestFrom(
            chain: Interceptor.Chain,
            request: Request,
            episode: JsonObject,
            entries: Map<String, Entry>,
        ): JsonObject {
            if (!inProgress(episode)) return episode
            val seriesId = str(episode, "SeriesId") ?: return episode
            val id = str(episode, "Id") ?: return episode
            val key = "$current|${norm(id)}|${request.url.queryParameterValues("fields").size}"
            seriesCache[key]?.let { (at, pick) -> if (System.currentTimeMillis() - at < SERIES_CACHE_MS) return if (str(pick, "Id") == id) episode else pick }
            // The show's episodes from this one on (Silo ignores a limit; a few hundred at most)
            val url =
                serverBase(request.url)
                    .addPathSegments("Shows/$seriesId/Episodes")
                    .copyQuery(request.url, "userId", "fields", "Fields", "enableImageTypes", "imageTypeLimit")
                    .addQueryParameter("startItemId", dashed(id))
                    .addQueryParameter("enableUserData", "true")
                    .build()
            val eps = getItems(chain, request, url).map { patch(it, entries) as JsonObject }
            // The answer has to start at this episode, or its order says nothing
            if (eps.isEmpty() || str(eps[0], "Id")?.let(::norm) != norm(id)) return episode
            val i = nextByLatest(eps.map(::watchOf)) ?: 0
            val pick = if (i == 0) episode else eps[i]
            seriesCache[key] = System.currentTimeMillis() to pick
            return pick
        }

        private fun inProgress(item: JsonObject): Boolean {
            val data = item["UserData"] as? JsonObject ?: return false
            val played = (data["Played"] as? JsonPrimitive)?.content == "true"
            val pos = (data["PlaybackPositionTicks"] as? JsonPrimitive)?.content?.toLongOrNull() ?: 0L
            return !played && pos > 0
        }

        private fun watchOf(item: JsonObject): Watch {
            val data = item["UserData"] as? JsonObject
            return Watch(
                season = (item["ParentIndexNumber"] as? JsonPrimitive)?.content?.toIntOrNull(),
                played = (data?.get("Played") as? JsonPrimitive)?.content == "true",
                positionTicks = (data?.get("PlaybackPositionTicks") as? JsonPrimitive)?.content?.toLongOrNull() ?: 0L,
                lastPlayed = lastPlayedOf(item),
            )
        }

        private fun str(
            item: JsonObject,
            name: String,
        ): String? = (item[name] as? JsonPrimitive)?.takeIf { it.isString }?.content?.takeIf { it.isNotBlank() }

        private fun param(
            url: HttpUrl,
            name: String,
        ): String? = url.queryParameterNames.firstOrNull { it.equals(name, true) }?.let { url.queryParameter(it) }?.takeIf { it.isNotBlank() }

        private fun fetchItems(
            chain: Interceptor.Chain,
            request: Request,
            ids: Collection<String>,
        ): List<JsonObject> {
            val url =
                serverBase(request.url)
                    .addPathSegment("Items")
                    .copyQuery(request.url, "userId", "fields", "Fields", "enableImageTypes", "imageTypeLimit")
                    .addQueryParameter("ids", ids.joinToString(",") { dashed(it) })
                    .addQueryParameter("enableUserData", "true")
                    .build()
            return getItems(chain, request, url)
        }

        private fun getItems(
            chain: Interceptor.Chain,
            original: Request,
            url: HttpUrl,
        ): List<JsonObject> =
            runCatching {
                chain.proceed(original.newBuilder().url(url).get().build()).use { r ->
                    if (!r.isSuccessful) return@use emptyList()
                    (json.parseToJsonElement(r.body.string()).jsonObject["Items"] as? JsonArray)?.filterIsInstance<JsonObject>().orEmpty()
                }
            }.onFailure { Timber.w(it, "Progress overlay lookup failed") }.getOrDefault(emptyList())

        /**
         * A list row's titles, failing as the server did: answered empty, a blip took every list
         * row off the page with no error (nothing retried it or kept its titles), and the empty
         * page was saved as the one to show at the next start.
         */
        private fun listItems(
            chain: Interceptor.Chain,
            original: Request,
            url: HttpUrl,
        ): List<JsonObject> =
            chain.proceed(original.newBuilder().url(url).get().build()).use { r ->
                if (!r.isSuccessful) throw java.io.IOException("List row lookup answered HTTP ${r.code}")
                // Only IOExceptions may leave an interceptor (anything else takes the app down)
                val body = runCatching { json.parseToJsonElement(r.body.string()).jsonObject }.getOrElse { throw java.io.IOException("List row lookup unreadable", it) }
                (body["Items"] as? JsonArray)?.filterIsInstance<JsonObject>().orEmpty()
            }

        /** The server's base URL (keeps a reverse-proxy prefix): everything before the API path. */
        private fun serverBase(url: HttpUrl): HttpUrl.Builder {
            val segments = url.pathSegments
            val apiStart = segments.indexOfFirst { it.equals("Users", true) || it.equals("UserItems", true) || it.equals("Shows", true) }
            val prefix = if (apiStart > 0) segments.take(apiStart) else emptyList()
            return url.newBuilder().encodedPath("/").query(null).apply { prefix.forEach { addPathSegment(it) } }
        }

        private fun HttpUrl.Builder.copyQuery(
            from: HttpUrl,
            vararg names: String,
        ): HttpUrl.Builder =
            apply {
                names.forEach { n -> from.queryParameter(n)?.let { addQueryParameter(n, it) } }
                if (from.queryParameter("userId") == null) {
                    // Old-style /Users/{id}/... routes carry the user in the path
                    val segs = from.pathSegments
                    val i = segs.indexOfFirst { it.equals("Users", true) }
                    if (i >= 0 && i + 1 < segs.size) addQueryParameter("userId", segs[i + 1])
                }
            }

        private fun limitOf(request: Request): Int? = (request.url.queryParameter("limit") ?: request.url.queryParameter("Limit"))?.toIntOrNull()

        private fun lastPlayedOf(item: JsonObject): Long =
            ((item["UserData"] as? JsonObject)?.get("LastPlayedDate") as? JsonPrimitive)?.content?.let(::parseMillis) ?: 0L

        private fun parseMillis(s: String): Long? =
            runCatching { OffsetDateTime.parse(s).toInstant().toEpochMilli() }.getOrNull()
                ?: runCatching { Instant.parse(s).toEpochMilli() }.getOrNull()

        // ------------------------------------------------------------ storage

        /**
         * Keeps each user's newest [MAX_ENTRIES] titles. There's no age limit: on a server that
         * ignores progress (Silo) this is the only record of what was watched, so watched flags and
         * resume points used to vanish after 120 days (or 500 titles, a few months of episodes).
         */
        private fun prune(b: ConcurrentHashMap<String, Entry>) {
            if (b.size <= MAX_ENTRIES) return
            b.entries
                .sortedBy { it.value.lastPlayed }
                .take(b.size - MAX_ENTRIES)
                .forEach { b.remove(it.key) }
        }

        /** Written off the caller's thread, changes close together once, to a file of its own. */
        private fun save() {
            val f = file ?: return
            if (!writePending.compareAndSet(false, true)) return
            writer.execute {
                Thread.sleep(WRITE_DELAY_MS)
                writePending.set(false)
                runCatching {
                    val all: Map<String, Map<String, Entry>> = users.mapValues { HashMap(it.value) }
                    val tmp = java.io.File(f.path + ".tmp")
                    java.io.FileOutputStream(tmp).use { out ->
                        val buffered = out.buffered()
                        json.encodeToStream(STORED, all, buffered)
                        buffered.flush()
                        out.fd.sync()
                    }
                    if (!tmp.renameTo(f)) error("Couldn't replace ${f.name}")
                    // The old single list is in the file now
                    if (legacy?.contains(LEGACY_KEY) == true) legacy.edit().remove(LEGACY_KEY).apply()
                }.onFailure { Timber.w(it, "Saving watch progress failed") }
            }
        }

        private fun pruneDismissed(d: ConcurrentHashMap<String, Long>) {
            if (d.size <= MAX_DISMISSED) return
            d.entries.sortedBy { it.value }.take(d.size - MAX_DISMISSED).forEach { d.remove(it.key) }
        }

        private val dismissedPending = java.util.concurrent.atomic.AtomicBoolean(false)

        private fun saveDismissed() {
            val f = dismissedFile ?: return
            if (!dismissedPending.compareAndSet(false, true)) return
            writer.execute {
                Thread.sleep(WRITE_DELAY_MS)
                dismissedPending.set(false)
                runCatching {
                    val all: Map<String, Map<String, Long>> = dismissed.mapValues { HashMap(it.value) }
                    val tmp = java.io.File(f.path + ".tmp")
                    tmp.writeText(json.encodeToString(DISMISSED_STORED, all))
                    if (!tmp.renameTo(f)) error("Couldn't replace ${f.name}")
                }.onFailure { Timber.w(it, "Saving Continue Watching removals failed") }
            }
        }

        private fun load() {
            dismissedFile?.takeIf { it.exists() }?.let { f ->
                runCatching { json.decodeFromString(DISMISSED_STORED, f.readText()) }
                    .onSuccess { all -> all.forEach { (u, m) -> dismissed[u] = ConcurrentHashMap(m) } }
                    .onFailure { Timber.w(it, "Reading Continue Watching removals failed") }
            }
            file?.takeIf { it.exists() }?.let { f ->
                runCatching { f.inputStream().buffered().use { json.decodeFromStream(STORED, it) } }
                    .onSuccess { all -> all.forEach { (u, m) -> users[u] = ConcurrentHashMap(m) } }
                    .onFailure { Timber.w(it, "Reading watch progress failed") }
            }
            // Before progress was kept per user: one list for the TV, in the preferences
            val old = runCatching { legacy?.getString(LEGACY_KEY, null)?.let { json.decodeFromString<Map<String, Entry>>(it) } }.getOrNull().orEmpty()
            if (old.isNotEmpty()) {
                val b = users.getOrPut(LEGACY) { ConcurrentHashMap() }
                old.forEach { (k, e) -> if ((b[norm(k)]?.lastPlayed ?: Long.MIN_VALUE) < e.lastPlayed) b[norm(k)] = e }
                // It belongs to whoever is signed in now
                runCatching { knownUser() }.getOrNull()?.takeIf { it.isNotBlank() }?.let { u ->
                    current = norm(u)
                    bucket(u)
                }
            }
        }

        /** Whether [text] names an item there's progress for: a plain scan, no regex, no copies. */
        private fun mentionsAny(
            text: String,
            entries: Map<String, Entry>,
        ): Boolean {
            val key = StringBuilder(32)
            var i = text.indexOf("\"Id\"")
            while (i >= 0) {
                var j = i + 4
                while (j < text.length && text[j].isWhitespace()) j++
                if (j < text.length && text[j] == ':') {
                    j++
                    while (j < text.length && text[j].isWhitespace()) j++
                    if (j < text.length && text[j] == '"') {
                        key.setLength(0)
                        var k = j + 1
                        while (k < text.length && k - j <= 37) {
                            val c = text[k]
                            if (c == '"') break
                            if (c != '-') key.append(c.lowercaseChar())
                            k++
                        }
                        if (key.length == 32 && entries.containsKey(key.toString())) return true
                        j = k
                    }
                }
                i = text.indexOf("\"Id\"", j)
            }
            return false
        }

        /** One show's "where was it left" answers, briefly (a title page and Home ask together). */
        private val seriesCache = ConcurrentHashMap<String, Pair<Long, JsonObject>>()

        /** An episode's watch state, for [nextByLatest]. */
        internal data class Watch(
            val season: Int?,
            val played: Boolean,
            val positionTicks: Long,
            /** Epoch millis, 0 when never. */
            val lastPlayed: Long,
        )

        companion object {
            private val RECENT: Duration = Duration.ofDays(30)
            private const val SERIES_CACHE_MS = 120_000L

            /**
             * Where a show was left, from [eps] (its episodes in watching order): the episode
             * watched most recently if it's half-watched, else the first unwatched one after it
             * (specials only after a special). null: nothing in [eps] was watched, or nothing
             * unwatched follows the latest one.
             */
            internal fun nextByLatest(eps: List<Watch>): Int? {
                val latest = eps.indices.filter { eps[it].lastPlayed > 0 && (eps[it].played || eps[it].positionTicks > 0) }.maxByOrNull { eps[it].lastPlayed } ?: return null
                val l = eps[latest]
                if (!l.played) return latest
                return (latest + 1 until eps.size).firstOrNull { !eps[it].played && (l.season == 0 || eps[it].season != 0) }
            }

            /** Key prefix of a whole show removed from Continue Watching. */
            internal const val SERIES_KEY = "s:"
            private const val MAX_DISMISSED = 2_000
            private val DISMISSED_STORED = MapSerializer(String.serializer(), MapSerializer(String.serializer(), Long.serializer()))

            /**
             * Whether an item ([id], of show [series], both [norm]ed) is out of Continue Watching:
             * removed at or after it was last played ([own]), or its show removed at or after
             * anything of the show was last played ([show]).
             */
            internal fun hiddenBy(
                gone: Map<String, Long>,
                id: String,
                series: String?,
                own: Long,
                show: Long,
            ): Boolean {
                val item = gone[id]
                if (item != null && own <= item) return true
                val s = series?.let { gone[SERIES_KEY + it] } ?: return false
                return show <= s
            }

            /** Removals from two copies of a profile as one: every key, the later time. */
            fun mergeDismissed(
                a: Map<String, Long>,
                b: Map<String, Long>,
            ): Map<String, Long> = (a.keys + b.keys).associateWith { k -> maxOf(a[k] ?: Long.MIN_VALUE, b[k] ?: Long.MIN_VALUE) }.toSortedMap()

            /** Titles kept per user: years of watching (about 120 bytes each). */
            internal const val MAX_ENTRIES = 5_000
            private const val LEGACY = ""
            private const val LEGACY_KEY = "entries_v1"
            private const val WRITE_DELAY_MS = 1_500L
            private val STORED = MapSerializer(String.serializer(), MapSerializer(String.serializer(), Entry.serializer()))
            private val ID_CHARS = Regex("[0-9a-fA-F-]{32,36}")
            private const val MAX_LIST_PAGE = 200
            private const val MAX_INJECT = 12
            private val JSON_TYPE = "application/json; charset=utf-8".toMediaType()

            /** Ids are compared lowercase without dashes; servers write them either way. */
            fun norm(id: String): String = id.replace("-", "").lowercase()

            fun dashed(id: String): String {
                val n = norm(id)
                return if (n.length == 32) "${n.substring(0, 8)}-${n.substring(8, 12)}-${n.substring(12, 16)}-${n.substring(16, 20)}-${n.substring(20)}" else id
            }
        }
    }
