package com.wholphinplus.sources.cinema

import kotlinx.coroutines.runBlocking
import org.jellyfin.sdk.model.api.BaseItemKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

/** A row's browsed titles meeting a fresh copy of the row (page refresh, back from a title page). */
class RowRefreshTest {
    private fun item(n: Long) =
        CinemaItem(
            id = UUID(0, n), kind = BaseItemKind.MOVIE, detailsId = UUID(0, n), detailsKind = BaseItemKind.MOVIE, title = "Title $n", subtitle = null,
            meta = emptyList(), rating = null, overview = "", backdropUrl = null, cardUrl = null, cardHasTitleArt = false, logoUrl = null, badge = null,
            resumeMs = 0, progress = null,
        )

    private val action = RowSource(RowsPage.HOME, HomeRowSpec(HomeRowType.GENRE, ref = "Action"))
    private val comedy = RowSource(RowsPage.HOME, HomeRowSpec(HomeRowType.GENRE, ref = "Comedy"))

    private fun row(
        vararg ids: Long,
        source: RowSource? = action,
    ) = CinemaRow("Action", ids.map(::item), source = source)

    @Test fun `the same row keeps what was browsed, a changed row starts over`() {
        val base = row(1, 2)
        assertEquals(RowRefresh.MERGE, rowRefresh(base, action, row(1, 2)))
        assertEquals(RowRefresh.RESET, rowRefresh(base, action, row(1, 2, source = comedy)))
        assertEquals(RowRefresh.RESET, rowRefresh(base, action, row(1, 2, source = null)))
        // Continue Watching (no source) just takes the fresh titles
        assertEquals(RowRefresh.MERGE, rowRefresh(row(1, source = null), null, row(3, source = null)))
    }

    @Test fun `a shuffle made with the remote stays, a new random order from the page replaces it`() {
        val base = row(1, 2)
        assertEquals(RowRefresh.KEEP, rowRefresh(base, action.copy(seed = 5), row(1, 2)))
        val seeded = row(1, 2, source = action.copy(seed = 7))
        assertEquals(RowRefresh.RESET, rowRefresh(seeded, action.copy(seed = 7), row(3, 4, source = action.copy(seed = 8))))
        assertEquals(RowRefresh.MERGE, rowRefresh(seeded, action.copy(seed = 7), row(1, 2, source = action.copy(seed = 7))))
    }

    @Test fun `merged titles are the fresh first page then the ones loaded since`() {
        val base = listOf(item(1), item(2), item(3))
        val browsed = base + listOf(item(4), item(5), item(6))
        // A new title at the top; 3 dropped from the list; 5 moved into the first page
        val fresh = listOf(item(9), item(1), item(2), item(5))
        assertEquals(listOf(item(9), item(1), item(2), item(5), item(4), item(6)), mergedItems(base, browsed, fresh))
    }

    @Test fun `a row state keeps loaded titles and its shuffle through a refresh`() {
        val state = RowState(row(1, 2))
        val more = RowPage(listOf(item(3), item(4)), next = 4, end = false)
        runBlocking { state.more { _, _ -> more } }
        assertEquals(4, state.items.size)
        // Back from a title page: the page's own copy of the row comes again (a new object)
        state.refresh(row(1, 2))
        assertEquals(listOf(item(1), item(2), item(3), item(4)), state.items.toList())
        // A reload with a new first title keeps the loaded ones after it
        state.refresh(row(0, 1, 2))
        assertEquals(listOf(item(0), item(1), item(2), item(3), item(4)), state.items.toList())
        // Shuffled with the remote: a refresh doesn't undo it
        runBlocking { state.shuffle { _, _ -> RowPage(listOf(item(8), item(7)), next = 2, end = false) } }
        state.refresh(row(1, 2))
        assertEquals(listOf(item(8), item(7)), state.items.toList())
        // The row changed what it is: starts over
        state.refresh(row(5, source = comedy))
        assertEquals(listOf(item(5)), state.items.toList())
        assertEquals(comedy, state.source)
    }

    @Test fun `the count says the server's number until the row ends, then the titles there are`() {
        val state = RowState(row(1, 2).copy(total = 5))
        assertEquals(5, state.count)
        // A repeat is dropped (2), and the server's last page brings fewer than it counted
        runBlocking { state.more { _, _ -> RowPage(listOf(item(2), item(3), item(4)), next = 5, end = true) } }
        assertEquals(4, state.items.size)
        assertEquals(4, state.count)
        // No count from the server: none shown
        assertEquals(null, RowState(row(1)).count)
    }

    @Test fun `a load begun before the titles were replaced is dropped`() {
        val state = RowState(row(1, 2))
        runBlocking {
            state.more { _, _ ->
                // The page is refreshed while this page of titles is on its way
                state.refresh(row(1, 2, source = comedy))
                RowPage(listOf(item(3)), next = 3, end = false)
            }
        }
        assertEquals(listOf(item(1), item(2)), state.items.toList())
    }

    @Test fun `the least recently shown go first, down to three quarters`() {
        val keys = (1..8).map { "k$it" }
        val used = keys.associateWith { it.drop(1).toLong() } - "k8"
        // Never shown since stamps began (k8) is the oldest of all
        assertEquals(listOf("k8", "k1", "k2", "k3", "k4"), leastRecentlyUsed(keys, used, 5))
        assertTrue(leastRecentlyUsed(keys, used, 8).isEmpty())
    }

    @Test fun `a saved page is rewritten only when its rows or titles changed`() {
        val a = CinemaHomeData(listOf(item(1)), listOf(row(1, 2)), null, null)
        val sameTitles = a.copy(rows = listOf(row(1, 2).copy(items = listOf(item(1).copy(progress = 0.5f), item(2)))))
        assertEquals(snapshotShape("me", a), snapshotShape("me", sameTitles))
        assertNotEquals(snapshotShape("me", a), snapshotShape("me", a.copy(rows = listOf(row(2, 1)))))
        assertNotEquals(snapshotShape("me", a), snapshotShape("you", a))
    }

    @Test fun `card logos are asked at card size`() {
        assertEquals("https://s/Items/x/Images/Logo?maxWidth=300&quality=90", PosterSize.cardLogo("https://s/Items/x/Images/Logo?maxWidth=600&quality=90"))
        assertEquals("https://image.tmdb.org/t/p/w300/a.png", PosterSize.cardLogo("https://image.tmdb.org/t/p/w500/a.png"))
        assertEquals("https://image.tmdb.org/t/p/w300/a.png", PosterSize.cardLogo("https://image.tmdb.org/t/p/original/a.png"))
        assertEquals("https://image.tmdb.org/t/p/w185/a.png", PosterSize.cardLogo("https://image.tmdb.org/t/p/w185/a.png"))
    }

    @Test fun `a Top 10 row keeps its own places, other rows take the first chart's`() {
        val disney = CinemaRow("Disney+ Top 10 Shows", listOf(item(1), item(2)), ranked = true)
        val hulu = CinemaRow("Hulu Top 10 Shows", listOf(item(3), item(2), item(4)), ranked = true)
        val plain = CinemaRow("Trending", listOf(item(2), item(5)))
        val data = withRanks(CinemaHomeData(listOf(item(2)), listOf(disney, hulu, plain), null, null)) { it.title }
        assertEquals(listOf(1, 2, 3), data.rows[1].items.map { it.rank })
        assertEquals("Hulu Top 10 Shows", data.rows[1].items[1].rankLabel)
        assertEquals(listOf(2, null), data.rows[2].items.map { it.rank })
        assertEquals(2, data.featured[0].rank)
    }
}
