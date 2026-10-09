package com.wholphinplus.sources

import android.accessibilityservice.AccessibilityServiceInfo
import android.app.Activity
import android.app.Application
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityManager
import timber.log.Timber

/**
 * Spares Compose its accessibility bookkeeping when nothing on the TV reads the screen.
 *
 * Compose keeps a copy of the screen's semantics tree and diffs it after every change (every
 * frame while a row scrolls) as soon as *any* accessibility service is on, even one that only
 * listens for key presses or window changes (a launcher's, a remote-remapper's). Measured on the
 * Shield (2026-10-06, Projectivy + a volume-key service): 373 ms of main-thread work in 16 s of
 * browsing, besides ~1 s of frames, landing between frames and pushing the next one late.
 *
 * When no enabled service asks for the events that tree exists to send (content changes,
 * accessibility focus, scrolling) and nothing explores by touch, Compose is told the service list
 * is empty. A screen reader, Switch Access or Voice Access asks for those events, so with one on
 * nothing changes. Re-checked whenever the services change. Reflection on two Compose fields
 * (kept by consumer-rules.pro); if they ever move, this does nothing.
 */
internal object QuietSemantics {
    private const val NEEDS =
        AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED or AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUSED or AccessibilityEvent.TYPE_VIEW_SCROLLED

    private var resumed: Activity? = null

    fun install(app: Application) {
        val am = app.getSystemService(AccessibilityManager::class.java) ?: return
        // Compose re-reads the services when they change; ours runs after its listener (posted)
        am.addAccessibilityStateChangeListener { resumed?.let { a -> a.window.decorView.post { apply(a, am) } } }
        am.addTouchExplorationStateChangeListener { resumed?.let { a -> a.window.decorView.post { apply(a, am) } } }
        // A service switched on while another already was (so "enabled" never changed): only
        // this listener hears it (Android 13+); older TVs re-check when Orca+ comes back to the front
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            am.addAccessibilityServicesStateChangeListener(app.mainExecutor) { resumed?.let { a -> a.window.decorView.post { apply(a, am) } } }
        }
        app.registerActivityLifecycleCallbacks(
            object : Application.ActivityLifecycleCallbacks {
                override fun onActivityResumed(activity: Activity) {
                    resumed = activity
                    activity.window.decorView.post { apply(activity, am) }
                }

                override fun onActivityPaused(activity: Activity) {
                    if (resumed === activity) resumed = null
                }

                override fun onActivityCreated(
                    activity: Activity,
                    savedInstanceState: Bundle?,
                ) {}

                override fun onActivityStarted(activity: Activity) {}

                override fun onActivityStopped(activity: Activity) {}

                override fun onActivitySaveInstanceState(
                    activity: Activity,
                    outState: Bundle,
                ) {}

                override fun onActivityDestroyed(activity: Activity) {}
            },
        )
    }

    /** Whether something on the TV uses what Compose's semantics tree is kept for. */
    private fun needed(am: AccessibilityManager): Boolean {
        if (!am.isEnabled) return false
        if (am.isTouchExplorationEnabled) return true
        return am.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK).any { s ->
            s.eventTypes and NEEDS != 0 ||
                s.feedbackType and (AccessibilityServiceInfo.FEEDBACK_SPOKEN or AccessibilityServiceInfo.FEEDBACK_BRAILLE) != 0
        }
    }

    private fun apply(
        activity: Activity,
        am: AccessibilityManager,
    ) {
        if (!am.isEnabled) return
        if (needed(am)) {
            // A service now reads the screen: hand Compose its real list back (it asks the system
            // again when the field is empty). Compose refreshes it only when "enabled" or touch
            // exploration flips, so a reader turned on beside another service stayed shut out.
            val restored = composeViews(activity.window.decorView).count { set(it, null) }
            if (restored > 0 && quietedViews > 0) Timber.i("QuietSemantics: a service reads the screen; semantics back on in %d view(s)", restored)
            quietedViews = 0
            return
        }
        val quieted = composeViews(activity.window.decorView).count { set(it, emptyList()) }
        quietedViews = quieted
        if (quieted > 0) Timber.i("QuietSemantics: no service reads the screen; semantics off in %d view(s)", quieted)
    }

    /** Views quieted by the last [apply], for the log. */
    private var quietedViews = 0

    private fun composeViews(v: View): List<View> =
        when {
            v is androidx.compose.ui.platform.ViewRootForTest -> listOf(v)
            v is ViewGroup -> (0 until v.childCount).flatMap { composeViews(v.getChildAt(it)) }
            else -> emptyList()
        }

    /** Sets Compose's copy of the enabled services: empty quiets it, null makes it ask the system again. */
    private fun set(
        view: View,
        services: List<AccessibilityServiceInfo>?,
    ): Boolean =
        runCatching {
            val delegate = view.javaClass.getDeclaredField("composeAccessibilityDelegate").apply { isAccessible = true }.get(view) ?: return false
            delegate.javaClass.getDeclaredField("_enabledServices").apply { isAccessible = true }.set(delegate, services)
            true
        }.onFailure { Timber.w(it, "QuietSemantics: Compose changed; leaving accessibility as it is") }.getOrDefault(false)
}
