package com.wholphinplus.sources

import com.wholphinplus.sources.core.ExternalSource
import com.wholphinplus.sources.core.PlayRequest
import com.wholphinplus.sources.core.TmdbClient
import com.wholphinplus.sources.core.TmdbItem
import com.wholphinplus.sources.core.TmdbSearch
import com.wholphinplus.sources.core.TmdbType
import com.wholphinplus.sources.core.sourceRanking
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import timber.log.Timber
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/** Where a search result can be watched. */
sealed interface Availability {
    /** On the main server: open Wholphin's own details page. */
    data class Library(
        val itemId: UUID,
        val series: Boolean,
    ) : Availability

    /** Not on the main server. Extra servers are only searched when the title is opened. */
    data object Elsewhere : Availability
}

/**
 * Smart search: TMDB decides what you meant, then each result is matched to the main
 * Jellyfin server (Wholphin opens it) or, when opened, to copies on the extra servers.
 */
@Singleton
class SearchService
    @Inject
    constructor(
        private val hook: SourceHook,
    ) {
        val tmdb =
            TmdbClient(
                http = OkHttpClient.Builder().connectTimeout(8, TimeUnit.SECONDS).readTimeout(15, TimeUnit.SECONDS).build(),
                apiKey = { hook.store.tmdbKey.value },
                proxy = com.wholphinplus.sources.sync.ProfileSync.ENDPOINT + "/v1/tmdb",
            )

        val enabled: Boolean get() = tmdb.available

        private val libraryCache = ConcurrentHashMap<String, Availability>()

        suspend fun search(query: String): TmdbSearch = withContext(Dispatchers.IO) { tmdb.search(query) }

        /** Is [item] on the main server? Title search there, confirmed by TMDB id or title + year. */
        private val lookups = kotlinx.coroutines.sync.Semaphore(6)

        suspend fun availability(item: TmdbItem): Availability {
            libraryCache[item.key]?.let { return it }
            // In the library index: known at once. Not there: a search confirms (the index can be
            // a day behind a title added today)
            hook.collections.indexFor(hook)?.let { idx ->
                (if (item.type == TmdbType.TV) idx.series else idx.movies).find(item.id, null)
                    ?.let { id -> runCatching { UUID.fromString(dashed(id)) }.getOrNull() }
                    ?.let { uuid -> return Availability.Library(uuid, item.type == TmdbType.TV).also { libraryCache[item.key] = it } }
            }
            // Only a real answer is remembered: after a failed lookup (network blip) the title
            // is asked about again next time instead of staying "elsewhere" until a restart
            var answered = false
            val result =
                lookups.withPermit {
                    withContext(Dispatchers.IO) {
                        val main = hook.mainConnection() ?: return@withContext Availability.Elsewhere
                        val request = PlayRequest(item.title, item.year, null, item.id, null)
                        val ids =
                            runCatching {
                                if (item.type == TmdbType.TV) hook.client.matchSeriesIds(main, request) else hook.client.matchItemIds(main, request)
                            }.onSuccess { answered = true }
                                .onFailure { Timber.w(it, "Library lookup failed for %s", item.title) }
                                .getOrDefault(emptyList())
                        ids.firstOrNull()?.let { id -> runCatching { UUID.fromString(dashed(id)) }.getOrNull() }
                            ?.let { Availability.Library(it, item.type == TmdbType.TV) }
                            ?: Availability.Elsewhere
                    }
                }
            if (answered) libraryCache[item.key] = result
            return result
        }

        /** Copies of a movie, or of one episode, on the extra servers; best first. */
        suspend fun sourcesFor(
            item: TmdbItem,
            season: Int? = null,
            episode: Int? = null,
        ): List<ExternalSource> =
            withContext(Dispatchers.IO) {
                val ids = runCatching { tmdb.externalIds(item) }.getOrNull()
                val request =
                    PlayRequest(
                        title = item.title,
                        year = item.year,
                        imdbId = ids?.imdb,
                        tmdbId = item.id,
                        tvdbId = ids?.tvdb,
                        season = season,
                        episode = episode,
                    )
                coroutineScope {
                    hook
                        .searchableConnections()
                        .map { c -> async { hook.findOn(c, request) } }
                        .awaitAll()
                        .flatten()
                        .sortedWith(sourceRanking)
                }
            }

        private fun dashed(id: String): String = ProgressOverlay.dashed(id)
    }
