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
                val genres =
                    GENRE_ROWS.map { (genre, title) ->
                        async {
                            title to safe {
                                api.itemsApi.getItems(
                                    GetItemsRequest(
                                        userId = userId,
                                        genres = listOf(genre),
                                        includeItemTypes = listOf(BaseItemKind.MOVIE, BaseItemKind.SERIES),
                                        recursive = true,
                                        sortBy = listOf(ItemSortBy.RANDOM),
                                        limit = 24,
                                        fields = fields,
                                        enableImageTypes = images,
                                        imageTypeLimit = 1,
                                    ),
                                ).content.items
                            }
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
                        genres.awaitAll().filter { it.second.size >= 6 }.take(5).forEach { (title, items) -> add(CinemaRow(title, items.map(::toItem))) }
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
        val GENRE_ROWS =
            listOf(
                "Action" to "Action-Packed",
                "Comedy" to "Comedies",
                "Science Fiction" to "Sci-Fi",
                "Thriller" to "Thrillers",
                "Animation" to "Animation",
                "Drama" to "Dramas",
                "Crime" to "Crime",
                "Family" to "Family Night",
            )
    }
}
