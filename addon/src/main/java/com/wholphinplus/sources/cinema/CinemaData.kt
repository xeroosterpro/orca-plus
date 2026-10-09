@file:UseSerializers(UUIDSerializer::class)

package com.wholphinplus.sources.cinema

import com.wholphinplus.sources.HomeCollections
import com.wholphinplus.sources.SourceHook
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable
import kotlinx.serialization.UseSerializers
import org.jellyfin.sdk.api.client.extensions.get
import org.jellyfin.sdk.api.client.extensions.itemsApi
import org.jellyfin.sdk.api.client.extensions.libraryApi
import org.jellyfin.sdk.api.client.extensions.playStateApi
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
import org.jellyfin.sdk.model.serializer.UUIDSerializer
import timber.log.Timber
import java.time.LocalDateTime
import java.util.UUID

/** One title as Cinema mode shows it. Episodes carry their series' art. */
@androidx.compose.runtime.Immutable
@Serializable
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
@Serializable
data class CinemaRow(
    val title: String,
    val items: List<CinemaItem>,
    /** A Top 10 row: big numbers beside portrait posters. */
    val ranked: Boolean = false,
    /** A row of Services or Genres tiles instead of titles. */
    val tiles: List<PageTile> = emptyList(),
    /** Where more of its titles come from (null: the row is all there is: Top 10s, Continue Watching). */
    val source: RowSource? = null,
    /** The Continue Watching row (it can be renamed): refreshed in place after a play. */
    val continueWatching: Boolean = false,
    /** How many titles the whole row has, when known (a list: its titles in the library). */
    val total: Int? = null,
    /**
     * Still on its way: shown as its name and placeholder cards of the real size while the page's
     * first rows are up, so it fills in where it stands instead of pushing the rows below down.
     */
    val loading: Boolean = false,
    /**
     * Its request failed this load (after a second try): stands for the row until the copy shown
     * before takes its place ([withPreviousRows]); never drawn.
     */
    val failed: Boolean = false,
)

/**
 * Where a row's titles come from, so it can keep loading and be shuffled. [seed]: a shuffled
 * order (null: the row's own); [next]: where the next page starts.
 */
@androidx.compose.runtime.Immutable
@Serializable
data class RowSource(
    val page: RowsPage,
    val spec: HomeRowSpec,
    val seed: Long? = null,
    val next: Int = 0,
) {
    /** Its "always shuffle" switch, per page. */
    val lockKey: String get() = page.name + "|" + spec.key
}

/** One more page of a row; [end] once its source has nothing more. */
class RowPage(
    val items: List<CinemaItem>,
    val next: Int,
    val end: Boolean,
)

/** A service's, genre's or decade's tile; it opens that page (with its Movies or Shows side first). */
@androidx.compose.runtime.Immutable
@Serializable
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

@Serializable
data class CinemaLibrary(
    val id: UUID,
    val name: String,
    val kind: BaseItemKind,
    val collectionType: CollectionType?,
)

@androidx.compose.runtime.Immutable
@Serializable
data class CinemaHomeData(
    val featured: List<CinemaItem>,
    val rows: List<CinemaRow>,
    val shows: CinemaLibrary?,
    val movies: CinemaLibrary?,
    /**
     * Rows whose request failed in this load (shown as they were before, or left out): such a
     * page isn't saved on the device or counted as fresh, so the next visit asks again.
     */
    val failed: Int = 0,
) {
    /** More rows, or more titles in them, than [other] (null = nothing shown yet). Placeholders don't count. */
    fun richerThan(other: CinemaHomeData?): Boolean =
        other == null || rows.count { !it.loading } > other.rows.count { !it.loading } || rows.sumOf { it.items.size + it.tiles.size } > other.rows.sumOf { it.items.size + it.tiles.size }
}

/**
 * [rows] with each row whose request failed put back as it was on [previous] pages (the one on
 * screen, the last load), so a server blip doesn't take a row away (and move the rows below it)
 * until the next load. A failed row never shown before leaves this load.
 */
internal fun withPreviousRows(
    rows: List<CinemaRow>,
    previous: List<CinemaHomeData>,
): List<CinemaRow> =
    rows.mapNotNull { row ->
        if (!row.failed) {
            row
        } else {
            previous.firstNotNullOfOrNull { p -> p.rows.firstOrNull { it.title == row.title && !it.loading && !it.failed && (it.items.isNotEmpty() || it.tiles.isNotEmpty()) } }
        }
    }

/**
 * The fresh [page][this] in place of [shown] (a page saved on the device, or one the background
 * refresh gave more rows): it keeps the shown billboard and the titles of rows that are random
 * each load (genre rows, shuffled rows), so nothing on screen jumps under the remote; Continue
 * Watching, lists and new arrivals take the fresh titles.
 */
/**
 * This page with [fresh]'s Continue Watching in its place, or null when it's the same (nothing
 * else on the page moves). The row is found by its kind, so a renamed one counts too.
 */
internal fun CinemaHomeData.withContinueWatching(fresh: CinemaHomeData): CinemaHomeData? {
    val i = rows.indexOfFirst { it.continueWatching }
    val row = fresh.rows.firstOrNull { it.continueWatching } ?: return null
    if (i < 0 || rows[i].loading || rows[i].items == row.items) return null
    return copy(rows = rows.toMutableList().also { it[i] = row })
}

internal fun CinemaHomeData.over(shown: CinemaHomeData): CinemaHomeData {
    val before = shown.rows.associateBy { it.title }
    // The same billboard titles, in their fresh copies: a show's billboard kept saying S1:E1
    // while its Continue Watching card below said S2:E14
    val fresh = HashMap<UUID, CinemaItem>()
    rows.forEach { row -> row.items.forEach { fresh.putIfAbsent(it.detailsId, it) } }
    featured.forEach { fresh.putIfAbsent(it.detailsId, it) }
    return copy(
        featured = shown.featured.map { fresh[it.detailsId] ?: it }.ifEmpty { featured },
        rows =
            rows.map { row ->
                val old = before[row.title]
                val random = row.source?.seed != null || row.source?.spec?.type == HomeRowType.GENRE
                if (old != null && random && old.items.isNotEmpty()) row.copy(items = old.items, source = old.source) else row
            },
    )
}

/**
 * Every card of a Top 10 title gets its place (the first Top 10 row it's in), wherever it shows on
 * the page, so the billboard can say "#2 in Movies This Week". A Top 10 row keeps its own places:
 * the place from another chart used to overwrite them (Hulu's row read 2, 3, 4, 2, 4 with
 * Disney+'s numbers on two of its titles).
 */
internal fun withRanks(
    data: CinemaHomeData,
    label: (CinemaRow) -> String,
): CinemaHomeData {
    val ranks = HashMap<UUID, Pair<Int, String>>()
    data.rows.filter { it.ranked }.forEach { row ->
        row.items.forEachIndexed { i, item -> ranks.putIfAbsent(item.detailsId, (item.rank ?: (i + 1)) to label(row)) }
    }
    if (ranks.isEmpty()) return data

    fun mark(item: CinemaItem) = ranks[item.detailsId]?.let { (n, l) -> item.copy(rank = n, rankLabel = l) } ?: item
    return data.copy(
        featured = data.featured.map(::mark),
        rows =
            data.rows.map { r ->
                if (r.ranked) {
                    val l = label(r)
                    r.copy(items = r.items.mapIndexed { i, item -> item.copy(rank = item.rank ?: (i + 1), rankLabel = l) })
                } else {
                    r.copy(items = r.items.map(::mark))
                }
            },
    )
}

