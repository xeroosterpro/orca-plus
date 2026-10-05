package com.wholphinplus.sources.cinema

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withPermit
import kotlinx.serialization.Serializable
import org.jellyfin.sdk.model.api.MediaStream
import org.jellyfin.sdk.model.api.MediaStreamType

/** Which corner of a poster the badges sit in. */
@Serializable
enum class OverlayCorner(
    val label: String,
) {
    TOP_RIGHT("Top right"),
    TOP_LEFT("Top left"),
}

/** How the badges look: dark glass chips, or each in its own colour. */
@Serializable
enum class OverlayStyle(
    val label: String,
) {
    MINIMAL("Minimal"),
    COLOUR("Colour"),
}

/** The user's poster overlays (Settings → Orca+ → Poster overlays). All off but the New labels. */
@Immutable
@Serializable
data class PosterOverlays(
    val resolution: Boolean = false,
    val hdr: Boolean = false,
    val audio: Boolean = false,
    val rating: Boolean = false,
    val top10: Boolean = false,
    val watched: Boolean = false,
    val newLabels: Boolean = true,
    val corner: OverlayCorner = OverlayCorner.TOP_RIGHT,
    val style: OverlayStyle = OverlayStyle.MINIMAL,
) {
    /** These need each title's streams, which make the server's answers bigger. */
    val needsStreams: Boolean get() = resolution || hdr || audio
}

internal val LocalOverlays = staticCompositionLocalOf { PosterOverlays() }

/** Looks one title's streams up on the server (the screen's repository). */
internal fun interface StreamLookup {
    suspend fun tags(id: java.util.UUID): StreamTags?
}

/** Looks many titles' streams up in one request. */
internal fun interface StreamBatchLookup {
    suspend fun tags(ids: List<java.util.UUID>): Map<java.util.UUID, StreamTags>
}

internal val LocalStreamLookup = staticCompositionLocalOf<StreamLookup?> { null }

/**
 * Each title's badge facts, kept on the device so they show on the first frame next time (like a
 * server that precomputes them). At most four lookups at once so scrolling stays smooth.
 */
internal object StreamCache {
    private val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
    private var prefs: android.content.SharedPreferences? = null
    val map = java.util.concurrent.ConcurrentHashMap<String, StreamTags>()
    private val gate = kotlinx.coroutines.sync.Semaphore(6)

    /** Titles a batch is already fetching: their cards wait for it instead of asking alone. */
    private val inFlight = java.util.concurrent.ConcurrentHashMap<String, kotlinx.coroutines.CompletableDeferred<StreamTags?>>()
    private val io = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.IO)
    private var pending: kotlinx.coroutines.Job? = null

    /** Loads the saved facts once; called by the screens that show posters. */
    @Synchronized
    fun attach(context: android.content.Context) {
        if (prefs != null) return
        val p = context.applicationContext.getSharedPreferences("wholphinplus_streams", android.content.Context.MODE_PRIVATE)
        prefs = p
        runCatching { p.getString(KEY, null)?.let { map.putAll(json.decodeFromString<Map<String, StreamTags>>(it)) } }
    }

    operator fun get(id: java.util.UUID): StreamTags? = map[id.toString()]

    /** Known facts, else asks the server once (titles with no file facts are remembered too). */
    suspend fun fetch(
        id: java.util.UUID,
        lookup: StreamLookup,
    ): StreamTags? {
        map[id.toString()]?.let { return it }
        inFlight[id.toString()]?.let { return it.await() }
        val got = gate.withPermit { map[id.toString()] ?: lookup.tags(id) } ?: return null
        map[id.toString()] = got
        save()
        return got
    }

    /**
     * Fetches a row's unknown titles in small batches; their cards pick the answers up. The
     * server works through a batch one title at a time (~120 ms each), so a whole row in one
     * request kept its first badges waiting ~2.5 s; batches of six, several at once, land the
     * first ones in well under a second.
     */
    suspend fun prefetch(
        ids: List<java.util.UUID>,
        batch: StreamBatchLookup,
    ): Unit =
        kotlinx.coroutines.coroutineScope {
            ids.distinct().filter { map[it.toString()] == null && inFlight[it.toString()] == null }.chunked(BATCH).forEach { chunk ->
                launch { prefetchChunk(chunk, batch) }
            }
        }

    private suspend fun prefetchChunk(
        wanted: List<java.util.UUID>,
        batch: StreamBatchLookup,
    ) {
        if (wanted.isEmpty()) return
        val waits = wanted.associateWith { kotlinx.coroutines.CompletableDeferred<StreamTags?>() }
        waits.forEach { (id, d) -> inFlight[id.toString()] = d }
        try {
            val got = gate.withPermit { runCatching { batch.tags(wanted) }.getOrNull() }
            waits.forEach { (id, d) ->
                // A title the answer left out has no file facts: remembered as empty
                val tags = got?.let { it[id] ?: StreamTags(null, null, null) }
                if (tags != null) map[id.toString()] = tags
                d.complete(tags)
            }
            if (got != null) save()
        } finally {
            waits.forEach { (id, d) ->
                d.complete(null)
                inFlight.remove(id.toString())
            }
        }
    }

    /** Written a few seconds after the last new title, off the UI thread. */
    private fun save() {
        val p = prefs ?: return
        pending?.cancel()
        pending =
            io.launch {
                kotlinx.coroutines.delay(4_000)
                Conductor.whenQuiet()
                p.edit().putString(KEY, json.encodeToString(HashMap(map))).apply()
            }
    }

    private const val KEY = "streams_v3"
    private const val BATCH = 6
}

