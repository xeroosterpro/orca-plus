package com.wholphinplus.sources.cinema

import android.content.Context
import android.os.SystemClock
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.produceState
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import coil3.request.transformations
import com.wholphinplus.sources.ConnectionStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import timber.log.Timber
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/** A review score Cinema mode can show. [key] is the source's name in MDBList's answers. */
@Serializable
enum class RatingSource(
    val label: String,
    val key: String,
) {
    IMDB("IMDb", "imdb"),
    TOMATOES("Rotten Tomatoes (critics)", "tomatoes"),
    AUDIENCE("Rotten Tomatoes (audience)", "popcorn"),
    METACRITIC("Metacritic", "metacritic"),
    LETTERBOXD("Letterboxd", "letterboxd"),
    TRAKT("Trakt", "trakt"),
    TMDB("TMDB", "tmdb"),
    ;

    /** TMDB's score comes with the title art; the others need an MDBList key. */
    val needsKey: Boolean get() = this != TMDB
}

/** Settings → Orca+ → Ratings: which scores, in the order picked, and where they show. */
@Immutable
@Serializable
data class RatingPrefs(
    // TMDB last: it shows when the others can't (no MDBList key), and cards show the first two
    val sources: List<RatingSource> = listOf(RatingSource.IMDB, RatingSource.TOMATOES, RatingSource.TMDB),
    // Scores on the title page; cards stay clean unless switched on (owner's choice, 2026-10-05)
    val onCards: Boolean = false,
    val onTitlePage: Boolean = true,
) {
    companion object {
        /** Saved scores, read the way they were saved (cards used to default on). */
        fun fromSaved(
            json: kotlinx.serialization.json.Json,
            raw: String,
        ): RatingPrefs = json.decodeFromJsonElement(serializer(), withOld(json, raw, mapOf("onCards" to true)))
    }

    fun with(
        source: RatingSource,
        on: Boolean,
    ): RatingPrefs = copy(sources = if (on) (sources - source) + source else sources - source)
}

internal val LocalRatingPrefs = staticCompositionLocalOf { RatingPrefs() }

/** One score, ready to draw: "7.6", "93%", "81", "3.9". */
@Immutable
internal data class Score(
    val source: RatingSource,
    val text: String,
    /** 0-100, for Metacritic's colour. */
    val score: Int,
)

/**
 * Review scores from MDBList (one request per title gives IMDb, Rotten Tomatoes, Metacritic,
 * Letterboxd and Trakt), remembered on the device for a week so each title is asked about
 * rarely; a free key allows about a thousand requests a day. At most four at a time; when
 * MDBList says the day's limit is reached (or the key is wrong) it isn't asked again for an hour.
 */
