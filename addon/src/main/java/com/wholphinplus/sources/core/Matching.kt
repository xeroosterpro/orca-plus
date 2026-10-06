package com.wholphinplus.sources.core

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.text.Normalizer
import java.util.Locale
import kotlin.math.abs

/** What a server tells us about one of its items, for scoring. */
internal data class CandidateInfo(
    val title: String,
    val productionYear: Int?,
    val providerIds: Map<String, String>,
)

/** Decides whether an item on a server is the title that was asked for. */
internal object Matcher {
    private const val ACCEPT_AT = 150

    private val accents = Regex("\\p{Mn}+")
    private val notAlphanumeric = Regex("[^a-z0-9]+")
    private val articles = Regex("(?<![a-z0-9])(?:the|an|a)(?![a-z0-9])")
    private val spaces = Regex("\\s+")

    /** ASCII-only comparison key: no accents, no punctuation, no "the" / "a" / "an". */
    fun normalizeTitle(title: String): String {
        val plain = Normalizer.normalize(title, Normalizer.Form.NFD).replace(accents, "")
        return plain
            .lowercase(Locale.US)
            .replace("&", " and ")
            .replace(notAlphanumeric, " ")
            .replace(articles, "")
            .trim()
            .replace(spaces, " ")
    }

    fun score(
        requestedTitle: String,
        requestedYear: Int?,
        imdbId: String?,
        tmdbId: Int?,
        tvdbId: Int?,
        candidate: CandidateInfo,
    ): Int {
        val ids = candidate.providerIds.mapKeys { it.key.lowercase(Locale.US) }
        val wantedImdb = imdbId?.trim()?.lowercase(Locale.US).orEmpty()
        val theirImdb = ids["imdb"]?.lowercase(Locale.US).orEmpty()
        val theirTmdb = ids["tmdb"]?.toIntOrNull()
        val theirTvdb = ids["tvdb"]?.toIntOrNull()

        var total = 0
        if (wantedImdb.isNotBlank() && wantedImdb == theirImdb) total += 1000
        if (tmdbId != null && theirTmdb == tmdbId) total += 900
        if (tvdbId != null && theirTvdb == tvdbId) total += 900

        // A different id vetoes the title, but only when no id agreed
        if (total == 0) {
            val imdbClash = wantedImdb.isNotBlank() && theirImdb.isNotBlank() && wantedImdb != theirImdb
            val tmdbClash = tmdbId != null && theirTmdb != null && theirTmdb != tmdbId
            val tvdbClash = tvdbId != null && theirTvdb != null && theirTvdb != tvdbId
            if (imdbClash || tmdbClash || tvdbClash) return 0
        }

        val want = normalizeTitle(requestedTitle)
        val have = normalizeTitle(candidate.title)
        if (want.isNotBlank() && have.isNotBlank()) {
            if (want == have) {
                total += 160
            } else if (want.contains(have) || have.contains(want)) {
                total += 65
            }
        }

        val theirYear = candidate.productionYear
        if (requestedYear != null && theirYear != null) {
            total +=
                when (abs(requestedYear - theirYear)) {
                    0 -> 90
                    1 -> 45
                    2 -> 15
                    else -> -120
                }
        }
        return total
    }

    fun isAcceptable(score: Int): Boolean = score >= ACCEPT_AT

    /** Another edition or library copy of a title that already matched. */
    fun isLikelySameVersion(
        requestedTitle: String,
        requestedYear: Int?,
        candidate: CandidateInfo,
    ): Boolean {
        val want = normalizeTitle(requestedTitle)
        if (want.isBlank() || want != normalizeTitle(candidate.title)) return false
        val theirYear = candidate.productionYear ?: return true
        val year = requestedYear ?: return true
        return abs(year - theirYear) <= 1
    }
}

/**
 * Turns a typed or pasted server address into a base URL. A trailing web-client path
 * (`/web`, `/web/index.html`) is cut; proxy prefixes, ports and the scheme stay.
 */
