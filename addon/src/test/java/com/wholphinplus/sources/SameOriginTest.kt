package com.wholphinplus.sources

import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SameOriginTest {
    private fun same(
        base: String?,
        url: String,
    ) = SameOrigin.matches(base, url.toHttpUrl())

    @Test fun `the main server's own requests`() {
        assertTrue(same("https://media.example.org", "https://media.example.org/Items/1/Images/Primary"))
        assertTrue(same("https://Media.Example.org:443/jellyfin", "https://media.example.org/jellyfin/Items/1"))
        assertTrue(same("http://10.0.0.5:8096", "http://10.0.0.5:8096/Audio/1/universal"))
        assertTrue(same("http://[fd00::5]:8096", "http://[FD00::5]:8096/Items/1"))
        assertTrue(same("http://my_server.lan:8096", "http://my_server.lan:8096/Items/1"))
    }

    @Test fun `another server, even on the same host, gets nothing`() {
        assertFalse(same("http://10.0.0.5:8096", "http://10.0.0.5:8920/Items/1"))
        assertFalse(same("https://media.example.org", "http://media.example.org/Items/1"))
        assertFalse(same("https://media.example.org", "https://image.tmdb.org/t/p/w500/x.jpg"))
        assertFalse(same(null, "https://media.example.org/Items/1"))
        assertFalse(same("not a url", "https://media.example.org/Items/1"))
    }
}
