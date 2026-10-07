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
import java.util.Base64
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
        )

        @Volatile var appSettings: AppSettings? = null

        private val prefs = context.getSharedPreferences("wholphinplus_cloud", Context.MODE_PRIVATE)
        private val client = CloudClient(ENDPOINT)
        private val mutex = Mutex()
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        private val _status = MutableStateFlow(Status(on = prefs.getString(ID, null) != null, lastSync = prefs.getLong(LAST, 0)))
        val status: StateFlow<Status> = _status.asStateFlow()

        @Volatile private var lastTry = 0L

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
                try {
                    val id = identity(hook)
                    val seed = ProfileCrypto.seed(pin, id)
                    val auth = ProfileCrypto.auth(seed)
                    val joining = client.exists(id)
                    if (joining) client.open(id, auth) else client.create(id, auth)
                    remember(id, seed, version = 0, hashes = emptyMap())
                    markPrompted()
                    syncLocked(hook, joining = joining)
                } finally {
                    busy(false)
                }
            }
        }

        /** Stops syncing on this TV; the cloud keeps the profile. */
        fun turnOff() {
            prefs.edit().remove(ID).remove(SEED).remove(VERSION).remove(HASHES).remove(LAST).apply()
            _status.value = Status()
        }

        /** The welcome's first choice: "I have an Orca+ account" (true) or "I'm new" (false). */
        var welcomeReturning: Boolean
            get() = prefs.getBoolean(RETURNING, false)
            set(v) = prefs.edit().putBoolean(RETURNING, v).apply()

        suspend fun pairStart(): PairStart = withContext(Dispatchers.IO) { client.pairStart() }

        suspend fun pairCollect(p: PairStart): Map<String, String>? = withContext(Dispatchers.IO) { client.pairCollect(p) }

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
                    val local = merged(hook, copy, seed, joining = false)
                    val version = client.changePin(id, auth, ProfileCrypto.auth(newSeed), copy.version, ProfileCrypto.seal(encode(local), newSeed, copy.wrap))
                    remember(id, newSeed, version, ProfileMerge.hashes(localProfile()))
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
                        syncLocked(hook, joining = false)
                    } catch (e: Exception) {
                        if (e is kotlinx.coroutines.CancellationException) throw e
                        Timber.w(e, "Cloud sync failed")
                        // Deleted from another TV: this one stops, and says why
                        if (e is CloudException && e.error == "no_profile") turnOff()
                        // The PIN was changed on another TV: stop at once (each try would count
                        // toward the lock) and ask for the new one next time the app opens
                        if (e is CloudException && (e.error == "wrong_pin" || e.error == "locked")) {
                            turnOff()
                            prefs.edit().remove(PROMPTED).apply()
                        }
                        _status.update { it.copy(problem = if (e is CloudException && e.error == "wrong_pin") "The sync PIN was changed on another TV. Enter the new one to keep syncing." else describe(e)) }
                    } finally {
                        busy(false)
                    }
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
            val (id, seed) = keys(hook) ?: error("This TV is signed in as a different user than its cloud profile")
            val auth = ProfileCrypto.auth(seed)
            repeat(3) {
                val copy = client.open(id, auth)
                // A "forgot PIN" reset asked for elsewhere: this TV shows it and can keep the setup
                _status.update { it.copy(resetAt = copy.resetAt) }
                val result = merged(hook, copy, seed, joining)
                val remote = copy.blob?.let { decode(ProfileCrypto.open(it, seed, copy.wrap)) }
                val version =
                    if (remote != null && ProfileMerge.hashes(result) == ProfileMerge.hashes(remote) && result.progress == remote.progress) {
                        copy.version
                    } else {
                        try {
                            client.put(id, auth, copy.version, ProfileCrypto.seal(encode(result), seed, copy.wrap))
                        } catch (e: CloudException) {
                            // Another TV wrote meanwhile: read it and merge again
                            if (e.error == "conflict") return@repeat else throw e
                        }
                    }
                remember(id, seed, version, ProfileMerge.hashes(localProfile()))
                _status.update { it.copy(on = true, lastSync = System.currentTimeMillis(), problem = null, resetAt = copy.resetAt) }
                prefs.edit().putLong(LAST, System.currentTimeMillis()).apply()
                Timber.i("Cloud sync: version %d", version)
                return
            }
            error("Another TV kept changing the profile; try again")
        }

        /** The cloud copy and this TV's merged, and the merge applied here. */
        private suspend fun merged(
            hook: SourceHook,
            copy: CloudCopy,
            seed: ByteArray,
            joining: Boolean,
        ): Profile {
            val remote = copy.blob?.let { decode(ProfileCrypto.open(it, seed, copy.wrap)) }
            val local = localProfile()
            val result = ProfileMerge.merge(local, remote, savedHashes(), joining, System.currentTimeMillis())
            apply(hook, local, result)
            return result
        }

        private suspend fun localProfile(): Profile =
            Profile(
                wholphin = appSettings?.let { s -> runCatching { Base64.getEncoder().encodeToString(s.read()) }.getOrNull() },
                settings = store.snapshot(),
                lists = collections.snapshot(),
                progress = progress.snapshot(),
            )

        private suspend fun apply(
            hook: SourceHook,
            local: Profile,
            result: Profile,
        ) {
            if (result.wholphin != null && result.wholphin != local.wholphin) appSettings?.write(Base64.getDecoder().decode(result.wholphin))
            if (result.settings != null && result.settings != local.settings) {
                store.restore(result.settings)
                hook.clearCache()
            }
            if (result.lists != null && result.lists != local.lists) collections.restore(result.lists)
            progress.absorb(result.progress)
        }

        // ------------------------------------------------------------ keys and state

        /** This TV's profile id and PIN seed, when they belong to the signed-in user. */
        private suspend fun keys(hook: SourceHook): Pair<String, ByteArray>? {
            val id = prefs.getString(ID, null) ?: return null
            if (id != identity(hook)) return null
            val seed = Base64.getDecoder().decode(store.unseal(prefs.getString(SEED, null) ?: return null).ifEmpty { return null })
            return id to seed
        }

        private suspend fun identity(hook: SourceHook): String {
            val main = hook.mainConnection() ?: error("Sign in to your Jellyfin server first")
            val serverId = hook.mainServerId() ?: error("Your Jellyfin server didn't answer")
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
                .putString(SEED, store.seal(Base64.getEncoder().encodeToString(seed)))
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
            private const val MIN_GAP_MS = 2 * 60 * 1000L

            /** A message for people, from whatever went wrong. */
            fun describe(e: Throwable): String =
                when {
                    e is CloudException && e.error == "wrong_pin" -> "Wrong PIN" + (e.triesLeft?.let { " ($it tries left)" } ?: "")
                    e is CloudException && e.error == "locked" -> "Too many wrong PINs. Try again in ${((e.retryAfterSec ?: 900) + 59) / 60} minutes"
                    e is CloudException && e.error == "no_profile" -> "The cloud profile was deleted, so sync is off on this TV"
                    e is CloudException && e.error == "slow_down" -> "The cloud is busy; try again in a minute"
                    e is CloudException -> "The cloud answered ${e.error}"
                    e is java.io.IOException -> "Can't reach the Orca+ cloud"
                    e is javax.crypto.AEADBadTagException -> "The cloud profile couldn't be unlocked with this PIN"
                    else -> e.message ?: e.javaClass.simpleName
                }
        }
    }
