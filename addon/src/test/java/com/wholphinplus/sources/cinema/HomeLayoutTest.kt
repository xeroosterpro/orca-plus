package com.wholphinplus.sources.cinema

import org.jellyfin.sdk.model.api.BaseItemKind
import org.jellyfin.sdk.model.api.CollectionType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

class HomeLayoutTest {
    private fun lib(
        n: Long,
        type: CollectionType,
    ) = CinemaLibrary(UUID(0, n), "Lib $n", BaseItemKind.COLLECTION_FOLDER, type)

    private fun lists(vararg ids: String) = ids.map { PageList(it, it) }

    // A server with HD and 4K copies of the same libraries, like the main one
    private val libs = listOf(lib(1, CollectionType.MOVIES), lib(2, CollectionType.TVSHOWS), lib(9, CollectionType.MOVIES), lib(11, CollectionType.TVSHOWS))

    private fun home(vararg ids: String) = HomeLayout.defaults(RowsPage.HOME, libs, lists(*ids))

    @Test fun `a page starts clean - continue watching, your lists on home, then your libraries`() {
        val on = home("mine").rows.filter { it.on }
        assertEquals(listOf(HomeRowType.CONTINUE_WATCHING, HomeRowType.COLLECTION) + List(4) { HomeRowType.LIBRARY }, on.map { it.type })
        // Everything else is offered, switched off
        val rows = home().rows
        assertTrue(rows.any { it.type == HomeRowType.RECENT_MOVIES && !it.on })
        assertEquals(CinemaRepository.GENRE_ROWS.size, rows.count { it.type == HomeRowType.GENRE && !it.on })
        listOf(RowsPage.SHOWS, RowsPage.MOVIES).forEach { p ->
            assertEquals(listOf(HomeRowType.CONTINUE_WATCHING, HomeRowType.LIBRARY, HomeRowType.LIBRARY), HomeLayout.defaults(p, libs, lists("mine")).rows.filter { it.on }.map { it.type })
        }
        assertTrue(HomeLayout.defaults(RowsPage.NEW_POPULAR, libs, lists("mine")).rows.none { it.on })
    }

    @Test fun `library rows start on, after the cloud's lineup`() {
        val charts = listOf(PageList("trend", "Trending", pages = listOf("HOME"), order = mapOf("HOME" to 0)))
        val rows = HomeLayout.defaults(RowsPage.HOME, libs, charts, mapOf(HomeRowType.SERVICES to 1)).rows
        val library = rows.filter { it.type == HomeRowType.LIBRARY }
        assertEquals(4, library.size)
        assertTrue(library.all { it.on })
        assertTrue(rows.indexOf(library.first()) > rows.indexOfFirst { it.type == HomeRowType.SERVICES })
    }

    @Test fun `continue watching is always first and on`() {
        val saved = HomeLayout(listOf(HomeRowSpec(HomeRowType.MY_LIST), HomeRowSpec(HomeRowType.CONTINUE_WATCHING, on = false, title = "Keep Going")))
        val row = saved.reconciled(RowsPage.HOME, libs, emptyList()).rows.first()
        assertEquals(HomeRowType.CONTINUE_WATCHING, row.type)
        assertTrue(row.on)
        assertEquals("Keep Going", row.title)
        // Even a saved page without it gets it back, on top
        assertEquals(HomeRowType.CONTINUE_WATCHING, HomeLayout(listOf(HomeRowSpec(HomeRowType.MY_LIST))).reconciled(RowsPage.SHOWS, libs, emptyList()).rows.first().type)
    }

    @Test fun `a new list joins after the others, a removed one goes`() {
        val rows = home("a", "b").reconciled(RowsPage.HOME, libs, lists("b", "c")).rows
        val lists = rows.filter { it.type == HomeRowType.COLLECTION }.map { it.ref }
        assertEquals(listOf("b", "c"), lists)
        assertEquals(rows.indexOfFirst { it.ref == "b" } + 1, rows.indexOfFirst { it.ref == "c" })
    }

    @Test fun `a new library is offered off, a removed one goes`() {
        val rows = home().reconciled(RowsPage.HOME, libs.drop(1) + lib(12, CollectionType.MOVIES), emptyList()).rows.filter { it.type == HomeRowType.LIBRARY }
        assertFalse(rows.any { it.ref == UUID(0, 1).toString() })
        assertTrue(rows.any { it.ref == UUID(0, 12).toString() && !it.on })
    }

