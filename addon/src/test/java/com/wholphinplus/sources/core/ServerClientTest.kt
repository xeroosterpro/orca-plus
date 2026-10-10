package com.wholphinplus.sources.core

import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Runs discovery against canned Emby and Plex responses, no network. */
class ServerClientTest {
    private val requests = mutableListOf<String>()

    private fun client(routes: Map<String, String>): ServerClient {
        val fake =
            Interceptor { chain ->
                val url = chain.request().url
                requests += "${chain.request().method} ${url.encodedPath}?${url.query.orEmpty()}"
                val body = routes.entries.firstOrNull { (k, _) -> "${url.encodedPath}?${url.query.orEmpty()}".contains(k) }?.value
                Response
                    .Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(if (body != null) 200 else 404)
                    .message("")
                    .body((body ?: "").toResponseBody("application/json".toMediaType()))
                    .build()
            }
        return ServerClient(OkHttpClient.Builder().addInterceptor(fake).build(), "dev1", "Test", "1")
    }

    private val emby =
        ServerConnection(
            connectionId = "emby",
            serverUrl = "http://emby.local:8096",
            serverName = "Server A",
            serverKind = ServerKind.EMBY,
            userId = "u1",
            accessToken = "tok",
        )

    @Test fun `emby movie found by imdb id plays direct with codec details`() {
        val item =
            """{"Items":[{"Id":"m1","Name":"Dune: Part Two","ProductionYear":2024,"ProviderIds":{"Imdb":"tt15239678"},
            "MediaSources":[{"Id":"ms1","Container":"mkv","Size":64000000000,"Path":"/m/dune.mkv","Name":"Dune 2160p",
            "TranscodingUrl":"/videos/m1/master.m3u8?x=1",
            "MediaStreams":[{"Type":"Video","Codec":"hevc","Width":3840,"Height":2160,"VideoRangeType":"DOVIWithHDR10","BitDepth":10},
            {"Type":"Audio","Codec":"truehd","Channels":8,"IsDefault":true,"DisplayTitle":"English - TrueHD Atmos 7.1"}]}]}]}"""
        val c = client(mapOf("AnyProviderIdEquals=imdb.tt15239678" to item, "/PlaybackInfo" to "{}"))
        val sources = runBlocking { c.findSources(emby, PlayRequest("Dune: Part Two", 2024, "tt15239678", null, null)) }
        assertEquals(1, sources.size)
        val s = sources.single()
        assertEquals("4K", s.quality)
        assertEquals("Dolby Vision", s.hdr)
        assertEquals("HEVC", s.videoCodec)
        assertEquals("TrueHD Atmos 7.1", s.audio)
        assertTrue(s.url, s.url.startsWith("http://emby.local:8096/Videos/m1/stream.mkv?Static=true&MediaSourceId=ms1"))
        assertTrue("direct play, not the server transcode", !s.url.contains("master.m3u8"))
        assertTrue(s.url.contains("api_key=tok"))
    }

    @Test fun `plex episode found through series guid and allLeaves`() {
        val plex =
            ServerConnection(
                connectionId = "plex",
                serverUrl = "http://plex.local:32400",
                serverName = "Server B",
                serverKind = ServerKind.PLEX,
                userId = "plex",
                accessToken = "ptok",
                collections = listOf(ServerCollection("2", "TV", "show")),
            )
        val series = """{"MediaContainer":{"Metadata":[{"ratingKey":"100","title":"Severance","year":2022,"Guid":[{"id":"tvdb://371980"}]}]}}"""
        val leaves =
            """{"MediaContainer":{"Metadata":[
            {"ratingKey":"201","title":"Ep","parentIndex":2,"index":3,"Media":[{"width":1920,"height":1080,"container":"mkv","videoCodec":"h264",
              "Part":[{"id":"9","key":"/library/parts/9/123/file.mkv","file":"/tv/sev.s02e03.mkv","size":4000000000}]}]},
            {"ratingKey":"202","title":"Other","parentIndex":2,"index":4,"Media":[]}]}}"""
        val c =
            client(
                mapOf(
                    "guid=tvdb://371980" to series,
                    "/library/metadata/100/allLeaves" to leaves,
                    "/library/metadata/201" to """{"MediaContainer":{"Metadata":[]}}""",
                ),
            )
        val sources = runBlocking { c.findSources(plex, PlayRequest("Severance", 2022, null, null, 371980, season = 2, episode = 3)) }
        assertEquals(1, sources.size)
        assertEquals("1080p", sources.single().quality)
        assertEquals("http://plex.local:32400/library/parts/9/123/file.mkv?X-Plex-Token=ptok", sources.single().url)
    }

