package com.wholphinplus.sources.core

import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The playback audit's matching fixes (2026-10-08), against canned answers. */
class PlaybackMatchingTest {
    private fun client(
        routes: Map<String, String>,
        down: Boolean = false,
    ): ServerClient {
        val fake =
            Interceptor { chain ->
                if (down) throw java.net.ConnectException("down")
                val url = chain.request().url
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

    private val emby = ServerConnection(connectionId = "emby", serverUrl = "http://emby.local:8096", serverName = "A", serverKind = ServerKind.EMBY, userId = "u1", accessToken = "tok")
    private val plex =
        ServerConnection(
            connectionId = "plex",
            serverUrl = "http://plex.local:32400",
            serverName = "B",
            serverKind = ServerKind.PLEX,
            serverId = "abcdef0123456789",
            userId = "plex",
            accessToken = "ptok",
            collections = listOf(ServerCollection("1", "Movies", "movie")),
        )

    @Test fun `no series match means no episode, not another show's episode of that number`() {
        // "Lost" isn't on this server; an episode called "Lost and Found" of another show is
        val stranger = """{"Items":[{"Id":"x5","Name":"Lost and Found","ParentIndexNumber":1,"IndexNumber":5,"MediaSources":[{"Id":"ms","Container":"mkv"}]}]}"""
        val c = client(mapOf("IncludeItemTypes=Series" to """{"Items":[]}""", "IncludeItemTypes=Episode" to stranger))
        val found = runBlocking { c.findSourcesOrNull(emby, PlayRequest("Lost", 2004, null, null, 73739, season = 1, episode = 5)) }
        assertEquals(emptyList<ExternalSource>(), found)
    }

    @Test fun `a server that never answered is a failure, not "not here"`() {
        val c = client(emptyMap(), down = true)
        assertNull(runBlocking { c.findSourcesOrNull(emby, PlayRequest("Dune", 2021, "tt1160419", null, null)) })
        // Plex lookups fail quietly inside; still a failure
        assertNull(runBlocking { c.findSourcesOrNull(plex, PlayRequest("Dune", 2021, "tt1160419", null, null)) })
        // findSources keeps its old promise: an empty list
        assertTrue(runBlocking { c.findSources(emby, PlayRequest("Dune", 2021, "tt1160419", null, null)) }.isEmpty())
    }

    @Test fun `a server that answered "nothing" is a real answer`() {
        val c = client(mapOf("/Items?" to """{"Items":[]}"""))
        assertEquals(emptyList<ExternalSource>(), runBlocking { c.findSourcesOrNull(emby, PlayRequest("Dune", 2021, "tt1160419", null, null)) })
    }

    private fun plexMovie(parts: String) =
        """{"MediaContainer":{"Metadata":[{"ratingKey":"7","title":"Dune","year":2021,"Guid":[{"id":"imdb://tt1160419"}],
        "Media":[{"width":1920,"height":1080,"container":"mkv","videoFrameRate":"24p","Part":[$parts]}]}]}}"""

    @Test fun `plex movies in parts are not offered, single files are, with their frame rate`() {
        val two = """{"id":"1","key":"/library/parts/1/a.mkv","file":"/m/a.cd1.mkv"},{"id":"2","key":"/library/parts/2/b.mkv","file":"/m/a.cd2.mkv"}"""
        val c2 = client(mapOf("guid=imdb://tt1160419" to plexMovie(two), "/library/metadata/7" to """{"MediaContainer":{"Metadata":[]}}"""))
        assertTrue(runBlocking { c2.findSources(plex, PlayRequest("Dune", 2021, "tt1160419", null, null)) }.isEmpty())
        val one = """{"id":"1","key":"/library/parts/1/a.mkv","file":"/m/a.mkv"}"""
        val c1 = client(mapOf("guid=imdb://tt1160419" to plexMovie(one), "/library/metadata/7" to """{"MediaContainer":{"Metadata":[]}}"""))
        val s = runBlocking { c1.findSources(plex, PlayRequest("Dune", 2021, "tt1160419", null, null)) }.single()
        assertEquals(1920, s.width)
        assertEquals(23.976f, s.frameRate)
    }

    @Test fun `the plex token goes only to the plex server itself`() {
        val elsewhere = """{"id":"1","key":"https://cdn.example.com/a.mkv","file":"/m/a.mkv"}"""
        val c = client(mapOf("guid=imdb://tt1160419" to plexMovie(elsewhere), "/library/metadata/7" to """{"MediaContainer":{"Metadata":[]}}"""))
        val s = runBlocking { c.findSources(plex, PlayRequest("Dune", 2021, "tt1160419", null, null)) }.single()
        assertFalse(s.url, s.url.contains("ptok"))
        val own = """{"id":"1","key":"https://10-0-0-5.abcdef0123456789.plex.direct:32400/library/parts/1/a.mkv","file":"/m/a.mkv"}"""
        val c2 = client(mapOf("guid=imdb://tt1160419" to plexMovie(own), "/library/metadata/7" to """{"MediaContainer":{"Metadata":[]}}"""))
        assertTrue(runBlocking { c2.findSources(plex, PlayRequest("Dune", 2021, "tt1160419", null, null)) }.single().url.contains("X-Plex-Token=ptok"))
    }

    @Test fun `plex titles resume where plex left off`() {
        val c = client(mapOf("/library/metadata/7" to """{"MediaContainer":{"Metadata":[{"ratingKey":"7","viewOffset":600000,"viewCount":0,"lastViewedAt":1760000000}]}}"""))
        val d = c.userData(plex, "7")!!
        assertEquals(600_000L * 10_000L, d.positionTicks)
        assertFalse(d.played)
    }

    private fun copy(
        q: String,
        hdr: String = "",
        compatible: Boolean = false,
        size: Long = 1,
    ) = ExternalSource("c", "S", ServerKind.PLEX, "u$q$hdr$compatible$size", q, qualityRank(q), "", hdr, "", "", size, "", compatible)

    @Test fun `autoplay takes the copy most like the one chosen`() {
        val found = listOf(copy("4K", "Dolby Vision", size = 80), copy("1080p", size = 10), copy("1080p", compatible = true), copy("720p"))
        assertEquals(found[1], closestCopy(copy("1080p"), found))
        assertEquals(found[2], closestCopy(copy("1080p", compatible = true), found))
        assertEquals(found[0], closestCopy(copy("4K", "Dolby Vision"), found))
        // Only a conversion there and a direct file chosen: the conversion still beats nothing
        assertEquals(found[2], closestCopy(copy("1080p"), listOf(found[2])))
    }

    @Test fun `servers behind one address with different paths are different servers`() {
        assertFalse(sameServer("https://media.example.com/jellyfin", "https://media.example.com/emby"))
        assertTrue(sameServer("http://media.example.com:8096/", "http://media.example.com:8096/web/index.html"))
        assertTrue(sameServer("https://Media.example.com/Jellyfin", "https://media.example.com/jellyfin/"))
    }
}
