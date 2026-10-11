package com.wholphinplus.sources

import org.jellyfin.sdk.model.api.MediaStream
import org.jellyfin.sdk.model.api.MediaStreamType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LangTest {
    @Test
    fun twoAndThreeLetterCodesAreOneLanguage() {
        // The owner's case: Wholphin's preference "eng", the main server's tracks "en"
        assertTrue(Lang.same("eng", "en"))
        assertTrue(Lang.same("EN", "eng"))
        assertTrue(Lang.same("en-US", "eng"))
        assertTrue(Lang.same("ger", "de"))
        assertTrue(Lang.same("fre", "fra"))
        assertTrue(Lang.same("pt", "por"))
        assertFalse(Lang.same("en", "es"))
        assertFalse(Lang.same(null, "eng"))
    }

    @Test
    fun unknownStaysAsItIs() {
        assertEquals("und", Lang.code("und"))
        assertEquals("English", Lang.code("English"))
        assertNull(Lang.code(null))
        assertEquals("", Lang.code(""))
    }

    @Test
    fun streamsGetOneSpelling() {
        val en = stream(5, "en")
        val eng = stream(28, "eng")
        val out = Lang.streams(listOf(en, eng))
        assertEquals(listOf("eng", "eng"), out.map { it.language })
        assertEquals(listOf(5, 28), out.map { it.index })
        // Already in the right spelling: the same object
        assertTrue(out[1] === eng)
    }

    private fun stream(
        index: Int,
        language: String,
    ) = MediaStream(
        type = MediaStreamType.SUBTITLE,
        index = index,
        language = language,
        isInterlaced = false,
        isDefault = false,
        isForced = false,
        isHearingImpaired = false,
        isExternal = false,
        isTextSubtitleStream = true,
        supportsExternalStream = true,
    )
}