@Singleton
class RatingsRepository
    @Inject
    constructor(
        @param:ApplicationContext context: Context,
        private val store: ConnectionStore,
    ) {
        @Serializable
        private data class Entry(
            /** Source key → [value, score]. Empty: MDBList doesn't know the title. */
            val values: Map<String, List<Double>>,
            val at: Long,
        )

        private val prefs = context.getSharedPreferences("wholphinplus_ratings", Context.MODE_PRIVATE)
        private val file = java.io.File(context.filesDir, "cinema_ratings_v1.json")
        private val json = Json { ignoreUnknownKeys = true }
        private val cache = ConcurrentHashMap<String, Entry>()
        private val inFlight = ConcurrentHashMap<String, CompletableDeferred<Entry?>>()
        private val gate = Semaphore(4)
        private val io = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        private var pending: Job? = null

        /** The saved scores, read off the main thread when the app starts; lookups wait for them. */
        private val ready: Job = io.launch { load() }

        @Volatile private var pausedUntil = 0L

        @Volatile private var pausedKey = ""
        private val http = OkHttpClient.Builder().connectTimeout(8, TimeUnit.SECONDS).readTimeout(15, TimeUnit.SECONDS).callTimeout(20, TimeUnit.SECONDS).build()

        val hasKey: Boolean get() = store.mdblistKey.value.isNotBlank()

        /** Known scores, readable on the first frame. */
        internal fun cached(
            tv: Boolean,
            tmdbId: Int,
        ): Map<String, List<Double>>? = cache[key(tv, tmdbId)]?.takeIf { fresh(it) }?.values

        internal suspend fun scores(
            tv: Boolean,
            tmdbId: Int,
        ): Map<String, List<Double>>? {
            val k = key(tv, tmdbId)
            cache[k]?.takeIf { fresh(it) }?.let { return it.values }
            if (!ready.isCompleted) {
                ready.join()
                cache[k]?.takeIf { fresh(it) }?.let { return it.values }
            }
            val apiKey = store.mdblistKey.value
            if (apiKey.isBlank() || (apiKey == pausedKey && SystemClock.elapsedRealtime() < pausedUntil)) return null
            inFlight[k]?.let { return it.await()?.values }
            val waiter = CompletableDeferred<Entry?>()
            inFlight.putIfAbsent(k, waiter)?.let { return it.await()?.values }
            try {
                val entry = gate.withPermit { withContext(Dispatchers.IO) { fetch(tv, tmdbId, apiKey) } }
                if (entry != null) {
                    cache[k] = entry
                    save()
                }
                waiter.complete(entry)
                return entry?.values
            } finally {
                waiter.complete(null)
                inFlight.remove(k)
            }
        }

        private fun fetch(
            tv: Boolean,
            tmdbId: Int,
            apiKey: String,
        ): Entry? {
            val url = "https://api.mdblist.com/tmdb/${if (tv) "show" else "movie"}/$tmdbId?apikey=$apiKey"
            return try {
                http.newCall(Request.Builder().url(url).header("Accept", "application/json").build()).execute().use { r ->
                    when {
                        r.code == 404 -> Entry(emptyMap(), System.currentTimeMillis())
                        r.code == 429 || r.code == 401 || r.code == 403 -> {
                            // Out of requests for today, or the key is wrong: rest a while
                            Timber.w("MDBList answered %d; ratings paused for an hour", r.code)
                            pausedKey = apiKey
                            pausedUntil = SystemClock.elapsedRealtime() + PAUSE_MS
                            null
                        }
                        !r.isSuccessful -> null
                        else -> {
                            val o = json.parseToJsonElement(r.body.string()) as? JsonObject ?: return null
                            val values =
                                (o["ratings"] as? JsonArray).orEmpty().filterIsInstance<JsonObject>().mapNotNull { e ->
                                    val source = (e["source"] as? JsonPrimitive)?.content?.lowercase() ?: return@mapNotNull null
                                    val value = (e["value"] as? JsonPrimitive)?.content?.toDoubleOrNull()
                                    val score = (e["score"] as? JsonPrimitive)?.content?.toDoubleOrNull()
                                    if ((value ?: 0.0) <= 0.0 && (score ?: 0.0) <= 0.0) return@mapNotNull null
                                    source to listOf(value ?: -1.0, score ?: -1.0)
                                }.toMap()
                            Entry(values, System.currentTimeMillis())
                        }
                    }
                }
            } catch (e: Exception) {
                Timber.w(e, "MDBList lookup failed for %s", tmdbId)
                null
            }
        }

        private fun fresh(e: Entry) = System.currentTimeMillis() - e.at < KEEP_MS

        private fun key(
            tv: Boolean,
            tmdbId: Int,
        ) = (if (tv) "tv:" else "movie:") + tmdbId

        /** Written a few seconds after the last new title, off the UI thread, while the remote rests. */
        private fun save() {
            pending?.cancel()
            pending =
                io.launch {
                    delay(4_000)
                    Conductor.whenQuiet()
                    ready.join()
                    if (cache.size > MAX) cache.entries.sortedBy { it.value.at }.take(cache.size - MAX * 3 / 4).forEach { cache.remove(it.key) }
                    CacheFiles.write(file, Entry.serializer(), CacheFiles.Saved(HashMap(cache)))
                }
        }

        private fun load() {
            val saved = CacheFiles.read(file, Entry.serializer())
            if (saved != null) {
                saved.items.forEach { (k, v) -> cache.putIfAbsent(k, v) }
                return
            }
            // Carried over once from the preferences they used to live in
            CacheFiles.legacy(prefs.getString(KEY, null), Entry.serializer()).forEach { (k, v) -> cache.putIfAbsent(k, v) }
            prefs.edit().remove(KEY).apply()
        }

        private companion object {
            const val KEY = "ratings_v1"
            const val MAX = 4_000
            const val KEEP_MS = 7 * 24 * 60 * 60 * 1000L
            const val PAUSE_MS = 60 * 60 * 1000L
        }
    }

