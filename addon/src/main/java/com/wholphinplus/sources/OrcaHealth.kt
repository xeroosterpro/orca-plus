package com.wholphinplus.sources

import android.app.Activity
import android.app.Application
import android.content.ComponentCallbacks2
import android.content.Context
import android.os.Debug
import android.os.Handler
import android.os.HandlerThread
import android.os.Process
import android.os.SystemClock
import timber.log.Timber
import java.io.File
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger

/**
 * A health line in the log while the app is on screen (owner, 2026-10-11: "advanced log… how the
 * app runs and using mem and cpu and errors"). Once a minute:
 *
 *     Orca health: cpu 0.31 cores (main 0.12) · heap 41/256 MB · native 88 MB · images 52/96 MB ·
 *     gc 3 (41 ms) · threads 63 · frames 412, late 9, over 50 ms 1 · warnings 2, errors 0
 *
 * and every five minutes, and whenever Android asks for memory back, the whole picture
 * (`Orca memory: pss … java … native … graphics … code … stack …`). Numbers since the last line.
 * Off the main thread, nothing while the app is in the background; tools/walk.py and the soak
 * test read these lines.
 */
object OrcaHealth {
    private const val EVERY_MS = 60_000L
    private const val FULL_EVERY = 5

    private var thread: HandlerThread? = null
    private var handler: Handler? = null
    private var app: Context? = null
    private var shown = 0
    private var ticks = 0

    private var lastWall = 0L
    private var lastCpu = 0L
    private var lastMain = 0L
    private var lastGc = 0L
    private var lastGcMs = 0L

    // Counted by the log tree below (any thread)
    private val warnings = AtomicInteger()
    private val errors = AtomicInteger()

    @Volatile private var lastError: String? = null

    // Frames seen while the remote is in use ([FrameWatch] counts them)
    private val frames = AtomicInteger()
    private val late = AtomicInteger()
    private val slow = AtomicInteger()

    fun start(context: Context) {
        if (thread != null) return
        val application = context.applicationContext as? Application ?: return
        app = application
        Timber.plant(Counter)
        thread = HandlerThread("orca-health", Process.THREAD_PRIORITY_BACKGROUND).also { it.start() }
        handler = Handler(thread!!.looper)
        // Started with the app's screen already up (its start isn't seen): count from now
        resume()
        application.registerActivityLifecycleCallbacks(
            object : Application.ActivityLifecycleCallbacks {
                override fun onActivityStarted(a: Activity) {
                    if (shown++ == 0) resume()
                }

                override fun onActivityStopped(a: Activity) {
                    shown = (shown - 1).coerceAtLeast(0)
                    if (shown == 0) handler?.removeCallbacksAndMessages(null)
                }

                override fun onActivityCreated(
                    a: Activity,
                    b: android.os.Bundle?,
                ) {}

                override fun onActivityResumed(a: Activity) {}

                override fun onActivityPaused(a: Activity) {}

                override fun onActivitySaveInstanceState(
                    a: Activity,
                    b: android.os.Bundle,
                ) {}

                override fun onActivityDestroyed(a: Activity) {}
            },
        )
        application.registerComponentCallbacks(
            object : ComponentCallbacks2 {
                override fun onTrimMemory(level: Int) {
                    if (level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW && level != ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN) {
                        handler?.post { Timber.i("Orca memory: Android asked for memory back (level %d) · %s", level, memory()) }
                    }
                }

                override fun onConfigurationChanged(c: android.content.res.Configuration) {}

                @Deprecated("Deprecated in Java")
                override fun onLowMemory() {}
            },
        )
    }

    /** A frame seen while the remote was in use, and how long since the one before (ns). */
    fun frame(gapNs: Long) {
        frames.incrementAndGet()
        if (gapNs >= 33_400_000L) late.incrementAndGet()
        if (gapNs >= 50_000_000L) slow.incrementAndGet()
    }

    private fun resume() {
        val h = handler ?: return
        h.removeCallbacksAndMessages(null)
        mark()
        h.postDelayed(::tick, EVERY_MS)
    }

    private fun mark() {
        lastWall = SystemClock.elapsedRealtime()
        lastCpu = Process.getElapsedCpuTime()
        lastMain = mainThreadMs()
        lastGc = gcCount()
        lastGcMs = gcMs()
    }

