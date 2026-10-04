package com.wholphinplus.sources

import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
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
}
