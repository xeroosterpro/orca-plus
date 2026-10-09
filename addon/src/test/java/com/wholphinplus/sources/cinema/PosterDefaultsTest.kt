package com.wholphinplus.sources.cinema

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PosterDefaultsTest {
    private val json = Json { ignoreUnknownKeys = true }
    private val full = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    @Test fun `a new TV starts with Standard - no quality tags, no scores on cards, titles under posters`() {
        val o = PosterOverlays()
        assertEquals(PosterOverlays.STANDARD, o)
        assertTrue(o.top10 && o.watched && o.newLabels && o.services && o.titleLogos && o.captions && o.progress)
        assertFalse(o.resolution || o.hdr || o.audio || o.rating)
        assertFalse(RatingPrefs().onCards)
        assertTrue(RatingPrefs().onTitlePage)
    }

    @Test fun `clean keeps the title under the poster, everything adds every tag`() {
        val clean = PosterOverlays.CLEAN
        assertTrue(clean.captions && clean.titleLogos && clean.progress)
        assertFalse(clean.top10 || clean.watched || clean.newLabels || clean.services || clean.resolution)
        val all = PosterOverlays.EVERYTHING
        assertTrue(all.resolution && all.hdr && all.audio && all.rating && all.top10 && all.watched && all.captions)
    }

    @Test fun `tags saved before the new defaults read back as they were saved`() {
        // The old Standard as a TV saved it: only what differed from that day's defaults
        val back = PosterOverlays.fromSaved(json, """{"resolution":true,"top10":true,"watched":true}""")
        assertTrue(back.resolution && back.top10 && back.watched)
        assertFalse(back.captions)
        // A save from back when top10 / watched / captions were off by default, left out of the file
        val bare = PosterOverlays.fromSaved(json, """{"resolution":true}""")
        assertFalse(bare.top10 || bare.watched || bare.captions)
        // Scores were on cards by default; a save that left that out keeps them on
        assertTrue(RatingPrefs.fromSaved(json, """{"onTitlePage":true}""").onCards)
    }

    @Test fun `tags saved now keep every value`() {
        val saved = full.encodeToString(PosterOverlays.serializer(), PosterOverlays.STANDARD)
        assertEquals(PosterOverlays.STANDARD, PosterOverlays.fromSaved(json, saved))
        val scores = full.encodeToString(RatingPrefs.serializer(), RatingPrefs())
        assertFalse(RatingPrefs.fromSaved(json, scores).onCards)
    }
}
