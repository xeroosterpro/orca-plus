package com.wholphinplus.sources.sync

import com.wholphinplus.sources.HomeCollection
import com.wholphinplus.sources.ProgressOverlay
import com.wholphinplus.sources.cinema.HomeLayout
import com.wholphinplus.sources.cinema.PosterOverlays
import com.wholphinplus.sources.cinema.RatingPrefs
import com.wholphinplus.sources.cinema.RowsPage
import com.wholphinplus.sources.core.ServerConnection
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Orca+'s own settings, keys and servers (tokens in the clear: the whole profile is encrypted). */
@Serializable
data class OrcaSettings(
    val connections: List<ServerConnection> = emptyList(),
    val tmdbKey: String = "",
    val mdblistKey: String = "",
    val cinemaMode: Boolean = false,
    val rollUp: Boolean = true,
    val overlays: PosterOverlays = PosterOverlays(),
    val ratings: RatingPrefs = RatingPrefs(),
    // Pages and rows a newer Orca+ added are left out, so its profile still loads here
    @Serializable(with = com.wholphinplus.sources.cinema.LenientLayouts::class)
    val layouts: Map<RowsPage, HomeLayout> = emptyMap(),
    /** Rows set to always shuffle ("PAGE|row key"). */
    val shuffleLocks: Set<String> = emptySet(),
    val kidsTab: Boolean = false,
)

/** Trakt/MDBList lists, Top Streaming charts and their accounts. */
@Serializable
data class ListsState(
    val lists: List<HomeCollection> = emptyList(),
    val traktClientId: String = "",
    val topStreaming: String = "",
)

/** Everything a profile carries from TV to TV. */
@Serializable
data class Profile(
    val format: Int = 1,
    val savedAt: Long = 0,
    /** Wholphin's own settings (its preferences file, base64). */
    val wholphin: String? = null,
    val settings: OrcaSettings? = null,
    val lists: ListsState? = null,
    /** Watch progress by main-server item id (the server itself ignores progress). */
    val progress: Map<String, ProgressOverlay.Entry> = emptyMap(),
)

/**
 * How two copies of a profile become one. Watch progress merges title by title (the newer
 * wins). Every other section is taken whole: this TV's copy if it changed here since the last
 * sync (a [joining] TV, one that never synced, has changed nothing), else the cloud's.
 */
object ProfileMerge {
    val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    const val WHOLPHIN = "wholphin"
    const val SETTINGS = "settings"
    const val LISTS = "lists"

    fun hashes(p: Profile): Map<String, String> =
        mapOf(
            WHOLPHIN to hash(p.wholphin.orEmpty()),
            SETTINGS to hash(p.settings?.let { json.encodeToString(it.sorted()) }.orEmpty()),
            LISTS to hash(p.lists?.let { json.encodeToString(it) }.orEmpty()),
        )

    fun merge(
        local: Profile,
        remote: Profile?,
        synced: Map<String, String>,
        joining: Boolean,
        now: Long,
    ): Profile {
        if (remote == null) return local.copy(savedAt = now)
        val mine = hashes(local)

        fun changedHere(section: String) = !joining && mine[section] != synced[section]

        fun <T> pick(
            section: String,
            l: T?,
            r: T?,
        ): T? = if (r == null || (l != null && changedHere(section))) l else r
        return Profile(
            savedAt = now,
            wholphin = pick(WHOLPHIN, local.wholphin, remote.wholphin),
            settings = pick(SETTINGS, local.settings, remote.settings),
            lists = pick(LISTS, local.lists, remote.lists),
            progress = mergeProgress(local.progress, remote.progress),
        )
    }

    fun mergeProgress(
        a: Map<String, ProgressOverlay.Entry>,
        b: Map<String, ProgressOverlay.Entry>,
    ): Map<String, ProgressOverlay.Entry> =
        (a.keys + b.keys).associateWith { k ->
            val x = a[k]
            val y = b[k]
            if (x == null) y!! else if (y == null || x.lastPlayed >= y.lastPlayed) x else y
        }.toSortedMap()

    /** Settings with the page layouts in a fixed order, so equal settings hash the same. */
    private fun OrcaSettings.sorted() = copy(layouts = layouts.toSortedMap(compareBy { it.ordinal }), shuffleLocks = shuffleLocks.toSortedSet())

    private fun hash(s: String) = ProfileCrypto.hex(ProfileCrypto.sha256(s.toByteArray()))
}
