package com.wholphinplus.sources

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.io.InterruptedIOException

class RetryingDataSourceTest {
    private class Flaky : IOException("404")

    private val waits = mutableListOf<Long>()

    private fun <T> run(block: () -> T): T =
        RetryingDataSource.withRetries(isTransient = { it is Flaky }, sleep = { waits += it }, block = block)

    @Test fun `a server's random failures are tried again until the file comes`() {
        var tries = 0
        val got = run { if (++tries < 4) throw Flaky() else 42L }
        assertEquals(42L, got)
        assertEquals(4, tries)
        assertEquals(listOf(250L, 500L, 750L), waits)
    }

    @Test fun `it gives up after the last try, with the server's answer`() {
        var tries = 0
        val e = runCatching { run { tries++; throw Flaky() } }.exceptionOrNull()
        assertTrue(e is Flaky)
        assertEquals(RetryingDataSource.MAX_TRIES, tries)
        assertTrue(waits.sum() < 8_000)
    }

    @Test fun `other errors and cancels pass straight through`() {
        var tries = 0
        assertTrue(runCatching { run { tries++; throw IOException("refused") } }.exceptionOrNull() !is Flaky)
        assertTrue(runCatching { run { tries++; throw InterruptedIOException() } }.exceptionOrNull() is InterruptedIOException)
        assertEquals(2, tries)
        assertTrue(waits.isEmpty())
    }

    @Test fun `only answers a flaky server gives count`() {
        listOf(404, 408, 429, 500, 502, 503, 504).forEach { assertTrue("$it", RetryingDataSource.isTransientCode(it)) }
        listOf(200, 206, 400, 401, 403, 410, 416).forEach { assertFalse("$it", RetryingDataSource.isTransientCode(it)) }
    }
}
