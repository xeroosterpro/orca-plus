package com.wholphinplus.sources.cinema

import org.jellyfin.sdk.model.api.BaseItemKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

/** How a fresh load meets the page already shown: failed rows, random rows, placeholders. */
class PageMergeTest {
    private fun item(n: Long) =
        CinemaItem(
            id = UUID(0, n), kind = BaseItemKind.MOVIE, detailsId = UUID(0, n), detailsKind = BaseItemKind.MOVIE, title = "Title $n", subtitle = null,
            meta = emptyList(), rating = null, overview = "", backdropUrl = null, cardUrl = null, cardHasTitleArt = false, logoUrl = null, badge = null,
            resumeMs = 0, progress = null,
        )

    private fun row(
        title: String,
        vararg ids: Long,
        source: RowSource? = null,
    ) = CinemaRow(title, ids.map(::item), source = source)

    private fun page(vararg rows: CinemaRow) = CinemaHomeData(listOf(item(99)), rows.toList(), null, null)

    @Test fun `the billboard keeps its titles in their fresh copies`() {
        val stale = item(1).copy(subtitle = "S1:E1")
        val shown = CinemaHomeData(listOf(stale, item(7)), listOf(row("Continue Watching", 1)), null, null)
        val fresh = CinemaHomeData(listOf(item(8)), listOf(CinemaRow("Continue Watching", listOf(item(1).copy(subtitle = "S2:E14")))), null, null)
        val merged = fresh.over(shown)
        assertEquals(listOf("S2:E14", null), merged.featured.map { it.subtitle })
        assertEquals(listOf(UUID(0, 1), UUID(0, 7)), merged.featured.map { it.id })
    }

    @Test fun `a failed row keeps its place with the copy shown before`() {
        val fresh = listOf(row("Continue Watching", 1), CinemaRow("Action", emptyList(), failed = true), row("Comedy", 5))
        val shown = page(row("Continue Watching", 1), row("Action", 2, 3), row("Comedy", 4))
        val merged = withPreviousRows(fresh, listOf(shown))
        assertEquals(listOf("Continue Watching", "Action", "Comedy"), merged.map { it.title })
        assertEquals(listOf(item(2), item(3)), merged[1].items)
        assertFalse(merged[1].failed)
        // Rows that loaded take their fresh titles
        assertEquals(listOf(item(5)), merged[2].items)
    }

    @Test fun `a failed row never shown before leaves this load, and placeholders are no copy`() {
        val fresh = listOf(CinemaRow("Action", emptyList(), failed = true), CinemaRow("Drama", emptyList(), failed = true), row("Comedy", 5))
        val first = page(CinemaRow("Action", emptyList(), loading = true))
        val merged = withPreviousRows(fresh, listOf(first, page(row("Drama", 7))))
        // Action: only a placeholder before, so it goes; Drama: found on the older page
        assertEquals(listOf("Drama", "Comedy"), merged.map { it.title })
        assertEquals(listOf(item(7)), merged[0].items)
    }

    @Test fun `the fresh page over the shown one keeps the billboard and the random rows`() {
        val genre = RowSource(RowsPage.HOME, HomeRowSpec(HomeRowType.GENRE, ref = "Action"))
        val shuffled = RowSource(RowsPage.HOME, HomeRowSpec(HomeRowType.RECENT_MOVIES), seed = 7)
        val shown = page(row("Action", 1, 2, source = genre), row("Recently Added Movies", 3, source = shuffled), row("My List", 4))
        val fresh = CinemaHomeData(listOf(item(50)), listOf(row("Action", 8, 9, source = genre), row("Recently Added Movies", 10, source = shuffled.copy(seed = 8)), row("My List", 4, 11), row("New", 12)), null, null)
        val merged = fresh.over(shown)
        assertEquals(shown.featured, merged.featured)
        assertEquals(listOf(item(1), item(2)), merged.rows[0].items)
        assertEquals(7L, merged.rows[1].source?.seed)
        assertEquals(listOf(item(4), item(11)), merged.rows[2].items)
        assertEquals(4, merged.rows.size)
    }

    @Test fun `placeholders don't make a page look richer`() {
        val first = page(row("Continue Watching", 1), CinemaRow("Action", emptyList(), loading = true), CinemaRow("Comedy", emptyList(), loading = true))
        val full = page(row("Continue Watching", 1), row("Action", 2))
        assertTrue(full.richerThan(first))
        assertFalse(first.richerThan(full))
    }
}