    @Test fun `moving stays among the rows that show, below continue watching`() {
        val layout = home("a", "b", "c").tidy()
        val second = layout.rows[1].key
        assertEquals(layout, layout.moved(second, -1))
        assertEquals(layout, layout.moved(layout.rows[0].key, +1))
        assertEquals(second, layout.moved(second, +1).rows[2].key)
        val far = layout.moved(second, +100).rows
        assertEquals(second, far.last { it.on }.key)
        val off = layout.rows.first { !it.on }.key
        assertEquals(layout, layout.moved(off, -1))
    }

    @Test fun `a row switched on joins the bottom of the page`() {
        val layout = home()
        val lib = layout.rows.first { it.type == HomeRowType.GENRE }.key
        val on = layout.switched(lib, true).rows
        assertEquals(lib, on.last { it.on }.key)
        assertTrue(on.dropWhile { it.on }.none { it.on })
        val off = HomeLayout(on).switched(on[1].key, false).rows
        assertEquals(on[1].key, off.first { !it.on }.key)
    }

    @Test fun `continue watching can't be switched off, others can`() {
        val layout = home()
        assertTrue(layout.switched(layout.rows[0].key, false).rows[0].on)
        val myList = HomeRowSpec(HomeRowType.MY_LIST).key
        assertFalse(layout.switched(myList, false).rows.first { it.key == myList }.on)
    }

    @Test fun `a blank name goes back to the row's own`() {
        val myList = HomeRowSpec(HomeRowType.MY_LIST).key
        val named = home().renamed(myList, "  Saved  ")
        assertEquals("Saved", named.rows.first { it.key == myList }.title)
        assertEquals("", named.renamed(myList, " ").rows.first { it.key == myList }.title)
        val tv = HomeRowSpec(HomeRowType.LIBRARY, ref = UUID(0, 2).toString())
        assertEquals("New Episodes in Lib 2", tv.defaultName(libs, emptyMap()))
    }

    // ---------------------------------------------------------------- other pages

    private val charts =
        listOf(
            PageList("nfm", "Hulu Top 10 Movies", series = false),
            PageList("nfs", "Hulu Top 10 Shows", series = true),
            PageList("mine", "Weekend Picks"),
        )

    @Test fun `shows offers only shows, with its own genres and libraries`() {
        val rows = HomeLayout.defaults(RowsPage.SHOWS, libs, charts).rows
        assertFalse(rows.any { it.type == HomeRowType.RECENT_MOVIES || it.type == HomeRowType.NEW_RELEASE_MOVIES })
        assertFalse(rows.any { it.ref == "nfm" })
        assertTrue(rows.any { it.ref == "nfs" && !it.on })
        assertTrue(rows.any { it.ref == "mine" && !it.on })
        assertEquals(CinemaRepository.SHOW_GENRES.map { it.second }, rows.filter { it.type == HomeRowType.GENRE }.map { it.ref })
        assertEquals(listOf(UUID(0, 2), UUID(0, 11)).map { it.toString() }, rows.filter { it.type == HomeRowType.LIBRARY }.map { it.ref })
        assertEquals(HomeRowType.CONTINUE_WATCHING, rows.first().type)
    }

    @Test fun `new and popular has no continue watching and starts with the cloud's picks only`() {
        val picked = charts.map { if (it.id == "nfs") it.copy(pages = listOf("NEW_POPULAR")) else it }
        val rows = HomeLayout.defaults(RowsPage.NEW_POPULAR, libs, picked).rows
        assertFalse(rows.any { it.type == HomeRowType.CONTINUE_WATCHING || it.type == HomeRowType.GENRE || it.type == HomeRowType.LIBRARY })
        assertEquals(listOf("nfs"), rows.filter { it.on }.map { it.ref })
        // A chart the cloud picks later joins switched on; one it doesn't, off
        val later = HomeLayout.defaults(RowsPage.NEW_POPULAR, libs, picked).reconciled(RowsPage.NEW_POPULAR, libs, picked + PageList("hulu", "Hulu Top 10 Shows", true, listOf("NEW_POPULAR")) + PageList("x", "Top 10 Elsewhere", true, emptyList()))
        assertTrue(later.rows.first { it.ref == "hulu" }.on)
        assertFalse(later.rows.first { it.ref == "x" }.on)
    }

