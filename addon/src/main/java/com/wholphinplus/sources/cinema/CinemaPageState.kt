package com.wholphinplus.sources.cinema

import com.wholphinplus.sources.core.cancellable
import androidx.compose.foundation.background
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.layout.layout
import androidx.compose.ui.input.key.key
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.serialization.json.decodeFromStream
import kotlinx.serialization.json.encodeToStream
import org.jellyfin.sdk.model.api.BaseItemKind
import java.util.UUID

/**
 * What a card already knows about a title, handed to its Cinema page so the page draws at once
 * (art, logo, overview, Play) while the rest loads. Episodes aren't handed over: their card
 * opens the series.
 */
internal object DetailsPreview {
    private val map = java.util.concurrent.ConcurrentHashMap<UUID, CinemaItem>()

    fun put(item: CinemaItem) {
        if (item.id == item.detailsId) map[item.id] = item
    }

    operator fun get(id: UUID): CinemaItem? = map[id]
}

/** Titles to suggest before you search: what's on your home, billboard first. */
internal fun homeSuggestions(): List<CinemaItem> {
    val home = TabCache.data[CinemaTab.HOME] ?: return emptyList()
    return (home.featured + home.rows.flatMap { it.items })
        .filter { it.kind != BaseItemKind.EPISODE && (it.backdropUrl != null || it.cardUrl != null) }
        .distinctBy { it.detailsId }
        .take(18)
}

/**
 * A real title from your home for the Poster tags preview: one with a logo and streaming
 * services if there is one (so those tags show), else any title with art; null before Home
 * has loaded.
 */
internal fun previewTitle(art: CinemaArt?): CinemaItem? {
    val all = TabCache.data[CinemaTab.HOME]?.rows?.flatMap { it.items }.orEmpty().filter { it.kind != BaseItemKind.EPISODE && it.tmdbId != null }
    fun known(i: CinemaItem) = art?.cached(i.tmdbTv, i.tmdbId!!)
    return all.firstOrNull { known(it)?.let { a -> a.logo != null && a.services.isNotEmpty() } == true }
        ?: all.firstOrNull { known(it)?.logo != null }
        ?: all.firstOrNull { it.cardUrl != null }
}

/**
 * Loads Home ahead of time (the welcome tour does, so Home is there the moment it ends) and the
 * title art of its first rows, so previews can show a real title.
 */
internal suspend fun preloadHome(
    repo: CinemaRepository,
    art: CinemaArt,
) {
    val gen = repo.listsGeneration
    val plays = repo.plays
    val d = repo.load(RowsPage.HOME)
    TabCache.data[CinemaTab.HOME] = d
    if (d.failed == 0) TabCache.stamp(CinemaTab.HOME, gen, plays)
    kotlinx.coroutines.coroutineScope {
        d.rows.take(3).flatMap { it.items.take(6) }.filter { it.tmdbId != null && it.kind != BaseItemKind.EPISODE }.forEach { i ->
            launch { runCatching { art.art(i.tmdbTv, i.tmdbId!!) }.cancellable() }
        }
    }
}

/**
 * The card (or the billboard: [row] null) a page opened a title page or the player from, so Back
 * lands on it instead of on Play (Home) or on nothing (the other tabs). Taken once, by that page.
 */
internal object ReturnCard {
    class Spot(
        val screen: String,
        val row: String?,
        val key: String?,
    )

    @Volatile private var spot: Spot? = null

    fun mark(
        screen: String,
        row: String?,
        key: String?,
    ) {
        spot = Spot(screen, row, key)
    }

    fun take(screen: String): Spot? = spot?.takeIf { it.screen == screen }?.also { spot = null }
}

/** What Down from a page's top menu lands on (its Play, My List's first title), by page. */
internal object DownTargets {
    private val map = java.util.concurrent.ConcurrentHashMap<String, FocusRequester>()

    fun of(screen: String): FocusRequester? = map[screen]

    @Composable
    fun Register(
        screen: String,
        target: FocusRequester,
    ) {
        DisposableEffect(screen, target) {
            map[screen] = target
            onDispose { map.remove(screen, target) }
        }
    }
}

/** Each tab's last page, kept for the life of the app so returning to it is instant. */
internal object TabCache {
    val data = java.util.concurrent.ConcurrentHashMap<CinemaTab, CinemaHomeData>()
    val at = java.util.concurrent.ConcurrentHashMap<CinemaTab, Long>()

    /** The [HomeCollections.changed] each tab's page was loaded under (when it started). */
    private val under = java.util.concurrent.ConcurrentHashMap<CinemaTab, Int>()

    /** The progress overlay's [ProgressOverlay.changes] each tab's page was loaded under. */
    private val watched = java.util.concurrent.ConcurrentHashMap<CinemaTab, Long>()

    /** [tab]'s page was loaded just now, from the lists as they were at [changed] and the plays as at [plays]. */
    fun stamp(
        tab: CinemaTab,
        changed: Int,
        plays: Long,
    ) {
        at[tab] = System.currentTimeMillis()
        under[tab] = changed
        watched[tab] = plays
    }

    /**
     * [tab]'s page is recent and from the current lists. A load that began before a refresh
     * announced new rows (and so finished after the caches were cleared) isn't fresh: the
     * announcement's reload would otherwise skip it and the new rows wait for the next visit.
     */
    fun fresh(
        tab: CinemaTab,
        changed: Int,
        plays: Long,
    ) = under[tab] == changed && watched[tab] == plays && System.currentTimeMillis() - (at[tab] ?: 0L) < 2 * 60_000
}

