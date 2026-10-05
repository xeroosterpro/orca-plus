package com.wholphinplus.sources.welcome

/** First-run welcome stages (stored in ConnectionStore.onboarding). */
object Onboarding {
    /** Never shown. Existing installs stay here: the welcome only starts with no server saved. */
    const val NEW = "new"

    /** The welcome is on screen, before sign-in. */
    const val STARTED = "started"

    /** Signed in; choosing extra libraries and the look, over the app. */
    const val FINISHING = "finishing"

    const val DONE = "done"

    /**
     * Whether to show "updated to …": not on a first install (Android records the install and
     * last-update times; they match until the app is really updated), where it would pop up
     * over the welcome.
     */
    fun announceUpdate(context: android.content.Context): Boolean =
        runCatching {
            val info = context.packageManager.getPackageInfo(context.packageName, 0)
            info.firstInstallTime != info.lastUpdateTime
        }.getOrDefault(true)
}
