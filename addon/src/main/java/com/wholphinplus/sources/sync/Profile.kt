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
import kotlinx.serialization.json.JsonObject

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
    // Fields from here on came later: list each in ProfileMerge.SETTINGS_ADDED with its schema
    val picker: com.wholphinplus.sources.core.PickerPrefs = com.wholphinplus.sources.core.PickerPrefs(),
    val playInfo: com.wholphinplus.sources.PlayInfoPrefs = com.wholphinplus.sources.PlayInfoPrefs(),
    val descriptionTags: com.wholphinplus.sources.cinema.DescriptionTags = com.wholphinplus.sources.cinema.DescriptionTags(),
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
    /** Removed from Continue Watching: item id or "s:" + show id → when (merged key by key, the later wins). */
    val dismissed: Map<String, Long> = emptyMap(),
    /**
     * The main server's sign-in, so a new TV signs in with the Orca+ name and PIN alone (owner,
     * 2026-10-08). Inside the encrypted profile like the rest; the new TV trades it for a token of
     * its own by Quick Connect where the server allows.
     */
    val login: MainLogin? = null,
)

@Serializable
data class MainLogin(
    val url: String,
    val serverId: String,
    val userId: String,
    /** Blank: the new TV asks the server (the hook doesn't know it). */
    val userName: String = "",
    val token: String,
    /** When this sign-in was written: a newer one from another TV replaces it after [STALE_MS]. */
    val at: Long = 0,
) {
    fun sameAccount(o: MainLogin) = url == o.url && serverId.equals(o.serverId, true) && userId.equals(o.userId, true)

    companion object {
        const val STALE_MS = 7L * 24 * 60 * 60 * 1000
    }
}

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
    private val SECTIONS = listOf(WHOLPHIN, SETTINGS, LISTS)

    /**
     * Which settings fields a build knows, saved with the hashes ([SCHEMA_KEY]). A build before
     * schema 2 saved no number: it's 1. Raise [SCHEMA] and list the field in [SETTINGS_ADDED]
     * whenever [OrcaSettings] gets one; the first sync after an update then compares like with
     * like (otherwise the new field alone made this TV's settings look edited here, and they
     * overwrote another TV's newer change in the cloud).
     */
    const val SCHEMA = 3
    const val SCHEMA_KEY = "schema"
    val SETTINGS_ADDED = mapOf("picker" to 2, "playInfo" to 2, "descriptionTags" to 3)

    /** The section hashes, and the schema they were taken with. */
    fun hashes(
        p: Profile,
        schema: Int = SCHEMA,
    ): Map<String, String> =
        mapOf(
            WHOLPHIN to hash(p.wholphin.orEmpty()),
            SETTINGS to hash(p.settings?.let { settingsJson(it, schema) }.orEmpty()),
            LISTS to hash(p.lists?.let { json.encodeToString(it) }.orEmpty()),
            SCHEMA_KEY to schema.toString(),
        )

    /** [s] as JSON, as a build of [schema] wrote it (later fields left out). */
    internal fun settingsJson(
        s: OrcaSettings,
        schema: Int,
    ): String {
        // Exactly as every build hashed it before the schema number (the saved hashes must match)
        if (schema >= SCHEMA) return json.encodeToString(OrcaSettings.serializer(), s.sorted())
        val full = json.encodeToJsonElement(OrcaSettings.serializer(), s.sorted()) as JsonObject
        return JsonObject(full.filterKeys { (SETTINGS_ADDED[it] ?: 0) <= schema }).toString()
    }

    /**
     * [remote] with the settings fields its writer didn't know taken from [local]. An older Orca+
     * reads the profile without the fields it doesn't know and writes it back without them;
     * taking its settings whole put this TV's picker tuning and now-playing choices back to the
     * defaults. [remoteRaw]: the cloud copy's JSON, to tell a missing field from a default one.
     * (Older builds still drop these fields when they write; this keeps them on newer TVs, and
     * the next sync from one puts them back in the cloud.)
     */
    fun keepFieldsUnknownToWriter(
        remote: Profile,
        remoteRaw: String,
        local: Profile,
    ): Profile {
        val rs = remote.settings ?: return remote
        val ls = local.settings ?: return remote
        val written =
            runCatching { (json.parseToJsonElement(remoteRaw) as JsonObject)[SETTINGS] as? JsonObject }.getOrNull()?.keys ?: return remote
        val mine = json.encodeToJsonElement(OrcaSettings.serializer(), ls) as JsonObject
        val missing = mine.keys - written
        if (missing.isEmpty()) return remote
        val theirs = json.encodeToJsonElement(OrcaSettings.serializer(), rs) as JsonObject
        val patched = JsonObject(theirs + missing.associateWith { mine.getValue(it) })
        return runCatching { remote.copy(settings = json.decodeFromJsonElement(OrcaSettings.serializer(), patched)) }.getOrDefault(remote)
    }

    fun merge(
        local: Profile,
        remote: Profile?,
        synced: Map<String, String>,
        joining: Boolean,
        now: Long,
    ): Profile = merge(local, remote, changedSections(local, synced, joining), now)

    /**
     * The sections this TV changed since its last sync ([synced]: their hashes then). A [joining]
     * TV, or one that never synced, changed nothing. Worked out once per sync, from this TV's
     * state before the sync touched it: a retry after a conflict compares against what this TV
     * had, not against the copy the first round applied (that would look like an edit here and
     * overwrite the other TV's newer change).
     */
    fun changedSections(
        local: Profile,
        synced: Map<String, String>,
        joining: Boolean,
    ): Set<String> {
        if (joining) return emptySet()
        // Hashed the way the last sync's build did (an update adds fields, not edits). Builds
        // before the schema number saved none: schema 1, or 2 for the ones that already had its
        // fields (2026-10-08), so either one matching means unchanged
        val then = synced[SCHEMA_KEY]?.toIntOrNull()?.let { listOf(it.coerceAtMost(SCHEMA)) } ?: (1..SCHEMA).toList()
        val mine = then.map { hashes(local, it) }
        return SECTIONS.filterTo(HashSet()) { section -> mine.none { it[section] == synced[section] } }
    }

    /** [local] and [remote] as one: each section from this TV if it's in [changed], else the cloud's. */
    fun merge(
        local: Profile,
        remote: Profile?,
        changed: Set<String>,
        now: Long,
    ): Profile {
        if (remote == null) return local.copy(savedAt = now, login = local.login?.copy(at = now))

        fun <T> pick(
            section: String,
            l: T?,
            r: T?,
        ): T? = if (r == null || (l != null && section in changed)) l else r
        return Profile(
            savedAt = now,
            wholphin = pick(WHOLPHIN, local.wholphin, remote.wholphin),
            settings = pick(SETTINGS, local.settings, remote.settings),
            lists = pick(LISTS, local.lists, remote.lists),
            progress = mergeProgress(local.progress, remote.progress),
            dismissed = ProgressOverlay.mergeDismissed(local.dismissed, remote.dismissed),
            login = mergeLogin(local.login, remote.login, now),
        )
    }

    /**
     * The cloud's sign-in stays while it's the same account and recent: every TV has a token of
     * its own, and swapping them on each sync would rewrite the profile every time. A week old,
     * or another server or address, and this TV's (still signed in, so working) takes over.
     */
    fun mergeLogin(
        local: MainLogin?,
        remote: MainLogin?,
        now: Long,
    ): MainLogin? =
        when {
            local == null -> remote
            remote == null || !remote.sameAccount(local) || now - remote.at > MainLogin.STALE_MS -> local.copy(at = now)
            else -> remote
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
