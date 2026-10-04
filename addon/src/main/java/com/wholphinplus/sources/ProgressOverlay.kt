package com.wholphinplus.sources

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.serialization.Serializable
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
 * streamed from them in Wholphin+. Some main servers (Silo) ignore every client progress write,
 * so instead of writing it there, this interceptor sits on Wholphin's connection to the main
 * server and merges it into the answers. Continue Watching, Next Up, details pages and Resume all
 * see it. Nothing is ever sent to the main server.
 */
@Singleton
class ProgressOverlay internal constructor(
    private val prefs: android.content.SharedPreferences?,
    private val collections: CollectionLookup?,
) : Interceptor {
        @Inject
        constructor(
            @ApplicationContext context: Context,
            collections: HomeCollections,
        ) : this(context.getSharedPreferences("wholphinplus_progress", Context.MODE_PRIVATE), collections)

        @Serializable
        data class Entry(
            val positionTicks: Long,
            val played: Boolean,
            /** Epoch millis. */
            val lastPlayed: Long,
            val seriesId: String? = null,
            val title: String = "",
        )

        private val json = Json { ignoreUnknownKeys = true }
        private val entries = ConcurrentHashMap<String, Entry>(load())

        /** Record progress for a main-server item. Older information never replaces newer. */
        fun record(
            mainItemId: String,
            entry: Entry,
        ) {
            val key = norm(mainItemId)
            val current = entries[key]
            if (current != null && current.lastPlayed > entry.lastPlayed) return
            entries[key] = entry
            prune()
            save()
        }

        fun lastPlayed(mainItemId: String): Long? = entries[norm(mainItemId)]?.lastPlayed

        // ------------------------------------------------------------ interceptor

        override fun intercept(chain: Interceptor.Chain): Response {
            listRow(chain)?.let { return it }
            val request = chain.request()
            val response = chain.proceed(request)
            if (entries.isEmpty() || request.method != "GET" || !response.isSuccessful) return response
            val type = response.body.contentType()
            if (type?.subtype?.contains("json") != true) return response
            val path = request.url.encodedPath
            // /UserItems/Resume (Jellyfin 10.9+, what Wholphin calls) or /Users/{id}/Items/Resume
            val isResume = path.endsWith("Items/Resume", ignoreCase = true)
            val isNextUp = path.endsWith("/Shows/NextUp", ignoreCase = true)
            val text = response.body.string()
            val rebuilt = { body: String -> response.newBuilder().body(body.toResponseBody(type)).build() }
            if (!isResume && !isNextUp && ID_FIELD.findAll(text).none { entries.containsKey(norm(it.groupValues[1])) }) {
                return rebuilt(text)
            }
            return try {
                var root = json.parseToJsonElement(text)
                if (isResume) root = mergeResume(chain, request, root)
                if (isNextUp) root = mergeNextUp(chain, request, root)
                rebuilt(patch(root).toString())
            } catch (e: Exception) {
                Timber.w(e, "Progress overlay skipped %s", path)
                rebuilt(text)
            }
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
            val limit = limitOf(request) ?: ids.size
            val page = ids.drop(start).take(limit)
            val items =
                if (page.isEmpty()) {
                    emptyList()
                } else {
                    val rewritten =
                        url
                            .newBuilder()
                            .apply {
                                listOf(tagParam, "startIndex", "StartIndex", "limit", "Limit", "sortBy", "SortBy", "sortOrder", "SortOrder", "includeItemTypes", "IncludeItemTypes")
                                    .forEach { removeAllQueryParameters(it) }
                            }.addQueryParameter("ids", page.joinToString(",") { dashed(it) })
                            .build()
                    val byId = getItems(chain, request, rewritten).associateBy { norm((it["Id"] as? JsonPrimitive)?.content.orEmpty()) }
                    page.mapNotNull { byId[norm(it)] }.map { patch(it) as JsonObject }
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

        /** Overwrite UserData wherever an item we know newer progress for appears. */
        private fun patch(element: JsonElement): JsonElement =
            when (element) {
                is JsonArray -> JsonArray(element.map(::patch))
                is JsonObject -> {
                    val id = (element["Id"] as? JsonPrimitive)?.content
                    val entry = id?.let { entries[norm(it)] }
                    val patched = JsonObject(element.mapValues { (_, v) -> patch(v) })
                    if (entry != null && patched["UserData"] is JsonObject) withUserData(patched, entry) else patched
                }
                else -> element
            }

        private fun withUserData(
            item: JsonObject,
            entry: Entry,
        ): JsonObject {
            val data = item["UserData"]!!.jsonObject
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
                    .keys
                    .take(MAX_INJECT)
            val extra = if (wanted.isEmpty()) emptyList() else fetchItems(chain, request, wanted)
            Timber.i("Progress overlay: Continue Watching %d from server, %d wanted, %d added (%d known)", items.size, wanted.size, extra.size, entries.size)
            val merged =
                (items + extra)
                    .map { patch(it) as JsonObject }
                    // Watched elsewhere since: drop from Continue Watching
                    .filterNot { (it["UserData"] as? JsonObject)?.get("Played")?.let { p -> (p as JsonPrimitive).content == "true" } == true }
                    .sortedByDescending { lastPlayedOf(it) }
            val limit = limitOf(request) ?: merged.size
            return JsonObject(obj + ("Items" to JsonArray(merged.take(limit))))
        }

        private fun mergeNextUp(
            chain: Interceptor.Chain,
            request: Request,
            root: JsonElement,
        ): JsonElement {
            val obj = root as? JsonObject ?: return root
            val items = (obj["Items"] as? JsonArray)?.filterIsInstance<JsonObject>() ?: return root
            val cutoff = System.currentTimeMillis() - RECENT.toMillis()
            // Latest episode finished elsewhere, per series
            val finished =
                entries
                    .filter { (_, e) -> e.played && e.seriesId != null && e.lastPlayed > cutoff }
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

        private fun prune() {
            val cutoff = System.currentTimeMillis() - KEEP.toMillis()
            entries.entries.removeIf { it.value.lastPlayed < cutoff }
            if (entries.size > MAX_ENTRIES) {
                entries.entries
                    .sortedBy { it.value.lastPlayed }
                    .take(entries.size - MAX_ENTRIES)
                    .forEach { entries.remove(it.key) }
            }
        }

        private fun save() {
            prefs?.edit()?.putString("entries_v1", json.encodeToString(HashMap(entries)))?.apply()
        }

        private fun load(): Map<String, Entry> =
            runCatching {
                prefs?.getString("entries_v1", null)?.let { json.decodeFromString<Map<String, Entry>>(it) }
            }.getOrNull().orEmpty()

        companion object {
            private val RECENT: Duration = Duration.ofDays(30)
            private val KEEP: Duration = Duration.ofDays(120)
            private const val MAX_ENTRIES = 500
            private const val MAX_INJECT = 12
            private val JSON_TYPE = "application/json; charset=utf-8".toMediaType()
            private val ID_FIELD = Regex("\"Id\"\\s*:\\s*\"([0-9a-fA-F-]{32,36})\"")

            /** Ids are compared lowercase without dashes; servers write them either way. */
            fun norm(id: String): String = id.replace("-", "").lowercase()

            fun dashed(id: String): String {
                val n = norm(id)
                return if (n.length == 32) "${n.substring(0, 8)}-${n.substring(8, 12)}-${n.substring(12, 16)}-${n.substring(16, 20)}-${n.substring(20)}" else id
            }
        }
    }
