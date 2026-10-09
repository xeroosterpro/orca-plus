package com.wholphinplus.sources

import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ProgressOverlayTest {
    init {
        timber.log.Timber.plant(
            object : timber.log.Timber.Tree() {
                override fun log(priority: Int, tag: String?, message: String, t: Throwable?) {
                    println(message)
                    t?.printStackTrace(System.out)
                }
            },
        )
    }
    private val now = System.currentTimeMillis()
    private val iso = java.time.Instant.ofEpochMilli(now - 86_400_000L).toString() // server: a day ago

    private fun client(
        overlay: ProgressOverlay,
        routes: Map<String, String>,
    ): OkHttpClient {
        val server =
            Interceptor { chain ->
                val u = chain.request().url
                val key = u.encodedPath + "?" + u.query.orEmpty()
                val body = routes.entries.firstOrNull { key.contains(it.key) }?.value ?: "{}"
                Response
                    .Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("")
                    .body(body.toResponseBody("application/json; charset=utf-8".toMediaType()))
                    .build()
            }
        return OkHttpClient.Builder().addInterceptor(overlay).addInterceptor(server).build()
    }

    private fun get(
        c: OkHttpClient,
        url: String,
    ) = c.newCall(Request.Builder().url(url).build()).execute().body.string()

    @Test fun `newer progress replaces server user data on any item`() {
        val o = ProgressOverlay(null, null)
        o.record("aaaaaaaa-0000-0000-0000-000000000001", ProgressOverlay.Entry(44 * 600_000_000L, false, now, null, "BTTF3"))
        val c = client(o, mapOf("/Items/aaaa" to """{"Id":"aaaaaaaa-0000-0000-0000-000000000001","RunTimeTicks":70000000000,"UserData":{"PlaybackPositionTicks":0,"Played":false}}"""))
        val body = get(c, "https://silo.test/Items/aaaaaaaa-0000-0000-0000-000000000001?userId=u")
        assertTrue(body, body.contains("\"PlaybackPositionTicks\":26400000000"))
    }

    @Test fun `older overlay never overrides the server`() {
        val o = ProgressOverlay(null, null)
        o.record("b1", ProgressOverlay.Entry(5, false, now - 5 * 86_400_000L, null, ""))
        val c = client(o, mapOf("/Items/b1" to """{"Id":"b1","UserData":{"PlaybackPositionTicks":99,"LastPlayedDate":"$iso"}}"""))
        assertTrue(get(c, "https://silo.test/Items/b1").contains("\"PlaybackPositionTicks\":99"))
    }

    @Test fun `continue watching gets missing items, newest first, within the limit`() {
        val o = ProgressOverlay(null, null)
        o.record("c0ffee00000000000000000000000001", ProgressOverlay.Entry(10, false, now, null, "Supergirl"))
        val resume = """{"Items":[{"Id":"11111111111111111111111111111111","UserData":{"PlaybackPositionTicks":5,"LastPlayedDate":"$iso"}}]}"""
        val injected = """{"Items":[{"Id":"c0ffee00-0000-0000-0000-000000000001","Name":"Supergirl","UserData":{"PlaybackPositionTicks":0}}]}"""
        val c = client(o, mapOf("/UserItems/Resume" to resume, "/Items?" to injected))
        val body = get(c, "https://silo.test/UserItems/Resume?userId=u&limit=5&fields=Overview")
        assertTrue(body, body.indexOf("Supergirl") in 0 until body.indexOf("11111111111111111111111111111111"))
    }

    @Test fun `next up switches to the episode after one finished elsewhere`() {
        val o = ProgressOverlay(null, null)
        o.record("e10", ProgressOverlay.Entry(0, true, now, "s1", "Silo"))
        val nextUp = """{"Items":[{"Id":"e5","SeriesId":"s1","UserData":{"LastPlayedDate":"$iso"}}]}"""
        val episodes = """{"Items":[{"Id":"e10","UserData":{"Played":false}},{"Id":"e11","SeriesId":"s1","UserData":{"Played":false}}]}"""
        val c = client(o, mapOf("/Shows/NextUp" to nextUp, "/Shows/s1/Episodes" to episodes))
        val body = get(c, "https://silo.test/Shows/NextUp?userId=u&limit=10")
        assertTrue(body, body.contains("\"e11\"") && !body.contains("\"e5\""))
    }

    @Test fun `ids match with or without dashes`() {
        assertEquals(ProgressOverlay.norm("AAAAAAAA-0000-0000-0000-000000000001"), ProgressOverlay.norm("aaaaaaaa000000000000000000000001"))
    }

    @Test fun `collection rows are answered with library items in list order`() {
        val o = ProgressOverlay(null) { tag -> if (tag == "wholphinplus-list-t1") listOf("bbbb", "aaaa", "cccc") else null }
        val items = """{"Items":[{"Id":"aaaa","Name":"A"},{"Id":"bbbb","Name":"B"}]}"""
        val c = client(o, mapOf("/Items?" to items))
        val body = get(c, "https://silo.test/Items?userId=u&tags=wholphinplus-list-t1&recursive=true&limit=2&startIndex=0")
        assertTrue(body, body.indexOf("\"B\"") < body.indexOf("\"A\"") && body.contains("\"TotalRecordCount\":3"))
    }

    @Test fun `a collection row whose lookup fails fails, rather than answering empty`() {
        val o = ProgressOverlay(null) { tag -> if (tag == "wholphinplus-list-t1") listOf("aaaa") else null }
        val down =
            Interceptor { chain ->
                Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(503).message("").body("".toResponseBody(null)).build()
            }
        val c = OkHttpClient.Builder().addInterceptor(o).addInterceptor(down).build()
        val failed = runCatching { get(c, "https://silo.test/Items?userId=u&tags=wholphinplus-list-t1&limit=40") }.exceptionOrNull()
        assertTrue("expected an IOException, got $failed", failed is java.io.IOException)
    }
    @Test fun `each user sees only their own progress`() {
        val o = ProgressOverlay(null, null)
        val item = """{"Items":[{"Id":"aaaaaaaa000000000000000000000002","UserData":{"PlaybackPositionTicks":0,"Played":false}}]}"""
        val c = client(o, mapOf("/Items" to item))
        get(c, "https://silo.test/Items?userId=a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1")
        o.record("aaaaaaaa000000000000000000000002", ProgressOverlay.Entry(0, true, now, null, "A's"))
        assertTrue(get(c, "https://silo.test/Items?userId=a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1").contains("\"Played\":true"))
        // Another user on the same TV: the server's own answer
        assertTrue(get(c, "https://silo.test/Items?userId=b2b2b2b2b2b2b2b2b2b2b2b2b2b2b2b2").contains("\"Played\":false"))
        assertTrue(o.snapshot("b2b2b2b2b2b2b2b2b2b2b2b2b2b2b2b2").isEmpty())
        assertEquals(1, o.snapshot("a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1").size)
        // And back
        assertTrue(get(c, "https://silo.test/Users/a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1/Items").contains("\"Played\":true"))
    }

    @Test fun `progress from before users belongs to the first user seen`() {
        val o = ProgressOverlay(null, null)
        o.record("aaaaaaaa000000000000000000000003", ProgressOverlay.Entry(7, false, now, null, "old"))
        o.setUser("owner")
        o.setUser("guest")
        assertEquals(1, o.snapshot("owner").size)
        assertTrue(o.snapshot("guest").isEmpty())
    }

    @Test fun `marking watched and unwatched is kept`() {
        val o = ProgressOverlay(null, null)
        o.record("aaaaaaaa000000000000000000000004", ProgressOverlay.Entry(5_000, false, now - 60_000, "s", "Ep"), "u")
        val c = client(o, mapOf("/Items" to """{"Id":"aaaaaaaa000000000000000000000004","UserData":{"PlaybackPositionTicks":0,"Played":false}}"""))
        c.newCall(Request.Builder().url("https://silo.test/UserPlayedItems/aaaaaaaa000000000000000000000004?userId=u").post(ByteArray(0).toRequestBody()).build()).execute().close()
        val e = o.snapshot("u").getValue("aaaaaaaa000000000000000000000004")
        assertTrue(e.played)
        assertEquals("s", e.seriesId)
        c.newCall(Request.Builder().url("https://silo.test/UserPlayedItems/aaaaaaaa000000000000000000000004?userId=u").delete().build()).execute().close()
        assertTrue(!o.snapshot("u").getValue("aaaaaaaa000000000000000000000004").played)
    }

    @Test fun `old watch history is kept, only the oldest beyond the cap go`() {
        val o = ProgressOverlay(null, null)
        val yearsAgo = now - 3L * 365 * 86_400_000L
        o.record("aaaaaaaa000000000000000000000005", ProgressOverlay.Entry(0, true, yearsAgo, null, "Old"), "u")
        o.record("aaaaaaaa000000000000000000000006", ProgressOverlay.Entry(0, true, now, null, "New"), "u")
        assertEquals(2, o.snapshot("u").size)
        val many = (0 until ProgressOverlay.MAX_ENTRIES).associate { "%032x".format(it + 100) to ProgressOverlay.Entry(0, true, now - it, null, "") }
        o.absorb(many, "u")
        val kept = o.snapshot("u")
        assertEquals(ProgressOverlay.MAX_ENTRIES, kept.size)
        assertTrue("the oldest went first", "aaaaaaaa000000000000000000000005" !in kept)
    }

    @Test fun `an answer with nothing of ours comes back as the server sent it`() {
        val o = ProgressOverlay(null, null)
        o.record("aaaaaaaa000000000000000000000007", ProgressOverlay.Entry(1, false, now, null, ""))
        val raw = """{ "Items" : [ { "Id" : "bbbbbbbb000000000000000000000001", "Name":"X" } ] }"""
        val c = client(o, mapOf("/Items" to raw))
        assertEquals(raw, get(c, "https://silo.test/Items?userId=u"))
    }

    private fun w(
        played: Boolean,
        pos: Long,
        at: Long,
        season: Int = 2,
    ) = ProgressOverlay.Watch(season, played, pos, at)

    @Test fun `a show is resumed where it was left last, not at an older half-watched episode`() {
        // Silo's case: E7 half-watched at 12:50, E8-E13 finished after it, E14 next
        val eps = listOf(w(false, 834_200_000, 1_000)) + (1..6).map { w(true, 0, 1_000L + it * 100) } + listOf(w(false, 0, 0), w(false, 0, 0))
        assertEquals(7, ProgressOverlay.nextByLatest(eps))
        // A later half-watched episode is where it was left
        assertEquals(2, ProgressOverlay.nextByLatest(listOf(w(true, 7_000, 1_000), w(false, 0, 0), w(false, 6_000, 2_000), w(false, 0, 0))))
        // Specials are skipped after a normal episode
        assertEquals(2, ProgressOverlay.nextByLatest(listOf(w(true, 0, 1_000), w(false, 0, 0, season = 0), w(false, 0, 0))))
        // Nothing watched, or nothing left: no say
        assertEquals(null, ProgressOverlay.nextByLatest(listOf(w(false, 0, 0), w(false, 0, 0))))
        assertEquals(null, ProgressOverlay.nextByLatest(listOf(w(false, 5, 1_000), w(true, 0, 2_000))))
    }

    @Test fun `one show's next up moves on from a half-watched episode with later ones watched since`() {
        val o = ProgressOverlay(null, null)
        val old = java.time.Instant.ofEpochMilli(now - 3 * 86_400_000L).toString()
        val nextUp = """{"Items":[{"Id":"e7","SeriesId":"s1","UserData":{"PlaybackPositionTicks":800,"Played":false,"LastPlayedDate":"$old"}}]}"""
        val episodes =
            """{"Items":[{"Id":"e7","SeriesId":"s1","UserData":{"PlaybackPositionTicks":800,"Played":false,"LastPlayedDate":"$old"}},""" +
                """{"Id":"e8","SeriesId":"s1","UserData":{"Played":true,"LastPlayedDate":"$iso"}},{"Id":"e9","SeriesId":"s1","UserData":{"Played":false}}]}"""
        val c = client(o, mapOf("/Shows/NextUp" to nextUp, "/Shows/s1/Episodes" to episodes))
        val body = get(c, "https://silo.test/Shows/NextUp?userId=u&seriesId=s1&limit=1&enableResumable=true")
        assertTrue(body, body.contains("\"e9\"") && !body.contains("\"e7\""))
    }

    @Test fun `one show's next up never offers another show's episode`() {
        val o = ProgressOverlay(null, null)
        o.record("e10", ProgressOverlay.Entry(0, true, now, "s2", "Other show"))
        val nextUp = """{"Items":[{"Id":"e5","SeriesId":"s1","UserData":{"Played":false}}]}"""
        val c = client(o, mapOf("/Shows/NextUp" to nextUp, "/Shows/s2/Episodes" to """{"Items":[{"Id":"e10"},{"Id":"e11","SeriesId":"s2","UserData":{"Played":false}}]}"""))
        val body = get(c, "https://silo.test/Shows/NextUp?userId=u&seriesId=s1&limit=1&enableResumable=true")
        assertTrue(body, body.contains("\"e5\"") && !body.contains("\"e11\""))
    }

    @Test fun `continue watching drops a half-watched episode when a later one was finished here since`() {
        val o = ProgressOverlay(null, null)
        o.record("e8", ProgressOverlay.Entry(0, true, now, "s1", "Later"))
        val resume = """{"Items":[{"Id":"e7","SeriesId":"s1","UserData":{"PlaybackPositionTicks":5,"Played":false,"LastPlayedDate":"$iso"}}]}"""
        val c = client(o, mapOf("/UserItems/Resume" to resume, "/Items?" to """{"Items":[]}"""))
        val body = get(c, "https://silo.test/UserItems/Resume?userId=u&limit=5")
        assertTrue(body, !body.contains("\"e7\""))
    }
}