/** Marks a row's requests: [CinemaRepository.safe] notes here when one gave up, so "failed" isn't taken for "empty". */
private class RowTrack : kotlin.coroutines.AbstractCoroutineContextElement(Key) {
    @Volatile var failed = false

    companion object Key : kotlin.coroutines.CoroutineContext.Key<RowTrack>
}

/** Loads Cinema mode's home from the main Jellyfin server (through Wholphin's own connection). */
internal class CinemaRepository(
    private val hook: SourceHook,
    private val collections: HomeCollections,
    /** TMDB, for Because You Watched (null: the row stays empty). */
    private val tmdb: com.wholphinplus.sources.core.TmdbClient? = null,
) {
    private val api get() = hook.jellyfin

    /** Whether TMDB can be asked (a key, or the cloud's proxy): cast photos, actors' pages. */
    val hasTmdb: Boolean get() = tmdb?.available == true

    /** Bumped by each play recorded on this TV ([ProgressOverlay.changes]). */
    val plays: Long get() = hook.overlay.changes.value

    /** When [item] (or, for an episode, anything of its show) was last played: the server's date or this TV's. */
    private fun watchedAt(item: BaseItemDto): Long =
        maxOf(
            item.userData?.lastPlayedDate?.toInstant(java.time.ZoneOffset.UTC)?.toEpochMilli() ?: 0L,
            hook.overlay.lastPlayed(item.id.toString()) ?: 0L,
            item.seriesId?.let { hook.overlay.seriesLastPlayed(it.toString()) } ?: 0L,
        )

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
        /** Pages shown before (on screen, the last load): a row whose request fails keeps its copy from there. */
        previous: List<CinemaHomeData> = emptyList(),
    ): CinemaHomeData = timed(page.name.lowercase(), onFirst) { first -> loadPage(page, first, previous) }

    /** [HomeCollections.changed] now: which lists a page loaded now is built from. */
    val listsGeneration: Int get() = collections.changed.value

    /** The lists as [page] sees them: Top Streaming charts know whether they're shows or movies. */
    fun pageLists(): List<PageList> = collections.lists.value.filterNot { it.hidden }.map { PageList(it.id, it.name, collections.chartSeries(it), it.pages, it.order) }

    /**
     * The lists (by id) [page] shows or would show once they have titles: its saved rows that are
     * on, else the lists it starts with. A change to any other list can't alter the page.
     */
    fun pageListIds(page: RowsPage): Set<String> {
        val saved = hook.store.pageLayouts.value[page]
        val ids = HashSet<String>()
        saved?.rows?.forEach { if (it.type == HomeRowType.COLLECTION && it.on) ids += it.ref }
        val all = pageLists()
        collections.lists.value.forEach { c ->
            val pl = all.firstOrNull { it.id == c.id } ?: return@forEach
            if ((page != RowsPage.HOME || c.showOnHome || collections.isChart(c)) && page.offers(pl) && (saved == null && page.listStartsOn(pl))) ids += c.id
        }
        return ids
    }

    /** A Services or Genres page by id (null when the cloud no longer offers it). */
    fun cloudPage(id: String): com.wholphinplus.sources.core.CloudPage? = collections.cloudPages.value.pages.firstOrNull { it.id == id }

    /**
     * Where the cloud places the rows the TV makes itself on [page] (their lineup positions): the
     * tiles, Because You Watched and the every-library rows.
     */
    fun tiles(page: RowsPage): Map<HomeRowType, Int> =
        collections.cloudPages.value.tiles[page.name].orEmpty().mapNotNull { (k, at) ->
            when (k) {
                "SERVICES" -> HomeRowType.SERVICES to at
                "GENRES" -> HomeRowType.GENRES to at
                "DECADES" -> HomeRowType.DECADES to at
                "BECAUSE" -> HomeRowType.BECAUSE_YOU_WATCHED to at
                "RECENT" -> HomeRowType.RECENT_MOVIES to at
                "EPISODES" -> HomeRowType.NEW_EPISODES to at
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
        onFirst: (CinemaHomeData) -> Unit = {},
    ): CinemaHomeData =
        withContext(Dispatchers.IO) {
            val userId = hook.mainConnection()?.userId?.let { runCatching { UUID.fromString(dash(it)) }.getOrNull() } ?: error("Not signed in")
            val p = collections.cloudPages.value.pages.firstOrNull { it.id == id } ?: error("This page is no longer offered")
            val side = if (series) RowsPage.SHOWS else RowsPage.MOVIES
            val locks = hook.store.shuffleLocks.value
            // A page has up to 13 rows a side: it shows once the first few are in, the rest join
            // as they come (rows with too few titles in the library aren't asked for at all)
            fun built(got: List<Pair<Triple<String, com.wholphinplus.sources.HomeCollection, List<BaseItemDto>>, RowSource>>): CinemaHomeData {
                val rows =
                    got.map { (g, source) ->
                        val (name, c, items) = g
                        val row = listRow(name, items.filter { fits(side, it) }, c)
                        if (row.ranked) row else row.copy(source = source, total = c.itemIds.size)
                    }.filter { it.items.size >= minFor(it.title) }
                val featured = rows.flatMap { it.items.take(4) }.filter { it.backdropUrl != null && it.overview.isNotBlank() }.distinctBy { it.detailsId }.take(6)
                return ranked(CinemaHomeData(featured, rows, null, null))
            }
            coroutineScope {
                val pending =
                    (if (series) p.shows else p.movies).mapNotNull { r -> collections.forChart(r.chart)?.takeIf { it.itemIds.size >= minFor(r.name) }?.let { c -> r.name to c } }.map { (name, c) ->
                        async {
                            // Rows here can keep loading and be shuffled like a tab's (Top 10s excepted)
                            val spec = HomeRowSpec(HomeRowType.COLLECTION, ref = c.id)
                            val seed = kotlin.random.Random.nextLong().takeIf { !isTopList(name) && RowSource(side, spec).lockKey in locks }
                            val items = if (seed != null) fetch(spec, side, userId, 0, seed).first else safe { api.itemsApi.getItems(GetItemsRequest(userId = userId, tags = listOf(c.tag), recursive = true, limit = 40, fields = fields, enableImageTypes = images, imageTypeLimit = 1)).content.items }
                            Triple(name, c, items) to RowSource(side, spec, seed, 40)
                        }
                    }
                if (pending.size > PAGE_FIRST_ROWS) onFirst(built(pending.take(PAGE_FIRST_ROWS).awaitAll()))
                built(pending.awaitAll())
            }
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
        previous: List<CinemaHomeData>,
    ): CinemaHomeData =
        withContext(Dispatchers.IO) {
            val userId = hook.mainConnection()?.userId?.let { runCatching { UUID.fromString(dash(it)) }.getOrNull() }
                ?: error("Not signed in")
            val saved = hook.store.pageLayouts.value[page]
            val allLists = pageLists()
            // Everything that doesn't need the library list starts at once. The slow genre pool
            // is sent last so the quick rows aren't queued behind it (the app's HTTP client runs
            // a few requests per server at a time).
            // Rows whose request failed (after its second try), by what started them: a row's spec,
            // a row type (rows started before the libraries were known), "list:<id>", or [POOL_KEY]
            val failed = java.util.concurrent.ConcurrentHashMap.newKeySet<Any>()
            coroutineScope {
                // Without the library list the page can't be built as arranged (library rows and
                // Home's defaults depend on it): no answer fails the load, which is tried again,
                // instead of a page missing rows replacing the one on screen
                val views = async { retried { api.userViewsApi.getUserViews(userId = userId).content.items } }
                val resume =
                    async {
                        if (!page.hasContinueWatching) emptyList() else tracked(failed, HomeRowType.CONTINUE_WATCHING) { safe { api.itemsApi.getResumeItems(GetResumeItemsRequest(userId = userId, limit = 24, fields = fields, mediaTypes = listOf(MediaType.VIDEO), enableImageTypes = images, imageTypeLimit = 1)).content.items } }
                    }
                val nextUp =
                    async {
                        if (!page.hasContinueWatching || page.series == false) emptyList() else tracked(failed, HomeRowType.CONTINUE_WATCHING) { safe { api.tvShowsApi.getNextUp(GetNextUpRequest(userId = userId, limit = 24, fields = fields, enableImageTypes = images, imageTypeLimit = 1)).content.items } }
                    }
                // Continue Watching's name as shown before: the greeting's request can fail or be
                // late, and a row that changes its name is a different row (it can't keep its copy)
                val greetedBefore = previous.firstNotNullOfOrNull { p -> p.rows.firstOrNull { it.title.startsWith(CONTINUE) }?.title }
                val greeting = async { if (page.hasContinueWatching) continueTitle(userId) ?: greetedBefore ?: CONTINUE else "" }
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
                        c.id to async { c.name to tracked(failed, "list:" + c.id) { safe { api.itemsApi.getItems(GetItemsRequest(userId = userId, tags = listOf(c.tag), recursive = true, limit = 40, fields = fields, enableImageTypes = images, imageTypeLimit = 1)).content.items } } }
                    }
                // Rows that don't depend on which libraries there are start now, before the
                // library list is back (the defaults include them; a saved page if it has them on)
                // (Home's defaults depend on the libraries, so before its first save all start)
                val guess = saved ?: HomeLayout.defaults(page, emptyList(), allLists, tiles(page)).takeIf { page != RowsPage.HOME }
                // Rows set to always shuffle (Shuffle → lock on the row) load in a new order each time
                val locks = hook.store.shuffleLocks.value
                fun locked(spec: HomeRowSpec) = spec.type in SHUFFLED_ON_LOAD && RowSource(page, spec).lockKey in locks
                val early =
                    LIBRARY_FREE.filter { t -> page.offers(t) && !locked(HomeRowSpec(t)) && (guess?.rows?.any { it.type == t && it.on } ?: true) }.associateWith { t ->
                        async { tracked(failed, t) { rowItems(HomeRowSpec(t), page, userId, emptyList(), emptyMap()) } }
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
                // Because You Watched starts from Continue Watching's first title
                val watched =
                    async {
                        // Most recently watched first across both lists: a show whose episode was just
                        // finished (its next episode is in Next Up) went to the end of the row
                        val all = (resume.await() + nextUp.await()).filter { fits(page, it) }.distinctBy { it.seriesId ?: it.id }.sortedByDescending(::watchedAt)
                        // Kids: kids' ratings only (an episode goes by its show's rating)
                        if (page != RowsPage.KIDS) {
                            all
                        } else {
                            fillSeriesTmdb(all, userId)
                            all.filter { (it.officialRating ?: it.seriesId?.let { s -> seriesRating[s] }) in KID_RATINGS }
                        }
                    }
                var becauseRow: Pair<String, List<CinemaItem>>? = null
                val because = if (on.any { it.type == HomeRowType.BECAUSE_YOU_WATCHED }) async { becauseRow = tracked(failed, HomeRowType.BECAUSE_YOU_WATCHED) { because(watched.await(), page, userId) } } else null
                val seeds = on.filter(::locked).associateWith { kotlin.random.Random.nextLong() }
                val rows =
                    on.filter { it.type != HomeRowType.GENRE && it.type != HomeRowType.BECAUSE_YOU_WATCHED }.map { spec ->
                        val seed = seeds[spec]
                        spec to (
                            if (seed != null) {
                                async {
                                    val name = if (spec.type == HomeRowType.COLLECTION) collections.lists.value.firstOrNull { it.id == spec.ref }?.name.orEmpty() else spec.defaultName(libs, emptyMap())
                                    name to tracked(failed, spec) { fetch(spec, page, userId, 0, seed).first.filter { fits(page, it) } }
                                }
                            } else {
                                early[spec.type] ?: async { tracked(failed, spec) { rowItems(spec, page, userId, libs, lists) } }
                            }
                        )
                    }
                // Sent last, so the quick rows aren't queued behind it. Some servers ignore the
                // genre filter, so genre rows are sorted out of one random pool
                val pool =
                    if (genres.isEmpty()) {
                        null
                    } else {
                        async {
                            tracked(failed, POOL_KEY) {
                                safe {
                                    api.itemsApi.getItems(
                                        GetItemsRequest(userId = userId, includeItemTypes = kinds(page), recursive = true, sortBy = listOf(ItemSortBy.RANDOM), limit = POOL, fields = fields, enableImageTypes = images, imageTypeLimit = 1),
                                    ).content.items
                                }
                            }
                        }
                    }
                // Episodes borrow their series' TMDB id for title art. Only art, so the page
                // doesn't wait for it: the first rows use what's known, the full page has it all
                val seriesArt = async { fillSeriesTmdb(watched.await(), userId) }
                // The screen shows once Continue Watching and the next rows (what's on screen) are
                // in; rows further down join as they arrive, like the genre rows. But not for long:
                // on a slow link (pictures share the server's one connection) a request can take the
                // SDK's full 30 s, and a first visit stayed blank that long. What isn't in by then
                // shows as a placeholder and fills in
                withTimeoutOrNull(FIRST_ROWS_WAIT_MS) {
                    watched.await()
                    greeting.await()
                    rows.filter { it.first.type != HomeRowType.CONTINUE_WATCHING }.take(ON_SCREEN_ROWS).forEach { it.second.await() }
                }
                // Null until in (the first screen may show without them)
                var cw = if (watched.isCompleted) watched.await() else null
                var title = if (greeting.isCompleted) greeting.await() else greetedBefore ?: CONTINUE
                var got = rows.filter { it.second.isCompleted }.map { (spec, items) -> spec to items.await() }

                fun failedRow(spec: HomeRowSpec) =
                    when (spec.type) {
                        HomeRowType.COLLECTION -> spec in failed || "list:" + spec.ref in failed
                        HomeRowType.GENRE -> POOL_KEY in failed
                        else -> spec in failed || spec.type in failed
                    }

                // [pending]: the page shown before every row is in; rows still coming hold their place.
                // A row whose request failed stands in for the copy shown before ([withPreviousRows])
                fun placeholder(
                    spec: HomeRowSpec,
                    name: String,
                ) = CinemaRow(spec.title.ifBlank { name }, emptyList(), ranked = spec.type == HomeRowType.COLLECTION && isTopList(name), loading = !failedRow(spec), failed = failedRow(spec))

                fun build(
                    genreRows: Map<String, CinemaRow>,
                    pending: Boolean = false,
                ) = on.mapNotNull { spec ->
                        when (spec.type) {
                            HomeRowType.CONTINUE_WATCHING ->
                                if (failedRow(spec) || cw == null) placeholder(spec, title).takeIf { pending || it.failed } else CinemaRow(spec.title.ifBlank { title }, cw.orEmpty().map(::toItem), continueWatching = true)
                            HomeRowType.SERVICES, HomeRowType.GENRES, HomeRowType.DECADES -> CinemaRow(spec.title.ifBlank { spec.defaultName(emptyList(), emptyMap()) }, emptyList(), tiles = tileRow(spec.type, page))
                            HomeRowType.GENRE ->
                                genreRows[spec.ref]?.let { r -> (spec.title.takeIf { it.isNotBlank() }?.let { r.copy(title = it) } ?: r).copy(source = RowSource(page, spec)) }
                                    ?: if (pending || failedRow(spec)) placeholder(spec, spec.ref.orEmpty()) else null
                            // Joins once found (it waits on TMDB and a search per title), like the genre rows
                            HomeRowType.BECAUSE_YOU_WATCHED ->
                                becauseRow?.takeUnless { failedRow(spec) }?.let { (name, items) -> CinemaRow(spec.title.ifBlank { name }, items) }
                                    ?: cw?.firstOrNull()?.takeIf { (pending || failedRow(spec)) && because != null && tmdb?.available == true }?.let { placeholder(spec, "Because You Watched ${toItem(it).title}") }
                            else ->
                                got.firstOrNull { it.first == spec && !failedRow(spec) }?.second?.let { (name, items) ->
                                    val shown = spec.title.ifBlank { name }
                                    val row = if (spec.type == HomeRowType.COLLECTION) listRow(shown, items, collections.lists.value.firstOrNull { it.id == spec.ref }) else CinemaRow(shown, items.map(::toItem))
                                    if (row.ranked || spec.type !in PAGED) row else row.copy(source = RowSource(page, spec, seeds[spec], firstPage(spec, seeds[spec] != null)), total = listTotal(spec))
                                } ?: if (pending || failedRow(spec)) {
                                    val name = if (spec.type == HomeRowType.COLLECTION) collections.lists.value.firstOrNull { it.id == spec.ref }?.name.orEmpty() else spec.defaultName(libs, emptyMap())
                                    placeholder(spec, name).takeIf { it.title.isNotBlank() }
                                } else {
                                    null
                                }
                        }
                    }.filter { it.items.isNotEmpty() || it.tiles.isNotEmpty() || it.loading || it.failed }
                val quickRows = withPreviousRows(build(emptyMap(), pending = true), previous)

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
                // What the rest of the page waits on, for performance checks
                val t0 = System.currentTimeMillis()
                cw = watched.await()
                title = greeting.await()
                got = rows.map { (spec, items) -> spec to items.await() }
                val tRows = System.currentTimeMillis()
                because?.await()
                val tBecause = System.currentTimeMillis()
                seriesArt.await()
                val tArt = System.currentTimeMillis()
                val picked = genres.mapNotNull { g -> page.genres.firstOrNull { it.second == g.ref } }
                val genreRows = pool?.await()?.let { genreRows(it, picked, picked.size) }.orEmpty().associateBy { it.title }
                val tPool = System.currentTimeMillis()
                Timber.i("Cinema load %s: after first rows, rows +%d ms, because +%d, series art +%d, genre pool +%d", page.name.lowercase(), tRows - t0, tBecause - tRows, tArt - tBecause, tPool - tArt)
                val built = build(genreRows)
                val failures = built.count { it.failed }
                // Not one row came back (the server down, or its connection dead after the TV slept):
                // a failed load, tried again, rather than a page of old copies or nothing saved as fresh
                if (failures > 0 && built.all { it.failed || it.tiles.isNotEmpty() }) throw java.io.IOException("Your server didn't answer")
                val all = withPreviousRows(built, previous)
                if (failures > 0) {
                    val lost = built.filter { it.failed }.map { it.title } - all.map { it.title }.toSet()
                    Timber.w("Cinema load %s: %d rows failed, %d shown as before, left out: %s", page.name.lowercase(), failures, failures - lost.size, lost)
                }
                // Nothing for the billboard in the first rows (they weren't in yet): pick from the whole page
                ranked(CinemaHomeData(featured.ifEmpty { pick(all) }, all, showLibs.firstOrNull(), movieLibs.firstOrNull(), failed = failures))
            }
        }

    /** The next page of a row, from [offset] in its source (filtered to its page). */
    suspend fun more(
        source: RowSource,
        offset: Int,
    ): RowPage =
        withContext(Dispatchers.IO) {
            val (items, end) = fetch(source.spec, source.page, userId(), offset, source.seed)
            RowPage(items.filter { fits(source.page, it) }.map(::toItem), offset + PAGE, end)
        }

    /** A list row's size: its titles in the library (null for other rows). */
    private fun listTotal(spec: HomeRowSpec): Int? =
        if (spec.type != HomeRowType.COLLECTION) null else collections.lists.value.firstOrNull { it.id == spec.ref }?.itemIds?.size

    /** Where a row's first load stopped in its source, so the next page carries on from there. */
    private fun firstPage(
        spec: HomeRowSpec,
        shuffled: Boolean,
    ) = when {
        spec.type == HomeRowType.COLLECTION -> 40
        shuffled -> PAGE
        spec.type == HomeRowType.NEW_EPISODES -> 40
        spec.type in setOf(HomeRowType.NEW_RELEASE_MOVIES, HomeRowType.NEW_RELEASE_SHOWS, HomeRowType.NEW_ARRIVALS, HomeRowType.MY_LIST) -> 24
        // Recently Added and library rows come from the server's Latest, grouped: start over in
        // date order (titles already shown are skipped)
        else -> 0
    }

    /**
     * A page of [spec]'s titles from [offset] and whether that's the last. With a [seed] the
     * order is shuffled: a list's own titles in a fixed random order, other rows random pages
     * (they never say "last"; the row stops when pages stop bringing anything new).
     */
    private suspend fun fetch(
        spec: HomeRowSpec,
        page: RowsPage,
        userId: UUID,
        offset: Int,
        seed: Long?,
    ): Pair<List<BaseItemDto>, Boolean> {
        fun request(
            kinds: List<BaseItemKind>?,
            sort: ItemSortBy,
            parentId: UUID? = null,
            favorite: Boolean? = null,
            limit: Int = PAGE,
        ) = GetItemsRequest(
            userId = userId,
            parentId = parentId,
            isFavorite = favorite,
            includeItemTypes = kinds,
            recursive = true,
            sortBy = listOf(if (seed != null) ItemSortBy.RANDOM else sort),
            sortOrder = listOf(org.jellyfin.sdk.model.api.SortOrder.DESCENDING),
            startIndex = if (seed != null) null else offset,
            limit = limit,
            fields = fields,
            enableImageTypes = images,
            imageTypeLimit = 1,
        )

        suspend fun get(r: GetItemsRequest): Pair<List<BaseItemDto>, Boolean> {
            val items = safe { api.itemsApi.getItems(r).content.items }
            return items to (seed == null && items.size < (r.limit ?: PAGE))
        }
        return when (spec.type) {
            HomeRowType.COLLECTION -> {
                val c = collections.lists.value.firstOrNull { it.id == spec.ref } ?: return emptyList<BaseItemDto>() to true
                val all = c.itemIds
                val items =
                    if (seed == null) {
                        // The list's own order, through the same tag the row was loaded with
                        safe { api.itemsApi.getItems(GetItemsRequest(userId = userId, tags = listOf(c.tag), recursive = true, startIndex = offset, limit = PAGE, fields = fields, enableImageTypes = images, imageTypeLimit = 1)).content.items }
                    } else {
                        cards(all.shuffled(kotlin.random.Random(seed)).drop(offset).take(PAGE), userId)
                    }
                items to (offset + PAGE >= all.size)
            }
            // The server ignores the genre filter (Silo): random titles, sorted out by genre here
            HomeRowType.GENRE -> {
                val names = page.genres.firstOrNull { it.second == spec.ref }?.first?.map { it.lowercase() }?.toSet() ?: return emptyList<BaseItemDto>() to true
                safe { api.itemsApi.getItems(request(kinds(page), ItemSortBy.RANDOM, limit = POOL / 2).copy(sortBy = listOf(ItemSortBy.RANDOM), startIndex = null)).content.items }
                    .filter { d -> d.genres.orEmpty().any { it.lowercase() in names } } to false
            }
            HomeRowType.RECENT_MOVIES -> get(request(listOf(BaseItemKind.MOVIE), ItemSortBy.DATE_CREATED))
            HomeRowType.NEW_EPISODES -> get(request(listOf(BaseItemKind.SERIES), ItemSortBy.DATE_LAST_CONTENT_ADDED))
            HomeRowType.NEW_RELEASE_MOVIES -> get(request(listOf(BaseItemKind.MOVIE), ItemSortBy.PREMIERE_DATE))
            HomeRowType.NEW_RELEASE_SHOWS -> get(request(listOf(BaseItemKind.SERIES), ItemSortBy.PREMIERE_DATE))
            HomeRowType.NEW_ARRIVALS -> get(request(kinds(page), ItemSortBy.DATE_CREATED))
            HomeRowType.MY_LIST -> get(request(kinds(page), ItemSortBy.DATE_CREATED, favorite = true)).let { (items, end) -> items.filter { it.userData?.isFavorite != false } to end }
            HomeRowType.LIBRARY -> {
                val lib = runCatching { UUID.fromString(spec.ref) }.getOrNull() ?: return emptyList<BaseItemDto>() to true
                get(request(listOf(BaseItemKind.MOVIE, BaseItemKind.SERIES), ItemSortBy.DATE_CREATED, parentId = lib))
            }
            else -> emptyList<BaseItemDto>() to true
        }
    }

    /**
     * Titles by id, as cards need them, in [ids]' order (one comma-joined value: see [itemsByIds]).
     * In small batches at once: Silo builds a batch's records one title at a time (~250 ms each on
     * the Shield), so 20 titles in one request took ~5 s.
     */
    private suspend fun cards(
        ids: List<String>,
        userId: UUID,
    ): List<BaseItemDto> {
        if (ids.isEmpty()) return emptyList()
        val got =
            coroutineScope {
                ids.chunked(ID_BATCH).map { chunk ->
                    async {
                        safe {
                            api.get<org.jellyfin.sdk.model.api.BaseItemDtoQueryResult>(
                                "/Items",
                                queryParameters =
                                    mapOf(
                                        "userId" to userId,
                                        "ids" to chunk.joinToString(",") { dash(it) },
                                        "fields" to "Overview,Genres,DateCreated,ChildCount,ProviderIds",
                                        "enableImageTypes" to "Primary,Backdrop,Thumb,Logo",
                                        "imageTypeLimit" to 1,
                                        "limit" to chunk.size,
                                    ),
                            ).content.items
                        }
                    }
                }.awaitAll().flatten()
            }
        val byId = got.associateBy { it.id.toString().replace("-", "").lowercase() }
        return ids.mapNotNull { byId[it.replace("-", "").lowercase()] }
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
                    HomeRowType.CONTINUE_WATCHING, HomeRowType.SERVICES, HomeRowType.GENRES, HomeRowType.DECADES, HomeRowType.BECAUSE_YOU_WATCHED -> false
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
                HomeRowType.CONTINUE_WATCHING, HomeRowType.GENRE, HomeRowType.SERVICES, HomeRowType.GENRES, HomeRowType.DECADES, HomeRowType.BECAUSE_YOU_WATCHED -> "" to emptyList()
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
        // The chart's top 10 in its own order, numbered 1, 2, 3 among the titles you have (owner,
        // 2026-10-09: a title not in the library left a gap, "1, 2, 4, 5" read as a mistake)
        val ranks = list?.rankById().orEmpty()
        val top =
            items
                .map { it to ranks[it.id.toString().replace("-", "").lowercase()] }
                .filter { (_, r) -> r == null || r <= 10 }
                .sortedBy { (_, r) -> r ?: Int.MAX_VALUE }
                .take(10)
        return CinemaRow(name, top.mapIndexed { i, (d, _) -> toItem(d).copy(rank = i + 1) }, ranked = true)
    }

    /**
     * Gives every card of a Top 10 title its place, wherever it shows up on the page, so the
     * billboard can say "#2 in Movies This Week" for it.
     */
    private fun ranked(raw: CinemaHomeData): CinemaHomeData = withRanks(unique(raw), ::rankLabel)

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
                    .filter { it.items.isNotEmpty() || it.tiles.isNotEmpty() || it.loading },
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
    /** Null when the server didn't say who's signed in. */
    private suspend fun continueTitle(userId: UUID): String? {
        val name =
            userName ?: runCatching { api.userApi.getCurrentUser().content.name }.getOrNull()?.also { userName = it } ?: return null
        // Account names like "abc123#Name": greet the part people read as the name
        val shown = name.substringAfterLast('#').trim()
        return if (shown.isBlank()) CONTINUE else "$CONTINUE for $shown"
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
            }.mapNotNull { d -> d.imageTags?.get(ImageType.PRIMARY)?.let { image(d.id, "Primary", it, POSTER_WIDTH) } }
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
        val got = itemsByIdsQuick(ids, userId, "ProviderIds")
        got.forEach { s ->
            tmdbOf(s)?.let { seriesTmdb[s.id] = it }
            s.officialRating?.let { seriesRating[s.id] = it }
        }
        val missing = ids - got.map { it.id }.toSet()
        missing.map { id ->
            async { runCatching { api.userLibraryApi.getItem(id, userId).content }.getOrNull() }
        }.awaitAll().filterNotNull().forEach { s ->
            tmdbOf(s)?.let { seriesTmdb[s.id] = it }
            s.officialRating?.let { seriesRating[s.id] = it }
        }
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

    /** [itemsByIds] in small batches at once (see [cards]); a batch that fails is left out. */
    private suspend fun itemsByIdsQuick(
        ids: List<UUID>,
        userId: UUID,
        fields: String,
    ): List<BaseItemDto> =
        coroutineScope {
            ids.chunked(ID_BATCH).map { chunk -> async { runCatching { itemsByIds(chunk, userId, fields) }.getOrDefault(emptyList()) } }.awaitAll().flatten()
        }

    // ------------------------------------------------------------ details page

    /** An episode's show, for opening its page from a card made before the show was known. */
    suspend fun seriesOf(episodeId: UUID): UUID? =
        withContext(Dispatchers.IO) { runCatching { api.userLibraryApi.getItem(episodeId, userId()).content.seriesId }.getOrNull() }

    // ------------------------------------------------------------ Continue Watching menu

    /**
     * "Remove from Continue Watching", told to the server as Jellyfin does it (the position back
     * to the start). The progress overlay keeps it ([ProgressOverlay.dismiss], per user, synced):
     * the main server may ignore every progress write.
     */
    suspend fun sendRemoveFromContinue(item: CinemaItem) {
        withContext(Dispatchers.IO) {
            runCatching {
                api.itemsApi.updateItemUserData(item.id, userId(), org.jellyfin.sdk.model.api.UpdateUserItemDataDto(playbackPositionTicks = 0L))
            }.onFailure { if (it is CancellationException) throw it else Timber.i("Remove from Continue Watching: the server didn't take it (%s)", it.message) }
        }
    }

    /** "Mark as watched" told to the server (the overlay keeps it: [ProgressOverlay.markWatched]). */
    suspend fun sendMarkWatched(item: CinemaItem) {
        withContext(Dispatchers.IO) {
            runCatching { api.playStateApi.markPlayedItem(item.id, userId()) }
                .onFailure { if (it is CancellationException) throw it else Timber.i("Mark as watched: the server didn't take it (%s)", it.message) }
        }
    }

    /** Where a show is up to now (its Next Up, through the overlay), as a card; null when it's finished. */
    suspend fun nextUpOf(seriesId: UUID): CinemaItem? =
        withContext(Dispatchers.IO) {
            safe { api.tvShowsApi.getNextUp(GetNextUpRequest(userId = userId(), seriesId = seriesId, limit = 1, fields = fields, enableImageTypes = images, imageTypeLimit = 1)).content.items }
                .firstOrNull()
                ?.let(::toItem)
        }

    // ------------------------------------------------------------ cast and actors

    /** The title's cast as the server lists it: actors, then guest stars, at most [CAST_MAX]. */
    private fun castOf(d: BaseItemDto): List<CastMember> =
        d.people.orEmpty()
            .filter { it.type == org.jellyfin.sdk.model.api.PersonKind.ACTOR || it.type == org.jellyfin.sdk.model.api.PersonKind.GUEST_STAR }
            .sortedBy { if (it.type == org.jellyfin.sdk.model.api.PersonKind.ACTOR) 0 else 1 }
            .filter { !it.name.isNullOrBlank() }
            .distinctBy { it.name!!.lowercase() }
            .take(CAST_MAX)
            .map { p -> CastMember(p.id, null, p.name!!, p.role?.takeIf { it.isNotBlank() }, p.primaryImageTag?.let { image(p.id, "Primary", it, PHOTO_WIDTH) }, null) }

    /**
     * [people] with TMDB's photos for those the server has none of (matched by name), and their
     * TMDB ids; TMDB's cast when the server lists nobody. Once per title in a session.
     */
    suspend fun fullCast(
        item: CinemaItem,
        people: List<CastMember>,
    ): List<CastMember> {
        castCache[item.detailsId]?.let { return it }
        val t = tmdb?.takeIf { it.available } ?: return people
        val id = item.tmdbId ?: return people
        val credits =
            withContext(Dispatchers.IO) {
                runCatching { t.cast(item.tmdbTv, id) }
                    .onFailure { if (it is CancellationException) throw it else Timber.w(it, "TMDB cast failed for %s", item.title) }
                    .getOrNull()
            } ?: return people
        val out =
            if (people.isEmpty()) {
                credits.take(CAST_MAX).map { c -> CastMember(null, c.id, c.name, c.character.ifBlank { null }, null, c.profileUrl()) }
            } else {
                val byName = credits.associateBy { com.wholphinplus.sources.core.TmdbClient.rankKey(it.name) }
                people.map { p ->
                    val c = byName[com.wholphinplus.sources.core.TmdbClient.rankKey(p.name)]
                    if (c == null) p else p.copy(tmdbId = c.id, tmdbPhoto = c.profileUrl())
                }
            }
        castCache[item.detailsId] = out
        return out
    }

    /**
     * An actor's page: who they are (the server's person and TMDB's), and the titles of theirs in
     * the library, newest first. [person] is a server person id, or a [CastMember.tmdbOnly] id.
     */
    suspend fun person(person: UUID): PersonPageData =
        withContext(Dispatchers.IO) {
            val userId = userId()
            val tmdbOnly = CastMember.tmdbIdOf(person)
            val server = if (tmdbOnly != null) null else runCatching { api.userLibraryApi.getItem(person, userId).content }.onFailure { if (it is CancellationException) throw it }.getOrNull()
            val t = tmdb?.takeIf { it.available }
            val name = server?.name.orEmpty()
            val tmdbId =
                tmdbOnly
                    ?: server?.providerIds?.entries?.firstOrNull { it.key.equals("Tmdb", true) }?.value?.toIntOrNull()
                    ?: name.takeIf { it.isNotBlank() && t != null }?.let { n -> runCatching { t!!.findPerson(n) }.getOrNull() }
            coroutineScope {
                val about = async { tmdbId?.let { id -> t?.let { runCatching { it.person(id) }.onFailure { e -> if (e is CancellationException) throw e; Timber.w(e, "TMDB person %d failed", id) }.getOrNull() } } }
                val titles = async { personTitles(server?.id, tmdbId, userId) }
                val a = about.await()
                PersonPageData(
                    name = name.ifBlank { a?.name.orEmpty() },
                    photo = server?.imageTags?.get(ImageType.PRIMARY)?.let { image(server.id, "Primary", it, PORTRAIT_WIDTH) },
                    tmdbPhoto = a?.portraitUrl(),
                    department = a?.department?.takeIf { it.isNotBlank() },
                    born = a?.birthday,
                    birthPlace = a?.placeOfBirth,
                    died = a?.deathday,
                    biography = a?.biography?.trim().orEmpty().ifBlank { server?.overview?.trim().orEmpty() },
                    titles = titles.await(),
                )
            }
        }

    /**
     * The library's titles with [serverId] in them (the server's own answer), else TMDB's credits
     * for [tmdbId] found in the library (the index, or a name search for the best known).
     */
    private suspend fun personTitles(
        serverId: UUID?,
        tmdbId: Int?,
        userId: UUID,
    ): List<CinemaItem> {
        if (serverId != null) {
            val r =
                runCatching {
                    api.itemsApi.getItems(
                        GetItemsRequest(
                            userId = userId,
                            personIds = listOf(serverId),
                            includeItemTypes = listOf(BaseItemKind.MOVIE, BaseItemKind.SERIES),
                            recursive = true,
                            sortBy = listOf(ItemSortBy.PREMIERE_DATE),
                            sortOrder = listOf(org.jellyfin.sdk.model.api.SortOrder.DESCENDING),
                            limit = PERSON_TITLES,
                            enableTotalRecordCount = true,
                            fields = fields,
                            enableImageTypes = images,
                            imageTypeLimit = 1,
                        ),
                    ).content
                }.onFailure { if (it is CancellationException) throw it else Timber.w(it, "Titles of person %s failed", serverId) }.getOrNull()
            // A server that ignores the filter answers with the whole library: not theirs
            if (r != null && (r.totalRecordCount ?: 0) in 1..PERSON_FILTER_IGNORED) {
                return r.items.map(::toItem).distinctBy { it.key }
            }
            if (r != null && (r.totalRecordCount ?: 0) == 0 && tmdbId == null) return emptyList()
        }
        val t = tmdb?.takeIf { it.available } ?: return emptyList()
        val credits = tmdbId?.let { id -> runCatching { t.personTitles(id) }.onFailure { if (it is CancellationException) throw it }.getOrNull() }.orEmpty()
        if (credits.isEmpty()) return emptyList()
        val idx = collections.savedIndexFor(hook)
        val found =
            if (idx != null) {
                val ids = credits.mapNotNull { c -> (if (c.type == com.wholphinplus.sources.core.TmdbType.TV) idx.series else idx.movies).takeIf { it.size > 0 }?.find(c.id, null)?.let { it to c } }.distinctBy { it.first }
                val byId = safe { cards(ids.map { it.first }, userId) }.associateBy { it.id.toString().replace("-", "") }
                ids.mapNotNull { (id, c) -> byId[id]?.let { toItem(it) to c } }
            } else {
                val gate = kotlinx.coroutines.sync.Semaphore(4)
                coroutineScope { credits.take(PERSON_SEARCHES).map { c -> async { gate.withPermit { inLibrary(c, userId)?.let { it to c } } } }.awaitAll().filterNotNull() }
            }
        Timber.i("Person %s: %d of %d TMDB credits in the library", tmdbId, found.size, credits.size)
        return found.sortedByDescending { it.second.year ?: 0 }.map { it.first }.distinctBy { it.key }
    }

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
                // Quality, HDR and audio from the file's streams (a server may list them only on
                // its media source), for Description tags
                val base =
                    toItem(d).let { b ->
                        val t = streamTags(d.mediaStreams?.takeIf { it.isNotEmpty() } ?: d.mediaSources?.firstOrNull()?.mediaStreams)
                        b.copy(resolution = b.resolution ?: t.resolution, hdr = b.hdr ?: t.hdr, audio = b.audio ?: t.audio)
                    }
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
                    people = castOf(d),
                    quality = quality,
                    year = d.productionYear?.toString(),
                    length =
                        if (series) {
                            d.childCount?.let { if (it == 1) "1 Season" else "$it Seasons" }
                        } else {
                            d.runTimeTicks?.let { t -> (t / 600_000_000L).let { m -> if (m >= 60) "${m / 60}h ${m % 60}m" else "${m}m" } }
                        },
                    minutesLeft = if (series) null else d.runTimeTicks?.let { ((it - (d.userData?.playbackPositionTicks ?: 0L)) / 600_000_000L).toInt() },
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
            val found = recommendedInLibrary(item, tmdb, userId)
            if (found.size >= 3) {
                found
            } else {
                safe { api.libraryApi.getSimilarItems(itemId = item.detailsId, userId = userId, limit = 16, fields = fields).content.items }
                    .map(::toItem)
                    .filter { it.backdropUrl != null || it.cardUrl != null }
                    .distinctBy { it.key }
            }
        }

    /**
     * Because You Watched: More Like This for the first title in [watched] (Continue Watching,
     * Orca+'s own progress included) that TMDB knows, without what's already in [watched]. Only
     * TMDB's picks: the server's Similar fallback is too loose for a row that names its reason.
     * No row (empty) under three titles.
     */
    private suspend fun because(
        watched: List<BaseItemDto>,
        page: RowsPage,
        userId: UUID,
    ): Pair<String, List<CinemaItem>> {
        if (tmdb?.available != true || watched.isEmpty()) return "" to emptyList()
        val started = System.currentTimeMillis()
        fillSeriesTmdb(watched.filter { it.type == BaseItemKind.EPISODE }, userId)
        Timber.i("Because You Watched: series ids in %d ms", System.currentTimeMillis() - started)
        val seen = watched.map { it.seriesId ?: it.id }.toSet()
        val source =
            watched.firstOrNull { d -> (if (d.type == BaseItemKind.EPISODE) d.seriesId?.let { seriesTmdb[it] } else tmdbOf(d)) != null }
                ?.let(::toItem) ?: return "" to emptyList()
        val found = recommendedInLibrary(source, tmdb, userId).filter { it.detailsId !in seen && (page.series == null || (it.kind == BaseItemKind.SERIES) == page.series) }
        // An episode's card is titled with its show
        return if (found.size >= 3) "Because You Watched ${source.title}" to found.take(24) else "" to emptyList()
    }

    /** TMDB's recommendations for [item] as found in the library, best first. */
    private suspend fun recommendedInLibrary(
        item: CinemaItem,
        tmdb: com.wholphinplus.sources.core.TmdbClient?,
        userId: UUID,
    ): List<CinemaItem> =
        coroutineScope {
            val started = System.currentTimeMillis()
            val track = coroutineContext[RowTrack]
            val recommended =
                item.tmdbId?.takeIf { tmdb?.available == true }?.let { id ->
                    val type = if (item.tmdbTv) com.wholphinplus.sources.core.TmdbType.TV else com.wholphinplus.sources.core.TmdbType.MOVIE
                    runCatching { tmdb!!.recommendations(type, id) }
                        .onFailure {
                            if (it is CancellationException) throw it
                            track?.failed = true
                            Timber.w(it, "TMDB recommendations failed for %s", item.title)
                        }
                        .getOrDefault(emptyList())
                        .take(RECOMMENDATIONS)
                }.orEmpty()
            // The library index knows at once which are here: one request then brings their cards.
            // Before it's read, each title is a name search (four at a time)
            val tTmdb = System.currentTimeMillis()
            val idx = collections.savedIndexFor(hook)
            val tIndex = System.currentTimeMillis()
            val inLibrary =
                if (idx != null) {
                    val ids = recommended.mapNotNull { r -> (if (r.type == com.wholphinplus.sources.core.TmdbType.TV) idx.series else idx.movies).takeIf { it.size > 0 }?.find(r.id, null) }.distinct()
                    safe { cards(ids, userId) }.map(::toItem).filter { it.backdropUrl != null || it.cardUrl != null }
                } else {
                    val gate = kotlinx.coroutines.sync.Semaphore(4)
                    recommended.map { r -> async { gate.withPermit { inLibrary(r, userId) } } }.awaitAll().filterNotNull()
                }
            val found = inLibrary.filter { it.detailsId != item.detailsId }.distinctBy { it.key }
            Timber.i(
                "More Like This for %s: %d of %d recommendations in the library (TMDB %d ms, index %d ms%s, cards %d ms)",
                item.title, found.size, recommended.size, tTmdb - started, tIndex - tTmdb, if (idx == null) " none" else "", System.currentTimeMillis() - tIndex,
            )
            found
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
        // For TMDB's stills: some servers list an episode picture they then answer 404 for
        // (Silo: every Mickey Mouse Clubhouse episode), which left the cards blank
        tmdbId: Int? = null,
        seasonNumber: Int? = null,
    ): List<CinemaEpisode> =
        withContext(Dispatchers.IO) {
            val userId = userId()
            val stills =
                async {
                    if (tmdbId == null || seasonNumber == null || tmdb == null || !tmdb.available) {
                        emptyMap()
                    } else {
                        runCatching { tmdb.episodes(tmdbId, seasonNumber).mapNotNull { e -> e.stillPath?.let { e.number to "https://image.tmdb.org/t/p/w500$it" } }.toMap() }.getOrDefault(emptyMap())
                    }
                }
            val tmdbStills = stills.await()
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
                    tmdbStill = e.indexNumber?.let { tmdbStills[it] },
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

    /**
     * Whose pages these are (server and sign-in, the token hashed): a saved page from another one
     * is never shown. Known without asking the server, so a saved page shows at once.
     */
    fun owner(): String? {
        val token = api.accessToken ?: return null
        val digest = java.security.MessageDigest.getInstance("SHA-256").digest((api.baseUrl.orEmpty().trimEnd('/') + "|" + token).toByteArray())
        return digest.joinToString("") { "%02x".format(it) }
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

    /**
     * A row's titles, or none when the server didn't answer. A quick failure (a reset stream, a
     * busy server's 5xx, the connection still waking with the TV) is tried once more a moment
     * later; a row that still fails is noted on its [RowTrack], so the load shows its copy from
     * before instead of losing it.
     */
    private suspend fun <T> safe(block: suspend () -> List<T>): List<T> =
        try {
            retried(block)
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            Timber.w(e, "Cinema row failed")
            currentCoroutineContext()[RowTrack]?.failed = true
            emptyList()
        }

    /** [block], tried once more after [RETRY_MS] when it failed in a way a retry can fix. */
    private suspend fun <T> retried(block: suspend () -> T): T {
        try {
            return block()
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            // A timeout already waited long (another wait would hold the page twice as long), and
            // a refusal (4xx) won't change
            if (e is org.jellyfin.sdk.api.client.exception.TimeoutException) throw e
            if (e is org.jellyfin.sdk.api.client.exception.InvalidStatusException && e.status in 400..499) throw e
            Timber.i("Cinema request failed (%s: %s), trying again", e.javaClass.simpleName, e.message)
        }
        delay(RETRY_MS)
        return block()
    }

    /** [block] with its requests' failures noted: [key] joins [failed] when one gave up. */
    private suspend fun <T> tracked(
        failed: MutableSet<Any>,
        key: Any,
        block: suspend () -> T,
    ): T {
        val track = RowTrack()
        return withContext(track) { block() }.also { if (track.failed) failed += key }
    }

    private val seriesTmdb = java.util.concurrent.ConcurrentHashMap<UUID, Int>()

    /** Shows' ratings, for their episodes on the Kids tab. */
    private val seriesRating = java.util.concurrent.ConcurrentHashMap<UUID, String>()

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
                // Drawn 224 px wide (Top 10 rows): 320 is sharp there. 360 fetched the 1280 px original
                // from servers behind TMDB's size tiers (350 and up), ~9x the bytes
                tags[ImageType.PRIMARY] != null && !episode -> image(d.id, "Primary", tags[ImageType.PRIMARY]!!, POSTER_WIDTH)
                episode && seriesId != null && d.seriesPrimaryImageTag != null -> image(seriesId, "Primary", d.seriesPrimaryImageTag!!, POSTER_WIDTH)
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
            // Without a thumb, the backdrop at card size (it was the 1280 px backdrop: 5x the bytes
            // on a server that resizes)
            cardUrl = thumb ?: cleanCard ?: backdrop,
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
        /** Posters' width asked of the server: TMDB-tiered servers send 300 px up to 320, the 1280 px original above. */
        const val POSTER_WIDTH = 320

        /** Titles per by-id request; several are sent at once. */
        private const val ID_BATCH = 5

        /** TMDB titles looked up in the library for More Like This (empty = not there). */
        private val libraryMatches = java.util.concurrent.ConcurrentHashMap<String, LibraryMatch>()

        /** Each title page's cast with TMDB's photos, for the session. */
        private val castCache = java.util.concurrent.ConcurrentHashMap<UUID, List<CastMember>>()
        private const val CAST_MAX = 20

        /** Cast photos are drawn ~130 px: TMDB's w185 and the server's resize to it. */
        private const val PHOTO_WIDTH = 185
        private const val PORTRAIT_WIDTH = 300

        /** An actor's titles asked of the server; more than [PERSON_FILTER_IGNORED] means it ignored the filter. */
        private const val PERSON_TITLES = 200
        private const val PERSON_FILTER_IGNORED = 1_500

        /** Without the library index: TMDB credits looked up by name, best known first. */
        private const val PERSON_SEARCHES = 30

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

        /** Longest the first screen waits for its rows; any still out then show as placeholders. */
        private const val FIRST_ROWS_WAIT_MS = 6_000L

        private const val CONTINUE = "Continue Watching"

        /** Wait before a failed request's second try. */
        private const val RETRY_MS = 1_200L

        /** [CinemaRepository.loadPage]'s failure key for the genre rows' shared pool. */
        private const val POOL_KEY = "pool"

        /** Home rows whose request doesn't depend on the library list. */
        private val LIBRARY_FREE = listOf(HomeRowType.RECENT_MOVIES, HomeRowType.NEW_EPISODES, HomeRowType.NEW_RELEASE_MOVIES, HomeRowType.MY_LIST, HomeRowType.NEW_RELEASE_SHOWS, HomeRowType.NEW_ARRIVALS, HomeRowType.JUST_AIRED)

        /** US ratings the Kids tab's Continue Watching keeps. */
        private val KID_RATINGS = setOf("G", "PG", "TV-Y", "TV-Y7", "TV-Y7-FV", "TV-G", "TV-PG")

        /** A tile page shows once this many of its rows are in. */
        private const val PAGE_FIRST_ROWS = 3

        /** Titles per page when a row loads more. */
        const val PAGE = 40

        /** Rows that keep loading as you browse (and can be shuffled): not Top 10s or Continue Watching. */
        val PAGED =
            setOf(
                HomeRowType.COLLECTION, HomeRowType.GENRE, HomeRowType.RECENT_MOVIES, HomeRowType.NEW_EPISODES, HomeRowType.NEW_RELEASE_MOVIES,
                HomeRowType.NEW_RELEASE_SHOWS, HomeRowType.NEW_ARRIVALS, HomeRowType.MY_LIST, HomeRowType.LIBRARY,
            )

        /** Rows an "always shuffle" lock reorders on load (genre rows are random anyway). */
        private val SHUFFLED_ON_LOAD = PAGED - HomeRowType.GENRE

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
    /** TMDB's still, tried when the server's picture fails. */
    val tmdbStill: String? = null,
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
    /** The cast row: the server's people (photos completed from TMDB once the row shows). */
    val people: List<CastMember> = emptyList(),
    // For Description tags: "2026", "2h 14m" or "3 Seasons", minutes left to watch (movies)
    val year: String? = null,
    val length: String? = null,
    val minutesLeft: Int? = null,
)

/**
 * One of a title's cast. [serverId]: the server's person (null: known only to TMDB); [photo] the
 * server's picture, [tmdbPhoto] TMDB's (tried when the server has none, or its picture fails).
 */
@androidx.compose.runtime.Immutable
data class CastMember(
    val serverId: UUID?,
    val tmdbId: Int?,
    val name: String,
    val role: String?,
    val photo: String?,
    val tmdbPhoto: String?,
) {
    val key: String get() = serverId?.toString() ?: "tmdb:$tmdbId:$name"

    /** The id their page opens with: the server's, or one standing for the TMDB person. */
    val pageId: UUID? get() = serverId ?: tmdbId?.let(::tmdbOnly)

    companion object {
        /** High bits of an id that stands for a TMDB person the server doesn't know ("tmdb"). */
        private const val TMDB_MARK = 0x746d6462_00000000L

        fun tmdbOnly(tmdbId: Int): UUID = UUID(TMDB_MARK, tmdbId.toLong())

        fun tmdbIdOf(id: UUID): Int? = if (id.mostSignificantBits == TMDB_MARK) id.leastSignificantBits.toInt() else null
    }
}

/** An actor's page ([CinemaRepository.person]). Dates as TMDB writes them ("1974-01-30"). */
@androidx.compose.runtime.Immutable
data class PersonPageData(
    val name: String,
    val photo: String?,
    val tmdbPhoto: String?,
    val department: String?,
    val born: String?,
    val birthPlace: String?,
    val died: String?,
    val biography: String,
    val titles: List<CinemaItem>,
)
