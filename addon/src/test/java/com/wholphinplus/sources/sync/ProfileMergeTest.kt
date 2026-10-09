package com.wholphinplus.sources.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProfileMergeTest {
    private val base = Profile(settings = OrcaSettings(tmdbKey = "k"), lists = ListsState(traktClientId = "t"))

    @Test fun `a retry after a conflict keeps the other TV's newer change`() {
        // This TV synced at base, then changed its lists; meanwhile TV2 wrote R1 and TV3 R2
        val synced = ProfileMerge.hashes(base)
        val here = base.copy(lists = ListsState(traktClientId = "mine"))
        val changed = ProfileMerge.changedSections(here, synced, joining = false)
        assertEquals(setOf(ProfileMerge.LISTS), changed)
        val r1 = base.copy(settings = OrcaSettings(tmdbKey = "tv2"))
        val round1 = ProfileMerge.merge(here, r1, changed, now = 1)
        assertEquals("tv2", round1.settings!!.tmdbKey)
        assertEquals("mine", round1.lists!!.traktClientId)
        // Round 1 was applied here, its put lost to TV3's R2: round 2 starts from round 1's copy
        val r2 = base.copy(settings = OrcaSettings(tmdbKey = "tv3"))
        val round2 = ProfileMerge.merge(round1, r2, changed, now = 2)
        assertEquals("TV3's newer settings win: this TV never changed them", "tv3", round2.settings!!.tmdbKey)
        assertEquals("mine", round2.lists!!.traktClientId)
        // The old way (changed worked out again from round 1's copy) put TV2's settings back
        val oldWay = ProfileMerge.merge(round1, r2, synced, joining = false, now = 2)
        assertEquals("tv2", oldWay.settings!!.tmdbKey)
    }

    @Test fun `a joining TV and one that never synced change nothing`() {
        assertTrue(ProfileMerge.changedSections(base, emptyMap(), joining = true).isEmpty())
        val fresh = Profile(settings = OrcaSettings())
        assertEquals(base.settings, ProfileMerge.merge(fresh, base, ProfileMerge.changedSections(fresh, emptyMap(), joining = true), now = 1).settings)
    }

    @Test fun `a dismissed reset stays dismissed when the cloud moves its time later`() {
        val day = 86_400_000L
        val seen = 1_000_000_000_000L
        assertTrue(ProfileSync.sameReset(seen, seen))
        assertTrue(ProfileSync.sameReset(seen, seen + 6 * day))
        assertFalse(ProfileSync.sameReset(0L, seen))
        assertFalse(ProfileSync.sameReset(seen, seen + 8 * day))
        assertFalse(ProfileSync.sameReset(seen, seen - 1))
    }

    @Test fun `cloud answers read as sentences`() {
        assertTrue(ProfileSync.describe(CloudException(409, "kept_recently", retryAfterSec = 2 * 86_400)).contains("about 2 days"))
        assertTrue(ProfileSync.describe(CloudException(507, "full")).contains("full"))
        assertEquals("Too many wrong PINs. Try again in 15 minutes", ProfileSync.describe(CloudException(423, "locked", retryAfterSec = 900)))
    }

    // What a build from before the picker and the now-playing card saved: no schema number,
    // settings hashed without those fields
    private fun oldBuildHashes(p: Profile): Map<String, String> {
        val legacy = ProfileMerge.settingsJson(p.settings!!, schema = 1)
        val sha = ProfileCrypto.hex(ProfileCrypto.sha256(legacy.toByteArray()))
        return ProfileMerge.hashes(p).minus(ProfileMerge.SCHEMA_KEY) + (ProfileMerge.SETTINGS to sha)
    }

    @Test fun `the first sync after an update doesn't count the new fields as an edit here`() {
        val synced = oldBuildHashes(base)
        assertTrue(ProfileMerge.changedSections(base, synced, joining = false).isEmpty())
        // A real edit still counts
        val edited = base.copy(settings = base.settings!!.copy(kidsTab = true))
        assertEquals(setOf(ProfileMerge.SETTINGS), ProfileMerge.changedSections(edited, synced, joining = false))
        // ...so does a change to a new field after the update (hashed with the schema from then on)
        val tuned = base.copy(settings = base.settings!!.copy(picker = com.wholphinplus.sources.core.PickerPrefs(preferMain = true)))
        assertEquals(setOf(ProfileMerge.SETTINGS), ProfileMerge.changedSections(tuned, ProfileMerge.hashes(base), joining = false))
        // Hashes from a build that had the fields but saved no schema number still match
        assertTrue(ProfileMerge.changedSections(base, ProfileMerge.hashes(base).minus(ProfileMerge.SCHEMA_KEY), joining = false).isEmpty())
    }

    @Test fun `the old form is the new one without the later fields`() {
        val s = OrcaSettings(tmdbKey = "k", kidsTab = true)
        val now = ProfileMerge.json.encodeToString(OrcaSettings.serializer(), s)
        val old = ProfileMerge.settingsJson(s, schema = 1)
        assertFalse(old.contains("picker"))
        assertTrue(now.startsWith(old.dropLast(1) + ",\"picker\""))
    }

    @Test fun `fields an older build dropped keep this TV's values`() {
        val local = Profile(settings = OrcaSettings(tmdbKey = "k", picker = com.wholphinplus.sources.core.PickerPrefs(preferMain = true)))
        // An old build wrote the cloud copy: kids tab on, no picker field at all
        val oldWrite = """{"format":1,"savedAt":5,"settings":{"tmdbKey":"k","kidsTab":true}}"""
        val remote = ProfileMerge.json.decodeFromString(Profile.serializer(), oldWrite)
        val kept = ProfileMerge.keepFieldsUnknownToWriter(remote, oldWrite, local)
        assertTrue(kept.settings!!.kidsTab)
        assertTrue("this TV's picker tuning stays", kept.settings!!.picker.preferMain)
        // A cloud copy that has the field (at its default) is taken as it is
        val newWrite = ProfileMerge.json.encodeToString(Profile.serializer(), remote)
        assertFalse(ProfileMerge.keepFieldsUnknownToWriter(remote, newWrite, local).settings!!.picker.preferMain)
        // Remote settings win whole, with the kept fields in
        val merged = ProfileMerge.merge(local, kept, emptySet(), now = 1)
        assertTrue(merged.settings!!.kidsTab && merged.settings!!.picker.preferMain)
    }
}