/**
 * Each tab's last full page on the device, so a cold start (Android often closes Orca+ while
 * other apps run) shows the page at once instead of after its 2-6 s of requests; the fresh load
 * replaces it when it lands. Kept per server and profile, for a week.
 */
@OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
internal object PageSnapshots {
    /** Tabs never saved: My List is quick, My Servers' extra-server titles mean nothing after a restart ([ServerTitles]). */
    val UNSAVED = setOf(CinemaTab.MY_LIST, CinemaTab.SERVERS)

    @kotlinx.serialization.Serializable
    private class Saved(
        val owner: String,
        val at: Long,
        val page: CinemaHomeData,
    )

    private val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
    private var dir: java.io.File? = null

    fun attach(context: android.content.Context) {
        if (dir == null) dir = java.io.File(context.applicationContext.noBackupFilesDir, "cinema_pages")
    }

    private fun file(tab: CinemaTab) = dir?.let { java.io.File(it, "${tab.name.lowercase()}.json") }

    /** [whose]: [CinemaRepository.owner], asked off the main thread (it may ask the server once). */
    suspend fun read(
        tab: CinemaTab,
        whose: () -> String?,
    ): CinemaHomeData? =
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            val started = System.currentTimeMillis()
            val owner = whose()
            val f = file(tab)?.takeIf { owner != null && it.exists() }
            if (f == null) {
                timber.log.Timber.i("Cinema snapshot %s: none (signed in: %b)", tab.name, owner != null)
                return@withContext null
            }
            val saved = runCatching { f.inputStream().buffered().use { json.decodeFromStream(Saved.serializer(), it) } }.getOrNull()
            if (saved == null) f.delete()
            val page = saved?.takeIf { it.owner == owner && System.currentTimeMillis() - it.at < MAX_AGE_MS }?.page
            timber.log.Timber.i("Cinema snapshot %s: %s in %d ms", tab.name, if (page != null) "shown" else "not used", System.currentTimeMillis() - started)
            page
        }

    /** What each tab's file holds (its rows and titles) and when it was written, to skip rewriting the same page. */
    private val written = java.util.concurrent.ConcurrentHashMap<CinemaTab, Pair<Int, Long>>()

    /**
     * Written once the remote rests (encoding ~1.4 MB on the Shield's cores while a row glides
     * cost frames), streamed to the file, and not at all when the same rows and titles were saved
     * within the day (most reloads change nothing but progress).
     */
    suspend fun save(
        tab: CinemaTab,
        whose: () -> String?,
        page: CinemaHomeData,
    ) {
        if (tab in UNSAVED) return
        Conductor.later {
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                val owner = whose() ?: return@withContext
                val f = file(tab) ?: return@withContext
                val shape = snapshotShape(owner, page)
                val now = System.currentTimeMillis()
                written[tab]?.let { (was, at) -> if (was == shape && now - at < RESAVE_MS && f.exists()) return@withContext }
                runCatching {
                    f.parentFile?.mkdirs()
                    val tmp = java.io.File(f.path + ".tmp")
                    tmp.outputStream().buffered().use { json.encodeToStream(Saved.serializer(), Saved(owner, now, page), it) }
                    tmp.renameTo(f)
                    written[tab] = shape to now
                }.onFailure { timber.log.Timber.w(it, "Cinema snapshot %s not saved", tab.name) }
            }
        }
    }

    /** The rows were rearranged: a saved page no longer matches its layout. */
    fun clear() {
        written.clear()
        dir?.listFiles()?.forEach { it.delete() }
    }

    private const val MAX_AGE_MS = 7 * 24 * 60 * 60_000L

    /** A page with the same rows and titles is written again after this long (its age counts). */
    private const val RESAVE_MS = 24 * 60 * 60_000L
}

/** A page's rows and titles in order (and whose they are), as a number: equal when a save would write the same page. */
internal fun snapshotShape(
    owner: String,
    page: CinemaHomeData,
): Int {
    var h = owner.hashCode()
    page.featured.forEach { h = h * 31 + it.key.hashCode() }
    page.rows.forEach { row ->
        h = h * 31 + row.title.hashCode()
        row.items.forEach { h = h * 31 + it.key.hashCode() }
        row.tiles.forEach { h = h * 31 + it.id.hashCode() }
    }
    return h
}

/** What MemoryTrim may drop: every tab's page but Home (they reload in the background). */
internal object CinemaCaches {
    fun trim() {
        TabCache.data.keys.filter { it != CinemaTab.HOME }.forEach {
            TabCache.data.remove(it)
            TabCache.at.remove(it)
        }
        RowMemory.trim { it == "tab:${CinemaTab.HOME.name}" }
        trimDetails()
        BecauseCache.map.clear()
    }

    /** My Servers' rows changed (its extra servers switched on or off): it loads afresh next time it shows. */
    fun serversChanged() {
        TabCache.data.remove(CinemaTab.SERVERS)
        TabCache.at.remove(CinemaTab.SERVERS)
        RowMemory.trim { it != "tab:${CinemaTab.SERVERS.name}" && !it.startsWith("server:") }
    }

    /** The rows were rearranged: every tab loads afresh next time it shows. */
    fun homeChanged() {
        TabCache.data.clear()
        TabCache.at.clear()
        RowMemory.clear()
        PageSnapshots.clear()
    }

    /**
     * Rows kept their places but gained or lost titles (the 6-hourly match, a new day's charts):
     * every tab loads afresh, but the pages saved on the device stay. They show at once and the
     * fresh load fills in over them. Deleting them (as [homeChanged] did for this too) meant the
     * first open after nearly every refresh built the page row by row, slow rows pushing in
     * between the ones already shown.
     */
    fun rowsRefreshed() {
        TabCache.data.clear()
        TabCache.at.clear()
    }
}
