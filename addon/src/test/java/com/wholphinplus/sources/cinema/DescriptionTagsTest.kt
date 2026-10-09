package com.wholphinplus.sources.cinema

import com.wholphinplus.sources.sync.OrcaSettings
import com.wholphinplus.sources.sync.ProfileMerge
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DescriptionTagsTest {
    @Test fun `money reads like a poster`() {
        assertEquals("$185M", money(185_000_000))
        assertEquals("$2.8B", money(2_799_439_100))
        assertEquals("$1B", money(1_000_000_000))
        assertEquals("$900K", money(900_000))
    }

    @Test fun `the defaults are the title page as it was`() {
        val d = DescriptionTags()
        assertTrue(d.year && d.length && d.ageRating && d.resolution && d.hdr && d.genres && d.services)
        assertFalse(d.wantsFacts)
        assertTrue(DescriptionTags.EVERYTHING.wantsFacts)
    }

    @Test fun `a TV on the older schema doesn't see the new setting as a change`() {
        val s = OrcaSettings(descriptionTags = DescriptionTags.EVERYTHING)
        assertFalse(ProfileMerge.settingsJson(s, 2).contains("descriptionTags"))
        assertTrue(ProfileMerge.settingsJson(s, ProfileMerge.SCHEMA).contains("descriptionTags"))
    }
}
