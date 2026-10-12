@file:UseSerializers(UUIDSerializer::class)

package com.wholphinplus.sources.cinema

import kotlinx.serialization.Serializable
import kotlinx.serialization.UseSerializers
import org.jellyfin.sdk.model.serializer.UUIDSerializer
import org.jellyfin.sdk.api.client.extensions.get
import org.jellyfin.sdk.model.api.BaseItemKind
import org.jellyfin.sdk.model.api.CollectionType
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
    /** For a full-screen grid's sorting and filters ([GridFilter]): when the server added it (epoch ms),
     *  its release day (epoch day), year, genres and the server's own score (Silo has none). */
    val added: Long? = null,
    val releasedDay: Long? = null,
    val year: Int? = null,
    val genres: List<String> = emptyList(),
    val score: Float? = null,
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
    /** The server whose library this row is (the My Servers tab): [SERVER_MAIN] or an extra server's connection id. */
    val server: String? = null,
)

/** [CinemaRow.server] of the main server's rows. */
internal const val SERVER_MAIN = "main"

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
    /** An extra server's library row (My Servers): its connection id; [spec]'s ref is the library. */
    val server: String? = null,
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

/** A service's, genre's, decade's or server's tile; it opens that page (with its Movies or Shows side first). */
@androidx.compose.runtime.Immutable
@Serializable
data class PageTile(
    val id: String,
    val name: String,
    /** "service", "genre", "decade" or "server". */
    val kind: String,
    val logoUrl: String?,
    val pictureUrl: String?,
    /** A server tile's line under its name ("3 libraries", or why it's left out). */
    val note: String? = null,
    /** A server tile's software ([com.wholphinplus.sources.core.ServerKind] name), for its logo. */
    val brand: String? = null,
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
