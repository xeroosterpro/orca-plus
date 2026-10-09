package com.wholphinplus.sources

import com.wholphinplus.sources.core.ServerKind
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.util.concurrent.ConcurrentHashMap

/**
 * Which server software a copy comes from, for its logo in the picker (owner, 2026-10-09: logos
 * next to each server, and the main server by its own name, never "Your server"). Emby and Plex
 * are known from the connection; Silo speaks Jellyfin's API, so it's told apart once per server by
 * its own API answering `/api/v2/` with a siloserver.org error page.
 */
object ServerBrands {
    enum class Brand { JELLYFIN, SILO, EMBY, PLEX }

    // host → is Silo (asked once per run)
    private val silo = ConcurrentHashMap<String, Boolean>()

    private fun host(url: String): String = url.toHttpUrlOrNull()?.let { "${it.scheme}://${it.host.lowercase()}:${it.port}" }.orEmpty()

    /** The brand if it's already known ([check] finds out Silo). */
    fun known(
        kind: ServerKind,
        url: String,
    ): Brand? =
        when (kind) {
            ServerKind.EMBY -> Brand.EMBY
            ServerKind.PLEX -> Brand.PLEX
            ServerKind.JELLYFIN -> if (silo[host(url)] == true) Brand.SILO else Brand.JELLYFIN
            ServerKind.UNKNOWN -> null
        }

    /** [known], after asking a Jellyfin-speaking server once whether it's Silo. */
    suspend fun check(
        kind: ServerKind,
        url: String,
    ): Brand? {
        val h = host(url)
        if (kind == ServerKind.JELLYFIN && h.isNotEmpty() && !silo.containsKey(h)) {
            silo[h] =
                withContext(Dispatchers.IO) {
                    runCatching {
                        val c = (java.net.URL("$h/api/v2/").openConnection() as java.net.HttpURLConnection)
                        c.connectTimeout = 4_000
                        c.readTimeout = 4_000
                        val body = (if (c.responseCode >= 400) c.errorStream else c.inputStream)?.use { s -> s.readBytes().decodeToString().take(400) }.orEmpty()
                        c.disconnect()
                        "siloserver" in body
                    }.getOrDefault(false)
                }
        }
        return known(kind, url)
    }

    fun logo(brand: Brand): Int =
        when (brand) {
            Brand.JELLYFIN -> R.drawable.orca_server_jellyfin
            Brand.SILO -> R.drawable.orca_server_silo
            Brand.EMBY -> R.drawable.orca_server_emby
            Brand.PLEX -> R.drawable.orca_server_plex
        }

    /** The main server's own name ("BlackoutVault"), else its host; never "Your server". */
    fun mainName(): String {
        val who = AccountActions.who() ?: return "Main server"
        return who.server.ifBlank { who.url.toHttpUrlOrNull()?.host.orEmpty() }.ifBlank { "Main server" }
    }

    /** The main server's address, for its brand. */
    fun mainUrl(): String = AccountActions.who()?.url.orEmpty()
}
