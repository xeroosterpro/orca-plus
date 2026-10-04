// Contains code adapted from a third-party project under the Apache License 2.0 and modified
// for Orca+. See NOTICE and LICENSES/Apache-2.0.txt.
package com.wholphinplus.sources.core

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.Locale

class ServerRequestException(
    val statusCode: Int,
    message: String,
) : IllegalStateException(message)

/**
 * Talks to Plex, Emby and Jellyfin servers: sign-in, and finding playable copies of a title.
 * Every call blocks; callers run it on an IO dispatcher.
 */
class ServerClient(
    private val http: OkHttpClient,
    private val deviceId: String,
    private val clientName: String,
    private val clientVersion: String,
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val jsonType = "application/json; charset=utf-8".toMediaType()

    // ---------------------------------------------------------------- sign-in

    fun fetchPublicInfo(serverUrl: String): ServerInfo {
        val info =
            try {
                getJson(buildUrl(serverUrl, "/System/Info/Public"))
            } catch (e: Exception) {
                // A transport failure won't be fixed by probing another path on the same host.
                if (e is CancellationException || e is IOException) throw e
                if (e is ServerRequestException && (e.statusCode == 429 || e.statusCode >= 500)) throw e
                null
            }
        if (info != null && info.isNotEmpty()) {
            return ServerInfo(
                serverName = info.string("ServerName"),
                serverId = info.string("Id"),
                productName = info.string("ProductName"),
                serverKind =
                    detectServerKind(info.string("ProductName"), info.string("ServerName"))
                        .takeUnless { it == ServerKind.UNKNOWN }
                        // Emby's public info has no ProductName; Jellyfin's always does.
                        ?: if (info.string("ProductName").isBlank()) ServerKind.EMBY else ServerKind.UNKNOWN,
            )
        }
        val identity =
            try {
                getText(buildUrl(serverUrl, "/identity"))
            } catch (e: Exception) {
                if (e is CancellationException || e is IOException) throw e
                null
            }
        val (name, id) = parsePlexIdentity(identity.orEmpty())
        val isPlex = id.isNotBlank() || identity?.contains("MediaContainer") == true
        return ServerInfo(
            serverName = name,
            serverId = id,
            productName = if (isPlex) "Plex Media Server" else "",
            serverKind = if (isPlex) ServerKind.PLEX else ServerKind.UNKNOWN,
        )
    }

    /** Emby/Jellyfin username + password. */
    fun signIn(
        serverUrl: String,
        info: ServerInfo,
        username: String,
        password: String,
        displayName: String,
    ): ServerConnection {
        require(username.isNotBlank()) { "Enter a username" }
        val body =
            buildJsonObject {
                put("Username", username)
                put("Pw", password)
                put("Password", password)
            }
        val response = postJson(buildUrl(serverUrl, "/Users/AuthenticateByName"), body)
        return connectionFromAuth(serverUrl, info, info.serverKind, response, displayName, username)
    }

    fun startQuickConnect(serverUrl: String): CodeLogin {
        val response = postJson(buildUrl(serverUrl, "/QuickConnect/Initiate"), JsonObject(emptyMap()))
        val code = response.string("Code")
        val secret = response.string("Secret")
        require(code.isNotBlank() && secret.isNotBlank()) { "Quick Connect is not enabled on this server" }
        return CodeLogin(secret, secret, code, serverUrl, ServerKind.JELLYFIN, serverUrl)
    }

    /** Returns null until the code has been approved on the server. */
    fun pollQuickConnect(
        login: CodeLogin,
        displayName: String,
    ): ServerConnection? {
        val state = getJson(buildUrl(login.serverUrl, "/QuickConnect/Connect", mapOf("secret" to login.secret)))
        if (state.boolean("Authenticated") != true) return null
        val response =
            postJson(
                buildUrl(login.serverUrl, "/Users/AuthenticateWithQuickConnect"),
                buildJsonObject { put("Secret", login.secret) },
            )
        val info = fetchPublicInfo(login.serverUrl)
        return connectionFromAuth(login.serverUrl, info, ServerKind.JELLYFIN, response, displayName, "")
    }

    private fun connectionFromAuth(
        serverUrl: String,
        info: ServerInfo,
        kind: ServerKind,
        response: JsonObject,
        displayName: String,
        fallbackUser: String,
    ): ServerConnection {
        val user = response.obj("User")
        val token = response.string("AccessToken")
        val userId = user?.string("Id").orEmpty()
        require(token.isNotBlank() && userId.isNotBlank()) { "The server did not return a usable account" }
        val shell =
            ServerConnection(
                connectionId = connectionId(serverUrl, kind, userId),
                serverUrl = serverUrl,
                displayName = displayName.trim(),
                serverName = info.serverName.ifBlank { user?.string("ServerName").orEmpty() }.ifBlank { kind.label },
                serverKind = kind,
                serverId = response.string("ServerId").ifBlank { info.serverId },
                userId = userId,
                userName = user?.string("Name").orEmpty().ifBlank { fallbackUser },
                accessToken = token,
                lastConnectedAt = System.currentTimeMillis(),
            )
        return shell.copy(collections = runCatching { fetchCollections(shell) }.getOrDefault(emptyList()))
    }

    fun startPlexPin(serverUrl: String): CodeLogin {
        val url =
            "https://plex.tv/api/v2/pins"
                .toHttpUrlOrNull()!!
                .newBuilder()
                .addQueryParameter("strong", "false")
                .addQueryParameter("X-Plex-Client-Identifier", deviceId)
                .addQueryParameter("X-Plex-Product", clientName)
                .build()
        val request =
            Request
                .Builder()
                .url(url)
                .post(ByteArray(0).toRequestBody(null))
                .apply { plexHeaders(null).forEach { (k, v) -> header(k, v) } }
                .build()
        val body = execute(request)
        val obj = json.parseToJsonElement(body) as? JsonObject ?: JsonObject(emptyMap())
        val id = obj.string("id")
        val code = obj.string("code")
        require(id.isNotBlank() && code.isNotBlank()) { "Plex did not return a sign-in code" }
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

    /** Returns null until the PIN has been linked at plex.tv/link. */
    fun pollPlexPin(
        login: CodeLogin,
        displayName: String,
    ): ServerConnection? {
        val url =
            "https://plex.tv/api/v2/pins/${login.id}"
                .toHttpUrlOrNull()!!
                .newBuilder()
                .addQueryParameter("X-Plex-Client-Identifier", deviceId)
                .build()
        val request =
            Request
                .Builder()
                .url(url)
                .get()
                .apply { plexHeaders(null).forEach { (k, v) -> header(k, v) } }
                .build()
        val obj = json.parseToJsonElement(execute(request)) as? JsonObject ?: return null
        val token = obj.string("authToken").takeIf { it.isNotBlank() } ?: return null
        return buildPlexConnection(token, login.serverUrl, displayName)
    }

    /**
     * Resolve a Plex account token to one server: the one at [preferredServerUrl] if given,
     * otherwise the account's own server. Tries local, then remote, then relay addresses.
     */
    fun buildPlexConnection(
        accountToken: String,
        preferredServerUrl: String,
        displayName: String,
    ): ServerConnection {
        val token = accountToken.trim()
        require(token.isNotBlank()) { "Missing Plex token" }
        val preferredUrl = normalizeServerUrl(preferredServerUrl)
        val preferredId =
            preferredUrl.takeIf { it.isNotBlank() }?.let { url ->
                runCatching { parsePlexIdentity(getText(buildUrl(url, "/identity"))).second }.getOrNull()
            }.orEmpty()
        val accountName = plexAccountName(token)
        val devices = fetchPlexResources(token)
        val device = selectPlexDevice(devices, preferredId, preferredUrl)
        val serverId = device?.clientIdentifier?.ifBlank { null } ?: preferredId
        val serverToken = device?.accessToken?.takeIf { it.isNotBlank() } ?: token
        val candidates =
            buildList {
                if (preferredUrl.isNotBlank()) add(preferredUrl)
                device
                    ?.connections
                    ?.sortedWith(
                        compareByDescending<PlexAddress> { it.local && !it.relay }
                            .thenBy { it.relay }
                            .thenByDescending { it.uri.startsWith("https://", true) },
                    )?.forEach { add(it.uri) }
            }.map(::normalizeServerUrl).filter { it.isNotBlank() }.distinctBy { it.lowercase(Locale.US) }
        require(candidates.isNotEmpty()) { "No reachable address for this Plex server" }

        var lastError: Throwable? = null
        for (candidateUrl in candidates) {
            val shell =
                ServerConnection(
                    serverUrl = candidateUrl,
                    displayName = displayName.trim(),
                    serverName = device?.name.orEmpty(),
                    serverKind = ServerKind.PLEX,
                    serverId = serverId,
                    userId = "plex",
                    userName = accountName,
                    accessToken = serverToken,
                    accountToken = token,
                    lastConnectedAt = System.currentTimeMillis(),
                )
            try {
                val (name, id) = parsePlexIdentity(getText(buildUrl(candidateUrl, "/identity"), shell))
                if (serverId.isNotBlank() && id.isNotBlank() && serverId != id) {
                    lastError = IllegalStateException("$candidateUrl is a different Plex server")
                    continue
                }
                val resolved =
                    shell.copy(
                        connectionId = connectionId(candidateUrl, ServerKind.PLEX, id.ifBlank { accountName }),
                        serverName = name.ifBlank { shell.serverName }.ifBlank { "Plex" },
                        serverId = id.ifBlank { serverId },
                    )
                val collections = fetchCollections(resolved)
                if (collections.isNotEmpty()) return resolved.copy(collections = collections)
                lastError = IllegalStateException("No libraries on this Plex server")
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                lastError = e
            }
        }
        throw lastError ?: IllegalStateException("Could not reach the Plex server")
    }

    /** Re-check a saved connection; returns it with fresh name, kind and libraries. */
    fun refresh(connection: ServerConnection): ServerConnection {
        if (connection.serverKind == ServerKind.PLEX) {
            val refreshed =
                buildPlexConnection(
                    connection.accountToken.ifBlank { connection.accessToken },
                    connection.serverUrl,
                    connection.displayName,
                )
            return refreshed.copy(
                enabled = connection.enabled,
                connectionId = connection.connectionId.ifBlank { refreshed.connectionId },
                collections = mergeCollections(refreshed.collections, connection.collections),
            )
        }
        val info = getJson(buildUrl(connection.serverUrl, "/System/Info"), connection)
        val shell =
            connection.copy(
                serverName = info.string("ServerName").ifBlank { connection.serverName },
                serverId = info.string("Id").ifBlank { connection.serverId },
                lastConnectedAt = System.currentTimeMillis(),
            )
        return shell.copy(collections = mergeCollections(fetchCollections(shell), connection.collections))
    }

    private fun mergeCollections(
        fresh: List<ServerCollection>,
        previous: List<ServerCollection>,
    ): List<ServerCollection> {
        val byId = previous.associateBy { it.id }
        return fresh.map { it.copy(enabled = byId[it.id]?.enabled ?: it.enabled) }
    }

    fun fetchCollections(connection: ServerConnection): List<ServerCollection> {
        if (connection.serverKind == ServerKind.PLEX) {
            return getJson(buildUrl(connection.serverUrl, "/library/sections"), connection)
                .array("MediaContainer", "Directory")
                .filterIsInstance<JsonObject>()
                .mapNotNull { dir ->
                    val id = dir.string("key").ifBlank { return@mapNotNull null }
                    ServerCollection(id, dir.string("title").ifBlank { "Library $id" }, dir.string("type"))
                }
        }
        return getJson(buildUrl(connection.serverUrl, "/Users/${connection.userId}/Views"), connection)
            .objects("Items")
            .mapNotNull { item ->
                val id = item.string("Id").ifBlank { return@mapNotNull null }
                ServerCollection(id, item.string("Name").ifBlank { "Library" }, item.string("CollectionType"))
            }
    }

    // ---------------------------------------------------------------- source discovery

    /** Every playable copy of [request] on [connection], best first. Never throws except on cancel. */
    suspend fun findSources(
        connection: ServerConnection,
        request: PlayRequest,
    ): List<ExternalSource> =
        try {
            val items =
                if (request.isEpisode) {
                    findEpisodes(connection, request)
                } else {
                    findMovies(connection, request)
                }
            coroutineScope {
                items.map { async { buildSources(connection, it) } }.awaitAll().flatten()
            }.distinctBy { it.url }.sortedWith(sourceRanking)
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            timber.log.Timber.w(e, "Source lookup failed on %s", connection.label)
            emptyList()
        }

    private suspend fun findMovies(
        connection: ServerConnection,
        r: PlayRequest,
    ): List<Item> {
        val candidates = linkedMapOf<String, Item>()
        coroutineScope {
            val plexTitle =
                async {
                    if (connection.serverKind == ServerKind.PLEX && r.title.isNotBlank()) {
                        queryItems(connection, "Movie", mapOf("SearchTerm" to r.title, "Limit" to "25"))
                    } else {
                        emptyList()
                    }
                }
            providerQueries(r.imdbId, r.tmdbId, null)
                .distinctBy { if (connection.serverKind == ServerKind.PLEX) it.lowercase(Locale.US) else it }
                .map { id ->
                    async {
                        quietly { queryItems(connection, "Movie", mapOf("AnyProviderIdEquals" to id, "Limit" to "10")) }
                    }
                }.awaitAll()
                .flatten()
                .forEach { candidates[it.id] = it }
            plexTitle.await().forEach { candidates.putIfAbsent(it.id, it) }
        }
        val bestScore = candidates.values.maxOfOrNull { score(r, it) } ?: 0
        if (r.title.isNotBlank() && connection.serverKind != ServerKind.PLEX && bestScore < 900) {
            queryItems(connection, "Movie", mapOf("SearchTerm" to r.title, "Limit" to "25"))
                .forEach { candidates[it.id] = it }
        }
        return matching(candidates.values, r)
    }

    private suspend fun findEpisodes(
        connection: ServerConnection,
        r: PlayRequest,
    ): List<Item> {
        val season = r.season!!
        val episode = r.episode!!
        val series = seriesMatches(connection, r)
        return episodesFor(connection, r, series, season, episode)
    }

    private suspend fun seriesMatches(
        connection: ServerConnection,
        r: PlayRequest,
    ): List<Item> {
        val candidates = linkedMapOf<String, Item>()
        coroutineScope {
            val byTitle =
                async {
                    if (r.title.isBlank()) {
                        emptyList()
                    } else {
                        quietly { queryItems(connection, "Series", mapOf("SearchTerm" to r.title, "Limit" to "25")) }
                    }
                }
            providerQueries(r.imdbId, r.tmdbId, r.tvdbId)
                .distinctBy { if (connection.serverKind == ServerKind.PLEX) it.lowercase(Locale.US) else it }
                .map { id ->
                    async {
                        quietly { queryItems(connection, "Series", mapOf("AnyProviderIdEquals" to id, "Limit" to "10")) }
                    }
                }.awaitAll()
                .flatten()
                .forEach { candidates[it.id] = it }
            byTitle.await().forEach { candidates.putIfAbsent(it.id, it) }
        }
        // Separate HD/UHD libraries can hold the same show under different series ids.
        return matching(candidates.values, r.copy(year = null))
    }

    private suspend fun episodesFor(
        connection: ServerConnection,
        r: PlayRequest,
        series: List<Item>,
        season: Int,
        episode: Int,
    ): List<Item> {
        val episodes =
            coroutineScope {
                series
                    .map { async { quietly { episodesOf(connection, it.id, season, episode) } } }
                    .awaitAll()
                    .flatten()
                    .distinctBy { it.id }
            }
        if (episodes.isNotEmpty()) return episodes
        val bySearch =
            quietly {
                queryItems(
                    connection,
                    "Episode",
                    mapOf(
                        "SearchTerm" to r.title,
                        "ParentIndexNumber" to season.toString(),
                        "IndexNumber" to episode.toString(),
                        "Limit" to "25",
                    ),
                ).filter { it.parentIndexNumber == season && it.indexNumber == episode }
            }
        return listOfNotNull(bySearch.maxByOrNull { score(r.copy(year = null), it) })
    }

    private fun episodesOf(
        connection: ServerConnection,
        seriesId: String,
        season: Int,
        episode: Int,
    ): List<Item> {
        if (connection.serverKind == ServerKind.PLEX) {
            return getJson(
                buildUrl(connection.serverUrl, "/library/metadata/$seriesId/allLeaves", mapOf("includeGuids" to "1")),
                connection,
            ).plexItems()
                .filter { it.parentIndexNumber == season && it.indexNumber == episode }
        }
        val direct =
            getJson(
                buildUrl(
                    connection.serverUrl,
                    "/Shows/$seriesId/Episodes",
                    mapOf("UserId" to connection.userId, "Season" to season.toString(), "Fields" to ITEM_FIELDS),
                ),
                connection,
            ).embyItems()
                .filter { it.parentIndexNumber == season && it.indexNumber == episode }
        if (direct.isNotEmpty()) return direct
        return queryItems(
            connection,
            "Episode",
            mapOf(
                "SeriesId" to seriesId,
                "ParentIndexNumber" to season.toString(),
                "IndexNumber" to episode.toString(),
                "Limit" to "10",
            ),
        ).filter { it.parentIndexNumber == season && it.indexNumber == episode }
    }

    private fun score(
        r: PlayRequest,
        item: Item,
    ): Int = Matcher.score(r.title, r.year, r.imdbId, r.tmdbId, r.tvdbId, item.info())

    private fun matching(
        candidates: Collection<Item>,
        r: PlayRequest,
    ): List<Item> {
        val scored = candidates.map { it to score(r, it) }.filter { Matcher.isAcceptable(it.second) }
        val best = scored.maxOfOrNull { it.second } ?: return emptyList()
        return scored
            .filter { (item, s) -> s >= 900 || s == best || Matcher.isLikelySameVersion(r.title, r.year, item.info()) }
            .sortedByDescending { it.second }
            .map { it.first }
            .distinctBy { it.id }
    }

    private fun providerQueries(
        imdbId: String?,
        tmdbId: Int?,
        tvdbId: Int?,
    ): List<String> =
        buildList {
            imdbId?.trim()?.takeIf { it.isNotBlank() }?.let {
                add("imdb.$it")
                add("Imdb.$it")
            }
            tmdbId?.takeIf { it > 0 }?.let {
                add("tmdb.$it")
                add("Tmdb.$it")
            }
            tvdbId?.takeIf { it > 0 }?.let {
                add("tvdb.$it")
                add("Tvdb.$it")
            }
        }.distinct()

    private fun queryItems(
        connection: ServerConnection,
        itemTypes: String,
        query: Map<String, String?>,
    ): List<Item> {
        if (connection.serverKind == ServerKind.PLEX) return queryPlex(connection, itemTypes, query)
        return getJson(
            buildUrl(
                connection.serverUrl,
                "/Users/${connection.userId}/Items",
                mapOf("Recursive" to "true", "IncludeItemTypes" to itemTypes, "Fields" to ITEM_FIELDS) + query,
            ),
            connection,
        ).embyItems()
    }

    private fun queryPlex(
        connection: ServerConnection,
        itemTypes: String,
        query: Map<String, String?>,
    ): List<Item> {
        val plexType =
            when (itemTypes.lowercase(Locale.US)) {
                "movie" -> "1"
                "series" -> "2"
                "episode" -> "4"
                else -> null
            }
        val limit = query["Limit"]?.takeIf { it.isNotBlank() } ?: "25"
        val sections = plexSections(connection, itemTypes)
        query["AnyProviderIdEquals"]?.takeIf { it.isNotBlank() }?.let { providerId ->
            val provider = providerId.substringBefore('.').lowercase(Locale.US)
            val id = providerId.substringAfter('.', "").trim()
            if (provider in setOf("imdb", "tmdb", "tvdb") && id.isNotBlank()) {
                val guid = "$provider://$id"
                val found =
                    sections
                        .flatMap { section ->
                            quietly {
                                getJson(
                                    buildUrl(
                                        connection.serverUrl,
                                        "/library/sections/${section.id}/all",
                                        mapOf("type" to plexType, "guid" to guid, "includeGuids" to "1", "limit" to limit),
                                    ),
                                    connection,
                                ).plexItems()
                            }
                        }.distinctBy { it.id }
                if (found.isNotEmpty()) return found
            }
            // Shared servers may reject guid filters; the caller searches by title next.
        }

        val term = query["SearchTerm"]?.takeIf { it.isNotBlank() } ?: return emptyList()
        val global =
            quietly {
                getJson(
                    buildUrl(
                        connection.serverUrl,
                        "/search",
                        mapOf("query" to term, "type" to plexType, "includeGuids" to "1", "limit" to limit),
                    ),
                    connection,
                ).plexItems()
            }.filter { item -> sections.isEmpty() || item.librarySectionId.isBlank() || sections.any { it.id == item.librarySectionId } }
        val exact = global.any { Matcher.normalizeTitle(it.name) == Matcher.normalizeTitle(term) }
        val perSection =
            if (exact) {
                emptyList()
            } else {
                sections.flatMap { section ->
                    quietly {
                        getJson(
                            buildUrl(
                                connection.serverUrl,
                                "/library/sections/${section.id}/all",
                                mapOf("type" to plexType, "title" to term, "includeGuids" to "1", "limit" to limit),
                            ),
                            connection,
                        ).plexItems()
                    }
                }
            }
        val season = query["ParentIndexNumber"]?.toIntOrNull()
        val episode = query["IndexNumber"]?.toIntOrNull()
        return (global + perSection)
            .filter { (season == null || it.parentIndexNumber == season) && (episode == null || it.indexNumber == episode) }
            .distinctBy { it.id }
    }

    private fun plexSections(
        connection: ServerConnection,
        itemTypes: String,
    ): List<ServerCollection> {
        val type = itemTypes.lowercase(Locale.US)
        return connection.collections.filter { it.enabled }.filter {
            val t = it.type.lowercase(Locale.US)
            when (type) {
                "movie" -> t in setOf("movies", "movie")
                "series", "episode" -> t in setOf("tvshows", "series", "show")
                else -> true
            }
        }
    }

    private fun buildSources(
        connection: ServerConnection,
        item: Item,
    ): List<ExternalSource> {
        val media =
            if (connection.serverKind == ServerKind.PLEX) {
                val fresh =
                    quietly {
                        getJson(
                            buildUrl(
                                connection.serverUrl,
                                "/library/metadata/${item.id}",
                                mapOf("includeGuids" to "1", "includeMedia" to "1"),
                            ),
                            connection,
                        ).plexItems()
                            .firstOrNull()
                            ?.media
                            .orEmpty()
                    }
                (fresh + item.media).distinctBy { it.identityKey() }
            } else {
                val fromPlaybackInfo =
                    quietly {
                        postJson(
                            buildUrl(
                                connection.serverUrl,
                                "/Items/${item.id}/PlaybackInfo",
                                mapOf(
                                    "UserId" to connection.userId,
                                    "StartTimeTicks" to "0",
                                    "IsPlayback" to "true",
                                    "AutoOpenLiveStream" to "true",
                                    "MaxStreamingBitrate" to "2147483647",
                                ),
                            ),
                            JsonObject(emptyMap()),
                            connection,
                        ).objects("MediaSources").map { it.toEmbyMedia() }
                    }
                (fromPlaybackInfo + item.media).distinctBy { it.identityKey() }
            }
        return media.flatMap { m ->
            val direct = m.playbackUrl(connection, item.id) ?: return@flatMap emptyList()
            val directSource = m.toSource(connection, direct, compatible = false).copy(itemId = item.id, mediaSourceId = m.id, runTimeTicks = m.runTimeTicks)
            if (connection.serverKind != ServerKind.PLEX || !m.needsPlexCompatible()) return@flatMap listOf(directSource)
            val compatible = m.plexCompatibleUrl(connection, item.id) ?: return@flatMap listOf(directSource)
            listOf(directSource, m.toSource(connection, compatible, compatible = true).copy(itemId = item.id, mediaSourceId = m.id, runTimeTicks = m.runTimeTicks))
        }
    }

    private fun Media.toSource(
        connection: ServerConnection,
        url: String,
        compatible: Boolean,
    ): ExternalSource {
        val quality = qualityLabel(videoHeight, videoWidth, name)
        return ExternalSource(
            connectionId = connection.connectionId,
            serverLabel = connection.label,
            serverKind = connection.serverKind,
            url = url,
            quality = quality.ifBlank { "?" },
            qualityRank = qualityRank(quality),
            videoCodec = Labels.videoCodec(videoCodec),
            hdr = hdr.ifBlank { Labels.hdrFromName(name) },
            audio = audio,
            container = container.substringBefore(',').uppercase(Locale.US),
            sizeBytes = sizeBytes,
            fileName = name,
            compatible = compatible,
        )
    }

    /**
     * Direct play first. Using the server's TranscodingUrl whenever one is offered would make
     * the server re-encode files the TV plays natively.
     */
    private fun Media.playbackUrl(
        connection: ServerConnection,
        itemId: String,
    ): String? {
        if (connection.serverKind == ServerKind.PLEX) {
            key.takeIf { it.isNotBlank() }?.let { return withPlexToken(connection, absoluteUrl(connection.serverUrl, it)) }
            path.takeIf { it.startsWith("http://", true) || it.startsWith("https://", true) }?.let {
                return withPlexToken(connection, it)
            }
            return id.takeIf { it.isNotBlank() }?.let {
                buildUrl(connection.serverUrl, "/library/parts/$it/file", mapOf("X-Plex-Token" to connection.accessToken))
            }
        }
        if (isRemote) path.takeIf { it.startsWith("http://", true) || it.startsWith("https://", true) }?.let { return it }
        val ext =
            container
                .split(',')
                .first()
                .trim()
                .lowercase(Locale.US)
                .replace("matroska", "mkv")
                .replace(Regex("[^a-z0-9]"), "")
                .takeIf { it.isNotBlank() && it.length <= 5 }
        return buildUrl(
            connection.serverUrl,
            if (ext != null) "/Videos/$itemId/stream.$ext" else "/Videos/$itemId/stream",
            mapOf(
                "Static" to "true",
                "MediaSourceId" to id,
                "DeviceId" to deviceId,
                "api_key" to connection.accessToken,
                "Tag" to eTag.takeIf { it.isNotBlank() },
            ),
        )
    }

    private fun withPlexToken(
        connection: ServerConnection,
        raw: String,
    ): String {
        val parsed = raw.toHttpUrlOrNull() ?: return raw
        if (!parsed.queryParameter("X-Plex-Token").isNullOrBlank()) return raw
        return parsed
            .newBuilder()
            .addQueryParameter("X-Plex-Token", connection.accessToken)
            .build()
            .toString()
    }

    /** MKV with HEVC Main10 or HE-AAC: ExoPlayer can stall on these, so offer Plex's HLS path too. */
    private fun Media.needsPlexCompatible(): Boolean {
        val isMkv = container.lowercase(Locale.US) in setOf("mkv", "matroska")
        val isHevc = videoCodec.lowercase(Locale.US) in setOf("hevc", "h265", "h.265")
        val isMain10 = videoBitDepth >= 10 || "main 10" in videoProfile.lowercase(Locale.US) || "main10" in videoProfile.lowercase(Locale.US)
        val ap = audioProfile.lowercase(Locale.US)
        val isHeAac = audioCodec.lowercase(Locale.US) == "aac" && ("he" in ap || "sbr" in ap)
        return isMkv && ((isHevc && isMain10) || isHeAac)
    }

    private fun Media.plexCompatibleUrl(
        connection: ServerConnection,
        itemId: String,
    ): String? {
        if (itemId.isBlank()) return null
        val base = connection.serverUrl.toHttpUrlOrNull() ?: return null
        return base
            .newBuilder()
            .encodedPath("/video/:/transcode/universal/start.m3u8")
            .addQueryParameter("path", "/library/metadata/$itemId")
            .addQueryParameter("mediaIndex", mediaIndex.toString())
            .addQueryParameter("partIndex", partIndex.toString())
            .addQueryParameter("protocol", "hls")
            .addQueryParameter("directPlay", "0")
            .addQueryParameter("directStream", "1")
            .addQueryParameter("videoQuality", "100")
            .addQueryParameter("maxVideoBitrate", "40000")
            .addQueryParameter("session", "$clientName-$deviceId-$itemId-${id.ifBlank { partIndex.toString() }}")
            .addQueryParameter("X-Plex-Client-Identifier", deviceId)
            .addQueryParameter("X-Plex-Product", clientName)
            .addQueryParameter("X-Plex-Token", connection.accessToken)
            .build()
            .toString()
    }

    // ---------------------------------------------------------------- parsing

    private class Item(
        val id: String,
        val name: String,
        val productionYear: Int?,
        val providerIds: Map<String, String>,
        val librarySectionId: String,
        val indexNumber: Int?,
        val parentIndexNumber: Int?,
        val media: List<Media>,
    ) {
        fun info() = CandidateInfo(name, productionYear, providerIds)
    }

    private class Media(
        val id: String,
        val key: String,
        val name: String,
        val path: String,
        val container: String,
        val eTag: String,
        val sizeBytes: Long,
        val isRemote: Boolean,
        val videoWidth: Int,
        val videoHeight: Int,
        val videoCodec: String,
        val videoProfile: String,
        val videoBitDepth: Int,
        val hdr: String,
        val audioCodec: String,
        val audioProfile: String,
        val audio: String,
        val variantKey: String = "",
        val runTimeTicks: Long = 0L,
        val mediaIndex: Int = 0,
        val partIndex: Int = 0,
    ) {
        fun identityKey(): String =
            variantKey.ifBlank { null } ?: id.ifBlank { null } ?: key.ifBlank { null } ?: path.ifBlank { null }
                ?: "$container|$sizeBytes|$videoWidth|$videoHeight"
    }

    private fun JsonObject.embyItems(): List<Item> = objects("Items").map { it.toEmbyItem() }

    private fun JsonObject.toEmbyItem(): Item =
        Item(
            id = string("Id"),
            name = string("Name"),
            productionYear = int("ProductionYear") ?: string("PremiereDate").take(4).toIntOrNull(),
            providerIds =
                obj("ProviderIds")
                    ?.mapNotNull { (k, v) -> (v as? kotlinx.serialization.json.JsonPrimitive)?.content?.let { k.lowercase(Locale.US) to it } }
                    ?.toMap()
                    .orEmpty(),
            librarySectionId = "",
            indexNumber = int("IndexNumber"),
            parentIndexNumber = int("ParentIndexNumber"),
            media = objects("MediaSources").map { it.toEmbyMedia() },
        )

    private fun JsonObject.toEmbyMedia(): Media {
        val streams = objects("MediaStreams")
        val video = streams.firstOrNull { it.string("Type").equals("Video", true) }
        val defaultAudio =
            streams.filter { it.string("Type").equals("Audio", true) }.let { audio ->
                audio.firstOrNull { it.boolean("IsDefault") == true } ?: audio.firstOrNull()
            }
        val rangeType = video?.string("VideoRangeType").orEmpty()
        val range = video?.string("VideoRange").orEmpty()
        val hdr =
            when {
                rangeType.startsWith("DOVI", true) || video?.string("DvProfile")?.isNotBlank() == true ||
                    video?.string("ExtendedVideoType")?.contains("DolbyVision", true) == true -> "Dolby Vision"
                rangeType.equals("HDR10Plus", true) || video?.string("ExtendedVideoSubType")?.contains("Plus", true) == true -> "HDR10+"
                rangeType.equals("HLG", true) -> "HLG"
                rangeType.startsWith("HDR", true) || range.equals("HDR", true) -> "HDR10"
                else -> ""
            }
        return Media(
            id = string("Id"),
            key = "",
            name = string("Name").ifBlank { string("Path").substringAfterLast('/').substringAfterLast('\\') },
            path = string("Path"),
            container = string("Container"),
            eTag = string("ETag").ifBlank { string("Etag") },
            sizeBytes = long("Size") ?: 0L,
            isRemote = boolean("IsRemote") == true,
            runTimeTicks = long("RunTimeTicks") ?: 0L,
            videoWidth = video?.int("Width") ?: 0,
            videoHeight = video?.int("Height") ?: 0,
            videoCodec = video?.string("Codec").orEmpty(),
            videoProfile = video?.string("Profile").orEmpty(),
            videoBitDepth = video?.int("BitDepth") ?: 0,
            hdr = hdr,
            audioCodec = defaultAudio?.string("Codec").orEmpty(),
            audioProfile = defaultAudio?.string("Profile").orEmpty(),
            audio =
                defaultAudio
                    ?.let {
                        Labels.audio(it.string("Codec"), it.string("Profile"), it.int("Channels"), it.string("DisplayTitle"), string("Path").ifBlank { string("Name") })
                    }.orEmpty(),
        )
    }

    private fun JsonObject.plexItems(): List<Item> =
        (array("MediaContainer", "Metadata").ifEmpty { array("Metadata") })
            .filterIsInstance<JsonObject>()
            .map { it.toPlexItem() }

    private fun JsonObject.toPlexItem(): Item {
        val providerIds =
            objects("Guid")
                .map { it.string("id") }
                .mapNotNull { guid ->
                    val provider = guid.substringBefore("://").lowercase(Locale.US)
                    val id = guid.substringAfter("://", "").substringBefore("?")
                    if (provider.isNotBlank() && id.isNotBlank()) provider to id else null
                }.toMap()
        return Item(
            id = string("ratingKey").ifBlank { string("key") },
            name = string("title"),
            productionYear = int("year") ?: string("originallyAvailableAt").take(4).toIntOrNull(),
            providerIds = providerIds,
            librarySectionId = string("librarySectionID"),
            indexNumber = int("index"),
            parentIndexNumber = int("parentIndex"),
            media =
                objects("Media").flatMapIndexed { mediaIndex, media ->
                    media.objects("Part").mapIndexed { partIndex, part -> part.toPlexMedia(media, mediaIndex, partIndex) }
                },
        )
    }

    private fun JsonObject.toPlexMedia(
        parent: JsonObject,
        mediaIndex: Int,
        partIndex: Int,
    ): Media {
        val width = parent.int("width") ?: int("width") ?: 0
        val height = parent.int("height") ?: int("height") ?: 0
        val streams = objects("Stream")
        val video = streams.firstOrNull { it.string("streamType") == "1" }
        val audio =
            streams.filter { it.string("streamType") == "2" }.let { all ->
                all.firstOrNull { it.boolean("selected") == true || it.boolean("default") == true } ?: all.firstOrNull()
            }
        val trc = video?.string("colorTrc").orEmpty()
        val hdr =
            when {
                video?.boolean("DOVIPresent") == true || video?.string("DOVIProfile")?.isNotBlank() == true -> "Dolby Vision"
                video?.string("displayTitle")?.contains("HDR10+", true) == true -> "HDR10+"
                trc.equals("arib-std-b67", true) -> "HLG"
                trc.equals("smpte2084", true) || video?.string("displayTitle")?.contains("HDR", true) == true -> "HDR10"
                else -> ""
            }
        val audioCodec = audio?.string("codec").orEmpty().ifBlank { parent.string("audioCodec") }
        return Media(
            id = string("id"),
            key = string("key"),
            name = string("file").substringAfterLast('/').substringAfterLast('\\').ifBlank { parent.string("title") },
            path = string("file"),
            container = parent.string("container").ifBlank { string("container") },
            eTag = "",
            sizeBytes = long("size") ?: 0L,
            isRemote = false,
            videoWidth = width,
            videoHeight = height,
            videoCodec = video?.string("codec").orEmpty().ifBlank { parent.string("videoCodec") },
            videoProfile = video?.string("profile").orEmpty().ifBlank { parent.string("videoProfile") },
            videoBitDepth = video?.int("bitDepth") ?: parent.int("bitDepth") ?: 0,
            hdr = hdr,
            audioCodec = audioCodec,
            audioProfile = audio?.string("profile").orEmpty(),
            audio =
                Labels.audio(
                    audioCodec,
                    audio?.string("profile").orEmpty(),
                    audio?.int("channels") ?: parent.int("audioChannels"),
                    audio?.string("displayTitle").orEmpty() + " " + audio?.string("title").orEmpty(),
                    string("file"),
                ),
            variantKey =
                listOf(parent.string("id"), parent.string("bitrate"), string("id"), string("key"), string("file"), string("size"))
                    .filter { it.isNotBlank() }
                    .joinToString("|"),
            runTimeTicks = (parent.long("duration") ?: long("duration") ?: 0L) * 10_000L,
            mediaIndex = mediaIndex,
            partIndex = partIndex,
        )
    }

    // ---------------------------------------------------------------- HTTP

    private fun authHeader(token: String?): String {
        val base = "MediaBrowser Client=\"$clientName\", Device=\"Android TV\", DeviceId=\"$deviceId\", Version=\"$clientVersion\""
        return if (token.isNullOrBlank()) base else "$base, Token=\"$token\""
    }

    private fun plexHeaders(token: String?): Map<String, String> =
        buildMap {
            put("Accept", "application/json")
            put("X-Plex-Client-Identifier", deviceId)
            put("X-Plex-Product", clientName)
            put("X-Plex-Version", clientVersion)
            put("X-Plex-Device", "Android TV")
            put("X-Plex-Platform", "Android")
            if (!token.isNullOrBlank()) put("X-Plex-Token", token)
        }

    private fun request(
        url: String,
        connection: ServerConnection?,
    ): Request.Builder {
        val builder =
            Request
                .Builder()
                .url(url)
                .header("Accept", "application/json")
                .header("User-Agent", "$clientName/$clientVersion")
        if (connection?.serverKind == ServerKind.PLEX) {
            plexHeaders(connection.accessToken).forEach { (k, v) -> builder.header(k, v) }
        } else {
            builder.header("Authorization", authHeader(connection?.accessToken))
            builder.header("X-Emby-Authorization", authHeader(connection?.accessToken))
            connection?.accessToken?.takeIf { it.isNotBlank() }?.let { builder.header("X-Emby-Token", it) }
        }
        return builder
    }

    private fun execute(request: Request): String =
        http.newCall(request).execute().use { response ->
            val body = response.body.string()
            if (!response.isSuccessful) {
                val hint =
                    when (response.code) {
                        401 -> " (wrong username/password or expired token)"
                        404, 405 -> " (not a Plex/Emby/Jellyfin API address)"
                        else -> ""
                    }
                throw ServerRequestException(response.code, "Server answered HTTP ${response.code}$hint")
            }
            body
        }

    private fun getJson(
        url: String,
        connection: ServerConnection? = null,
    ): JsonObject = json.parseToJsonElement(execute(request(url, connection).get().build()).ifBlank { "{}" }) as? JsonObject ?: JsonObject(emptyMap())

    private fun getText(
        url: String,
        connection: ServerConnection? = null,
    ): String = execute(request(url, connection).get().build())

    private fun postJson(
        url: String,
        body: JsonObject,
        connection: ServerConnection? = null,
    ): JsonObject {
        val req =
            request(url, connection)
                .post(body.toString().toRequestBody(jsonType))
                .build()
        return json.parseToJsonElement(execute(req).ifBlank { "{}" }) as? JsonObject ?: JsonObject(emptyMap())
    }

    private fun buildUrl(
        baseUrl: String,
        path: String,
        query: Map<String, String?> = emptyMap(),
    ): String {
        val builder = (baseUrl.toHttpUrlOrNull() ?: error("Invalid server address")).newBuilder()
        path.trim('/').split('/').filter { it.isNotBlank() }.forEach { builder.addPathSegment(it) }
        query.forEach { (k, v) -> if (!v.isNullOrBlank()) builder.addQueryParameter(k, v) }
        return builder.build().toString()
    }

    private fun absoluteUrl(
        baseUrl: String,
        pathOrUrl: String,
    ): String {
        if (pathOrUrl.startsWith("http://", true) || pathOrUrl.startsWith("https://", true)) return pathOrUrl
        val builder = baseUrl.toHttpUrlOrNull()?.newBuilder() ?: return pathOrUrl
        pathOrUrl.substringBefore('?').trim('/').split('/').filter { it.isNotBlank() }.forEach { builder.addPathSegment(it) }
        pathOrUrl.substringAfter('?', "").split('&').filter { it.isNotBlank() }.forEach { part ->
            builder.addEncodedQueryParameter(part.substringBefore('='), part.substringAfter('=', ""))
        }
        return builder.build().toString()
    }

    // ---------------------------------------------------------------- Plex account

    private class PlexAddress(
        val uri: String,
        val local: Boolean,
        val relay: Boolean,
    )

    private class PlexDevice(
        val name: String,
        val clientIdentifier: String,
        val accessToken: String,
        val owned: Boolean,
        val connections: List<PlexAddress>,
    )

    private fun plexAccountName(token: String): String =
        runCatching {
            val req =
                Request
                    .Builder()
                    .url("https://plex.tv/api/v2/user")
                    .header("Accept", "application/json")
                    .header("X-Plex-Token", token)
                    .header("X-Plex-Client-Identifier", deviceId)
                    .build()
            val obj = json.parseToJsonElement(execute(req)) as JsonObject
            obj.string("friendlyName").ifBlank { obj.string("username") }.ifBlank { obj.string("title") }
        }.getOrDefault("").ifBlank { "Plex account" }

    private fun fetchPlexResources(token: String): List<PlexDevice> {
        val url =
            "https://plex.tv/api/v2/resources"
                .toHttpUrlOrNull()!!
                .newBuilder()
                .addQueryParameter("includeHttps", "1")
                .addQueryParameter("includeRelay", "1")
                .build()
        val req =
            Request
                .Builder()
                .url(url)
                .apply { plexHeaders(token).forEach { (k, v) -> header(k, v) } }
                .build()
        val body = runCatching { execute(req) }.getOrNull() ?: return emptyList()
        val arr = runCatching { json.parseToJsonElement(body) as kotlinx.serialization.json.JsonArray }.getOrNull() ?: return emptyList()
        return arr
            .filterIsInstance<JsonObject>()
            .filter { d -> d.string("provides").split(',').any { it.trim() == "server" } }
            .map { d ->
                PlexDevice(
                    name = d.string("name"),
                    clientIdentifier = d.string("clientIdentifier"),
                    accessToken = d.string("accessToken"),
                    owned = d.boolean("owned") == true,
                    connections =
                        d
                            .objects("connections")
                            .map { PlexAddress(normalizeServerUrl(it.string("uri")), it.boolean("local") == true, it.boolean("relay") == true) }
                            .filter { it.uri.isNotBlank() }
                            .distinctBy { it.uri.lowercase(Locale.US) },
                )
            }.filter { it.clientIdentifier.isNotBlank() }
    }

    private fun selectPlexDevice(
        devices: List<PlexDevice>,
        preferredId: String,
        preferredUrl: String,
    ): PlexDevice? {
        if (devices.isEmpty()) return null
        if (preferredId.isNotBlank()) devices.firstOrNull { it.clientIdentifier == preferredId }?.let { return it }
        if (preferredUrl.isNotBlank()) {
            devices.firstOrNull { d -> d.connections.any { sameEndpoint(it.uri, preferredUrl) } }?.let { return it }
        }
        return devices
            .filter { it.accessToken.isNotBlank() }
            .sortedWith(compareByDescending<PlexDevice> { it.owned }.thenByDescending { it.connections.isNotEmpty() })
            .firstOrNull()
    }

    private fun parsePlexIdentity(body: String): Pair<String, String> {
        val container =
            runCatching {
                val obj = json.parseToJsonElement(body) as JsonObject
                obj.obj("MediaContainer") ?: obj
            }.getOrNull()
        val name = container?.string("friendlyName").orEmpty()
        val id = container?.string("machineIdentifier").orEmpty()
        if (name.isNotBlank() || id.isNotBlank()) return name to id
        fun attr(n: String) =
            Regex("\\b$n=[\"']([^\"']*)[\"']")
                .find(body)
                ?.groupValues
                ?.get(1)
                .orEmpty()
        return attr("friendlyName") to attr("machineIdentifier")
    }

    private inline fun <T> quietly(block: () -> List<T>): List<T> =
        try {
            block()
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            emptyList()
        }

    // ---------------------------------------------------------------- watch state

    fun currentUserId(connection: ServerConnection): String = getJson(buildUrl(connection.serverUrl, "/Users/Me"), connection).string("Id")

    /** Items started or finished on an Emby/Jellyfin server after [since], newest first. */
    fun recentWatchActivity(
        connection: ServerConnection,
        since: java.time.Instant,
        limit: Int,
    ): List<WatchEntry> {
        if (connection.serverKind == ServerKind.PLEX) return emptyList()
        val base = "/Users/${connection.userId}/Items"
        val fields = "ProviderIds,ProductionYear"
        val resume =
            getJson(
                buildUrl(connection.serverUrl, "$base/Resume", mapOf("Limit" to "$limit", "MediaTypes" to "Video", "Fields" to fields, "EnableUserData" to "true")),
                connection,
            ).objects("Items")
        val played =
            getJson(
                buildUrl(
                    connection.serverUrl,
                    base,
                    mapOf(
                        "Recursive" to "true",
                        "Filters" to "IsPlayed",
                        "IncludeItemTypes" to "Movie,Episode",
                        "SortBy" to "DatePlayed",
                        "SortOrder" to "Descending",
                        "Limit" to "$limit",
                        "Fields" to fields,
                        "EnableUserData" to "true",
                    ),
                ),
                connection,
            ).objects("Items")
        val seriesCache = mutableMapOf<String, JsonObject?>()
        return (resume + played)
            .mapNotNull { item ->
                val data = item.obj("UserData") ?: return@mapNotNull null
                val lastPlayed = parseInstant(data.string("LastPlayedDate")) ?: return@mapNotNull null
                if (!lastPlayed.isAfter(since)) return@mapNotNull null
                val request =
                    if (item.string("Type").equals("Episode", true)) {
                        val seriesId = item.string("SeriesId").ifBlank { return@mapNotNull null }
                        val series =
                            seriesCache.getOrPut(seriesId) {
                                runCatching { getJson(buildUrl(connection.serverUrl, "$base/$seriesId", mapOf("Fields" to fields)), connection) }.getOrNull()
                            } ?: return@mapNotNull null
                        val ids = series.providerIds()
                        PlayRequest(
                            title = series.string("Name"),
                            year = series.int("ProductionYear"),
                            imdbId = ids["imdb"],
                            tmdbId = ids["tmdb"]?.toIntOrNull(),
                            tvdbId = ids["tvdb"]?.toIntOrNull(),
                            season = item.int("ParentIndexNumber") ?: return@mapNotNull null,
                            episode = item.int("IndexNumber") ?: return@mapNotNull null,
                        )
                    } else {
                        val ids = item.providerIds()
                        PlayRequest(item.string("Name"), item.int("ProductionYear"), ids["imdb"], ids["tmdb"]?.toIntOrNull(), null)
                    }
                WatchEntry(
                    itemId = item.string("Id"),
                    request = request,
                    positionTicks = data.long("PlaybackPositionTicks") ?: 0L,
                    played = data.boolean("Played") == true,
                    lastPlayed = lastPlayed,
                )
            }.distinctBy { it.itemId }
            .sortedByDescending { it.lastPlayed }
    }

    private fun JsonObject.providerIds(): Map<String, String> =
        obj("ProviderIds")
            ?.mapNotNull { (k, v) -> (v as? kotlinx.serialization.json.JsonPrimitive)?.content?.let { k.lowercase(Locale.US) to it } }
            ?.toMap()
            .orEmpty()

    /** Ids of the series matching [request] (title/year/ids; season and episode are ignored). */
    suspend fun matchSeriesIds(
        connection: ServerConnection,
        request: PlayRequest,
    ): List<String> = seriesMatches(connection, request).map { it.id }

    /** Ids of every copy of [request] on [connection] (HD and 4K libraries can both hold it). */
    suspend fun matchItemIds(
        connection: ServerConnection,
        request: PlayRequest,
    ): List<String> = (if (request.isEpisode) findEpisodes(connection, request) else findMovies(connection, request)).map { it.id }

    fun userData(
        connection: ServerConnection,
        itemId: String,
    ): UserItemData? {
        val item =
            withFallback(
                { getJson(buildUrl(connection.serverUrl, "/Items/$itemId", mapOf("userId" to connection.userId)), connection) },
                { getJson(buildUrl(connection.serverUrl, "/Users/${connection.userId}/Items/$itemId"), connection) },
                newFirst = connection.serverKind == ServerKind.JELLYFIN,
            )
        val data = item.obj("UserData") ?: return null
        return UserItemData(
            data.long("PlaybackPositionTicks") ?: 0L,
            data.boolean("Played") == true,
            parseInstant(data.string("LastPlayedDate")),
            item.string("SeriesId").ifBlank { null },
        )
    }

    /** Tell the server a stream from it is playing, so its own Continue Watching stays current. */
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
        if (connection.serverKind == ServerKind.PLEX) {
            val state =
                when {
                    event == PlayEvent.STOP -> "stopped"
                    paused -> "paused"
                    else -> "playing"
                }
            getText(
                buildUrl(
                    connection.serverUrl,
                    "/:/timeline",
                    mapOf(
                        "ratingKey" to source.itemId,
                        "key" to "/library/metadata/${source.itemId}",
                        "state" to state,
                        "time" to "$positionMs",
                        "duration" to "$durationMs",
                    ),
                ),
                connection,
            )
            return
        }
        val path =
            when (event) {
                PlayEvent.START -> "/Sessions/Playing"
                PlayEvent.PROGRESS -> "/Sessions/Playing/Progress"
                PlayEvent.STOP -> "/Sessions/Playing/Stopped"
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
        postJson(buildUrl(connection.serverUrl, path), body, connection)
    }

    /** Jellyfin 10.9 moved user-data routes; Emby and older Jellyfin keep the /Users/{id}/ ones. */
    private fun <T> withFallback(
        new: () -> T,
        old: () -> T,
        newFirst: Boolean,
    ): T {
        val (first, second) = if (newFirst) new to old else old to new
        return try {
            first()
        } catch (e: ServerRequestException) {
            if (e.statusCode == 404 || e.statusCode == 405) second() else throw e
        }
    }

    private fun parseInstant(value: String): java.time.Instant? =
        value.takeIf { it.isNotBlank() }?.let {
            runCatching { java.time.OffsetDateTime.parse(it).toInstant() }.getOrNull()
                ?: runCatching { java.time.Instant.parse(it) }.getOrNull()
        }

    companion object {
        private const val ITEM_FIELDS = "ProviderIds,MediaSources,MediaStreams,Path,PremiereDate,ProductionYear"

        fun connectionId(
            serverUrl: String,
            kind: ServerKind,
            user: String,
        ): String =
            "${kind.name}:${serverUrl.trimEnd('/').lowercase(Locale.US)}:${user.lowercase(Locale.US)}"
                .replace(Regex("[^a-z0-9:._-]+", RegexOption.IGNORE_CASE), "_")
    }
}
