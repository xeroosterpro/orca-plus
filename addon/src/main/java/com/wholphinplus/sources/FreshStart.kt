package com.wholphinplus.sources

import android.app.Activity
import android.app.ActivityManager
import android.app.Application
import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.database.Cursor
import android.net.Uri
import android.os.Bundle
import android.os.Process
import android.os.SystemClock
import timber.log.Timber

/**
 * Opens the app in a process of its own when Android had started it for something else.
 *
 * Measured on the Shield (2026-10-06): a process Android starts in the background (Wholphin's
 * hourly jobs, a broadcast, the one sent right after every update) never runs the app's
 * precompiled code, only JIT code, for as long as it lives. Opening the app into it gave
 * 15–20% janky frames while browsing, against under 1% in a process started for the app's
 * screen. On a TV that process can live for days. So when the main screen opens in such a
 * process, it's reopened once in a fresh one (about a second), the way ProcessPhoenix does it.
 */
object FreshStart {
    @Volatile private var startedInBackground = false

    @Volatile private var checked = false

    private const val PREFS = "wholphinplus_fresh_start"
    private const val LAST = "last_restart"

    /** Never two restarts within this long (a loop would otherwise be possible). */
    private const val MIN_GAP_MS = 60_000L

    /** The screens a fresh process is worth it for (not the crash dialog or the extra player). */
    private const val MAIN = "com.github.damontecres.wholphin.MainActivity"

    internal fun onProcessStart(app: Application) {
        val info = ActivityManager.RunningAppProcessInfo()
        ActivityManager.getMyMemoryState(info)
        // Started for a screen: the process is already in the foreground (TOP) while it starts
        startedInBackground = info.importance > ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND
        Timber.i("FreshStart: process started %s (importance %d)", if (startedInBackground) "in the background" else "for a screen", info.importance)
        if (!startedInBackground) return
        app.registerActivityLifecycleCallbacks(
            object : Application.ActivityLifecycleCallbacks {
                override fun onActivityPreCreated(
                    activity: Activity,
                    savedInstanceState: Bundle?,
                ) {
                    if (checked) return
                    checked = true
                    app.unregisterActivityLifecycleCallbacks(this)
                    if (activity.javaClass.name == MAIN && savedInstanceState == null) restart(activity)
                }

                override fun onActivityCreated(
                    activity: Activity,
                    savedInstanceState: Bundle?,
                ) {}

                override fun onActivityStarted(activity: Activity) {}

                override fun onActivityResumed(activity: Activity) {}

                override fun onActivityPaused(activity: Activity) {}

                override fun onActivityStopped(activity: Activity) {}

                override fun onActivitySaveInstanceState(
                    activity: Activity,
                    outState: Bundle,
                ) {}

                override fun onActivityDestroyed(activity: Activity) {}
            },
        )
    }

    private fun restart(activity: Activity) {
        val prefs = activity.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()
        if (now - prefs.getLong(LAST, 0) < MIN_GAP_MS) {
            Timber.w("FreshStart: restarted moments ago; staying in this process")
            return
        }
        prefs.edit().putLong(LAST, now).commit()
        Timber.i("FreshStart: the screen opened in a background-started process (up %d ms); reopening in a fresh one", SystemClock.uptimeMillis() - Process.getStartUptimeMillis())
        val again = Intent(activity.intent).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        activity.startActivity(
            Intent(activity, FreshStartActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION)
                .putExtra(FreshStartActivity.EXTRA_INTENT, again)
                .putExtra(FreshStartActivity.EXTRA_PID, Process.myPid()),
        )
        activity.finish()
        Runtime.getRuntime().exit(0)
    }
}

/** Runs [FreshStart.onProcessStart] and [QuietSemantics.install] before the app's own start-up (providers come first). */
class FreshStartProvider : ContentProvider() {
    override fun onCreate(): Boolean {
        runCatching { FreshStart.onProcessStart(context!!.applicationContext as Application) }
        runCatching { QuietSemantics.install(context!!.applicationContext as Application) }
        return true
    }

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor? = null

    override fun getType(uri: Uri): String? = null

    override fun insert(
        uri: Uri,
        values: ContentValues?,
    ): Uri? = null

    override fun delete(
        uri: Uri,
        selection: String?,
        selectionArgs: Array<out String>?,
    ): Int = 0

    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?,
    ): Int = 0
}

/** In a process of its own: waits for the old process to go, then opens the app again. */
class FreshStartActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pid = intent.getIntExtra(EXTRA_PID, -1)
        if (pid > 0) Process.killProcess(pid)
        @Suppress("DEPRECATION")
        val again = intent.getParcelableExtra<Intent>(EXTRA_INTENT)
        if (again != null) startActivity(again)
        finish()
        overridePendingTransition(0, 0)
        // Leaves a few seconds later, not at once: exiting while the app's start was still pending
        // made Android drop it (Android 11 emulator, 2026-10-06: back on the TV's home screen)
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({ Runtime.getRuntime().exit(0) }, EXIT_AFTER_MS)
    }

    companion object {
        const val EXTRA_INTENT = "intent"
        const val EXTRA_PID = "pid"

        private const val EXIT_AFTER_MS = 3_000L
    }
}
