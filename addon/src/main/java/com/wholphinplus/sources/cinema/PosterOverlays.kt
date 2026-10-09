package com.wholphinplus.sources.cinema

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.layout
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.heightIn
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
import coil3.request.allowHardware
import coil3.request.crossfade
import coil3.request.transformations
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
    val top10: Boolean = true,
    val watched: Boolean = true,
    val newLabels: Boolean = true,
    /** Where a title streams, as small service logos on its card. */
    val services: Boolean = true,
    /** How many service logos a card shows (a show's own network first). */
    val maxServices: Int = 2,
    /** The title's logo over a clean picture; off, the old art with the name baked in. */
    val titleLogos: Boolean = true,
    /** The title, release date and length under each card. */
    val captions: Boolean = true,
    /** The red watched-so-far line along the bottom. */
    val progress: Boolean = true,
    val corner: OverlayCorner = OverlayCorner.TOP_RIGHT,
    val style: OverlayStyle = OverlayStyle.MINIMAL,
) {
    /** These need each title's streams, which make the server's answers bigger. */
    val needsStreams: Boolean get() = resolution || hdr || audio

    companion object {
        /** Just the picture, its title logo, the title under it and how far you got. */
        val CLEAN = PosterOverlays(top10 = false, watched = false, newLabels = false, services = false)

        /**
         * The everyday set, and what a TV starts with: Top 10, watched, what's new, where it
         * streams, the title under the card. No quality tags, no scores on cards (owner's choice).
         */
        val STANDARD = PosterOverlays()

        /** Every tag there is. */
        val EVERYTHING = PosterOverlays(resolution = true, hdr = true, audio = true, rating = true)

        /** Defaults before 2026-10-05: tags saved then (which left defaults out) keep these. */
        private val SAVED_BEFORE = mapOf("top10" to false, "watched" to false, "captions" to false)

        /** Saved tags, read the way they were saved (see [SAVED_BEFORE]). */
        fun fromSaved(
            json: kotlinx.serialization.json.Json,
            raw: String,
        ): PosterOverlays = json.decodeFromJsonElement(serializer(), withOld(json, raw, SAVED_BEFORE))
    }
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
    private var file: java.io.File? = null
    val map = java.util.concurrent.ConcurrentHashMap<String, StreamTags>()

    /** When each title's badges were last shown, so the cache drops the ones not seen for longest. */
    private val used = java.util.concurrent.ConcurrentHashMap<String, Long>()

    /** The saved facts, read off the main thread; lookups wait for them. */
    private val ready = kotlinx.coroutines.CompletableDeferred<Unit>()
    private val gate = kotlinx.coroutines.sync.Semaphore(com.wholphinplus.sources.DeviceClass.lookupsAtOnce)

    /** Titles a batch is already fetching: their cards wait for it instead of asking alone. */
    private val inFlight = java.util.concurrent.ConcurrentHashMap<String, kotlinx.coroutines.CompletableDeferred<StreamTags?>>()
    private val io = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.IO)
    private var pending: kotlinx.coroutines.Job? = null

    /** Loads the saved facts once (in the background); called by the screens that show posters. */
    @Synchronized
    fun attach(context: android.content.Context) {
        if (file != null) return
        val app = context.applicationContext
        val f = java.io.File(app.filesDir, "cinema_streams_v3.json")
        file = f
        io.launch {
            try {
                val saved = CacheFiles.read(f, StreamTags.serializer())
                if (saved != null) {
                    saved.items.forEach { (k, v) -> map.putIfAbsent(k, v) }
                    saved.used.forEach { (k, v) -> used.putIfAbsent(k, v) }
                } else {
                    // Carried over once from the preferences it used to live in
                    val p = app.getSharedPreferences("wholphinplus_streams", android.content.Context.MODE_PRIVATE)
                    CacheFiles.legacy(p.getString(KEY, null), StreamTags.serializer()).forEach { (k, v) -> map.putIfAbsent(k, v) }
                    p.edit().clear().apply()
                }
            } finally {
                ready.complete(Unit)
            }
        }
    }

    operator fun get(id: java.util.UUID): StreamTags? = map[id.toString()]?.also { used[id.toString()] = System.currentTimeMillis() }

    /** Known facts, else asks the server once (titles with no file facts are remembered too). */
    suspend fun fetch(
        id: java.util.UUID,
        lookup: StreamLookup,
    ): StreamTags? {
        map[id.toString()]?.let { return it }
        if (file != null) ready.await()
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
            if (file != null) ready.await()
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
        val f = file ?: return
        pending?.cancel()
        pending =
            io.launch {
                kotlinx.coroutines.delay(4_000)
                Conductor.whenQuiet()
                ready.await()
                leastRecentlyUsed(map.keys, used, MAX).forEach {
                    map.remove(it)
                    used.remove(it)
                }
                CacheFiles.write(f, StreamTags.serializer(), CacheFiles.Saved(HashMap(map), HashMap(used)))
            }
    }

    private const val KEY = "streams_v3"
    private const val BATCH = 6
    private const val MAX = 6_000
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

