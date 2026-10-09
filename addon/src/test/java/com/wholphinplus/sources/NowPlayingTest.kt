package com.wholphinplus.sources

import com.wholphinplus.sources.core.ExternalSource
import com.wholphinplus.sources.core.ServerKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NowPlayingTest {
    private val remux =
        ExternalSource(
            connectionId = "bk7",
            serverLabel = "Emby Alpha",
            serverKind = ServerKind.EMBY,
            url = "u",
            quality = "4K",
            qualityRank = 4,
            videoCodec = "HEVC",
            hdr = "Dolby Vision",
            audio = "TrueHD Atmos 7.1",
            container = "mkv",
            sizeBytes = 1,
            fileName = "f",
        )

    @Test fun `a direct play is described by the file's own labels`() {
        val i = NowPlaying.info("DIRECT_PLAY", remux, isMain = false, PlayedFormats(1920, 1080, "video/avc"))
        assertEquals("Direct play", i.method)
        assertFalse(i.transcoding)
        assertEquals("4K", i.quality)
        assertEquals("Dolby Vision", i.hdr)
        assertEquals("TrueHD Atmos 7.1", i.audio)
        assertEquals("Emby Alpha", i.server)
    }

    @Test fun `a transcode is described by what the player receives`() {
        val played = PlayedFormats(1920, 1080, "video/avc", -1, "audio/mp4a-latm", 2)
        val i = NowPlaying.info("TRANSCODE", remux.copy(connectionId = "main"), isMain = true, played)
        assertEquals("Transcode", i.method)
        assertTrue(i.transcoding)
        assertEquals("1080p", i.quality)
        assertEquals("H.264", i.videoCodec)
        assertEquals("AAC 2.0", i.audio)
        assertEquals(PlaybackTrouble.MAIN, i.server)
    }

    @Test fun `formats the player reports read as people know them`() {
        assertEquals("HDR10", NowPlaying.hdr("video/hevc", 6))
        assertEquals("Dolby Vision", NowPlaying.hdr("video/dolby-vision", -1))
        val atmos = NowPlaying.info("DIRECT_STREAM", null, isMain = true, PlayedFormats(3840, 2160, "video/hevc", 6, "audio/eac3-joc", 6))
        assertEquals("DD+ Atmos 5.1", atmos.audio)
        assertEquals("Direct stream", atmos.method)
    }
}