/** A card's quality facts: known ones at once, else looked up once it's on screen (if wanted). */
@Composable
private fun rememberStreamTags(
    item: CinemaItem,
    wanted: Boolean,
): StreamTags? {
    val known = item.resolution?.let { StreamTags(it, item.hdr, item.audio) }
    val lookup = LocalStreamLookup.current
    // Shows have no file of their own; search results from TMDB have placeholder ids
    val askable = wanted && known == null && lookup != null && item.kind != org.jellyfin.sdk.model.api.BaseItemKind.SERIES && item.id.mostSignificantBits != 0L
    return androidx.compose.runtime.produceState(known ?: StreamCache[item.id], item.id, askable) {
        if (value == null && askable) {
            // Fast scrolling: a card that leaves the screen within a moment never asks
            kotlinx.coroutines.delay(150)
            value = StreamCache.fetch(item.id, lookup!!)
        }
    }.value
}

/** What a title's streams say, for its badges. */
@Serializable
internal data class StreamTags(
    val resolution: String?,
    val hdr: String?,
    val audio: String?,
)

internal fun streamTags(streams: List<MediaStream>?): StreamTags {
    if (streams.isNullOrEmpty()) return StreamTags(null, null, null)
    val video = streams.firstOrNull { it.type == MediaStreamType.VIDEO }
    val w = video?.width ?: 0
    val h = video?.height ?: 0
    val resolution =
        when {
            w >= 3800 || h >= 2000 -> "4K"
            w >= 1900 || h >= 1000 -> "HD"
            w > 0 -> "SD"
            else -> null
        }
    val hdr =
        video?.videoRangeType?.name?.let { r ->
            when {
                r.startsWith("DOVI") -> "DV"
                r.startsWith("HDR10_PLUS") -> "HDR10+"
                r.startsWith("HDR") || r == "HLG" -> "HDR"
                else -> null
            }
        }
    val audios = streams.filter { it.type == MediaStreamType.AUDIO }
    val atmos =
        audios.any { a ->
            listOfNotNull(a.profile, a.displayTitle, a.title).any { it.contains("atmos", true) }
        }
    val channels = audios.maxOfOrNull { it.channels ?: 0 } ?: 0
    val audio =
        when {
            atmos -> "ATMOS"
            channels >= 8 -> "7.1"
            channels >= 6 -> "5.1"
            else -> null
        }
    return StreamTags(resolution, hdr, audio)
}

/**
 * The badges a card shows, drawn over its art. [tall] = a portrait poster, where the chips stack
 * down instead of across. The New label is drawn by the card itself (it sits on the bottom edge).
 */
