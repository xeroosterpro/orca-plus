package com.wholphinplus.sources.sync

import android.content.Context
import com.wholphinplus.sources.ConnectionStore
import com.wholphinplus.sources.HomeCollections
import com.wholphinplus.sources.ProgressOverlay
import com.wholphinplus.sources.SourceHook
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/** Wholphin's own settings file, handed over by the app at start-up (a hook in MainActivity). */
interface AppSettings {
    suspend fun read(): ByteArray

    suspend fun write(bytes: ByteArray)
}

/**
 * Cloud sync: this TV's whole Orca+ setup (Wholphin's settings, Orca+'s settings, rows, lists,
 * keys, extra servers and watch progress) kept in one end-to-end encrypted profile per Jellyfin
 * user. It syncs when Home opens (at most every two minutes) and when the app goes to the
 * background. A profile belongs to the Jellyfin user it was made for: signed in as someone else,
 * this TV doesn't sync.
 */
@Singleton
class ProfileSync
    @Inject
    constructor(
        @param:ApplicationContext context: Context,
        private val store: ConnectionStore,
        private val collections: HomeCollections,
        private val progress: ProgressOverlay,
    ) {
        /** What the settings screen shows. */
        data class Status(
            val on: Boolean = false,
            val busy: Boolean = false,
            val lastSync: Long = 0,
            val problem: String? = null,
            /** Someone asked to reset the cloud setup ("forgot PIN"); it's deleted then unless kept (0: no). */
            val resetAt: Long = 0,
            /** The profile's Orca+ name, as the cloud last said (null: none, or not synced yet). */
            val name: String? = null,
        )

        @Volatile var appSettings: AppSettings? = null

        private val prefs = context.getSharedPreferences("wholphinplus_cloud", Context.MODE_PRIVATE)
        private val client =
            CloudClient(
                ENDPOINT,
                object : DeviceTokens {
                    override fun get(id: String): String? = prefs.getString(DEVICE + id, null)?.let { runCatching { store.unseal(it) }.getOrNull() }?.ifEmpty { null }

                    override fun set(
                        id: String,
                        token: String,
                    ) {
                        prefs.edit().putString(DEVICE + id, store.seal(token)).apply()
                    }
                },
            )
        private val mutex = Mutex()
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        private val _status = MutableStateFlow(Status(on = prefs.getString(ID, null) != null, lastSync = prefs.getLong(LAST, 0)))
        val status: StateFlow<Status> = _status.asStateFlow()

        @Volatile private var lastTry = 0L

        /** A sync the cloud asked to wait for (locked, slow down), started once the wait is over. */
        @Volatile private var retry: kotlinx.coroutines.Job? = null

        // ------------------------------------------------------------ turning it on and off

        /** Whether the cloud has a profile for the signed-in user (null: can't tell right now). */
        suspend fun cloudHasProfile(hook: SourceHook): Boolean? = cloudState(hook)?.exists

        /** The cloud's profile for the signed-in user and any pending reset (null: can't tell right now). */
        suspend fun cloudState(hook: SourceHook): CloudState? =
            withContext(Dispatchers.IO) { runCatching { client.state(identity(hook)) }.onFailure { Timber.w(it, "Cloud check failed") }.getOrNull() }

        /**
         * "Forgot PIN": the cloud deletes this account's profile a day from now, unless a TV that
         * still syncs keeps it. After that a new PIN starts a fresh one. When the reset happens.
         */
        suspend fun forgotPin(hook: SourceHook): Long = withContext(Dispatchers.IO) { client.requestReset(identity(hook)) }

        /** Keeps the cloud setup: cancels a pending "forgot PIN" reset, from a TV that syncs. */
        suspend fun keepSetup(hook: SourceHook) =
            withContext(Dispatchers.IO) {
                mutex.withLock {
                    val (id, seed) = keys(hook) ?: error("Cloud sync isn't on")
                    client.keep(id, ProfileCrypto.auth(seed))
                    _status.update { it.copy(resetAt = 0) }
                }
            }

        /**
         * Turns sync on with [pin]. With a profile already in the cloud this TV joins it and takes
         * everything from it; otherwise this TV's setup becomes the profile. Throws
         * [CloudException] ("wrong_pin", "locked") or a plain error (offline, not signed in).
         */
        suspend fun turnOn(
            hook: SourceHook,
            pin: String,
        ) = withContext(Dispatchers.IO) {
            mutex.withLock {
                busy(true)
                var stored = false
                var kept = false
                try {
                    val id = identity(hook)
                    val seed = ProfileCrypto.seed(pin, id)
                    val auth = ProfileCrypto.auth(seed)
                    val joining = client.exists(id)
                    if (joining) client.open(id, auth) else client.create(id, auth)
                    // Joining: until the first sync has brought the cloud's setup here, every sync
                    // is a joining one (the app may be closed or killed half way)
                    remember(id, seed, version = 0, hashes = emptyMap())
                    stored = true
                    prefs.edit().putBoolean(JOINING, joining).commit()
                    syncLocked(hook, joining = joining)
                    markPrompted()
                    kept = true
                } finally {
                    // Cancelled (Back on the PIN pad, the welcome closed) or failed half way: this TV
                    // doesn't sync. Left on, its next sync would have counted every setting here as
                    // a change and put a fresh TV's setup over the cloud's
                    if (stored && !kept) turnOff()
                    busy(false)
                }
            }
        }

        /** Stops syncing on this TV; the cloud keeps the profile. */
        fun turnOff() {
            retry?.cancel()
            prefs.edit().remove(ID).remove(SEED).remove(VERSION).remove(HASHES).remove(LAST).remove(JOINING).apply()
            _status.value = Status()
        }

        /** The welcome's first choice: "I have an Orca+ account" (true) or "I'm new" (false). */
        var welcomeReturning: Boolean
            get() = prefs.getBoolean(RETURNING, false)
            set(v) = prefs.edit().putBoolean(RETURNING, v).apply()

        suspend fun pairStart(): PairStart = withContext(Dispatchers.IO) { client.pairStart() }

        suspend fun pairCollect(p: PairStart): Map<String, String>? = withContext(Dispatchers.IO) { client.pairCollect(p) }

        suspend fun setupStart(
            pub: String,
            mode: String? = null,
        ): SetupStart = withContext(Dispatchers.IO) { client.setupStart(pub, mode) }

        suspend fun setupCollect(s: SetupStart): SetupBox? = withContext(Dispatchers.IO) { client.setupCollect(s) }

        suspend fun setupResult(
            s: SetupStart,
            ok: Boolean,
            message: String,
            final: Boolean,
        ) = withContext(Dispatchers.IO) { client.setupResult(s, ok, message, final) }

        /** The pending reset this TV was already asked about (its time), so it asks once. */
        var resetSeen: Long
            get() = prefs.getLong(RESET_SEEN, 0)
            set(v) = prefs.edit().putLong(RESET_SEEN, v).apply()

        /** Whether this TV already offered cloud sync (welcome or the one-time prompt). */
        val prompted: Boolean get() = prefs.getBoolean(PROMPTED, false)

        fun markPrompted() = prefs.edit().putBoolean(PROMPTED, true).apply()

        /** Deletes the cloud profile (every TV stops syncing with it). */
        suspend fun deleteCloud(hook: SourceHook) =
            withContext(Dispatchers.IO) {
                mutex.withLock {
                    val (id, seed) = keys(hook) ?: return@withLock
                    client.delete(id, ProfileCrypto.auth(seed))
                    turnOff()
                }
            }

        /** A new PIN for the profile, from a TV that's already syncing. */
        suspend fun changePin(
            hook: SourceHook,
            newPin: String,
        ) = withContext(Dispatchers.IO) {
            mutex.withLock {
                busy(true)
                try {
                    val (id, seed) = keys(hook) ?: error("Cloud sync isn't on")
                    val auth = ProfileCrypto.auth(seed)
                    val copy = client.open(id, auth)
                    val newSeed = ProfileCrypto.seed(newPin, id)
                    val local = merged(hook, copy, seed, ProfileMerge.changedSections(localProfile(hook), savedHashes(), joining = false))
                    val version = client.changePin(id, auth, ProfileCrypto.auth(newSeed), copy.version, ProfileCrypto.seal(encode(local), newSeed, copy.wrap))
                    remember(id, newSeed, version, ProfileMerge.hashes(localProfile(hook)))
                } finally {
                    busy(false)
                }
            }
        }

        // ------------------------------------------------------------ syncing

        /** Syncs now; errors land in [status]. */
        suspend fun syncNow(hook: SourceHook) =
            withContext(Dispatchers.IO) {
                mutex.withLock {
                    if (prefs.getString(ID, null) == null) return@withLock
                    busy(true)
                    try {
                        syncLocked(hook, joining = prefs.getBoolean(JOINING, false))
                    } catch (e: Exception) {
                        if (e is kotlinx.coroutines.CancellationException) throw e
                        Timber.w(e, "Cloud sync failed")
                        // Deleted from another TV: this one stops, and says why
                        if (e is CloudException && e.error == "no_profile") turnOff()
                        // The PIN was changed on another TV: stop at once (each try would count
                        // toward the lock) and ask for the new one next time the app opens
                        if (e is CloudException && e.error == "wrong_pin") {
                            turnOff()
                            prefs.edit().remove(PROMPTED).apply()
                        }
                        // Locked by guesses from elsewhere, or the cloud asks to slow down: this TV
                        // keeps its keys (it has the right PIN) and tries again when told to. It
                        // used to turn sync off, so a stranger could switch off every TV's sync
                        // and keep "Keep my setup" from ever showing
                        if (e is CloudException && (e.error == "locked" || e.error == "slow_down")) retryAfter(hook, e.retryAfterSec)
                        _status.update { it.copy(problem = if (e is CloudException && e.error == "wrong_pin") "The sync PIN was changed on another TV. Enter the new one to keep syncing." else describe(e)) }
                        com.wholphinplus.sources.Inbox.post(com.wholphinplus.sources.Inbox.Kind.SYNC, "Cloud sync didn't work", _status.value.problem.orEmpty(), com.wholphinplus.sources.Inbox.Level.WARN, key = "sync", action = com.wholphinplus.sources.Inbox.Action.SyncNow())
                    } finally {
                        busy(false)
                    }
                }
            }

        private fun retryAfter(
            hook: SourceHook,
            seconds: Int?,
        ) {
            val wait = ((seconds ?: 60).coerceIn(5, 30 * 60) + 1) * 1000L
            retry?.cancel()
            retry =
                scope.launch {
                    kotlinx.coroutines.delay(wait)
                    syncNow(hook)
                }
        }

        /** The sections the last merge brought from the cloud (another TV's changes). */
        @Volatile private var arrived: Set<String> = emptySet()

        /** The Message Center: one "last synced" line; its own message when another TV's changes arrived. */
        private fun synced(arrived: Set<String>) {
            val words = arrived.mapNotNull { SECTION_WORDS[it] }.distinct()
            if (words.isNotEmpty()) {
                com.wholphinplus.sources.Inbox.post(com.wholphinplus.sources.Inbox.Kind.SYNC, "Synced changes from another TV", "Brought over: ${words.joinToString(", ")}.", com.wholphinplus.sources.Inbox.Level.GOOD, key = "sync")
            } else {
                com.wholphinplus.sources.Inbox.post(com.wholphinplus.sources.Inbox.Kind.SYNC, "Synced with your Orca+ account", "Everything on this TV is saved in the cloud.", com.wholphinplus.sources.Inbox.Level.GOOD, key = "sync", popUp = false, unread = false)
            }
        }

        /** Syncs in the background unless it did in the last two minutes ([force]: the app is leaving). */
        fun syncSoon(
            hook: SourceHook,
            force: Boolean = false,
        ) {
            if (prefs.getString(ID, null) == null) return
            val now = System.currentTimeMillis()
            if (!force && now - lastTry < MIN_GAP_MS) return
            lastTry = now
            scope.launch { syncNow(hook) }
        }

        /** Reads the cloud copy, merges it with this TV's, applies the result here and uploads it. */
        private suspend fun syncLocked(
            hook: SourceHook,
            joining: Boolean,
        ) {
            val (id, seed) = keys(hook) ?: throw noKeys(hook)
            val auth = ProfileCrypto.auth(seed)
            // What this TV changed since its last sync, from before this sync touched anything
            // (a retry after a conflict must not count the first round's copy as a change here)
            val changed = ProfileMerge.changedSections(localProfile(hook), savedHashes(), joining)
            repeat(3) {
                val copy = client.open(id, auth)
                // A "forgot PIN" reset asked for elsewhere: this TV shows it and can keep the setup
                _status.update { it.copy(resetAt = copy.resetAt) }
                if (copy.resetAt == 0L && resetSeen != 0L) resetSeen = 0L
                val result = merged(hook, copy, seed, changed)
                val remote = copy.blob?.let { decode(ProfileCrypto.open(it, seed, copy.wrap)) }
                val version =
                    if (remote != null && ProfileMerge.hashes(result) == ProfileMerge.hashes(remote) && result.progress == remote.progress && result.dismissed == remote.dismissed && result.login == remote.login) {
                        copy.version
                    } else {
                        try {
                            client.put(id, auth, copy.version, ProfileCrypto.seal(encode(result), seed, copy.wrap))
                        } catch (e: CloudException) {
                            // Another TV wrote meanwhile: read it and merge again
                            if (e.error == "conflict") return@repeat else throw e
                        }
                    }
                remember(id, seed, version, ProfileMerge.hashes(localProfile(hook)))
                _status.update { it.copy(on = true, lastSync = System.currentTimeMillis(), problem = null, resetAt = copy.resetAt, name = copy.name) }
                prefs.edit().putLong(LAST, System.currentTimeMillis()).remove(JOINING).apply()
                Timber.i("Cloud sync: version %d", version)
                synced(arrived)
                return
            }
            error("Another TV kept changing the profile; try again")
        }

        /** The cloud copy and this TV's merged, and the merge applied here. */
        private suspend fun merged(
            hook: SourceHook,
            copy: CloudCopy,
            seed: ByteArray,
            changed: Set<String>,
        ): Profile {
            val local = localProfile(hook)
            val remote =
                copy.blob?.let { ProfileCrypto.open(it, seed, copy.wrap) }?.let { raw ->
                    // Fields an older Orca+ dropped when it wrote: this TV's stay
                    ProfileMerge.keepFieldsUnknownToWriter(decode(raw), String(raw), local)
                }
            val result = ProfileMerge.merge(local, remote, changed, System.currentTimeMillis())
            apply(hook, local, result)
            val before = ProfileMerge.hashes(local)
            arrived = ProfileMerge.hashes(result).filter { (k, v) -> before[k] != v }.keys
            return result
        }

        /** This TV's setup; watch progress is the signed-in user's own (the profile is theirs). */
        private suspend fun localProfile(hook: SourceHook): Profile {
            val main = hook.mainConnection()
            return Profile(
                wholphin = appSettings?.let { s -> runCatching { kotlin.io.encoding.Base64.Default.encode(s.read()) }.getOrNull() },
                settings = store.snapshot(),
                lists = collections.snapshot(),
                progress = progress.snapshot(main?.userId),
                dismissed = progress.dismissedSnapshot(main?.userId),
                login =
                    main?.let { m ->
                        hook.mainServerId()?.let { sid -> MainLogin(url = m.serverUrl, serverId = sid, userId = m.userId, userName = "", token = m.accessToken) }
                    },
            )
        }

        // ------------------------------------------------------------ several accounts on one TV

        /** Each account's setup on this TV while another is signed in ([accountChanged]). */
        private val accountsDir = java.io.File(context.noBackupFilesDir, "orca_accounts")

        /** One account's setup and sync state, as kept while another account is signed in. */
        @kotlinx.serialization.Serializable
        private class Slot(
            val profile: Profile,
            val sync: Map<String, String> = emptyMap(),
        )

        private val SYNC_KEYS = listOf(ID, SEED, VERSION, HASHES, LAST, JOINING, PROMPTED)

        private fun slotFile(identity: String) = java.io.File(accountsDir, ProfileCrypto.profileId(identity, "slot").take(40))

        /**
         * The signed-in account changed (the account switcher, Wholphin's own Switch user, signing
         * out and in as someone else). The setup on this TV still belongs to the account before:
         * it's kept, with its sync state, in a sealed file of its own; the account now signed in
         * gets its kept setup back and carries on syncing without its PIN. An account this TV
         * hasn't had keeps the setup as it is, with sync off and the cloud's offer coming up, so
         * one account's setup is never synced into another's profile.
         */
        suspend fun accountChanged(hook: SourceHook) =
            withContext(Dispatchers.IO) {
                mutex.withLock {
                    val now = runCatching { identity(hook) }.getOrNull() ?: return@withLock
                    val before = prefs.getString(ACTIVE, null)
                    if (before == now) return@withLock
                    if (before != null) {
                        runCatching {
                            val setup = Profile(wholphin = localProfile(hook).wholphin, settings = store.snapshot(), lists = collections.snapshot())
                            val sync = SYNC_KEYS.mapNotNull { k -> prefs.all[k]?.let { v -> k to (if (v is Boolean) "b:$v" else if (v is Int) "i:$v" else if (v is Long) "l:$v" else "s:$v") } }.toMap()
                            accountsDir.mkdirs()
                            slotFile(before).writeText(store.seal(ProfileMerge.json.encodeToString(Slot.serializer(), Slot(setup, sync))))
                        }.onFailure { Timber.w(it, "Accounts: couldn't keep the previous account's setup") }
                    }
                    val kept = runCatching { slotFile(now).takeIf { it.isFile }?.readText()?.let { ProfileMerge.json.decodeFromString(Slot.serializer(), store.unseal(it)) } }.getOrNull()
                    val edit = prefs.edit()
                    SYNC_KEYS.forEach { edit.remove(it) }
                    kept?.sync?.forEach { (k, v) ->
                        when (v.take(2)) {
                            "b:" -> edit.putBoolean(k, v.drop(2).toBoolean())
                            "i:" -> edit.putInt(k, v.drop(2).toInt())
                            "l:" -> edit.putLong(k, v.drop(2).toLong())
                            else -> edit.putString(k, v.drop(2))
                        }
                    }
                    edit.putString(ACTIVE, now).commit()
                    // Pages and rows on screen were the other account's (the first account recorded: nothing to drop)
                    if (before != null) com.wholphinplus.sources.cinema.CinemaCaches.homeChanged()
                    if (kept != null) {
                        apply(hook, localProfile(hook), kept.profile)
                        slotFile(now).delete()
                    }
                    Timber.i("Accounts: signed-in account changed (%s)", if (kept != null) "its setup back" else if (before == null) "first one" else "new on this TV")
                    if (before != null) {
                        val who = com.wholphinplus.sources.AccountActions.who()
                        com.wholphinplus.sources.Inbox.post(
                            com.wholphinplus.sources.Inbox.Kind.ACCOUNT,
                            if (who != null) "Signed in as ${who.user} on ${who.server}" else "Switched account",
                            if (kept != null) "Its own setup is back on this TV." else "New on this TV: it starts with this TV's setup, and cloud sync is off until you turn it on.",
                            action = com.wholphinplus.sources.Inbox.Action.Settings("ACCOUNT", "Account"),
                        )
                    }
                    _status.value = Status(on = prefs.getString(ID, null) != null, lastSync = prefs.getLong(LAST, 0))
                }
            }

        // ------------------------------------------------------------ Orca+ name sign-in

        /**
         * A new TV with only the Orca+ [name] and [pin]: the main server's sign-in from the cloud
         * profile. The PIN goes on to [pendingPin], so the welcome's restore step uses it at once.
         * Throws [CloudException] ("no_name", "wrong_pin", "locked"), or a plain error when the
         * profile has no sign-in yet (saved by an Orca+ before this one).
         */
        suspend fun signInWithName(
            name: String,
            pin: String,
        ): MainLogin =
            withContext(Dispatchers.IO) {
                val id = client.nameLookup(name)
                val seed = ProfileCrypto.seed(pin, id)
                val copy = client.open(id, ProfileCrypto.auth(seed))
                val profile = copy.blob?.let { decode(ProfileCrypto.open(it, seed, copy.wrap)) }
                val login = profile?.login ?: error("Your setup doesn't have your server sign-in yet. Open Orca+ once on a TV that syncs (it adds it), or sign in with your server instead.")
                pendingPin = pin
                login
            }

        /** The PIN from a name sign-in, taken once by the welcome's restore step. */
        @Volatile private var pendingPin: String? = null

        fun takePendingPin(): String? = pendingPin.also { pendingPin = null }

        /** Whether no account has the Orca+ [name] yet (asked before a new account's PIN). */
        suspend fun nameFree(name: String): Boolean =
            withContext(Dispatchers.IO) {
                try {
                    client.nameLookup(name)
                    false
                } catch (e: CloudException) {
                    if (e.error == "no_name") true else throw e
                }
            }

        /** Gives this account's cloud setup the Orca+ [name] ("" takes it away). */
        suspend fun setName(
            hook: SourceHook,
            name: String,
        ) = withContext(Dispatchers.IO) {
            mutex.withLock {
                val (id, seed) = keys(hook) ?: error("Cloud sync isn't on")
                val now = client.setName(id, ProfileCrypto.auth(seed), name)
                _status.update { it.copy(name = now) }
            }
        }

        private suspend fun apply(
            hook: SourceHook,
            local: Profile,
            result: Profile,
        ) {
            if (result.wholphin != null && result.wholphin != local.wholphin) appSettings?.write(kotlin.io.encoding.Base64.Default.decode(result.wholphin))
            if (result.settings != null && result.settings != local.settings) {
                store.restore(result.settings)
                hook.clearCache()
            }
            if (result.lists != null && result.lists != local.lists) collections.restore(result.lists)
            progress.absorb(result.progress, hook.mainConnection()?.userId)
            progress.absorbDismissed(result.dismissed, hook.mainConnection()?.userId)
        }

        // ------------------------------------------------------------ keys and state

        /** This TV's profile id and PIN seed, when they belong to the signed-in user. */
        private suspend fun keys(hook: SourceHook): Pair<String, ByteArray>? {
            val id = prefs.getString(ID, null) ?: return null
            if (id != identity(hook)) return null
            val seed = kotlin.io.encoding.Base64.Default.decode(store.unseal(prefs.getString(SEED, null) ?: return null).ifEmpty { return null })
            return id to seed
        }

        /**
         * Why there are no keys for the signed-in user: another user signed in, or the PIN seed
         * can't be read on this TV any more (a backup restored on another device, a Keystore reset).
         */
        private suspend fun noKeys(hook: SourceHook): Exception {
            val id = prefs.getString(ID, null)
            if (id != null && id == runCatching { identity(hook) }.getOrNull()) {
                turnOff()
                prefs.edit().remove(PROMPTED).apply()
                return IllegalStateException("This TV lost its sync key. Enter your sync PIN again to keep syncing.")
            }
            return IllegalStateException("This TV is signed in as a different user than its cloud profile")
        }

        private suspend fun identity(hook: SourceHook): String {
            val main = hook.mainConnection() ?: error("Sign in to your main server first")
            val serverId = hook.mainServerId() ?: error("Your main server didn't answer")
            return ProfileCrypto.profileId(serverId, main.userId)
        }

        private fun remember(
            id: String,
            seed: ByteArray,
            version: Int,
            hashes: Map<String, String>,
        ) {
            prefs
                .edit()
                .putString(ID, id)
                .putString(SEED, store.seal(kotlin.io.encoding.Base64.Default.encode(seed)))
                .putInt(VERSION, version)
                .putString(HASHES, ProfileMerge.json.encodeToString(hashes))
                .apply()
        }

        private fun savedHashes(): Map<String, String> =
            runCatching { ProfileMerge.json.decodeFromString<Map<String, String>>(prefs.getString(HASHES, null) ?: "{}") }.getOrDefault(emptyMap())

        private fun busy(b: Boolean) = _status.update { it.copy(busy = b, on = prefs.getString(ID, null) != null) }

        private fun encode(p: Profile) = ProfileMerge.json.encodeToString(p).toByteArray()

        private fun decode(b: ByteArray) = ProfileMerge.json.decodeFromString<Profile>(String(b))

        companion object {
            /**
             * The Orca+ cloud, given to the build (-PorcaCloudUrl; the official release sets it
             * from a repository secret, personal builds from .cloud-url). Not in the source, so a
             * build from it has no cloud: sync, phone pairing, charts and the TMDB proxy stay off.
             */
            val ENDPOINT: String = com.wholphinplus.sources.BuildConfig.CLOUD_URL.trimEnd('/')

            /** Whether this build has a cloud at all (see [ENDPOINT]). */
            val AVAILABLE: Boolean get() = ENDPOINT.isNotBlank()

            private const val ID = "profile_id"
            private const val RESET_SEEN = "reset_seen"
            private const val SEED = "seed"
            private const val VERSION = "version"
            private const val HASHES = "hashes"
            private const val LAST = "last_sync"
            private const val PROMPTED = "prompted"
            private const val RETURNING = "welcome_returning"
            private const val JOINING = "joining"
            private const val ACTIVE = "active_account"

            /** [ProfileMerge.hashes]' sections, as the Message Center says them. */
            private val SECTION_WORDS = mapOf(ProfileMerge.WHOLPHIN to "app settings", ProfileMerge.SETTINGS to "Orca+ settings and servers", ProfileMerge.LISTS to "lists and rows")
            private const val DEVICE = "device_"
            private const val MIN_GAP_MS = 2 * 60 * 1000L

            /**
             * Whether a pending reset due at [at] is the one already answered at [seen]. A reset's
             * time only moves later (the cloud waits while the profile's guesses are being run out,
             * at most six days past the day asked for); a new one can only follow the old one's end
             * (the profile was deleted, or kept: then the time read 0 and [seen] was cleared).
             */
            fun sameReset(
                seen: Long,
                at: Long,
            ): Boolean = seen > 0 && at >= seen && at <= seen + 7 * 86_400_000L

            /** A message for people, from whatever went wrong. */
            fun describe(e: Throwable): String =
                when {
                    e is CloudException && e.error == "wrong_pin" -> "Wrong PIN" + (e.triesLeft?.let { " ($it tries left)" } ?: "")
                    e is CloudException && e.error == "locked" -> "Too many wrong PINs. Try again in ${minutes(e.retryAfterSec ?: 900)}"
                    e is CloudException && e.error == "no_profile" -> "The cloud profile was deleted, so sync is off on this TV"
                    e is CloudException && e.error == "no_name" -> "No Orca+ account has that name"
                    e is CloudException && e.error == "name_taken" -> "Someone already has that name. Try another"
                    e is CloudException && e.error == "bad_name" -> "Use 3 to 24 letters or numbers (dots, dashes and underscores are fine too)"
                    e is CloudException && e.error == "slow_down" -> "The cloud is busy. Trying again in ${minutes(e.retryAfterSec ?: 60)}"
                    e is CloudException && e.error == "kept_recently" ->
                        "A TV that syncs kept this setup recently, so it can't be reset again for ${days(e.retryAfterSec)}. " +
                            "On a TV that still syncs, change the PIN instead: Settings → Account → Name & PIN → Change PIN."
                    e is CloudException && e.error == "full" -> "The Orca+ cloud is full right now, so nothing new can be saved. Your setup here is unchanged; try again later."
                    e is CloudException -> "The cloud answered ${e.error}"
                    e is java.io.IOException -> "Can't reach the Orca+ cloud"
                    e is javax.crypto.AEADBadTagException -> "The cloud profile couldn't be unlocked with this PIN"
                    else -> e.message ?: e.javaClass.simpleName
                }

            private fun minutes(sec: Int): String = ((sec + 59) / 60).coerceAtLeast(1).let { if (it == 1) "a minute" else "$it minutes" }

            private fun days(sec: Int?): String =
                when (val d = ((sec ?: 3 * 86_400) + 86_399) / 86_400) {
                    1 -> "about a day"
                    else -> "about $d days"
                }
        }
    }
