package com.wholphinplus.sources.sync

import com.wholphinplus.sources.ProgressOverlay
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ProfileSyncTest {
    private val id = ProfileCrypto.profileId("0F1E2D3C-4B5A-4978-8695-A4B3C2D1E0F9", "user-1")

    @Test fun `the profile id is the same however the ids are written`() {
        assertEquals(id, ProfileCrypto.profileId("0f1e2d3c4b5a49788695a4b3c2d1e0f9", "USER1"))
        assertEquals(64, id.length)
    }

    @Test fun `a sealed profile opens only with the same PIN and wrap`() {
        val seed = ProfileCrypto.seed("123456", id)
        val data = "{\"hello\":\"tv\"}".repeat(50).toByteArray()
        val sealed = ProfileCrypto.seal(data, seed, "wrap-a")
        assertArrayEquals(data, ProfileCrypto.open(sealed, seed, "wrap-a"))
        assertTrue(runCatching { ProfileCrypto.open(sealed, ProfileCrypto.seed("123457", id), "wrap-a") }.isFailure)
        assertTrue(runCatching { ProfileCrypto.open(sealed, seed, "wrap-b") }.isFailure)
        assertNotEquals(ProfileCrypto.auth(seed), ProfileCrypto.auth(ProfileCrypto.seed("123457", id)))
    }

    private fun entry(at: Long) = ProgressOverlay.Entry(positionTicks = at, played = false, lastPlayed = at)

    @Test fun `progress merges title by title, newer wins`() {
        val merged = ProfileMerge.mergeProgress(mapOf("a" to entry(5), "b" to entry(1)), mapOf("b" to entry(9), "c" to entry(2)))
        assertEquals(listOf(5L, 9L, 2L), listOf("a", "b", "c").map { merged[it]!!.lastPlayed })
    }

    @Test fun `a joining TV takes the cloud's settings, a TV that changed something keeps its own`() {
        val home = Profile(settings = OrcaSettings(cinemaMode = true, tmdbKey = "k"))
        val fresh = Profile(settings = OrcaSettings())
        assertEquals(home.settings, ProfileMerge.merge(fresh, home, emptyMap(), joining = true, now = 1).settings)
        // Synced earlier with the cloud's copy, then changed here: this TV's change wins
        val synced = ProfileMerge.hashes(home)
        val changed = home.copy(settings = home.settings!!.copy(rollUp = false))
        assertEquals(false, ProfileMerge.merge(changed, home, synced, joining = false, now = 1).settings!!.rollUp)
        // Unchanged here, changed in the cloud: the cloud wins
        val cloudChanged = home.copy(settings = home.settings!!.copy(mdblistKey = "m"))
        assertEquals("m", ProfileMerge.merge(home, cloudChanged, synced, joining = false, now = 1).settings!!.mdblistKey)
    }
}
