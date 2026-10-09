package com.wholphinplus.sources.cinema

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.jellyfin.sdk.model.api.CollectionType

/** The kinds of row Cinema mode's pages can show. */
@Serializable
enum class HomeRowType {
    /** In progress and next episodes, greeting the profile. */
    CONTINUE_WATCHING,

    /** A Trakt/MDBList list or Top Streaming chart ([HomeRowSpec.ref] = its id); Top lists become Top 10 rows. */
    COLLECTION,

    /** The newest movies across every movie library, each title once. */
    RECENT_MOVIES,

    /** Shows that just got episodes, across every TV library, each show once. */
    NEW_EPISODES,

    /** Movies by release date. */
    NEW_RELEASE_MOVIES,

    /** Shows by first air date. */
    NEW_RELEASE_SHOWS,

    /** Your My List (favourites). */
    MY_LIST,

    /** One genre ([HomeRowSpec.ref] = its row name in the page's [RowsPage.genres]). */
    GENRE,

    /** One library's latest additions ([HomeRowSpec.ref] = the library id). */
    LIBRARY,

    /** Whatever just arrived on the server, newest first. */
    NEW_ARRIVALS,

    /** Episodes by air date, one per show. */
    JUST_AIRED,

    /** Tiles of streaming services, each opening the service's page. */
    SERVICES,

    /** Tiles of genres, each opening the genre's page. */
    GENRES,

    /** Tiles of decades (2020s… 70s), each opening the decade's page. */
    DECADES,

    /** More Like This for the title at the front of Continue Watching, as found in the library. */
    BECAUSE_YOU_WATCHED,
}

/**
 * A Cinema mode page whose rows can be arranged. [series]: true shows only shows, false only
 * movies, null both.
 */
@Serializable
enum class RowsPage(
    val label: String,
    val series: Boolean?,
) {
    HOME("Home", null),
    SHOWS("Shows", true),
    MOVIES("Movies", false),
    NEW_POPULAR("New & Popular", null),

    /** Kids' rows only (off until switched on in Settings). */
    KIDS("Kids", null),
    ;

    val hasContinueWatching: Boolean get() = this != NEW_POPULAR

    /** The genre rows this page can offer (name → the server genres it gathers). */
    val genres: List<Pair<Set<String>, String>>
        get() =
            when (this) {
                HOME -> CinemaRepository.GENRE_ROWS
                SHOWS -> CinemaRepository.SHOW_GENRES
                MOVIES -> CinemaRepository.MOVIE_GENRES
                NEW_POPULAR, KIDS -> emptyList()
            }

    /** The kinds of row this page offers. */
    fun offers(type: HomeRowType): Boolean =
        // Kids: only kids' charts and Continue Watching (kept to kids' ratings), never the
        // library's newest or your own favourites
        if (this == KIDS) {
            type in setOf(HomeRowType.CONTINUE_WATCHING, HomeRowType.BECAUSE_YOU_WATCHED, HomeRowType.COLLECTION)
        } else when (type) {
            HomeRowType.CONTINUE_WATCHING, HomeRowType.BECAUSE_YOU_WATCHED -> hasContinueWatching
            HomeRowType.RECENT_MOVIES, HomeRowType.NEW_RELEASE_MOVIES -> series != true
            HomeRowType.NEW_EPISODES, HomeRowType.NEW_RELEASE_SHOWS, HomeRowType.JUST_AIRED -> series != false
            HomeRowType.GENRE -> genres.isNotEmpty()
            HomeRowType.LIBRARY -> this != NEW_POPULAR
            HomeRowType.MY_LIST, HomeRowType.COLLECTION, HomeRowType.NEW_ARRIVALS, HomeRowType.SERVICES, HomeRowType.GENRES, HomeRowType.DECADES -> true
        }

    /** A library whose latest additions this page can show as a row of its own. */
    fun offers(library: CinemaLibrary): Boolean =
        offers(HomeRowType.LIBRARY) &&
            when (library.collectionType) {
                CollectionType.MOVIES -> series != true
                CollectionType.TVSHOWS -> series != false
                else -> false
            }

    /** A list this page can show: on Shows and Movies, not a chart of the other kind. */
    fun offers(list: PageList): Boolean = series == null || list.series == null || list.series == series

    /** Whether a list starts switched on here: an Orca+ chart where the cloud picks it; your own lists on Home. */
    fun listStartsOn(list: PageList): Boolean = list.pages?.let { name in it } ?: (this == HOME)
}