internal val LocalRatings = staticCompositionLocalOf<RatingsRepository?> { null }

/** The picked scores for a title, in the order picked; known ones at once, else looked up. */
@Composable
internal fun rememberScores(
    item: CinemaItem?,
    // A card passes the art it already has: one lookup per card, not one per part of it
    art: com.wholphinplus.sources.core.TitleArt? = rememberArt(item),
): List<Score> {
    val prefs = LocalRatingPrefs.current
    val repo = LocalRatings.current
    val tmdb = item?.tmdbId
    val wantsMdb = prefs.sources.any { it.needsKey }
    val mdb =
        produceState(tmdb?.let { repo?.cached(item.tmdbTv, it) }, item?.key, tmdb, wantsMdb) {
            if (value == null && tmdb != null && wantsMdb && repo != null) {
                // Fast scrolling: a card that leaves the screen within a moment never asks
                delay(250)
                value = repo.scores(item.tmdbTv, tmdb)
            }
        }.value
    return prefs.sources.mapNotNull { source ->
        if (source == RatingSource.TMDB) {
            art?.tmdbScore?.let { Score(source, "${(it * 10).toInt()}%", (it * 10).toInt()) }
        } else {
            mdb?.get(source.key)?.let { format(source, it) }
        }
    }
}

private fun format(
    source: RatingSource,
    v: List<Double>,
): Score? {
    val value = v.getOrNull(0)?.takeIf { it > 0 }
    val score = v.getOrNull(1)?.takeIf { it > 0 } ?: value?.let { if (it <= 10) it * 10 else it } ?: return null
    val text =
        when (source) {
            RatingSource.IMDB -> String.format(java.util.Locale.US, "%.1f", value ?: (score / 10))
            RatingSource.LETTERBOXD -> String.format(java.util.Locale.US, "%.1f", value?.takeIf { it <= 5 } ?: (score / 20))
            RatingSource.METACRITIC -> "${score.toInt()}"
            else -> "${score.toInt()}%"
        }
    return Score(source, text, score.toInt())
}

/** A card's scores, stacked in its bottom-right corner (at most two). */
@Composable
internal fun BoxScope.CardRatings(
    item: CinemaItem,
    art: com.wholphinplus.sources.core.TitleArt?,
) {
    if (!LocalRatingPrefs.current.onCards) return
    val scores = rememberScores(item, art).take(2)
    if (scores.isEmpty()) return
    // Beside the watched check when it's there; above the progress bar
    val o = LocalOverlays.current
    val end = if (o.watched && item.played) 30.dp else 7.dp
    Column(
        // Bottom edge in line with the title logo's in the opposite corner (both sit above a
        // New label, which runs along the bottom edge)
        Modifier.align(Alignment.BottomEnd).padding(end = end, bottom = if (item.badge != null && o.newLabels) 22.dp else 10.dp),
        verticalArrangement = Arrangement.spacedBy(3.dp),
        horizontalAlignment = Alignment.End,
    ) {
        scores.forEach { ScoreChip(it, small = true) }
    }
}