/**
 * The Poster tags preview: a real card and Top 10 poster from your home, drawn by the rows' own
 * code, wearing every tag that's on. Tags the title doesn't have (Top 10, watched, a New label)
 * are shown as if it did, so each switch visibly does something. Redrawn as switches flip.
 */
@Composable
internal fun PosterTagsPreview(
    overlays: PosterOverlays,
    ratingPrefs: RatingPrefs,
    art: CinemaArt?,
    ratings: RatingsRepository?,
    /** Changes when Home has loaded meanwhile, so a real title replaces the stand-in. */
    sampleKey: Any? = null,
) {
    val sample = androidx.compose.runtime.remember(art, sampleKey) { previewTitle(art) ?: PREVIEW_SAMPLE }
    val shown =
        sample.copy(
            rank = sample.rank ?: 1,
            played = true,
            badge = sample.badge ?: "Recently Added",
            progress = sample.progress ?: 0.35f,
            resolution = sample.resolution ?: "4K",
            hdr = sample.hdr ?: "DV",
            audio = sample.audio ?: "ATMOS",
            rating = sample.rating ?: "PG-13",
        )
    androidx.compose.runtime.CompositionLocalProvider(
        LocalArt provides art,
        LocalOverlays provides overlays,
        LocalRatingPrefs provides ratingPrefs,
        LocalRatings provides ratings,
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(20.dp)) {
            Scaled(208.dp, 1.6f) {
                Column {
                    Box(Modifier.size(208.dp, 117.dp).clip(RoundedCornerShape(6.dp)).background(Color(0xFF1F1F1F))) { CinemaCardFace(shown, 208.dp) }
                    if (overlays.captions) CardCaption(shown)
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(18.dp), verticalAlignment = Alignment.Bottom) {
                Scaled(PosterWidth, 1.15f) {
                    Box(Modifier.size(PosterWidth, PosterHeight).clip(RoundedCornerShape(4.dp)).background(Color(0xFF1F1F1F))) { PosterFace(shown) }
                }
                Column(verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(bottom = 4.dp)) {
                    Text("Wide card and Top 10 poster", color = Ink, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
                    Text(
                        if (sample === PREVIEW_SAMPLE) "Open Home once to preview your own titles." else "${sample.title} from your home, wearing every tag that's on.",
                        color = InkDim,
                        fontSize = 12.sp,
                        lineHeight = 16.sp,
                        modifier = Modifier.width(260.dp),
                    )
                }
            }
        }
    }
}

/**
 * [content] laid out at [width] (its own height) and drawn [scale] times larger, taking up the
 * scaled size. Crisp: a canvas scale, not a bitmap.
 */
@Composable
private fun Scaled(
    width: androidx.compose.ui.unit.Dp,
    scale: Float,
    content: @Composable () -> Unit,
) {
    Box(
        Modifier.layout { measurable, _ ->
            val p = measurable.measure(androidx.compose.ui.unit.Constraints.fixedWidth(width.roundToPx()))
            val w = (p.width * scale).toInt()
            val h = (p.height * scale).toInt()
            layout(w, h) {
                // Scaled about its centre: shifted so its top-left sits at ours
                p.placeWithLayer((w - p.width) / 2, (h - p.height) / 2) {
                    scaleX = scale
                    scaleY = scale
                }
            }
        },
    ) { content() }
}

/** Stands in until Home has loaded once. */
private val PREVIEW_SAMPLE =
    CinemaItem(
        id = java.util.UUID(0, 1),
        kind = org.jellyfin.sdk.model.api.BaseItemKind.MOVIE,
        detailsId = java.util.UUID(0, 1),
        detailsKind = org.jellyfin.sdk.model.api.BaseItemKind.MOVIE,
        title = "Your title",
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
        progress = 0.35f,
    )

/**
 * Where a title streams: its services' wordmarks, small and white straight on the picture
 * (see [WhiteWordmark]), in the top corner opposite the quality badges. Nothing behind them:
 * a shade there showed as a grey band across the picture.
 */
@Composable
internal fun BoxScope.ServiceLogos(
    item: CinemaItem,
    services: List<String>,
) {
    val o = LocalOverlays.current
    if (!o.services || services.isEmpty()) return
    val context = androidx.compose.ui.platform.LocalContext.current
    val right = o.corner == OverlayCorner.TOP_LEFT
    // The Top 10 corner badge shares that corner: the logos sit beside it
    val beside = if (o.top10 && item.rank != null) 28.dp else 0.dp
    Row(
        Modifier.align(if (right) Alignment.TopEnd else Alignment.TopStart).padding(top = 8.dp, start = if (right) 8.dp else 8.dp + beside, end = if (right) 8.dp + beside else 8.dp),
        horizontalArrangement = Arrangement.spacedBy(7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        services.forEach { url ->
            val model = androidx.compose.runtime.remember(url) { coil3.request.ImageRequest.Builder(context).data(url).transformations(WhiteWordmark).build() }
            FittedLogo(model, height = 9.dp, maxWidth = 40.dp, evenOut = true)
        }
    }
}

/**
 * Turns a service's wordmark into a white one for any picture. Wordmarks are made for white
 * pages: coloured or dark ink on transparent, and some (a dark box with light letters) have
 * light parts. Ink (dark or coloured) becomes white, light parts become see-through, so a boxed
 * logo reads as a white badge with its letters cut out. A wordmark that is already all light
 * just turns white.
 */
internal object WhiteWordmark : coil3.transform.Transformation() {
    override val cacheKey: String = "white-wordmark-v1"

    override suspend fun transform(
        input: android.graphics.Bitmap,
        size: coil3.size.Size,
    ): android.graphics.Bitmap {
        val w = input.width
        val h = input.height
        val px = IntArray(w * h)
        input.getPixels(px, 0, w, 0, 0, w, h)
        val inked = IntArray(px.size)
        var ink = 0.0
        var opaque = 0.0
        for (i in px.indices) {
            val c = px[i]
            val a = c ushr 24
            if (a == 0) continue
            val r = (c shr 16) and 0xFF
            val g = (c shr 8) and 0xFF
            val b = c and 0xFF
            val max = maxOf(r, g, b)
            val light = (0.299 * r + 0.587 * g + 0.114 * b) / 255.0
            val saturation = if (max == 0) 0.0 else (max - minOf(r, g, b)).toDouble() / max
            val k = maxOf(1.0 - light, saturation).coerceIn(0.0, 1.0)
            inked[i] = ((a * k).toInt() shl 24) or 0xFFFFFF
            ink += a * k
            opaque += a
        }
        // Barely any ink: an all-light wordmark, shown whole in white instead of vanishing
        val keep = opaque > 0 && ink / opaque < 0.15
        val out = if (keep) IntArray(px.size) { i -> (px[i] and 0xFF000000.toInt()) or 0xFFFFFF } else inked
        return android.graphics.Bitmap.createBitmap(out, w, h, android.graphics.Bitmap.Config.ARGB_8888)
    }
}

/**
 * A picture link as a plain-bitmap request (see rememberCardPicture): logos arrive while rows
 * glide, and a hardware bitmap's upload held the frame on screen.
 */
@Composable
internal fun rememberPlain(
    model: Any?,
    /** Fade in over this long when it wasn't in memory (0: appear at once). */
    fade: Int = 0,
): Any? {
    val context = androidx.compose.ui.platform.LocalContext.current
    return androidx.compose.runtime.remember(model) {
        if (model is String) coil3.request.ImageRequest.Builder(context).data(model).allowHardware(false).crossfade(fade).build() else model
    }
}

/**
 * A logo drawn as large as fits [height] x [maxWidth] for its shape. Sized from the image once
 * it's loaded (a wrapping image stays at its pixel size, small on a TV).
 */
@Composable
internal fun FittedLogo(
    model: Any,
    height: androidx.compose.ui.unit.Dp,
    maxWidth: androidx.compose.ui.unit.Dp,
    modifier: Modifier = Modifier,
    alignment: Alignment = Alignment.CenterStart,
    /** Squarer logos (a short word over a swoosh) drawn taller, so all read about as large. */
    evenOut: Boolean = false,
) {
    var ratio by androidx.compose.runtime.remember(model) { androidx.compose.runtime.mutableStateOf<Float?>(null) }
    coil3.compose.AsyncImage(
        model = rememberPlain(model, CARD_FADE_MS),
        contentDescription = null,
        contentScale = androidx.compose.ui.layout.ContentScale.Fit,
        alignment = alignment,
        onState = { state ->
            if (state is coil3.compose.AsyncImagePainter.State.Success) {
                val image = state.result.image
                if (image.width > 0 && image.height > 0) ratio = image.width.toFloat() / image.height
            }
        },
        modifier =
            modifier.then(
                ratio?.let { r -> Modifier.heightIn(max = if (evenOut && r < 2.6f) height * 1.5f else height).widthIn(max = maxWidth).aspectRatio(r) }
                    ?: Modifier.size(width = maxWidth, height = height),
            ),
    )
}

/**
 * A saved settings object with [old] filled in for the keys it lacks. Settings were once saved
 * without the values that equalled the defaults of the day; now every value is saved, so a
 * later change of default only reaches TVs that never saved the setting.
 */
internal fun withOld(
    json: kotlinx.serialization.json.Json,
    raw: String,
    old: Map<String, Boolean>,
): kotlinx.serialization.json.JsonObject {
    val saved = json.parseToJsonElement(raw) as kotlinx.serialization.json.JsonObject
    return kotlinx.serialization.json.JsonObject(old.mapValues { kotlinx.serialization.json.JsonPrimitive(it.value) } + saved)
}