@Composable
internal fun BoxScope.PosterBadges(
    item: CinemaItem,
    tall: Boolean = false,
) {
    val o = LocalOverlays.current
    val tags = rememberStreamTags(item, o.needsStreams)
    val chips =
        buildList {
            if (o.resolution) tags?.resolution?.let { add(it to Color(0xFFB8912F)) }
            if (o.hdr) tags?.hdr?.let { add(it to if (it == "DV") Color(0xFF6B4BD6) else Color(0xFFC9632A)) }
            if (o.audio) tags?.audio?.let { add(it to Color(0xFF2F6FB8)) }
            if (o.rating) item.rating?.let { add(it to Color(0xFF4A4A4A)) }
        }
    val right = o.corner == OverlayCorner.TOP_RIGHT
    if (chips.isNotEmpty()) {
        val align = if (right) Alignment.TopEnd else Alignment.TopStart
        val content: @Composable () -> Unit = {
            chips.forEach { (text, colour) -> Chip(text, if (o.style == OverlayStyle.COLOUR) colour else Color.Black.copy(alpha = 0.62f)) }
        }
        if (tall) {
            Column(Modifier.align(align).padding(5.dp), verticalArrangement = Arrangement.spacedBy(3.dp), horizontalAlignment = if (right) Alignment.End else Alignment.Start) { content() }
        } else {
            Row(Modifier.align(align).padding(6.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) { content() }
        }
    }
    // The streaming chart's corner badge, opposite the chips
    if (o.top10 && item.rank != null) {
        Top10Badge(Modifier.align(if (right) Alignment.TopStart else Alignment.TopEnd))
    }
    if (o.watched && item.played) {
        Box(
            Modifier.align(Alignment.BottomEnd).padding(end = 6.dp, bottom = 8.dp).size(18.dp).background(Color.Black.copy(alpha = 0.7f), CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Text("✓", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Black)
        }
    }
}

@Composable
private fun Chip(
    text: String,
    background: Color,
) {
    Text(
        text,
        color = Color.White,
        fontSize = 9.sp,
        fontWeight = FontWeight.Black,
        letterSpacing = 0.5.sp,
        modifier = Modifier.background(background, RoundedCornerShape(3.dp)).padding(horizontal = 5.dp, vertical = 1.dp),
    )
}

/** The red "TOP 10" square tucked into a poster's corner. */
@Composable
private fun Top10Badge(modifier: Modifier) {
    Column(
        modifier.size(width = 24.dp, height = 28.dp).background(Label, RoundedCornerShape(bottomStart = 3.dp, bottomEnd = 3.dp)),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text("TOP", color = Color.White, fontSize = 6.sp, lineHeight = 7.sp, fontWeight = FontWeight.Black)
        Text("10", color = Color.White, fontSize = 11.sp, lineHeight = 12.sp, fontWeight = FontWeight.Black)
    }
}

/** Settings preview: a wide card and a poster wearing every badge that's switched on. */
@Composable
internal fun OverlayPreview(overlays: PosterOverlays) {
    val sample =
        CinemaItem(
            id = java.util.UUID(0, 1),
            kind = org.jellyfin.sdk.model.api.BaseItemKind.MOVIE,
            detailsId = java.util.UUID(0, 1),
            detailsKind = org.jellyfin.sdk.model.api.BaseItemKind.MOVIE,
            title = "Preview",
            subtitle = null,
            meta = emptyList(),
            rating = "PG-13",
            overview = "",
            backdropUrl = null,
            cardUrl = null,
            cardHasTitleArt = false,
            logoUrl = null,
            badge = "Recently Added",
            resumeMs = 0,
            progress = null,
            rank = 1,
            resolution = "4K",
            hdr = "DV",
            audio = "ATMOS",
            played = true,
        )
    val art = androidx.compose.ui.graphics.Brush.linearGradient(listOf(Color(0xFF3B4A6B), Color(0xFF1B1F2A), Color(0xFF6B3B3B)))
    androidx.compose.runtime.CompositionLocalProvider(LocalOverlays provides overlays) {
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.Bottom) {
            Box(Modifier.size(width = 224.dp, height = 126.dp).background(art, RoundedCornerShape(6.dp))) {
                Text("Wide card", color = Color.White.copy(alpha = 0.5f), fontSize = 13.sp, modifier = Modifier.align(Alignment.Center))
                if (overlays.newLabels) {
                    Text(
                        sample.badge!!,
                        color = Color.White,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.align(Alignment.BottomCenter).background(Label, RoundedCornerShape(topStart = 3.dp, topEnd = 3.dp)).padding(horizontal = 7.dp, vertical = 2.dp),
                    )
                }
                PosterBadges(sample)
            }
            Box(Modifier.size(width = 84.dp, height = 126.dp).background(art, RoundedCornerShape(4.dp))) {
                Text("Poster", color = Color.White.copy(alpha = 0.5f), fontSize = 12.sp, modifier = Modifier.align(Alignment.Center))
                PosterBadges(sample, tall = true)
            }
        }
    }
}
