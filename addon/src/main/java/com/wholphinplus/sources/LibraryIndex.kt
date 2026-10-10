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
                current?.takeIf { it.owner == owner } ?: runCatching { load(owner) }.getOrNull()?.also { current = it }
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
                withContext(Dispatchers.IO) { current?.takeIf { it.owner == owner } ?: runCatching { load(owner) }.getOrNull() }.also { current = it }
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
        limit: Int = pageSize(hook),
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
                        put("limit", limit)
                        put("fields", if (newest) "ProviderIds,DateCreated" else "ProviderIds")
                        put("enableImages", false)
                        put("enableUserData", false)
                        // The total plans the read. Jellyfin counts by default; said out loud because
                        // an Emby main server isn't asked to count unless a request says so (EmbyBridge)
                        if (start == 0) put("enableTotalRecordCount", true)
                        if (newest) {
                            put("sortBy", "DateCreated")
                            put("sortOrder", "Descending")
                        }
                    },
            ).content

    /**
     * [page] for a full read, asked up to three times: one failed request used to throw away the
     * whole read (minutes on a big library). A page that comes back empty though the library
     * goes on past it counts as failed: taken, its 1,000 titles would look gone from the
     * library, and rows matched against it would lose them until the next full read.
     */
    private suspend fun steadyPage(
        hook: SourceHook,
        userId: UUID,
        kind: String,
        start: Int,
        total: Int,
        limit: Int,
    ): IndexPage {
        var tries = 0
        while (true) {
            try {
                val p = page(hook, userId, kind, start, limit = limit)
                // (Empty with a total past it, or with none at all: a library doesn't shrink to nothing mid-read)
                if (p.items.isEmpty() && (p.total == 0 || p.total > start)) error("Library index: an empty page at $start of $total")
                return p
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException || ++tries >= 3) throw e
                Timber.i("Library index: page at %d failed (%s), trying again", start, e.message)
                kotlinx.coroutines.delay(3_000L * tries)
            }
        }
    }

    private suspend fun readAll(
        hook: SourceHook,
        owner: String,
        first: Boolean,
    ): Snapshot {
        val started = System.currentTimeMillis()
        val userId = userId(hook)
        embyPage = EMBY_PAGE
        // How much there is, for the notice: the first page of each kind says
        val firstPages = KINDS.associateWith { page(hook, userId, it, 0) }
        val total = firstPages.values.sumOf { it.total }
        // A read the app was closed in the middle of carries on where it was (a big library takes
        // minutes, and Android closes apps in the background: it used to start over every time)
        val journal = Journal(File(file.parentFile, file.name + ".partial"), owner)
        var done = 0
        _progress.value = Progress(0, total, first)
        val parts =
            KINDS.associateWith { kind ->
                val b = Builder()
                val head = firstPages.getValue(kind)
                val resumed = journal.resume(kind, b)
                var start =
                    if (resumed != null && resumed in 1..head.total) {
                        Timber.i("Library index: %s carries on from %d of %d", kind, resumed, head.total)
                        resumed
                    } else {
                        head.titles().forEach(b::add)
                        journal.add(kind, head.titles(), head.items.size)
                        // From where the first page ended (on Emby the page size changes as it goes, see [paced])
                        head.items.size
                    }
                done += start
                _progress.value = Progress(done.coerceAtMost(total), total, first)
                while (start < head.total) {
                    // Never while the remote is in use, something plays or Orca+ is in the
                    // background: browsing and the video get the CPU and the network
                    Conductor.whenIdle()
                    val size = pageSize(hook)
                    val starts = (0 until atOnce(hook)).map { start + it * size }.filter { it < head.total }
                    val asked = System.currentTimeMillis()
                    val pages = coroutineScope { starts.map { s -> async { steadyPage(hook, userId, kind, s, head.total, size) } }.awaitAll() }
                    paced(hook, System.currentTimeMillis() - asked)
                    val read = ArrayList<Title>()
                    pages.forEach { p ->
                        p.titles().forEach { t ->
                            b.add(t)
                            read += t
                        }
                        done += p.items.size
                    }
                    _progress.value = Progress(done.coerceAtMost(total), total, first)
                    start += starts.size * size
                    journal.add(kind, read, start)
                }
                b.build()
            }
        journal.finish()
        val now = System.currentTimeMillis()
        val s = Snapshot(owner, now, now, parts.getValue(MOVIE), parts.getValue(SERIES))
        Timber.i("Library index: %d movies, %d shows in %d ms (full)", s.movies.size, s.series.size, now - started)
        Inbox.indexed(s.movies.size, s.series.size, now - started, full = true)
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
                    Conductor.whenIdle()
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
        Inbox.indexed(s.movies.size, s.series.size, System.currentTimeMillis() - started, full = false, newMovies = added.getValue(MOVIE).size, newShows = added.getValue(SERIES).size)
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

    /**
     * A full read so far, on the device: each kind's titles appended as pages come in, and where the
     * read got to. Kept for [owner] and two days at most; deleted when the read is done.
     */
    private class Journal(
        private val f: File,
        private val owner: String,
    ) {
        // kind -> (titles written, where the read got to)
        private val at = HashMap<String, Pair<Int, Int>>()
        private var startedAt = System.currentTimeMillis()

        init {
            runCatching {
                if (f.isFile) {
                    DataInputStream(f.inputStream().buffered()).use { inp ->
                        if (inp.readInt() == JOURNAL_FORMAT && inp.readUTF() == owner) {
                            val began = inp.readLong()
                            if (System.currentTimeMillis() - began < 2 * DAY_MS) startedAt = began else return@runCatching
                        }
                    }
                }
            }
            // Not this owner's, damaged or old: start afresh
            if (!f.isFile || !matches()) reset()
        }

        private fun matches(): Boolean =
            runCatching {
                DataInputStream(f.inputStream().buffered()).use { inp ->
                    inp.readInt() == JOURNAL_FORMAT && inp.readUTF() == owner && System.currentTimeMillis() - inp.readLong() < 2 * DAY_MS
                }
            }.getOrDefault(false)

        private fun reset() {
            f.delete()
            startedAt = System.currentTimeMillis()
            runCatching {
                DataOutputStream(f.outputStream().buffered()).use { out ->
                    out.writeInt(JOURNAL_FORMAT)
                    out.writeUTF(owner)
                    out.writeLong(startedAt)
                }
            }
        }

        /** The titles a closed read had for [kind] into [b], and where it got to; null when there's nothing to carry on. */
        fun resume(
            kind: String,
            b: Builder,
        ): Int? =
            runCatching {
                var reached: Int? = null
                DataInputStream(f.inputStream().buffered()).use { inp ->
                    inp.readInt()
                    inp.readUTF()
                    inp.readLong()
                    // Records: kind, count, the titles, where the read got to after them. A record cut
                    // short (closed mid-write) ends the journal there
                    while (true) {
                        val k = runCatching { inp.readUTF() }.getOrNull() ?: break
                        val n = inp.readInt()
                        if (n !in 0..MAX_TITLES) break
                        val titles = ArrayList<Title>(n)
                        for (i in 0 until n) {
                            val hi = inp.readLong()
                            val lo = inp.readLong()
                            val t = inp.readInt()
                            val m = inp.readInt()
                            titles += Title(UUID(hi, lo), t.takeIf { it != NONE }, m.takeIf { it != NONE })
                        }
                        val to = inp.readInt()
                        if (k == kind) {
                            titles.forEach(b::add)
                            reached = to
                        }
                    }
                }
                reached
            }.getOrNull()

        fun add(
            kind: String,
            titles: List<Title>,
            reached: Int,
        ) {
            runCatching {
                DataOutputStream(java.io.FileOutputStream(f, true).buffered()).use { out ->
                    out.writeUTF(kind)
                    out.writeInt(titles.size)
                    titles.forEach { t ->
                        out.writeLong(t.id.mostSignificantBits)
                        out.writeLong(t.id.leastSignificantBits)
                        out.writeInt(t.tmdb ?: NONE)
                        out.writeInt(t.imdb ?: NONE)
                    }
                    out.writeInt(reached)
                }
            }
        }

        fun finish() {
            f.delete()
        }

        private companion object {
            const val JOURNAL_FORMAT = 1
            const val NONE = Int.MIN_VALUE
        }
    }

    private fun write(s: Snapshot) {
        val tmp = File(file.path + ".tmp")
        val raw = tmp.outputStream()
        DataOutputStream(raw.buffered()).use { out ->
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
            // On the disk before it replaces the old file (a power cut mid-write)
            out.flush()
            raw.fd.sync()
        }
        // Another account's index (several accounts on one TV) is kept aside for when it's back
        runCatching { ownerOf(file)?.takeIf { it != s.owner }?.let { other -> file.renameTo(keptFile(other)) } }
        if (!tmp.renameTo(file)) Timber.w("Library index: couldn't replace %s", file.name)
        keptFile(s.owner).delete()
        file.parentFile?.listFiles { f -> f.name.startsWith(file.name + ".kept.") }?.sortedByDescending { it.lastModified() }?.drop(KEPT_MAX)?.forEach { it.delete() }
    }

    /** Where another account's index waits while this TV is signed in as someone else. */
    private fun keptFile(owner: String) =
        File(file.parentFile, file.name + ".kept." + java.security.MessageDigest.getInstance("SHA-256").digest(owner.toByteArray()).take(8).joinToString("") { "%02x".format(it) })

    /** Whose index [f] is, read from its head only. */
    private fun ownerOf(f: File): String? {
        if (!f.isFile) return null
        DataInputStream(f.inputStream().buffered()).use { inp -> return if (inp.readInt() == FORMAT) inp.readUTF() else null }
    }

    /**
     * The saved index for [owner]: this one, or the one kept aside when the TV switched to another
     * account (it swaps back in, so switching accounts doesn't read a whole library again).
     */
    private fun load(owner: String): Snapshot? {
        if (ownerOf(file) == owner) return read()
        val kept = keptFile(owner).takeIf { it.isFile } ?: return null
        ownerOf(file)?.let { other -> file.renameTo(keptFile(other)) }
        if (!kept.renameTo(file)) return null
        Timber.i("Library index: this account's index is back")
        return read()?.takeIf { it.owner == owner }
    }

    private fun read(): Snapshot? {
        if (!file.exists()) return null
        DataInputStream(file.inputStream().buffered()).use { inp ->
            if (inp.readInt() != FORMAT) return null
            val owner = inp.readUTF()
            val builtAt = inp.readLong()
            val fullAt = inp.readLong()

            // A damaged length must not ask for gigabytes (the file is rebuilt instead)
            fun count() = inp.readInt().also { if (it !in 0..MAX_TITLES) throw java.io.IOException("Library index damaged") }

            fun ints() = IntArray(count()) { inp.readInt() }

            fun part(): Part {
                val (t, ta, i, ia) = List(4) { ints() }
                val n = count()
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

        // An Emby main server (EmbyBridge): a plain Emby box answered 1,000-title pages four at a
        // time slowly enough to stop answering anything else (bench, 2026-10-09); gentler there
        private const val EMBY_PAGE = 200
        private const val EMBY_AT_ONCE = 1

        private fun emby(hook: SourceHook) = hook.jellyfin.baseUrl?.let(EmbyBridge::isEmby) == true

        // Emby takes about as long for 1,000 titles as for 200 (a 131k-title bench server: 2-7 s either
        // way), so its pages grow while they come back quickly and shrink when it slows (a slow bench server)
        @Volatile private var embyPage = EMBY_PAGE
        private const val GROW_MS = 8_000L
        private const val SHRINK_MS = 20_000L

        private fun pageSize(hook: SourceHook) = if (emby(hook)) embyPage else PAGE

        private fun atOnce(hook: SourceHook) = if (emby(hook)) EMBY_AT_ONCE else AT_ONCE

        private fun paced(hook: SourceHook, ms: Long) {
            if (!emby(hook)) return
            embyPage =
                when {
                    ms < GROW_MS -> (embyPage * 2).coerceAtMost(PAGE)
                    ms > SHRINK_MS -> (embyPage / 2).coerceAtLeast(EMBY_PAGE)
                    else -> embyPage
                }
        }
        private const val NEW_LIMIT = 5000
        private const val FORMAT = 2

        /** Other accounts' indexes kept on the device (a big library's is a few MB). */
        private const val KEPT_MAX = 3
        private const val MAX_TITLES = 5_000_000
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
