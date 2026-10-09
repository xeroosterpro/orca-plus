package com.wholphinplus.sources.cinema

import android.content.Context
import com.wholphinplus.sources.SearchService
import com.wholphinplus.sources.core.TitleArt
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Title logos and title-art card images from TMDB, for servers that don't store them.
 * Fetched as cards come into view, cached on the device so each title is looked up once.
 */
@Singleton
class CinemaArt
    @Inject
    constructor(
        @param:ApplicationContext context: Context,
        private val search: SearchService,
    ) {
        private val prefs = context.getSharedPreferences("wholphinplus_art", Context.MODE_PRIVATE)
        private val file = java.io.File(context.filesDir, "cinema_art_v4.json")
        private val cache = ConcurrentHashMap<String, TitleArt>()

        /** When each title's art was last shown, so the cache drops the ones not seen for longest. */
        private val used = ConcurrentHashMap<String, Long>()

        /** Lookups on their way: every card, caption and billboard asking for a title waits for one. */
        private val inFlight = ConcurrentHashMap<String, CompletableDeferred<TitleArt?>>()
        private val gate = Semaphore(4)
        private val io = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        private var pending: Job? = null

        /** The saved art, read off the main thread when the app starts; lookups wait for it. */
        private val ready: Job = io.launch { load() }

        val enabled: Boolean get() = search.tmdb.available

        /** Already-known art, readable on the first frame (no flash from server art to TMDB art). */
        fun cached(
            tv: Boolean,
            tmdbId: Int,
        ): TitleArt? {
            if (!enabled) return null
            val key = key(tv, tmdbId)
            return cache[key]?.also { used[key] = System.currentTimeMillis() }
        }

        suspend fun art(
            tv: Boolean,
            tmdbId: Int,
        ): TitleArt? {
            if (!enabled) return null
            val key = key(tv, tmdbId)
            if (!ready.isCompleted) ready.join()
            while (true) {
                cache[key]?.let {
                    used[key] = System.currentTimeMillis()
                    return it
                }
                val mine = CompletableDeferred<TitleArt?>()
                val other = inFlight.putIfAbsent(key, mine)
                if (other != null) {
                    // Someone is already asking: wait for that answer. If they gave up (their card
                    // left the screen), ask again ourselves
                    val answer = runCatching { other.await() }
                    currentCoroutineContext().ensureActive()
                    if (answer.isSuccess) return answer.getOrNull()
                    continue
                }
                try {
                    val fetched =
                        gate.withPermit {
                            // Arrived while this one queued
                            cache[key] ?: withContext(Dispatchers.IO) {
                                runCatching { search.tmdb.titleArt(tv, tmdbId) }.getOrElse { e ->
                                    if (e is kotlinx.coroutines.CancellationException) throw e
                                    null
                                }
                            }
                        }
                    if (fetched != null && cache.put(key, fetched) == null) {
                        used[key] = System.currentTimeMillis()
                        save()
                    }
                    mine.complete(fetched)
                    return fetched
                } catch (e: Throwable) {
                    mine.completeExceptionally(e)
                    throw e
                } finally {
                    inFlight.remove(key, mine)
                }
            }
        }

        /** Writes the cache a few seconds after the last new title, off the UI thread (it's big). */
        fun save() {
            pending?.cancel()
            pending =
                io.launch {
                    delay(4_000)
                    Conductor.whenQuiet()
                    ready.join()
                    leastRecentlyUsed(cache.keys, used, MAX).forEach {
                        cache.remove(it)
                        used.remove(it)
                    }
                    CacheFiles.write(file, TitleArt.serializer(), CacheFiles.Saved(HashMap(cache), HashMap(used)))
                }
        }

        private fun load() {
            val saved = CacheFiles.read(file, TitleArt.serializer())
            if (saved != null) {
                saved.items.forEach { (k, v) -> cache.putIfAbsent(k, v) }
                saved.used.forEach { (k, v) -> used.putIfAbsent(k, v) }
                return
            }
            // Carried over once from the preferences it used to live in
            val old = CacheFiles.legacy(prefs.getString(KEY, null), TitleArt.serializer())
            old.forEach { (k, v) -> cache.putIfAbsent(k, v) }
            prefs.edit().clear().apply()
            if (old.isNotEmpty()) CacheFiles.write(file, TitleArt.serializer(), CacheFiles.Saved(HashMap(cache), HashMap(used)))
        }

        private fun key(
            tv: Boolean,
            tmdbId: Int,
        ) = (if (tv) "tv:" else "movie:") + tmdbId

        private companion object {
            // v4: titles carry their services, TMDB score and release date; older entries are fetched again
            const val KEY = "art_v4"

            // ~250 bytes a title: five tabs alone show ~3,000, and dropping titles that are on
            // screen meant looking them up again on the next start
            const val MAX = 10_000
        }
    }
