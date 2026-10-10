package com.wholphinplus.sources

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import timber.log.Timber
import java.io.File

/**
 * The Message Center (owner, 2026-10-10): everything Orca+ has to tell, in one place on the device.
 * Updates from GitHub, where a play stopped, the library index, cloud sync, servers that went
 * away or need a new sign-in, account changes. Each kind can also pop up a banner, switched per
 * kind in the Message Center.
 *
 * A message with a [Message.key] replaces the one before it with that key (a status, like "last
 * synced", stays one line instead of filling the list). Kept on the device, newest first, at
 * most [MAX]. Nothing here sends a request.
 */
object Inbox {
    @Serializable
    enum class Kind(
        val label: String,
        /** Pops up unless switched off. */
        val popUpByDefault: Boolean,
    ) {
        UPDATE("Updates", true),
        WATCHING("Watching", false),
        LIBRARY("Library", true),
        SYNC("Sync", true),
        SERVERS("Servers", true),
        ACCOUNT("Account", false),
    }

    @Serializable
    enum class Level { INFO, GOOD, WARN }

    /** What a message's button does. */
    @Serializable
    sealed interface Action {
        val label: String

        /** Opens Wholphin's install page for the update. */
        @Serializable
        data class Update(
            override val label: String = "Update now",
        ) : Action

        /** Opens a title's page (a main-server item; [series]: a show). */
        @Serializable
        data class OpenTitle(
            val id: String,
            val series: Boolean,
            override val label: String = "Open",
        ) : Action

        /** Opens Orca+'s settings on a section ("SOURCES", "ACCOUNT"). */
        @Serializable
        data class Settings(
            val section: String,
            override val label: String,
        ) : Action

        /** Syncs with the cloud now. */
        @Serializable
        data class SyncNow(
            override val label: String = "Sync now",
        ) : Action
    }

    @Serializable
    data class Message(
        val id: Long,
        val kind: Kind,
        val title: String,
        val body: String = "",
        val at: Long,
        val level: Level = Level.INFO,
        val read: Boolean = false,
        val key: String? = null,
        val action: Action? = null,
    )

