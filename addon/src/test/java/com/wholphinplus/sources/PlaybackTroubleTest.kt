package com.wholphinplus.sources

import com.wholphinplus.sources.PlaybackTrouble.Culprit
import com.wholphinplus.sources.PlaybackTrouble.Signals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackTroubleTest {
    private fun explain(
        s: Signals,
        server: String = PlaybackTrouble.MAIN,
    ) = PlaybackTrouble.explain(s, server)

    @Test fun `a server failing to hand over the file is the server's problem, even behind the fallback's no-URL`() {
        // 2026-10-08: direct play died on bad data while the server 404'd, then the fallback found no URL
        val t = explain(Signals(parse = true, noStream = true, serverFailures = 9))
        assertEquals(Culprit.SERVER, t.culprit)
        assertEquals("Your server isn't sending this video right now", t.headline)
        assertTrue(t.explanation.contains("not your TV or your internet"))
        assertTrue(t.technical.contains("9 \"not found\" answers"))
        assertFalse("never the old permission hint", t.explanation.contains("permission"))
    }

    @Test fun `a 404 after the retries names the extra server it came from`() {
        val t = explain(Signals(httpCode = 404, serverFailures = 8), server = "Jellyfin Beta")
        assertEquals(Culprit.SERVER, t.culprit)
        assertEquals("Jellyfin Beta isn't sending this video right now", t.headline)
        assertTrue(t.technical.startsWith("server answered 404"))
    }

    @Test fun `refused is a sign-in or permission matter`() {
        assertEquals("Your server turned this video away", explain(Signals(httpCode = 403)).headline)
        assertEquals("Your server turned this video away", explain(Signals(httpCode = 401, serverFailures = 5)).headline)
    }

    @Test fun `no connection, damaged copy, format, no conversion, unknown`() {
        assertEquals(Culprit.CONNECTION, explain(Signals(network = true)).culprit)
        assertEquals(Culprit.FILE, explain(Signals(parse = true)).culprit)
        assertTrue(explain(Signals(parse = true, noStream = true)).explanation.contains("can't convert"))
        assertEquals(Culprit.TV, explain(Signals(decoder = true)).culprit)
        assertEquals("Your server won't stream this copy", explain(Signals(noStream = true)).headline)
        assertEquals(Culprit.UNKNOWN, explain(Signals()).culprit)
        assertEquals("", explain(Signals()).technical)
    }

    @Test fun `one or two stray failures don't blame the server`() {
        assertEquals(Culprit.FILE, explain(Signals(parse = true, serverFailures = 2)).culprit)
    }

    @Test fun `the no-URL message is read from Wholphin's text`() {
        val s = PlaybackTrouble.signalsOf(emptyList(), "Unable to get media URL from the server. Do you have permission to view and/or transcode?", 0)
        assertTrue(s.noStream)
    }

    @Test fun `the TV's formats come before the server's misses`() {
        assertEquals(Culprit.TV, explain(Signals(decoder = true, serverFailures = 9)).culprit)
        assertEquals(Culprit.TV, explain(Signals(decoder = true, httpCode = 404)).culprit)
        assertEquals(Culprit.FILE, explain(Signals(parse = true, serverFailures = 1)).culprit)
    }

    @Test fun `stream health counts only recent 404s, and a play clears them`() {
        StreamHealth.played()
        StreamHealth.failed(404, now = 1_000)
        StreamHealth.failed(404, now = 100_000)
        StreamHealth.failed(503, now = 110_000)
        StreamHealth.failed(null, now = 120_000)
        StreamHealth.failed(404, now = 130_000)
        assertEquals(2, StreamHealth.recent(now = 130_000))
        StreamHealth.played()
        assertEquals(0, StreamHealth.recent(now = 130_000))
    }
}
