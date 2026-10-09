package com.wholphinplus.sources

import com.wholphinplus.sources.core.ExternalSource
import com.wholphinplus.sources.core.PickerPrefs
import com.wholphinplus.sources.core.ServerKind
import com.wholphinplus.sources.core.tunedRanking
import org.junit.Assert.assertEquals
import org.junit.Test

class PickerTuningTest {
    private val gb = 1_000_000_000L

    private fun copy(
        server: String,
        quality: String,
        rank: Int,
        size: Long,
    ) = ExternalSource(
        connectionId = server,
        serverLabel = server,
        serverKind = ServerKind.JELLYFIN,
        url = "$server/$quality/$size",
        quality = quality,
        qualityRank = rank,
        videoCodec = "HEVC",
        hdr = "",
        audio = "",
        container = "mkv",
        sizeBytes = size,
        fileName = "f",
    )

    // The Goonies, 2026-10-08: the same 54.0 GB remux on three servers, your server listed last
    private val alpha = copy("alpha", "4K", 4, 54 * gb + 12_000_000)
    private val beta = copy("beta", "4K", 4, 54 * gb + 3_000_000)
    private val main = copy("main-row", "4K", 4, 54 * gb)
    private val alphaSmall = copy("alpha", "1080p", 3, 4 * gb)
    private val all = listOf(alphaSmall, main, beta, alpha)
    private val order = listOf("alpha", "beta")

    private fun ranked(p: PickerPrefs) = all.sortedWith(tunedRanking(order, p, "main-row")).map { "${it.connectionId} ${it.quality}" }

    @Test fun `by default the biggest copy of the best quality leads`() {
        assertEquals(listOf("alpha 4K", "beta 4K", "main-row 4K", "alpha 1080p"), ranked(PickerPrefs()))
    }

    @Test fun `your server first on a tie of quality and size`() {
        assertEquals(listOf("main-row 4K", "alpha 4K", "beta 4K", "alpha 1080p"), ranked(PickerPrefs(preferMain = true)))
    }

    @Test fun `a real size difference still wins over your server`() {
        val bigger = copy("beta", "4K", 4, 60 * gb)
        val r = listOf(main, bigger).sortedWith(tunedRanking(order, PickerPrefs(preferMain = true), "main-row"))
        assertEquals("beta", r.first().connectionId)
    }

    @Test fun `a server put first leads with its best copy`() {
        assertEquals(listOf("beta 4K", "alpha 4K", "main-row 4K", "alpha 1080p"), ranked(PickerPrefs(first = "beta")))
        assertEquals("main-row 4K", ranked(PickerPrefs(first = PickerPrefs.MAIN)).first())
        // A server without this title changes nothing
        assertEquals(ranked(PickerPrefs()), ranked(PickerPrefs(first = "plex")))
    }
}