/** A list as the pages see it: [series] true/false for a chart of shows/movies, null for mixed. */
data class PageList(
    val id: String,
    val name: String,
    val series: Boolean? = null,
    /** An Orca+ chart's suggested pages (RowsPage names); null for your own lists. */
    val pages: List<String>? = null,
    /** An Orca+ chart's place on each page it starts on. */
    val order: Map<String, Int>? = null,
)

/** One row: what it is, whether it shows, and an optional name of your own. */
@Serializable
data class HomeRowSpec(
    val type: HomeRowType,
    val on: Boolean = true,
    val ref: String = "",
    val title: String = "",
) {
    val key: String get() = type.name + ":" + ref

    /**
     * The row's own name, before any name you give it. [libraries] and [lists] (list id →
     * name) name the rows that depend on them; Continue Watching greets the profile at run time.
     */
    fun defaultName(
        libraries: List<CinemaLibrary>,
        lists: Map<String, String>,
    ): String =
        when (type) {
            HomeRowType.CONTINUE_WATCHING -> "Continue Watching"
            HomeRowType.COLLECTION -> lists[ref] ?: "List"
            HomeRowType.RECENT_MOVIES -> "Recently Added Movies"
            HomeRowType.NEW_EPISODES -> "New Episodes"
            HomeRowType.NEW_RELEASE_MOVIES -> "New Release Movies"
            HomeRowType.NEW_RELEASE_SHOWS -> "New Release Shows"
            HomeRowType.MY_LIST -> "My List"
            HomeRowType.GENRE -> ref
            HomeRowType.NEW_ARRIVALS -> "New on Orca+"
            HomeRowType.JUST_AIRED -> "Latest Episodes"
            HomeRowType.SERVICES -> "Services"
            HomeRowType.GENRES -> "Genres"
            HomeRowType.DECADES -> "Decades"
            HomeRowType.BECAUSE_YOU_WATCHED -> "Because You Watched"
            HomeRowType.LIBRARY ->
                libraries.firstOrNull { it.id.toString() == ref }?.let { lib ->
                    if (lib.collectionType == CollectionType.TVSHOWS) "New Episodes in ${lib.name}" else "Recently Added in ${lib.name}"
                } ?: "Library"
        }
}

/**
 * A page's rows, in order. Continue Watching (on pages that have it) is always the first row
 * and always on; every other row can be switched off, moved or renamed.
 */
