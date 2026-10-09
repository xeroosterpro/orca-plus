package com.wholphinplus.sources.cinema

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test

class PosterSizeTest {
    private val server = "https://jf.example/Items/abc/Images/Backdrop/0?maxWidth=480&quality=90&tag=t&api_key=k"
    private val tmdb = "https://image.tmdb.org/t/p/w780/abc.jpg"

    @After
    fun reset() {
        PosterSize.mode = PosterSize.AUTO
    }

    @Test
    fun sharpLeavesPicturesAlone() {
        PosterSize.mode = PosterSize.SHARP
        assertEquals(server, PosterSize.fit(server))
        assertEquals(tmdb, PosterSize.fit(tmdb))
    }

    @Test
    fun fastAsksThreeHundred() {
        PosterSize.mode = PosterSize.FAST
        assertEquals(server.replace("maxWidth=480", "maxWidth=300"), PosterSize.fit(server))
        assertEquals("https://image.tmdb.org/t/p/w300/abc.jpg", PosterSize.fit(tmdb))
        // Already small: unchanged
        val small = server.replace("maxWidth=480", "maxWidth=200")
        assertEquals(small, PosterSize.fit(small))
    }
}
