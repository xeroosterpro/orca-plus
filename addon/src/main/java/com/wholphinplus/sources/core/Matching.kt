// Contains code adapted from a third-party project under the Apache License 2.0 and modified
// for Orca+. See NOTICE and LICENSES/Apache-2.0.txt.
package com.wholphinplus.sources.core

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.text.Normalizer
import java.util.Locale
import kotlin.math.abs

internal data class CandidateInfo(
    val title: String,
    val productionYear: Int?,
    val providerIds: Map<String, String>,
)

internal object Matcher {
    private val DIACRITICS = Regex("\\p{Mn}+")
    private val NON_ALPHA_NUM = Regex("[^a-z0-9]+")
    private val ARTICLES = Regex("\\b(the|a|an)\\b")
    private val MULTI_SPACE = Regex("\\s+")

    fun normalizeTitle(title: String): String =
        Normalizer
            .normalize(title, Normalizer.Form.NFD)
            .replace(DIACRITICS, "")
            .lowercase(Locale.US)
            .replace("&", " and ")
            .replace(NON_ALPHA_NUM, " ")
            .replace(ARTICLES, " ")
            .trim()
            .replace(MULTI_SPACE, " ")

    fun score(
        requestedTitle: String,
        requestedYear: Int?,
        imdbId: String?,
        tmdbId: Int?,
        tvdbId: Int?,
        candidate: CandidateInfo,
    ): Int {
        var score = 0
        val providers = candidate.providerIds.mapKeys { it.key.lowercase(Locale.US) }
        val cleanImdb = imdbId?.trim()?.lowercase(Locale.US)
        if (!cleanImdb.isNullOrBlank() && providers["imdb"]?.lowercase(Locale.US) == cleanImdb) score += 1000
        if (tmdbId != null && providers["tmdb"]?.toIntOrNull() == tmdbId) score += 900
        if (tvdbId != null && providers["tvdb"]?.toIntOrNull() == tvdbId) score += 900
        // A conflicting provider id with no matching one is a different title.
        if (score == 0 &&
            (
                (!cleanImdb.isNullOrBlank() && !providers["imdb"].isNullOrBlank() && providers["imdb"]?.lowercase(Locale.US) != cleanImdb) ||
                    (tmdbId != null && providers["tmdb"]?.toIntOrNull()?.let { it != tmdbId } == true) ||
                    (tvdbId != null && providers["tvdb"]?.toIntOrNull()?.let { it != tvdbId } == true)
            )
        ) {
            return 0
        }

        val requested = normalizeTitle(requestedTitle)
        val found = normalizeTitle(candidate.title)
        if (requested.isNotBlank() && found.isNotBlank()) {
            when {
                requested == found -> score += 160
                requested in found || found in requested -> score += 65
            }
        }
        val candidateYear = candidate.productionYear
        if (requestedYear != null && candidateYear != null) {
            when (abs(requestedYear - candidateYear)) {
                0 -> score += 90
                1 -> score += 45
                2 -> score += 15
                else -> score -= 120
            }
        }
        return score
    }

    fun isAcceptable(score: Int): Boolean = score >= 150

    fun isLikelySameVersion(
        requestedTitle: String,
        requestedYear: Int?,
        candidate: CandidateInfo,
    ): Boolean {
        val requested = normalizeTitle(requestedTitle)
        if (requested.isBlank() || requested != normalizeTitle(candidate.title)) return false
        val candidateYear = candidate.productionYear ?: return true
        return requestedYear == null || abs(requestedYear - candidateYear) <= 1
    }
}

fun normalizeServerUrl(rawUrl: String): String {
    val input = rawUrl.trim()
    if (input.isEmpty()) return ""
    val withScheme = if (input.startsWith("http://", true) || input.startsWith("https://", true)) input else "http://$input"
    val url = withScheme.toHttpUrlOrNull() ?: return ""
    val segments = url.encodedPathSegments.dropLastWhile { it.isEmpty() }
    val webIndex = segments.indexOfLast { it.equals("web", ignoreCase = true) }
    val dashboardSuffix =
        webIndex >= 0 &&
            (webIndex == segments.lastIndex || (webIndex == segments.lastIndex - 1 && segments.last().endsWith(".html", true)))
    // Strip only a web-client suffix; keep reverse-proxy prefixes and explicit ports.
    val baseSegments = if (dashboardSuffix) segments.take(webIndex) else segments
    return url
        .newBuilder()
        .encodedPath("/" + baseSegments.joinToString("/"))
        .query(null)
        .fragment(null)
        .build()
        .toString()
        .trimEnd('/')
}

internal fun hostLabel(serverUrl: String): String =
    serverUrl
        .toHttpUrlOrNull()
        ?.let { url ->
            val defaultPort = if (url.scheme == "https") 443 else 80
            if (url.port != defaultPort) "${url.host}:${url.port}" else url.host
        }.orEmpty()

internal fun sameEndpoint(
    left: String,
    right: String,
): Boolean {
    val l = left.toHttpUrlOrNull() ?: return false
    val r = right.toHttpUrlOrNull() ?: return false
    return l.host.equals(r.host, ignoreCase = true) && l.port == r.port
}

/**
 * Product name decides first; checking "plex" against product+server name together would
 * misdetect an Emby server named "Plex Mirror".
 */
internal fun detectServerKind(
    productName: String,
    serverName: String,
): ServerKind {
    fun fromText(text: String): ServerKind? {
        val t = text.lowercase(Locale.US)
        return when {
            "emby" in t -> ServerKind.EMBY
            "jellyfin" in t || t == "silo" || t == "silo server" -> ServerKind.JELLYFIN
            "plex" in t -> ServerKind.PLEX
            else -> null
        }
    }
    return fromText(productName) ?: fromText(serverName) ?: ServerKind.UNKNOWN
}

fun qualityLabel(
    height: Int,
    width: Int,
    fileName: String,
): String =
    when {
        height >= 2160 || width >= 3800 -> "4K"
        height >= 1440 -> "1440p"
        height >= 1080 || width >= 1900 -> "1080p"
        height >= 720 || width >= 1260 -> "720p"
        height >= 576 -> "576p"
        height >= 480 -> "480p"
        height > 0 -> "${height}p"
        else -> qualityFromName(fileName)
    }

private fun qualityFromName(name: String): String {
    val t = name.lowercase(Locale.US)
    return when {
        "2160" in t || "4k" in t || "uhd" in t -> "4K"
        "1440" in t -> "1440p"
        "1080" in t -> "1080p"
        "720" in t -> "720p"
        "576" in t -> "576p"
        "480" in t -> "480p"
        else -> ""
    }
}

fun qualityRank(quality: String): Int {
    val t = quality.lowercase(Locale.US)
    return when {
        "4k" in t || "2160" in t || "uhd" in t -> 2160
        "1440" in t -> 1440
        "1080" in t -> 1080
        "720" in t -> 720
        "576" in t -> 576
        "480" in t -> 480
        else -> 0
    }
}

/** Best first: resolution, then HDR/DV, then bigger file (higher bitrate), direct before compatible. */
val sourceRanking: Comparator<ExternalSource> =
    compareByDescending<ExternalSource> { it.qualityRank }
        .thenByDescending { hdrRank(it.hdr) }
        .thenBy { it.compatible }
        .thenByDescending { it.sizeBytes }

fun hdrRank(hdr: String): Int =
    when {
        hdr.contains("Dolby Vision", true) -> 3
        hdr.contains("HDR10+", true) -> 2
        hdr.isNotBlank() -> 1
        else -> 0
    }
