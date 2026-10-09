package com.wholphinplus.sources

import com.wholphinplus.sources.sync.Profile
import com.wholphinplus.sources.sync.ProfileMerge
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Continue Watching's long-press menu: Remove and Mark as watched, kept by the progress overlay. */
class ContinueDismissTest {
    private val now = System.currentTimeMillis()
    private val dayAgo = java.time.Instant.ofEpochMilli(now - 86_400_000L).toString()

    private fun client(
        overlay: ProgressOverlay,
        routes: Map<String, String>,
    ): OkHttpClient {
        val server =
            Interceptor { chain ->
                val u = chain.request().url
                val key = u.encodedPath + "?" + u.query.orEmpty()
                val body = routes.entries.firstOrNull { key.contains(it.key) }?.value ?: "{}"
                Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("")
                    .body(body.toResponseBody("application/json; charset=utf-8".toMediaType())).build()
            }
        return OkHttpClient.Builder().addInterceptor(overlay).addInterceptor(server).build()
    }

    private fun get(
        c: OkHttpClient,
        url: String,
    ) = c.newCall(Request.Builder().url(url).build()).execute().body.string()

    private val resume =
        """{"Items":[
            {"Id":"m1","Name":"Movie","UserData":{"PlaybackPositionTicks":5,"LastPlayedDate":"$dayAgo"}},
            {"Id":"e7","SeriesId":"s1","Name":"Episode","UserData":{"PlaybackPositionTicks":5,"LastPlayedDate":"$dayAgo"}}
        ]}"""

    @Test fun `a removed movie leaves Continue Watching until it's played again`() {
        val o = ProgressOverlay(null, null)
        val c = client(o, mapOf("/UserItems/Resume" to resume, "/Items?" to """{"Items":[]}"""))
        o.dismiss("m1")
        val gone = get(c, "https://silo.test/UserItems/Resume?userId=u&limit=10")
        assertTrue(gone, !gone.contains("\"m1\"") && gone.contains("\"e7\""))
        assertTrue(o.isDismissed("m1", null))
        // Played again afterwards: back in the row
        o.record("m1", ProgressOverlay.Entry(9, false, now + 60_000, null, "Movie"))
        val back = get(c, "https://silo.test/UserItems/Resume?userId=u&limit=10")
        assertTrue(back, back.contains("\"m1\""))
        assertFalse(o.isDismissed("m1", null))
    }

    @Test fun `a removed show stays out of Next Up until a newer episode is played`() {
        val o = ProgressOverlay(null, null)
        o.record("e6", ProgressOverlay.Entry(0, true, now - 3_600_000L, "s1", "Show"))
        val nextUp = """{"Items":[{"Id":"e9","SeriesId":"s1","UserData":{"Played":false}},{"Id":"x1","SeriesId":"s2","UserData":{"Played":false}}]}"""
        val episodes = """{"Items":[{"Id":"e6"},{"Id":"e7","SeriesId":"s1","UserData":{"Played":false}}]}"""
        val c = client(o, mapOf("/Shows/NextUp" to nextUp, "/Shows/s1/Episodes" to episodes, "/UserItems/Resume" to resume, "/Items?" to """{"Items":[]}"""))
        o.dismiss("e7", seriesId = "s1")
        val up = get(c, "https://silo.test/Shows/NextUp?userId=u&limit=10")
        assertTrue(up, !up.contains("SeriesId\":\"s1") && up.contains("\"x1\""))
        val res = get(c, "https://silo.test/UserItems/Resume?userId=u&limit=10")
        assertTrue(res, !res.contains("\"e7\"") && res.contains("\"m1\""))
        // The show's own page still offers where it was left
        val page = get(c, "https://silo.test/Shows/NextUp?userId=u&seriesId=s1&limit=1")
        assertTrue(page, page.contains("SeriesId\":\"s1"))
        // A newer episode played: the show comes back
        o.record("e8", ProgressOverlay.Entry(0, true, now + 60_000, "s1", "Show"))
        assertFalse(o.isDismissed("e9", "s1"))
    }

