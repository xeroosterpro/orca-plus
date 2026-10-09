package com.wholphinplus.sources.cinema

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.platform.LocalContext
import coil3.SingletonImageLoader
import coil3.request.CachePolicy
import coil3.request.ImageRequest
import coil3.request.allowHardware
import com.wholphinplus.sources.DeviceClass
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/**
 * Card pictures loaded while the remote rests, outward from where you are (owner, 2026-10-09:
 * "if the user is sitting still or going slow… a good time to catch up on posters for all rows").
 * A card's picture used to start only once the card was built and filled in, so scrolling down
 * from a fresh start every row's pictures began late.
 *
 * Rows near you ([DeviceClass.prefetchMemoryRows]) are decoded into memory at card size, ready to
 * show at once; rows further on ([DeviceClass.prefetchDiskRows]) only download to the disk cache
 * (no memory). Rows ahead in the direction you were going come first. Any move stops it; it starts
 * again from the new spot once the remote rests.
 */
internal object PosterPrefetch {
    /** The remote has to rest this long before loading starts (a slow browse counts). */
    private const val REST_MS = 300L

    /** Pictures loading at once (the main server shares one connection with the rows). */
    private val gate = Semaphore(3)

    /** Pictures already loaded this run: into memory, or to disk only. */
    private val inMemory = java.util.Collections.newSetFromMap(java.util.concurrent.ConcurrentHashMap<String, Boolean>())

    /** Titles whose card picture (and art) are in memory: their cards may fill while the page moves. */
    val ready: MutableSet<Any> = java.util.Collections.newSetFromMap(java.util.concurrent.ConcurrentHashMap())
    private val onDisk = java.util.Collections.newSetFromMap(java.util.concurrent.ConcurrentHashMap<String, Boolean>())

    @Composable
    fun Follow(
        data: CinemaHomeData,
        column: LazyListState,
    ) {
        val context = LocalContext.current
        val art = LocalArt.current
        val overlays = LocalOverlays.current
        LaunchedEffect(data, column) {
            var last = column.firstVisibleItemIndex
            snapshotFlow { column.firstVisibleItemIndex }.collectLatest { at ->
                val down = at >= last
                last = at
                rested()
                load(context, data, at, down, art, overlays)
            }
        }
    }

    private suspend fun rested() {
        while (!Conductor.quietFor(REST_MS) || CardFill.anyBusy) delay(100)
    }

    /** Row indexes by distance from [at], the way you were going first; with their distance. */
    private fun order(
        at: Int,
        count: Int,
        down: Boolean,
    ): List<Pair<Int, Int>> {
        val ahead = if (down) 1 else -1
        val out = mutableListOf(at to 0)
        for (d in 1..DeviceClass.prefetchDiskRows) {
            out += (at + ahead * d) to d
            // Behind: as far as memory reaches, then a little
            if (d <= DeviceClass.prefetchMemoryRows + 1) out += (at - ahead * d) to d
        }
        return out.filter { it.first in 0 until count }
    }

    private suspend fun load(
        context: android.content.Context,
        data: CinemaHomeData,
        at: Int,
        down: Boolean,
        art: CinemaArt?,
        overlays: PosterOverlays,
    ) {
        val loader = SingletonImageLoader.get(context)
        for ((index, distance) in order(at, data.rows.size, down)) {
            val row = data.rows[index]
            if (row.items.isEmpty() || row.loading) continue
            val memory = distance <= DeviceClass.prefetchMemoryRows
            kotlinx.coroutines.coroutineScope {
                row.items.take(DeviceClass.prefetchCards).forEach { item ->
                    // Paused while the remote moves; a move to another row restarts from there
                    rested()
                    launch {
                        gate.withPermit {
                            val url =
                                if (row.ranked) {
                                    item.posterUrl ?: item.cardUrl
                                } else {
                                    val a = item.tmdbId?.let { id -> runCatching { art?.art(item.tmdbTv, id) }.getOrNull() }
                                    cardChoice(item, a, overlays).url
                                } ?: return@withPermit
                            val (w, h) = if (row.ranked) 224 to 336 else 416 to 234
                            if (memory) {
                                if (url !in inMemory) {
                                    // The card's own request: a memory hit when it's built
                                    val ok = runCatching { loader.execute(cardRequest(context, url, w, h)) }.getOrNull() is coil3.request.SuccessResult
                                    if (!ok) return@withPermit
                                    inMemory += url
                                }
                                ready += item.key
                            } else {
                                if (url in inMemory || url in onDisk) return@withPermit
                                // To disk only: decoded at a token size, nothing kept in memory
                                runCatching {
                                    loader.execute(
                                        ImageRequest
                                            .Builder(context)
                                            .data(PosterSize.fit(url))
                                            .size(16, 9)
                                            .memoryCachePolicy(CachePolicy.DISABLED)
                                            .allowHardware(false)
                                            .build(),
                                    )
                                }
                                onDisk += url
                            }
                        }
                    }
                }
            }
        }
    }
}
