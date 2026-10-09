package com.wholphinplus.sources

import android.content.Context
import com.wholphinplus.sources.core.ExternalSource
import com.wholphinplus.sources.core.ServerKind
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import org.jellyfin.sdk.model.api.BaseItemDto
import timber.log.Timber
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID
import kotlin.math.abs

/**
 * Pictures for the player that the main server doesn't have: chapter pictures and seek-bar
 * thumbnails, borrowed from the copy being played or from an extra server's copy of the same cut.
 * (Owner, 2026-10-08: no thumbnails when skipping by chapters. Silo lists chapters but has no
 * chapter pictures and no trickplay; Emby servers often have both: chapter images and a BIF
 * thumbnail track, one small picture every few seconds.) One title plays at a time.
 */
object PlayerPictures {
    data class Borrowed(
        val itemId: UUID,
        // Chapter pictures by start (ms), from the copy that has them
        val chapters: List<Pair<Long, String>> = emptyList(),
        val thumbnails: Bif? = null,
        val from: String = "",
    )

    private val _state = MutableStateFlow<Borrowed?>(null)
    val state: StateFlow<Borrowed?> = _state.asStateFlow()

    // The title pictures are being found for: nothing for another title is published or shown
    private val _playing = MutableStateFlow<UUID?>(null)
    val playing: StateFlow<UUID?> = _playing.asStateFlow()

    /** The biggest thumbnail track worth fetching (a 3 h film at 320 px is ~15 MB). */
    const val MAX_BIF_BYTES = 30L * 1024 * 1024

    /** What the main server lacks for [base]: chapter pictures, seek thumbnails (Wholphin shows its own otherwise). */
    fun needs(base: BaseItemDto): Pair<Boolean, Boolean> {
        val chapters = base.chapters.orEmpty()
        val wantChapters = chapters.isNotEmpty() && chapters.any { it.imageTag.isNullOrBlank() }
        val wantThumbnails = base.trickplay.isNullOrEmpty() || base.trickplay!!.values.all { it.isEmpty() }
        return wantChapters to wantThumbnails
    }

    /** Publishes [change] only while [itemId] is still the title being played. */
    private fun publish(
        itemId: UUID,
        change: (Borrowed?) -> Borrowed?,
    ) = _state.update { b -> if (_playing.value == itemId) change(b?.takeIf { it.itemId == itemId }) else b }

    /** A chapter starting this close to a borrowed one is the same chapter (cuts and rounding differ). */
    const val SAME_CHAPTER_MS = 3_000L

    /** The borrowed picture for [itemId]'s chapter starting at [startMs], or null. */
    fun chapterImage(
        b: Borrowed?,
        itemId: UUID,
        startMs: Long,
    ): String? {
        if (b == null || b.itemId != itemId) return null
        return nearestChapter(b.chapters, startMs)
    }

    fun nearestChapter(
        chapters: List<Pair<Long, String>>,
        startMs: Long,
    ): String? = chapters.minByOrNull { abs(it.first - startMs) }?.takeIf { abs(it.first - startMs) <= SAME_CHAPTER_MS }?.second

    fun clear() {
        _playing.value = null
        _state.update { null }
    }

    /** Copies of the same cut: runtimes within a minute (or 1%). Unknown runtimes don't match. */
    fun sameCut(
        mainTicks: Long,
        copyTicks: Long,
    ): Boolean {
        if (mainTicks <= 0 || copyTicks <= 0) return false
        val diff = abs(mainTicks - copyTicks)
        return diff <= maxOf(60L * 10_000_000, mainTicks / 100)
    }