/** The title page's line of scores, then where it streams (the cards' white wordmarks). */
@Composable
internal fun TitleRatings(item: CinemaItem) {
    val scores = if (LocalRatingPrefs.current.onTitlePage) rememberScores(item) else emptyList()
    val context = androidx.compose.ui.platform.LocalContext.current
    val services = if (LocalOverlays.current.services) rememberArt(item)?.serviceUrls().orEmpty() else emptyList()
    if (scores.isEmpty() && services.isEmpty()) return
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
        scores.forEach { ScoreChip(it, small = false) }
        if (scores.isNotEmpty() && services.isNotEmpty()) Spacer(Modifier.width(6.dp))
        services.forEach { url ->
            val model = androidx.compose.runtime.remember(url) { coil3.request.ImageRequest.Builder(context).data(url).transformations(WhiteWordmark).build() }
            FittedLogo(model, height = 14.dp, maxWidth = 70.dp, evenOut = true)
        }
    }
}

/** One score: its source's mark and the number, on dark glass. */
@Composable
internal fun ScoreChip(
    s: Score,
    small: Boolean,
) {
    // On a card it stays quieter than the title logo: small, on a light glass
    val text: TextUnit = if (small) 7.5.sp else 13.sp
    val mark: Dp = if (small) 7.dp else 13.dp
    Row(
        Modifier.background(Color.Black.copy(alpha = if (small) 0.45f else 0.62f), RoundedCornerShape(3.dp)).padding(horizontal = if (small) 3.dp else 6.dp, vertical = if (small) 1.dp else 3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SourceMark(s, mark, text)
        Spacer(Modifier.width(if (small) 2.5.dp else 5.dp))
        Text(s.text, color = Color.White, fontSize = text, fontWeight = FontWeight.Bold, lineHeight = text)
    }
}

/** Each source's own mark, drawn (no logos shipped): a tag, a coloured dot, three dots. */
@Composable
private fun SourceMark(
    s: Score,
    size: Dp,
    text: TextUnit,
) {
    when (s.source) {
        RatingSource.IMDB -> Tag("IMDb", Color(0xFFF5C518), Color.Black, text)
        RatingSource.TMDB -> Tag("TMDB", Color(0xFF01B4E4), Color(0xFF0D253F), text)
        RatingSource.METACRITIC -> {
            val c = if (s.score >= 61) Color(0xFF66CC33) else if (s.score >= 40) Color(0xFFFFCC33) else Color(0xFFFF0000)
            Tag("MC", c, Color.Black, text)
        }
        RatingSource.TOMATOES -> Dot(if (s.score >= 60) Color(0xFFFA320A) else Color(0xFF0AC855), size)
        RatingSource.AUDIENCE -> Dot(if (s.score >= 60) Color(0xFFFFB800) else Color(0xFF8A8A8A), size)
        RatingSource.TRAKT -> Dot(Color(0xFFED1C24), size)
        RatingSource.LETTERBOXD ->
            Row(horizontalArrangement = Arrangement.spacedBy((-1).dp)) {
                listOf(Color(0xFFFF8000), Color(0xFF00E054), Color(0xFF40BCF4)).forEach { Dot(it, size * 0.75f) }
            }
    }
}

@Composable
private fun Tag(
    label: String,
    background: Color,
    ink: Color,
    text: TextUnit,
) {
    Box(Modifier.background(background, RoundedCornerShape(2.dp)).padding(horizontal = 2.dp)) {
        Text(label, color = ink, fontSize = text * 0.82f, fontWeight = FontWeight.Black, lineHeight = text)
    }
}

@Composable
private fun Dot(
    color: Color,
    size: Dp,
) {
    Canvas(Modifier.size(size)) { drawCircle(color) }
}
