package com.wholphinplus.sources

import com.wholphinplus.sources.DeviceClass.Decoder
import com.wholphinplus.sources.DeviceClass.Facts
import com.wholphinplus.sources.DeviceClass.Tier
import com.wholphinplus.sources.core.CopyFit
import com.wholphinplus.sources.core.ExternalSource
import com.wholphinplus.sources.core.PickerPrefs
import com.wholphinplus.sources.core.ServerKind
import com.wholphinplus.sources.core.copyFit
import com.wholphinplus.sources.core.tunedRanking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DeviceClassTest {
    // Published specs (RAM as Android reports it: a little under the label)
    private val shield = Facts(maker = "NVIDIA", model = "SHIELD Android TV", ramMb = 2_900, cores = 4, maxGhz = 2.0f, bigCore = true)
    private val fireStick4k = Facts(maker = "Amazon", model = "AFTMM", ramMb = 1_400, cores = 4, maxGhz = 1.7f, bigCore = false, fireTv = true)
    private val fireStickLite = Facts(maker = "Amazon", model = "AFTSS", ramMb = 1_000, lowRam = true, cores = 4, maxGhz = 1.7f, bigCore = false, fireTv = true)
    private val stick4kMax2 = Facts(maker = "Amazon", model = "AFTKRT", ramMb = 1_900, cores = 4, maxGhz = 2.0f, bigCore = false, fireTv = true)
    private val chromecast = Facts(maker = "Google", model = "Chromecast", ramMb = 1_900, cores = 4, maxGhz = 1.9f, bigCore = false)
    private val cube2 = Facts(maker = "Amazon", model = "AFTR", ramMb = 1_900, cores = 6, maxGhz = 2.2f, bigCore = true, fireTv = true)
    private val bigBox = Facts(maker = "Xiaomi", model = "Box", ramMb = 3_800, cores = 4, maxGhz = 2.2f, bigCore = true)

    @Test fun `the Shield and the emulator bench run everything`() {
        assertEquals(Tier.FULL, DeviceClass.tierOf(shield))
        assertEquals(Tier.FULL, DeviceClass.tierOf(Facts(emulator = true, ramMb = 2_900)))
        assertEquals(Tier.FULL, DeviceClass.tierOf(bigBox))
    }

    @Test fun `Fire TV Sticks and low-RAM boxes are light`() {
        assertEquals(Tier.LIGHT, DeviceClass.tierOf(fireStick4k))
        assertEquals(Tier.LIGHT, DeviceClass.tierOf(fireStickLite))
        assertEquals(Tier.LIGHT, DeviceClass.tierOf(Facts(ramMb = 4_000, lowRam = true)))
    }

    @Test fun `mid-range sticks are balanced`() {
        assertEquals(Tier.BALANCED, DeviceClass.tierOf(stick4kMax2))
        assertEquals(Tier.BALANCED, DeviceClass.tierOf(chromecast))
        // A big core but only 2 GB: still balanced
        assertEquals(Tier.BALANCED, DeviceClass.tierOf(cube2))
        // Nothing readable: no guess either way
        assertEquals(Tier.BALANCED, DeviceClass.tierOf(Facts()))
    }

    private fun copy(
        server: String,
        quality: String,
        rank: Int,
        gb: Long,
        codec: String = "HEVC",
        hdr: String = "",
    ) = ExternalSource(
        connectionId = server,
        serverLabel = server,
        serverKind = ServerKind.JELLYFIN,
        url = "$server/$quality",
        quality = quality,
        qualityRank = rank,
        videoCodec = codec,
        hdr = hdr,
        audio = "",
        container = "mkv",
        sizeBytes = gb * 1_000_000_000L,
        fileName = "f",
    )

    private val remux4kDv = copy("a", "4K", 2160, 60, hdr = "Dolby Vision")
    private val web4kAv1 = copy("b", "4K", 2160, 12, codec = "AV1", hdr = "HDR10")
    private val bluray1080 = copy("c", "1080p", 1080, 14, codec = "H.264")
    private val web1080 = copy("d", "1080p", 1080, 5, codec = "H.264")

    private fun order(f: Facts): List<String> {
        copyFit = CopyFit { DeviceClass.fitOf(f, it) }
        return listOf(web1080, bluray1080, web4kAv1, remux4kDv).sortedWith(tunedRanking(listOf("a", "b", "c", "d"), PickerPrefs(), "main")).map { it.connectionId }
    }

    @After fun reset() {
        copyFit = CopyFit.ANY
    }

    @Test fun `with no device opinion the order is as before`() {
        copyFit = CopyFit.ANY
        val r = listOf(web1080, bluray1080, web4kAv1, remux4kDv).sortedWith(tunedRanking(listOf("a", "b", "c", "d"), PickerPrefs(), "main"))
        assertEquals(listOf("a", "b", "c", "d"), r.map { it.connectionId })
    }

    @Test fun `a Shield on a 4K Dolby Vision TV keeps the remux first, the AV1 copy it can't decode last`() {
        val tv =
            shield.copy(
                screenHeight = 2160,
                screenHdr = setOf("Dolby Vision", "HDR10", "HLG"),
                decoders = mapOf("HEVC" to Decoder(3840, 2160, true, true), "H.264" to Decoder(3840, 2160, false, false)),
            )
        assertEquals(listOf("a", "c", "d", "b"), order(tv))
    }

    @Test fun `a 1080p Fire TV Stick leads with a 1080p copy, not the 4K remux`() {
        val stick =
            fireStick4k.copy(
                screenHeight = 1080,
                decoders = mapOf("HEVC" to Decoder(1920, 1088, true, false), "H.264" to Decoder(1920, 1088, false, false)),
            )
        // The 4K copies: too big for its decoders. Of the 1080p ones, the bigger file
        assertEquals(listOf("c", "d", "a", "b"), order(stick))
        assertFalse(DeviceClass.fitOf(stick, remux4kDv).plays)
        assertTrue(DeviceClass.fitOf(stick, bluray1080).plays)
    }

    @Test fun `a 4K decoder on a 1080p screen prefers the copy that fits the screen`() {
        val box = chromecast.copy(screenHeight = 1080, decoders = mapOf("HEVC" to Decoder(3840, 2160, true, false), "H.264" to Decoder(1920, 1088, false, false)))
        assertEquals("c", order(box).first())
    }

    @Test fun `Dolby Vision without a DV decoder counts as HDR10`() {
        val box = chromecast.copy(screenHeight = 2160, screenHdr = setOf("HDR10"), decoders = mapOf("HEVC" to Decoder(3840, 2160, true, false)))
        assertEquals(1, DeviceClass.fitOf(box, remux4kDv).hdr)
        assertEquals(0, DeviceClass.fitOf(box.copy(screenHdr = emptySet()), remux4kDv).hdr)
    }

    @Test fun `unknown codec or a server conversion is never ruled out`() {
        val stick = fireStick4k.copy(decoders = mapOf("H.264" to Decoder(1920, 1088, false, false)))
        assertTrue(DeviceClass.fitOf(stick, copy("x", "4K", 2160, 10, codec = "")).plays)
        assertTrue(DeviceClass.fitOf(stick, copy("x", "4K", 2160, 10).copy(compatible = true)).plays)
    }
}
