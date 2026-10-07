package com.wholphinplus.sources

import com.wholphinplus.sources.cinema.Conductor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import org.jellyfin.sdk.api.client.extensions.get
import kotlinx.serialization.Serializable
import timber.log.Timber
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.util.UUID

/**
 * The main server's whole library by TMDB and IMDb id, so a list matches by id in one lookup per
 * title instead of a name search each (a 1,000-title list was minutes of searches, and name
 * search misses short titles: Silo finds nothing for "Up"). Kept the way Infuse and Kodi keep
 * their library: read in full once (and weekly, which drops titles the server no longer has),
 * then daily only what was added since. A full read asks for just each title's id and provider
 * ids, four pages of 1,000 at a time (~30 s for Silo's 125k titles; one at a time was 3 min),
 * and waits whenever the remote is in use, so it never competes with browsing. Held compactly:
 * sorted int keys and the item ids as two longs each (~4 MB for 125k titles).
 */
internal class LibraryIndex(
    private val file: File,
) {
    /** One kind's titles: [tmdb]/[imdb] sorted, each with its item's place in [msb]/[lsb]. */
    class Part(
        val tmdb: IntArray,
        val tmdbAt: IntArray,
        val imdb: IntArray,
        val imdbAt: IntArray,
        val msb: LongArray,
        val lsb: LongArray,
    ) {
        fun find(
            tmdbId: Int?,
            imdbId: String?,
        ): String? {
            tmdbId?.let { k -> java.util.Arrays.binarySearch(tmdb, k).takeIf { it >= 0 }?.let { return id(tmdbAt[it]) } }
            imdbNumber(imdbId)?.let { k -> java.util.Arrays.binarySearch(imdb, k).takeIf { it >= 0 }?.let { return id(imdbAt[it]) } }
            return null
        }

        /** The id as the server writes it (32 hex digits, no dashes). */
        private fun id(at: Int) = UUID(msb[at], lsb[at]).toString().replace("-", "")

        val size: Int get() = msb.size

        /** This part with [more] titles added (a title already here keeps its first item). */
        fun plus(more: List<Title>): Part {
            if (more.isEmpty()) return this
            val b = Builder()
            for (i in 0 until size) b.addKeys(msb[i], lsb[i], tmdbKeyOf(i), imdbKeyOf(i))
            more.forEach(b::add)
            return b.build()
        }

        // Each item's own keys, read back from the sorted arrays (only for merging, rare)
        private val tmdbByItem by lazy { IntArray(size) { NONE }.also { a -> tmdbAt.forEachIndexed { k, at -> if (a[at] == NONE) a[at] = tmdb[k] } } }
        private val imdbByItem by lazy { IntArray(size) { NONE }.also { a -> imdbAt.forEachIndexed { k, at -> if (a[at] == NONE) a[at] = imdb[k] } } }

        private fun tmdbKeyOf(i: Int) = tmdbByItem[i].takeIf { it != NONE }

        private fun imdbKeyOf(i: Int) = imdbByItem[i].takeIf { it != NONE }
    }

    /** One title as the index reads it: its item id and the ids lists know it by. */
    class Title(
        val id: UUID,
        val tmdb: Int?,
        val imdb: Int?,
    )

    /** A server's index: [movies], [series]; read in full at [fullAt], last brought up to date at [builtAt]. */
    class Snapshot(
        val owner: String,
        val builtAt: Long,
        val fullAt: Long,
        val movies: Part,
        val series: Part,
    )

    /** A full read in progress, for the "Getting your library ready" notice. */
    data class Progress(
        val done: Int,
        val total: Int,
        /** The first read for this server (rows fill in when it's done); else the weekly one. */
        val first: Boolean,
    )

    private val _progress = MutableStateFlow<Progress?>(null)

    /** A full read in progress (null when none). */
    val progress: StateFlow<Progress?> = _progress.asStateFlow()

    private val mutex = Mutex()

    /** The index in memory for [owner], without waiting or reading anything (null if none yet). */
    fun peek(owner: String): Snapshot? = current?.takeIf { it.owner == owner }

    @Volatile private var current: Snapshot? = null

    /**
     * The index for [owner] in memory, else as saved on the device: no server requests, and no
     * waiting behind a read in progress (null then, or when none is saved). For rows that would
     * otherwise search title by title on a cold start.
     */
    suspend fun saved(owner: String): Snapshot? {
        peek(owner)?.let { return it }
        if (!mutex.tryLock()) return null
        return try {
            withContext(Dispatchers.IO) {
                current?.takeIf { it.owner == owner } ?: runCatching { read() }.getOrNull()?.takeIf { it.owner == owner }?.also { current = it }
            }
        } finally {
            mutex.unlock()
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile private var updating: Job? = null

    /**
     * The index for [owner] (server + user). None yet: read in full now (the caller waits; rows
     * match their first titles by search meanwhile). A week since the last full read: served as
     * it is while a full read runs in the background. A day since the last update: served while
     * the titles added since are read (seconds). Null when the server can't be read and there's
     * nothing saved.
     */
    suspend fun ensure(
        hook: SourceHook,
        owner: String,
    ): Snapshot? {
        val known =
            mutex.withLock {
                withContext(Dispatchers.IO) { current?.takeIf { it.owner == owner } ?: runCatching { read() }.getOrNull()?.takeIf { it.owner == owner } }.also { current = it }
            }
        if (known == null) return update(hook, owner, full = true)
        val t = System.currentTimeMillis()
        val full = t - known.fullAt >= FULL_MS
        if ((full || t - known.builtAt >= DAY_MS) && updating?.isActive != true) {
            updating = scope.launch { update(hook, owner, full) }
        }
        return known
    }

    /** Reads the library in full ([full]) or what was added since the last read; the index in use stays until it's done. */
    private suspend fun update(
        hook: SourceHook,
        owner: String,
        full: Boolean,
    ): Snapshot? =
        mutex.withLock {
            withContext(Dispatchers.IO) {
                val before = current?.takeIf { it.owner == owner }
                try {
                    val next =
                        if (full || before == null) {
                            readAll(hook, owner, first = before == null)
                        } else {
                            // Thousands added since (a new library): a full read instead
                            readNew(hook, before) ?: readAll(hook, owner, first = false)
                        }
                    current = next
                    write(next)
                    next
                } catch (e: Exception) {
                    if (e is kotlinx.coroutines.CancellationException) throw e
                    Timber.w(e, "Library index: couldn't read the library")
                    before
                } finally {
                    _progress.value = null
                }
            }
        }

    private fun userId(hook: SourceHook): UUID = hook.mainConnection()?.userId?.let { UUID.fromString(ProgressOverlay.dashed(it)) } ?: error("Not signed in")

    /** One page of [kind] from [start], light: ids only (and when each was added, for [newest]). */
    private suspend fun page(
        hook: SourceHook,
        userId: UUID,
        kind: String,
        start: Int,
        newest: Boolean = false,
    ): IndexPage =
        hook.jellyfin
            .get<IndexPage>(
                "/Items",
                queryParameters =
                    buildMap {
                        put("userId", userId)
                        put("includeItemTypes", kind)
                        put("recursive", true)
                        put("startIndex", start)
                        put("limit", PAGE)
                        put("fields", if (newest) "ProviderIds,DateCreated" else "ProviderIds")
                        put("enableImages", false)
                        put("enableUserData", false)
                        if (newest) {
                            put("sortBy", "DateCreated")
                            put("sortOrder", "Descending")
                        }
                    },
            ).content

    private suspend fun readAll(
        hook: SourceHook,
        owner: String,
        first: Boolean,
    ): Snapshot {
        val started = System.currentTimeMillis()
        val userId = userId(hook)
        // How much there is, for the notice: the first page of each kind says
        val firstPages = KINDS.associateWith { page(hook, userId, it, 0) }
        val total = firstPages.values.sumOf { it.total }
        var done = 0
        _progress.value = Progress(0, total, first)
        val parts =
            KINDS.associateWith { kind ->
                val b = Builder()
                val head = firstPages.getValue(kind)
                head.titles().forEach(b::add)
                done += head.items.size
                _progress.value = Progress(done, total, first)
                (PAGE until head.total step PAGE).chunked(AT_ONCE).forEach { starts ->
                    // Never while the remote is in use: browsing gets the CPU and the network
                    Conductor.whenQuiet()
                    val pages = coroutineScope { starts.map { s -> async { page(hook, userId, kind, s) } }.awaitAll() }
                    pages.forEach { p ->
                        p.titles().forEach(b::add)
                        done += p.items.size
                    }
                    _progress.value = Progress(done.coerceAtMost(total), total, first)
                }
                b.build()
            }
        val now = System.currentTimeMillis()
        val s = Snapshot(owner, now, now, parts.getValue(MOVIE), parts.getValue(SERIES))
        Timber.i("Library index: %d movies, %d shows in %d ms (full)", s.movies.size, s.series.size, now - started)
        return s
    }

    /** What the server added since [before] was read, newest first, until titles from before it; null when it's thousands. */
    private suspend fun readNew(
        hook: SourceHook,
        before: Snapshot,
    ): Snapshot? {
        val started = System.currentTimeMillis()
        val userId = userId(hook)
        // A day's margin: a title added while the last read ran is read again (and kept once)
        val since = before.builtAt - DAY_MS
        val added =
            KINDS.associateWith { kind ->
                val found = ArrayList<Title>()
                var start = 0
                while (true) {
                    Conductor.whenQuiet()
                    val p = page(hook, userId, kind, start, newest = true)
                    found += p.items.filter { (parseTime(it.dateCreated) ?: Long.MAX_VALUE) >= since }.mapNotNull { it.title() }
                    val oldest = p.items.mapNotNull { parseTime(it.dateCreated) }.minOrNull()
                    start += p.items.size
                    if (p.items.isEmpty() || start >= p.total || (oldest != null && oldest < since)) break
                    if (start >= NEW_LIMIT) return null
                }
                found
            }
        val s = Snapshot(before.owner, System.currentTimeMillis(), before.fullAt, before.movies.plus(added.getValue(MOVIE)), before.series.plus(added.getValue(SERIES)))
        Timber.i("Library index: %d new movies, %d new shows in %d ms", added.getValue(MOVIE).size, added.getValue(SERIES).size, System.currentTimeMillis() - started)
        return s
    }

    private fun parseTime(s: String?): Long? = s?.let { runCatching { java.time.OffsetDateTime.parse(it).toInstant().toEpochMilli() }.getOrNull() ?: runCatching { java.time.Instant.parse(it).toEpochMilli() }.getOrNull() }

    /** Titles in order, keys sorted on build; a title in several libraries (HD, 4K) keeps the first. */
    private class Builder {
        private var msb = LongArray(1024)
        private var lsb = LongArray(1024)
        private var n = 0
        private val tmdb = Pairs()
        private val imdb = Pairs()

        fun add(t: Title) = addKeys(t.id.mostSignificantBits, t.id.leastSignificantBits, t.tmdb, t.imdb)

        fun addKeys(
            hi: Long,
            lo: Long,
            t: Int?,
            i: Int?,
        ) {
            if (t == null && i == null) return
            if (n == msb.size) {
                msb = msb.copyOf(n * 2)
                lsb = lsb.copyOf(n * 2)
            }
            msb[n] = hi
            lsb[n] = lo
            t?.let { tmdb.add(it, n) }
            i?.let { imdb.add(it, n) }
            n++
        }

        fun build(): Part {
            val (tk, ta) = tmdb.sorted()
            val (ik, ia) = imdb.sorted()
            return Part(tk, ta, ik, ia, msb.copyOf(n), lsb.copyOf(n))
        }
    }

    /** Key → item pairs, sorted by key, the first item kept for a key. */
    private class Pairs {
        private var keys = LongArray(1024) // key << 32 | item, so one sort orders both

        private var n = 0

        fun add(
            key: Int,
            item: Int,
        ) {
            if (n == keys.size) keys = keys.copyOf(n * 2)
            keys[n++] = (key.toLong() shl 32) or (item.toLong() and 0xffffffffL)
        }

        fun sorted(): Pair<IntArray, IntArray> {
            val a = keys.copyOf(n)
            a.sort()
            val k = IntArray(n)
            val at = IntArray(n)
            var m = 0
            for (v in a) {
                val key = (v shr 32).toInt()
                if (m > 0 && k[m - 1] == key) continue
                k[m] = key
                at[m] = v.toInt()
                m++
            }
            return k.copyOf(m) to at.copyOf(m)
        }
    }

    private fun write(s: Snapshot) {
        val tmp = File(file.path + ".tmp")
        DataOutputStream(tmp.outputStream().buffered()).use { out ->
            out.writeInt(FORMAT)
            out.writeUTF(s.owner)
            out.writeLong(s.builtAt)
            out.writeLong(s.fullAt)
            for (p in listOf(s.movies, s.series)) {
                for (a in listOf(p.tmdb, p.tmdbAt, p.imdb, p.imdbAt)) {
                    out.writeInt(a.size)
                    a.forEach(out::writeInt)
                }
                out.writeInt(p.msb.size)
                for (i in p.msb.indices) {
                    out.writeLong(p.msb[i])
                    out.writeLong(p.lsb[i])
                }
            }
        }
        if (!tmp.renameTo(file)) Timber.w("Library index: couldn't replace %s", file.name)
    }

    private fun read(): Snapshot? {
        if (!file.exists()) return null
        DataInputStream(file.inputStream().buffered()).use { inp ->
            if (inp.readInt() != FORMAT) return null
            val owner = inp.readUTF()
            val builtAt = inp.readLong()
            val fullAt = inp.readLong()

            fun ints() = IntArray(inp.readInt()) { inp.readInt() }

            fun part(): Part {
                val (t, ta, i, ia) = List(4) { ints() }
                val n = inp.readInt()
                val msb = LongArray(n)
                val lsb = LongArray(n)
                for (k in 0 until n) {
                    msb[k] = inp.readLong()
                    lsb[k] = inp.readLong()
                }
                return Part(t, ta, i, ia, msb, lsb)
            }
            return Snapshot(owner, builtAt, fullAt, part(), part())
        }
    }

    companion object {
        const val DAY_MS = 24 * 60 * 60 * 1000L
        private const val FULL_MS = 7 * DAY_MS
        private const val PAGE = 1000
        private const val AT_ONCE = 4
        private const val NEW_LIMIT = 5000
        private const val FORMAT = 2
        private const val NONE = Int.MIN_VALUE
        private const val MOVIE = "Movie"
        private const val SERIES = "Series"
        private val KINDS = listOf(MOVIE, SERIES)

        /** "tt0111161" → 111161 (null for anything else). */
        fun imdbNumber(id: String?): Int? = id?.trim()?.lowercase()?.removePrefix("tt")?.takeIf { it.isNotEmpty() && it.all(Char::isDigit) }?.toIntOrNull()
    }
}

/** A page of titles as the index reads them: nothing but ids (a full item is ~30 fields). */
@Serializable
internal class IndexPage(
    @SerialName("Items") val items: List<IndexItem> = emptyList(),
    @SerialName("TotalRecordCount") val total: Int = 0,
) {
    fun titles(): List<LibraryIndex.Title> = items.mapNotNull { it.title() }
}

@Serializable
internal class IndexItem(
    @SerialName("Id") val id: String = "",
    @SerialName("ProviderIds") val providerIds: Map<String, String?>? = null,
    @SerialName("DateCreated") val dateCreated: String? = null,
) {
    fun title(): LibraryIndex.Title? {
        val uuid = runCatching { UUID.fromString(ProgressOverlay.dashed(id)) }.getOrNull() ?: return null
        val ids = providerIds.orEmpty()
        val tmdb = ids.entries.firstOrNull { it.key.equals("Tmdb", true) }?.value?.toIntOrNull()
        val imdb = LibraryIndex.imdbNumber(ids.entries.firstOrNull { it.key.equals("Imdb", true) }?.value)
        return if (tmdb == null && imdb == null) null else LibraryIndex.Title(uuid, tmdb, imdb)
    }
}
