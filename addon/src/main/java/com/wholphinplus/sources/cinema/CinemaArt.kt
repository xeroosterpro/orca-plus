package com.wholphinplus.sources.cinema

import android.content.Context
import com.wholphinplus.sources.SearchService
import com.wholphinplus.sources.core.TitleArt
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
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
        private val json = Json { ignoreUnknownKeys = true }
        private val cache = ConcurrentHashMap<String, TitleArt>(load())
        private val gate = Semaphore(4)
        private var unsaved = 0

        val enabled: Boolean get() = search.tmdb.hasKey

        /** Already-known art, readable on the first frame (no flash from server art to TMDB art). */
        fun cached(
            tv: Boolean,
            tmdbId: Int,
        ): TitleArt? = if (enabled) cache[(if (tv) "tv:" else "movie:") + tmdbId] else null

        suspend fun art(
            tv: Boolean,
            tmdbId: Int,
        ): TitleArt? {
            if (!enabled) return null
            val key = (if (tv) "tv:" else "movie:") + tmdbId
            cache[key]?.let { return it }
            val fetched =
                gate.withPermit {
                    withContext(Dispatchers.IO) { runCatching { search.tmdb.titleArt(tv, tmdbId) }.getOrNull() }
                } ?: return null
            cache[key] = fetched
            if (++unsaved >= 10) save()
            return fetched
        }

        fun save() {
            unsaved = 0
            prefs.edit().putString(KEY, json.encodeToString(HashMap(cache))).apply()
        }

        private fun load(): Map<String, TitleArt> =
            runCatching { prefs.getString(KEY, null)?.let { json.decodeFromString<Map<String, TitleArt>>(it) } }.getOrNull().orEmpty()

        private companion object {
            const val KEY = "art_v1"
        }
    }
