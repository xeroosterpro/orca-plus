package com.wholphinplus.sources.core

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.FormBody
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import timber.log.Timber
import java.io.IOException
import java.time.Instant
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter
import kotlin.coroutines.cancellation.CancellationException

/** A server (or web service) answered with a non-2xx status. */
class ServerRequestException(
    val statusCode: Int,
    message: String,
) : IllegalStateException(message)

/**
 * Talks to Jellyfin, Emby and Plex servers plus the plex.tv and Emby Connect account services.
 * Every call blocks; callers run it on an IO dispatcher. Timeouts come from [http].
 */
class ServerClient(
    private val http: OkHttpClient,
    private val deviceId: String,
    private val clientName: String,
    private val clientVersion: String,
) {
    private val json = Json { ignoreUnknownKeys = true }

    // ---- Probing and sign-in ----

    fun fetchPublicInfo(serverUrl: String): ServerInfo {
        val answer =
            try {
                getJson(endpoint(serverUrl, "System/Info/Public"), null)
            } catch (e: CancellationException) {
                throw e
            } catch (e: IOException) {
                throw e
            } catch (e: ServerRequestException) {
                // A busy or broken server is an outage, not a sign that something else lives here
                if (e.statusCode == 429 || e.statusCode >= 500) throw e
                null
            } catch (e: Exception) {
                null
            }
        if (answer != null && answer.isNotEmpty()) {
            val name = answer.string("ServerName")
            val product = answer.string("ProductName")
            val detected = detectServerKind(product, name)
            // Jellyfin names its product here and Emby doesn't
            val kind =
                when {
                    detected != ServerKind.UNKNOWN -> detected
                    product.isBlank() -> ServerKind.EMBY
                    else -> ServerKind.UNKNOWN
                }
            return ServerInfo(serverName = name, serverId = answer.string("Id"), productName = product, serverKind = kind)
        }

        val identity =
            try {
                getText(endpoint(serverUrl, "identity"), null)
            } catch (e: CancellationException) {
                throw e
            } catch (e: IOException) {
                throw e
            } catch (e: Exception) {
                null
            }
        val (name, machineId) = plexIdentity(identity.orEmpty())
        val plex = machineId.isNotBlank() || identity?.contains("MediaContainer") == true
        return ServerInfo(
            serverName = name,
            serverId = machineId,
            productName = if (plex) "Plex Media Server" else "",
            serverKind = if (plex) ServerKind.PLEX else ServerKind.UNKNOWN,
        )
    }

    fun signIn(
        serverUrl: String,
        info: ServerInfo,
        username: String,
        password: String,
        displayName: String,
    ): ServerConnection {
        if (username.isBlank()) throw IllegalArgumentException("Enter a username")
        // Older and newer servers read different keys for the password
        val body =
            buildJsonObject {
                put("Username", username)
                put("Pw", password)
                put("Password", password)
            }
        val answer = postJson(endpoint(serverUrl, "Users/AuthenticateByName"), body, null)
        return connectionFromAuth(serverUrl, info, info.serverKind, answer, username, displayName)
    }

    fun startQuickConnect(serverUrl: String): CodeLogin {
        val answer = postJson(endpoint(serverUrl, "QuickConnect/Initiate"), JsonObject(emptyMap()), null)
        val code = answer.string("Code")
        val secret = answer.string("Secret")
        if (code.isBlank() || secret.isBlank()) throw IllegalArgumentException("Quick Connect is not enabled on this server")
        return CodeLogin(
            id = secret,
            secret = secret,
            code = code,
            verificationUrl = serverUrl,
            kind = ServerKind.JELLYFIN,
            serverUrl = serverUrl,
        )
    }

    fun pollQuickConnect(
        login: CodeLogin,
        displayName: String,
    ): ServerConnection? {
        val state = getJson(endpoint(login.serverUrl, "QuickConnect/Connect", "secret" to login.secret), null)
        if (state.boolean("Authenticated") != true) return null
        val answer =
            postJson(
                endpoint(login.serverUrl, "Users/AuthenticateWithQuickConnect"),
                buildJsonObject { put("Secret", login.secret) },
                null,
            )
        val info = fetchPublicInfo(login.serverUrl)
        return connectionFromAuth(login.serverUrl, info, ServerKind.JELLYFIN, answer, "", displayName)
    }

    private fun connectionFromAuth(
        serverUrl: String,
        info: ServerInfo,
        kind: ServerKind,
        answer: JsonObject,
        fallbackUser: String,
        displayName: String,
    ): ServerConnection {
        val token = answer.string("AccessToken")
        val user = answer.obj("User") ?: JsonObject(emptyMap())
        val userId = user.string("Id")
        if (token.isBlank() || userId.isBlank()) throw IllegalArgumentException("The server did not return a usable account")
        val connection =
            ServerConnection(
                enabled = true,
                connectionId = connectionId(serverUrl, kind, userId),
                serverUrl = serverUrl,
                displayName = displayName.trim(),
                serverName = info.serverName.ifBlank { user.string("ServerName") }.ifBlank { kind.label },
                serverKind = kind,
                serverId = answer.string("ServerId").ifBlank { info.serverId },
                userId = userId,
                userName = user.string("Name").ifBlank { fallbackUser },
                accessToken = token,
                accountToken = "",
                lastConnectedAt = System.currentTimeMillis(),
            )
        return connection.copy(collections = quietly(emptyList()) { fetchCollections(connection) })
    }

    // ---- Emby Connect ----

    fun startEmbyConnectPin(): CodeLogin {
        val request =
            connectRequest(endpoint(EMBY_CONNECT, "pin"))
                .post(FormBody.Builder().add("deviceId", deviceId).build())
                .build()
        val answer = parse(call(request)) as? JsonObject ?: JsonObject(emptyMap())
        val pin = answer.string("Pin")
        if (pin.isBlank()) throw IllegalArgumentException("Emby Connect did not return a PIN")
        return CodeLogin(
            id = pin,
            secret = "",
            code = pin,
            verificationUrl = "emby.media/pin",
            kind = ServerKind.EMBY,
            serverUrl = "",
            intervalSeconds = 4,
        )
    }

    fun pollEmbyConnectPin(login: CodeLogin): EmbyConnectAccount? {
        val status =
            parse(call(connectRequest(endpoint(EMBY_CONNECT, "pin", "deviceId" to deviceId, "pin" to login.code)).get().build()))
                as? JsonObject ?: return null
        if (status.boolean("IsExpired") == true) throw IllegalArgumentException("The PIN expired. Start again for a new one.")
        if (status.boolean("IsConfirmed") != true) return null
        val form =
            FormBody
                .Builder()
                .add("deviceId", deviceId)
                .add("pin", login.code)
                .build()
        val answer =
            parse(call(connectRequest(endpoint(EMBY_CONNECT, "pin/authenticate")).post(form).build())) as? JsonObject
                ?: return null
        val token = answer.string("AccessToken")
        val userId = answer.string("UserId")
        if (token.isBlank() || userId.isBlank()) throw IllegalArgumentException("Emby Connect did not return an account")
        return EmbyConnectAccount(userId = userId, token = token)
    }

    fun embyConnectServers(account: EmbyConnectAccount): List<EmbyConnectServer> {
        val request =
            connectRequest(endpoint(EMBY_CONNECT, "servers", "userId" to account.userId))
                .header("X-Connect-UserToken", account.token)
                .get()
                .build()
        val list = parse(call(request)) as? JsonArray ?: return emptyList()
        return list
            .filterIsInstance<JsonObject>()
            .map {
                EmbyConnectServer(
                    name = it.string("Name"),
                    remoteUrl = it.string("Url"),
                    localUrl = it.string("LocalAddress"),
                    systemId = it.string("SystemId"),
                    accessKey = it.string("AccessKey"),
                )
            }.filter { it.accessKey.isNotBlank() && (it.remoteUrl.isNotBlank() || it.localUrl.isNotBlank()) }
    }

    fun connectEmbyServer(
        account: EmbyConnectAccount,
        server: EmbyConnectServer,
        displayName: String,
    ): ServerConnection {
        val addresses =
            listOf(server.localUrl, server.remoteUrl)
                .map(::normalizeServerUrl)
                .filter { it.isNotBlank() }
                .distinct()
        var lastError: Exception? = null
        for (address in addresses) {
            try {
                // The exchange wants the server's access key and a token-less client description
                val request =
                    Request
                        .Builder()
                        .url(endpoint(address, "Connect/Exchange", "format" to "json", "ConnectUserId" to account.userId))
                        .header("X-Emby-Token", server.accessKey)
                        .header("X-Emby-Authorization", mediaBrowser(null))
                        .get()
                        .build()
                val answer = parse(call(request)) as? JsonObject ?: continue
                val token = answer.string("AccessToken")
                val localUserId = answer.string("LocalUserId")
                if (token.isBlank() || localUserId.isBlank()) continue
                val info = quietly<ServerInfo?>(null) { fetchPublicInfo(address) }
                val connection =
                    ServerConnection(
                        connectionId = connectionId(address, ServerKind.EMBY, localUserId),
                        serverUrl = address,
                        displayName = displayName.trim(),
                        serverName =
                            info
                                ?.serverName
                                .orEmpty()
                                .ifBlank { server.name }
                                .ifBlank { "Emby" },
                        serverKind = ServerKind.EMBY,
                        serverId = info?.serverId.orEmpty().ifBlank { server.systemId },
                        userId = localUserId,
                        userName = "",
                        accessToken = token,
                        lastConnectedAt = System.currentTimeMillis(),
                    )
                return connection.copy(collections = quietly(emptyList()) { fetchCollections(connection) })
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                lastError = e
            }
        }
        val name = server.name.ifBlank { "this server" }
        throw IllegalStateException("Couldn't reach $name" + (lastError?.let { ": ${it.message}" } ?: ""))
    }

    // ---- Libraries ----

    fun fetchCollections(connection: ServerConnection): List<ServerCollection> =
        if (connection.isPlex) {
            getJson(endpoint(connection.serverUrl, "library/sections"), connection)
                .array("MediaContainer", "Directory")
                .filterIsInstance<JsonObject>()
                .mapNotNull { d ->
                    val id = d.string("key").ifBlank { return@mapNotNull null }
                    ServerCollection(id = id, name = d.string("title").ifBlank { "Library $id" }, type = d.string("type"), enabled = true)
                }
        } else {
            getJson(endpoint(connection.serverUrl, "Users/${connection.userId}/Views"), connection)
                .objects("Items")
                .mapNotNull { v ->
                    val id = v.string("Id").ifBlank { return@mapNotNull null }
                    ServerCollection(id = id, name = v.string("Name").ifBlank { "Library" }, type = v.string("CollectionType"), enabled = true)
                }
        }

    // ---- Plex sign-in ----

    fun startPlexPin(serverUrl: String): CodeLogin {
        val request =
            plexHeaders(
                Request.Builder().url(
                    endpoint(PLEX_TV, "pins", "strong" to "false", "X-Plex-Client-Identifier" to deviceId, "X-Plex-Product" to clientName),
                ),
                null,
            ).post(ByteArray(0).toRequestBody()).build()
        val answer = parse(call(request)) as? JsonObject ?: JsonObject(emptyMap())
        val id = answer.string("id")
        val code = answer.string("code")
        if (id.isBlank() || code.isBlank()) throw IllegalArgumentException("Plex did not return a sign-in code")
        return CodeLogin(
            id = id,
            secret = "",
            code = code,
            verificationUrl = "plex.tv/link",
            kind = ServerKind.PLEX,
            serverUrl = serverUrl,
            intervalSeconds = 3,
        )
    }

    fun pollPlexPin(
        login: CodeLogin,
        displayName: String,
    ): ServerConnection? {
        val request =
            plexHeaders(Request.Builder().url(endpoint(PLEX_TV, "pins/${login.id}", "X-Plex-Client-Identifier" to deviceId)), null)
                .get()
                .build()
        val answer = parse(call(request)) as? JsonObject ?: return null
        val token = answer.string("authToken").ifBlank { return null }
        return buildPlexConnection(token, login.serverUrl, displayName)
    }

    /** Turns a plex.tv account token into one reachable server with at least one library. */
    fun buildPlexConnection(
        accountToken: String,
        preferredServerUrl: String,
        displayName: String,
    ): ServerConnection {
        val token = accountToken.trim()
        if (token.isBlank()) throw IllegalArgumentException("Missing Plex token")
        val preferredUrl = normalizeServerUrl(preferredServerUrl)
        val preferredId =
            if (preferredUrl.isEmpty()) {
                ""
            } else {
                quietly("") { plexIdentity(getText(endpoint(preferredUrl, "identity"), null)).second }
            }
        val accountName = plexAccountName(token).ifBlank { "Plex account" }
        val devices = plexServers(token)

        val device =
            when {
                devices.isEmpty() -> null
                preferredId.isNotBlank() && devices.any { it.clientId == preferredId } -> devices.first { it.clientId == preferredId }
                preferredUrl.isNotBlank() && devices.any { d -> d.addresses.any { sameEndpoint(it.uri, preferredUrl) } } ->
                    devices.first { d -> d.addresses.any { sameEndpoint(it.uri, preferredUrl) } }
                else ->
                    devices
                        .filter { it.accessToken.isNotBlank() }
                        .sortedWith(compareByDescending<PlexDevice> { it.owned }.thenByDescending { it.addresses.isNotEmpty() })
                        .firstOrNull()
            }
        val serverId = device?.clientId.orEmpty().ifBlank { preferredId }
        val serverToken = device?.accessToken.orEmpty().ifBlank { token }

        // Direct LAN first, relays last, https before http
        val deviceAddresses =
            device
                ?.addresses
                .orEmpty()
                .sortedWith(
                    compareBy<PlexAddress> { !(it.local && !it.relay) }
                        .thenBy { it.relay }
                        .thenBy { !it.uri.startsWith("https", ignoreCase = true) },
                ).map { it.uri }
        val candidates =
            (listOfNotNull(preferredUrl.ifEmpty { null }) + deviceAddresses)
                .map(::normalizeServerUrl)
                .filter { it.isNotBlank() }
                .distinctBy { it.lowercase() }
        if (candidates.isEmpty()) throw IllegalArgumentException("No reachable address for this Plex server")

        var lastError: Exception? = null
        for (candidate in candidates) {
            try {
                val draft =
                    ServerConnection(
                        serverUrl = candidate,
                        displayName = displayName.trim(),
                        serverName = device?.name.orEmpty(),
                        serverKind = ServerKind.PLEX,
                        serverId = serverId,
                        userId = PLEX_USER,
                        userName = accountName,
                        accessToken = serverToken,
                        accountToken = token,
                        lastConnectedAt = System.currentTimeMillis(),
                    )
                val (name, machineId) = plexIdentity(getText(endpoint(candidate, "identity"), draft))
                // A stale LAN address can belong to somebody else's server now
                if (serverId.isNotBlank() && machineId.isNotBlank() && serverId != machineId) {
                    lastError = IllegalStateException("$candidate is a different Plex server")
                    continue
                }
                val ready =
                    draft.copy(
                        connectionId = connectionId(candidate, ServerKind.PLEX, machineId.ifBlank { accountName }),
                        serverName = name.ifBlank { device?.name.orEmpty() }.ifBlank { "Plex" },
                        serverId = machineId.ifBlank { serverId },
                    )
                val collections = fetchCollections(ready)
                if (collections.isNotEmpty()) return ready.copy(collections = collections)
                lastError = IllegalStateException("No libraries on this Plex server")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                lastError = e
            }
        }
        throw lastError ?: IllegalStateException("Could not reach the Plex server")
    }

    private fun plexAccountName(token: String): String =
        quietly("") {
            val request =
                Request
                    .Builder()
                    .url(endpoint(PLEX_TV, "user"))
                    .header("Accept", "application/json")
                    .header("X-Plex-Token", token)
                    .header("X-Plex-Client-Identifier", deviceId)
                    .get()
                    .build()
            val user = parse(call(request)) as? JsonObject ?: JsonObject(emptyMap())
            user.string("friendlyName").ifBlank { user.string("username") }.ifBlank { user.string("title") }
        }

    private fun plexServers(token: String): List<PlexDevice> =
        quietly(emptyList()) {
            val request =
                plexHeaders(Request.Builder().url(endpoint(PLEX_TV, "resources", "includeHttps" to "1", "includeRelay" to "1")), token)
                    .get()
                    .build()
            val list = parse(call(request)) as? JsonArray ?: return@quietly emptyList()
            list
                .filterIsInstance<JsonObject>()
                .filter { d -> d.string("provides").split(',').map { it.trim() }.contains("server") }
                .map { d ->
                    PlexDevice(
                        name = d.string("name"),
                        clientId = d.string("clientIdentifier"),
                        accessToken = d.string("accessToken"),
                        owned = d.boolean("owned") == true,
                        addresses =
                            d
                                .objects("connections")
                                .map { c ->
                                    PlexAddress(
                                        uri = normalizeServerUrl(c.string("uri")),
                                        local = c.boolean("local") == true,
                                        relay = c.boolean("relay") == true,
                                    )
                                }.filter { it.uri.isNotBlank() }
                                .distinctBy { it.uri.lowercase() },
                    )
                }.filter { it.clientId.isNotBlank() }
        }

    fun refresh(connection: ServerConnection): ServerConnection {
        if (connection.isPlex) {
            val fresh =
                buildPlexConnection(
                    connection.accountToken.ifBlank { connection.accessToken },
                    connection.serverUrl,
                    connection.displayName,
                )
            return fresh.copy(
                enabled = connection.enabled,
                connectionId = connection.connectionId.ifBlank { fresh.connectionId },
                collections = keepSwitches(connection.collections, fresh.collections),
            )
        }
        val info = getJson(endpoint(connection.serverUrl, "System/Info"), connection)
        val updated =
            connection.copy(
                serverName = info.string("ServerName").ifBlank { connection.serverName },
                serverId = info.string("Id").ifBlank { connection.serverId },
                lastConnectedAt = System.currentTimeMillis(),
            )
        return updated.copy(collections = keepSwitches(connection.collections, fetchCollections(updated)))
    }

    /** The fresh list, with each library's on/off switch carried over from before. */
    private fun keepSwitches(
        before: List<ServerCollection>,
        fresh: List<ServerCollection>,
    ): List<ServerCollection> {
        val switches = before.associate { it.id to it.enabled }
        return fresh.map { c -> switches[c.id]?.let { c.copy(enabled = it) } ?: c }
    }

    // ---- Finding copies of a title ----

    /** Every playable copy on this server, best first. Never throws, apart from cancellation. */
    suspend fun findSources(
        connection: ServerConnection,
        request: PlayRequest,
    ): List<ExternalSource> = findSourcesOrNull(connection, request).orEmpty()

    /**
     * [findSources], or null when the server couldn't be asked (down, a blip, every request
     * failed): that isn't "no copies here", and must not be remembered as such.
     */
    suspend fun findSourcesOrNull(
        connection: ServerConnection,
        request: PlayRequest,
    ): List<ExternalSource>? {
        val started = System.currentTimeMillis()
        val found =
            try {
                val items = matchItems(connection, request)
                coroutineScope { items.map { async { sourcesOf(connection, it) } }.awaitAll() }
                    .flatten()
                    .distinctBy { it.url }
                    .sortedWith(sourceRanking)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.w(e, "Source lookup failed on %s", connection.label)
                return null
            }
        // Many lookups fail quietly (Plex searches, id queries): nothing found from a server that
        // never answered a request is a failure too
        if (found.isEmpty() && (answeredAt[hostKey(connection.serverUrl)] ?: 0L) < started) {
            Timber.w("Source lookup on %s: the server didn't answer", connection.label)
            return null
        }
        return found
    }

    /** Each server's last failed request since [since] (status, or -1 for no answer): why a lookup came back empty. */
    fun troubleSince(
        connection: ServerConnection,
        since: Long,
    ): Int? {
        val host = hostKey(connection.serverUrl)
        val (code, at) = troubleAt[host] ?: return null
        return code.takeIf { at >= since && (answeredAt[host] ?: 0L) <= at }
    }

    private val troubleAt = java.util.concurrent.ConcurrentHashMap<String, Pair<Int, Long>>()

    /** When each server (scheme, host, port) last answered a request successfully. */
    private val answeredAt = java.util.concurrent.ConcurrentHashMap<String, Long>()

    private fun hostKey(url: String): String = url.toHttpUrlOrNull()?.let { hostKey(it) } ?: url

    private fun hostKey(url: HttpUrl): String = "${url.scheme}://${url.host.lowercase()}:${url.port}"

    fun currentUserId(connection: ServerConnection): String = getJson(endpoint(connection.serverUrl, "Users/Me"), connection).string("Id")

    suspend fun matchSeriesIds(
        connection: ServerConnection,
        request: PlayRequest,
    ): List<String> = seriesMatches(connection, request).map { it.id }

    suspend fun matchItemIds(
        connection: ServerConnection,
        request: PlayRequest,
    ): List<String> = matchItems(connection, request).map { it.id }

    private suspend fun matchItems(
        connection: ServerConnection,
        request: PlayRequest,
    ): List<Item> = if (request.isEpisode) episodeMatches(connection, request) else movieMatches(connection, request)

    private suspend fun movieMatches(
        c: ServerConnection,
        request: PlayRequest,
    ): List<Item> {
        val found = LinkedHashMap<String, Item>()
        coroutineScope {
            val byTitle =
                if (c.isPlex && request.title.isNotBlank()) async { queryItems(c, MOVIE, searchTerm = request.title, limit = 25) } else null
            val byIds =
                idQueries(c, request.imdbId, request.tmdbId, null).map { id ->
                    async { quietly(emptyList()) { queryItems(c, MOVIE, anyProviderId = id, limit = 10) } }
                }
            byIds.awaitAll().flatten().forEach { found[it.id] = it }
            byTitle?.await()?.forEach { found.putIfAbsent(it.id, it) }
        }
        if (!c.isPlex && request.title.isNotBlank()) {
            val best = found.values.maxOfOrNull { scoreOf(request, it) } ?: 0
            // No id hit: ask by name, and let a failing server fail here
            if (best < ID_MATCH) queryItems(c, MOVIE, searchTerm = request.title, limit = 25).forEach { found[it.id] = it }
        }
        return pick(found.values.toList(), request)
    }

    /** Series that match; the year is ignored because HD and 4K libraries can disagree on it. */
    private suspend fun seriesMatches(
        c: ServerConnection,
        request: PlayRequest,
    ): List<Item> {
        val (byTitle, byIds) =
            coroutineScope {
                val title =
                    if (request.title.isNotBlank()) {
                        async { attempt { queryItems(c, SERIES, searchTerm = request.title, limit = 25) } }
                    } else {
                        null
                    }
                val ids =
                    idQueries(c, request.imdbId, request.tmdbId, request.tvdbId).map { id ->
                        async { attempt { queryItems(c, SERIES, anyProviderId = id, limit = 10) } }
                    }
                title?.await() to ids.awaitAll()
            }
        val outcomes = listOfNotNull(byTitle) + byIds
        // Everything failing means the server is down, which must not look like "not there"
        if (outcomes.isNotEmpty() && outcomes.all { it.isFailure }) throw outcomes.first().exceptionOrNull()!!

        val found = LinkedHashMap<String, Item>()
        byIds.forEach { r -> r.getOrNull().orEmpty().forEach { found[it.id] = it } }
        byTitle?.getOrNull().orEmpty().forEach { found.putIfAbsent(it.id, it) }
        return pick(found.values.toList(), request.copy(year = null))
    }

    private suspend fun episodeMatches(
        c: ServerConnection,
        request: PlayRequest,
    ): List<Item> {
        val season = request.season ?: return emptyList()
        val episode = request.episode ?: return emptyList()
        val series = seriesMatches(c, request)
        // Only episodes of a series that matched. (A search for episodes by the show's title used
        // to stand in when none did: it matched any episode whose own name had the words in it,
        // so "Lost" S01E05 could play another show's S01E05 called "Lost and Found".)
        return coroutineScope {
            series.map { s -> async { quietly(emptyList()) { episodeOf(c, s.id, season, episode) } } }.awaitAll()
        }.flatten().distinctBy { it.id }
    }

    private fun episodeOf(
        c: ServerConnection,
        seriesId: String,
        season: Int,
        episode: Int,
    ): List<Item> {
        val exact = { it: Item -> it.parentIndex == season && it.index == episode }
        if (c.isPlex) {
            return plexItems(getJson(endpoint(c.serverUrl, "library/metadata/$seriesId/allLeaves", "includeGuids" to "1"), c)).filter(exact)
        }
        val listed =
            getJson(endpoint(c.serverUrl, "Shows/$seriesId/Episodes", "UserId" to c.userId, "Season" to season, "Fields" to ITEM_FIELDS), c)
                .objects("Items")
                .map(::embyItem)
                .filter(exact)
        if (listed.isNotEmpty()) return listed
        return queryItems(c, EPISODE, seriesId = seriesId, parentIndex = season, index = episode, limit = 10).filter(exact)
    }

    /** The id strings to ask for; Plex ignores case, so it gets each id once. */
    private fun idQueries(
        c: ServerConnection,
        imdb: String?,
        tmdb: Int?,
        tvdb: Int?,
    ): List<String> {
        val all = mutableListOf<String>()
        imdb?.trim()?.takeIf { it.isNotBlank() }?.let { all += listOf("imdb.$it", "Imdb.$it") }
        tmdb?.takeIf { it > 0 }?.let { all += listOf("tmdb.$it", "Tmdb.$it") }
        tvdb?.takeIf { it > 0 }?.let { all += listOf("tvdb.$it", "Tvdb.$it") }
        val unique = all.distinct()
        return if (c.isPlex) unique.distinctBy { it.lowercase() } else unique
    }

    private fun scoreOf(
        request: PlayRequest,
        item: Item,
    ): Int = Matcher.score(request.title, request.year, request.imdbId, request.tmdbId, request.tvdbId, item.candidate)

    /** Acceptable matches, best first. Keeps every id match and same-version copy (HD and 4K libraries). */
    private fun pick(
        candidates: List<Item>,
        request: PlayRequest,
    ): List<Item> {
        val scored = candidates.map { it to scoreOf(request, it) }.filter { Matcher.isAcceptable(it.second) }
        if (scored.isEmpty()) return emptyList()
        val best = scored.maxOf { it.second }
        return scored
            .filter { (item, score) ->
                score >= ID_MATCH || score == best || Matcher.isLikelySameVersion(request.title, request.year, item.candidate)
            }.sortedByDescending { it.second }
            .map { it.first }
            .distinctBy { it.id }
    }

    private fun queryItems(
        c: ServerConnection,
        type: String,
        searchTerm: String? = null,
        anyProviderId: String? = null,
        seriesId: String? = null,
        parentIndex: Int? = null,
        index: Int? = null,
        limit: Int? = null,
    ): List<Item> {
        if (c.isPlex) return plexQuery(c, type, searchTerm, anyProviderId, parentIndex, index, limit)
        val url =
            endpoint(
                c.serverUrl,
                "Users/${c.userId}/Items",
                "Recursive" to "true",
                "IncludeItemTypes" to type,
                "Fields" to ITEM_FIELDS,
                "SearchTerm" to searchTerm,
                "AnyProviderIdEquals" to anyProviderId,
                "SeriesId" to seriesId,
                "ParentIndexNumber" to parentIndex,
                "IndexNumber" to index,
                "Limit" to limit,
            )
        return getJson(url, c).objects("Items").map(::embyItem)
    }

    private fun plexQuery(
        c: ServerConnection,
        type: String,
        searchTerm: String?,
        anyProviderId: String?,
        parentIndex: Int?,
        index: Int?,
        limit: Int?,
    ): List<Item> {
        val plexType =
            when (type) {
                MOVIE -> "1"
                SERIES -> "2"
                else -> "4"
            }
        val max = (limit ?: 25).toString()
        val sectionTypes = if (type == MOVIE) setOf("movies", "movie") else setOf("tvshows", "series", "show")
        val sections = c.collections.filter { it.enabled && it.type.lowercase() in sectionTypes }

        if (anyProviderId != null) {
            val provider = anyProviderId.substringBefore('.').lowercase()
            val id = anyProviderId.substringAfter('.', "").trim()
            if (provider in setOf("imdb", "tmdb", "tvdb") && id.isNotBlank()) {
                val hits =
                    sections
                        .flatMap { s ->
                            quietly(emptyList()) {
                                plexItems(
                                    getJson(
                                        endpoint(
                                            c.serverUrl,
                                            "library/sections/${s.id}/all",
                                            "type" to plexType,
                                            "guid" to "$provider://$id",
                                            "includeGuids" to "1",
                                            "limit" to max,
                                        ),
                                        c,
                                    ),
                                )
                            }
                        }.distinctBy { it.id }
                if (hits.isNotEmpty()) return hits
            }
        }

        val term = searchTerm?.takeIf { it.isNotBlank() } ?: return emptyList()
        val sectionIds = sections.map { it.id }.toSet()
        val searched =
            quietly(emptyList()) {
                plexItems(
                    getJson(
                        endpoint(c.serverUrl, "search", "query" to term, "type" to plexType, "includeGuids" to "1", "limit" to max),
                        c,
                    ),
                )
            }.filter { sections.isEmpty() || it.sectionId.isBlank() || it.sectionId in sectionIds }
        // The global search can miss things; then filter each library by title too
        val wanted = Matcher.normalizeTitle(term)
        val filtered =
            if (searched.none { Matcher.normalizeTitle(it.name) == wanted }) {
                sections.flatMap { s ->
                    quietly(emptyList()) {
                        plexItems(
                            getJson(
                                endpoint(
                                    c.serverUrl,
                                    "library/sections/${s.id}/all",
                                    "type" to plexType,
                                    "title" to term,
                                    "includeGuids" to "1",
                                    "limit" to max,
                                ),
                                c,
                            ),
                        )
                    }
                }
            } else {
                emptyList()
            }
        return (searched + filtered)
            .filter { (parentIndex == null || it.parentIndex == parentIndex) && (index == null || it.index == index) }
            .distinctBy { it.id }
    }

    // ---- Building sources ----

    private fun sourcesOf(
        c: ServerConnection,
        item: Item,
    ): List<ExternalSource> {
        // A movie in parts (CD1/CD2): the stream is only the first part, and its end would mark
        // the whole title watched. Not offered
        if (item.partCount > 1) return emptyList()
        // Plex only reports stream details on the item itself; Emby/Jellyfin's PlaybackInfo is the freshest list
        val detailed =
            if (c.isPlex) {
                quietly(emptyList()) {
                    plexItems(
                        getJson(endpoint(c.serverUrl, "library/metadata/${item.id}", "includeGuids" to "1", "includeMedia" to "1"), c),
                    ).firstOrNull()?.media.orEmpty()
                }
            } else {
                quietly(emptyList()) {
                    val url =
                        endpoint(
                            c.serverUrl,
                            "Items/${item.id}/PlaybackInfo",
                            "UserId" to c.userId,
                            "StartTimeTicks" to "0",
                            "IsPlayback" to "true",
                            "AutoOpenLiveStream" to "true",
                            "MaxStreamingBitrate" to "2147483647",
                        )
                    postJson(url, JsonObject(emptyMap()), c).objects("MediaSources").map(::embyMedia)
                }
            }
        val versions = (detailed + item.media).distinctBy { it.identity }

        val out = mutableListOf<ExternalSource>()
        for (media in versions.filter { it.partCount <= 1 }) {
            val direct = directUrl(c, item, media) ?: continue
            out += source(c, item, media, direct, compatible = false)
            if (c.isPlex && media.needsCompatibleStream) {
                compatibleUrl(c, item, media)?.let { out += source(c, item, media, it, compatible = true) }
            }
        }
        return out
    }

    private fun source(
        c: ServerConnection,
        item: Item,
        media: Media,
        url: String,
        compatible: Boolean,
    ): ExternalSource {
        val quality = qualityLabel(media.height, media.width, media.name)
        return ExternalSource(
            connectionId = c.connectionId,
            serverLabel = c.label,
            serverKind = c.serverKind,
            url = url,
            quality = quality.ifEmpty { "?" },
            qualityRank = qualityRank(quality),
            videoCodec = Labels.videoCodec(media.videoCodec),
            hdr = media.hdr.ifEmpty { Labels.hdrFromName(media.name) },
            audio = media.audioLabel,
            container = media.container.substringBefore(',').uppercase(),
            sizeBytes = media.size,
            fileName = media.name,
            compatible = compatible,
            itemId = item.id,
            mediaSourceId = media.id,
            runTimeTicks = media.runTimeTicks,
            width = media.width,
            height = media.height,
            frameRate = media.frameRate,
        )
    }

    /**
     * A copy's chapter pictures and, on Emby, its thumbnail track, for the player (Emby and Jellyfin;
     * Plex has neither in this form). The picture links carry this server's own token, as the
     * stream link does, so no other server's sign-in goes with them.
     */
    fun copyPictures(
        c: ServerConnection,
        itemId: String,
        mediaSourceId: String,
    ): CopyPictures {
        if (c.isPlex) return CopyPictures(emptyList(), null)
        val o = getJson(endpoint(c.serverUrl, "Users/${c.userId}/Items/$itemId", "Fields" to "Chapters"), c)
        val chapters =
            o.objects("Chapters").mapIndexedNotNull { i, ch ->
                val tag = ch.string("ImageTag").ifBlank { return@mapIndexedNotNull null }
                val startMs = (ch.long("StartPositionTicks") ?: return@mapIndexedNotNull null) / 10_000
                startMs to endpoint(c.serverUrl, "Items/$itemId/Images/Chapter/$i", "tag" to tag, "maxWidth" to 480, "api_key" to c.accessToken).toString()
            }
        val bif =
            if (c.serverKind == ServerKind.EMBY) {
                endpoint(c.serverUrl, "Videos/$itemId/index.bif", "width" to 320, "MediaSourceId" to mediaSourceId, "api_key" to c.accessToken).toString()
            } else {
                null
            }
        return CopyPictures(chapters, bif)
    }

    /**
     * Saves [url] (on [c]'s server) to [file], at most [maxBytes]. False when the server has
     * nothing there or sends more than that (what was written is then left for the caller to delete).
     */
    fun download(
        c: ServerConnection,
        url: String,
        file: java.io.File,
        maxBytes: Long = Long.MAX_VALUE,
    ): Boolean {
        val httpUrl = url.toHttpUrlOrNull() ?: return false
        if (hostKey(httpUrl) != hostKey(c.serverUrl)) return false
        http.newCall(serverRequest(httpUrl, c).header("Accept", "*/*").get().build()).execute().use { response ->
            if (!response.isSuccessful) return false
            if (response.body.contentLength() > maxBytes) return false
            file.outputStream().use { out -> if (!copyAtMost(response.body.byteStream(), out, maxBytes)) return false }
        }
        return true
    }

    /** The original file. The server's TranscodingUrl is never used. */
    private fun directUrl(
        c: ServerConnection,
        item: Item,
        media: Media,
    ): String? {
        if (c.isPlex) {
            return when {
                media.key.isNotBlank() -> withPlexToken(absolutePlexUrl(c.serverUrl, media.key), c)
                media.path.isHttp() -> withPlexToken(media.path, c)
                media.id.isNotBlank() -> endpoint(c.serverUrl, "library/parts/${media.id}/file", "X-Plex-Token" to c.accessToken).toString()
                else -> null
            }
        }
        if (media.remote && media.path.isHttp()) return media.path
        val ext =
            media.container
                .substringBefore(',')
                .trim()
                .lowercase()
                .replace("matroska", "mkv")
                .filter { it in 'a'..'z' || it in '0'..'9' }
                .takeIf { it.length in 1..5 }
        val path = if (ext != null) "Videos/${item.id}/stream.$ext" else "Videos/${item.id}/stream"
        // The player can't send headers, so the token rides in the query
        return endpoint(
            c.serverUrl,
            path,
            "Static" to "true",
            "MediaSourceId" to media.id,
            "DeviceId" to deviceId,
            "api_key" to c.accessToken,
            "Tag" to media.etag,
        ).toString()
    }

    /** [url] with the server's token, only when it points at that server (never another host). */
    private fun withPlexToken(
        url: String,
        c: ServerConnection,
    ): String {
        val parsed = url.toHttpUrlOrNull() ?: return url
        if (!parsed.queryParameter("X-Plex-Token").isNullOrBlank()) return url
        if (!isPlexServerHost(parsed, c)) return url
        return parsed
            .newBuilder()
            .addQueryParameter("X-Plex-Token", c.accessToken)
            .build()
            .toString()
    }

    private fun isPlexServerHost(
        url: HttpUrl,
        c: ServerConnection,
    ): Boolean {
        val server = c.serverUrl.toHttpUrlOrNull()
        if (server != null && url.host.equals(server.host, ignoreCase = true)) return true
        // The server's own plex.direct addresses carry its machine id
        val id = c.serverId.lowercase()
        return id.length >= 8 && url.host.lowercase().endsWith(".$id.plex.direct")
    }

    private fun absolutePlexUrl(
        serverUrl: String,
        key: String,
    ): String {
        if (key.isHttp()) return key
        val builder = serverUrl.toHttpUrlOrNull()?.newBuilder() ?: return key
        key
            .substringBefore('?')
            .split('/')
            .filter { it.isNotEmpty() }
            .forEach { builder.addPathSegment(it) }
        key
            .substringAfter('?', "")
            .split('&')
            .filter { it.isNotEmpty() }
            .forEach { pair -> builder.addEncodedQueryParameter(pair.substringBefore('='), pair.substringAfter('=', "")) }
        return builder.build().toString()
    }

    /**
     * A Plex HLS conversion for files the Android player can stall on. It replaces the server
     * URL's path, so a reverse-proxy prefix is not kept for this one URL.
     */
    private fun compatibleUrl(
        c: ServerConnection,
        item: Item,
        media: Media,
    ): String? {
        if (item.id.isBlank()) return null
        val base = c.serverUrl.toHttpUrlOrNull() ?: return null
        val part = media.id.ifBlank { media.partIndex.toString() }
        return base
            .newBuilder()
            .encodedPath("/video/:/transcode/universal/start.m3u8")
            .query(null)
            .addQueryParameter("path", "/library/metadata/${item.id}")
            .addQueryParameter("mediaIndex", media.mediaIndex.toString())
            .addQueryParameter("partIndex", media.partIndex.toString())
            .addQueryParameter("protocol", "hls")
            .addQueryParameter("directPlay", "0")
            .addQueryParameter("directStream", "1")
            .addQueryParameter("videoQuality", "100")
            .addQueryParameter("maxVideoBitrate", "40000")
            .addQueryParameter("session", "$clientName-$deviceId-${item.id}-$part")
            .addQueryParameter("X-Plex-Client-Identifier", deviceId)
            .addQueryParameter("X-Plex-Product", clientName)
            .addQueryParameter("X-Plex-Token", c.accessToken)
            .build()
            .toString()
    }

    // ---- Watch state ----

    fun recentWatchActivity(
        connection: ServerConnection,
        since: Instant,
        limit: Int,
    ): List<WatchEntry> {
        if (connection.isPlex) return emptyList()
        val base = connection.serverUrl
        val user = connection.userId
        val resume =
            getJson(
                endpoint(
                    base,
                    "Users/$user/Items/Resume",
                    "Limit" to limit,
                    "MediaTypes" to "Video",
                    "Fields" to "ProviderIds,ProductionYear",
                    "EnableUserData" to "true",
                ),
                connection,
            ).objects("Items")
        val played =
            getJson(
                endpoint(
                    base,
                    "Users/$user/Items",
                    "Recursive" to "true",
                    "Filters" to "IsPlayed",
                    "IncludeItemTypes" to "Movie,Episode",
                    "SortBy" to "DatePlayed",
                    "SortOrder" to "Descending",
                    "Limit" to limit,
                    "Fields" to "ProviderIds,ProductionYear",
                    "EnableUserData" to "true",
                ),
                connection,
            ).objects("Items")

        val seriesById = HashMap<String, JsonObject?>()
        fun series(id: String): JsonObject? =
            seriesById.getOrPut(id) {
                quietly<JsonObject?>(null) { getJson(endpoint(base, "Users/$user/Items/$id", "Fields" to "ProviderIds,ProductionYear"), connection) }
            }

        val entries =
            (resume + played).mapNotNull { item ->
                val data = item.obj("UserData") ?: return@mapNotNull null
                val lastPlayed = parseDate(data.string("LastPlayedDate"))?.takeIf { it.isAfter(since) } ?: return@mapNotNull null
                val request =
                    if (item.string("Type").equals("Episode", ignoreCase = true)) {
                        val seriesId = item.string("SeriesId").ifBlank { return@mapNotNull null }
                        val show = series(seriesId) ?: return@mapNotNull null
                        val ids = providerIds(show)
                        PlayRequest(
                            title = show.string("Name"),
                            year = show.int("ProductionYear"),
                            imdbId = ids["imdb"]?.takeIf { it.isNotBlank() },
                            tmdbId = ids["tmdb"]?.toIntOrNull(),
                            tvdbId = ids["tvdb"]?.toIntOrNull(),
                            season = item.int("ParentIndexNumber") ?: return@mapNotNull null,
                            episode = item.int("IndexNumber") ?: return@mapNotNull null,
                        )
                    } else {
                        val ids = providerIds(item)
                        PlayRequest(
                            item.string("Name"),
                            item.int("ProductionYear"),
                            ids["imdb"]?.takeIf { it.isNotBlank() },
                            ids["tmdb"]?.toIntOrNull(),
                            null,
                        )
                    }
                WatchEntry(
                    itemId = item.string("Id"),
                    request = request,
                    positionTicks = data.long("PlaybackPositionTicks") ?: 0L,
                    played = data.boolean("Played") == true,
                    lastPlayed = lastPlayed,
                )
            }
        // A resume entry beats a played entry for the same item
        return entries.distinctBy { it.itemId }.sortedByDescending { it.lastPlayed }
    }

    fun userData(
        connection: ServerConnection,
        itemId: String,
    ): UserItemData? {
        // Plex keeps where you are on the item itself (viewOffset, ms) and how often it was seen
        if (connection.serverKind == ServerKind.PLEX) {
            val o = getJson(endpoint(connection.serverUrl, "library/metadata/$itemId"), connection)
            val m = (o.array("MediaContainer", "Metadata").firstOrNull() ?: o.objects("Metadata").firstOrNull()) as? JsonObject ?: return null
            return UserItemData(
                positionTicks = (m.long("viewOffset") ?: 0L) * 10_000L,
                played = (m.int("viewCount") ?: 0) > 0 && (m.long("viewOffset") ?: 0L) == 0L,
                lastPlayed = m.long("lastViewedAt")?.let { Instant.ofEpochSecond(it) },
                seriesId = m.string("grandparentRatingKey").ifBlank { null },
            )
        }
        // Jellyfin 10.9+ moved item user data; Emby and older Jellyfin keep the per-user route
        val current = { getJson(endpoint(connection.serverUrl, "Items/$itemId", "userId" to connection.userId), connection) }
        val legacy = { getJson(endpoint(connection.serverUrl, "Users/${connection.userId}/Items/$itemId"), connection) }
        val (first, second) = if (connection.serverKind == ServerKind.JELLYFIN) current to legacy else legacy to current
        val answer =
            try {
                first()
            } catch (e: ServerRequestException) {
                if (e.statusCode == 404 || e.statusCode == 405) second() else throw e
            }
        val data = answer.obj("UserData") ?: return null
        return UserItemData(
            positionTicks = data.long("PlaybackPositionTicks") ?: 0L,
            played = data.boolean("Played") == true,
            lastPlayed = parseDate(data.string("LastPlayedDate")),
            seriesId = answer.string("SeriesId").ifBlank { null },
        )
    }

    fun reportPlayback(
        connection: ServerConnection,
        source: ExternalSource,
        event: PlayEvent,
        positionMs: Long,
        durationMs: Long,
        paused: Boolean,
        playSessionId: String,
    ) {
        if (source.itemId.isBlank()) return
        if (connection.isPlex) {
            val state =
                when {
                    event == PlayEvent.STOP -> "stopped"
                    paused -> "paused"
                    else -> "playing"
                }
            getText(
                endpoint(
                    connection.serverUrl,
                    ":/timeline",
                    "ratingKey" to source.itemId,
                    "key" to "/library/metadata/${source.itemId}",
                    "state" to state,
                    "time" to positionMs,
                    "duration" to durationMs,
                ),
                connection,
            )
            return
        }
        val path =
            when (event) {
                PlayEvent.START -> "Sessions/Playing"
                PlayEvent.PROGRESS -> "Sessions/Playing/Progress"
                PlayEvent.STOP -> "Sessions/Playing/Stopped"
            }
        val body =
            buildJsonObject {
                put("ItemId", source.itemId)
                put("MediaSourceId", source.mediaSourceId)
                put("PlaySessionId", playSessionId)
                put("PositionTicks", positionMs * 10_000L)
                put("IsPaused", paused)
                put("CanSeek", true)
                put("PlayMethod", "DirectPlay")
            }
        postJson(endpoint(connection.serverUrl, path), body, connection)
    }

    // ---- Parsing ----

    private fun embyItem(o: JsonObject): Item =
        Item(
            id = o.string("Id"),
            name = o.string("Name"),
            year = o.int("ProductionYear") ?: o.string("PremiereDate").take(4).toIntOrNull(),
            providerIds = providerIds(o),
            sectionId = "",
            index = o.int("IndexNumber"),
            parentIndex = o.int("ParentIndexNumber"),
            media = o.objects("MediaSources").map(::embyMedia),
            partCount = o.int("PartCount") ?: 1,
        )

    /** Keys lowercased. A JSON null value becomes the text "null", as the shared accessors do. */
    private fun providerIds(o: JsonObject): Map<String, String> =
        o
            .obj("ProviderIds")
            ?.entries
            ?.mapNotNull { (k, v) -> (v as? JsonPrimitive)?.let { k.lowercase() to it.content } }
            ?.toMap()
            .orEmpty()

    private fun embyMedia(o: JsonObject): Media {
        val streams = o.objects("MediaStreams")
        val video = streams.firstOrNull { it.string("Type").equals("Video", ignoreCase = true) }
        val audios = streams.filter { it.string("Type").equals("Audio", ignoreCase = true) }
        val audio = audios.firstOrNull { it.boolean("IsDefault") == true } ?: audios.firstOrNull()
        val path = o.string("Path")
        val name = o.string("Name").ifBlank { lastPathPart(path) }
        return Media(
            id = o.string("Id"),
            key = "",
            name = name,
            path = path,
            container = o.string("Container"),
            etag = o.string("ETag").ifBlank { o.string("Etag") },
            size = o.long("Size") ?: 0L,
            remote = o.boolean("IsRemote") == true,
            runTimeTicks = o.long("RunTimeTicks") ?: 0L,
            width = video?.int("Width") ?: 0,
            height = video?.int("Height") ?: 0,
            videoCodec = video?.string("Codec").orEmpty(),
            videoProfile = video?.string("Profile").orEmpty(),
            bitDepth = video?.int("BitDepth") ?: 0,
            audioCodec = audio?.string("Codec").orEmpty(),
            audioProfile = audio?.string("Profile").orEmpty(),
            audioLabel =
                audio
                    ?.let {
                        Labels.audio(it.string("Codec"), it.string("Profile"), it.int("Channels"), it.string("DisplayTitle"), path.ifBlank { name })
                    }.orEmpty(),
            hdr = video?.let(::embyHdr).orEmpty(),
            mediaIndex = 0,
            partIndex = 0,
            variantKey = "",
            frameRate = (video?.string("RealFrameRate")?.toFloatOrNull() ?: video?.string("AverageFrameRate")?.toFloatOrNull()) ?: 0f,
        )
    }

    private fun embyHdr(video: JsonObject): String {
        val range = video.string("VideoRangeType")
        return when {
            range.startsWith("DOVI", ignoreCase = true) ||
                video.string("DvProfile").isNotBlank() ||
                video.string("ExtendedVideoType").contains("DolbyVision", ignoreCase = true) -> "Dolby Vision"
            range.equals("HDR10Plus", ignoreCase = true) || video.string("ExtendedVideoSubType").contains("Plus") -> "HDR10+"
            range.equals("HLG", ignoreCase = true) -> "HLG"
            range.startsWith("HDR", ignoreCase = true) || video.string("VideoRange").equals("HDR", ignoreCase = true) -> "HDR10"
            else -> ""
        }
    }

    private fun plexItems(root: JsonObject): List<Item> {
        val inContainer = root.array("MediaContainer", "Metadata").filterIsInstance<JsonObject>()
        val list = inContainer.ifEmpty { root.objects("Metadata") }
        return list.map(::plexItem)
    }

    private fun plexItem(o: JsonObject): Item {
        val ids = LinkedHashMap<String, String>()
        for (guid in o.objects("Guid")) {
            val text = guid.string("id")
            val provider = text.substringBefore("://").lowercase()
            val value = text.substringAfter("://", "").substringBefore('?')
            if (provider.isNotBlank() && value.isNotBlank()) ids[provider] = value
        }
        return Item(
            id = o.string("ratingKey").ifBlank { o.string("key") },
            name = o.string("title"),
            year = o.int("year") ?: o.string("originallyAvailableAt").take(4).toIntOrNull(),
            providerIds = ids,
            sectionId = o.string("librarySectionID"),
            index = o.int("index"),
            parentIndex = o.int("parentIndex"),
            media =
                o.objects("Media").flatMapIndexed { mediaIndex, m ->
                    val parts = m.objects("Part")
                    parts.mapIndexed { partIndex, p -> plexMedia(m, p, mediaIndex, partIndex).copy(partCount = parts.size) }
                },
        )
    }

    private fun plexMedia(
        m: JsonObject,
        p: JsonObject,
        mediaIndex: Int,
        partIndex: Int,
    ): Media {
        val streams = p.objects("Stream")
        val video = streams.firstOrNull { it.string("streamType") == "1" }
        val audios = streams.filter { it.string("streamType") == "2" }
        val audio = audios.firstOrNull { it.boolean("selected") == true || it.boolean("default") == true } ?: audios.firstOrNull()
        val file = p.string("file")
        val audioCodec = audio?.string("codec").orEmpty().ifBlank { m.string("audioCodec") }
        val audioProfile = audio?.string("profile").orEmpty()
        val trackText = audio?.string("displayTitle").orEmpty() + " " + audio?.string("title").orEmpty()
        val duration = m.long("duration") ?: p.long("duration") ?: 0L
        return Media(
            id = p.string("id"),
            key = p.string("key"),
            name = lastPathPart(file).ifBlank { m.string("title") },
            path = file,
            container = m.string("container").ifBlank { p.string("container") },
            etag = "",
            size = p.long("size") ?: 0L,
            remote = false,
            // Plex durations are milliseconds; ticks are 100 ns
            runTimeTicks = duration * 10_000L,
            width = m.int("width") ?: p.int("width") ?: 0,
            height = m.int("height") ?: p.int("height") ?: 0,
            videoCodec = video?.string("codec").orEmpty().ifBlank { m.string("videoCodec") },
            videoProfile = video?.string("profile").orEmpty().ifBlank { m.string("videoProfile") },
            bitDepth = video?.int("bitDepth") ?: m.int("bitDepth") ?: 0,
            audioCodec = audioCodec,
            audioProfile = audioProfile,
            audioLabel = Labels.audio(audioCodec, audioProfile, audio?.int("channels") ?: m.int("audioChannels"), trackText, file),
            hdr = video?.let(::plexHdr).orEmpty(),
            mediaIndex = mediaIndex,
            partIndex = partIndex,
            variantKey =
                listOf(m.string("id"), m.string("bitrate"), p.string("id"), p.string("key"), file, p.string("size"))
                    .filter { it.isNotBlank() }
                    .joinToString("|"),
            frameRate = video?.string("frameRate")?.toFloatOrNull() ?: plexFrameRate(m.string("videoFrameRate")),
        )
    }

    /** Plex's summary frame rate ("24p", "PAL", "NTSC", "60p"); 0 when unknown. */
    private fun plexFrameRate(text: String): Float =
        when (text.lowercase()) {
            "24p" -> 23.976f
            "pal" -> 25f
            "ntsc" -> 29.97f
            "50p" -> 50f
            "60p" -> 59.94f
            else -> text.trimEnd('p', 'P').toFloatOrNull() ?: 0f
        }

    private fun plexHdr(video: JsonObject): String {
        val title = video.string("displayTitle")
        val transfer = video.string("colorTrc")
        return when {
            video.boolean("DOVIPresent") == true || video.string("DOVIProfile").isNotBlank() -> "Dolby Vision"
            title.contains("HDR10+", ignoreCase = true) -> "HDR10+"
            transfer.equals("arib-std-b67", ignoreCase = true) -> "HLG"
            transfer.equals("smpte2084", ignoreCase = true) || title.contains("HDR", ignoreCase = true) -> "HDR10"
            else -> ""
        }
    }

    /** (friendlyName, machineIdentifier) from a Plex /identity answer, JSON or XML. */
    private fun plexIdentity(body: String): Pair<String, String> {
        try {
            val root = json.parseToJsonElement(body) as? JsonObject
            if (root != null) {
                val container = root.obj("MediaContainer") ?: root
                val name = container.string("friendlyName")
                val id = container.string("machineIdentifier")
                if (name.isNotBlank() || id.isNotBlank()) return name to id
            }
        } catch (e: Exception) {
            // Not JSON: read it as XML below
        }
        return xmlAttribute(body, "friendlyName") to xmlAttribute(body, "machineIdentifier")
    }

    private fun xmlAttribute(
        body: String,
        name: String,
    ): String = Regex("(?<![A-Za-z0-9_])$name=(?:\"([^\"]*)\"|'([^']*)')").find(body)?.let { it.groupValues[1].ifEmpty { it.groupValues[2] } }.orEmpty()

    private fun parseDate(text: String): Instant? {
        if (text.isBlank()) return null
        return try {
            OffsetDateTime.parse(text, DateTimeFormatter.ISO_OFFSET_DATE_TIME).toInstant()
        } catch (e: Exception) {
            try {
                Instant.parse(text)
            } catch (e: Exception) {
                null
            }
        }
    }

    // ---- HTTP plumbing ----

    /** A server URL plus path segments and the non-blank query values, in the order given. */
    private fun endpoint(
        base: String,
        path: String,
        vararg query: Pair<String, Any?>,
    ): HttpUrl {
        val builder = base.toHttpUrlOrNull()?.newBuilder() ?: throw IllegalStateException("Invalid server address")
        path.split('/').filter { it.isNotEmpty() }.forEach { builder.addPathSegment(it) }
        for ((name, value) in query) {
            val text = value?.toString()
            if (!text.isNullOrBlank()) builder.addQueryParameter(name, text)
        }
        return builder.build()
    }

    private fun mediaBrowser(token: String?): String {
        val base = "MediaBrowser Client=\"$clientName\", Device=\"Android TV\", DeviceId=\"$deviceId\", Version=\"$clientVersion\""
        return if (token.isNullOrBlank()) base else "$base, Token=\"$token\""
    }

    private fun plexHeaders(
        builder: Request.Builder,
        token: String?,
    ): Request.Builder =
        builder
            .header("Accept", "application/json")
            .header("X-Plex-Client-Identifier", deviceId)
            .header("X-Plex-Product", clientName)
            .header("X-Plex-Version", clientVersion)
            .header("X-Plex-Device", "Android TV")
            .header("X-Plex-Platform", "Android")
            .apply { if (!token.isNullOrBlank()) header("X-Plex-Token", token) }

    /** Headers for a Jellyfin, Emby or Plex server; no connection means no token. */
    private fun serverRequest(
        url: HttpUrl,
        connection: ServerConnection?,
    ): Request.Builder {
        val builder =
            Request
                .Builder()
                .url(url)
                .header("Accept", "application/json")
                .header("User-Agent", "$clientName/$clientVersion")
        if (connection?.isPlex == true) return plexHeaders(builder, connection.accessToken)
        val token = connection?.accessToken
        val auth = mediaBrowser(token)
        builder.header("Authorization", auth).header("X-Emby-Authorization", auth)
        if (!token.isNullOrBlank()) builder.header("X-Emby-Token", token)
        return builder
    }

    private fun connectRequest(url: HttpUrl): Request.Builder = Request.Builder().url(url).header("X-Application", "$clientName/$clientVersion")

    private fun call(request: Request): String {
        val host = hostKey(request.url)
        val response =
            try {
                http.newCall(request).execute()
            } catch (e: IOException) {
                troubleAt[host] = -1 to System.currentTimeMillis()
                throw e
            }
        return response.use {
            val body = it.body.string()
            if (!it.isSuccessful) {
                // Read only when a lookup failed as a whole (a single missing item doesn't count)
                troubleAt[host] = it.code to System.currentTimeMillis()
                throw ServerRequestException(it.code, httpError(it.code))
            }
            answeredAt[host] = System.currentTimeMillis()
            body
        }
    }

    private fun httpError(code: Int): String {
        val hint =
            when (code) {
                401 -> " (wrong username/password or expired token)"
                404, 405 -> " (not a Plex/Emby/Jellyfin API address)"
                else -> ""
            }
        return "Server answered HTTP $code$hint"
    }

    /** A blank body counts as an empty object. Malformed JSON throws. */
    private fun parse(body: String): JsonElement = if (body.isBlank()) JsonObject(emptyMap()) else json.parseToJsonElement(body)

    private fun getJson(
        url: HttpUrl,
        connection: ServerConnection?,
    ): JsonObject = parse(getText(url, connection)) as? JsonObject ?: JsonObject(emptyMap())

    private fun getText(
        url: HttpUrl,
        connection: ServerConnection?,
    ): String = call(serverRequest(url, connection).get().build())

    private fun postJson(
        url: HttpUrl,
        body: JsonObject,
        connection: ServerConnection?,
    ): JsonObject {
        val request = serverRequest(url, connection).post(body.toString().toRequestBody(JSON_TYPE)).build()
        return parse(call(request)) as? JsonObject ?: JsonObject(emptyMap())
    }

    /** Runs [block]; any failure except cancellation gives [fallback]. */
    private inline fun <T> quietly(
        fallback: T,
        block: () -> T,
    ): T =
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            fallback
        }

    private inline fun <T> attempt(block: () -> T): Result<T> =
        try {
            Result.success(block())
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }

    private val ServerConnection.isPlex: Boolean get() = serverKind == ServerKind.PLEX

    private fun String.isHttp(): Boolean = startsWith("http://", ignoreCase = true) || startsWith("https://", ignoreCase = true)

    private fun lastPathPart(path: String): String = path.substringAfterLast('/').substringAfterLast('\\')

    private data class Item(
        val id: String,
        val name: String,
        val year: Int?,
        val providerIds: Map<String, String>,
        val sectionId: String,
        val index: Int?,
        val parentIndex: Int?,
        val media: List<Media>,
        /** Parts of a stacked movie (Jellyfin/Emby); 1 for a single file. */
        val partCount: Int = 1,
    ) {
        val candidate: CandidateInfo get() = CandidateInfo(name, year, providerIds)
    }

    /** One playable version (Emby media source, or Plex media + part). */
    private data class Media(
        val id: String,
        val key: String,
        val name: String,
        val path: String,
        val container: String,
        val etag: String,
        val size: Long,
        val remote: Boolean,
        val runTimeTicks: Long,
        val width: Int,
        val height: Int,
        val videoCodec: String,
        val videoProfile: String,
        val bitDepth: Int,
        val audioCodec: String,
        val audioProfile: String,
        val audioLabel: String,
        val hdr: String,
        val mediaIndex: Int,
        val partIndex: Int,
        val variantKey: String,
        val frameRate: Float = 0f,
        /** Parts of the Plex media this part belongs to. */
        val partCount: Int = 1,
    ) {
        val identity: String
            get() =
                variantKey.ifBlank { id }.ifBlank { key }.ifBlank { path }.ifBlank { "$container|$size|$width|$height" }

        /** MKV with 10-bit HEVC or HE-AAC, which the Android player can stall on. */
        val needsCompatibleStream: Boolean
            get() {
                val box = container.lowercase()
                if (box != "mkv" && box != "matroska") return false
                val profile = videoProfile.lowercase()
                val hevc10 =
                    videoCodec.lowercase() in setOf("hevc", "h265", "h.265") &&
                        (bitDepth >= 10 || "main 10" in profile || "main10" in profile)
                val heAac = audioCodec.lowercase() == "aac" && audioProfile.lowercase().let { "he" in it || "sbr" in it }
                return hevc10 || heAac
            }
    }

    private data class PlexAddress(
        val uri: String,
        val local: Boolean,
        val relay: Boolean,
    )

    private data class PlexDevice(
        val name: String,
        val clientId: String,
        val accessToken: String,
        val owned: Boolean,
        val addresses: List<PlexAddress>,
    )

    companion object {
        private const val PLEX_TV = "https://plex.tv/api/v2"
        private const val EMBY_CONNECT = "https://connect.emby.media/service"
        private const val PLEX_USER = "plex"
        private const val ID_MATCH = 900
        private const val MOVIE = "Movie"
        private const val SERIES = "Series"
        private const val EPISODE = "Episode"
        private const val ITEM_FIELDS = "ProviderIds,MediaSources,MediaStreams,Path,PremiereDate,ProductionYear"
        private val JSON_TYPE = "application/json; charset=utf-8".toMediaType()
        private val unsafeIdChars = Regex("[^A-Za-z0-9:._-]+")

        /** Stored with each connection and used to spot duplicates: the format must never change. */
        fun connectionId(
            serverUrl: String,
            kind: ServerKind,
            user: String,
        ): String = "${kind.name}:${serverUrl.trimEnd('/').lowercase()}:${user.lowercase()}".replace(unsafeIdChars, "_")
    }
}

/** Copies [input] to [out]; false (stopping there) once more than [maxBytes] came. */
internal fun copyAtMost(
    input: java.io.InputStream,
    out: java.io.OutputStream,
    maxBytes: Long,
): Boolean {
    val buffer = ByteArray(64 * 1024)
    var total = 0L
    while (true) {
        val n = input.read(buffer)
        if (n < 0) return true
        total += n
        if (total > maxBytes) return false
        out.write(buffer, 0, n)
    }
}
