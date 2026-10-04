// Contains code adapted from a third-party project under the Apache License 2.0 and modified
// for Wholphin+. See NOTICE and LICENSES/Apache-2.0.txt.
package com.wholphinplus.sources.core

import kotlinx.serialization.Serializable

@Serializable
enum class ServerKind { UNKNOWN, JELLYFIN, EMBY, PLEX }

@Serializable
data class ServerCollection(
    val id: String = "",
    val name: String = "",
    val type: String = "",
    val enabled: Boolean = true,
)

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
    val isUsable: Boolean
        get() =
            enabled && serverUrl.isNotBlank() && accessToken.isNotBlank() &&
                (serverKind == ServerKind.PLEX || userId.isNotBlank())

    val label: String
        get() {
            val name = displayName.ifBlank { serverName }.ifBlank { hostLabel(serverUrl) }.ifBlank { "Server" }
            val kind = serverKind.label
            return if (kind.isBlank() || name.contains(kind, ignoreCase = true)) name else "$kind $name"
        }
}

val ServerKind.label: String
    get() =
        when (this) {
            ServerKind.PLEX -> "Plex"
            ServerKind.JELLYFIN -> "Jellyfin"
            ServerKind.EMBY -> "Emby"
            ServerKind.UNKNOWN -> ""
        }

/** What the user pressed Play on, reduced to what matching needs. */
data class PlayRequest(
    val title: String,
    val year: Int?,
    val imdbId: String?,
    val tmdbId: Int?,
    val tvdbId: Int?,
    /** Non-null for episodes; [title]/ids then describe the series. */
    val season: Int? = null,
    val episode: Int? = null,
) {
    val isEpisode: Boolean get() = season != null && episode != null
}

/** One playable copy of the title on another server. */
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
    /** Plex server-side HLS transcode, offered for files ExoPlayer struggles with. */
    val compatible: Boolean = false,
    /** The item and version on its own server, for reporting playback back to it. */
    val itemId: String = "",
    val mediaSourceId: String = "",
    val runTimeTicks: Long = 0L,
) {
    val size: String get() = formatBytes(sizeBytes)
}

/** Login in progress via a code (Plex PIN or Jellyfin Quick Connect). */
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

fun formatBytes(bytes: Long): String {
    if (bytes <= 0L) return ""
    val gb = bytes / (1024.0 * 1024.0 * 1024.0)
    return if (gb >= 1.0) {
        String.format(java.util.Locale.US, "%.1f GB", gb)
    } else {
        String.format(java.util.Locale.US, "%.0f MB", bytes / (1024.0 * 1024.0))
    }
}

/** Something watched (finished or started) on a server, for syncing into the main server. */
data class WatchEntry(
    val itemId: String,
    val request: PlayRequest,
    val positionTicks: Long,
    val played: Boolean,
    val lastPlayed: java.time.Instant,
)

data class UserItemData(
    val positionTicks: Long,
    val played: Boolean,
    val lastPlayed: java.time.Instant?,
    val seriesId: String?,
)

enum class PlayEvent { START, PROGRESS, STOP }
