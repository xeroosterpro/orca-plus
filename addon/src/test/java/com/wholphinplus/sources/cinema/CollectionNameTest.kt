package com.wholphinplus.sources.cinema

import org.junit.Assert.assertEquals
import org.junit.Test

class CollectionNameTest {
    private fun clean(s: String) = CollectionSearch.cleanName(s)

    @Test fun `makers' notes and tags go`() {
        assertEquals("Britbox Movies 2023", clean("Britbox Movies 2023 (BBC IPLAYER,BBC Earth added)"))
        assertEquals("Britbox", clean("[Service] britbox"))
        assertEquals("Marvel Cinematic Universe", clean("Marvel Cinematic Universe (MCU)"))
    }

    @Test fun `all lower or upper case becomes title case, mixed case stays`() {
        assertEquals("Britbox Movies", clean("britbox-movies"))
        assertEquals("Best of the Bbc", clean("BEST OF THE BBC"))
        assertEquals("BritBox Shows - AllRegions", clean("BritBox Shows - AllRegions"))
    }

    @Test fun `emoji and stray symbols go, a name made only of them stays`() {
        assertEquals("Christmas Movies", clean("🎄 Christmas Movies 🎄"))
        assertEquals("🎄", clean("🎄"))
    }
}