    @Test fun `movies keeps a saved home's rows to itself`() {
        // A Home layout read on Movies (or one saved before a kind was offered) loses what Movies can't show
        val movies = home("a").reconciled(RowsPage.MOVIES, libs, lists("a")).rows
        assertFalse(movies.any { it.type == HomeRowType.NEW_EPISODES || it.type == HomeRowType.NEW_RELEASE_SHOWS })
        assertFalse(movies.any { it.type == HomeRowType.GENRE && it.ref !in CinemaRepository.MOVIE_GENRES.map { g -> g.second } })
        assertTrue(movies.any { it.type == HomeRowType.GENRE && !it.on })
    }

    @Test fun `orca charts start on the pages the cloud suggests, right under continue watching`() {
        val charts =
            listOf(
                PageList("trend", "Trending Movies on Trakt", series = false, pages = listOf("HOME", "MOVIES")),
                PageList("top", "Top Rated Movies of All Time", series = false, pages = listOf("MOVIES")),
                PageList("vudu", "Vudu Top 10 Movies", series = false, pages = emptyList()),
            )
        val movies = HomeLayout.defaults(RowsPage.MOVIES, libs, charts).tidy().rows
        assertEquals(listOf("trend", "top"), movies.drop(1).take(2).map { it.ref })
        assertFalse(movies.first { it.ref == "vudu" }.on)
        assertEquals(1, movies.count { it.ref == "trend" })
        // On New & Popular a chart that doesn't suggest it stays off, even with "Top" in its name
        assertFalse(HomeLayout.defaults(RowsPage.NEW_POPULAR, libs, charts).rows.first { it.ref == "vudu" }.on)
        assertTrue(HomeLayout.defaults(RowsPage.HOME, libs, charts).rows.first { it.ref == "trend" }.on)
    }

    @Test fun `the cloud's lineup sets each page's order`() {
        val charts =
            listOf(
                PageList("a", "A", pages = listOf("HOME", "MOVIES"), order = mapOf("HOME" to 1, "MOVIES" to 0)),
                PageList("b", "B", pages = listOf("HOME", "MOVIES"), order = mapOf("HOME" to 0, "MOVIES" to 1)),
            )
        assertEquals(listOf("b", "a"), HomeLayout.defaults(RowsPage.HOME, libs, charts).rows.filter { it.on && it.type == HomeRowType.COLLECTION }.map { it.ref })
        assertEquals(listOf("a", "b"), HomeLayout.defaults(RowsPage.MOVIES, libs, charts).rows.filter { it.on && it.type == HomeRowType.COLLECTION }.map { it.ref })
    }

    @Test fun `charts new to a saved page join in the cloud's order`() {
        val charts =
            listOf(
                PageList("late", "Late", pages = listOf("HOME"), order = mapOf("HOME" to 5)),
                PageList("early", "Early", pages = listOf("HOME"), order = mapOf("HOME" to 1)),
            )
        val rows = home("mine").reconciled(RowsPage.HOME, libs, lists("mine") + charts).rows
        assertEquals(listOf("early", "late", "mine"), rows.filter { it.type == HomeRowType.COLLECTION }.map { it.ref })
    }

    @Test fun `a seasonal chart joins a saved home at its place in the lineup, not at the bottom`() {
        val charts =
            listOf(
                PageList("trend", "Trending", pages = listOf("HOME"), order = mapOf("HOME" to 0)),
                PageList("top10", "Top 10", pages = listOf("HOME"), order = mapOf("HOME" to 1)),
                PageList("greats", "Greats", pages = listOf("HOME"), order = mapOf("HOME" to 5)),
            )
        val tiles = mapOf(HomeRowType.SERVICES to 3)
        val saved = HomeLayout.defaults(RowsPage.HOME, libs, charts, tiles)
        val halloween = PageList("halloween", "Halloween Favorites", series = false, pages = listOf("HOME"), order = mapOf("HOME" to 2))
        val on = saved.reconciled(RowsPage.HOME, libs, charts + halloween, tiles).rows.filter { it.on && it.type != HomeRowType.LIBRARY }.map { it.ref.ifBlank { it.type.name } }
        assertEquals(listOf("CONTINUE_WATCHING", "trend", "top10", "halloween", "SERVICES", "greats"), on)
        // Gone after its month, it leaves no gap
        val later = HomeLayout(saved.reconciled(RowsPage.HOME, libs, charts + halloween, tiles).rows).reconciled(RowsPage.HOME, libs, charts, tiles)
        assertEquals(saved.rows.filter { it.on }, later.rows.filter { it.on })
    }

