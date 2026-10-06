package com.wholphinplus.sources.cinema

import com.wholphinplus.sources.HomeCollections
import com.wholphinplus.sources.SourceHook
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.withPermit
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
    /** The backdrop at card size: the clean picture a title logo is laid over. */
    val cleanCardUrl: String? = null,
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
    /** The caption under a card (Poster tags → Title and date): "Apr 24, 2019" or "S2:E18". */
    val captionDate: String? = null,
    /** The caption's right side: "2h 23m" or "2 Seasons". */
    val captionLength: String? = null,
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
    /** A row of Services or Genres tiles instead of titles. */
    val tiles: List<PageTile> = emptyList(),
)

/** A service's, genre's or decade's tile; it opens that page (with its Movies or Shows side first). */
@androidx.compose.runtime.Immutable
data class PageTile(
    val id: String,
    val name: String,
    /** "service", "genre" or "decade". */
    val kind: String,
    val logoUrl: String?,
    val pictureUrl: String?,
) {
    val service: Boolean get() = kind == "service"
}

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
) {
    /** More rows, or more titles in them, than [other] (null = nothing shown yet). */
    fun richerThan(other: CinemaHomeData?): Boolean =
        other == null || rows.size > other.rows.size || rows.sumOf { it.items.size + it.tiles.size } > other.rows.sumOf { it.items.size + it.tiles.size }
}

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
     * A page (Home, Shows, Movies, New & Popular), built from its rows as arranged. [onFirst]
     * gets it without the genre rows as soon as the quick rows are in: the genre rows come from
     * one random 500-title pool, the slowest request by far (~1.4 s of the ~1.75 s on Silo,
     * which shuffles the whole library for it), so they're added after.
     */
    suspend fun load(
        page: RowsPage = RowsPage.HOME,
        onFirst: (CinemaHomeData) -> Unit = {},
    ): CinemaHomeData = timed(page.name.lowercase(), onFirst) { first -> loadPage(page, first) }

    /** The lists as [page] sees them: Top Streaming charts know whether they're shows or movies. */
    fun pageLists(): List<PageList> = collections.lists.value.filterNot { it.hidden }.map { PageList(it.id, it.name, collections.chartSeries(it), it.pages, it.order) }

    /** A Services or Genres page by id (null when the cloud no longer offers it). */
    fun cloudPage(id: String): com.wholphinplus.sources.core.CloudPage? = collections.cloudPages.value.pages.firstOrNull { it.id == id }

    /** Where the cloud places the Services and Genres tiles on [page] (their lineup positions). */
    fun tiles(page: RowsPage): Map<HomeRowType, Int> =
        collections.cloudPages.value.tiles[page.name].orEmpty().mapNotNull { (k, at) ->
            when (k) {
                "SERVICES" -> HomeRowType.SERVICES to at
                "GENRES" -> HomeRowType.GENRES to at
                "DECADES" -> HomeRowType.DECADES to at
                else -> null
            }
        }.toMap()

    /** The tiles of a Services or Genres row on [page]: only pages with something on its side. */
    private fun tileRow(
        type: HomeRowType,
        page: RowsPage,
    ): List<PageTile> =
        collections.cloudPages.value.pages
            .filter {
                it.kind ==
                    when (type) {
                        HomeRowType.SERVICES -> "service"
                        HomeRowType.DECADES -> "decade"
                        else -> "genre"
                    }
            }
            // Only pages with a row to show on this tab's side (a small library may have none yet)
            .filter { p ->
                when (page.series) {
                    true -> p.shows.any(::rowShows)
                    false -> p.movies.any(::rowShows)
                    null -> (p.shows + p.movies).any(::rowShows)
                }
            }.map { PageTile(it.id, it.name, it.kind, it.logo, it.picture) }

    /**
     * A service's or genre's page: its Movies rows ([series] false) or Shows rows, each one of its
     * charts as matched to the library, under the page's names for them.
     */
    suspend fun loadCloudPage(
        id: String,
        series: Boolean,
    ): CinemaHomeData =
        withContext(Dispatchers.IO) {
            val userId = hook.mainConnection()?.userId?.let { runCatching { UUID.fromString(dash(it)) }.getOrNull() } ?: error("Not signed in")
            val p = collections.cloudPages.value.pages.firstOrNull { it.id == id } ?: error("This page is no longer offered")
            val side = if (series) RowsPage.SHOWS else RowsPage.MOVIES
            val rows =
                coroutineScope {
                    (if (series) p.shows else p.movies).mapNotNull { r -> collections.forChart(r.chart)?.let { c -> r.name to c } }.map { (name, c) ->
                        async { Triple(name, c, safe { api.itemsApi.getItems(GetItemsRequest(userId = userId, tags = listOf(c.tag), recursive = true, limit = 40, fields = fields, enableImageTypes = images, imageTypeLimit = 1)).content.items }) }
                    }.awaitAll()
                }.map { (name, c, items) -> listRow(name, items.filter { fits(side, it) }, c) }
                    .filter { it.items.size >= minFor(it.title) }
            val featured = rows.flatMap { it.items.take(4) }.filter { it.backdropUrl != null && it.overview.isNotBlank() }.distinctBy { it.detailsId }.take(6)
            ranked(CinemaHomeData(featured, rows, null, null))
        }

    /**
     * Whether a list row on [page] has anything to load: on Home, only your lists shown on the
     * home (an Orca+ chart's place is the page layout's alone).
     */
    private fun listLoads(
        page: RowsPage,
        c: com.wholphinplus.sources.HomeCollection,
    ) = collections.enough(c) && (page != RowsPage.HOME || c.showOnHome || collections.isChart(c))

    private fun minFor(name: String) = collections.minFor(name)

    /** Whether a Services / Genres page has a row to show on its Shows ([series]) or Movies side. */
    fun hasRows(
        p: com.wholphinplus.sources.core.CloudPage,
        series: Boolean,
    ) = (if (series) p.shows else p.movies).any(::rowShows)

    /** Whether a page's chart has enough titles in the library to show. */
    private fun rowShows(r: com.wholphinplus.sources.core.CloudPageRow) = (collections.forChart(r.chart)?.itemIds?.size ?: 0) >= minFor(r.name)

    /** Whether a title belongs on [page] (Shows: shows and their episodes; Movies: movies). */
    private fun fits(
        page: RowsPage,
        d: BaseItemDto,
    ): Boolean =
        when (page.series) {
            null -> true
            true -> d.type == BaseItemKind.SERIES || d.type == BaseItemKind.EPISODE || d.type == BaseItemKind.SEASON
            false -> d.type == BaseItemKind.MOVIE
        }

    private fun kinds(page: RowsPage) =
        when (page.series) {
            null -> listOf(BaseItemKind.MOVIE, BaseItemKind.SERIES)
            true -> listOf(BaseItemKind.SERIES)
            false -> listOf(BaseItemKind.MOVIE)
        }

    private suspend fun loadPage(
        page: RowsPage,
        onFirst: (CinemaHomeData) -> Unit,
    ): CinemaHomeData =
        withContext(Dispatchers.IO) {
            val userId = hook.mainConnection()?.userId?.let { runCatching { UUID.fromString(dash(it)) }.getOrNull() }
                ?: error("Not signed in")
            val saved = hook.store.pageLayouts.value[page]
            val allLists = pageLists()
            // Everything that doesn't need the library list starts at once. The slow genre pool
            // is sent last so the quick rows aren't queued behind it (the app's HTTP client runs
            // a few requests per server at a time).
            coroutineScope {
                val views = async { runCatching { api.userViewsApi.getUserViews(userId = userId).content.items }.getOrDefault(emptyList()) }
                val resume =
                    async {
                        if (!page.hasContinueWatching) emptyList() else safe { api.itemsApi.getResumeItems(GetResumeItemsRequest(userId = userId, limit = 24, fields = fields, mediaTypes = listOf(MediaType.VIDEO), enableImageTypes = images, imageTypeLimit = 1)).content.items }
                    }
                val nextUp =
                    async {
                        if (!page.hasContinueWatching || page.series == false) emptyList() else safe { api.tvShowsApi.getNextUp(GetNextUpRequest(userId = userId, limit = 24, fields = fields, enableImageTypes = images, imageTypeLimit = 1)).content.items }
                    }
                val greeting = async { if (page.hasContinueWatching) continueTitle(userId) else "" }
                // The lists this page shows (before the libraries are known, from the saved
                // layout, or the page's defaults for lists)
                val wanted =
                    collections.lists.value.filter { c ->
                        // Page-only charts (Services, Genres) aren't a tab's rows
                        val pl = allLists.firstOrNull { it.id == c.id } ?: return@filter false
                        listLoads(page, c) && page.offers(pl) &&
                            (saved?.rows?.any { it.type == HomeRowType.COLLECTION && it.ref == c.id && it.on } ?: page.listStartsOn(pl))
                    }
                val lists =
                    wanted.associate { c ->
                        // Same route as Wholphin's own collection rows: the tag is answered by
                        // ProgressOverlay with the list's titles, in list order
                        c.id to async { c.name to safe { api.itemsApi.getItems(GetItemsRequest(userId = userId, tags = listOf(c.tag), recursive = true, limit = 40, fields = fields, enableImageTypes = images, imageTypeLimit = 1)).content.items } }
                    }
                // Rows that don't depend on which libraries there are start now, before the
                // library list is back (the defaults include them; a saved page if it has them on)
                // (Home's defaults depend on the libraries, so before its first save all start)
                val guess = saved ?: HomeLayout.defaults(page, emptyList(), allLists, tiles(page)).takeIf { page != RowsPage.HOME }
                val early =
                    LIBRARY_FREE.filter { t -> page.offers(t) && (guess?.rows?.any { it.type == t && it.on } ?: true) }.associateWith { t ->
                        async { rowItems(HomeRowSpec(t), page, userId, emptyList(), emptyMap()) }
                    }
                val libs = views.await().map { CinemaLibrary(it.id, it.name.orEmpty(), it.type, it.collectionType) }
                val movieLibs = libs.filter { it.collectionType == CollectionType.MOVIES }
                val showLibs = libs.filter { it.collectionType == CollectionType.TVSHOWS }
                // Your arrangement, brought up to date with the server; a page never arranged
                // starts with its defaults (one Recently Added row for all movies, not one per library)
                val layout = layoutFor(page, libs, saved)
                val on = withFallback(page, layout.rows.filter { it.on }, lists.keys)
                val genres = on.filter { it.type == HomeRowType.GENRE }
                // Every other row's items, in one request each, all at once
                val rows =
                    on.filter { it.type != HomeRowType.GENRE }.map { spec ->
                        spec to (early[spec.type] ?: async { rowItems(spec, page, userId, libs, lists) })
                    }
                // Sent last, so the quick rows aren't queued behind it. Some servers ignore the
                // genre filter, so genre rows are sorted out of one random pool
                val pool =
                    if (genres.isEmpty()) {
                        null
                    } else {
                        async {
                            safe {
                                api.itemsApi.getItems(
                                    GetItemsRequest(userId = userId, includeItemTypes = kinds(page), recursive = true, sortBy = listOf(ItemSortBy.RANDOM), limit = POOL, fields = fields, enableImageTypes = images, imageTypeLimit = 1),
                                ).content.items
                            }
                        }
                    }
                val cw = (resume.await() + nextUp.await()).filter { fits(page, it) }.distinctBy { it.seriesId ?: it.id }
                // Episodes borrow their series' TMDB id for title art. Only art, so the page
                // doesn't wait for it: the first rows use what's known, the full page has it all
                val seriesArt = async { fillSeriesTmdb(cw, userId) }
                val title = greeting.await()
                // The screen shows once Continue Watching and the next rows (what's on screen) are
                // in; rows further down join as they arrive, like the genre rows
                rows.filter { it.first.type != HomeRowType.CONTINUE_WATCHING }.take(ON_SCREEN_ROWS).forEach { it.second.await() }
                var got = rows.filter { it.second.isCompleted }.map { (spec, items) -> spec to items.await() }

                fun build(genreRows: Map<String, CinemaRow>) =
                    on.mapNotNull { spec ->
                        when (spec.type) {
                            HomeRowType.CONTINUE_WATCHING -> CinemaRow(spec.title.ifBlank { title }, cw.map(::toItem))
                            HomeRowType.SERVICES, HomeRowType.GENRES, HomeRowType.DECADES -> CinemaRow(spec.title.ifBlank { spec.defaultName(emptyList(), emptyMap()) }, emptyList(), tiles = tileRow(spec.type, page))
                            HomeRowType.GENRE -> genreRows[spec.ref]?.let { r -> spec.title.takeIf { it.isNotBlank() }?.let { r.copy(title = it) } ?: r }
                            else ->
                                got.firstOrNull { it.first == spec }?.second?.let { (name, items) ->
                                    val shown = spec.title.ifBlank { name }
                                    if (spec.type == HomeRowType.COLLECTION) listRow(shown, items, collections.lists.value.firstOrNull { it.id == spec.ref }) else CinemaRow(shown, items.map(::toItem))
                                }
                        }
                    }.filter { it.items.isNotEmpty() || it.tiles.isNotEmpty() }
                val quickRows = build(emptyMap())

                // The billboard: the charts' leaders, then recent titles with a backdrop and an overview
                val fresh = setOf(HomeRowType.RECENT_MOVIES, HomeRowType.NEW_EPISODES, HomeRowType.LIBRARY, HomeRowType.NEW_RELEASE_MOVIES, HomeRowType.NEW_RELEASE_SHOWS, HomeRowType.NEW_ARRIVALS)
                fun pick(shownRows: List<CinemaRow>): List<CinemaItem> {
                    val leaders = if (page == RowsPage.NEW_POPULAR) shownRows.filter { it.ranked }.flatMap { it.items.take(3) } else emptyList()
                    return (
                        leaders +
                            got.filter { it.first.type in fresh }
                                .flatMap { it.second.second.map(::toItem) }
                                .shuffled()
                    ).filter { it.backdropUrl != null && it.overview.isNotBlank() && it.kind != BaseItemKind.EPISODE }
                        .distinctBy { it.detailsId }
                        .take(6)
                        .ifEmpty { shownRows.flatMap { it.items }.filter { it.backdropUrl != null }.take(6) }
                        // No backdrops on the server: titles TMDB has a picture for (the billboard draws it)
                        .ifEmpty { shownRows.flatMap { it.items }.filter { it.kind != BaseItemKind.EPISODE && it.tmdbId != null }.distinctBy { it.detailsId }.take(6) }
                }
                val featured = pick(quickRows)

                // Show the page now; the genre rows join in their places (same billboard)
                onFirst(ranked(CinemaHomeData(featured, quickRows, showLibs.firstOrNull(), movieLibs.firstOrNull())))
                // What Wholphin's own home does on load: refresh stale Trakt/MDBList rows and pull
                // progress from the extra servers. In the background; it shows on the next refresh
                if (page == RowsPage.HOME) hook.syncWatchStateLater()
                got = rows.map { (spec, items) -> spec to items.await() }
                seriesArt.await()
                val picked = genres.mapNotNull { g -> page.genres.firstOrNull { it.second == g.ref } }
                val genreRows = pool?.await()?.let { genreRows(it, picked, picked.size) }.orEmpty().associateBy { it.title }
                val all = build(genreRows)
                // Nothing for the billboard in the first rows (they weren't in yet): pick from the whole page
                ranked(CinemaHomeData(featured.ifEmpty { pick(all) }, all, showLibs.firstOrNull(), movieLibs.firstOrNull()))
            }
        }

    /**
     * [on] plus the server's newest titles when it would show less than two rows of titles (the
     * cloud couldn't be reached, or its charts barely meet a small library). Only for this load:
     * the arrangement isn't changed, and the extra rows leave once the page has its own.
     */
    private fun withFallback(
        page: RowsPage,
        on: List<HomeRowSpec>,
        loadingLists: Set<String>,
    ): List<HomeRowSpec> {
        // List rows with nothing to load (too few titles in the library yet) leave the page, so the
        // first screen waits for rows that will show
        val on = on.filter { it.type != HomeRowType.COLLECTION || it.ref in loadingLists }
        val titled =
            on.count {
                when (it.type) {
                    HomeRowType.CONTINUE_WATCHING, HomeRowType.SERVICES, HomeRowType.GENRES, HomeRowType.DECADES -> false
                    HomeRowType.COLLECTION -> it.ref in loadingLists
                    else -> true
                }
            }
        if (titled >= 2) return on
        val fallback = if (page == RowsPage.NEW_POPULAR) listOf(HomeRowType.NEW_ARRIVALS, HomeRowType.JUST_AIRED) else listOf(HomeRowType.RECENT_MOVIES, HomeRowType.NEW_EPISODES)
        val extra = fallback.filter { t -> page.offers(t) && on.none { it.type == t } }.map { HomeRowSpec(it) }
        return on + extra
    }

    /** The server's libraries, for the row editor's names and choices. */
    suspend fun libraries(): List<CinemaLibrary> =
        withContext(Dispatchers.IO) {
            val userId = hook.mainConnection()?.userId?.let { runCatching { UUID.fromString(dash(it)) }.getOrNull() } ?: return@withContext emptyList()
            runCatching { api.userViewsApi.getUserViews(userId = userId).content.items }.getOrDefault(emptyList())
                .map { CinemaLibrary(it.id, it.name.orEmpty(), it.type, it.collectionType) }
        }

    /**
     * [page]'s rows as arranged, brought up to date with the server and your lists. A page
     * never arranged starts with its defaults.
     */
    fun layoutFor(
        page: RowsPage,
        libs: List<CinemaLibrary>,
        saved: HomeLayout? = hook.store.pageLayouts.value[page],
    ): HomeLayout {
        val lists = pageLists()
        return saved?.reconciled(page, libs, lists, tiles(page)) ?: HomeLayout.defaults(page, libs, lists, tiles(page))
    }

    /** One row's name and titles (everything but Continue Watching and genres), fitted to [page]. */
    private suspend fun rowItems(
        spec: HomeRowSpec,
        page: RowsPage,
        userId: UUID,
        libs: List<CinemaLibrary>,
        lists: Map<String, kotlinx.coroutines.Deferred<Pair<String, List<BaseItemDto>>>>,
    ): Pair<String, List<BaseItemDto>> {
        fun newest(
            kinds: List<BaseItemKind>,
            sort: ItemSortBy,
            limit: Int = 24,
        ) = GetItemsRequest(userId = userId, includeItemTypes = kinds, recursive = true, sortBy = listOf(sort), sortOrder = listOf(org.jellyfin.sdk.model.api.SortOrder.DESCENDING), limit = limit, fields = fields, enableImageTypes = images, imageTypeLimit = 1)
        val name = spec.defaultName(libs, emptyMap())
        val (shown, items) =
            when (spec.type) {
                HomeRowType.COLLECTION -> lists[spec.ref]?.await() ?: ("" to emptyList())
                // Every movie library at once; a title in both the HD and 4K library shows once
                HomeRowType.RECENT_MOVIES ->
                    name to
                        safe { api.userLibraryApi.getLatestMedia(GetLatestMediaRequest(userId = userId, includeItemTypes = listOf(BaseItemKind.MOVIE), limit = 40, fields = fields, enableImageTypes = images, imageTypeLimit = 1, groupItems = true)).content }
                            .distinctBy { tmdbOf(it)?.toString() ?: (it.name.orEmpty().lowercase() + it.productionYear) }
                            .take(24)
                // Shows by when they last got something new: one request for every TV library
                // (Silo answers no episodes to a library-wide episode query)
                HomeRowType.NEW_EPISODES ->
                    name to safe { api.itemsApi.getItems(newest(listOf(BaseItemKind.SERIES), ItemSortBy.DATE_LAST_CONTENT_ADDED, 40)).content.items }
                        .distinctBy { tmdbOf(it)?.toString() ?: it.name.orEmpty().lowercase() }
                        .take(24)
                HomeRowType.NEW_RELEASE_MOVIES -> name to safe { api.itemsApi.getItems(newest(listOf(BaseItemKind.MOVIE), ItemSortBy.PREMIERE_DATE)).content.items }
                HomeRowType.NEW_RELEASE_SHOWS -> name to safe { api.itemsApi.getItems(newest(listOf(BaseItemKind.SERIES), ItemSortBy.PREMIERE_DATE)).content.items }
                HomeRowType.NEW_ARRIVALS -> name to safe { api.itemsApi.getItems(newest(kinds(page), ItemSortBy.DATE_CREATED)).content.items }
                HomeRowType.JUST_AIRED ->
                    name to
                        safe { api.itemsApi.getItems(newest(listOf(BaseItemKind.EPISODE), ItemSortBy.PREMIERE_DATE, 60)).content.items }
                            .distinctBy { it.seriesId ?: it.id }
                            .take(24)
                            .also { fillSeriesTmdb(it, userId) }
                HomeRowType.MY_LIST ->
                    name to
                        safe {
                            api.itemsApi.getItems(
                                GetItemsRequest(userId = userId, isFavorite = true, includeItemTypes = kinds(page), recursive = true, sortBy = listOf(ItemSortBy.DATE_CREATED), sortOrder = listOf(org.jellyfin.sdk.model.api.SortOrder.DESCENDING), limit = 24, fields = fields, enableImageTypes = images, imageTypeLimit = 1),
                            ).content.items
                        }.filter { it.userData?.isFavorite != false }
                HomeRowType.LIBRARY -> {
                    val lib = libs.firstOrNull { it.id.toString() == spec.ref } ?: return "" to emptyList()
                    name to safe { api.userLibraryApi.getLatestMedia(GetLatestMediaRequest(userId = userId, parentId = lib.id, limit = 24, fields = fields, enableImageTypes = images, imageTypeLimit = 1, groupItems = true)).content }
                }
                HomeRowType.CONTINUE_WATCHING, HomeRowType.GENRE, HomeRowType.SERVICES, HomeRowType.GENRES, HomeRowType.DECADES -> "" to emptyList()
            }
        return shown to items.filter { fits(page, it) }
    }

    /** A Trakt/MDBList row; lists named like a chart ("Top …") become Top 10 rows. */
    private fun listRow(
        name: String,
        items: List<BaseItemDto>,
        list: com.wholphinplus.sources.HomeCollection? = null,
    ): CinemaRow {
        if (!isTopList(name) || items.size < 3) return CinemaRow(name, items.map(::toItem))
        // A Top 10 keeps the chart's own numbers: a title not in the library leaves its number
        // out rather than moving the rest up (rows matched before ranks were kept count 1, 2, 3)
        val ranks = list?.rankById().orEmpty()
        val top = items.map { it to ranks[it.id.toString().replace("-", "").lowercase()] }.filter { (_, r) -> r == null || r <= 10 }.take(10)
        return CinemaRow(name, top.map { (d, r) -> toItem(d).copy(rank = r) }, ranked = true)
    }

    /**
     * Gives every card of a Top 10 title its place, wherever it shows up on the page, so the
     * billboard can say "#2 in Movies This Week" for it.
     */
    private fun ranked(raw: CinemaHomeData): CinemaHomeData {
        val data = unique(raw)
        val ranks = HashMap<UUID, Pair<Int, String>>()
        data.rows.filter { it.ranked }.forEach { row ->
            row.items.forEachIndexed { i, item -> ranks.putIfAbsent(item.detailsId, (item.rank ?: (i + 1)) to rankLabel(row)) }
        }
        if (ranks.isEmpty()) return data

        fun mark(item: CinemaItem) = ranks[item.detailsId]?.let { (n, label) -> item.copy(rank = n, rankLabel = label) } ?: item
        return data.copy(featured = data.featured.map(::mark), rows = data.rows.map { r -> r.copy(items = r.items.map(::mark)) })
    }

    /**
     * Rows and cards are list keys on screen, and a repeated key crashes the page. Two lists can
     * share a name (or match a genre row's), and a list can name a title twice: keep the first.
     */
    private fun unique(data: CinemaHomeData): CinemaHomeData =
        data.copy(
            rows =
                data.rows
                    .distinctBy { it.title }
                    .map { r -> if (r.items.distinctBy { it.key }.size == r.items.size) r else r.copy(items = r.items.distinctBy { it.key }) }
                    .filter { it.items.isNotEmpty() || it.tiles.isNotEmpty() },
        )

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

    /** A mix of the library's posters, for the welcome's backdrop once signed in. */
    suspend fun posterWall(): List<String> =
        withContext(Dispatchers.IO) {
            val userId = userId()
            safe {
                api.itemsApi.getItems(
                    GetItemsRequest(userId = userId, includeItemTypes = listOf(BaseItemKind.MOVIE, BaseItemKind.SERIES), recursive = true, sortBy = listOf(ItemSortBy.RANDOM), limit = 36, enableImageTypes = listOf(ImageType.PRIMARY), imageTypeLimit = 1),
                ).content.items
            }.mapNotNull { d -> d.imageTags?.get(ImageType.PRIMARY)?.let { image(d.id, "Primary", it, 360) } }
        }

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
            }.filter { it.userData?.isFavorite != false }.map(::toItem).distinctBy { it.key }
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
                // Nothing in progress: start from Season 1, Episode 1. Asked for the first real
                // season (the list puts Specials last); unscoped, Specials sort first and Play
                // offered "S0:E7"
                val nextEp =
                    next.await() ?: if (series) {
                        val firstSeason = seasons.await().firstOrNull()
                        safe {
                            api.tvShowsApi.getEpisodes(org.jellyfin.sdk.model.api.request.GetEpisodesRequest(seriesId = id, userId = userId, seasonId = firstSeason?.id, limit = 1, isMissing = false)).content.items
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
                    // Filled by moreLikeThis() once the page is up
                    similar = emptyList(),
                    startSeason = nextEp?.parentIndexNumber,
                )
            }
        }

    /**
     * More Like This: TMDB's recommendations for the title, as they're found in the library,
     * best first. The server's own Similar list is only a fallback: some servers (Silo) answer
     * it with random titles of the same genre. Silo ignores provider-id filters, so each title
     * is one name search confirmed by its TMDB id (or exact name and year); found titles and
     * misses are remembered for the session.
     */
    suspend fun moreLikeThis(
        item: CinemaItem,
        tmdb: com.wholphinplus.sources.core.TmdbClient?,
    ): List<CinemaItem> =
        withContext(Dispatchers.IO) {
            val userId = userId()
            val recommended =
                item.tmdbId?.takeIf { tmdb?.available == true }?.let { id ->
                    val type = if (item.tmdbTv) com.wholphinplus.sources.core.TmdbType.TV else com.wholphinplus.sources.core.TmdbType.MOVIE
                    runCatching { tmdb!!.recommendations(type, id) }
                        .onFailure { Timber.w(it, "TMDB recommendations failed for %s", item.title) }
                        .getOrDefault(emptyList())
                        .take(RECOMMENDATIONS)
                }.orEmpty()
            val gate = kotlinx.coroutines.sync.Semaphore(4)
            val found =
                coroutineScope {
                    recommended.map { r -> async { gate.withPermit { inLibrary(r, userId) } } }.awaitAll()
                }.filterNotNull().filter { it.detailsId != item.detailsId }.distinctBy { it.key }
            Timber.i("More Like This for %s: %d of %d recommendations in the library", item.title, found.size, recommended.size)
            if (found.size >= 3) {
                found
            } else {
                safe { api.libraryApi.getSimilarItems(itemId = item.detailsId, userId = userId, limit = 16, fields = fields).content.items }
                    .map(::toItem)
                    .filter { it.backdropUrl != null || it.cardUrl != null }
                    .distinctBy { it.key }
            }
        }

    /** One TMDB title in the library, or null. Remembered (misses too) for the session. */
    private suspend fun inLibrary(
        r: com.wholphinplus.sources.core.TmdbItem,
        userId: UUID,
    ): CinemaItem? {
        libraryMatches[r.key]?.let { return it.item }
        val tv = r.type == com.wholphinplus.sources.core.TmdbType.TV
        val hits =
            try {
                api.itemsApi.getItems(
                    GetItemsRequest(
                        userId = userId,
                        searchTerm = r.title,
                        includeItemTypes = listOf(if (tv) BaseItemKind.SERIES else BaseItemKind.MOVIE),
                        recursive = true,
                        limit = 10,
                        fields = fields,
                        enableImageTypes = images,
                        imageTypeLimit = 1,
                    ),
                ).content.items
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                return null // not remembered: the server didn't answer
            }
        val match =
            hits.firstOrNull { tmdbOf(it) == r.id }
                ?: hits.firstOrNull { tmdbOf(it) == null && it.name.equals(r.title, true) && (r.year == null || it.productionYear == r.year) }
        val card = match?.let(::toItem)?.takeIf { it.backdropUrl != null || it.cardUrl != null }
        libraryMatches[r.key] = LibraryMatch(card)
        return card
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
        val cleanCard =
            when {
                !d.backdropImageTags.isNullOrEmpty() -> image(d.id, "Backdrop/0", d.backdropImageTags!!.first(), 480)
                d.parentBackdropItemId != null && !d.parentBackdropImageTags.isNullOrEmpty() ->
                    image(d.parentBackdropItemId!!, "Backdrop/0", d.parentBackdropImageTags!!.first(), 480)
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
            cleanCardUrl = cleanCard,
            cardUrl = thumb ?: backdrop,
            cardHasTitleArt = thumb != null,
            logoUrl = logo,
            badge = badge,
            resumeMs = positionTicks / 10_000L,
            progress = progress,
            tmdbId = if (episode) d.seriesId?.let { seriesTmdb[it] } else tmdbOf(d),
            tmdbTv = episode || d.type == BaseItemKind.SERIES,
            posterUrl = poster,
            captionDate =
                if (episode) {
                    "S${d.parentIndexNumber ?: 0}:E${d.indexNumber ?: 0}"
                } else {
                    d.premiereDate?.let { CAPTION_DATE.format(it) } ?: year
                },
            captionLength = if (d.type == BaseItemKind.SERIES) seasons else runtime,
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
        /** TMDB titles looked up in the library for More Like This (empty = not there). */
        private val libraryMatches = java.util.concurrent.ConcurrentHashMap<String, LibraryMatch>()

        /** How many TMDB recommendations are looked for in the library. */
        private const val RECOMMENDATIONS = 20

        /** Lists named like a chart: "Top 10 …", "Top Watched Movies Of The Week". */
        fun isTopList(name: String): Boolean = TOP_LIST.containsMatchIn(name)

        // Charts get the numbered Top 10 row: "Top 10 …", "Top … of the Week". "Top 250", "Top
        // Rated … of All Time" and "Top Movies" are long lists, shown as normal rows
        private val TOP_LIST = Regex("""\btop\s*(10|ten)\b|\btop\b.*\bweek\b""", RegexOption.IGNORE_CASE)

        /** "Apr 24, 2019", in the device's language. */
        private val CAPTION_DATE = java.time.format.DateTimeFormatter.ofPattern("MMM d, yyyy")

        /** Home rows below Continue Watching that are on screen when the page opens. */
        private const val ON_SCREEN_ROWS = 2

        /** Home rows whose request doesn't depend on the library list. */
        private val LIBRARY_FREE = listOf(HomeRowType.RECENT_MOVIES, HomeRowType.NEW_EPISODES, HomeRowType.NEW_RELEASE_MOVIES, HomeRowType.MY_LIST, HomeRowType.NEW_RELEASE_SHOWS, HomeRowType.NEW_ARRIVALS, HomeRowType.JUST_AIRED)

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

/** A TMDB title's library card, or null when it isn't in the library. */
private class LibraryMatch(
    val item: CinemaItem?,
)

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
