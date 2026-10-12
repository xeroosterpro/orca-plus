package com.wholphinplus.sources.cinema

import com.wholphinplus.sources.core.TitleArt
import org.jellyfin.sdk.model.api.BaseItemKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.util.UUID

/** A full-screen grid's sort and filters ([GridFilter.apply]). */
class GridFilterTest {
    private fun item(
        n: Long,
        title: String = "Title $n",
        added: Long? = null,
        released: LocalDate? = null,
        year: Int? = released?.year,
        genres: List<String> = emptyList(),
        played: Boolean = false,
        score: Float? = null,
        tmdb: Int? = n.toInt(),
    ) = CinemaItem(
        id = UUID(0, n), kind = BaseItemKind.MOVIE, detailsId = UUID(0, n), detailsKind = BaseItemKind.MOVIE, title = title, subtitle = null,
        meta = emptyList(), rating = null, overview = "", backdropUrl = null, cardUrl = null, cardHasTitleArt = false, logoUrl = null, badge = null,
        resumeMs = 0, progress = null, tmdbId = tmdb, added = added, releasedDay = released?.toEpochDay(), year = year, genres = genres,
        played = played, score = score,
    )

    private val art =
        mapOf(
            1 to TitleArt(tmdbScore = 6.1, lang = "en"),
            2 to TitleArt(tmdbScore = 8.4, lang = "ko"),
            3 to TitleArt(tmdbScore = 7.0, lang = "en"),
        )

    private fun List<CinemaItem>.ids() = map { it.id.leastSignificantBits }

    private fun GridFilter.on(items: List<CinemaItem>) = apply(items) { art[it.tmdbId] }.ids()

    private val titles =
        listOf(
            item(1, "The Matrix", added = 300, released = LocalDate.of(1999, 3, 31), genres = listOf("Action", "Science Fiction")),
            item(2, "Parasite", added = 100, released = LocalDate.of(2019, 5, 30), genres = listOf("Thriller", "Drama"), played = true),
            item(3, "Arrival", added = null, released = LocalDate.of(2016, 11, 11), genres = listOf("Drama", "Science Fiction")),
            item(4, "Zodiac", added = 200, released = null, year = null, tmdb = null),
        )

    @Test fun `as listed keeps the row's own order`() {
        assertEquals(listOf(1L, 2, 3, 4), GridFilter().on(titles))
        assertFalse(GridFilter().active)
    }

    @Test fun `recently added puts titles without a date last`() {
        assertEquals(listOf(1L, 4, 2, 3), GridFilter(sort = GridSort.ADDED).on(titles))
    }

    @Test fun `newest and oldest by release day, undated last both ways`() {
        assertEquals(listOf(2L, 3, 1, 4), GridFilter(sort = GridSort.NEWEST).on(titles))
        assertEquals(listOf(1L, 3, 2, 4), GridFilter(sort = GridSort.OLDEST).on(titles))
    }

    @Test fun `top rated uses the server's score, else TMDB's`() {
        assertEquals(listOf(2L, 3, 1, 4), GridFilter(sort = GridSort.RATED).on(titles))
        val withServer = titles.map { if (it.id.leastSignificantBits == 1L) it.copy(score = 9.5f) else it }
        assertEquals(listOf(1L, 2, 3, 4), GridFilter(sort = GridSort.RATED).on(withServer))
    }

    @Test fun `a to z skips a leading The`() {
        assertEquals(listOf(3L, 1, 2, 4), GridFilter(sort = GridSort.TITLE).on(titles))
    }

    @Test fun `year, decade, genre, language and unwatched narrow it down`() {
        assertEquals(listOf(2L, 3), GridFilter(decade = 2010).on(titles))
        assertEquals(listOf(1L), GridFilter(year = 1999).on(titles))
        assertEquals(listOf(1L, 3), GridFilter(genre = "science fiction").on(titles))
        assertEquals(listOf(1L, 3), GridFilter(language = "en").on(titles))
        assertEquals(listOf(1L, 3, 4), GridFilter(unwatched = true).on(titles))
        assertEquals(listOf(3L), GridFilter(genre = "Drama", unwatched = true).on(titles))
        assertTrue(GridFilter(unwatched = true).narrowed)
    }

    @Test fun `year choices list each decade before its years, newest first`() {
        assertEquals(listOf(-2010 to 2, 2019 to 1, 2016 to 1, -1990 to 1, 1999 to 1), yearChoices(titles))
    }

    @Test fun `genre choices most common first`() {
        assertEquals("Science Fiction" to 2, genreChoices(titles).first())
    }
}
