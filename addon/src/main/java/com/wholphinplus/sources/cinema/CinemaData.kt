package com.wholphinplus.sources.cinema

import com.wholphinplus.sources.HomeCollections
import com.wholphinplus.sources.SourceHook
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import org.jellyfin.sdk.api.client.extensions.get
import org.jellyfin.sdk.api.client.extensions.itemsApi
import org.jellyfin.sdk.api.client.extensions.libraryApi
import org.jellyfin.sdk.api.client.extensions.tvShowsApi
import org.jellyfin.sdk.api.client.extensions.userApi
import org.jellyfin.sdk.api.client.extensions.userLibraryApi
import org.jellyfin.sdk.api.client.extensions.userViewsApi
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.BaseItemKind
import org.jellyfin.sdk.model.api.CollectionType
import org.jellyfin.sdk.model.api.ImageType
import org.jellyfin.sdk.model.api.ItemFields
import org.jellyfin.sdk.model.api.ItemSortBy
import org.jellyfin.sdk.model.api.MediaType
import org.jellyfin.sdk.model.api.request.GetItemsRequest
import org.jellyfin.sdk.model.api.request.GetLatestMediaRequest
import org.jellyfin.sdk.model.api.request.GetNextUpRequest
import org.jellyfin.sdk.model.api.request.GetResumeItemsRequest
import timber.log.Timber
import java.time.LocalDateTime
import java.util.UUID

/** One title as Cinema mode shows it. Episodes carry their series' art. */
@androidx.compose.runtime.Immutable
data class CinemaItem(
    val id: UUID,
    val kind: BaseItemKind,
    /** What "More Info" opens: the series for an episode, else the item itself. */
    val detailsId: UUID,
    val detailsKind: BaseItemKind,
    val title: String,
    val subtitle: String?,
    val meta: List<String>,
    val rating: String?,
    val overview: String,
    val backdropUrl: String?,
    val cardUrl: String?,
    val cardHasTitleArt: Boolean,
    val logoUrl: String?,
    val badge: String?,
    val resumeMs: Long,
    val progress: Float?,
    /** TMDB id of the movie, or of the series for shows and episodes; used for title art. */
    val tmdbId: Int? = null,
    val tmdbTv: Boolean = false,
    /** Portrait poster, for Top 10 rows. */
    val posterUrl: String? = null,
    /** Place in a Top 10 list, and how the billboard names it ("#2 in Movies This Week"). */
    val rank: Int? = null,
    val rankLabel: String? = null,
    /** Poster overlay facts: "4K"/"HD", "DV"/"HDR", "ATMOS"/"5.1", and watched. */
    val resolution: String? = null,
    val hdr: String? = null,
    val audio: String? = null,
    val played: Boolean = false,
) {
    val key: String get() = "$kind:$id"
}

@androidx.compose.runtime.Immutable
data class CinemaRow(
    val title: String,
    val items: List<CinemaItem>,
    /** A Top 10 row: big numbers beside portrait posters. */
    val ranked: Boolean = false,
)

data class CinemaLibrary(
    val id: UUID,
    val name: String,
    val kind: BaseItemKind,
    val collectionType: CollectionType?,
)

@androidx.compose.runtime.Immutable
data class CinemaHomeData(
    val featured: List<CinemaItem>,
    val rows: List<CinemaRow>,
    val shows: CinemaLibrary?,
    val movies: CinemaLibrary?,
)