    /**
     * Finds pictures for [base] (playing from [playing], or the main server when null) and
     * publishes them. Runs off the main thread; a later call for another title replaces it.
     */
    internal suspend fun load(
        hook: SourceHook,
        context: Context,
        base: BaseItemDto,
        playing: ExternalSource?,
    ) {
        val itemId = base.id
        _playing.value = itemId
        if (_state.value?.itemId != itemId) _state.update { null }
        // Only what the main server doesn't have: no lookups or download when it has both
        val (wantChapters, wantThumbnails) = needs(base)
        if (!wantChapters && !wantThumbnails) return
        val candidates: List<ExternalSource> =
            buildList {
                if (playing != null) add(playing)
                val request =
                    try {
                        hook.requestFor(base)
                    } catch (e: Exception) {
                        if (e is kotlinx.coroutines.CancellationException) throw e
                        null
                    }
                if (request != null) {
                    hook
                        .searchableConnections()
                        .filter { it.serverKind == ServerKind.EMBY || it.serverKind == ServerKind.JELLYFIN }
                        .forEach { c -> hook.findOn(c, request).filter { sameCut(base.runTimeTicks ?: 0, it.runTimeTicks) }.forEach(::add) }
                }
            }.distinctBy { it.connectionId + it.itemId + it.mediaSourceId }
                // Emby first: only Emby has the thumbnail track
                .sortedBy { if (it == playing) 0 else if (it.serverKind == ServerKind.EMBY) 1 else 2 }
                .take(4)
        var chapters: List<Pair<Long, String>> = emptyList()
        var bifFrom: Pair<ExternalSource, String>? = null
        var from = ""
        for (copy in candidates) {
            val connection = hook.connectionFor(copy.connectionId) ?: continue
            val pictures = runCatching { hook.client.copyPictures(connection, copy.itemId, copy.mediaSourceId) }.getOrNull() ?: continue
            if (wantChapters && chapters.isEmpty() && pictures.chapters.isNotEmpty()) {
                chapters = pictures.chapters
                from = copy.serverLabel
            }
            if (wantThumbnails && bifFrom == null && pictures.bifUrl != null) bifFrom = copy to pictures.bifUrl
            if ((!wantChapters || chapters.isNotEmpty()) && (!wantThumbnails || bifFrom != null)) break
        }
        currentCoroutineContext().ensureActive()
        if (chapters.isNotEmpty()) {
            Timber.i("Player pictures for %s: %d chapter pictures from %s", itemId, chapters.size, from)
            publish(itemId) { Borrowed(itemId, chapters, null, from) }
        }
        val (copy, url) = bifFrom ?: return
        // Let the stream get going first; the track is a few MB
        delay(8_000)
        val connection = hook.connectionFor(copy.connectionId) ?: return
        val dir = File(context.cacheDir, "orca_thumbnails").apply { mkdirs() }
        val file = File(dir, "${copy.connectionId.hashCode().toUInt()}_${copy.itemId}_${copy.mediaSourceId.hashCode().toUInt()}.bif")
        if (!file.exists() || file.length() < 64) {
            // Leftovers of downloads a kill or a crash cut short (one title loads at a time)
            dir.listFiles { f -> f.name.endsWith(".part") }?.forEach { it.delete() }
            val part = File(dir, file.name + ".part")
            // Interruptible: leaving the title stops the download instead of finishing it unseen
            val ok =
                try {
                    runInterruptible(Dispatchers.IO) { hook.client.download(connection, url, part, MAX_BIF_BYTES) }
                } catch (e: Exception) {
                    part.delete()
                    if (e is kotlinx.coroutines.CancellationException) throw e
                    false
                }
            if (!ok || !part.renameTo(file)) {
                part.delete()
                Timber.i("Player pictures for %s: no thumbnails on %s", itemId, copy.serverLabel)
                return
            }
            // Keep the last few titles' tracks
            dir.listFiles { f -> f.name.endsWith(".bif") }?.sortedByDescending { it.lastModified() }?.drop(6)?.forEach { it.delete() }
        }
        val bif = runCatching { Bif.open(file) }.getOrNull() ?: return file.delete().let {}
        // Left the title meanwhile: publish nothing
        currentCoroutineContext().ensureActive()
        Timber.i("Player pictures for %s: %d thumbnails from %s", itemId, bif.count, copy.serverLabel)
        publish(itemId) { b -> (b ?: Borrowed(itemId, from = copy.serverLabel)).copy(thumbnails = bif) }
    }
}

/**
 * Emby's thumbnail track (Roku's BIF): a 64-byte header ("\u0089BIF\r\n\u001a\n", version, picture
 * count, ms per timestamp unit), then (timestamp, offset) pairs, one more than the pictures
 * (the last offset is the end), then the JPEG pictures.
 */
class Bif private constructor(
    private val file: File,
    private val timesMs: LongArray,
    private val offsets: LongArray,
) {
    val count: Int get() = timesMs.size

    /** Which picture shows at [positionMs]: the last one starting at or before it. */
    fun indexAt(positionMs: Long): Int {
        var lo = 0
        var hi = timesMs.lastIndex
        if (hi < 0) return -1
        while (lo < hi) {
            val mid = (lo + hi + 1) / 2
            if (timesMs[mid] <= positionMs) lo = mid else hi = mid - 1
        }
        return lo
    }

    /** The JPEG bytes of picture [index]. */
    @Synchronized
    fun picture(index: Int): ByteArray? {
        if (index !in timesMs.indices) return null
        val start = offsets[index]
        val length = (offsets[index + 1] - start).toInt()
        if (length <= 0 || length > 2_000_000) return null
        return RandomAccessFile(file, "r").use { f ->
            f.seek(start)
            ByteArray(length).also { f.readFully(it) }
        }
    }

    companion object {
        private val MAGIC = byteArrayOf(0x89.toByte(), 0x42, 0x49, 0x46, 0x0d, 0x0a, 0x1a, 0x0a)

        fun open(file: File): Bif {
            RandomAccessFile(file, "r").use { f ->
                val header = ByteArray(64).also { f.readFully(it) }
                val (count, unitMs) = header(header)
                val table = ByteArray((count + 1) * 8).also { f.readFully(it) }
                val (times, offsets) = index(table, count, unitMs)
                require(offsets.last() <= f.length()) { "BIF is cut short" }
                return Bif(file, times, offsets)
            }
        }

        /** Picture count and ms per timestamp unit, from the 64-byte header. */
        fun header(bytes: ByteArray): Pair<Int, Long> {
            require(bytes.size >= 64 && bytes.copyOfRange(0, 8).contentEquals(MAGIC)) { "not a BIF" }
            val b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            val count = b.getInt(12)
            val unit = b.getInt(16).toLong().takeIf { it > 0 } ?: 1000L
            require(count in 1..200_000) { "bad BIF count $count" }
            return count to unit
        }

        /** Start times (ms) and file offsets (count + 1, the last one the end) from the index table. */
        fun index(
            table: ByteArray,
            count: Int,
            unitMs: Long,
        ): Pair<LongArray, LongArray> {
            val b = ByteBuffer.wrap(table).order(ByteOrder.LITTLE_ENDIAN)
            val times = LongArray(count) { (b.getInt(it * 8).toLong() and 0xffffffffL) * unitMs }
            val offsets = LongArray(count + 1) { b.getInt(it * 8 + 4).toLong() and 0xffffffffL }
            return times to offsets
        }
    }
}