    @Test fun `unreachable server gives no sources instead of failing`() {
        val c = client(emptyMap())
        val sources = runBlocking { c.findSources(emby, PlayRequest("Anything", 2020, "tt1", null, null)) }
        assertTrue(sources.isEmpty())
    }

    @Test fun `recent activity reads resume and played items with series ids`() {
        val resume = """{"Items":[{"Id":"e5","Type":"Episode","SeriesId":"s1","ParentIndexNumber":2,"IndexNumber":5,
            "UserData":{"PlaybackPositionTicks":6000000000,"Played":false,"LastPlayedDate":"2026-10-02T20:00:00.0000000Z"}}]}"""
        val played = """{"Items":[{"Id":"m1","Type":"Movie","Name":"Dune","ProductionYear":2021,"ProviderIds":{"Imdb":"tt1160419"},
            "UserData":{"Played":true,"LastPlayedDate":"2026-10-01T20:00:00Z"}},
            {"Id":"old","Type":"Movie","Name":"Old","UserData":{"Played":true,"LastPlayedDate":"2025-01-01T00:00:00Z"}}]}"""
        val series = """{"Id":"s1","Name":"Severance","ProductionYear":2022,"ProviderIds":{"Tvdb":"371980"}}"""
        val c = client(mapOf("/Items/Resume" to resume, "Filters=IsPlayed" to played, "/Users/u1/Items/s1" to series))
        val got = c.recentWatchActivity(emby, java.time.Instant.parse("2026-09-01T00:00:00Z"), 20)
        assertEquals(listOf("e5", "m1"), got.map { it.itemId })
        assertEquals(PlayRequest("Severance", 2022, null, null, 371980, 2, 5), got[0].request)
        assertEquals(6000000000L, got[0].positionTicks)
        assertTrue(got[1].played)
        assertEquals("tt1160419", got[1].request.imdbId)
    }

    @Test fun `emby library titles newest first, file names cleaned, tmdb id from the name`() {
        val page =
            """{"Items":[
            {"Id":"1","Name":"Rise (2022) {imdb-tt13531468} {tmdb-771077}","Type":"Movie","ProductionYear":2022,"RunTimeTicks":60000000000,
             "BackdropImageTags":["b1"],"ImageTags":{"Primary":"p1"}},
            {"Id":"2","Name":"Severance","Type":"Series","ProductionYear":2022,"ChildCount":2,"ProviderIds":{"Tmdb":"95396"},"ImageTags":{"Thumb":"t2"}}]}"""
        val c = client(mapOf("/Users/u1/Items" to page))
        val got = c.libraryTitles(emby, "lib9", 40, 40)
        assertTrue(requests.single(), requests.single().contains("ParentId=lib9") && requests.single().contains("StartIndex=40") && requests.single().contains("SortBy=DateCreated"))
        assertEquals("Rise", got[0].name)
        assertEquals(771077, got[0].tmdbId)
        assertEquals(100, got[0].minutes)
        assertEquals("http://emby.local:8096/Items/1/Images/Backdrop/0?maxWidth=480&quality=90&tag=b1&api_key=tok", got[0].cardUrl)
        assertTrue(got[1].series)
        assertEquals(95396, got[1].tmdbId)
        assertEquals(2, got[1].seasons)
        assertTrue(got[1].cardHasTitleArt)
    }

    @Test fun `plex library titles read the section with paging and keep only movies and shows`() {
        val plex =
            ServerConnection(connectionId = "plex", serverUrl = "http://plex.local:32400", serverName = "Server B", serverKind = ServerKind.PLEX, userId = "plex", accessToken = "ptok")
        val page =
            """{"MediaContainer":{"Metadata":[
            {"ratingKey":"7","type":"movie","title":"Heat","year":1995,"duration":10200000,"art":"/library/metadata/7/art/1","Guid":[{"id":"tmdb://949"}]},
            {"ratingKey":"8","type":"collection","title":"Box"}]}}"""
        val c = client(mapOf("/library/sections/3/all" to page))
        val got = c.libraryTitles(plex, "3", 0, 40)
        assertTrue(requests.single(), requests.single().contains("sort=addedAt:desc") && requests.single().contains("X-Plex-Container-Size=40"))
        assertEquals(1, got.size)
        assertEquals(949, got[0].tmdbId)
        assertEquals(170, got[0].minutes)
        assertTrue(got[0].cardUrl.orEmpty(), got[0].cardUrl.orEmpty().startsWith("http://plex.local:32400/photo/:/transcode?width=480") && got[0].cardUrl.orEmpty().contains("X-Plex-Token=ptok"))
    }
}
