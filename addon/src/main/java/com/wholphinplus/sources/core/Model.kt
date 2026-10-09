package com.wholphinplus.sources.core

import kotlinx.serialization.Serializable
import java.time.Instant
import java.util.Locale

/** Which server product a connection talks to. The names are stored, never rename them. */
@Serializable
enum class ServerKind {
    UNKNOWN,
    JELLYFIN,
    EMBY,
    PLEX,
}

val ServerKind.label: String
    get() =
        when (this) {
            ServerKind.PLEX -> "Plex"
            ServerKind.JELLYFIN -> "Jellyfin"
            ServerKind.EMBY -> "Emby"
            ServerKind.UNKNOWN -> ""
        }

/** One library on a server. Property order is part of the saved format. */
@Serializable
data class ServerCollection(
    val id: String = "",
    val name: String = "",
    val type: String = "",
    val enabled: Boolean = true,
)

/** A saved sign-in to one server for one user. Property order is part of the saved format. */
@Serializable
data class ServerConnection(
    val enabled: Boolean = true,
    val connectionId: String = "",
    val serverUrl: String = "",
    val displayName: String = "",
    val serverName: String = "",
    val serverKind: ServerKind = ServerKind.UNKNOWN,
    val serverId: String = "",
    val userId: String = "",
    val userName: String = "",
    val accessToken: String = "",
    val accountToken: String = "",
    val collections: List<ServerCollection> = emptyList(),
    val lastConnectedAt: Long = 0L,
) {
    // Plex sign-ins carry no real user id, so only the token counts there
    val isUsable: Boolean
        get() =
            enabled &&
                serverUrl.isNotBlank() &&
                accessToken.isNotBlank() &&
                (serverKind == ServerKind.PLEX || userId.isNotBlank())

    val label: String
        get() {
            val name =
                displayName.ifBlank { null }
                    ?: serverName.ifBlank { null }
                    ?: hostLabel(serverUrl).ifBlank { null }
                    ?: "Server"
            val kind = serverKind.label
            return if (kind.isEmpty() || name.contains(kind, ignoreCase = true)) name else "$kind $name"
        }
}

/** What to look for. For an episode, title, year and ids describe the series. */
data class PlayRequest(
    val title: String,
    val year: Int?,
    val imdbId: String?,
    val tmdbId: Int?,
    val tvdbId: Int?,
    val season: Int? = null,
    val episode: Int? = null,
) {
    val isEpisode: Boolean get() = season != null && episode != null
}

/** One playable copy of a title on some server. */
data class ExternalSource(
    val connectionId: String,
    val serverLabel: String,
    val serverKind: ServerKind,
    val url: String,
    val quality: String,
    val qualityRank: Int,
    val videoCodec: String,
    val hdr: String,
    val audio: String,
    val container: String,
    val sizeBytes: Long,
    val fileName: String,
    // A server-side HLS conversion (Plex) instead of the file itself
    val compatible: Boolean = false,
    val itemId: String = "",
    val mediaSourceId: String = "",
    val runTimeTicks: Long = 0L,
    // The video itself, for the display mode and player choice (0: unknown)
    val width: Int = 0,
    val height: Int = 0,
    val frameRate: Float = 0f,
) {
    val size: String get() = formatBytes(sizeBytes)

    // A log line or crash report gets the copy, never its stream URL (it carries the server's token)
    override fun toString(): String = "ExternalSource($serverLabel, $quality, $container, ${size}, item $itemId)"
}

/** A copy's pictures for the player: chapter pictures by start (ms), and Emby's thumbnail track (BIF). */
data class CopyPictures(
    val chapters: List<Pair<Long, String>>,
    val bifUrl: String?,
)

data class EmbyConnectAccount(
    val userId: String,
    val token: String,
)

data class EmbyConnectServer(
    val name: String,
    val remoteUrl: String,
    val localUrl: String,
    val systemId: String,
    val accessKey: String,
)

/** A sign-in waiting for the user to enter [code] somewhere else. */
data class CodeLogin(
    val id: String,
    val secret: String,
    val code: String,
    val verificationUrl: String,
    val kind: ServerKind,
    val serverUrl: String,
    val intervalSeconds: Int = 5,
)

data class ServerInfo(
    val serverName: String,
    val serverId: String,
    val productName: String,
    val serverKind: ServerKind = ServerKind.UNKNOWN,
)

/** Binary units with the familiar GB / MB names. */
fun formatBytes(bytes: Long): String {
    if (bytes <= 0) return ""
    val mb = 1024.0 * 1024.0
    val gb = mb * 1024.0
    return if (bytes >= gb) {
        String.format(Locale.US, "%.1f GB", bytes / gb)
    } else {
        String.format(Locale.US, "%.0f MB", bytes / mb)
    }
}

/** Something started or finished on another server. */
data class WatchEntry(
    val itemId: String,
    val request: PlayRequest,
    val positionTicks: Long,
    val played: Boolean,
    val lastPlayed: Instant,
)

data class UserItemData(
    val positionTicks: Long,
    val played: Boolean,
    val lastPlayed: Instant?,
    val seriesId: String?,
)

enum class PlayEvent {
    START,
    PROGRESS,
    STOP,
}