fun normalizeServerUrl(rawUrl: String): String {
    val typed = rawUrl.trim()
    if (typed.isEmpty()) return ""
    val withScheme =
        if (typed.startsWith("http://", ignoreCase = true) || typed.startsWith("https://", ignoreCase = true)) typed else "http://$typed"
    val url = withScheme.toHttpUrlOrNull() ?: return ""

    val segments = url.encodedPathSegments.toMutableList()
    while (segments.isNotEmpty() && segments.last().isEmpty()) segments.removeAt(segments.lastIndex)

    val web = segments.indexOfLast { it.equals("web", ignoreCase = true) }
    val isWebClient =
        web >= 0 &&
            (web == segments.lastIndex || (web == segments.lastIndex - 1 && segments.last().endsWith(".html", ignoreCase = true)))
    val kept = if (isWebClient) segments.subList(0, web) else segments

    return url
        .newBuilder()
        .encodedPath("/" + kept.joinToString("/"))
        .query(null)
        .fragment(null)
        .build()
        .toString()
        .trimEnd('/')
}

/** Host, plus the port when it isn't the scheme's usual one. */
internal fun hostLabel(serverUrl: String): String {
    val url = serverUrl.toHttpUrlOrNull() ?: return ""
    val usual = if (url.scheme == "https") 443 else 80
    return if (url.port == usual) url.host else "${url.host}:${url.port}"
}

/** Same host and port, whatever the scheme or path. */
internal fun sameEndpoint(
    left: String,
    right: String,
): Boolean {
    val a = left.toHttpUrlOrNull() ?: return false
    val b = right.toHttpUrlOrNull() ?: return false
    return a.host.equals(b.host, ignoreCase = true) && a.port == b.port
}

/** The product name wins over the server name, which owners can set to anything. */
internal fun detectServerKind(
    productName: String,
    serverName: String,
): ServerKind = kindFromText(productName) ?: kindFromText(serverName) ?: ServerKind.UNKNOWN

private fun kindFromText(text: String): ServerKind? {
    val t = text.lowercase(Locale.US)
    return when {
        "emby" in t -> ServerKind.EMBY
        // Silo is built on Jellyfin
        "jellyfin" in t || t == "silo" || t == "silo server" -> ServerKind.JELLYFIN
        "plex" in t -> ServerKind.PLEX
        else -> null
    }
}

/** Width counts at the main tiers so scope encodes (3840x1600) keep their class. */
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

private fun qualityFromName(fileName: String): String {
    val name = fileName.lowercase(Locale.US)
    return when {
        "2160" in name || "4k" in name || "uhd" in name -> "4K"
        "1440" in name -> "1440p"
        "1080" in name -> "1080p"
        "720" in name -> "720p"
        "576" in name -> "576p"
        "480" in name -> "480p"
        else -> ""
    }
}

fun qualityRank(quality: String): Int {
    val q = quality.lowercase(Locale.US)
    return when {
        "4k" in q || "2160" in q || "uhd" in q -> 2160
        "1440" in q -> 1440
        "1080" in q -> 1080
        "720" in q -> 720
        "576" in q -> 576
        "480" in q -> 480
        else -> 0
    }
}

fun hdrRank(hdr: String): Int =
    when {
        hdr.contains("Dolby Vision", ignoreCase = true) -> 3
        hdr.contains("HDR10+", ignoreCase = true) -> 2
        hdr.isNotBlank() -> 1
        else -> 0
    }

/** Best first: resolution, HDR format, direct play before conversion, then the bigger file. */
val sourceRanking: Comparator<ExternalSource> =
    compareByDescending<ExternalSource> { it.qualityRank }
        .thenByDescending { hdrRank(it.hdr) }
        .thenBy { it.compatible }
        .thenByDescending { it.sizeBytes }
