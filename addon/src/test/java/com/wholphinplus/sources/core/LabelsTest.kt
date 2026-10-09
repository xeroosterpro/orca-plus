package com.wholphinplus.sources.core

import org.junit.Assert.assertEquals
import org.junit.Test

class LabelsTest {
    @Test fun `jellyfin eac3 display title becomes DD+`() {
        assertEquals("DD+ 5.1", Labels.audio("eac3", "", 6, "EAC3 5.1 - ATSC A/52B (AC-3, E-AC-3)", "Show.S02E13.2160p.WEB.mkv"))
    }

    @Test fun `atmos comes from the track or the release name`() {
        assertEquals("TrueHD Atmos 7.1", Labels.audio("truehd", "", 8, "English - TrueHD Atmos 7.1", ""))
        assertEquals("DD+ Atmos 5.1", Labels.audio("eac3", "", 6, "", "Movie.2160p.DDP5.1.Atmos.DV.mkv"))
        assertEquals("DD 5.1", Labels.audio("ac3", "", 6, "", "Movie.Atmos.mkv"))
    }

    @Test fun `dts variants`() {
        assertEquals("DTS-HD MA 7.1", Labels.audio("dts", "DTS-HD MA", 8, "", ""))
        assertEquals("DTS:X 7.1", Labels.audio("dca", "", 8, "", "Movie.DTS-X.mkv"))
        assertEquals("DTS 5.1", Labels.audio("dca", "", 6, "", ""))
    }

    @Test fun `no codec falls back to the file name`() {
        assertEquals("DD+", Labels.audio("", "", null, "", "Show.S01E01.1080p.DDP5.1.H.264.mkv"))
    }

    @Test fun `hdr from release name`() {
        assertEquals("Dolby Vision", Labels.hdrFromName("Movie.2160p.DV.HDR10.mkv"))
        assertEquals("", Labels.hdrFromName("Movie.1080p.DVDRip.mkv"))
    }
}
