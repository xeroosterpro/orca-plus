package com.wholphinplus.sources

import android.content.Context
import com.wholphinplus.sources.core.ServerConnection
import com.wholphinplus.sources.core.ServerKind
import com.wholphinplus.sources.core.WatchEntry
import com.wholphinplus.sources.core.normalizeServerUrl
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.jellyfin.sdk.api.client.ApiClient
import timber.log.Timber
import java.time.Duration
import java.time.Instant

/**
 * Pulls what you watched on the extra servers (in other apps) into the
 * [ProgressOverlay] for the matching main-server items, newest wins. The overlay then merges it
 * into Wholphin's Continue Watching, Next Up and resume positions.
 *
 * Only items that also exist on the main server can be synced; others are skipped.
 */
internal class WatchSync(
    context: Context,
    private val hook: SourceHook,
    private val jellyfin: ApiClient,
    private val overlay: ProgressOverlay,
) {
    private val prefs = context.getSharedPreferences("wholphinplus_sync", Context.MODE_PRIVATE)
    private var lastRun = 0L

    private val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + Dispatchers.IO)
    private var job: kotlinx.coroutines.Job? = null

    /**
     * Start a sync in the background if one is due, then wait briefly so quick incremental runs
     * land in the row being loaded. A long first run carries on and shows up on the next refresh.
     */
    suspend fun syncIfStale() {
        val running =
            synchronized(this) {
                job?.takeIf { it.isActive }
                    ?: if (System.currentTimeMillis() - lastRun >= MIN_INTERVAL_MS) {
                        lastRun = System.currentTimeMillis()
                        scope.launch {
                            val start = System.currentTimeMillis()
                            withTimeoutOrNull(BUDGET_MS) { runSync() }
                                ?: Timber.i("Watch sync: time budget used, continuing next run")
                            Timber.i("Watch sync finished in %d ms", System.currentTimeMillis() - start)
                        }.also { job = it }
                    } else {
                        null
                    }
            } ?: return
        withTimeoutOrNull(HOME_WAIT_MS) { running.join() }
    }

    private suspend fun runSync() {
        val main = hook.mainConnection() ?: return
        val extras = hook.searchableConnections().filter { it.serverKind != ServerKind.PLEX }
        for (extra in extras) {
            try {
                syncFrom(extra, main)
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                Timber.w(e, "Watch sync from %s failed", extra.label)
            }
        }
    }

    private suspend fun syncFrom(
        extra: ServerConnection,
        main: ServerConnection,
    ) {
        val key = "overlay_since_${extra.connectionId}"
        val since = Instant.ofEpochMilli(prefs.getLong(key, Instant.now().minus(FIRST_LOOKBACK).toEpochMilli()))
        // The newest 20 come first; a full batch may hide older ones since the last run (a binge,
        // the first 30 days), and the mark below moves past them, so ask for more until it isn't
        var limit = 20
        var entries = hook.client.recentWatchActivity(extra, since, limit)
        while (entries.size >= limit && limit < MAX_BATCH) {
            limit = (limit * 5).coerceAtMost(MAX_BATCH)
            entries = hook.client.recentWatchActivity(extra, since, limit)
        }
        Timber.i("Watch sync: %d new items on %s since %s", entries.size, extra.label, since)
        if (entries.isEmpty()) return
        // Oldest first, saving progress after each, so a timeout never skips anything.
        for (entry in entries.sortedBy { it.lastPlayed }) {
            apply(entry, extra, main)
            prefs.edit().putLong(key, entry.lastPlayed.toEpochMilli()).apply()
        }
    }

    private suspend fun apply(
        entry: WatchEntry,
        extra: ServerConnection,
        main: ServerConnection,
    ) {
        val copies = hook.client.matchItemIds(main, entry.request)
        if (copies.isEmpty()) {
            Timber.d("Watch sync: %s from %s is not on the main server", entry.request.title, extra.label)
            return
        }
        // With several copies (HD and 4K libraries), update the one you last used there.
        val target =
            copies
                .take(4)
                .map { it to runCatching { hook.client.userData(main, it) }.getOrNull() }
                .maxByOrNull { it.second?.lastPlayed ?: Instant.EPOCH }
                ?: return
        val current = target.second
        if (current?.lastPlayed != null && !current.lastPlayed.isBefore(entry.lastPlayed)) return
        if (entry.played && current?.played == true && current.positionTicks == 0L) return
        overlay.record(
            target.first,
            ProgressOverlay.Entry(entry.positionTicks, entry.played, entry.lastPlayed.toEpochMilli(), current?.seriesId, entry.request.title),
        )
        Timber.i(
            "Watch sync: %s %s from %s",
            entry.request.title + (entry.request.season?.let { " S%02dE%02d".format(it, entry.request.episode) } ?: ""),
            if (entry.played) "watched" else "at ${entry.positionTicks / 600_000_000}m",
            extra.label,
        )
    }

    private companion object {
        const val MIN_INTERVAL_MS = 2 * 60 * 1000L
        const val BUDGET_MS = 90_000L
        const val HOME_WAIT_MS = 1_500L
        const val MAX_BATCH = 500
        val FIRST_LOOKBACK: Duration = Duration.ofDays(30)
    }
}