    @Test fun `services and genres tiles take their places in the cloud's lineup`() {
        val charts =
            listOf(
                PageList("trend", "Trending", pages = listOf("HOME"), order = mapOf("HOME" to 0)),
                PageList("new", "New", pages = listOf("HOME"), order = mapOf("HOME" to 2)),
            )
        val tiles = mapOf(HomeRowType.SERVICES to 1, HomeRowType.GENRES to 3)
        val on = HomeLayout.defaults(RowsPage.HOME, libs, charts, tiles).rows.filter { it.on && it.type != HomeRowType.LIBRARY }.map { it.ref.ifBlank { it.type.name } }
        assertEquals(listOf("CONTINUE_WATCHING", "trend", "SERVICES", "new", "GENRES"), on)
        // A saved page from before the tiles gets them, on, in their places
        val saved = HomeLayout(HomeLayout.defaults(RowsPage.HOME, libs, charts).rows.filterNot { it.type == HomeRowType.SERVICES || it.type == HomeRowType.GENRES })
        val after = saved.reconciled(RowsPage.HOME, libs, charts, tiles).rows.filter { it.on && it.type != HomeRowType.LIBRARY }.map { it.ref.ifBlank { it.type.name } }
        assertEquals(listOf("CONTINUE_WATCHING", "trend", "SERVICES", "new", "GENRES"), after)
        // Switched off, they stay off
        val off = HomeLayout(saved.rows + HomeRowSpec(HomeRowType.SERVICES, on = false)).reconciled(RowsPage.HOME, libs, charts, tiles)
        assertFalse(off.rows.first { it.type == HomeRowType.SERVICES }.on)
    }

    @Test fun `the cloud places because you watched and the every-library rows, library rows then start off`() {
        val charts = listOf(PageList("trend", "Trending", pages = listOf("HOME", "MOVIES"), order = mapOf("HOME" to 1, "MOVIES" to 1)))
        val tiles = mapOf(HomeRowType.SERVICES to 0, HomeRowType.BECAUSE_YOU_WATCHED to 2, HomeRowType.RECENT_MOVIES to 3, HomeRowType.NEW_EPISODES to 4)
        val home = HomeLayout.defaults(RowsPage.HOME, libs, charts, tiles).rows
        assertEquals(
            listOf("CONTINUE_WATCHING", "SERVICES", "trend", "BECAUSE_YOU_WATCHED", "RECENT_MOVIES", "NEW_EPISODES"),
            home.filter { it.on }.map { it.ref.ifBlank { it.type.name } },
        )
        // One Recently Added row for every movie library instead of one per library
        assertTrue(home.filter { it.type == HomeRowType.LIBRARY }.none { it.on })
        // Movies has no New Episodes: its show libraries aren't offered, the movie ones start off
        val movies = HomeLayout.defaults(RowsPage.MOVIES, libs, charts, tiles).rows
        assertEquals(listOf("CONTINUE_WATCHING", "SERVICES", "trend", "BECAUSE_YOU_WATCHED", "RECENT_MOVIES"), movies.filter { it.on }.map { it.ref.ifBlank { it.type.name } })
        // A page saved before the row existed gets it in its place; the every-library rows it had off stay off
        val saved = HomeLayout(HomeLayout.defaults(RowsPage.HOME, libs, charts, mapOf(HomeRowType.SERVICES to 0)).rows.filter { it.type != HomeRowType.BECAUSE_YOU_WATCHED })
        val after = saved.reconciled(RowsPage.HOME, libs, charts, tiles).rows.filter { it.on && it.type != HomeRowType.LIBRARY }.map { it.ref.ifBlank { it.type.name } }
        assertEquals(listOf("CONTINUE_WATCHING", "SERVICES", "trend", "BECAUSE_YOU_WATCHED"), after)
        // New & Popular has no Continue Watching, so nothing to go by
        assertFalse(RowsPage.NEW_POPULAR.offers(HomeRowType.BECAUSE_YOU_WATCHED))
    }