/** Loads Cinema mode's home from the main Jellyfin server (through Wholphin's own connection). */
internal class CinemaRepository(
    private val hook: SourceHook,
    private val collections: HomeCollections,
) {
    private val api get() = hook.jellyfin

    private val fields =
        listOf(
            ItemFields.OVERVIEW,
            ItemFields.GENRES,
            ItemFields.DATE_CREATED,
            ItemFields.CHILD_COUNT,
            ItemFields.PROVIDER_IDS,
        )
    private val images = listOf(ImageType.PRIMARY, ImageType.BACKDROP, ImageType.THUMB, ImageType.LOGO)

    /**
     * The home page. [onFirst] gets it without the genre rows as soon as the quick rows are in:
     * the genre rows come from one random 500-title pool, the slowest request by far (~1.4 s of
     * the ~1.75 s on Silo, which shuffles the whole library for it), so they're added after.
     */
    suspend fun load(onFirst: (CinemaHomeData) -> Unit = {}): CinemaHomeData = timed("home", onFirst) { first -> loadHome(first) }

    private suspend fun loadHome(onFirst: (CinemaHomeData) -> Unit): CinemaHomeData =
        withContext(Dispatchers.IO) {
            val userId = hook.mainConnection()?.userId?.let { runCatching { UUID.fromString(dash(it)) }.getOrNull() }
                ?: error("Not signed in")
            val views =
                runCatching { api.userViewsApi.getUserViews(userId = userId).content.items }.getOrDefault(emptyList())
            val libs =
                views.map { CinemaLibrary(it.id, it.name.orEmpty(), it.type, it.collectionType) }
            val movieLibs = libs.filter { it.collectionType == CollectionType.MOVIES }
            val showLibs = libs.filter { it.collectionType == CollectionType.TVSHOWS }

            coroutineScope {
                val resume = async { safe { api.itemsApi.getResumeItems(GetResumeItemsRequest(userId = userId, limit = 24, fields = fields, mediaTypes = listOf(MediaType.VIDEO), enableImageTypes = images, imageTypeLimit = 1)).content.items } }
                val nextUp = async { safe { api.tvShowsApi.getNextUp(GetNextUpRequest(userId = userId, limit = 24, fields = fields, enableImageTypes = images, imageTypeLimit = 1)).content.items } }
                val latest =
                    (movieLibs + showLibs).map { lib ->
                        async {
                            lib to safe { api.userLibraryApi.getLatestMedia(GetLatestMediaRequest(userId = userId, parentId = lib.id, limit = 24, fields = fields, enableImageTypes = images, imageTypeLimit = 1, groupItems = true)).content }
                        }
                    }
                val lists =
                    collections.lists.value.filter { it.showOnHome && it.itemIds.isNotEmpty() }.map { c ->
                        async {
                            // Same route as Wholphin's own collection rows: the tag is answered by
                            // ProgressOverlay with the list's titles, in list order
                            c.name to safe {
                                api.itemsApi.getItems(GetItemsRequest(userId = userId, tags = listOf(c.tag), recursive = true, limit = 40, fields = fields, enableImageTypes = images, imageTypeLimit = 1)).content.items
                            }
                        }
                    }
                // Some servers ignore the genre filter, so rows are sorted out of one random pool
                val pool =
                    async {
                        safe {
                            api.itemsApi.getItems(
                                GetItemsRequest(userId = userId, includeItemTypes = listOf(BaseItemKind.MOVIE, BaseItemKind.SERIES), recursive = true, sortBy = listOf(ItemSortBy.RANDOM), limit = POOL, fields = fields, enableImageTypes = images, imageTypeLimit = 1),
                            ).content.items
                        }
                    }

                val cw = (resume.await() + nextUp.await()).distinctBy { it.seriesId ?: it.id }
                // Episodes borrow their series' TMDB id for title art
                val seriesIds = cw.mapNotNull { it.seriesId }.distinct()
                fillSeriesTmdb(cw, userId)
                val continueWatching = cw.map(::toItem)
                val latestRows =
                    latest.awaitAll().map { (lib, items) ->
                        CinemaRow(
                            if (lib.collectionType == CollectionType.TVSHOWS) "New Episodes in ${lib.name}" else "Recently Added in ${lib.name}",
                            items.map(::toItem),
                        )
                    }
                val quickRows =
                    buildList {
                        add(CinemaRow(continueTitle(userId), continueWatching))
                        lists.awaitAll().forEach { (name, items) -> add(listRow(name, items)) }
                        addAll(latestRows)
                    }.filter { it.items.isNotEmpty() }

                // The billboard: recent titles that have both a backdrop and title art
                val featured =
                    latestRows
                        .flatMap { it.items }
                        .filter { it.backdropUrl != null && it.overview.isNotBlank() && it.kind != BaseItemKind.EPISODE }
                        .distinctBy { it.detailsId }
                        .shuffled()
                        .take(6)
                        .ifEmpty { quickRows.flatMap { it.items }.filter { it.backdropUrl != null }.take(6) }

                // Show the page now; the genre rows join at the bottom (same billboard, no jump)
                onFirst(ranked(CinemaHomeData(featured, quickRows, showLibs.firstOrNull(), movieLibs.firstOrNull())))
                val rows = quickRows + genreRows(pool.await(), GENRE_ROWS, 5)
                ranked(CinemaHomeData(featured, rows, showLibs.firstOrNull(), movieLibs.firstOrNull()))
            }
        }

    // ------------------------------------------------------------ Shows / Movies / My List tabs

    /** A Shows or Movies page: the home's layout, every row narrowed to one kind of title. */
    /** A Shows or Movies page; like [load], [onFirst] gets it before the genre rows. */
    suspend fun loadKind(
        series: Boolean,
        onFirst: (CinemaHomeData) -> Unit = {},
    ): CinemaHomeData = timed(if (series) "shows" else "movies", onFirst) { first -> loadKindNow(series, first) }

    private suspend fun loadKindNow(
        series: Boolean,
        onFirst: (CinemaHomeData) -> Unit,
    ): CinemaHomeData =
        withContext(Dispatchers.IO) {
            val userId = userId()
            val kind = if (series) BaseItemKind.SERIES else BaseItemKind.MOVIE
            val views = runCatching { api.userViewsApi.getUserViews(userId = userId).content.items }.getOrDefault(emptyList())
            val libs = views.map { CinemaLibrary(it.id, it.name.orEmpty(), it.type, it.collectionType) }
            val mine = libs.filter { it.collectionType == if (series) CollectionType.TVSHOWS else CollectionType.MOVIES }

            fun query(sort: ItemSortBy) =
                GetItemsRequest(
                userId = userId,
                includeItemTypes = listOf(kind),
                recursive = true,
                sortBy = listOf(sort),
                sortOrder = listOf(org.jellyfin.sdk.model.api.SortOrder.DESCENDING),
                limit = 24,
                fields = fields,
                enableImageTypes = images,
                imageTypeLimit = 1,
            )

            coroutineScope {
                val resume =
                    async {
                        safe { api.itemsApi.getResumeItems(GetResumeItemsRequest(userId = userId, limit = 24, fields = fields, mediaTypes = listOf(MediaType.VIDEO), enableImageTypes = images, imageTypeLimit = 1)).content.items }
                            .filter { if (series) it.type == BaseItemKind.EPISODE else it.type == BaseItemKind.MOVIE }
                    }
                val nextUp =
                    async {
                        if (series) safe { api.tvShowsApi.getNextUp(GetNextUpRequest(userId = userId, limit = 24, fields = fields, enableImageTypes = images, imageTypeLimit = 1)).content.items } else emptyList()
                    }
                val latest =
                    mine.map { lib ->
                        async {
                            lib to safe { api.userLibraryApi.getLatestMedia(GetLatestMediaRequest(userId = userId, parentId = lib.id, limit = 24, fields = fields, enableImageTypes = images, imageTypeLimit = 1, groupItems = true)).content }
                        }
                    }
                val newReleases = async { safe { api.itemsApi.getItems(query(ItemSortBy.PREMIERE_DATE)).content.items } }
                val pool = async { safe { api.itemsApi.getItems(query(ItemSortBy.RANDOM).copy(limit = POOL)).content.items } }

                val cw = (resume.await() + nextUp.await()).distinctBy { it.seriesId ?: it.id }
                fillSeriesTmdb(cw, userId)
                val latestRows =
                    latest.awaitAll().map { (lib, items) ->
                        CinemaRow(if (series) "New Episodes in ${lib.name}" else "Recently Added in ${lib.name}", items.map(::toItem))
                    }
                val fresh = CinemaRow("New Releases", newReleases.await().map(::toItem))
                val quickRows =
                    buildList {
                        add(CinemaRow(continueTitle(userId), cw.map(::toItem)))
                        add(fresh)
                        addAll(latestRows)
                    }.filter { it.items.isNotEmpty() }.distinctBy { it.title }

                val featured =
                    (fresh.items + latestRows.flatMap { it.items })
                        .filter { it.backdropUrl != null && it.overview.isNotBlank() && it.kind == kind }
                        .distinctBy { it.detailsId }
                        .shuffled()
                        .take(6)
                        .ifEmpty { quickRows.flatMap { it.items }.filter { it.backdropUrl != null }.take(6) }
                val showLib = libs.firstOrNull { it.collectionType == CollectionType.TVSHOWS }
                val movieLib = libs.firstOrNull { it.collectionType == CollectionType.MOVIES }
                onFirst(ranked(CinemaHomeData(featured, quickRows, showLib, movieLib)))
                val rows = (quickRows + genreRows(pool.await(), if (series) SHOW_GENRES else MOVIE_GENRES, 8)).distinctBy { it.title }
                ranked(CinemaHomeData(featured, rows, showLib, movieLib))
            }
        }

    /**
     * New & Popular: Top 10 lists first, then what just arrived and what just premiered, movies
     * and shows together.
     */
    suspend fun loadNewPopular(): CinemaHomeData =
        withContext(Dispatchers.IO) {
            val userId = userId()

            fun query(
                sort: ItemSortBy,
                kinds: List<BaseItemKind>,
            ) = GetItemsRequest(
                userId = userId,
                includeItemTypes = kinds,
                recursive = true,
                sortBy = listOf(sort),
                sortOrder = listOf(org.jellyfin.sdk.model.api.SortOrder.DESCENDING),
                limit = 24,
                fields = fields,
                enableImageTypes = images,
                imageTypeLimit = 1,
            )
            val both = listOf(BaseItemKind.MOVIE, BaseItemKind.SERIES)
            coroutineScope {
                val lists =
                    collections.lists.value.filter { it.itemIds.isNotEmpty() && isTopList(it.name) }.map { c ->
                        async { c.name to safe { api.itemsApi.getItems(GetItemsRequest(userId = userId, tags = listOf(c.tag), recursive = true, limit = 10, fields = fields, enableImageTypes = images, imageTypeLimit = 1)).content.items } }
                    }
                val arrived = async { safe { api.itemsApi.getItems(query(ItemSortBy.DATE_CREATED, both)).content.items } }
                val movies = async { safe { api.itemsApi.getItems(query(ItemSortBy.PREMIERE_DATE, listOf(BaseItemKind.MOVIE))).content.items } }
                val shows = async { safe { api.itemsApi.getItems(query(ItemSortBy.PREMIERE_DATE, listOf(BaseItemKind.SERIES))).content.items } }
                val episodes =
                    async {
                        safe { api.itemsApi.getItems(query(ItemSortBy.PREMIERE_DATE, listOf(BaseItemKind.EPISODE)).copy(limit = 60)).content.items }
                            .distinctBy { it.seriesId ?: it.id }
                            .take(24)
                    }
                val eps = episodes.await()
                fillSeriesTmdb(eps, userId)
                val rows =
                    buildList {
                        lists.awaitAll().forEach { (name, items) -> add(listRow(name, items)) }
                        add(CinemaRow("New on Orca+", arrived.await().map(::toItem)))
                        add(CinemaRow("New Episodes", eps.map(::toItem)))
                        add(CinemaRow("New Release Movies", movies.await().map(::toItem)))
                        add(CinemaRow("New Release Shows", shows.await().map(::toItem)))
                    }.filter { it.items.isNotEmpty() }
                val featured =
                    (rows.filter { it.ranked }.flatMap { it.items.take(3) } + rows.flatMap { it.items }.take(12))
                        .filter { it.backdropUrl != null && it.overview.isNotBlank() && it.kind != BaseItemKind.EPISODE }
                        .distinctBy { it.detailsId }
                        .take(6)
                ranked(CinemaHomeData(featured, rows, null, null))
            }
        }

    /** A Trakt/MDBList row; lists named like a chart ("Top …") become Top 10 rows. */
    private fun listRow(
        name: String,
        items: List<BaseItemDto>,
    ): CinemaRow =
        if (isTopList(name) && items.size >= 3) {
            CinemaRow(name, items.take(10).map(::toItem), ranked = true)
        } else {
            CinemaRow(name, items.map(::toItem))
        }

    /**
     * Gives every card of a Top 10 title its place, wherever it shows up on the page, so the
     * billboard can say "#2 in Movies This Week" for it.
     */
    private fun ranked(data: CinemaHomeData): CinemaHomeData {
        val ranks = HashMap<UUID, Pair<Int, String>>()
        data.rows.filter { it.ranked }.forEach { row ->
            row.items.forEachIndexed { i, item -> ranks.putIfAbsent(item.detailsId, (i + 1) to rankLabel(row)) }
        }
        if (ranks.isEmpty()) return data

        fun mark(item: CinemaItem) = ranks[item.detailsId]?.let { (n, label) -> item.copy(rank = n, rankLabel = label) } ?: item
        return data.copy(featured = data.featured.map(::mark), rows = data.rows.map { r -> r.copy(items = r.items.map(::mark)) })
    }

    private fun rankLabel(row: CinemaRow): String {
        val shows = row.items.count { it.kind == BaseItemKind.SERIES }
        val what = if (shows * 2 > row.items.size) "Shows" else "Movies"
        val n = row.title.lowercase()
        val `when` =
            when {
                "week" in n -> "This Week"
                "today" in n || "day" in n -> "Today"
                "month" in n -> "This Month"
                "year" in n -> "This Year"
                else -> null
            }
        return listOfNotNull(what, `when`).joinToString(" ")
    }

    /** "Continue Watching for <name>", as a streaming app greets its profile. */
    private suspend fun continueTitle(userId: UUID): String {
        val name =
            userName ?: runCatching { api.userApi.getCurrentUser().content.name }.getOrNull()?.also { userName = it }
        // Account names like "abc123#Name": greet the part people read as the name
        val shown = name?.substringAfterLast('#')?.trim()
        return if (shown.isNullOrBlank()) "Continue Watching" else "Continue Watching for $shown"
    }

    private var userName: String? = null

    /** Everything marked My List (Jellyfin favourites), newest first. */
    suspend fun myList(): List<CinemaItem> =
        withContext(Dispatchers.IO) {
            val userId = userId()
            safe {
                api.itemsApi.getItems(
                    GetItemsRequest(
                        userId = userId,
                        isFavorite = true,
                        includeItemTypes = listOf(BaseItemKind.MOVIE, BaseItemKind.SERIES),
                        recursive = true,
                        sortBy = listOf(ItemSortBy.DATE_CREATED),
                        sortOrder = listOf(org.jellyfin.sdk.model.api.SortOrder.DESCENDING),
                        limit = 300,
                        fields = fields,
                        enableImageTypes = images,
                        imageTypeLimit = 1,
                    ),
                ).content.items
            }.filter { it.userData?.isFavorite != false }.map(::toItem)
        }

    /** Genre rows picked out of a random pool by each title's own genres (any alias matches). */
    private fun genreRows(
        pool: List<BaseItemDto>,
        rows: List<Pair<Set<String>, String>>,
        max: Int,
    ): List<CinemaRow> {
        val used = HashSet<UUID>()
        return rows
            .map { (names, title) ->
                val wanted = names.map { it.lowercase() }.toSet()
                // A title appears in one genre row only, so neighbouring rows don't repeat
                val items = pool.filter { d -> d.id !in used && d.genres.orEmpty().any { it.lowercase() in wanted } }.take(24)
                items.forEach { used += it.id }
                CinemaRow(title, items.map(::toItem))
            }.filter { it.items.size >= 6 }
            .take(max)
    }

    /**
     * Episodes borrow their series' TMDB id for title art. All the series in one request (it
     * holds up the page, so it used to be up to 30 one-by-one lookups); any the answer leaves
     * out are asked for alone.
     */
    private suspend fun fillSeriesTmdb(
        items: List<BaseItemDto>,
        userId: UUID,
    ) = coroutineScope {
        val ids = items.mapNotNull { it.seriesId }.distinct().filter { !seriesTmdb.containsKey(it) }.take(30)
        if (ids.isEmpty()) return@coroutineScope
        val got = runCatching { itemsByIds(ids, userId, "ProviderIds") }.getOrDefault(emptyList())
        got.forEach { s -> tmdbOf(s)?.let { seriesTmdb[s.id] = it } }
        val missing = ids - got.map { it.id }.toSet()
        missing.map { id ->
            async { runCatching { api.userLibraryApi.getItem(id, userId).content }.getOrNull() }
        }.awaitAll().filterNotNull().forEach { s -> tmdbOf(s)?.let { seriesTmdb[s.id] = it } }
    }

    /**
     * Several items by id in one request. The ids go as one comma-joined value: the SDK repeats
     * the parameter per id and some servers (Silo) read only the first copy.
     */
    private suspend fun itemsByIds(
        ids: List<UUID>,
        userId: UUID,
        fields: String,
    ): List<BaseItemDto> =
        api.get<org.jellyfin.sdk.model.api.BaseItemDtoQueryResult>(
            "/Items",
            queryParameters = mapOf("userId" to userId, "ids" to ids.joinToString(","), "fields" to fields, "enableImages" to false, "limit" to ids.size),
        ).content.items

    // ------------------------------------------------------------ details page

    suspend fun details(
        id: UUID,
        kind: BaseItemKind,
    ): CinemaDetailsData =
        withContext(Dispatchers.IO) {
            val userId = userId()
            val d = api.userLibraryApi.getItem(id, userId).content
            val series = d.type == BaseItemKind.SERIES
            coroutineScope {
                val similar =
                    async {
                        safe {
                            api.libraryApi.getSimilarItems(itemId = id, userId = userId, limit = 16, fields = fields).content.items
                        }.map(::toItem)
                    }
                val seasons =
                    async {
                        if (!series) {
                            emptyList()
                        } else {
                            safe { api.tvShowsApi.getSeasons(seriesId = id, userId = userId).content.items }
                                .map { CinemaSeason(it.id, it.name.orEmpty().ifBlank { "Season ${it.indexNumber}" }, it.indexNumber ?: 0) }
                                .sortedBy { if (it.number == 0) Int.MAX_VALUE else it.number }
                        }
                    }
                val next =
                    async {
                        if (!series) {
                            null
                        } else {
                            safe { api.tvShowsApi.getNextUp(GetNextUpRequest(userId = userId, seriesId = id, limit = 1, enableResumable = true)).content.items }.firstOrNull()
                        }
                    }
                tmdbOf(d)?.let { if (series) seriesTmdb[d.id] = it }
                val base = toItem(d)
                val video = d.mediaStreams?.firstOrNull { it.type == org.jellyfin.sdk.model.api.MediaStreamType.VIDEO }
                    ?: d.mediaSources?.firstOrNull()?.mediaStreams?.firstOrNull { it.type == org.jellyfin.sdk.model.api.MediaStreamType.VIDEO }
                val quality =
                    listOfNotNull(
                        video?.let { if ((it.width ?: 0) >= 3800 || (it.height ?: 0) >= 2000) "4K" else if ((it.width ?: 0) >= 1900) "HD" else null },
                        video?.videoRangeType?.name?.let { r ->
                            when {
                                r.startsWith("DOVI") -> "Dolby Vision"
                                r.startsWith("HDR10_PLUS") -> "HDR10+"
                                r.startsWith("HDR") || r == "HLG" -> "HDR"
                                else -> null
                            }
                        },
                    ).joinToString(" · ").ifBlank { null }
                val people = d.people.orEmpty()
                // Nothing in progress: start from the very first episode
                val nextEp =
                    next.await() ?: if (series) {
                        safe {
                            api.tvShowsApi.getEpisodes(org.jellyfin.sdk.model.api.request.GetEpisodesRequest(seriesId = id, userId = userId, limit = 1, isMissing = false)).content.items
                        }.firstOrNull()
                    } else {
                        null
                    }
                val play =
                    if (!series) {
                        val pos = (d.userData?.playbackPositionTicks ?: 0L) / 10_000L
                        PlayTarget(d.id, pos, if (pos > 0) "Resume" else "Play", base.progress, remaining(d.runTimeTicks, d.userData?.playbackPositionTicks))
                    } else if (nextEp != null) {
                        val pos = (nextEp.userData?.playbackPositionTicks ?: 0L) / 10_000L
                        val label = "S${nextEp.parentIndexNumber ?: 1}:E${nextEp.indexNumber ?: 1}"
                        PlayTarget(nextEp.id, pos, if (pos > 0) "Resume $label" else "Play $label", nextEp.userData?.playedPercentage?.let { (it / 100).toFloat() }, remaining(nextEp.runTimeTicks, nextEp.userData?.playbackPositionTicks))
                    } else {
                        null
                    }
                CinemaDetailsData(
                    item = base,
                    genres = d.genres.orEmpty().take(4),
                    cast = people.filter { it.type == org.jellyfin.sdk.model.api.PersonKind.ACTOR }.take(4).map { it.name.orEmpty() },
                    makers = people.filter { it.type == org.jellyfin.sdk.model.api.PersonKind.DIRECTOR || it.type == org.jellyfin.sdk.model.api.PersonKind.CREATOR }.take(2).map { it.name.orEmpty() },
                    makersLabel = if (series) "Created by" else "Director",
                    quality = quality,
                    favorite = d.userData?.isFavorite == true,
                    series = series,
                    seasons = seasons.await(),
                    play = play,
                    similar = similar.await().filter { it.backdropUrl != null || it.cardUrl != null },
                    startSeason = nextEp?.parentIndexNumber,
                )
            }
        }

    suspend fun episodes(
        seriesId: UUID,
        seasonId: UUID,
    ): List<CinemaEpisode> =
        withContext(Dispatchers.IO) {
            val userId = userId()
            safe {
                api.tvShowsApi.getEpisodes(
                    org.jellyfin.sdk.model.api.request.GetEpisodesRequest(
                        seriesId = seriesId,
                        userId = userId,
                        seasonId = seasonId,
                        fields = listOf(ItemFields.OVERVIEW),
                        enableImageTypes = listOf(ImageType.PRIMARY),
                        imageTypeLimit = 1,
                    ),
                ).content.items
            }.map { e ->
                CinemaEpisode(
                    id = e.id,
                    number = e.indexNumber ?: 0,
                    title = e.name.orEmpty(),
                    overview = e.overview.orEmpty(),
                    runtime = e.runTimeTicks?.let { "${it / 600_000_000L}m" },
                    stillUrl = e.imageTags?.get(ImageType.PRIMARY)?.let { image(e.id, "Primary", it, 480) },
                    progress = e.userData?.playedPercentage?.takeIf { it > 0 }?.let { (it / 100).toFloat() },
                    played = e.userData?.played == true,
                    resumeMs = (e.userData?.playbackPositionTicks ?: 0L) / 10_000L,
                )
            }
        }

    /**
     * One title's quality facts for its poster badges, from its full record. Asked per card on
     * screen: adding streams to row requests made the 500-title genre pool time out.
     */
    suspend fun streamTagsOf(id: UUID): StreamTags? =
        withContext(Dispatchers.IO) {
            runCatching { streamTags(api.userLibraryApi.getItem(id, userId()).content.mediaStreams) }.getOrNull()
        }

    /**
     * Badge facts for many titles in one request. Asking the item list for these exact ids with
     * streams makes Silo's compat layer upgrade the page to full records in one batch on its side
     * (it does that whenever detail fields are asked for), so a whole row costs one call.
     * Kept to a row's worth: the same on a 500-title page timed out.
     */
    suspend fun streamTagsBatch(ids: List<UUID>): Map<UUID, StreamTags> =
        withContext(Dispatchers.IO) {
            if (ids.isEmpty()) return@withContext emptyMap()
            val started = System.currentTimeMillis()
            val items = itemsByIds(ids, userId(), "MediaStreams")
            Timber.i("Cinema badges: %d of %d titles in one request, %d ms", items.size, ids.size, System.currentTimeMillis() - started)
            items.associate { it.id to streamTags(it.mediaStreams) }
        }

    suspend fun setFavorite(
        id: UUID,
        favorite: Boolean,
    ) = withContext(Dispatchers.IO) {
        val userId = userId()
        if (favorite) api.userLibraryApi.markFavoriteItem(id, userId) else api.userLibraryApi.unmarkFavoriteItem(id, userId)
    }

    /** Logs how long a page took to show (first rows) and to finish, for performance checks. */
    private suspend fun timed(
        page: String,
        onFirst: (CinemaHomeData) -> Unit,
        block: suspend ((CinemaHomeData) -> Unit) -> CinemaHomeData,
    ): CinemaHomeData {
        val started = System.currentTimeMillis()
        val d =
            block { first ->
                Timber.i("Cinema load %s: first rows in %d ms (%d rows)", page, System.currentTimeMillis() - started, first.rows.size)
                onFirst(first)
            }
        Timber.i("Cinema load %s: done in %d ms, %d rows, %d titles", page, System.currentTimeMillis() - started, d.rows.size, d.rows.sumOf { it.items.size })
        return d
    }

    private fun userId(): UUID =
        hook.mainConnection()?.userId?.let { runCatching { UUID.fromString(dash(it)) }.getOrNull() } ?: error("Not signed in")

    private fun remaining(
        runTicks: Long?,
        posTicks: Long?,
    ): String? {
        if (runTicks == null || posTicks == null || posTicks <= 0) return null
        val min = ((runTicks - posTicks) / 600_000_000L).coerceAtLeast(1)
        return if (min >= 60) "${min / 60}h ${min % 60}m left" else "${min}m left"
    }

    private suspend fun <T> safe(block: suspend () -> List<T>): List<T> =
        try {
            block()
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            Timber.w(e, "Cinema row failed")
            emptyList()
        }

    private val seriesTmdb = java.util.concurrent.ConcurrentHashMap<UUID, Int>()

    private fun tmdbOf(d: BaseItemDto): Int? =
        d.providerIds?.entries?.firstOrNull { it.key.equals("Tmdb", true) }?.value?.toIntOrNull()

    private fun toItem(d: BaseItemDto): CinemaItem {
        val episode = d.type == BaseItemKind.EPISODE
        val seriesId = d.seriesId
        val tags = d.imageTags.orEmpty()
        val backdrop =
            when {
                !d.backdropImageTags.isNullOrEmpty() -> image(d.id, "Backdrop/0", d.backdropImageTags!!.first(), 1280)
                d.parentBackdropItemId != null && !d.parentBackdropImageTags.isNullOrEmpty() ->
                    image(d.parentBackdropItemId!!, "Backdrop/0", d.parentBackdropImageTags!!.first(), 1280)
                else -> null
            }
        val logo =
            when {
                tags[ImageType.LOGO] != null -> image(d.id, "Logo", tags[ImageType.LOGO]!!, 600)
                d.parentLogoItemId != null && d.parentLogoImageTag != null -> image(d.parentLogoItemId!!, "Logo", d.parentLogoImageTag!!, 600)
                else -> null
            }
        // Wide card: a "Thumb" usually has the title art baked in, like a streaming tile
        val thumb =
            when {
                tags[ImageType.THUMB] != null -> image(d.id, "Thumb", tags[ImageType.THUMB]!!, 480)
                episode && d.parentThumbItemId != null && d.parentThumbImageTag != null -> image(d.parentThumbItemId!!, "Thumb", d.parentThumbImageTag!!, 480)
                else -> null
            }
        val poster =
            when {
                tags[ImageType.PRIMARY] != null && !episode -> image(d.id, "Primary", tags[ImageType.PRIMARY]!!, 360)
                episode && seriesId != null && d.seriesPrimaryImageTag != null -> image(seriesId, "Primary", d.seriesPrimaryImageTag!!, 360)
                else -> null
            }
        val tags2 = streamTags(d.mediaStreams)
        val now = LocalDateTime.now()
        val recent = d.dateCreated?.isAfter(now.minusDays(14)) == true
        val badge =
            when {
                episode && d.premiereDate?.isAfter(now.minusDays(14)) == true -> "New Episode"
                d.type == BaseItemKind.SERIES && recent -> "New Episodes"
                recent -> "Recently Added"
                else -> null
            }
        val runtime =
            d.runTimeTicks?.let { t ->
                val min = t / 600_000_000L
                if (min >= 60) "${min / 60}h ${min % 60}m" else "${min}m"
            }
        val seasons = d.childCount?.takeIf { d.type == BaseItemKind.SERIES }?.let { if (it == 1) "1 Season" else "$it Seasons" }
        val year = d.productionYear?.toString()
        val meta = listOfNotNull(year, if (d.type == BaseItemKind.SERIES) seasons else runtime, d.genres?.firstOrNull())
        val positionTicks = d.userData?.playbackPositionTicks ?: 0L
        val progress = d.userData?.playedPercentage?.takeIf { it > 0 }?.let { (it / 100.0).toFloat() }
        return CinemaItem(
            id = d.id,
            kind = d.type,
            detailsId = if (episode && seriesId != null) seriesId else d.id,
            detailsKind = if (episode && seriesId != null) BaseItemKind.SERIES else d.type,
            title = if (episode) d.seriesName.orEmpty().ifBlank { d.name.orEmpty() } else d.name.orEmpty(),
            subtitle = if (episode) "S${d.parentIndexNumber ?: 0}:E${d.indexNumber ?: 0} “${d.name.orEmpty()}”" else null,
            meta = meta,
            rating = d.officialRating,
            overview = d.overview.orEmpty(),
            backdropUrl = backdrop,
            cardUrl = thumb ?: backdrop,
            cardHasTitleArt = thumb != null,
            logoUrl = logo,
            badge = badge,
            resumeMs = positionTicks / 10_000L,
            progress = progress,
            tmdbId = if (episode) d.seriesId?.let { seriesTmdb[it] } else tmdbOf(d),
            tmdbTv = episode || d.type == BaseItemKind.SERIES,
            posterUrl = poster,
            resolution = tags2.resolution,
            hdr = tags2.hdr,
            audio = tags2.audio,
            played = d.userData?.played == true,
        )
    }

    private fun image(
        id: UUID,
        type: String,
        tag: String,
        width: Int,
    ): String {
        val base = api.baseUrl.orEmpty().trimEnd('/')
        val token = api.accessToken.orEmpty()
        return "$base/Items/$id/Images/$type?maxWidth=$width&quality=90&tag=$tag&api_key=$token"
    }

    private fun dash(id: String): String = com.wholphinplus.sources.ProgressOverlay.dashed(id)

    companion object {
        /** Lists named like a chart: "Top 10 …", "Top Watched Movies Of The Week". */
        fun isTopList(name: String): Boolean = Regex("""\btop\b""", RegexOption.IGNORE_CASE).containsMatchIn(name)

        /** How many random titles a page sorts into genre rows. */
        const val POOL = 500

        val MOVIE_GENRES =
            listOf(
                setOf("Action") to "Action Movies",
                setOf("Comedy") to "Comedy Movies",
                setOf("Thriller") to "Thrillers",
                setOf("Science Fiction", "Sci-Fi") to "Sci-Fi Movies",
                setOf("Horror") to "Horror Movies",
                setOf("Drama") to "Dramas",
                setOf("Animation") to "Animated Movies",
                setOf("Crime") to "Crime Movies",
                setOf("Romance") to "Romantic Movies",
                setOf("Documentary") to "Documentaries",
                setOf("Family") to "Family Movies",
                setOf("Adventure") to "Adventures",
            )

        val SHOW_GENRES =
            listOf(
                setOf("Drama") to "TV Dramas",
                setOf("Comedy", "Sitcom") to "TV Comedies",
                setOf("Crime") to "Crime TV",
                setOf("Sci-Fi & Fantasy", "Science Fiction", "Fantasy") to "Sci-Fi & Fantasy TV",
                setOf("Action & Adventure", "Action", "Adventure") to "Action & Adventure TV",
                setOf("Documentary") to "Docuseries",
                setOf("Reality", "Reality-TV") to "Reality TV",
                setOf("Animation") to "Animated Series",
                setOf("Mystery") to "Mysteries",
                setOf("Kids", "Children") to "Kids' TV",
                setOf("Family") to "Family TV",
            )

        val GENRE_ROWS =
            listOf(
                setOf("Action") to "Action-Packed",
                setOf("Comedy") to "Comedies",
                setOf("Science Fiction", "Sci-Fi & Fantasy") to "Sci-Fi",
                setOf("Thriller") to "Thrillers",
                setOf("Animation") to "Animation",
                setOf("Drama") to "Dramas",
                setOf("Crime") to "Crime",
                setOf("Family") to "Family Night",
            )
    }
}

@androidx.compose.runtime.Immutable
data class CinemaSeason(
    val id: UUID,
    val name: String,
    val number: Int,
)

@androidx.compose.runtime.Immutable
data class CinemaEpisode(
    val id: UUID,
    val number: Int,
    val title: String,
    val overview: String,
    val runtime: String?,
    val stillUrl: String?,
    val progress: Float?,
    val played: Boolean,
    val resumeMs: Long,
)

/** What the big Play button plays: the movie, or the episode you're up to. */
@androidx.compose.runtime.Immutable
data class PlayTarget(
    val id: UUID,
    val positionMs: Long,
    val label: String,
    val progress: Float?,
    val remaining: String?,
    /** True until the real target is known (a show's next episode); Play waits for it. */
    val pending: Boolean = false,
)

@androidx.compose.runtime.Immutable
data class CinemaDetailsData(
    val item: CinemaItem,
    val genres: List<String>,
    val cast: List<String>,
    val makers: List<String>,
    val makersLabel: String,
    val quality: String?,
    val favorite: Boolean,
    val series: Boolean,
    val seasons: List<CinemaSeason>,
    val play: PlayTarget?,
    val similar: List<CinemaItem>,
    val startSeason: Int?,
)
