package com.wholphinplus.sources

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.UUID

class LibraryIndexTest {
    private val a = UUID.fromString("0123abcd-0000-0000-0000-00000000000a")
    private val b = UUID.fromString("0123abcd-0000-0000-0000-00000000000b")

    // Sorted keys, each pointing at its item: TMDB 550 → a, 603 → b; IMDb tt0137523 → a
    private val part =
        LibraryIndex.Part(
            tmdb = intArrayOf(550, 603),
            tmdbAt = intArrayOf(0, 1),
            imdb = intArrayOf(137523),
            imdbAt = intArrayOf(0),
            msb = longArrayOf(a.mostSignificantBits, b.mostSignificantBits),
            lsb = longArrayOf(a.leastSignificantBits, b.leastSignificantBits),
        )

    @Test fun `finds by tmdb, else imdb, as the server writes ids`() {
        assertEquals("0123abcd00000000000000000000000b", part.find(603, null))
        assertEquals("0123abcd00000000000000000000000a", part.find(999, "tt0137523"))
        assertNull(part.find(999, "tt7"))
        assertNull(part.find(null, null))
    }

    @Test fun `imdb ids are read as their number`() {
        assertEquals(111161, LibraryIndex.imdbNumber("tt0111161"))
        assertNull(LibraryIndex.imdbNumber("nm0000123"))
        assertNull(LibraryIndex.imdbNumber(null))
    }

    @Test fun `a daily update adds new titles and keeps the ones already there`() {
        val c = UUID.fromString("0123abcd-0000-0000-0000-00000000000c")
        val more = part.plus(listOf(LibraryIndex.Title(c, 680, 110912), LibraryIndex.Title(b, 603, null)))
        assertEquals("0123abcd00000000000000000000000c", more.find(680, null))
        assertEquals("0123abcd00000000000000000000000c", more.find(null, "tt0110912"))
        // Still found as before; a title read again keeps its first item
        assertEquals("0123abcd00000000000000000000000a", more.find(550, null))
        assertEquals("0123abcd00000000000000000000000a", more.find(null, "tt0137523"))
        assertEquals("0123abcd00000000000000000000000b", more.find(603, null))
    }
}
