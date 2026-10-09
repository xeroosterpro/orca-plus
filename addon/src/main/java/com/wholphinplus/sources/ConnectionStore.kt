package com.wholphinplus.sources

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import com.wholphinplus.sources.core.ServerConnection
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.Json
import timber.log.Timber
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.inject.Inject
import javax.inject.Singleton

/** Saved servers. Tokens are encrypted with a key that never leaves the Android Keystore. */
@Singleton
class ConnectionStore
    @Inject
    constructor(
        @param:ApplicationContext context: Context,
    ) {
        private val prefs = context.getSharedPreferences("wholphinplus_sources", Context.MODE_PRIVATE)

        // Opening the Keystore is slow on a TV box, and the store decrypts every saved token
        // while the app starts: open it once. (Declared before load() runs below.)
        @Volatile private var secretKey: SecretKey? = null

        private val json = Json { ignoreUnknownKeys = true }

        // Poster tags and scores keep every value, so a later change of default can't move them
        private val fullJson = Json { ignoreUnknownKeys = true; encodeDefaults = true }
        private val _connections = MutableStateFlow(load())
        val connections: StateFlow<List<ServerConnection>> = _connections.asStateFlow()

        private val _tmdbKey = MutableStateFlow(decrypt(prefs.getString(TMDB_KEY, "").orEmpty()))

        /**
         * Orca+'s own look (the big-screen home, title pages, search) instead of Wholphin's.
         * Always on since 2026-10-07 (owner: one look, no theme to choose); the classic look and
         * its switches are gone. Kept as a flow so the screens that ask needn't change.
         */
        private val _cinemaMode = MutableStateFlow(true)

        val cinemaMode: StateFlow<Boolean> = _cinemaMode.asStateFlow()

        /** No-op: there's only one look now (old synced profiles may still carry "off"). */
        @Suppress("UNUSED_PARAMETER")
        fun setCinemaMode(on: Boolean) = Unit

        private val _onboarding = MutableStateFlow(prefs.getString(ONBOARDING_KEY, com.wholphinplus.sources.welcome.Onboarding.NEW) ?: com.wholphinplus.sources.welcome.Onboarding.NEW)

        /**
         * First-run welcome: "new" (never shown), "started" (welcome on screen, before sign-in),
         * "finishing" (signed in, choosing extra libraries and the look), "done". Existing
         * installs stay "new" and never see it, because it only starts with no server saved.
         */
        val onboarding: StateFlow<String> = _onboarding.asStateFlow()

        fun setOnboarding(stage: String) {
            prefs.edit().putString(ONBOARDING_KEY, stage).apply()
            _onboarding.value = stage
        }

        private val _cinemaRollUp = MutableStateFlow(prefs.getBoolean(ROLL_UP_KEY, true))

        /** Cinema mode: shrink the billboard to a quarter while browsing rows, for more room. */
        val cinemaRollUp: StateFlow<Boolean> = _cinemaRollUp.asStateFlow()

        fun setCinemaRollUp(on: Boolean) {
            prefs.edit().putBoolean(ROLL_UP_KEY, on).apply()
            _cinemaRollUp.value = on
        }

        private val _kidsTab = MutableStateFlow(prefs.getBoolean(KIDS_TAB_KEY, false))

        /** Cinema mode's Kids tab (off until switched on in Settings → Home & Look). */
        val kidsTab: StateFlow<Boolean> = _kidsTab.asStateFlow()

        fun setKidsTab(on: Boolean) {
            prefs.edit().putBoolean(KIDS_TAB_KEY, on).apply()
            _kidsTab.value = on
        }

        private val _posterSize = MutableStateFlow(prefs.getString(POSTER_SIZE_KEY, null) ?: com.wholphinplus.sources.cinema.PosterSize.AUTO)

        /**
         * Card picture size: auto, sharp or fast (300 px). This TV's network, so not synced.
         * Auto's "slow" verdict is kept a day ([com.wholphinplus.sources.cinema.PosterSize]).
         */
        val posterSize: StateFlow<String> = _posterSize.asStateFlow()

        fun setPosterSize(mode: String) {
            prefs.edit().putString(POSTER_SIZE_KEY, mode).apply()
            _posterSize.value = mode
            com.wholphinplus.sources.cinema.PosterSize.mode = mode
            if (mode == com.wholphinplus.sources.cinema.PosterSize.AUTO) com.wholphinplus.sources.cinema.PosterSize.retest()
        }

        private val _deviceMode = MutableStateFlow(prefs.getString(DEVICE_MODE_KEY, null) ?: DeviceClass.AUTO)

        /**
         * How much this TV does (Settings → About & Help → This TV): auto, or a
         * [DeviceClass.Tier] name. This box's hardware, so not synced. Applies from the next start.
         */
        val deviceMode: StateFlow<String> = _deviceMode.asStateFlow()

        fun setDeviceMode(mode: String) {
            prefs.edit().putString(DEVICE_MODE_KEY, mode).apply()
            _deviceMode.value = mode
            DeviceClass.mode = mode
        }

        init {
            DeviceClass.init(context)
            DeviceClass.mode = _deviceMode.value
            com.wholphinplus.sources.cinema.PosterSize.also {
                it.mode = _posterSize.value
                it.restore(prefs.getLong(POSTER_SLOW_KEY, 0L))
                it.onVerdict = { since -> prefs.edit().putLong(POSTER_SLOW_KEY, since).apply() }
            }
        }

        private val _shuffleLocks = MutableStateFlow(prefs.getStringSet(SHUFFLE_LOCKS_KEY, null).orEmpty().toSet())

        /** Rows set to always shuffle, by [com.wholphinplus.sources.cinema.RowSource.lockKey]. */
        val shuffleLocks: StateFlow<Set<String>> = _shuffleLocks.asStateFlow()

        fun setShuffleLocks(keys: Set<String>) {
            prefs.edit().putStringSet(SHUFFLE_LOCKS_KEY, keys).apply()
            _shuffleLocks.value = keys
        }

        fun setShuffleLock(
            key: String,
            on: Boolean,
        ) = setShuffleLocks(if (on) _shuffleLocks.value + key else _shuffleLocks.value - key)

        private val _overlays =
            MutableStateFlow(
                runCatching { com.wholphinplus.sources.cinema.PosterOverlays.fromSaved(json, prefs.getString(OVERLAYS_KEY, null)!!) }
                    .getOrDefault(com.wholphinplus.sources.cinema.PosterOverlays()),
            )

        /** Cinema mode's poster overlays: quality, HDR, audio, rating, Top 10 and watched badges. */
        val overlays: StateFlow<com.wholphinplus.sources.cinema.PosterOverlays> = _overlays.asStateFlow()

        fun setOverlays(o: com.wholphinplus.sources.cinema.PosterOverlays) {
            prefs.edit().putString(OVERLAYS_KEY, fullJson.encodeToString(o)).apply()
            _overlays.value = o
        }

        private val _pickerPrefs =
            MutableStateFlow(
                runCatching { json.decodeFromString<com.wholphinplus.sources.core.PickerPrefs>(prefs.getString(PICKER_KEY, null)!!) }
                    .getOrDefault(com.wholphinplus.sources.core.PickerPrefs()),
            )

        /** How the copy picker orders and chooses (Settings → Servers & Search). Synced. */
        val pickerPrefs: StateFlow<com.wholphinplus.sources.core.PickerPrefs> = _pickerPrefs.asStateFlow()

        fun setPickerPrefs(p: com.wholphinplus.sources.core.PickerPrefs) {
            prefs.edit().putString(PICKER_KEY, fullJson.encodeToString(p)).apply()
            _pickerPrefs.value = p
        }

        private val _playInfoPrefs =
            MutableStateFlow(
                runCatching { json.decodeFromString<PlayInfoPrefs>(prefs.getString(PLAY_INFO_KEY, null)!!) }
                    .getOrDefault(PlayInfoPrefs()),
            )

        /** The now-playing card and what it shows (Settings → Playback). Synced. */
        val playInfoPrefs: StateFlow<PlayInfoPrefs> = _playInfoPrefs.asStateFlow()

        fun setPlayInfoPrefs(p: PlayInfoPrefs) {
            prefs.edit().putString(PLAY_INFO_KEY, fullJson.encodeToString(p)).apply()
            _playInfoPrefs.value = p
        }

        private val _ratingPrefs =
            MutableStateFlow(
                runCatching { com.wholphinplus.sources.cinema.RatingPrefs.fromSaved(json, prefs.getString(RATINGS_KEY, null)!!) }
                    .getOrDefault(com.wholphinplus.sources.cinema.RatingPrefs()),
            )

        /** Which review scores Cinema mode shows (Settings → Orca+ → Ratings), and where. */
        val ratingPrefs: StateFlow<com.wholphinplus.sources.cinema.RatingPrefs> = _ratingPrefs.asStateFlow()

        fun setRatingPrefs(r: com.wholphinplus.sources.cinema.RatingPrefs) {
            prefs.edit().putString(RATINGS_KEY, fullJson.encodeToString(r)).apply()
            _ratingPrefs.value = r
        }

        private fun layoutKey(page: com.wholphinplus.sources.cinema.RowsPage) =
            if (page == com.wholphinplus.sources.cinema.RowsPage.HOME) HOME_LAYOUT_KEY else HOME_LAYOUT_KEY + "_" + page.name.lowercase()

        private val _pageLayouts =
            MutableStateFlow(
                com.wholphinplus.sources.cinema.RowsPage.entries.mapNotNull { page ->
                    runCatching { prefs.getString(layoutKey(page), null)?.let { page to json.decodeFromString<com.wholphinplus.sources.cinema.HomeLayout>(it) } }.getOrNull()
                }.toMap(),
            )

        /** Each Cinema page's rows as you arranged them; a page that's missing uses its defaults. */
        val pageLayouts: StateFlow<Map<com.wholphinplus.sources.cinema.RowsPage, com.wholphinplus.sources.cinema.HomeLayout>> = _pageLayouts.asStateFlow()

        fun setPageLayout(
            page: com.wholphinplus.sources.cinema.RowsPage,
            layout: com.wholphinplus.sources.cinema.HomeLayout?,
        ) {
            prefs.edit().apply { if (layout == null) remove(layoutKey(page)) else putString(layoutKey(page), json.encodeToString(layout)) }.apply()
            _pageLayouts.value = if (layout == null) _pageLayouts.value - page else _pageLayouts.value + (page to layout)
        }

        private val _mdblistKey = MutableStateFlow(decrypt(prefs.getString(MDBLIST_KEY, "").orEmpty()))

        /** The user's MDBList API key: IMDb, Rotten Tomatoes, Metacritic, Letterboxd and Trakt scores. */
        val mdblistKey: StateFlow<String> = _mdblistKey.asStateFlow()

        fun setMdblistKey(key: String) {
            prefs.edit().putString(MDBLIST_KEY, encrypt(key.trim())).apply()
            _mdblistKey.value = key.trim()
        }

        /** The user's own TMDB API key, for smart search. Empty = Wholphin's normal search. */
        val tmdbKey: StateFlow<String> = _tmdbKey.asStateFlow()

        fun setTmdbKey(key: String) {
            prefs.edit().putString(TMDB_KEY, encrypt(key.trim())).apply()
            _tmdbKey.value = key.trim()
        }

        @Synchronized
        fun save(connection: ServerConnection) {
            val others =
                _connections.value.filterNot {
                    it.connectionId == connection.connectionId ||
                        (it.serverKind == connection.serverKind && it.serverId.isNotBlank() && it.serverId == connection.serverId)
                }
            write(others + connection)
        }

        @Synchronized
        fun replace(connection: ServerConnection) {
            write(_connections.value.map { if (it.connectionId == connection.connectionId) connection else it })
        }

        @Synchronized
        fun remove(connectionId: String) {
            write(_connections.value.filterNot { it.connectionId == connectionId })
        }

        private fun write(list: List<ServerConnection>) {
            val stored = list.map { it.copy(accessToken = encrypt(it.accessToken), accountToken = encrypt(it.accountToken)) }
            prefs.edit().putString(KEY, json.encodeToString(stored)).apply()
            _connections.value = list
        }

        private fun load(): List<ServerConnection> =
            try {
                prefs
                    .getString(KEY, null)
                    ?.let { json.decodeFromString<List<ServerConnection>>(it) }
                    ?.map { it.copy(accessToken = decrypt(it.accessToken), accountToken = decrypt(it.accountToken)) }
                    .orEmpty()
            } catch (e: Exception) {
                Timber.e(e, "Could not read saved source servers")
                emptyList()
            }

        /** Everything here that a cloud profile carries, tokens and keys in the clear. */
        fun snapshot(): com.wholphinplus.sources.sync.OrcaSettings =
            com.wholphinplus.sources.sync.OrcaSettings(
                connections = _connections.value,
                tmdbKey = _tmdbKey.value,
                mdblistKey = _mdblistKey.value,
                cinemaMode = _cinemaMode.value,
                rollUp = _cinemaRollUp.value,
                overlays = _overlays.value,
                ratings = _ratingPrefs.value,
                layouts = _pageLayouts.value,
                shuffleLocks = _shuffleLocks.value,
                kidsTab = _kidsTab.value,
                picker = _pickerPrefs.value,
                playInfo = _playInfoPrefs.value,
            )

        /** A cloud profile's settings, through the usual setters so every screen follows. */
        @Synchronized
        fun restore(s: com.wholphinplus.sources.sync.OrcaSettings) {
            if (s.connections != _connections.value) write(s.connections)
            if (s.tmdbKey != _tmdbKey.value) setTmdbKey(s.tmdbKey)
            if (s.mdblistKey != _mdblistKey.value) setMdblistKey(s.mdblistKey)
            if (s.cinemaMode != _cinemaMode.value) setCinemaMode(s.cinemaMode)
            if (s.rollUp != _cinemaRollUp.value) setCinemaRollUp(s.rollUp)
            if (s.shuffleLocks != _shuffleLocks.value) setShuffleLocks(s.shuffleLocks)
            if (s.kidsTab != _kidsTab.value) setKidsTab(s.kidsTab)
            if (s.picker != _pickerPrefs.value) setPickerPrefs(s.picker)
            if (s.playInfo != _playInfoPrefs.value) setPlayInfoPrefs(s.playInfo)
            if (s.overlays != _overlays.value) setOverlays(s.overlays)
            if (s.ratings != _ratingPrefs.value) setRatingPrefs(s.ratings)
            com.wholphinplus.sources.cinema.RowsPage.entries.forEach { page -> if (s.layouts[page] != _pageLayouts.value[page]) setPageLayout(page, s.layouts[page]) }
        }

        /** Encrypts a small secret with this TV's Keystore key (the cloud profile's PIN seed). */
        internal fun seal(plain: String): String = encrypt(plain)

        internal fun unseal(stored: String): String = decrypt(stored)

        private fun key(): SecretKey = secretKey ?: synchronized(this) { secretKey ?: openKey().also { secretKey = it } }

        private fun openKey(): SecretKey {
            val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }
            return KeyGenerator
                .getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
                .apply {
                    init(
                        KeyGenParameterSpec
                            .Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                            .build(),
                    )
                }.generateKey()
        }

        private fun encrypt(plain: String): String {
            if (plain.isEmpty()) return ""
            val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key()) }
            val out = cipher.iv + cipher.doFinal(plain.toByteArray())
            return PREFIX + Base64.encodeToString(out, Base64.NO_WRAP)
        }

        private fun decrypt(stored: String): String {
            if (!stored.startsWith(PREFIX)) return stored
            return try {
                val bytes = Base64.decode(stored.removePrefix(PREFIX), Base64.NO_WRAP)
                val cipher = Cipher.getInstance("AES/GCM/NoPadding")
                cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, bytes, 0, 12))
                String(cipher.doFinal(bytes, 12, bytes.size - 12))
            } catch (e: Exception) {
                Timber.e(e, "Could not decrypt a source server token")
                ""
            }
        }

        private companion object {
            const val KEY = "connections_v1"
            const val TMDB_KEY = "tmdb_key_v1"
            const val CINEMA_KEY = "cinema_mode"
            const val ROLL_UP_KEY = "cinema_roll_up"
            const val SHUFFLE_LOCKS_KEY = "shuffle_locks"
            const val KIDS_TAB_KEY = "kids_tab"
            const val PICKER_KEY = "picker_prefs"
            const val PLAY_INFO_KEY = "play_info_prefs"
            const val POSTER_SIZE_KEY = "poster_size"
            const val DEVICE_MODE_KEY = "device_mode"
            const val POSTER_SLOW_KEY = "poster_slow_since"
            const val ONBOARDING_KEY = "onboarding_stage"

            const val OVERLAYS_KEY = "poster_overlays"
            const val RATINGS_KEY = "rating_prefs"
            const val HOME_LAYOUT_KEY = "home_layout_v1"
            const val MDBLIST_KEY = "mdblist_key_v1"
            const val ALIAS = "wholphinplus_sources_v1"
            const val PREFIX = "enc1:"
        }
    }
