package com.wholphinplus.sources.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ListsTest {
    @Test fun `mdblist links`() {
        val s = ListSource.parse("https://mdblist.com/lists/linaspurinis/top-watched-movies-of-the-week?sort=rank") as ListSource.MdbList
        assertEquals("https://mdblist.com/lists/linaspurinis/top-watched-movies-of-the-week", s.url)
        assertEquals("Top Watched Movies Of The Week", ListSource.prettify(s.slug))
        assertTrue(ListSource.parse("mdblist.com/lists/a/b") is ListSource.MdbList)
    }

    @Test fun `trakt links`() {
        assertEquals("users/bob/lists/sci-fi", (ListSource.parse("https://trakt.tv/users/bob/lists/sci-fi?sort=rank,asc") as ListSource.Trakt).apiPath)
        assertEquals("users/bob/watchlist", (ListSource.parse("trakt.tv/users/bob/watchlist") as ListSource.Trakt).apiPath)
        assertEquals("lists/12345", (ListSource.parse("https://app.trakt.tv/lists/12345") as ListSource.Trakt).apiPath)
    }

    @Test fun `other links are rejected`() {
        assertNull(ListSource.parse("https://www.imdb.com/list/ls000"))
        assertNull(ListSource.parse("hello"))
    }

    @Test fun `top streaming catalog links`() {
        val url = "https://top-streaming.stream/0000aaaa-1111-2222-3333-444455556666/catalog/movie/hulu-movies-united-states.json"
        assertEquals(url, (ListSource.parse(url) as ListSource.TopStreaming).url)
        // Your own row stands in for the cloud's copy of the same chart
        assertEquals("ts-movie-hulu-movies-united-states", (ListSource.parse(url) as ListSource.TopStreaming).chartId)
        assertNull(ListSource.parse("https://top-streaming.stream/configure/"))
    }

    @Test fun `orca chart links`() {
        val s = ListSource.parse("https://orca-cloud-production.up.railway.app/v1/charts#trakt-trending-movies") as ListSource.OrcaChart
        assertEquals("trakt-trending-movies", s.id)
        assertEquals("https://orca-cloud-production.up.railway.app", s.base)
        assertNull(ListSource.parse("https://orca-cloud-production.up.railway.app/v1/charts"))
    }
}
