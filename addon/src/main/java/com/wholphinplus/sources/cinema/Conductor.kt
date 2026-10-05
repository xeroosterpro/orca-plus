package com.wholphinplus.sources.cinema

import android.content.ComponentCallbacks2
import android.content.Context
import android.content.res.Configuration
import android.os.SystemClock
import coil3.SingletonImageLoader
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import timber.log.Timber
import java.util.concurrent.atomic.AtomicLong

/**
 * Times Cinema mode's background work around the person holding the remote.
 *
 * What's on screen loads at once. Everything else (badges and title art for rows further down,
 * preloading the other tabs, writing caches to disk) waits until the remote has been still for a
 * moment, and runs two at a time, so it never competes with a key press for the CPU, the network
 * or the main thread.
 */
internal object Conductor {
    private val lastInput = AtomicLong(0L)
    private val background = Semaphore(2)

    /** A remote key was pressed (called by the Cinema screens' key handlers). */
    fun touch() = lastInput.set(SystemClock.uptimeMillis())

    /** Returns once the remote has been still for [quietMs]. */
    suspend fun whenQuiet(quietMs: Long = QUIET_MS) {
        while (true) {
            val still = SystemClock.uptimeMillis() - lastInput.get()
            if (still >= quietMs) return
            delay(quietMs - still)
        }
    }

    /** Runs [block] once the remote is still, at most two such jobs at a time. */
    suspend fun <T> later(block: suspend () -> T): T {
        whenQuiet()
        return background.withPermit {
            whenQuiet()
            block()
        }
    }

    private const val QUIET_MS = 600L
}

/**
 * Gives memory back when Android asks: when Orca+ goes to the background, drop what can be
 * rebuilt (other tabs' pages, cached title pages, half the decoded images); when memory is
 * critical, all decoded images too. Keeps more room for playback and other apps.
 */
internal class MemoryTrim(
    private val context: Context,
) : ComponentCallbacks2 {
    override fun onTrimMemory(level: Int) {
        if (level < ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN) return
        CinemaCaches.trim()
        runCatching {
            val images = SingletonImageLoader.get(context).memoryCache ?: return@runCatching
            if (level >= ComponentCallbacks2.TRIM_MEMORY_COMPLETE || level == ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL) {
                images.clear()
            } else {
                images.trimToSize(images.maxSize / 2)
            }
        }
        Timber.i("Cinema memory trim (level %d)", level)
    }

    override fun onConfigurationChanged(newConfig: Configuration) = Unit

    @Deprecated("Deprecated in Java")
    override fun onLowMemory() = onTrimMemory(ComponentCallbacks2.TRIM_MEMORY_COMPLETE)
}