    @Test fun `mark as watched takes a movie out and moves a show on`() {
        val o = ProgressOverlay(null, null)
        o.markWatched("m1")
        val episodes = """{"Items":[{"Id":"e7"},{"Id":"e8","SeriesId":"s1","UserData":{"Played":false}}]}"""
        o.markWatched("e7", seriesId = "s1", title = "Show")
        val c = client(o, mapOf("/UserItems/Resume" to resume, "/Items?" to """{"Items":[]}""", "/Shows/NextUp" to """{"Items":[]}""", "/Shows/s1/Episodes" to episodes))
        val res = get(c, "https://silo.test/UserItems/Resume?userId=u&limit=10")
        assertTrue(res, !res.contains("\"m1\"") && !res.contains("\"e7\""))
        val up = get(c, "https://silo.test/Shows/NextUp?userId=u&limit=10")
        assertTrue(up, up.contains("\"e8\""))
        // A watched mark is never older than what was kept before
        val e = o.snapshot()[ProgressOverlay.norm("e7")]!!
        assertTrue(e.played && e.seriesId == "s1")
    }

    @Test fun `hidden only while nothing was played after the removal`() {
        val gone = mapOf("a" to 100L, ProgressOverlay.SERIES_KEY + "s" to 200L)
        assertTrue(ProgressOverlay.hiddenBy(gone, "a", null, own = 100, show = 100))
        assertFalse(ProgressOverlay.hiddenBy(gone, "a", null, own = 101, show = 101))
        assertTrue(ProgressOverlay.hiddenBy(gone, "b", "s", own = 0, show = 150))
        assertFalse(ProgressOverlay.hiddenBy(gone, "b", "s", own = 0, show = 250))
        assertFalse(ProgressOverlay.hiddenBy(gone, "b", null, own = 0, show = 0))
    }

    @Test fun `removals sync as a union, the later time winning`() {
        val merged = ProgressOverlay.mergeDismissed(mapOf("a" to 1L, "b" to 5L), mapOf("b" to 3L, "c" to 7L))
        assertEquals(mapOf("a" to 1L, "b" to 5L, "c" to 7L), merged)
        val p = ProfileMerge.merge(Profile(dismissed = mapOf("a" to 1L)), Profile(dismissed = mapOf("a" to 2L, "s:x" to 4L)), emptySet(), now = 9)
        assertEquals(mapOf("a" to 2L, "s:x" to 4L), p.dismissed)
        // Absorbed on a TV: the later stays, an earlier copy can't undo it
        val o = ProgressOverlay(null, null)
        o.dismiss("a", at = 10)
        o.absorbDismissed(mapOf("a" to 5L, "z" to 3L))
        assertEquals(mapOf(ProgressOverlay.norm("a") to 10L, "z" to 3L), o.dismissedSnapshot())
    }

    @Test fun `marked unwatched on a server that keeps marks wins over the watched mark here`() {
        val o = ProgressOverlay(null, null)
        o.markWatched("abababababababababababababababab", at = now - 60_000)
        var played = "true"
        val server =
            Interceptor { chain ->
                val body = """{"Id":"abababababababababababababababab","UserData":{"Played":$played,"PlaybackPositionTicks":0,"LastPlayedDate":"$dayAgo"}}"""
                Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("")
                    .body(body.toResponseBody("application/json; charset=utf-8".toMediaType())).build()
            }
        val c = OkHttpClient.Builder().addInterceptor(o).addInterceptor(server).build()
        get(c, "https://srv.test/Items/abababababababababababababababab?userId=u")
        assertTrue(o.snapshot()[ProgressOverlay.norm("abababababababababababababababab")]!!.serverSaw)
        // Unmarked on another device: the server says not watched, its last-played date unchanged
        played = "false"
        val body = get(c, "https://srv.test/Items/abababababababababababababababab?userId=u")
        assertTrue(body, body.contains("\"Played\":false"))
        val e = o.snapshot()[ProgressOverlay.norm("abababababababababababababababab")]!!
        assertFalse(e.played)
        assertTrue(e.lastPlayed > now - 60_000)
    }

    @Test fun `a server that never keeps marks (Silo) can't undo one`() {
        val o = ProgressOverlay(null, null)
        o.markWatched("abababababababababababababababab")
        val c = client(o, mapOf("/Items/abababababababababababababababab" to """{"Id":"abababababababababababababababab","UserData":{"Played":false,"PlaybackPositionTicks":0,"LastPlayedDate":"$dayAgo"}}"""))
        val body = get(c, "https://silo.test/Items/abababababababababababababababab?userId=u")
        assertTrue(body, body.contains("\"Played\":true"))
        assertTrue(o.snapshot()[ProgressOverlay.norm("abababababababababababababababab")]!!.played)
    }
}
