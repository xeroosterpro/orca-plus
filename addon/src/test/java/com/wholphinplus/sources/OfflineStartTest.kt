package com.wholphinplus.sources

import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OfflineStartTest {
    @Test fun `network failures open the saved session, server answers don't`() {
        assertTrue(OfflineStart.isNetwork(RuntimeException("wrapped", java.io.InterruptedIOException("timeout"))))
        assertTrue(OfflineStart.isNetwork(java.net.UnknownHostException("silo")))
        assertFalse(OfflineStart.isNetwork(IllegalStateException("no")))
        assertEquals("saved", runBlocking { OfflineStart.restore<String>({ throw java.net.ConnectException("down") }, { "saved" }, { null }) })
        assertEquals("online", runBlocking { OfflineStart.restore<String>({ "online" }, { "saved" }) })
        assertTrue(runCatching { runBlocking { OfflineStart.restore<String>({ throw IllegalStateException("expired") }, { "saved" }) } }.isFailure)
    }

    @Test fun `a slow server opens the saved session after a few seconds`() {
        val t0 = System.currentTimeMillis()
        assertEquals("saved", runBlocking { OfflineStart.restore<String>({ delay(60_000); "online" }, { "saved" }, { null }) })
        assertTrue(System.currentTimeMillis() - t0 < 15_000)
    }

    private fun refused() = org.jellyfin.sdk.api.client.exception.InvalidStatusException(401)

    @Test fun `a late refusal sends the viewer to sign in, a late server error is checked again`() {
        // Slow start, then 401: the saved session opened, then onRefused
        val refusedLate = kotlinx.coroutines.CompletableDeferred<Throwable>()
        val r =
            runBlocking {
                OfflineStart.restore<String>({ delay(300); throw refused() }, { "saved" }, { null }, onRefused = { refusedLate.complete(it) }, waitMs = 50)
            }
        assertEquals("saved", r)
        assertTrue(OfflineStart.isRefused(runBlocking { kotlinx.coroutines.withTimeout(5_000) { refusedLate.await() } }))

        // No network at start, then the server comes back with a 500, then with a 401
        OfflineStart.retryStartMs = 20
        var calls = 0
        val refusedAfter = kotlinx.coroutines.CompletableDeferred<Int>()
        runBlocking {
            OfflineStart.restore<String>(
                { throw java.net.ConnectException("down") },
                { "saved" },
                recheck = {
                    calls++
                    if (calls == 1) throw org.jellyfin.sdk.api.client.exception.InvalidStatusException(503) else throw refused()
                },
                onRefused = { refusedAfter.complete(calls) },
            )
        }
        assertEquals(2, runBlocking { kotlinx.coroutines.withTimeout(5_000) { refusedAfter.await() } })
        OfflineStart.retryStartMs = 5_000
    }

    @Test fun `only a 401 or 403 counts as refused`() {
        assertTrue(OfflineStart.isRefused(RuntimeException("x", refused())))
        assertFalse(OfflineStart.isRefused(org.jellyfin.sdk.api.client.exception.InvalidStatusException(500)))
        assertFalse(OfflineStart.isRefused(java.net.ConnectException("down")))
    }
}
