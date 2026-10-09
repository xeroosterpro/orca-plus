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

    /** The remote has rested at least [quietMs]. */
    fun quietFor(quietMs: Long = QUIET_MS): Boolean = SystemClock.uptimeMillis() - lastInput.get() >= quietMs

    /** Returns once the remote has been still for [quietMs]. */
    suspend fun whenQuiet(quietMs: Long = QUIET_MS) {
        while (true) {
            val still = SystemClock.uptimeMillis() - lastInput.get()
            if (still >= quietMs) return
            delay(quietMs - still)
        }
    }

    private val playing = java.util.concurrent.atomic.AtomicInteger(0)

    /** A stream started playing (Orca+'s reporters, for every play); [playbackEnded] when it stops. */
    fun playbackStarted() {
        playing.incrementAndGet()
    }

    fun playbackEnded() {
        playing.updateAndGet { (it - 1).coerceAtLeast(0) }
    }

    /** Whether something is playing now. */
    val isPlaying: Boolean get() = playing.get() > 0

    /**
     * Returns once the remote is still, nothing plays and Orca+ is on screen. For heavy background
     * work (reading the whole library, refreshing every list): run during playback it competed
     * with the video for the network and the CPU, and after leaving the app with whatever plays
     * there.
     */
    suspend fun whenIdle() {
        while (true) {
            whenQuiet()
            if (!isPlaying && onScreen()) return
            delay(IDLE_POLL_MS)
        }
    }

    /** Orca+ is visible (not in the background, not behind another app, the TV not asleep). */
    private fun onScreen(): Boolean =
        runCatching {
            val info = android.app.ActivityManager.RunningAppProcessInfo()
            android.app.ActivityManager.getMyMemoryState(info)
            info.importance <= android.app.ActivityManager.RunningAppProcessInfo.IMPORTANCE_VISIBLE
        }.getOrDefault(true)

    /** Runs [block] once the remote is still, at most two such jobs at a time. */
    suspend fun <T> later(block: suspend () -> T): T {
        whenQuiet()
        return background.withPermit {
            whenQuiet()
            block()
        }
    }

    private const val QUIET_MS = 600L
    private const val IDLE_POLL_MS = 2_000L
}

/**
 * Gives memory back when Android asks. In the foreground (playback, usually) when memory runs
 * low: half the decoded images; when it's critical, all of them and the other tabs' pages. When
 * Orca+ goes to the background: what can be rebuilt (other tabs' pages, cached title pages) and
 * all but a quarter of the decoded images (the disk cache refills them quickly), so Android is
 * less likely to close the app; at the edge of being closed, everything.
 */
internal class MemoryTrim(
    private val context: Context,
) : ComponentCallbacks2 {
    override fun onTrimMemory(level: Int) {
        // RUNNING_MODERATE: nothing yet (the system's own first warning)
        if (level < ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW) return
        // Pages and title pages only when it's serious (Back from the player keeps its instant page)
        if (level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL) CinemaCaches.trim()
        runCatching {
            val images = SingletonImageLoader.get(context).memoryCache ?: return@runCatching
            when {
                level >= ComponentCallbacks2.TRIM_MEMORY_COMPLETE || level == ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL -> images.clear()
                level >= ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN -> images.trimToSize(images.maxSize / 4)
                // RUNNING_LOW, still in the foreground
                else -> images.trimToSize(images.maxSize / 2)
            }
        }
        Timber.i("Cinema memory trim (level %d)", level)
    }

    override fun onConfigurationChanged(newConfig: Configuration) = Unit

    @Deprecated("Deprecated in Java")
    override fun onLowMemory() = onTrimMemory(ComponentCallbacks2.TRIM_MEMORY_COMPLETE)
}
