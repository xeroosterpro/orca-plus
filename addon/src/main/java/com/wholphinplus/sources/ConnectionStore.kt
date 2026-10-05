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
        private val _connections = MutableStateFlow(load())
        val connections: StateFlow<List<ServerConnection>> = _connections.asStateFlow()

        private val _tmdbKey = MutableStateFlow(decrypt(prefs.getString(TMDB_KEY, "").orEmpty()))

        private val _cinemaMode = MutableStateFlow(prefs.getBoolean(CINEMA_KEY, false))

        /** Cinema mode: the big-screen streaming home instead of Wholphin's home page. */
        val cinemaMode: StateFlow<Boolean> = _cinemaMode.asStateFlow()

        fun setCinemaMode(on: Boolean) {
            prefs.edit().putBoolean(CINEMA_KEY, on).apply()
            _cinemaMode.value = on
        }

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

        private val _overlays =
            MutableStateFlow(
                runCatching { json.decodeFromString<com.wholphinplus.sources.cinema.PosterOverlays>(prefs.getString(OVERLAYS_KEY, null) ?: "") }
                    .getOrDefault(com.wholphinplus.sources.cinema.PosterOverlays()),
            )

        /** Cinema mode's poster overlays: quality, HDR, audio, rating, Top 10 and watched badges. */
        val overlays: StateFlow<com.wholphinplus.sources.cinema.PosterOverlays> = _overlays.asStateFlow()

        fun setOverlays(o: com.wholphinplus.sources.cinema.PosterOverlays) {
            prefs.edit().putString(OVERLAYS_KEY, json.encodeToString(o)).apply()
            _overlays.value = o
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
            const val ONBOARDING_KEY = "onboarding_stage"

            const val OVERLAYS_KEY = "poster_overlays"
            const val ALIAS = "wholphinplus_sources_v1"
            const val PREFIX = "enc1:"
        }
    }