    private const val MAX = 150
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = false; classDiscriminator = "type" }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    @Volatile private var file: File? = null
    @Volatile private var prefs: android.content.SharedPreferences? = null
    private var saving: Job? = null

    private val _messages = MutableStateFlow<List<Message>>(emptyList())
    val messages: StateFlow<List<Message>> = _messages.asStateFlow()

    private val _popUps = MutableStateFlow<Set<Kind>>(Kind.entries.filter { it.popUpByDefault }.toSet())

    /** The kinds that pop up a banner. */
    val popUps: StateFlow<Set<Kind>> = _popUps.asStateFlow()

    private val _welcome = MutableStateFlow(true)

    /** The greeting when Home first shows after the app starts (owner, 2026-10-10). */
    val welcome: StateFlow<Boolean> = _welcome.asStateFlow()

    fun setWelcome(on: Boolean) {
        _welcome.value = on
        prefs?.edit()?.putBoolean(WELCOME, on)?.apply()
    }

    /** Greeted already in this run of the app (once per start, not per visit to Home). */
    @Volatile var greeted = false

    private val _banners = MutableSharedFlow<Message>(extraBufferCapacity = 8)

    /** Messages to pop up now (the Home screen shows them; nothing while something plays). */
    val banners: SharedFlow<Message> = _banners.asSharedFlow()

    /** Screens showing [banners] now; with none, a pop-up waits for one ([takeWaiting]). */
    @Volatile var bannerHosts = 0

    /** Set by the app: Wholphin's install page for an update (public builds only). */
    @Volatile var openUpdate: (() -> Unit)? = null

    /** Settings' topic to open next ([Action.Settings]); taken once by the settings screen. */
    @Volatile var settingsSection: String? = null

    fun takeSettingsSection(): String? = settingsSection.also { settingsSection = null }

    /** Set by the app: checks GitHub for an update now ([update] says what it found). */
    @Volatile var checkUpdate: (suspend () -> Unit)? = null

    fun init(context: Context) {
        if (file != null) return
        val p = context.getSharedPreferences("wholphinplus_inbox", Context.MODE_PRIVATE)
        prefs = p
        _welcome.value = p.getBoolean(WELCOME, true)
        p.getStringSet(POP_UPS, null)?.let { saved -> _popUps.value = Kind.entries.filter { it.name in saved }.toSet() }
        val f = File(context.noBackupFilesDir, "inbox.json")
        file = f
        _messages.value = runCatching { json.decodeFromString(ListSerializer(Message.serializer()), f.readText()) }.getOrDefault(emptyList())
    }

    fun setPopUp(
        kind: Kind,
        on: Boolean,
    ) {
        _popUps.update { if (on) it + kind else it - kind }
        prefs?.edit()?.putStringSet(POP_UPS, _popUps.value.map { it.name }.toSet())?.apply()
    }

    /**
     * Adds a message (or replaces the one with its [key]). [popUp]: may pop up (when its kind
     * does); [unread]: counts on the bell (a quiet status line doesn't).
     */
    fun post(
        kind: Kind,
        title: String,
        body: String = "",
        level: Level = Level.INFO,
        key: String? = null,
        action: Action? = null,
        popUp: Boolean = true,
        unread: Boolean = true,
    ) {
        val now = System.currentTimeMillis()
        // The same news again (a sync failing each try, a server still offline): no new pop-up or unread mark
        val same = key?.let { k -> _messages.value.firstOrNull { it.key == k } }?.takeIf { it.title == title && it.body == body && it.level == level }
        @Suppress("NAME_SHADOWING") val unread = unread && same == null
        @Suppress("NAME_SHADOWING") val popUp = popUp && same == null
        val m = Message(now * 1000 + (now % 1000), kind, title, body, now, level, read = !unread, key = key, action = action)
        _messages.update { list ->
            // A replaced status keeps its unread mark when it was still unread
            val before = key?.let { k -> list.firstOrNull { it.key == k } }
            val keepUnread = before != null && !before.read && !unread
            listOf(if (keepUnread) m.copy(read = false) else m) + list.filter { key == null || it.key != key }.take(MAX - 1)
        }
        Timber.i("Inbox: %s: %s", kind, title)
        save()
        if (popUp && kind in _popUps.value) popUp(m)
    }

    private fun popUp(m: Message) {
        if (com.wholphinplus.sources.cinema.Conductor.isPlaying || bannerHosts == 0) {
            // Shown when Home is back on screen (after the player, Settings, a title page)
            synchronized(waiting) {
                waiting.removeAll { it.key != null && it.key == m.key }
                waiting += m
                while (waiting.size > 3) waiting.removeAt(0)
            }
            return
        }
        _banners.tryEmit(m)
    }

    private val waiting = ArrayList<Message>()

    /** Pop-ups that came while Home wasn't showing, from the last minute (Home asks when it shows). */
    fun takeWaiting(): List<Message> =
        synchronized(waiting) {
            val now = System.currentTimeMillis()
            waiting.filter { now - it.at < 60_000L }.also { waiting.clear() }
        }

    fun markAllRead() {
        if (_messages.value.none { !it.read }) return
        _messages.update { list -> list.map { if (it.read) it else it.copy(read = true) } }
        save()
    }

    fun remove(id: Long) {
        _messages.update { list -> list.filter { it.id != id } }
        save()
    }

    fun clear() {
        _messages.value = emptyList()
        save()
    }

    /** Written a moment later, once (several messages often come together). */
    private fun save() {
        val f = file ?: return
        saving?.cancel()
        saving =
            scope.launch {
                delay(500)
                runCatching {
                    val tmp = File(f.path + ".tmp")
                    tmp.writeText(json.encodeToString(ListSerializer(Message.serializer()), _messages.value))
                    tmp.renameTo(f)
                }.onFailure { Timber.w(it, "Inbox: couldn't save") }
            }
    }

    // ------------------------------------------------------------ what the app reports

    /** GitHub has a newer release (once per version; the toast it replaces came every 12 hours). */
    fun update(
        installed: String,
        latest: String,
    ) {
        val key = "update:$latest"
        if (_messages.value.any { it.key == key }) return
        // An older offer is out of date
        _messages.update { list -> list.filter { it.key?.startsWith("update:") != true } }
        post(Kind.UPDATE, "Update available: Orca+ $latest", "You have $installed. It downloads from GitHub and installs in a minute.", Level.GOOD, key, Action.Update())
    }

    /** This start is the first after an update: an offer for it (or older) is done with. */
    fun updated(version: String) {
        _messages.update { list -> list.filter { it.key?.startsWith("update:") != true } }
        post(Kind.UPDATE, "Updated to Orca+ $version", "You're on the newest version.", Level.GOOD, key = "updated", popUp = false)
    }

    /** A check found nothing newer (asked from the Message Center). */
    fun upToDate(installed: String) {
        post(Kind.UPDATE, "Orca+ is up to date", "You have the newest version, $installed.", Level.GOOD, key = "update:check", popUp = false, unread = false)
    }

    /** Where a play stopped. [episode]: "S4:E6 · Name" for an episode. */
    fun played(
        itemId: String,
        series: String?,
        title: String,
        episode: String?,
        positionMs: Long,
        durationMs: Long,
        from: String?,
    ) {
        if (positionMs < 60_000L) return
        val finished = durationMs > 0 && positionMs >= durationMs * 9 / 10
        val name = if (episode != null) "$title · $episode" else title
        val where = if (durationMs > 0) "${clock(positionMs)} of ${clock(durationMs)} (${(positionMs * 100 / durationMs).toInt()}%)" else clock(positionMs)
        post(
            Kind.WATCHING,
            name,
            listOfNotNull(if (finished) "Finished" else "Stopped at $where", from?.let { "from $it" }).joinToString(" · "),
            key = "play:${series ?: itemId}",
            action = Action.OpenTitle(series ?: itemId, series != null, if (finished) "Open" else "Resume"),
        )
    }

    /** The library index finished reading (a full read, or only what's new). */
    fun indexed(
        movies: Int,
        shows: Int,
        ms: Long,
        full: Boolean,
        newMovies: Int = 0,
        newShows: Int = 0,
    ) {
        val total = "%,d movies and %,d shows".format(movies, shows)
        if (full) {
            post(Kind.LIBRARY, "Library ready", "Matched $total in ${duration(ms)}. Every row can show all you have.", Level.GOOD, key = "index")
        } else {
            val added = newMovies + newShows
            post(
                Kind.LIBRARY,
                if (added > 0) "Library updated: $added new" else "Library checked",
                (if (added > 0) "%,d new movies, %,d new shows. ".format(newMovies, newShows) else "Nothing new. ") + "Now $total.",
                key = "index",
                popUp = false,
                unread = false,
            )
        }
    }

    private fun clock(ms: Long): String {
        val s = ms / 1000
        return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, s / 60 % 60, s % 60) else "%d:%02d".format(s / 60, s % 60)
    }

    private fun duration(ms: Long): String {
        val s = ms / 1000
        return if (s >= 60) "${s / 60} min ${s % 60} s" else "$s s"
    }

    private const val POP_UPS = "pop_ups"
    private const val WELCOME = "welcome"
}