    @Test fun `a profile from a newer orca still loads, without the rows and pages it doesn't know`() {
        val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
        val synced =
            """{"layouts":{"HOME":{"rows":[{"type":"CONTINUE_WATCHING"},{"type":"HOLOGRAM_ROW"},{"type":"MY_LIST","on":false}]},""" +
                """"ARCADE":{"rows":[{"type":"MY_LIST"}]},"SHOWS":{"rows":[{"type":"NEW_EPISODES"}]}}}"""
        val s = json.decodeFromString<com.wholphinplus.sources.sync.OrcaSettings>(synced)
        assertEquals(setOf(RowsPage.HOME, RowsPage.SHOWS), s.layouts.keys)
        assertEquals(listOf(HomeRowType.CONTINUE_WATCHING, HomeRowType.MY_LIST), s.layouts.getValue(RowsPage.HOME).rows.map { it.type })
        // And it writes back the same way
        assertEquals(s, json.decodeFromString<com.wholphinplus.sources.sync.OrcaSettings>(json.encodeToString(s)))
    }

    @Test fun `kids starts with continue watching and the cloud's kids rows, never the library's newest`() {
        val charts =
            listOf(
                PageList("family", "Family Movie Night", series = false, pages = listOf("HOME", "KIDS"), order = mapOf("HOME" to 3, "KIDS" to 1)),
                PageList("preschool", "Preschool Favorites", series = true, pages = listOf("KIDS"), order = mapOf("KIDS" to 2)),
                PageList("trend", "Trending Movies", series = false, pages = listOf("HOME"), order = mapOf("HOME" to 0)),
            )
        val kids = HomeLayout.defaults(RowsPage.KIDS, libs, charts + PageList("mine", "My own list"), mapOf(HomeRowType.BECAUSE_YOU_WATCHED to 0)).rows
        assertEquals(listOf("CONTINUE_WATCHING", "BECAUSE_YOU_WATCHED", "family", "preschool"), kids.filter { it.on }.map { it.ref.ifBlank { it.type.name } })
        // No library, newest, My List or genre rows to switch on there
        assertTrue(kids.all { it.type in setOf(HomeRowType.CONTINUE_WATCHING, HomeRowType.BECAUSE_YOU_WATCHED, HomeRowType.COLLECTION) })
    }

    @Test fun `a saved page gets services right under continue watching once, then it can move`() {
        val charts = listOf(PageList("trend", "Trending", pages = listOf("HOME"), order = mapOf("HOME" to 1)))
        // Saved before the move: Services further down
        val old = HomeLayout(HomeLayout.defaults(RowsPage.HOME, libs, charts, mapOf(HomeRowType.SERVICES to 3)).rows)
        val tiles = mapOf(HomeRowType.SERVICES to 0)
        val moved = old.reconciled(RowsPage.HOME, libs, charts, tiles)
        assertEquals(listOf("CONTINUE_WATCHING", "SERVICES", "trend"), moved.rows.filter { it.on && it.type != HomeRowType.LIBRARY }.map { it.ref.ifBlank { it.type.name } })
        assertTrue(moved.servicesTop)
        // Moved down afterwards, it stays where it was put
        val down = moved.moved("SERVICES:", 1)
        assertEquals(down.rows, down.reconciled(RowsPage.HOME, libs, charts, tiles).rows)
    }

    @Test fun `only charts are numbered top 10 rows`() {
        listOf("Hulu Top 10 Movies", "Top Movies of the week", "Top Watched Movies of The Week").forEach { assertTrue(it, CinemaRepository.isTopList(it)) }
        listOf("Top 250 Movies (iMDB)", "Top Rated Movies of All Time", "Top Movies", "IMDb Moviemeter (Top 100)").forEach { assertFalse(it, CinemaRepository.isTopList(it)) }
    }
}
