package com.wholphinplus.sources.cinema

import com.wholphinplus.sources.HomeCollections
import com.wholphinplus.sources.SourceHook
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import org.jellyfin.sdk.api.client.extensions.itemsApi
import org.jellyfin.sdk.api.client.extensions.libraryApi
import org.jellyfin.sdk.api.client.extensions.tvShowsApi
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
) {
    val key: String get() = "$kind:$id"
}

@androidx.compose.runtime.Immutable
data class CinemaRow(
    val title: String,
    val items: List<CinemaItem>,
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

    suspend fun load(): CinemaHomeData =
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
                // One by one: some servers only honour the first id of a multi-id request
                seriesIds.take(30).map { id ->
                    async { runCatching { api.userLibraryApi.getItem(id, userId).content }.getOrNull() }
                }.awaitAll().filterNotNull().forEach { s -> tmdbOf(s)?.let { seriesTmdb[s.id] = it } }
                val continueWatching = cw.map(::toItem)
                val latestRows =
                    latest.awaitAll().map { (lib, items) ->
                        CinemaRow(
                            if (lib.collectionType == CollectionType.TVSHOWS) "New Episodes in ${lib.name}" else "Recently Added in ${lib.name}",
                            items.map(::toItem),
                        )
                    }
                val rows =
                    buildList {
                        add(CinemaRow("Continue Watching", continueWatching))
                        lists.awaitAll().forEach { (name, items) -> add(CinemaRow(name, items.map(::toItem))) }
                        addAll(latestRows)
                        addAll(genreRows(pool.await(), GENRE_ROWS, 5))
                    }.filter { it.items.isNotEmpty() }

                // The billboard: recent titles that have both a backdrop and title art
                val featured =
                    latestRows
                        .flatMap { it.items }
                        .filter { it.backdropUrl != null && it.overview.isNotBlank() && it.kind != BaseItemKind.EPISODE }
                        .distinctBy { it.detailsId }
                        .shuffled()
                        .take(6)
                        .ifEmpty { rows.flatMap { it.items }.filter { it.backdropUrl != null }.take(6) }

                CinemaHomeData(featured, rows, showLibs.firstOrNull(), movieLibs.firstOrNull())
            }
        }

    // ------------------------------------------------------------ Shows / Movies / My List tabs

    /** A Shows or Movies page: the home's layout, every row narrowed to one kind of title. */
    suspend fun loadKind(series: Boolean): CinemaHomeData =
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
                val rows =
                    buildList {
                        add(CinemaRow("Continue Watching", cw.map(::toItem)))
                        add(fresh)
                        addAll(latestRows)
                        addAll(genreRows(pool.await(), if (series) SHOW_GENRES else MOVIE_GENRES, 8))
                    }.filter { it.items.isNotEmpty() }.distinctBy { it.title }

                val featured =
                    (fresh.items + latestRows.flatMap { it.items })
                        .filter { it.backdropUrl != null && it.overview.isNotBlank() && it.kind == kind }
                        .distinctBy { it.detailsId }
                        .shuffled()
                        .take(6)
                        .ifEmpty { rows.flatMap { it.items }.filter { it.backdropUrl != null }.take(6) }
                CinemaHomeData(featured, rows, libs.firstOrNull { it.collectionType == CollectionType.TVSHOWS }, libs.firstOrNull { it.collectionType == CollectionType.MOVIES })
            }
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

    /** Episodes borrow their series' TMDB id for title art; fetched one by one (see load). */
    private suspend fun fillSeriesTmdb(
        items: List<BaseItemDto>,
        userId: UUID,
    ) = coroutineScope {
        items.mapNotNull { it.seriesId }.distinct().filter { !seriesTmdb.containsKey(it) }.take(30).map { id ->
            async { runCatching { api.userLibraryApi.getItem(id, userId).content }.getOrNull() }
        }.awaitAll().filterNotNull().forEach { s -> tmdbOf(s)?.let { seriesTmdb[s.id] = it } }
    }

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

    suspend fun setFavorite(
        id: UUID,
        favorite: Boolean,
    ) = withContext(Dispatchers.IO) {
        val userId = userId()
        if (favorite) api.userLibraryApi.markFavoriteItem(id, userId) else api.userLibraryApi.unmarkFavoriteItem(id, userId)
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
        /** Genre rows, shown when the library has enough titles for them. */
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
