package com.wholphinplus.sources

import android.annotation.SuppressLint
import android.content.Context
import android.provider.Settings
import com.wholphinplus.sources.core.ExternalSource
import com.wholphinplus.sources.core.Labels
import com.wholphinplus.sources.core.PlayRequest
import com.wholphinplus.sources.core.ServerClient
import com.wholphinplus.sources.core.ServerConnection
import com.wholphinplus.sources.core.ServerKind
import com.wholphinplus.sources.core.normalizeServerUrl
import com.wholphinplus.sources.core.qualityLabel
import com.wholphinplus.sources.core.closestCopy
import com.wholphinplus.sources.core.qualityRank
import com.wholphinplus.sources.core.sameServer
import com.wholphinplus.sources.core.tunedRanking
import com.wholphinplus.sources.core.withoutRepeats
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.api.client.extensions.userLibraryApi
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.BaseItemKind
import org.jellyfin.sdk.model.api.MediaStreamType
import org.jellyfin.sdk.model.api.VideoRangeType
import org.jellyfin.sdk.api.client.extensions.systemApi
import timber.log.Timber
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The addon's single entry point into Wholphin. Wholphin's PlaybackViewModel holds one
 * [PickSession] and asks it which server to stream each item from.
 */
@Singleton
class SourceHook
    @Inject
    constructor(
        @param:ApplicationContext internal val context: Context,
        internal val jellyfin: ApiClient,
        val store: ConnectionStore,
        internal val overlay: ProgressOverlay,
        val collections: HomeCollections,
        val profileSync: com.wholphinplus.sources.sync.ProfileSync,
    ) {
        init {
            // Cinema mode gives memory back when Android asks (see MemoryTrim)
            context.registerComponentCallbacks(com.wholphinplus.sources.cinema.MemoryTrim(context))
            // Leaving the app (Home button, sleep): upload what changed to the cloud profile
            context.registerComponentCallbacks(
                object : android.content.ComponentCallbacks2 {
                    override fun onTrimMemory(level: Int) {
                        if (level == android.content.ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN) profileSync.syncSoon(this@SourceHook, force = true)
                    }

                    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {}

                    @Deprecated("Deprecated in Java")
                    override fun onLowMemory() {}
                },
            )
        }

        @Volatile private var serverIdFor: Pair<String, String>? = null

        /** The main Jellyfin server's own id (the cloud profile is keyed by it), remembered per address. */
        internal suspend fun mainServerId(): String? {
            val url = jellyfin.baseUrl ?: return null
            serverIdFor?.takeIf { it.first == url }?.let { return it.second }
            return kotlinx.coroutines.withContext(Dispatchers.IO) {
                runCatching { jellyfin.systemApi.getPublicSystemInfo().content.id }
                    .onFailure { Timber.w(it, "Cannot read the main server's id") }
                    .getOrNull()
                    ?.takeIf { it.isNotBlank() }
                    ?.also { serverIdFor = url to it }
            }
        }

        val client: ServerClient by lazy {
            ServerClient(
                // Our own client: Wholphin's authenticated one would send the Jellyfin token to other servers.
                http =
                    OkHttpClient
                        .Builder()
                        .connectTimeout(8, TimeUnit.SECONDS)
                        .readTimeout(20, TimeUnit.SECONDS)
                        // A blocking call ignores coroutine timeouts: cap the whole call too, so
                        // one hung server can't hold the picker or watch sync past its budget
                        .callTimeout(25, TimeUnit.SECONDS)
                        .build(),
                deviceId = deviceId(),
                clientName = "Orca+",
                clientVersion = "1.0",
            )
        }

        private class Cached(
            val sources: List<ExternalSource>,
            val at: Long,
        )

        private val cache = ConcurrentHashMap<String, Cached>()

        /** Lookups in flight, so a picker and a search asking for the same title share one. */
        private val inflight = ConcurrentHashMap<String, kotlinx.coroutines.Deferred<List<ExternalSource>?>>()

        fun newSession(): PickSession = PickSession(this)

        private val watchSync by lazy { WatchSync(context, this, jellyfin, overlay) }

        /** Bring progress from the extra servers into the main one. Cheap to call often (throttled). */
        suspend fun syncWatchState() {
            // Home is loading: fetch the Orca+ charts and refresh lists older than 6 h in the
            // background (also with no lists yet: an install that skipped the welcome has none)
            collections.refreshStale(this)
            if (store.connections.value.any { it.isUsable }) watchSync.syncIfStale()
        }

        internal val background = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + Dispatchers.IO)

        /**
         * [syncWatchState] without waiting for it, for Cinema mode's home (which doesn't go
         * through Wholphin's Continue Watching). What it finds shows on the next refresh.
         */
        fun syncWatchStateLater() {
            background.launch { runCatching { syncWatchState() }.onFailure { Timber.w(it, "Background watch sync failed") } }
            profileSync.syncSoon(this)
        }

        /**
         * Collection rows for Wholphin's home page, in order. Wholphin turns each into a
         * "GetItems" row whose request carries [HomeCollection.tag]; [ProgressOverlay] fills it.
         */
        // Lazy: the lists are read in the background while the app starts (see HomeCollections)
        val homeRows: kotlinx.coroutines.flow.Flow<List<HomeRow>> by lazy {
            collections.lists.map { list ->
                list.filter { it.showOnHome && it.name != HomeCollections.PENDING_NAME && collections.enough(it) }.map { c ->
                    HomeRow(c.name, org.jellyfin.sdk.model.api.request.GetItemsRequest(tags = listOf(c.tag), recursive = true))
                }
            }
        }

        /** Calls [onChange] on the main thread now and whenever the collection rows change. */
        fun watchHomeRows(onChange: (List<HomeRow>) -> Unit) {
            kotlinx.coroutines.MainScope().launch { homeRows.collect { onChange(it) } }
        }

        /** Rows this addon added, so Wholphin's own saved copies of them can be dropped. */
        fun isAddonRow(request: org.jellyfin.sdk.model.api.request.GetItemsRequest): Boolean =
            request.tags.orEmpty().any { it.startsWith(HomeCollection.TAG_PREFIX) }

        @Volatile private var mainUserId: Pair<String, String>? = null

        // The main user's id for the sign-in in use, kept on the device: asked of the server on
        // every cold start, it was one more request in front of the first rows, and on a slow or
        // waking link its timeout failed the whole page as "Not signed in"
        private val mainUserPrefs by lazy { context.getSharedPreferences("wholphinplus_main_user", Context.MODE_PRIVATE) }

        private fun signInKey(token: String) = java.security.MessageDigest.getInstance("SHA-256").digest(token.toByteArray()).joinToString("") { "%02x".format(it) }

        /** Wholphin's current Jellyfin server as a [ServerConnection], for our own lookups. */
        internal fun mainConnection(): ServerConnection? {
            val url = jellyfin.baseUrl?.let(::normalizeServerUrl) ?: return null
            val token = jellyfin.accessToken ?: return null
            // An Emby main server (EmbyBridge) is asked the Emby way
            val shell = ServerConnection(serverUrl = url, serverKind = if (EmbyBridge.isEmby(url)) ServerKind.EMBY else ServerKind.JELLYFIN, accessToken = token, serverName = "main")
            val userId =
                // An Emby main server has no "who am I": the sign-in told EmbyBridge
                (if (shell.serverKind == ServerKind.EMBY) EmbyBridge.userOf(token) else null)
                    ?: mainUserId?.takeIf { it.first == token }?.second
                    ?: mainUserPrefs.getString("user_id", null)?.takeIf { mainUserPrefs.getString("sign_in", null) == signInKey(token) }
                        ?.also { mainUserId = token to it }
                    ?: runCatching { client.currentUserId(shell) }
                        .onFailure { Timber.w(it, "Watch sync: cannot read the main user") }
                        .getOrNull()
                        ?.also {
                            mainUserId = token to it
                            mainUserPrefs.edit().putString("sign_in", signInKey(token)).putString("user_id", it).apply()
                        }
                    ?: return null
            overlay.setUser(userId)
            return shell.copy(userId = userId)
        }


        internal fun connectionFor(id: String): ServerConnection? = store.connections.value.firstOrNull { it.connectionId == id }

        /** Apply a pushed import file, if any. Returns the Jellyfin login Wholphin should switch to. */
        fun takeImportedLogin(): ImportedLogin? = Importer.run(context, store, collections).also { clearCache() }

        /** Saved servers that can be searched, minus the Jellyfin server Wholphin is already using. */
        internal fun searchableConnections(): List<ServerConnection> {
            val current = jellyfin.baseUrl?.let(::normalizeServerUrl).orEmpty()
            return store.connections.value.filter { it.isUsable && (current.isBlank() || !sameServer(it.serverUrl, current)) }
        }

        internal suspend fun requestFor(item: BaseItemDto): PlayRequest? =
            withContext(Dispatchers.IO) {
                when (item.type) {
                    BaseItemKind.MOVIE, BaseItemKind.VIDEO, BaseItemKind.MUSIC_VIDEO -> {
                        PlayRequest(
                            title = item.name.orEmpty(),
                            year = item.productionYear ?: item.premiereDate?.year,
                            imdbId = item.provider("imdb"),
                            tmdbId = item.provider("tmdb")?.toIntOrNull(),
                            tvdbId = null,
                        )
                    }

                    BaseItemKind.EPISODE -> {
                        val season = item.parentIndexNumber ?: return@withContext null
                        val episode = item.indexNumber ?: return@withContext null
                        // The show's ids tell same-named shows apart (The Office UK / US): without
                        // them a lookup would take any show of that name, so a show that can't be
                        // read (twice) plays from Jellyfin only
                        val series =
                            item.seriesId?.let { id ->
                                var got: BaseItemDto? = null
                                for (attempt in 0 until 2) {
                                    got = runCatching { jellyfin.userLibraryApi.getItem(id).content }
                                        .onFailure { if (it is kotlinx.coroutines.CancellationException) throw it else Timber.w(it, "Could not load series %s", id) }
                                        .getOrNull()
                                    if (got != null) break
                                    if (attempt == 0) kotlinx.coroutines.delay(500)
                                }
                                got ?: return@withContext null
                            }
                        PlayRequest(
                            title = series?.name ?: item.seriesName.orEmpty(),
                            year = series?.productionYear,
                            imdbId = series?.provider("imdb"),
                            tmdbId = series?.provider("tmdb")?.toIntOrNull(),
                            tvdbId = series?.provider("tvdb")?.toIntOrNull(),
                            season = season,
                            episode = episode,
                        )
                    }

                    else -> {
                        null
                    }
                }
            }

        /**
         * Look up one server, with a cache so replaying or resuming is instant. Waits at most
         * [SERVER_TIMEOUT_MS]: the lookup runs on its own (blocking HTTP calls don't stop when a
         * coroutine is cancelled), so a slow or dead server never holds the caller, and a late
         * answer still lands in the cache. Only real answers are cached: a failed lookup (the
         * server down for a moment) is asked again next time, not remembered as "not here".
         */
        internal suspend fun findOn(
            connection: ServerConnection,
            request: PlayRequest,
        ): List<ExternalSource> = findChecked(connection, request).first

        /**
         * [findOn], and why it found nothing when the server is in trouble ([ServerHealth]). A
         * resting server isn't asked at all: its remembered trouble comes back at once.
         */
        internal suspend fun findChecked(
            connection: ServerConnection,
            request: PlayRequest,
        ): Pair<List<ExternalSource>, ServerHealth.Trouble?> {
            health.resting(connection)?.let { return emptyList<ExternalSource>() to it.trouble }
            val key = "${connection.connectionId}|${connection.lastConnectedAt}|$request"
            cache[key]?.let { if (System.currentTimeMillis() - it.at < CACHE_MS) return it.sources to null }
            val started = System.currentTimeMillis()
            val lookup =
                inflight.computeIfAbsent(key) {
                    background.async {
                        client.findSourcesOrNull(connection, request)?.also { cache[key] = Cached(it, System.currentTimeMillis()) }
                    }.also { d -> d.invokeOnCompletion { inflight.remove(key, d) } }
                }
            // Wrapped: a lookup that failed (null) and one still running (timeout) mean different things
            val done = withTimeoutOrNull(SERVER_TIMEOUT_MS) { listOf(lookup.await()) }
            val sources = done?.single()
            val trouble =
                when {
                    done == null -> ServerHealth.Trouble.SLOW
                    sources != null -> null
                    else ->
                        when (client.troubleSince(connection, started)) {
                            401, 403 -> ServerHealth.Trouble.SIGN_IN
                            -1 -> ServerHealth.Trouble.OFFLINE
                            else -> ServerHealth.Trouble.ERROR
                        }
                }
            if (trouble == null) health.ok(connection) else health.failed(connection, trouble)
            return sources.orEmpty() to trouble
        }

        /** How each extra server answered its last lookup (no requests of its own). */
        val health =
            ServerHealth(context).also { h ->
                val main = android.os.Handler(android.os.Looper.getMainLooper())
                h.notify = { text -> main.post { android.widget.Toast.makeText(context, text, android.widget.Toast.LENGTH_LONG).show() } }
            }

        fun clearCache() = cache.clear()

        @SuppressLint("HardwareIds")
        private fun deviceId(): String {
            val androidId = Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID).orEmpty()
            val digest = MessageDigest.getInstance("SHA-256").digest("wholphinplus:$androidId".toByteArray())
            return digest.take(12).joinToString("") { "%02x".format(it) }
        }

        companion object {
            const val JELLYFIN_ROW = "jellyfin"
            const val AUTO_PLAY_WAIT_MS = 6_000L
            private const val CACHE_MS = 15 * 60 * 1000L
            private const val SERVER_TIMEOUT_MS = 10_000L
        }
    }

