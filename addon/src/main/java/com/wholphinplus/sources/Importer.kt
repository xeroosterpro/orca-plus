package com.wholphinplus.sources

import android.content.Context
import com.wholphinplus.sources.core.ServerClient
import com.wholphinplus.sources.core.ServerCollection
import com.wholphinplus.sources.core.ServerConnection
import com.wholphinplus.sources.core.ServerKind
import com.wholphinplus.sources.core.array
import com.wholphinplus.sources.core.boolean
import com.wholphinplus.sources.core.long
import com.wholphinplus.sources.core.normalizeServerUrl
import com.wholphinplus.sources.core.obj
import com.wholphinplus.sources.core.string
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import timber.log.Timber
import java.io.File

/** The Jellyfin login Wholphin itself should use, from an import file. */
data class ImportedLogin(
    val url: String,
    val serverId: String,
    val userId: String,
    val userName: String,
    val token: String,
)

/**
 * One-time import of servers pushed over adb by a restore script:
 * `Android/data/<package>/files/wholphinplus-import.json` =
 * `{"main": <record>, "sources": [<record>, ...]}`, records with the fields
 * (`type,name,url,token,username,userId,serverId,enabled,collections`). The file is deleted once
 * read so tokens don't sit on shared storage.
 *
 * Or `/data/local/tmp/wholphinplus-import.json`: on a fresh Android 11+ install (the Android 11
 * emulator bench) adb can't write the app's own folder. Only adb can write there, so it's no less
 * safe; the app can't delete it, the script does once it's read. Public builds read only this
 * one (see [candidates]).
 */
internal object Importer {
    const val FILE_NAME = "wholphinplus-import.json"

    fun run(
        context: Context,
        store: ConnectionStore,
        collections: HomeCollections? = null,
    ): ImportedLogin? {
        val file = candidates(context).firstOrNull { runCatching { it.canRead() }.getOrDefault(false) } ?: return null
        return try {
            val root = Json.parseToJsonElement(file.readText()) as JsonObject
            root.array("sources").filterIsInstance<JsonObject>().mapNotNull(::toConnection).forEach {
                store.save(it)
                Timber.i("Imported source server %s", it.label)
            }
            // "collections": ["https://mdblist.com/lists/…", …] → home rows (refreshed on next home load)
            if (collections != null) {
                root.array("collections").mapNotNull { (it as? kotlinx.serialization.json.JsonPrimitive)?.content }
                    .filter { url -> collections.lists.value.none { it.url.equals(url, true) } }
                    .forEach { collections.add(collections.newCollection(it)) }
            }
            // "tour": true → the welcome tour runs after this sign-in (testing the tour on the bench)
            if (root.boolean("tour") == true) store.setOnboarding(com.wholphinplus.sources.welcome.Onboarding.FINISHING)
            root.string("tmdbApiKey").takeIf { it.isNotBlank() }?.let {
                store.setTmdbKey(it)
                Timber.i("Imported TMDB API key")
            }
            root.obj("main")?.let { m ->
                // An Emby main server signs in without asking its public info first: say so here
                if (m.string("type").equals("emby", ignoreCase = true)) EmbyBridge.markEmby(normalizeServerUrl(m.string("url")))
                ImportedLogin(
                    url = normalizeServerUrl(m.string("url")),
                    serverId = m.string("serverId"),
                    userId = m.string("userId"),
                    userName = m.string("userName").ifBlank { m.string("username") },
                    token = m.string("token").ifBlank { m.string("accessToken") },
                ).takeIf { it.url.isNotBlank() && it.serverId.isNotBlank() && it.userId.isNotBlank() && it.token.isNotBlank() }
            }
        } catch (e: Exception) {
            Timber.e(e, "Could not import %s", FILE_NAME)
            null
        } finally {
            file.delete()
        }
    }

    /**
     * Where an import file may come from. Shared storage (`Android/data/...`) only in personal builds:
     * on Android 6-10 any app with storage permission can write there, so a public build would let
     * another app plant a login. `/data/local/tmp` is writable only by adb, so every build accepts it.
     */
    private fun candidates(context: Context): List<File> =
        listOfNotNull(
            if (BuildConfig.PUBLIC_BUILD) null else context.getExternalFilesDir(null)?.let { File(it, FILE_NAME) },
            File("/data/local/tmp", FILE_NAME),
        )

    private fun toConnection(r: JsonObject): ServerConnection? {
        val url = normalizeServerUrl(r.string("url").ifBlank { r.string("serverUrl") })
        val kind = ServerKind.entries.firstOrNull { it.name.equals(r.string("type").ifBlank { r.string("serverKind") }, true) } ?: return null
        val userId = r.string("userId").ifBlank { if (kind == ServerKind.PLEX) "plex" else return null }
        return ServerConnection(
            enabled = r.boolean("enabled") ?: true,
            connectionId = ServerClient.connectionId(url, kind, userId),
            serverUrl = url,
            serverName = r.string("name"),
            serverKind = kind,
            serverId = r.string("serverId"),
            userId = userId,
            userName = r.string("userName").ifBlank { r.string("username") },
            accessToken = r.string("token").ifBlank { r.string("accessToken") },
            accountToken = r.string("accountToken"),
            collections =
                r.array("collections").filterIsInstance<JsonObject>().map {
                    ServerCollection(it.string("id"), it.string("name"), it.string("type"), it.boolean("enabled") ?: true)
                },
            lastConnectedAt = r.long("lastConnectedAt") ?: System.currentTimeMillis(),
        ).takeIf { it.isUsable || !it.enabled }
    }
}