    private fun tick() {
        runCatching { Timber.i("Orca health: %s", line()) }
        if (++ticks % FULL_EVERY == 0) runCatching { Timber.i("Orca memory: %s", memory()) }
        handler?.postDelayed(::tick, EVERY_MS)
    }

    /** One health line, numbers since the last one. */
    fun line(): String {
        val wall = (SystemClock.elapsedRealtime() - lastWall).coerceAtLeast(1)
        val cpu = Process.getElapsedCpuTime() - lastCpu
        val main = mainThreadMs() - lastMain
        val gc = gcCount() - lastGc
        val gcTime = gcMs() - lastGcMs
        val rt = Runtime.getRuntime()
        val mb = 1024 * 1024
        val images =
            runCatching {
                if (!com.wholphinplus.sources.cinema.MemoryTrim.loaderSet) return@runCatching null
                coil3.SingletonImageLoader.get(app!!).memoryCache?.let { "${it.size / mb}/${it.maxSize / mb} MB" }
            }.getOrNull()
        val w = warnings.getAndSet(0)
        val e = errors.getAndSet(0)
        val f = frames.getAndSet(0)
        val l = late.getAndSet(0)
        val s = slow.getAndSet(0)
        mark()
        return listOfNotNull(
            "cpu %.2f cores (main %.2f)".format(Locale.US, cpu / wall.toFloat(), main / wall.toFloat()),
            "heap ${(rt.totalMemory() - rt.freeMemory()) / mb}/${rt.maxMemory() / mb} MB",
            "native ${Debug.getNativeHeapAllocatedSize() / mb} MB",
            images?.let { "images $it" },
            "gc $gc ($gcTime ms)",
            "threads ${threads()}",
            if (f > 0) "frames $f, late $l, over 50 ms $s" else null,
            "warnings $w, errors $e" + (if (e > 0) lastError?.let { " (last: $it)" }.orEmpty() else ""),
        ).joinToString("  ·  ")
    }

    /** The whole memory picture (a few ms: every few minutes, not every line). */
    fun memory(): String {
        val m = Debug.MemoryInfo().also { Debug.getMemoryInfo(it) }
        fun stat(key: String) = (m.getMemoryStat(key)?.toIntOrNull() ?: 0) / 1024
        return "pss ${m.totalPss / 1024} MB · java ${stat("summary.java-heap")} · native ${stat("summary.native-heap")} · " +
            "graphics ${stat("summary.graphics")} · code ${stat("summary.code")} · stack ${stat("summary.stack")} · other ${stat("summary.private-other")} MB"
    }

    private fun gcCount(): Long = Debug.getRuntimeStat("art.gc.gc-count")?.toLongOrNull() ?: 0L

    private fun gcMs(): Long = Debug.getRuntimeStat("art.gc.gc-time")?.toLongOrNull() ?: 0L

    private fun threads(): Int =
        runCatching { File("/proc/self/status").useLines { l -> l.first { it.startsWith("Threads:") }.substringAfter(':').trim().toInt() } }.getOrDefault(0)

    /** The main thread's CPU time (ms), from the kernel's per-thread record (10 ms ticks). */
    private fun mainThreadMs(): Long =
        runCatching {
            val stat = File("/proc/self/task/${Process.myPid()}/stat").readText()
            val f = stat.substringAfterLast(')').trim().split(' ')
            // utime and stime are fields 14 and 15; after the name, index 11 and 12
            (f[11].toLong() + f[12].toLong()) * 10L
        }.getOrDefault(0L)

    /** Counts warnings and errors from every log line (it logs nothing itself). */
    private object Counter : Timber.Tree() {
        override fun isLoggable(
            tag: String?,
            priority: Int,
        ) = priority >= android.util.Log.WARN

        override fun log(
            priority: Int,
            tag: String?,
            message: String,
            t: Throwable?,
        ) {
            if (priority >= android.util.Log.ERROR) {
                errors.incrementAndGet()
                lastError = LogScrub.text((t?.let { "${it.javaClass.simpleName}: ${it.message}" } ?: message).take(120))
            } else {
                warnings.incrementAndGet()
            }
        }
    }
}