/** Same copy, same key: the picker marks failed rows by it. */
// The main server's versions have no URL of their own: their media source tells them apart
fun copyKey(s: ExternalSource): String = s.connectionId + "|" + s.url.ifBlank { s.mediaSourceId }

/** How the trouble screen plays a failed title again (see [PickSession.prepareRetry]). */
sealed interface Retry {
    /** The copy that failed, once more. */
    data object Same : Retry

    /** Show the picker again (the failed copy marked and last). */
    data object Choose : Retry

    /** This copy (the countdown's pick). */
    data class Copy(
        val source: ExternalSource,
    ) : Retry
}

sealed interface Pick {
    data object Jellyfin : Pick

    /** One version of the main server's item (it has several: 4K, 1080p…); Wholphin plays that one. */
    data class JellyfinSource(
        val mediaSourceId: String,
    ) : Pick

    data class External(
        val source: ExternalSource,
    ) : Pick

    data object Cancelled : Pick
}

/** What the picker dialog shows. Rows include the Jellyfin copy, identified by [SourceHook.JELLYFIN_ROW]. */
data class PickerUi(
    val title: String,
    val rows: List<ExternalSource>,
    val searching: Boolean,
    val serversTotal: Int,
    val serversDone: Int,
    val onSelect: (ExternalSource) -> Unit,
    val onCancel: () -> Unit,
    // What's playing (and its show), so Cinema mode can show the title's art behind the choice
    val itemId: String = "",
    val seriesId: String? = null,
    // A title not on the main server (smart search): its TMDB backdrop to stand on
    val backdrop: String? = null,
    // Copies that failed to play just now ([PickSession.copyKey]): marked, and ranked last
    val failed: Set<String> = emptySet(),
    // Servers with no copy of this, and why (listed under the copies)
    val misses: List<Miss> = emptyList(),
)

