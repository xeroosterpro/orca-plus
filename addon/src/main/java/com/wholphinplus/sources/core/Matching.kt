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

/**
 * The same server: [sameEndpoint] and the same base path. One reverse proxy can put two servers
 * on one host and port (…/jellyfin and …/emby); host and port alone hid the second one.
 */
internal fun sameServer(
    left: String,
    right: String,
): Boolean {
    if (!sameEndpoint(left, right)) return false
    fun path(u: String) = normalizeServerUrl(u).toHttpUrlOrNull()?.encodedPath?.trimEnd('/')?.lowercase().orEmpty()
    return path(left) == path(right)
}

/**
 * The copy most like [chosen] (the one picked for the first item), for the next episode on the
 * same server: the same kind of stream (direct or the server's conversion), then the nearest
 * resolution, then the same HDR, then the usual ranking. Picking the server's best every time
 * played a 4K remux after you chose 1080p because the remux buffered.
 */
fun closestCopy(
    chosen: ExternalSource,
    candidates: List<ExternalSource>,
): ExternalSource? {
    if (candidates.isEmpty()) return null
    val sameKind = candidates.filter { it.compatible == chosen.compatible }.ifEmpty { candidates.filterNot { it.compatible }.ifEmpty { candidates } }
    return sameKind.sortedWith(
        compareBy<ExternalSource> { kotlin.math.abs(it.qualityRank - chosen.qualityRank) }
            .thenBy { if (hdrRank(it.hdr) == hdrRank(chosen.hdr)) 0 else 1 }
            .then(sourceRanking),
    ).first()
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

/**
 * How well this TV plays a copy ([com.wholphinplus.sources.DeviceClass] sets it once it has read
 * the hardware). [Fit.quality] and [Fit.hdr] are what the screen will show of it; [Fit.overshoot]
 * is a copy bigger than the screen (a 4K copy on a 1080p TV: more to download and decode for
 * nothing). [ANY] is "no opinion": the copy's own quality and HDR.
 */
fun interface CopyFit {
    data class Fit(
        val plays: Boolean,
        val quality: Int,
        val hdr: Int,
        val overshoot: Boolean = false,
    )

    fun judge(s: ExternalSource): Fit

    companion object {
        val ANY = CopyFit { Fit(true, it.qualityRank, hdrRank(it.hdr)) }
    }
}

@Volatile var copyFit: CopyFit = CopyFit.ANY

/**
 * Best first: copies this TV's decoders take, then resolution and HDR as far as the screen shows
 * them, direct play before conversion, a copy that fits the screen before a bigger one, then the
 * bigger file. On a box that plays everything on a 4K HDR screen (the Shield) that's simply
 * resolution, HDR, direct play, size.
 */
private fun ranking(fit: CopyFit): Comparator<ExternalSource> =
    compareByDescending<ExternalSource> { fit.judge(it).plays }
        .thenByDescending { fit.judge(it).quality }
        .thenByDescending { fit.judge(it).hdr }
        .thenBy { it.compatible }
        .thenBy { fit.judge(it).overshoot }

val sourceRanking: Comparator<ExternalSource>
    get() = ranking(copyFit).thenByDescending { it.sizeBytes }

/**
 * How the owner tunes the picker (Settings → Servers & Search): your server's copy first when
 * copies are the same size, one server put first, and playing the top copy without the list.
 */
@kotlinx.serialization.Serializable
data class PickerPrefs(
    val preferMain: Boolean = false,
    // "" best match, [MAIN] your server, else an extra server's connection id
    val first: String = "",
    val autoPlay: Boolean = false,
) {
    companion object {
        const val MAIN = "main"
    }
}

/** Sizes this close (the 0.1 GB the picker shows) count as the same file for [PickerPrefs.preferMain]. */
fun sizeBucket(bytes: Long): Long = Math.round(bytes / 100_000_000.0)

/**
 * [stableRanking] with the owner's tuning: the server put first comes first (its best copy at
 * the top), and with [PickerPrefs.preferMain] your server's copy wins over another of the same
 * quality and size. [mainId] is the main server's row id.
 */
fun tunedRanking(
    serverOrder: List<String>,
    prefs: PickerPrefs,
    mainId: String,
): Comparator<ExternalSource> {
    val place = serverOrder.withIndex().associate { (i, id) -> id to i }
    val firstId =
        when (prefs.first) {
            "" -> null
            PickerPrefs.MAIN -> mainId
            else -> prefs.first
        }
    val quality =
        if (prefs.preferMain) {
            ranking(copyFit)
                .thenByDescending { sizeBucket(it.sizeBytes) }
                .thenBy { if (it.connectionId == mainId) 0 else 1 }
                .thenByDescending { it.sizeBytes }
        } else {
            sourceRanking
        }
    return compareBy<ExternalSource> { if (firstId != null && it.connectionId == firstId) 0 else 1 }
        .then(quality)
        .thenBy { place[it.connectionId] ?: Int.MAX_VALUE }
        .thenBy { it.serverLabel.lowercase() }
        .thenBy { it.fileName }
        .thenBy { it.url }
}

/**
 * One row per copy: the same file reached twice on one server (an item listed in two libraries,
 * or found by two searches) showed as two identical rows. Same server and same file (name and
 * size), else the same media source, is one copy.
 */
fun List<ExternalSource>.withoutRepeats(): List<ExternalSource> =
    distinctBy { s ->
        val file = if (s.fileName.isNotBlank() && s.sizeBytes > 0) "file:" + s.fileName.lowercase() + "|" + s.sizeBytes else null
        Triple(s.connectionId, file ?: s.mediaSourceId.takeIf { it.isNotBlank() }?.let { "ms:$it" } ?: "url:" + s.url, s.compatible)
    }

/**
 * [sourceRanking], with exact ties (the same remux on three servers) settled the same way every
 * time: the servers in the order they're listed in Settings, then the file name. They used to
 * keep the order the servers happened to answer in, so copies swapped places between opens.
 */
fun stableRanking(serverOrder: List<String>): Comparator<ExternalSource> {
    val place = serverOrder.withIndex().associate { (i, id) -> id to i }
    return sourceRanking
        .thenBy { place[it.connectionId] ?: Int.MAX_VALUE }
        .thenBy { it.serverLabel.lowercase() }
        .thenBy { it.fileName }
        .thenBy { it.url }
}