@Serializable
data class HomeLayout(
    @Serializable(with = LenientRows::class) val rows: List<HomeRowSpec>,
    /**
     * Services was moved right under Continue Watching (owner, 2026-10-06), once: a layout saved
     * before that gets it there on its next load, and you can move it again after.
     */
    val servicesTop: Boolean = false,
) {
    /** Continue Watching first and on (keeping a name you gave it), when the layout has it. */
    fun pinned(): HomeLayout {
        val cw = rows.firstOrNull { it.type == HomeRowType.CONTINUE_WATCHING }?.copy(on = true) ?: return this
        return copy(rows = listOf(cw) + rows.filter { it.type != HomeRowType.CONTINUE_WATCHING })
    }

    /** The rows that show, in order, then the ones you can add (each group keeps its order). */
    fun tidy(): HomeLayout = copy(rows = rows.filter { it.on } + rows.filterNot { it.on }).pinned()

    /**
     * [key]'s row switched on (it joins the bottom of the page) or off (it goes back among the
     * rows you can add). Continue Watching stays on.
     */
    fun switched(
        key: String,
        on: Boolean,
    ): HomeLayout {
        val row = rows.firstOrNull { it.key == key } ?: return this
        if (row.type == HomeRowType.CONTINUE_WATCHING || row.on == on) return this
        val rest = tidy().rows.filter { it.key != key }
        val at = rest.count { it.on }
        return copy(rows = rest.take(at) + row.copy(on = on) + rest.drop(at)).pinned()
    }

    /** [key]'s row with a name of your own; blank goes back to the row's own name. */
    fun renamed(
        key: String,
        title: String,
    ): HomeLayout = copy(rows = rows.map { if (it.key == key) it.copy(title = title.trim()) else it }).pinned()

    /** [key]'s row moved [by] places among the rows that show, never above Continue Watching. */
    fun moved(
        key: String,
        by: Int,
    ): HomeLayout {
        val list = tidy().rows.toMutableList()
        val from = list.indexOfFirst { it.key == key }
        val first = if (list.firstOrNull()?.type == HomeRowType.CONTINUE_WATCHING) 1 else 0
        if (from < first || !list[from].on) return this
        val to = (from + by).coerceIn(first, list.count { it.on } - 1)
        if (to == from) return copy(rows = list)
        list.add(to, list.removeAt(from))
        return copy(rows = list)
    }

    companion object {
        /**
         * The rows a page starts with: Continue Watching (on pages that have it), then the rows
         * the Orca+ cloud picks for the page, in its order, and on Home your own lists, then one
         * row per library the page offers. Everything else (genres, other charts) is there to
         * switch on, off.
         */
        fun defaults(
            page: RowsPage,
            libraries: List<CinemaLibrary>,
            lists: List<PageList>,
            tiles: Map<HomeRowType, Int> = emptyMap(),
        ): HomeLayout {
            val offered = lists.filter { page.offers(it) }
            val rows =
                buildList {
                    if (page.hasContinueWatching) add(HomeRowSpec(HomeRowType.CONTINUE_WATCHING))
                    // The cloud's lineup: its charts and the rows it places by token (tiles, Because You
                    // Watched, the every-library rows), in its order
                    val lineup =
                        offered.filter { page.listStartsOn(it) }.map { (it.order?.get(page.name) ?: Int.MAX_VALUE) to HomeRowSpec(HomeRowType.COLLECTION, ref = it.id) } +
                            tiles.filterKeys { page.offers(it) }.map { (t, at) -> at to HomeRowSpec(t) }
                    lineup.sortedBy { it.first }.forEach { add(it.second) }
                    offered.filterNot { page.listStartsOn(it) }.forEach { add(HomeRowSpec(HomeRowType.COLLECTION, on = false, ref = it.id)) }
                    HomeRowType.entries
                        .filter { t -> page.offers(t) && t !in tiles && t !in setOf(HomeRowType.CONTINUE_WATCHING, HomeRowType.COLLECTION, HomeRowType.GENRE, HomeRowType.LIBRARY) }
                        .forEach { add(HomeRowSpec(it, on = false)) }
                    page.genres.forEach { (_, name) -> add(HomeRowSpec(HomeRowType.GENRE, on = false, ref = name)) }
                    // Your own libraries' latest additions, after the cloud's rows: a page always has
                    // the server's own titles, even when few chart titles are in the library
                    // (off where the cloud places the every-library row of their kind instead)
                    libraries.filter { page.offers(it) }.forEach { lib ->
                        val combined = if (lib.collectionType == CollectionType.TVSHOWS) HomeRowType.NEW_EPISODES else HomeRowType.RECENT_MOVIES
                        add(HomeRowSpec(HomeRowType.LIBRARY, on = combined !in tiles, ref = lib.id.toString()))
                    }
                }
            return HomeLayout(rows, servicesTop = true).pinned()
        }
    }

    /**
     * This layout brought up to date with the server, your lists and what [page] offers: charts
     * added since take their place in the cloud's lineup, other lists go after the last list row (on the page's terms: see
     * [RowsPage.listStartsOn]), libraries, genres and kinds of row added since join at the end,
     * switched off; rows for lists or libraries that are gone are dropped.
     */
    fun reconciled(
        page: RowsPage,
        libraries: List<CinemaLibrary>,
        lists: List<PageList>,
        tiles: Map<HomeRowType, Int> = emptyMap(),
    ): HomeLayout {
        val offeredLists = lists.filter { page.offers(it) }
        val listIds = offeredLists.map { it.id }.toSet()
        val libraryIds = libraries.filter { page.offers(it) }.map { it.id.toString() }.toSet()
        val genreNames = page.genres.map { it.second }.toSet()
        val kept =
            rows.filter {
                page.offers(it.type) &&
                    when (it.type) {
                        HomeRowType.COLLECTION -> it.ref in listIds
                        HomeRowType.LIBRARY -> it.ref in libraryIds
                        HomeRowType.GENRE -> it.ref in genreNames
                        else -> true
                    }
            }.distinctBy { it.key }.toMutableList()
        if (page.hasContinueWatching && kept.none { it.type == HomeRowType.CONTINUE_WATCHING }) kept.add(0, HomeRowSpec(HomeRowType.CONTINUE_WATCHING))
        val haveLists = kept.filter { it.type == HomeRowType.COLLECTION }.map { it.ref }.toSet()
        val lineup = offeredLists.mapNotNull { l -> l.order?.get(page.name)?.let { l.id to it } }.toMap()
        val (placed, loose) = offeredLists.filter { it.id !in haveLists }.partition { it.id in lineup }
        if (loose.isNotEmpty()) {
            val at = (kept.indexOfLast { it.type == HomeRowType.COLLECTION }.takeIf { it >= 0 } ?: kept.indexOfFirst { it.type == HomeRowType.CONTINUE_WATCHING }) + 1
            kept.addAll(at.coerceIn(0, kept.size), loose.map { HomeRowSpec(HomeRowType.COLLECTION, on = page.listStartsOn(it), ref = it.id) })
        }
        // Charts the cloud places on this page (a seasonal row, say) go after the rows its lineup puts before them
        placed.sortedBy { lineup.getValue(it.id) }.forEach { l ->
            val at = lineup.getValue(l.id)
            val after = kept.indexOfLast { r -> r.type == HomeRowType.CONTINUE_WATCHING || ((if (r.type == HomeRowType.COLLECTION) lineup[r.ref] else tiles[r.type]) ?: Int.MAX_VALUE) < at }
            kept.add(after + 1, HomeRowSpec(HomeRowType.COLLECTION, on = page.listStartsOn(l), ref = l.id))
        }
        // Tiles the cloud places on this page join switched on, after the lineup's rows above them
        tiles.filterKeys { t -> page.offers(t) && kept.none { it.type == t } }.entries.sortedBy { it.value }.forEach { (t, at) ->
            val order = offeredLists.associate { it.id to (it.order?.get(page.name) ?: Int.MAX_VALUE) }
            val after = kept.indexOfLast { (it.type == HomeRowType.COLLECTION && (order[it.ref] ?: Int.MAX_VALUE) < at) || it.type == HomeRowType.CONTINUE_WATCHING }
            kept.add(after + 1, HomeRowSpec(t))
        }
        HomeRowType.entries
            .filter { t -> page.offers(t) && t !in setOf(HomeRowType.COLLECTION, HomeRowType.GENRE, HomeRowType.LIBRARY) && kept.none { it.type == t } }
            .forEach { kept += HomeRowSpec(it, on = false) }
        val haveGenres = kept.filter { it.type == HomeRowType.GENRE }.map { it.ref }.toSet()
        page.genres.map { it.second }.filter { it !in haveGenres }.forEach { kept += HomeRowSpec(HomeRowType.GENRE, on = false, ref = it) }
        val haveLibraries = kept.filter { it.type == HomeRowType.LIBRARY }.map { it.ref }.toSet()
        libraries
            .filter { page.offers(it) && it.id.toString() !in haveLibraries }
            .forEach { kept += HomeRowSpec(HomeRowType.LIBRARY, on = false, ref = it.id.toString()) }
        // Once: Services right under Continue Watching where the cloud puts it first
        if (!servicesTop && tiles[HomeRowType.SERVICES] == 0) {
            val at = kept.indexOfFirst { it.type == HomeRowType.SERVICES }
            if (at >= 0) {
                val services = kept.removeAt(at).copy(on = true)
                kept.add(if (kept.firstOrNull()?.type == HomeRowType.CONTINUE_WATCHING) 1 else 0, services)
            }
        }
        return copy(rows = kept, servicesTop = servicesTop || tiles[HomeRowType.SERVICES] == 0).pinned()
    }
}

