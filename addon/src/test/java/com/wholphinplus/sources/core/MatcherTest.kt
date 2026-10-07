package com.wholphinplus.sources.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MatcherTest {
    private fun score(
        title: String,
        year: Int?,
        imdb: String?,
        tmdb: Int?,
        tvdb: Int?,
        candidate: CandidateInfo,
    ) = Matcher.score(title, year, imdb, tmdb, tvdb, candidate)

    private fun source(
        quality: String,
        hdr: String,
        size: Long,
        compatible: Boolean = false,
    ) = ExternalSource(
        "c",
        "s",
        ServerKind.PLEX,
        "http://x/$quality/$hdr/$size/$compatible",
        quality,
        qualityRank(quality),
        "",
        hdr,
        "",
        "",
        size,
        "",
        compatible = compatible,
    )

    @Test fun `title match without year or ids is enough`() {
        val s = score("Example Show", null, "tt42", 42, null, CandidateInfo("Example Show", null, emptyMap()))
        assertEquals(160, s)
        assertTrue(Matcher.isAcceptable(s))
    }

    @Test fun `a different tmdb id vetoes an exact title`() {
        val s = score("Example Show", null, null, 42, null, CandidateInfo("Example Show", null, mapOf("tmdb" to "99")))
        assertEquals(0, s)
        assertFalse(Matcher.isAcceptable(s))
    }

    @Test fun `ids beat an older remake with the same title`() {
        val right = score("Suits", 2011, "tt1632701", 37680, null, CandidateInfo("Suits", 2011, mapOf("imdb" to "tt1632701", "tmdb" to "37680")))
        val wrong = score("Suits", 2011, "tt1632701", 37680, null, CandidateInfo("Suits", 1990, emptyMap()))
        assertEquals(2150, right)
        assertEquals(40, wrong)
        assertTrue(right > wrong)
        assertTrue(Matcher.isAcceptable(right))
        assertFalse(Matcher.isAcceptable(wrong))
    }

    @Test fun `title and year accept items that have no ids`() {
        val s = score("The Pitt", 2025, null, null, null, CandidateInfo("The Pitt", 2025, emptyMap()))
        assertEquals(250, s)
        assertTrue(Matcher.isAcceptable(s))
    }

    @Test fun `partial titles and year distance`() {
        assertEquals(155, score("Dune", 2024, null, null, null, CandidateInfo("Dune: Part Two", 2024, emptyMap())))
        assertEquals(-55, score("Dune", 2021, null, null, null, CandidateInfo("Dune: Part Two", 2024, emptyMap())))
        val heat = { year: Int -> score("Heat", 1995, null, null, null, CandidateInfo("Heat", year, emptyMap())) }
        assertEquals(250, heat(1995))
        assertEquals(205, heat(1996))
        assertEquals(175, heat(1997))
        assertEquals(40, heat(1998))
    }

    @Test fun `provider keys and imdb ids ignore case and padding`() {
        assertEquals(1000, score("X", null, " TT0111161 ", null, null, CandidateInfo("Y", null, mapOf("Imdb" to "tt0111161"))))
    }

    @Test fun `one matching id outweighs another conflicting id`() {
        assertEquals(1150, score("X", 2000, "tt1", 5, null, CandidateInfo("X", 2000, mapOf("imdb" to "tt2", "tmdb" to "5"))))
        assertEquals(1105, score("X", 2000, null, null, 7, CandidateInfo("X", 2001, mapOf("tvdb" to "7"))))
    }

    @Test fun `a non numeric id is not a conflict`() {
        assertEquals(250, score("X", 2000, null, 5, null, CandidateInfo("X", 2000, mapOf("tmdb" to "abc"))))
    }

    @Test fun `acceptance threshold is 150`() {
        assertTrue(Matcher.isAcceptable(150))
        assertFalse(Matcher.isAcceptable(149))
    }

    @Test fun `same version keeps editions and rejects other titles`() {
        val br2049 = CandidateInfo("Blade Runner 2049", 2017, emptyMap())
        assertTrue(Matcher.isLikelySameVersion("Blade Runner 2049", 2017, br2049))
        assertFalse(Matcher.isLikelySameVersion("Blade Runner 2049", 2017, CandidateInfo("Blade Runner", 1982, emptyMap())))
        assertTrue(Matcher.isLikelySameVersion("Heat", 1995, CandidateInfo("Heat", 1996, emptyMap())))
        assertFalse(Matcher.isLikelySameVersion("Heat", 1995, CandidateInfo("Heat", 1997, emptyMap())))
        assertTrue(Matcher.isLikelySameVersion("Heat", null, CandidateInfo("Heat", 1986, emptyMap())))
        assertTrue(Matcher.isLikelySameVersion("Heat", 1995, CandidateInfo("Heat", null, emptyMap())))
        assertFalse(Matcher.isLikelySameVersion("", null, CandidateInfo("", null, emptyMap())))
    }

    @Test fun `titles lose accents articles and punctuation`() {
        assertEquals("amelie", Matcher.normalizeTitle("Amélie"))
        assertEquals("fast and furious", Matcher.normalizeTitle("The Fast & the Furious"))
        assertEquals("i artificial intelligence", Matcher.normalizeTitle("A.I. Artificial Intelligence"))
        assertEquals("spider man no way home", Matcher.normalizeTitle("Spider-Man: No Way Home"))
        assertEquals("wall e", Matcher.normalizeTitle("WALL·E"))
        assertEquals("leon professional", Matcher.normalizeTitle("Léon: The Professional"))
        assertEquals("", Matcher.normalizeTitle("  The  "))
        assertEquals("", Matcher.normalizeTitle("東京物語"))
        assertEquals("theater", Matcher.normalizeTitle("Theater"))
    }

    @Test fun `product name decides the server kind first`() {
        assertEquals(ServerKind.EMBY, detectServerKind("Emby Server", "Plex Mirror"))
        assertEquals(ServerKind.JELLYFIN, detectServerKind("Jellyfin Server", "My Plex"))
        assertEquals(ServerKind.PLEX, detectServerKind("", "plex box"))
        assertEquals(ServerKind.PLEX, detectServerKind("Plex Media Server", "Emby"))
        assertEquals(ServerKind.JELLYFIN, detectServerKind("", "Silo"))
        assertEquals(ServerKind.JELLYFIN, detectServerKind("Silo Server", ""))
        assertEquals(ServerKind.UNKNOWN, detectServerKind("Silo Media", ""))
        assertEquals(ServerKind.UNKNOWN, detectServerKind("", ""))
    }

    @Test fun `server addresses keep ports and proxy prefixes but drop the web client`() {
        assertEquals("http://10.0.0.5:8096", normalizeServerUrl("10.0.0.5:8096/web/index.html"))
        assertEquals("https://media.example.com/emby", normalizeServerUrl("https://media.example.com/emby/web/"))
        assertEquals("http://host:32400", normalizeServerUrl("http://host:32400/"))
        assertEquals("http://example.com/jellyfin", normalizeServerUrl("HTTP://Example.COM:80/jellyfin/?x=1#f"))
        assertEquals("https://host", normalizeServerUrl("https://host:443"))
        assertEquals("http://jellyfin.local:8096", normalizeServerUrl("jellyfin.local:8096/web/#/home.html"))
        assertEquals("http://host", normalizeServerUrl("http://host/WEB"))
        assertEquals("http://host/website", normalizeServerUrl("http://host/website"))
        assertEquals("http://host/web/abc", normalizeServerUrl("http://host/web/abc"))
        assertEquals("http://host/a/web/b/index.html", normalizeServerUrl("http://host/a/web/b/index.html"))
        assertEquals("", normalizeServerUrl("   "))
        assertEquals("", normalizeServerUrl("host:abc"))
    }

    @Test fun `host labels and endpoints`() {
        assertEquals("10.0.0.5:8096", hostLabel("http://10.0.0.5:8096"))
        assertEquals("media.example.com", hostLabel("https://media.example.com"))
        assertEquals("x", hostLabel("https://x:443"))
        assertEquals("x:443", hostLabel("http://x:443"))
        assertEquals("x:80", hostLabel("https://x:80"))
        assertEquals("", hostLabel("x"))
        assertTrue(sameEndpoint("http://a:32400", "https://A:32400/x"))
        assertTrue(sameEndpoint("http://a", "http://a:80"))
        assertFalse(sameEndpoint("http://a", "https://a"))
        assertFalse(sameEndpoint("a:1", "a:1"))
    }

    @Test fun `quality labels use width for scope encodes and the name as a last resort`() {
        assertEquals("4K", qualityLabel(2160, 3840, ""))
        assertEquals("4K", qualityLabel(1600, 3840, ""))
        assertEquals("1440p", qualityLabel(1440, 2560, ""))
        assertEquals("1080p", qualityLabel(800, 1920, ""))
        assertEquals("720p", qualityLabel(536, 1280, ""))
        assertEquals("576p", qualityLabel(576, 720, ""))
        assertEquals("480p", qualityLabel(480, 640, ""))
        assertEquals("360p", qualityLabel(360, 640, ""))
        assertEquals("4K", qualityLabel(0, 0, "Movie.2160p.mkv"))
        assertEquals("4K", qualityLabel(0, 0, "Movie.UHD.BluRay.mkv"))
        assertEquals("720p", qualityLabel(0, 0, "Show.720p.mkv"))
        assertEquals("", qualityLabel(0, 0, "Movie.mkv"))
    }

    @Test fun `quality and hdr ranks`() {
        assertEquals(2160, qualityRank("4K"))
        assertEquals(2160, qualityRank("2160p"))
        assertEquals(1440, qualityRank("1440p"))
        assertEquals(1080, qualityRank("1080p"))
        assertEquals(720, qualityRank("720p"))
        assertEquals(576, qualityRank("576p"))
        assertEquals(480, qualityRank("480p"))
        assertEquals(0, qualityRank("360p"))
        assertEquals(0, qualityRank("?"))
        assertEquals(0, qualityRank(""))
        assertEquals(3, hdrRank("Dolby Vision"))
        assertEquals(2, hdrRank("HDR10+"))
        assertEquals(1, hdrRank("HDR10"))
        assertEquals(1, hdrRank("HLG"))
        assertEquals(0, hdrRank(""))
    }

    @Test fun `ranking prefers resolution then dolby vision then size`() {
        val sorted =
            listOf(
                source("1080p", "", 50),
                source("4K", "HDR10", 40),
                source("4K", "Dolby Vision", 30),
                source("4K", "Dolby Vision", 60),
            ).sortedWith(sourceRanking)
        assertEquals(listOf(60L, 30L, 40L, 50L), sorted.map { it.sizeBytes })
    }

    @Test fun `identical copies keep the Settings order of servers whatever order they arrived in`() {
        fun copy(server: String) = source("4K", "Dolby Vision", 70).copy(connectionId = server, serverLabel = server.uppercase(), url = "http://$server/midway")
        val order = listOf("b", "c", "a")
        val expected = listOf("b", "c", "a")
        listOf(listOf("a", "b", "c"), listOf("c", "a", "b"), listOf("b", "a", "c")).forEach { arrival ->
            assertEquals(expected, arrival.map(::copy).sortedWith(stableRanking(order)).map { it.connectionId })
        }
        // Quality still comes first
        val better = source("4K", "Dolby Vision", 90).copy(connectionId = "a")
        assertEquals("a", (order.map(::copy) + better).sortedWith(stableRanking(order)).first().connectionId)
    }

    @Test fun `direct play beats a bigger server conversion of the same quality`() {
        val sorted = listOf(source("4K", "HDR10", 20, compatible = true), source("4K", "HDR10", 10)).sortedWith(sourceRanking)
        assertEquals(listOf(false, true), sorted.map { it.compatible })
    }
}
