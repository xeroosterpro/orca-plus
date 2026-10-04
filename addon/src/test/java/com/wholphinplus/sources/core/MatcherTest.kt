// Contains code adapted from a third-party project under the Apache License 2.0 and modified
// for Orca+. See NOTICE and LICENSES/Apache-2.0.txt.
package com.wholphinplus.sources.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MatcherTest {
    @Test fun `exact series title without year or provider metadata can match`() {
        val score = Matcher.score("Example Show", null, "tt42", 42, null, CandidateInfo("Example Show", null, emptyMap()))
        assertTrue(Matcher.isAcceptable(score))
    }

    @Test fun `exact title does not override conflicting provider identity`() {
        val score = Matcher.score("Example Show", null, null, 42, null, CandidateInfo("Example Show", null, mapOf("tmdb" to "99")))
        assertFalse(Matcher.isAcceptable(score))
    }

    @Test fun `external ids beat older title-only remakes`() {
        val right = Matcher.score("Suits", 2011, "tt1632701", 37680, null, CandidateInfo("Suits", 2011, mapOf("imdb" to "tt1632701", "tmdb" to "37680")))
        val wrong = Matcher.score("Suits", 2011, "tt1632701", 37680, null, CandidateInfo("Suits", 1990, emptyMap()))
        assertTrue(right > wrong)
        assertTrue(Matcher.isAcceptable(right))
        assertFalse(Matcher.isAcceptable(wrong))
    }

    @Test fun `title and year fallback allows local items without ids`() {
        assertTrue(Matcher.isAcceptable(Matcher.score("The Pitt", 2025, null, null, null, CandidateInfo("The Pitt", 2025, emptyMap()))))
    }

    @Test fun `matching title versions are retained while unrelated titles are rejected`() {
        assertTrue(Matcher.isLikelySameVersion("Blade Runner 2049", 2017, CandidateInfo("Blade Runner 2049", 2017, emptyMap())))
        assertFalse(Matcher.isLikelySameVersion("Blade Runner 2049", 2017, CandidateInfo("Blade Runner", 1982, emptyMap())))
    }

    @Test fun `normalizing strips accents articles and punctuation`() {
        assertEquals("amelie", Matcher.normalizeTitle("Amélie"))
        assertEquals("fast and furious", Matcher.normalizeTitle("The Fast & the Furious"))
    }

    @Test fun `emby named after plex is still emby`() {
        assertEquals(ServerKind.EMBY, detectServerKind("Emby Server", "Plex Mirror"))
        assertEquals(ServerKind.JELLYFIN, detectServerKind("Jellyfin Server", "My Plex"))
        assertEquals(ServerKind.PLEX, detectServerKind("", "plex box"))
    }

    @Test fun `server urls keep ports and proxy prefixes but drop web client paths`() {
        assertEquals("http://10.0.0.5:8096", normalizeServerUrl("10.0.0.5:8096/web/index.html"))
        assertEquals("https://media.example.com/emby", normalizeServerUrl("https://media.example.com/emby/web/"))
        assertEquals("http://host:32400", normalizeServerUrl("http://host:32400/"))
    }

    @Test fun `ranking prefers resolution then dolby vision then size`() {
        fun src(q: String, hdr: String, size: Long) =
            ExternalSource("c", "s", ServerKind.PLEX, "u$q$hdr$size", q, qualityRank(q), "", hdr, "", "", size, "")
        val sorted =
            listOf(src("1080p", "", 50), src("4K", "HDR10", 40), src("4K", "Dolby Vision", 30), src("4K", "Dolby Vision", 60))
                .sortedWith(sourceRanking)
        assertEquals(listOf(60L, 30L, 40L, 50L), sorted.map { it.sizeBytes })
    }
}