/** A server the picker found nothing on: [trouble] null means it answered, it just hasn't this title. */
data class Miss(
    val connectionId: String,
    val label: String,
    val kind: ServerKind,
    val url: String,
    val trouble: ServerHealth.Trouble?,
) {
    val reason: String
        get() =
            when (trouble) {
                null -> "not on this server"
                ServerHealth.Trouble.SIGN_IN -> "sign-in expired · sign in again in Settings → Servers & Copies"
                else -> trouble.reason
            }
}

/**
 * One playback screen's worth of choices. The first item asks; later items in the same
 * playlist (next episode, autoplay) follow the server picked first, so autoplay never stops on a dialog.
 */
class PickSession internal constructor(
    private val hook: SourceHook,
) : AutoCloseable {
    private val _ui = MutableStateFlow<PickerUi?>(null)
    val ui: StateFlow<PickerUi?> = _ui.asStateFlow()

    private val chosen = ConcurrentHashMap<UUID, ExternalSource>()
    private val mainItems = ConcurrentHashMap<UUID, MainItem>()
    private var asked = false
    private var stickyConnectionId: String? = null

    /** The copy picked for the first item, so later episodes take the most similar one. */
    private var sticky: ExternalSource? = null

    /** The external URL to play for [itemId], or null to use Wholphin's own Jellyfin stream. */
    fun urlFor(itemId: UUID): String? = chosen[itemId]?.url

    fun isExternal(itemId: UUID): Boolean = chosen.containsKey(itemId)

    /**
     * Forget the external choice for [itemId] after it failed to play. True if there was one.
     * Later episodes no longer go to that server first (each paid the failed start again).
     */
    fun dropExternal(itemId: UUID): Boolean =
        (chosen.remove(itemId) != null).also {
            if (it) {
                Timber.w("External source failed for %s, falling back to Jellyfin", itemId)
                reporter?.stop()
                reporter = null
                stickyConnectionId = null
                sticky = null
                restoreTracks()
            }
        }

    private var reporter: PlaybackReporter? = null

    /**
     * Called (on the main thread) each time Wholphin sets a new stream on the player. Reports
     * playback to the extra server when [itemId] streams from one, and keeps the position of
     * every play (main server too) in the progress overlay.
     */
    fun track(
        player: androidx.media3.common.Player,
        itemId: UUID,
    ) {
        loadPictures(itemId)
        watchHealth(player)
        val source = chosen[itemId]
        val main = mainItems[itemId]
        if (reporter?.key == PlaybackReporter.key(main?.id, source)) return
        reporter?.stop()
        reporter = null
        if (source == null && main == null) return
        val connection = source?.let { hook.connectionFor(it.connectionId) }
        reporter =
            if (source != null && connection != null) {
                PlaybackReporter(hook.client, connection, source, player, hook.overlay, main)
            } else {
                PlaybackReporter(null, null, null, player, hook.overlay, main)
            }.also { it.start() }
    }

    private var health: Pair<androidx.media3.common.Player, androidx.media3.common.Player.Listener>? = null

    /**
     * Once the picture moves, the server delivered: its earlier misses ([StreamHealth]) and the
     * last copy's error no longer explain a later failure (the trouble screen blamed the server
     * for a decoder or file problem after 404s a retry had ridden out).
     */
    private fun watchHealth(player: androidx.media3.common.Player) {
        if (health?.first === player) return
        health?.let { (p, l) -> runCatching { p.removeListener(l) } }
        val listener =
            object : androidx.media3.common.Player.Listener {
                override fun onIsPlayingChanged(isPlaying: Boolean) {
                    if (!isPlaying) return
                    StreamHealth.played()
                    lastError = null
                }
            }
        player.addListener(listener)
        health = player to listener
    }

    /** The playback screen stopped (Home pressed, or closed): the play ends here. */
    override fun close() {
        health?.let { (p, l) -> runCatching { p.removeListener(l) } }
        health = null
        reporter?.stop()
        reporter = null
        picturesJob?.cancel()
        picturesFor = null
        PlayerPictures.clear()
    }

    private var picturesJob: kotlinx.coroutines.Job? = null
    private var picturesFor: String? = null

    /** Chapter pictures and seek thumbnails for what's playing (see [PlayerPictures]). */
    private fun loadPictures(itemId: UUID) {
        val base = lastBase?.takeIf { it.id == itemId } ?: return
        val playing = chosen[itemId]
        val key = "$itemId|${playing?.let(::copyKey)}"
        if (key == picturesFor) return
        picturesFor = key
        picturesJob?.cancel()
        picturesJob =
            hook.background.launch {
                try {
                    PlayerPictures.load(hook, hook.context, base, playing)
                } catch (e: Exception) {
                    if (e is kotlinx.coroutines.CancellationException) throw e
                    Timber.w(e, "Player pictures for %s failed", itemId)
                }
            }
    }

    // ------------------------------------------------------------ the picked file's own tracks and video

    private class MainTracks(
        val audioIndex: Int?,
        val subtitleIndex: Int?,
    )

    private val mainTracks = ConcurrentHashMap<UUID, MainTracks>()
    private var savedParams: androidx.media3.common.TrackSelectionParameters? = null
    private var tracksPlayer: androidx.media3.common.Player? = null

    /**
     * Before a stream starts (on the main thread): for a file from an extra server, asks the
     * player for the languages Wholphin picked from the main copy ([audioIndex] and
     * [subtitleIndex] index the main copy's tracks, which mean nothing in another file). Without
     * this an external file played its own default tracks: no preferred language, no subtitles.
     * For a main-server stream, puts back the player's own choices.
     */
    fun preferTracks(
        player: androidx.media3.common.Player,
        itemId: UUID,
        mainSource: org.jellyfin.sdk.model.api.MediaSourceInfo?,
        audioIndex: Int?,
        subtitleIndex: Int?,
    ) {
        mainTracks[itemId] = MainTracks(audioIndex, subtitleIndex)
        if (!isExternal(itemId)) return restoreTracks()
        val streams = mainSource?.mediaStreams.orEmpty()
        val audio = streams.firstOrNull { it.type == MediaStreamType.AUDIO && it.index == audioIndex }
        val subtitle = streams.firstOrNull { it.type == MediaStreamType.SUBTITLE && it.index == subtitleIndex }
        runCatching {
            if (tracksPlayer !== player) {
                savedParams = player.trackSelectionParameters
                tracksPlayer = player
            }
            val base = savedParams ?: player.trackSelectionParameters
            player.trackSelectionParameters = externalTrackParams(base, audio?.language, subtitle != null, subtitle?.language)
        }.onFailure { Timber.w(it, "Could not set track languages") }
    }

    private fun restoreTracks() {
        val player = tracksPlayer ?: return
        val params = savedParams
        tracksPlayer = null
        savedParams = null
        if (params != null) runCatching { player.trackSelectionParameters = params }
    }

    /**
     * Wholphin's track choice for [itemId] on the main copy, for the retry from Jellyfin after an
     * external file failed (the external play had none of its own: subtitles went off).
     */
    fun mainTrackChoice(itemId: UUID): Pair<Int?, Int?>? = mainTracks[itemId]?.let { it.audioIndex to it.subtitleIndex }

    /** HDR of the picked external file (null: not external, use the main copy's). */
    fun isHdr(itemId: UUID): Boolean? = chosen[itemId]?.takeIf { it.hdr.isNotBlank() || it.height > 0 }?.let { it.hdr.isNotBlank() }

    /** 4K-ness of the picked external file (null: not external or unknown). */
    fun is4k(itemId: UUID): Boolean? = chosen[itemId]?.takeIf { it.width > 0 || it.height > 0 }?.let { it.width > 2560 || it.height > 1440 }

    /**
     * The video stream to match the display to: the main copy's [stream] with the picked
     * external file's size and frame rate (a 4K 23.976 copy on another server was shown at the
     * main copy's 1080p 25).
     */
    fun videoStreamFor(
        itemId: UUID,
        stream: org.jellyfin.sdk.model.api.MediaStream,
    ): org.jellyfin.sdk.model.api.MediaStream {
        val src = chosen[itemId] ?: return stream
        return stream.copy(
            width = src.width.takeIf { it > 0 } ?: stream.width,
            height = src.height.takeIf { it > 0 } ?: stream.height,
            realFrameRate = src.frameRate.takeIf { it > 0f } ?: stream.realFrameRate,
            averageFrameRate = src.frameRate.takeIf { it > 0f } ?: stream.averageFrameRate,
        )
    }

    suspend fun pick(
        item: BaseItemDto,
        eligible: Boolean,
    ): Pick {
        chosen.remove(item.id)
        chosenMain.remove(item.id)
        if (lastBase?.id != item.id) lastError = null
        lastBase = item
        if (!eligible) return Pick.Jellyfin
        // Every play of a main-server item is kept in the overlay (see track)
        mainItems[item.id] = MainItem(item.id.toString(), item.seriesId?.toString(), item.seriesName ?: item.name.orEmpty())
        // The trouble screen's replay: the copy it was told to play, no questions
        forced.remove(item.id)?.let { copy ->
            asked = true
            stickyConnectionId = copy.connectionId.takeUnless { it == SourceHook.JELLYFIN_ROW }
            sticky = copy.takeUnless { it.connectionId == SourceHook.JELLYFIN_ROW }
            if (sticky == null) return mainPick(item, copy)
            chosen[item.id] = copy
            return Pick.External(copy)
        }
        val connections = hook.searchableConnections()
        if (connections.isEmpty()) return Pick.Jellyfin
        val request =
            try {
                hook.requestFor(item)
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                Timber.w(e, "Could not describe %s for matching", item.id)
                null
            } ?: return Pick.Jellyfin

        val result = if (asked) followSticky(connections, request) else ask(item, connections, request)
        if (result is Pick.External) {
            chosen[item.id] = result.source
            mainItems[item.id] = MainItem(item.id.toString(), item.seriesId?.toString(), request.title)
        }
        return result
    }

    private suspend fun followSticky(
        connections: List<ServerConnection>,
        request: PlayRequest,
    ): Pick {
        val connection = connections.firstOrNull { it.connectionId == stickyConnectionId } ?: return Pick.Jellyfin
        val found = hook.findOn(connection, request)
        // The copy most like the one you chose, not the server's best (see closestCopy)
        val best = sticky?.let { closestCopy(it, found) } ?: found.filterNot { it.compatible }.firstOrNull()
        Timber.i("Following %s for next item: %s", connection.label, best?.quality ?: "not found, using Jellyfin")
        return best?.let { Pick.External(it) } ?: Pick.Jellyfin
    }

    private suspend fun ask(
        item: BaseItemDto,
        connections: List<ServerConnection>,
        request: PlayRequest,
    ): Pick {
        val prefs = hook.store.pickerPrefs.value
        val ranking = tunedRanking(connections.map { it.connectionId }, prefs, SourceHook.JELLYFIN_ROW)
        val list = showList.also { showList = false }
        if (prefs.autoPlay && !list) return autoPick(item, connections, request, ranking)
        val decision = CompletableDeferred<Pick>()
        val found = MutableStateFlow(jellyfinRows(item))
        val title =
            if (request.isEpisode) {
                "${request.title} S%02dE%02d".format(request.season, request.episode)
            } else {
                listOfNotNull(request.title, request.year?.let { "($it)" }).joinToString(" ")
            }
        _ui.value =
            PickerUi(
                title = title,
                rows = found.value,
                searching = true,
                serversTotal = connections.size,
                serversDone = 0,
                onSelect = { row ->
                    decision.complete(if (row.connectionId == SourceHook.JELLYFIN_ROW) mainPick(item, row) else Pick.External(row))
                },
                onCancel = { decision.complete(Pick.Cancelled) },
                itemId = item.id.toString(),
                seriesId = item.seriesId?.toString(),
                failed = failedCopies[item.id].orEmpty().toSet(),
            )
        // The search runs on its own, not as a child the choice must wait for: a server that
        // answers slowly held OK and Back for up to half a minute (a scope can't end before its
        // children, and blocking HTTP calls don't stop when cancelled). Choosing closes the
        // picker at once; late answers just land in the cache.
        val search =
            hook.background.launch {
                connections
                    .map { connection ->
                        async {
                            val (sources, trouble) = hook.findChecked(connection, request)
                            found.update { (it + sources).withoutRepeats() }
                            val miss = if (sources.isEmpty()) Miss(connection.connectionId, connection.label, connection.serverKind, connection.serverUrl, trouble) else null
                            _ui.update { ui ->
                                ui?.copy(
                                    serversDone = ui.serversDone + 1,
                                    // In the Settings order of servers, whatever answers first
                                    misses = (ui.misses + listOfNotNull(miss)).sortedBy { m -> connections.indexOfFirst { it.connectionId == m.connectionId } },
                                )
                            }
                        }
                    }.awaitAll()
                // Rank only once everything is in, so rows never jump under the cursor.
                _ui.update { ui -> ui?.copy(rows = found.value.sortedWith(ranking).sortedBy { copyKey(it) in ui.failed }, searching = false) }
            }
        // finally: a playback screen closed mid-search must not leave the picker on screen
        val result =
            try {
                decision.await()
            } finally {
                search.cancel()
                _ui.value = null
            }
        asked = true
        stickyConnectionId = (result as? Pick.External)?.source?.connectionId
        sticky = (result as? Pick.External)?.source
        Timber.i("Source picked for %s: %s", item.id, (result as? Pick.External)?.source?.serverLabel ?: result.toString())
        return result
    }

    // ------------------------------------------------------------ a copy that failed (the trouble screen)

    private val failedCopies = ConcurrentHashMap<UUID, MutableSet<String>>()
    private val forced = ConcurrentHashMap<UUID, ExternalSource>()

    @Volatile private var lastError: Throwable? = null

    @Volatile private var lastFailed: ExternalSource? = null

    @Volatile private var lastBase: BaseItemDto? = null

    /** The player failed on [itemId] (before Wholphin falls back): remember which copy, and why. */
    fun noteFailure(
        itemId: UUID,
        error: Throwable,
    ) {
        lastError = error
        markFailed(itemId)
    }

    /** The copy [itemId] plays from failed: the countdown never offers it again. */
    private fun markFailed(itemId: UUID) {
        val copy = chosen[itemId] ?: mainCopy(itemId) ?: return
        lastFailed = copy
        failedCopies.getOrPut(itemId) { ConcurrentHashMap.newKeySet() } += copyKey(copy)
    }

    /** What to tell the viewer about the error on screen ([message], [exception]: Wholphin's). */
    fun trouble(
        itemId: UUID?,
        message: String?,
        exception: Throwable?,
    ): PlaybackTrouble.Trouble {
        // The copy on screen failed even when the server just offered nothing to play (no player error)
        itemId?.let(::markFailed)
        // The fallback's "no URL" carries no error of its own: the failure before it counts too
        val signals = PlaybackTrouble.signalsOf(listOfNotNull(exception, lastError).distinct(), message, StreamHealth.recent())
        val server = lastFailed?.takeUnless { it.connectionId == SourceHook.JELLYFIN_ROW }?.serverLabel ?: ServerBrands.mainName()
        return PlaybackTrouble.explain(signals, server)
    }

    /** The copy [itemId] plays from (null when unknown), and whether it's the main server's. */
    fun playingCopy(itemId: UUID): Pair<ExternalSource?, Boolean> {
        chosen[itemId]?.let { return it to false }
        return mainCopy(itemId) to true
    }

    /** Extra servers to choose from. */
    fun hasOtherServers(): Boolean = hook.searchableConnections().isNotEmpty()

    /**
     * The best copy of [itemId] that hasn't failed just now, on any server (the main one too),
     * or null. Uses the picker's lookups when it asked (cached), else looks now (~10 s at most).
     */
    suspend fun otherCopy(itemId: UUID): ExternalSource? {
        val base = lastBase?.takeIf { it.id == itemId } ?: return null
        val connections = hook.searchableConnections()
        if (connections.isEmpty()) return null
        val request =
            try {
                hook.requestFor(base)
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                null
            } ?: return null
        val found = kotlinx.coroutines.coroutineScope { connections.map { async { hook.findOn(it, request) } }.awaitAll().flatten() }
        val failed = failedCopies[itemId].orEmpty()
        return (jellyfinRows(base) + found)
            .withoutRepeats()
            .sortedWith(tunedRanking(connections.map { it.connectionId }, hook.store.pickerPrefs.value, SourceHook.JELLYFIN_ROW))
            .firstOrNull { copyKey(it) !in failed }
    }

    /** Before the trouble screen plays [itemId] again: which copy the next [pick] takes. */
    fun prepareRetry(
        itemId: UUID,
        retry: Retry,
    ) {
        // A new attempt is judged on its own failure, not the one before it
        lastError = null
        when (retry) {
            Retry.Same -> lastFailed?.let { forced[itemId] = it }
            Retry.Choose -> {
                asked = false
                showList = true
            }
            is Retry.Copy -> forced[itemId] = retry.source
        }
    }

    /** The list was asked for (the trouble screen's Choose a copy), even with auto-play on. */
    @Volatile private var showList = false

    /**
     * [PickerPrefs.autoPlay]: no list, the top copy that hasn't failed plays. Waits for the
     * servers a few seconds at most (answers are cached, so a replay is instant); a server
     * still looking by then is left out.
     */
    private suspend fun autoPick(
        item: BaseItemDto,
        connections: List<ServerConnection>,
        request: PlayRequest,
        ranking: Comparator<ExternalSource>,
    ): Pick {
        val found = java.util.concurrent.ConcurrentLinkedQueue<ExternalSource>()
        withTimeoutOrNull(SourceHook.AUTO_PLAY_WAIT_MS) {
            kotlinx.coroutines.coroutineScope { connections.map { c -> async { found += hook.findOn(c, request) } }.awaitAll() }
        }
        val failed = failedCopies[item.id].orEmpty()
        val best =
            (jellyfinRows(item) + found)
                .withoutRepeats()
                .sortedWith(ranking)
                .firstOrNull { copyKey(it) !in failed }
        asked = true
        val external = best?.takeUnless { it.connectionId == SourceHook.JELLYFIN_ROW }
        stickyConnectionId = external?.connectionId
        sticky = external
        Timber.i("Source auto-picked for %s: %s", item.id, best?.serverLabel ?: "Jellyfin")
        return external?.let { Pick.External(it) } ?: best?.let { mainPick(item, it) } ?: Pick.Jellyfin
    }

    /**
     * The main server's copies: one row per version of the item (sweep 2026-10-09: the main server
     * keeps 4K and 1080p versions in one item, and only the first was offered). Identical files
     * collapse in [withoutRepeats].
     */
    private fun jellyfinRows(item: BaseItemDto): List<ExternalSource> {
        val sources = item.mediaSources.orEmpty()
        if (sources.isEmpty()) return listOf(jellyfinRow(item, null))
        // The same file twice (the movie in two libraries: "4K HEVC 71.7 GB" listed twice) is one row
        return sources.map { jellyfinRow(item, it) }.distinctBy { listOf(it.quality, it.videoCodec, it.hdr, it.audio, it.container, it.sizeBytes) }
    }

    /** Which main-server version a row stands for: a version of its own only when there's a choice. */
    private fun mainPick(
        item: BaseItemDto,
        row: ExternalSource,
    ): Pick {
        chosenMain[item.id] = row
        return row.mediaSourceId.takeIf { it.isNotBlank() && item.mediaSources.orEmpty().size > 1 }?.let { Pick.JellyfinSource(it) } ?: Pick.Jellyfin
    }

    /** The main-server version [pick] chose, per item (its failure marks only that version). */
    private val chosenMain = ConcurrentHashMap<UUID, ExternalSource>()

    /** The main-server copy [itemId] plays: the version chosen, else its first. */
    private fun mainCopy(itemId: UUID): ExternalSource? = chosenMain[itemId] ?: lastBase?.takeIf { it.id == itemId }?.let { jellyfinRows(it).first() }

    private fun jellyfinRow(
        item: BaseItemDto,
        source: org.jellyfin.sdk.model.api.MediaSourceInfo?,
    ): ExternalSource {
        val streams = source?.mediaStreams.orEmpty()
        val video = streams.firstOrNull { it.type == MediaStreamType.VIDEO }
        val audio = streams.filter { it.type == MediaStreamType.AUDIO }.let { a -> a.firstOrNull { it.isDefault } ?: a.firstOrNull() }
        val quality = qualityLabel(video?.height ?: 0, video?.width ?: 0, source?.name.orEmpty())
        val hdr =
            when (video?.videoRangeType) {
                null, VideoRangeType.SDR, VideoRangeType.UNKNOWN -> ""
                VideoRangeType.HDR10_PLUS -> "HDR10+"
                VideoRangeType.HLG -> "HLG"
                VideoRangeType.HDR10 -> "HDR10"
                else -> if (video.videoRangeType?.name?.startsWith("DOVI") == true) "Dolby Vision" else "HDR"
            }
        return ExternalSource(
            connectionId = SourceHook.JELLYFIN_ROW,
            serverLabel = ServerBrands.mainName(),
            serverKind = ServerKind.JELLYFIN,
            url = "",
            quality = quality.ifBlank { "?" },
            qualityRank = qualityRank(quality),
            videoCodec = Labels.videoCodec(video?.codec.orEmpty()),
            hdr = hdr.ifBlank { Labels.hdrFromName(source?.path ?: source?.name.orEmpty()) },
            audio =
                audio
                    ?.let {
                        Labels.audio(
                            it.codec.orEmpty(),
                            it.profile.orEmpty(),
                            it.channels,
                            listOfNotNull(it.title, it.displayTitle).joinToString(" "),
                            source?.path ?: source?.name.orEmpty(),
                        )
                    }.orEmpty(),
            container = source?.container?.substringBefore(',')?.uppercase().orEmpty(),
            sizeBytes = source?.size ?: 0L,
            fileName = source?.path?.substringAfterLast('/')?.ifBlank { null } ?: source?.name.orEmpty(),
            mediaSourceId = source?.id.orEmpty(),
        )
    }
}

