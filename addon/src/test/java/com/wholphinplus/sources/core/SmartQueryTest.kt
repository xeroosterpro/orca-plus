package com.wholphinplus.sources.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SmartQueryTest {
    @Test fun `a lead and a genre alone searches movies and series`() {
        val q = SmartQuery.parse("best sci-fi")!!
        assertEquals("878", q.genreId)
        assertTrue(q.movies && q.tv)
        assertEquals("Best Sci-fi Movies & Series", q.interpretation)
        assertEquals("27", SmartQuery.parse("new horror")!!.genreId)
        assertEquals("878", SmartQuery.parse("top 10 sci fi")!!.genreId)
    }

    @Test fun `the kind still narrows it`() {
        val q = SmartQuery.parse("best sci-fi movies")!!
        assertTrue(q.movies)
        assertTrue(!q.tv)
    }

    @Test fun `titles stay normal searches`() {
        assertNull(SmartQuery.parse("best"))
        assertNull(SmartQuery.parse("top gun"))
        assertNull(SmartQuery.parse("the best of me"))
        assertNull(SmartQuery.parse("new girl"))
    }
}
