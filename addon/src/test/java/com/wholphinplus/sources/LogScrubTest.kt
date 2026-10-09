package com.wholphinplus.sources

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class LogScrubTest {
    private fun clean(s: String) = LogScrub.text(s)

    @Test fun `query tokens go, the rest of the URL stays`() {
        assertEquals(
            "https://media.example.org/Videos/1/stream?Static=true&api_key=…&MediaSourceId=2",
            clean("https://media.example.org/Videos/1/stream?Static=true&api_key=abc123&MediaSourceId=2"),
        )
        assertEquals("http://10.0.0.5:32400/library/parts/9/file.mkv?X-Plex-Token=…", clean("http://10.0.0.5:32400/library/parts/9/file.mkv?X-Plex-Token=SeCrEt"))
        assertEquals("u?ApiKey=…&x=1", clean("u?ApiKey=k1&x=1"))
        assertEquals("u?access_token=…", clean("u?access_token=t"))
    }

    @Test fun `a data class string loses its tokens`() {
        val s = clean("Copy(source=ExternalSource(url=https://e.example.org/v?api_key=TOKEN1, serverLabel=Emby Alpha, token=TOKEN2))")
        assertFalse(s.contains("TOKEN"))
        assertEquals(true, s.contains("serverLabel=Emby Alpha"))
    }

    @Test fun `headers and JSON lose their values`() {
        assertFalse(clean("Authorization: MediaBrowser Client=\"Orca\", Token=\"SECRET\"").contains("SECRET"))
        assertFalse(clean("headers={Authorization=MediaBrowser Token=\"SECRET\"}").contains("SECRET"))
        assertEquals("X-Plex-Token: … next", clean("X-Plex-Token: SECRET next"))
        assertEquals("{\"AccessToken\":\"…\",\"User\":\"x\"}", clean("{\"AccessToken\":\"SECRET\",\"User\":\"x\"}"))
        assertEquals("Token=\"…\" rest", clean("Token=\"SECRET\" rest"))
    }

    @Test fun `ordinary lines are left alone`() {
        val line = "Stream open failed (Response code: 404), try 2: /Videos/1/stream.mkv"
        assertEquals(line, clean(line))
        assertEquals("Monkey business, keys 3", clean("Monkey business, keys 3"))
        assertEquals("", LogScrub.text(null))
    }
}