/**
 * The player's track choice for a file from an extra server: Wholphin's picks from the main copy
 * as languages (its track numbers belong to another file). Subtitles off leaves the player's
 * usual rule (forced or default-flagged tracks), as Wholphin does for the main copy.
 */
private fun externalTrackParams(
    base: androidx.media3.common.TrackSelectionParameters,
    audioLanguage: String?,
    subtitles: Boolean,
    subtitleLanguage: String?,
): androidx.media3.common.TrackSelectionParameters {
    fun lang(s: String?) = s?.trim()?.takeIf { it.isNotEmpty() && !it.equals("und", true) }
    return base
        .buildUpon()
        .clearOverrides()
        .setPreferredAudioLanguage(lang(audioLanguage))
        .setPreferredTextLanguage(if (subtitles) lang(subtitleLanguage) else null)
        // Subtitles on in a language the file doesn't name: still take its text track
        .setSelectUndeterminedTextLanguage(subtitles)
        .build()
}

private fun BaseItemDto.provider(name: String): String? =
    providerIds
        ?.entries
        ?.firstOrNull { it.key.equals(name, ignoreCase = true) }
        ?.value
        ?.takeIf { it.isNotBlank() }

/** The main-server item an external stream stands in for, so its progress lands in the overlay. */
internal data class MainItem(
    val id: String,
    val seriesId: String?,
    val title: String,
)

/** A home row added by Orca+ (a Trakt/MDBList collection). */
data class HomeRow(
    val name: String,
    val request: org.jellyfin.sdk.model.api.request.GetItemsRequest,
)
