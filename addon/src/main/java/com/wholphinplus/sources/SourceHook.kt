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
import com.wholphinplus.sources.core.qualityRank
import com.wholphinplus.sources.core.sameEndpoint
import com.wholphinplus.sources.core.sourceRanking
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
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
        @param:ApplicationContext private val context: Context,
        internal val jellyfin: ApiClient,
        val store: ConnectionStore,
        internal val overlay: ProgressOverlay,
        val collections: HomeCollections,
    ) {
        init {
            // Cinema mode gives memory back when Android asks (see MemoryTrim)
            context.registerComponentCallbacks(com.wholphinplus.sources.cinema.MemoryTrim(context))
        }

        val client: ServerClient by lazy {
            ServerClient(
                // Our own client: Wholphin's authenticated one would send the Jellyfin token to other servers.
                http =
                    OkHttpClient
                        .Builder()
                        .connectTimeout(8, TimeUnit.SECONDS)
                        .readTimeout(20, TimeUnit.SECONDS)
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

        fun newSession(): PickSession = PickSession(this)

        private val watchSync by lazy { WatchSync(context, this, jellyfin, overlay) }

        /** Bring progress from the extra servers into the main one. Cheap to call often (throttled). */
        suspend fun syncWatchState() {
            // Home is loading: refresh Trakt/MDBList collections older than 6 h in the background
            if (collections.lists.value.isNotEmpty()) collections.refreshStale(this)
            if (store.connections.value.any { it.isUsable }) watchSync.syncIfStale()
        }

        /**
         * Collection rows for Wholphin's home page, in order. Wholphin turns each into a
         * "GetItems" row whose request carries [HomeCollection.tag]; [ProgressOverlay] fills it.
         */
        val homeRows: kotlinx.coroutines.flow.Flow<List<HomeRow>> =
            collections.lists.map { list ->
                list.filter { it.showOnHome && it.name != HomeCollections.PENDING_NAME }.map { c ->
                    HomeRow(c.name, org.jellyfin.sdk.model.api.request.GetItemsRequest(tags = listOf(c.tag), recursive = true))
                }
            }

        /** Calls [onChange] on the main thread now and whenever the collection rows change. */
        fun watchHomeRows(onChange: (List<HomeRow>) -> Unit) {
            kotlinx.coroutines.MainScope().launch { homeRows.collect { onChange(it) } }
        }

        /** Rows this addon added, so Wholphin's own saved copies of them can be dropped. */
        fun isAddonRow(request: org.jellyfin.sdk.model.api.request.GetItemsRequest): Boolean =
            request.tags.orEmpty().any { it.startsWith(HomeCollection.TAG_PREFIX) }

        private var mainUserId: Pair<String, String>? = null

        /** Wholphin's current Jellyfin server as a [ServerConnection], for our own lookups. */
        internal fun mainConnection(): ServerConnection? {
            val url = jellyfin.baseUrl?.let(::normalizeServerUrl) ?: return null
            val token = jellyfin.accessToken ?: return null
            val shell = ServerConnection(serverUrl = url, serverKind = ServerKind.JELLYFIN, accessToken = token, serverName = "main")
            val userId =
                mainUserId?.takeIf { it.first == token }?.second
                    ?: runCatching { client.currentUserId(shell) }
                        .onFailure { Timber.w(it, "Watch sync: cannot read the main user") }
                        .getOrNull()
                        ?.also { mainUserId = token to it }
                    ?: return null
            return shell.copy(userId = userId)
        }


        internal fun connectionFor(id: String): ServerConnection? = store.connections.value.firstOrNull { it.connectionId == id }

        /** Apply a pushed import file, if any. Returns the Jellyfin login Wholphin should switch to. */
        fun takeImportedLogin(): ImportedLogin? = Importer.run(context, store, collections).also { clearCache() }

        /** Saved servers that can be searched, minus the Jellyfin server Wholphin is already using. */
        internal fun searchableConnections(): List<ServerConnection> {
            val current = jellyfin.baseUrl?.let(::normalizeServerUrl).orEmpty()
            return store.connections.value.filter { it.isUsable && (current.isBlank() || !sameEndpoint(it.serverUrl, current)) }
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
                        val series =
                            item.seriesId?.let { id ->
                                runCatching { jellyfin.userLibraryApi.getItem(id).content }
                                    .onFailure { Timber.w(it, "Could not load series %s", id) }
                                    .getOrNull()
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

        /** Look up one server, with a cache so replaying or resuming is instant. */
        internal suspend fun findOn(
            connection: ServerConnection,
            request: PlayRequest,
        ): List<ExternalSource> {
            val key = "${connection.connectionId}|${connection.lastConnectedAt}|$request"
            cache[key]?.let { if (System.currentTimeMillis() - it.at < CACHE_MS) return it.sources }
            val found =
                withContext(Dispatchers.IO) {
                    withTimeoutOrNull(SERVER_TIMEOUT_MS) { client.findSources(connection, request) }
                } ?: return emptyList()
            cache[key] = Cached(found, System.currentTimeMillis())
            return found
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
            private const val CACHE_MS = 15 * 60 * 1000L
            private const val SERVER_TIMEOUT_MS = 10_000L
        }
    }

sealed interface Pick {
    data object Jellyfin : Pick

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
)

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

    /** The external URL to play for [itemId], or null to use Wholphin's own Jellyfin stream. */
    fun urlFor(itemId: UUID): String? = chosen[itemId]?.url

    fun isExternal(itemId: UUID): Boolean = chosen.containsKey(itemId)

    /** Forget the external choice for [itemId] after it failed to play. True if there was one. */
    fun dropExternal(itemId: UUID): Boolean =
        (chosen.remove(itemId) != null).also {
            if (it) {
                Timber.w("External source failed for %s, falling back to Jellyfin", itemId)
                reporter?.stop()
                reporter = null
            }
        }

    private var reporter: PlaybackReporter? = null

    /**
     * Called (on the main thread) each time Wholphin sets a new stream on the player. Reports
     * playback to the extra server when [itemId] streams from one.
     */
    fun track(
        player: androidx.media3.common.Player,
        itemId: UUID,
    ) {
        val source = chosen[itemId]
        if (reporter != null && source != null && reporter?.isFor(source) == true) return
        reporter?.stop()
        reporter = null
        val connection = source?.let { hook.connectionFor(it.connectionId) } ?: return
        reporter = PlaybackReporter(hook.client, connection, source, player, hook.overlay, mainItems[itemId]).also { it.start() }
    }

    override fun close() {
        reporter?.stop()
        reporter = null
    }

    suspend fun pick(
        item: BaseItemDto,
        eligible: Boolean,
    ): Pick {
        chosen.remove(item.id)
        if (!eligible) return Pick.Jellyfin
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
        val best = hook.findOn(connection, request).filterNot { it.compatible }.firstOrNull()
        Timber.i("Following %s for next item: %s", connection.label, best?.quality ?: "not found, using Jellyfin")
        return best?.let { Pick.External(it) } ?: Pick.Jellyfin
    }

    private suspend fun ask(
        item: BaseItemDto,
        connections: List<ServerConnection>,
        request: PlayRequest,
    ): Pick {
        val decision = CompletableDeferred<Pick>()
        val jellyfinRow = jellyfinRow(item)
        val found = MutableStateFlow(listOf(jellyfinRow))
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
                    decision.complete(if (row.connectionId == SourceHook.JELLYFIN_ROW) Pick.Jellyfin else Pick.External(row))
                },
                onCancel = { decision.complete(Pick.Cancelled) },
            )
        val result =
            coroutineScope {
                val search =
                    launch {
                        connections
                            .map { connection ->
                                async {
                                    val sources = hook.findOn(connection, request)
                                    found.update { it + sources }
                                    _ui.update { ui -> ui?.copy(serversDone = ui.serversDone + 1) }
                                }
                            }.awaitAll()
                        // Rank only once everything is in, so rows never jump under the cursor.
                        _ui.update { ui -> ui?.copy(rows = found.value.sortedWith(sourceRanking), searching = false) }
                    }
                decision.await().also { search.cancel() }
            }
        _ui.value = null
        asked = true
        stickyConnectionId = (result as? Pick.External)?.source?.connectionId
        Timber.i("Source picked for %s: %s", item.id, (result as? Pick.External)?.source?.serverLabel ?: result.toString())
        return result
    }

    private fun jellyfinRow(item: BaseItemDto): ExternalSource {
        val source = item.mediaSources?.firstOrNull()
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
            serverLabel = "Jellyfin (this server)",
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
            fileName = source?.name.orEmpty(),
        )
    }
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