/**
 * A layout's rows, leaving out any this app doesn't know (a kind of row a newer Orca+ added): a
 * layout synced from a newer TV still loads here, without those rows.
 */
internal object LenientRows : KSerializer<List<HomeRowSpec>> {
    private val list = ListSerializer(HomeRowSpec.serializer())
    override val descriptor = list.descriptor

    override fun serialize(
        encoder: Encoder,
        value: List<HomeRowSpec>,
    ) = list.serialize(encoder, value)

    override fun deserialize(decoder: Decoder): List<HomeRowSpec> {
        val json = decoder as? JsonDecoder ?: return list.deserialize(decoder)
        return json.decodeJsonElement().jsonArray.mapNotNull { runCatching { json.json.decodeFromJsonElement(HomeRowSpec.serializer(), it) }.getOrNull() }
    }
}

/** Every page's layout, leaving out pages this app doesn't know (a tab a newer Orca+ added). */
internal object LenientLayouts : KSerializer<Map<RowsPage, HomeLayout>> {
    private val map = MapSerializer(RowsPage.serializer(), HomeLayout.serializer())
    override val descriptor = map.descriptor

    override fun serialize(
        encoder: Encoder,
        value: Map<RowsPage, HomeLayout>,
    ) = map.serialize(encoder, value)

    override fun deserialize(decoder: Decoder): Map<RowsPage, HomeLayout> {
        val json = decoder as? JsonDecoder ?: return map.deserialize(decoder)
        return json.decodeJsonElement().jsonObject.mapNotNull { (name, layout) ->
            val page = RowsPage.entries.firstOrNull { it.name == name } ?: return@mapNotNull null
            runCatching { page to json.json.decodeFromJsonElement(HomeLayout.serializer(), layout) }.getOrNull()
        }.toMap()
    }
}
