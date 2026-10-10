package com.wholphinplus.sources

import android.content.Context
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.ResponseBody.Companion.toResponseBody
import java.util.concurrent.ConcurrentHashMap

/**
 * Emby as the main server (owner, 2026-10-09): Wholphin speaks Jellyfin, whose newer API moved a
 * few things Emby still has at the old addresses. On a host found to be Emby, this rewrites those
 * requests on their way out ("/Users/Me" → "/Users/{id}", "/UserViews" → "/Users/{id}/Views"…),
 * answers the two Emby lacks (media segments, Quick Connect) itself, and lets the server through
 * Jellyfin's version check. Every other host passes untouched: one map lookup per request.
 *
 * Sits inside [ProgressOverlay] on Wholphin's API client (AppModule hook), so that one still sees
 * Jellyfin's paths.
 */
object EmbyBridge : Interceptor {
    // Hosts known to be Emby (scheme://host:port), saved so a cold start knows before its first call
    private val hosts = ConcurrentHashMap<String, Boolean>()

    // Access token → its user, for the requests that name no user ("/Users/Me")
    private val users = ConcurrentHashMap<String, String>()

    @Volatile private var prefs: android.content.SharedPreferences? = null

    private val json = Json { ignoreUnknownKeys = true }

    /**
     * API calls to an Emby main server at once. A page asks for ~20 rows together, which a Jellyfin
     * or Silo server takes in its stride; a plain Emby box (bench, 2026-10-09) stopped answering
     * anything for minutes under it. Silo and Jellyfin never wait here.
     */
    // 6: Emby answers 4 at once as fast as 1 (bench, 2026-10-10); with 4, a page's calls waited up to ~4 s for a turn
    private val gate = java.util.concurrent.Semaphore(6, true)

    /**
     * Big lookups by id (a list row's 40 titles) take their own turns, two at a time: alone one costs
     * Emby ~0.45 s, six at once ~2.7 s each, and the small calls Home waits on (Continue Watching,
     * Latest, the user) queued up to ~3 s behind them (bench, 2026-10-10).
     */
    private val heavyGate = java.util.concurrent.Semaphore(2, true)

    private fun heavy(url: HttpUrl): Boolean = ((url.queryParameter("Ids") ?: url.queryParameter("ids"))?.count { it == ',' } ?: 0) >= 19

    /** What Emby is let in as: Jellyfin's product name, and a version past its minimum. */
    const val AS_VERSION = "10.10.7"

    // ---- Item ids: Emby's are numbers ("596545"), Jellyfin's UUIDs. A number travels inside Orca+ as
    // "454d4259-0000-4000-8000-" + its 12 hex digits ("EMBY"), exactly reversible.

    private const val MARK = "454d4259-0000-4000-8000-"

    internal fun toUuid(number: String): String = MARK + java.lang.Long.toHexString(number.toLong()).padStart(12, '0')

    private val MARKED = Regex("(?i)454d4259-?0000-?4000-?8000-?([0-9a-f]{12})")

    /** Marked ids back to Emby's numbers, anywhere in [text] (a path, a query, a JSON body). */
    internal fun toEmby(text: String): String = MARKED.replace(text) { java.lang.Long.parseLong(it.groupValues[1], 16).toString() }

    // "…Id":"123" and "…Ids":["1","2"] in an answer; ids that aren't numbers (users, servers) stay
    private val ID_FIELD = Regex("\"(\\w*Id)\"\\s*:\\s*\"(\\d{1,15})\"")
    private val ID_LIST = Regex("\"(\\w*Ids)\"\\s*:\\s*\\[([^\\]]*)\\]")
    private val NUMBER = Regex("\"(\\d{1,15})\"")

    /** An Emby answer with its numeric ids as marked UUIDs. */
    internal fun toJellyfin(json: String): String {
        val one = ID_FIELD.replace(json) { m -> "\"${m.groupValues[1]}\":\"${toUuid(m.groupValues[2])}\"" }
        return ID_LIST.replace(one) { m -> "\"${m.groupValues[1]}\":[" + NUMBER.replace(m.groupValues[2]) { n -> "\"${toUuid(n.groupValues[1])}\"" } + "]" }
    }

    fun init(context: Context) {
        val p = context.getSharedPreferences("wholphinplus_emby", Context.MODE_PRIVATE)
        prefs = p
        p.getStringSet(HOSTS, emptySet())?.forEach { hosts[it] = true }
        p.all.forEach { (k, v) -> if (k.startsWith(USER) && v is String) users[k.removePrefix(USER)] = v }
        resumeDir = java.io.File(context.noBackupFilesDir, "emby_resume")
    }

