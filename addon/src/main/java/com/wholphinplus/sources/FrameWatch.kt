package com.wholphinplus.sources

import android.os.SystemClock
import android.view.Choreographer

/**
 * Times this box's frames while someone browses, and steps [DeviceClass] down a class when they
 * keep running late. The hardware facts guess; this checks (owner, 2026-10-11: on a Fire Stick
 * "the device detect didn't help or do anything").
 *
 * Counts only while the remote is in use ([keyed], from every D-pad press, for [ACTIVE_MS] after
 * it): a frame callback each vsync, and the gap between them. Nothing runs while nobody browses,
 * and the first [SKIP_MS] of a run are left out (code still compiling, first loads). After
 * [WINDOW] browsing frames: more than [SLOW_SHARE] of them 50 ms or longer (a hitch anyone sees),
 * or [LATE_SHARE] two vsyncs or longer, and Auto drops one class (Full → Balanced → Light), saved,
 * at most once a run. Never up again by itself: picking Auto in Settings measures afresh.
 *
 * Off on NVIDIA boxes (the Shield is the reference and stays Full) and on the emulator bench (its
 * frame times are the emulator's graphics, not the app's). `adb shell setprop debug.orca.framewatch 1`
 * turns it on there; `test` also uses hair-trigger limits to try the step down.
 */
internal object FrameWatch {
    private const val SKIP_MS = 30_000L
    private const val ACTIVE_MS = 1_200L
    private const val WINDOW = 600
    private const val SLOW_SHARE = 0.06f
    private const val LATE_SHARE = 0.25f

    private var slowNs = 50_000_000L
    private var lateNs = 33_400_000L
    private var window = WINDOW

    private var onStep: ((DeviceClass.Tier, String) -> Unit)? = null
    private var enabled = false
    private var startedAt = 0L
    private var decided = false
    private var running = false
    private var lastKey = 0L
    private var lastFrame = 0L
    private var frames = 0
    private var slow = 0
    private var late = 0

    fun start(
        facts: DeviceClass.Facts,
        step: (DeviceClass.Tier, String) -> Unit,
    ) {
        onStep = step
        startedAt = SystemClock.uptimeMillis()
        val prop = runCatching { Class.forName("android.os.SystemProperties").getMethod("get", String::class.java).invoke(null, "debug.orca.framewatch") as String }.getOrDefault("")
        enabled = prop == "1" || prop == "test" || !(facts.emulator || facts.maker.equals("NVIDIA", ignoreCase = true))
        if (prop == "test") {
            slowNs = 6_000_000L
            lateNs = 4_000_000L
            window = 120
            startedAt -= SKIP_MS
        }
    }

    /** Settings picked Auto again: watch afresh. */
    fun restart() {
        decided = false
        reset()
    }

    /** Whether this run's frames may still step the class down (else they're only counted for [OrcaHealth]). */
    private val deciding: Boolean
        get() =
            enabled && !decided && SystemClock.uptimeMillis() - startedAt >= SKIP_MS &&
                DeviceClass.mode == DeviceClass.AUTO && DeviceClass.auto != DeviceClass.Tier.LIGHT

    /** A D-pad press was taken (main thread): count the frames of the next moments. */
    fun keyed() {
        val now = SystemClock.uptimeMillis()
        lastKey = now
        if (!running) {
            running = true
            lastFrame = 0L
            Choreographer.getInstance().postFrameCallback(tick)
        }
    }

    private val tick: Choreographer.FrameCallback =
        Choreographer.FrameCallback { at ->
            if (lastFrame != 0L) {
                val gap = at - lastFrame
                OrcaHealth.frame(gap)
                if (deciding) {
                    frames++
                    if (gap >= slowNs) slow++
                    if (gap >= lateNs) late++
                }
            }
            lastFrame = at
            if (frames >= window) verdict()
            if (SystemClock.uptimeMillis() - lastKey < ACTIVE_MS) {
                Choreographer.getInstance().postFrameCallback(tick)
            } else {
                running = false
                lastFrame = 0L
            }
        }

    private fun verdict() {
        val slowShare = slow.toFloat() / frames
        val lateShare = late.toFloat() / frames
        if (slowShare > SLOW_SHARE || lateShare > LATE_SHARE) {
            val now = DeviceClass.auto
            val next = DeviceClass.Tier.entries.getOrNull(now.ordinal + 1)
            if (next != null) {
                decided = true
                val why = "%d%% of frames late while browsing, %d%% over 50 ms".format(java.util.Locale.US, (lateShare * 100).toInt(), (slowShare * 100).toInt())
                onStep?.invoke(next, why)
            }
        }
        reset()
    }

    private fun reset() {
        frames = 0
        slow = 0
        late = 0
    }
}
