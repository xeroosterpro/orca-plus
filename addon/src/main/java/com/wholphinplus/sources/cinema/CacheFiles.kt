package com.wholphinplus.sources.cinema

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromStream
import kotlinx.serialization.json.encodeToStream
import timber.log.Timber
import java.io.File

/**
 * The device caches of title facts (TMDB art, badges, review scores), as plain files written and
 * read as streams. They used to live in SharedPreferences as one JSON string: every new title
 * re-encoded the whole map into a String (~750 KB for art), the XML file was rewritten, and the
 * String stayed in memory for the app's life beside the map itself.
 */
@OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
internal object CacheFiles {
    private val json = Json { ignoreUnknownKeys = true }

    @Volatile private var oldPrefsDropped = false

    /** A cache as saved: its entries, and when each was last shown (for dropping the oldest). */
    @Serializable
    class Saved<V>(
        val items: Map<String, V>,
        val used: Map<String, Long> = emptyMap(),
    )

    fun <V> read(
        file: File,
        serializer: KSerializer<V>,
    ): Saved<V>? =
        runCatching {
            if (!file.exists()) return null
            file.inputStream().buffered().use { json.decodeFromStream(Saved.serializer(serializer), it) }
        }.onFailure {
            Timber.w(it, "Cache %s unreadable; starting over", file.name)
            file.delete()
        }.getOrNull()

    fun <V> write(
        file: File,
        serializer: KSerializer<V>,
        saved: Saved<V>,
    ) {
        runCatching {
            file.parentFile?.mkdirs()
            val tmp = File(file.path + ".tmp")
            tmp.outputStream().buffered().use { json.encodeToStream(Saved.serializer(serializer), saved, it) }
            if (!tmp.renameTo(file)) tmp.delete()
        }.onFailure { Timber.w(it, "Cache %s not saved", file.name) }
    }

    /**
     * Where the cache [name] lives: the no-backup folder, so what can be fetched again (megabytes
     * on a big library) isn't copied into the device's backups. A copy from the backed-up folder
     * it used to live in (and its `name.*` companions) is moved over once.
     */
    fun at(
        context: android.content.Context,
        name: String,
    ): File {
        val app = context.applicationContext
        if (!oldPrefsDropped) {
            oldPrefsDropped = true
            // Emptied when these caches moved to files (2026-10-08); only the files were left
            for (p in listOf("wholphinplus_art", "wholphinplus_ratings", "wholphinplus_streams")) app.deleteSharedPreferences(p)
        }
        val target = File(app.noBackupFilesDir, name)
        app.filesDir.listFiles { f -> f.name == name || f.name.startsWith("$name.") }?.forEach { old ->
            val moved = File(app.noBackupFilesDir, old.name)
            if (moved.exists() || !old.renameTo(moved)) old.delete()
        }
        return target
    }
}

/**
 * Which of [keys] to drop when there are more than [max]: the least recently [used] first (never
 * shown since the stamps began: oldest of all), down to three quarters of [max], so a cache
 * isn't trimmed again with every new title. It used to drop in hash order, which could take
 * titles on Home right now; they were then looked up again on the next start.
 */
internal fun leastRecentlyUsed(
    keys: Collection<String>,
    used: Map<String, Long>,
    max: Int,
): List<String> {
    if (keys.size <= max) return emptyList()
    return keys.sortedBy { used[it] ?: 0L }.take(keys.size - max * 3 / 4)
}