    /** The signed-in user for [token] (Wholphin's current sign-in, from the MainActivity hook). */
    fun remember(
        token: String?,
        userId: String?,
    ) {
        if (token.isNullOrBlank() || userId.isNullOrBlank()) return
        if (users.put(token, userId) != userId) prefs?.edit()?.putString(USER + token, userId)?.apply()
    }

    fun isEmby(url: HttpUrl): Boolean = hosts[key(url)] == true

    /** The user an Emby sign-in [token] belongs to, as Emby writes it (no dashes); null if unknown. */
    fun userOf(token: String): String? = users[token]?.replace("-", "")

    /** [url] is an Emby server (known before any request: the bench import, a saved sign-in). */
    fun markEmby(url: String) {
        val k = url.toHttpUrlOrNull()?.let(::key) ?: return
        if (hosts.put(k, true) != true) prefs?.edit()?.putStringSet(HOSTS, hosts.keys.toSet())?.apply()
    }

    fun isEmby(url: String): Boolean = url.toHttpUrlOrNull()?.let(::isEmby) == true

    /**
     * An address the player opens itself, never through this interceptor (Media3's own HTTP, mpv):
     * Emby's numbers; the sign-in [token] as api_key (Jellyfin streams without one, Emby answers
     * 401, and Jellyfin's "ApiKey" too); no streamOptions (Emby: 500 "not implemented").
     */
    fun forPlayer(
        url: String,
        token: String?,
    ): String {
        if (!isEmby(url)) return url
        val u = toEmby(url).toHttpUrlOrNull() ?: return url
        val key = u.queryParameter("api_key") ?: u.queryParameter("ApiKey") ?: token
        return u
            .newBuilder()
            .removeAllQueryParameters("streamOptions")
            .removeAllQueryParameters("ApiKey")
            .apply { if (key != null) setQueryParameter("api_key", key) }
            .build()
            .toString()
    }

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val url = request.url
        val path = url.encodedPath
        if (path.endsWith("/System/Info/Public", ignoreCase = true)) return info(chain.proceed(request))
        if (!isEmby(url)) return chain.proceed(request)
        local(request)?.let { return it }
        val rewritten = lighter(outgoing(rewrite(request) ?: request)).let { if (it.header(REFRESH) != null) it.newBuilder().removeHeader(REFRESH).build() else it }
        // Pictures and video aren't held back; API calls take turns ([gate])
        val api = !Regex("(?i)/(Images|Videos|Audio)/").containsMatchIn(url.encodedPath)
        if (!api) return fetch(chain, request, rewritten, false)
        // A change (played, favourite, progress) may change any answer: start over
        if (request.method != "GET") {
            answers.clear()
            // Something was played, marked or saved here: Continue Watching is asked fresh next time
            if (CHANGE.containsMatchIn(url.encodedPath)) resumeDir?.listFiles()?.forEach { it.delete() }
            return fetch(chain, request, rewritten, true)
        }
        // The same question asked again (a page asks for the user 3 times, the libraries 5 times,
        // Continue Watching 3 times): one request, its answer shared; some kept a little while
        val key = rewritten.url.toString() + "|" + (request.header("Authorization") ?: request.header("X-Emby-Token")).orEmpty().hashCode()
        val keep = keepFor(rewritten.url.encodedPath, "?" + rewritten.url.encodedQuery.orEmpty())
        answers[key]?.takeIf { System.currentTimeMillis() - it.at < keep }?.let { return it.response(request) }
        val resume = RESUME.containsMatchIn(rewritten.url.encodedPath)
        if (resume && request.header(REFRESH) == null) storedResume(key)?.let { stored ->
            refreshResume(request, key)
            return stored.response(request)
        }
        val mine = java.util.concurrent.CompletableFuture<Answer?>()
        val shared = inFlight.putIfAbsent(key, mine)
        if (shared != null) {
            val got = runCatching { shared.get(60, java.util.concurrent.TimeUnit.SECONDS) }.getOrNull()
            if (got != null) return got.response(request)
            return fetch(chain, request, rewritten, true)
        }
        try {
            val response = fetch(chain, request, rewritten, true)
            val type = response.body.contentType()
            if (!response.isSuccessful || type?.subtype?.contains("json") != true) {
                mine.complete(null)
                return response
            }
            val answer = Answer(response.code, response.message, response.headers, type, response.body.bytes(), System.currentTimeMillis())
            if (resume) storeResume(key, answer, refreshed = request.header(REFRESH) != null)
            if (keep > 0) {
                answers[key] = answer
                if (answers.size > KEEP_MAX) answers.entries.sortedBy { it.value.at }.take(answers.size - KEEP_MAX).forEach { answers.remove(it.key, it.value) }
            }
            mine.complete(answer)
            return answer.response(request)
        } catch (e: Throwable) {
            mine.complete(null)
            throw e
        } finally {
            inFlight.remove(key, mine)
        }
    }

    // ---- Continue Watching at once. Emby takes ~2.1 s for it however it's asked (bench, 2026-10-10:
    // the type filter; the faster IsResumable query lacks Emby's next-episode entries), and Home's
    // first rows wait for it. Its last answer is kept on the device and given at once while the real
    // one is asked in the background; if that differs, [resumeChanged] tells Home to swap it in.
    // Anything played, marked or saved on this TV deletes the kept copy, so Home asks fresh then.

    private val RESUME = Regex("(?i)/Items/Resume$")
    private val CHANGE = Regex("(?i)/(Sessions/Playing|PlayedItems|FavoriteItems|UserData|HideFromResume|Items/[^/]+/Refresh)")
    private const val REFRESH = "X-Orca-Refresh"
    @Volatile private var resumeDir: java.io.File? = null
    private val refreshing = ConcurrentHashMap<String, Boolean>()
    private val refreshClient by lazy { okhttp3.OkHttpClient.Builder().addInterceptor(this).build() }
    private val _resumeChanged = kotlinx.coroutines.flow.MutableStateFlow(0)

    /** Bumped when Continue Watching given from the device turned out different from the server's. */
    val resumeChanged: kotlinx.coroutines.flow.StateFlow<Int> = _resumeChanged

    private fun resumeFile(key: String): java.io.File? =
        resumeDir?.let { dir ->
            val name = java.security.MessageDigest.getInstance("SHA-256").digest(key.toByteArray()).joinToString("") { "%02x".format(it) }.take(32)
            java.io.File(dir, name)
        }

    private fun storedResume(key: String): Answer? {
        val f = resumeFile(key)?.takeIf { it.isFile } ?: return null
        // A day old at most: older, the wait is worth a list that's right
        if (System.currentTimeMillis() - f.lastModified() > 24 * 60 * 60_000L) return null
        val body = runCatching { f.readBytes() }.getOrNull() ?: return null
        return Answer(200, "OK", okhttp3.Headers.headersOf(), "application/json; charset=utf-8".toMediaType(), body, f.lastModified())
    }

    private fun storeResume(
        key: String,
        answer: Answer,
        refreshed: Boolean,
    ) {
        val f = resumeFile(key) ?: return
        val before = if (f.isFile) runCatching { f.readBytes() }.getOrNull() else null
        runCatching {
            f.parentFile?.mkdirs()
            val tmp = java.io.File(f.path + ".tmp")
            tmp.writeBytes(answer.body)
            tmp.renameTo(f)
        }
        if (refreshed && before != null && !before.contentEquals(answer.body)) _resumeChanged.value++
    }

    private fun refreshResume(
        request: Request,
        key: String,
    ) {
        if (refreshing.putIfAbsent(key, true) != null) return
        refreshClient.newCall(request.newBuilder().header(REFRESH, "1").build()).enqueue(
            object : okhttp3.Callback {
                override fun onFailure(call: okhttp3.Call, e: java.io.IOException) {
                    refreshing.remove(key)
                }

                override fun onResponse(call: okhttp3.Call, response: Response) {
                    response.close()
                    refreshing.remove(key)
                }
            },
        )
    }

    /** A JSON answer as sent on, kept to share ([intercept]). */
    private class Answer(
        val code: Int,
        val message: String,
        val headers: okhttp3.Headers,
        val type: okhttp3.MediaType,
        val body: ByteArray,
        val at: Long,
    ) {
        fun response(request: Request): Response =
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(code).message(message).headers(headers)
                .body(body.toResponseBody(type)).build()
    }

    private val answers = ConcurrentHashMap<String, Answer>()
    private val inFlight = ConcurrentHashMap<String, java.util.concurrent.CompletableFuture<Answer?>>()

    /**
     * How long an answer is kept. Emby sends no ETag or cache headers (Expires: -1), and a single title
     * costs ~150 ms but the server freezes for 2-29 s at random (bench, 2026-10-10): every answer not
     * asked again is one less chance to hit a freeze. Any change sent to the server clears them all.
     */
    private fun keepFor(
        path: String,
        query: String,
    ): Long =
        when {
            Regex("(?i)/Users/[^/]+/Views$").containsMatchIn(path) -> 60_000L
            Regex("(?i)/Users/[^/]+$").containsMatchIn(path) -> 60_000L
            Regex("(?i)/Items/Resume$").containsMatchIn(path) -> 4_000L
            // A title, its seasons and episodes: the title page asks again on every visit
            Regex("(?i)/Users/[^/]+/Items/[^/]+$").containsMatchIn(path) -> 5 * 60_000L
            Regex("(?i)/Shows/[^/]+/(Seasons|Episodes)$").containsMatchIn(path) -> 5 * 60_000L
            // Titles by id (a list row's 40, badge facts): the same rows are asked again on every
            // Home reload and tab, each a chance to land in a freeze
            path.endsWith("/Items", ignoreCase = true) && Regex("(?i)[?&]ids=").containsMatchIn(query) -> 5 * 60_000L
            // More Like This: ~0.8 s of server work each time, and it hardly changes
            Regex("(?i)/Items/[^/]+/Similar$").containsMatchIn(path) -> 30 * 60_000L
            else -> 0L
        }

    /** At most this many answers kept (a title is ~7 KB): the oldest go first. */
    private const val KEEP_MAX = 400

    /** One API call (taking its turn when [api]), timed in the log, its answer fitted for Wholphin. */
    private fun fetch(
        chain: Interceptor.Chain,
        request: Request,
        rewritten: Request,
        api: Boolean,
    ): Response {
        val path = request.url.encodedPath
        val asked = System.nanoTime()
        // A heavy lookup waits for its own lane first, so it never holds a general turn while waiting
        val big = api && heavy(rewritten.url)
        if (big) heavyGate.acquire()
        try {
            if (api) gate.acquire()
        } catch (e: InterruptedException) {
            if (big) heavyGate.release()
            throw e
        }
        val started = System.nanoTime()
        val response =
            try {
                chain.proceed(rewritten)
            } finally {
                if (api) gate.release()
                if (big) heavyGate.release()
            }
        // Where an Emby main server's time goes: each API call, its wait for a turn and the server's
        // own time (no ids or keys: the path's numbers are blanked)
        if (api) {
            val u = rewritten.url
            timber.log.Timber.i(
                "EmbyBridge: %s %s fields=%s ids=%d limit=%s · waited %d ms · server %d ms · %s",
                rewritten.method, u.encodedPath.replace(Regex("[0-9a-fA-F]{6,}"), "#"),
                (u.queryParameter("Fields") ?: u.queryParameter("fields")).orEmpty().take(80),
                (u.queryParameter("Ids") ?: u.queryParameter("ids"))?.split(',')?.size ?: 0,
                u.queryParameter("Limit") ?: u.queryParameter("limit") ?: "-",
                (started - asked) / 1_000_000, (System.nanoTime() - started) / 1_000_000, response.code,
            )
        }
        if (response.code >= 400) timber.log.Timber.w("EmbyBridge: %s %d for %s ids=%s", rewritten.method, response.code, rewritten.url.encodedPath, rewritten.url.queryParameter("ids")?.take(120))
        if (path.endsWith("/Users/AuthenticateByName", ignoreCase = true) && response.isSuccessful) return learnUser(incoming(response, request))
        return incoming(response, request)
    }

    /**
     * Emby's own Kodi add-on asks lists the cheap way, and so does this: no total count unless a
     * request asks for one (Emby counts the whole matching library for it, every time; a caller
     * that uses the count says enableTotalRecordCount=true), and titles asked for by id aren't
     * sorted (callers put them in their own order).
     */
    internal fun lighter(request: Request): Request {
        if (request.method != "GET") return request
        val url = request.url
        if (!Regex("(?i)/Items/?$").containsMatchIn(url.encodedPath)) return request
        fun has(name: String) = url.queryParameterNames.any { it.equals(name, ignoreCase = true) }
        val builder = url.newBuilder()
        var changed = false
        if (!has("EnableTotalRecordCount")) {
            builder.addQueryParameter("EnableTotalRecordCount", "false")
            changed = true
        }
        if (has("Ids") && !has("SortBy")) {
            builder.addQueryParameter("SortBy", "None")
            changed = true
        }
        return if (changed) request.newBuilder().url(builder.build()).build() else request
    }

    /** Marked ids back to numbers in the address and a JSON body; another server's ids dropped. */
    internal fun outgoing(request: Request): Request {
        val ids = idsParam(request.url)
        val cleaned =
            if (ids != null && embyIds(ids) != ids) {
                val name = if (request.url.queryParameter("ids") != null) "ids" else "Ids"
                request.newBuilder().url(request.url.newBuilder().setQueryParameter(name, embyIds(ids)).build()).build()
            } else {
                request
            }
        return convert(cleaned)
    }

    private fun convert(request: Request): Request {
        val url = request.url.toString()
        val newUrl = toEmby(url)
        val body = request.body
        val newBody =
            if (body != null && body.contentType()?.subtype?.contains("json") == true) {
                val text = okio.Buffer().also { body.writeTo(it) }.readUtf8()
                val out = toEmby(text)
                if (out != text) out.toByteArray().toRequestBody(body.contentType()) else body
            } else {
                body
            }
        if (newUrl == url && newBody === body) return request
        return request.newBuilder().url(newUrl).method(request.method, newBody).build()
    }

    /**
     * A JSON answer as Jellyfin would give it: by the type Wholphin expects ([EmbyShapes]), fields
     * Emby leaves out filled, numeric ids as marked UUIDs where a UUID goes, unknown enum values
     * replaced; an answer of no known type gets the ids only. Pictures and video pass as they are.
     */
    private fun incoming(
        response: Response,
        asked: Request,
    ): Response {
        val type = response.body.contentType()
        if (type?.subtype?.contains("json") != true || !response.isSuccessful) return response
        val text = response.body.string()
        val shape = EmbyShapes.of(asked.method, asked.url.encodedPath)
        val out =
            shape?.let { d ->
                runCatching { EmbyShapes.fit(json.parseToJsonElement(text), d).toString() }
                    .onFailure { timber.log.Timber.w(it, "EmbyBridge: couldn't fit %s", asked.url.encodedPath) }
                    .getOrNull()
            } ?: toJellyfin(text)
        return response.newBuilder().body(out.toResponseBody(type)).build()
    }

    // ---- What Emby answers differently

    /** Emby's public info has no product name and a 4.x version: noted, and let through. */
    private fun info(response: Response): Response {
        if (!response.isSuccessful) return response
        val body = response.body.string()
        val o = runCatching { json.parseToJsonElement(body).jsonObject }.getOrNull() ?: return response.newBuilder().body(body.toResponseBody(response.body.contentType())).build()
        val product = (o["ProductName"] as? JsonPrimitive)?.content.orEmpty()
        val version = (o["Version"] as? JsonPrimitive)?.content.orEmpty()
        val emby = product.isBlank() && version.startsWith("4.")
        val k = key(response.request.url)
        if (emby && hosts.put(k, true) != true) prefs?.edit()?.putStringSet(HOSTS, hosts.keys.toSet())?.apply()
        val out =
            if (emby) {
                JsonObject(o + mapOf("ProductName" to JsonPrimitive("Jellyfin Server"), "Version" to JsonPrimitive(AS_VERSION))).toString()
            } else {
                body
            }
        return response.newBuilder().body(out.toResponseBody(JSON_TYPE)).build()
    }

    /** The ids an Emby server can have: its own (marked or numeric); another server's UUIDs dropped. */
    private fun embyIds(raw: String): String = raw.split(',').map { it.trim() }.filter { it.isNotEmpty() && (MARKED.matches(it) || it.all(Char::isDigit)) }.joinToString(",")

    private fun idsParam(url: HttpUrl): String? = url.queryParameter("ids") ?: url.queryParameter("Ids")

    /** Requests Emby has nothing for, answered here: no media segments, no Quick Connect. */
    internal fun local(request: Request): Response? {
        val path = request.url.encodedPath
        val body =
            when {
                // Only another server's ids (a list matched before the main server changed): Emby
                // answers those 500, and an empty "ids" would make it search the whole library
                idsParam(request.url)?.let { it.isNotBlank() && embyIds(it).isEmpty() } == true ->
                    """{"Items":[],"TotalRecordCount":0,"StartIndex":0}"""
                Regex("(?i)/MediaSegments/[^/]+$").containsMatchIn(path) -> """{"Items":[],"TotalRecordCount":0,"StartIndex":0}"""
                // Orca+'s list rows ask by a tag no server has (ProgressOverlay answers them from the
                // library index): sent to Emby, each made it scan the whole library, retried, ~150 a
                // load, and the server stopped answering (bench, 2026-10-09)
                request.url.queryParameterValues("tags").any { it?.startsWith(HomeCollection.TAG_PREFIX) == true } ->
                    """{"Items":[],"TotalRecordCount":0,"StartIndex":0}"""
                path.endsWith("/QuickConnect/Enabled", ignoreCase = true) -> "false"
                else -> return null
            }
        return Response
            .Builder()
            .request(request)
            .protocol(Protocol.HTTP_1_1)
            .code(200)
            .message("OK")
            .body(body.toResponseBody(JSON_TYPE))
            .build()
    }

    /** Jellyfin's newer addresses to Emby's (null: nothing to change). */
    internal fun rewrite(request: Request): Request? {
        val url = request.url
        val path = url.encodedPath
        // Emby writes user ids without dashes
        val user = (url.queryParameterValues("userId").firstOrNull { !it.isNullOrBlank() } ?: url.queryParameterValues("UserId").firstOrNull { !it.isNullOrBlank() } ?: users[token(request)])?.replace("-", "")
        fun to(newPath: String) = request.newBuilder().url(url.newBuilder().encodedPath(newPath).build()).build()
        for ((pattern, target) in RULES) {
            val m = pattern.find(path) ?: continue
            if (target.needsUser && user == null) return null
            if (target.getOnly && request.method != "GET") continue
            val prefix = path.substring(0, m.range.first)
            return to(prefix + target.build(m.groupValues.drop(1), user.orEmpty()))
        }
        return null
    }

    private class Target(
        val needsUser: Boolean = true,
        val getOnly: Boolean = false,
        val build: (groups: List<String>, user: String) -> String,
    )

    private val RULES: List<Pair<Regex, Target>> =
        listOf(
            Regex("(?i)/Users/Me$") to Target { _, u -> "/Users/$u" },
            Regex("(?i)/UserViews$") to Target { _, u -> "/Users/$u/Views" },
            Regex("(?i)/UserItems/Resume$") to Target { _, u -> "/Users/$u/Items/Resume" },
            Regex("(?i)/UserItems/([^/]+)/UserData$") to Target { g, u -> "/Users/$u/Items/${g[0]}/UserData" },
            Regex("(?i)/UserPlayedItems/([^/]+)$") to Target { g, u -> "/Users/$u/PlayedItems/${g[0]}" },
            Regex("(?i)/UserFavoriteItems/([^/]+)$") to Target { g, u -> "/Users/$u/FavoriteItems/${g[0]}" },
            Regex("(?i)/Items/Latest$") to Target { _, u -> "/Users/$u/Items/Latest" },
            Regex("(?i)/Items/([^/]+)/Intros$") to Target { g, u -> "/Users/$u/Items/${g[0]}/Intros" },
            // One item by id ("/Items/{id}?userId="): not "/Items/Filters" and the like
            Regex("(?i)/Items/([0-9a-f]{32}|[0-9a-f-]{36}|\\d+)$") to Target(getOnly = true) { g, u -> "/Users/$u/Items/${g[0]}" },
            Regex("(?i)/socket$") to Target(needsUser = false) { _, _ -> "/embywebsocket" },
        )

    /** A sign-in's user, for later requests by that token that name no user. */
    private fun learnUser(response: Response): Response {
        val body = response.body.string()
        runCatching {
            val o = json.parseToJsonElement(body).jsonObject
            val token = (o["AccessToken"] as? JsonPrimitive)?.content
            val id = ((o["User"] as? JsonObject)?.get("Id") as? JsonPrimitive)?.content
            remember(token, id)
        }
        return response.newBuilder().body(body.toResponseBody(response.body.contentType())).build()
    }

    private fun token(request: Request): String? =
        request.header("X-Emby-Token")
            ?: request.url.queryParameter("api_key")
            ?: request.url.queryParameter("ApiKey")
            ?: (request.header("Authorization") ?: request.header("X-Emby-Authorization"))?.let { Regex("Token=\"([^\"]+)\"").find(it)?.groupValues?.get(1) }

    private fun key(url: HttpUrl) = "${url.scheme}://${url.host.lowercase()}:${url.port}"

    private const val HOSTS = "hosts"
    private const val USER = "user:"
    private val JSON_TYPE = "application/json; charset=utf-8".toMediaType()
}
