package com.wholphinplus.sources.cinema

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.BringIntoViewSpec
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.focusable
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableLongState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import kotlinx.coroutines.launch
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Border
import androidx.tv.material3.Card
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.Text
import coil3.SingletonImageLoader
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.request.allowHardware
import coil3.request.transformations
import coil3.toBitmap
import java.util.UUID
import org.jellyfin.sdk.model.api.BaseItemKind

/** Tile size: the same as a title card, so a row of tiles lines up with the rows around it. */
private val TileWidth = 208.dp

/** A row of Services or Genres tiles. */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
internal fun TileRowView(
    row: CinemaRow,
    cardSpec: BringIntoViewSpec,
    onFocused: () -> Unit,
    onClick: (PageTile) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(row.title, color = Ink, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(start = 48.dp))
        val rowState = androidx.compose.foundation.lazy.rememberLazyListState()
        ReportMotion(rowState)
        CompositionLocalProvider(LocalBringIntoViewSpec provides cardSpec.gliding(rowState)) {
            LazyRow(
                state = rowState,
                modifier = Modifier.staysInRow(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                contentPadding = PaddingValues(start = 48.dp, end = 400.dp, top = 8.dp, bottom = 8.dp),
            ) {
                items(row.tiles, key = { it.id }, contentType = { it.kind }) { tile ->
                    PageTileCard(tile, onFocused, onClick)
                }
            }
        }
    }
}

@Composable
private fun PageTileCard(
    tile: PageTile,
    onFocused: () -> Unit,
    onClick: (PageTile) -> Unit,
    width: Dp = TileWidth,
) {
    val shape = RoundedCornerShape(6.dp)
    // Kept per tile for the app's life: back from a title page opened on the tile's page (or a
    // See all on it), the tab is built again, and a new requester left Back from the page
    // landing on Continue Watching instead of the tile
    val self = remember(tile.id) { TileFocus.of(tile.id) }
    val open = {
        ReturnFocus.target = self
        onClick(tile)
    }
    Card(
        onClick = open,
        shape = CardDefaults.shape(shape),
        border = CardDefaults.border(focusedBorder = Border.None),
        scale = CardDefaults.scale(focusedScale = 1f),
        glow = CardDefaults.glow(),
        colors = CardDefaults.colors(containerColor = Color(0xFF1F1F1F)),
        modifier = Modifier.width(width).aspectRatio(16f / 9f).focusRequester(self).glideLift(onFocused = onFocused).tapToClick { open() },
    ) {
        when {
            tile.service -> ServiceFace(tile, width)
            tile.kind == "server" -> ServerFace(tile)
            else -> GenreFace(tile)
        }
    }
}

/**
 * A service: its wordmark in white on a wash of the wordmark's own colour (read from the picture,
 * so no brand colours live in the app). An ink-less wordmark (black, white) gets a plain dark tile.
 */
@Composable
private fun ServiceFace(
    tile: PageTile,
    width: Dp,
) {
    val context = LocalContext.current
    val ink = rememberInk(tile.logoUrl)
    val base = ink ?: Color(0xFF2A2A2E)
    Box(
        Modifier.fillMaxSize().background(
            Brush.linearGradient(listOf(base.darken(0.55f), base.darken(0.2f), base.darken(0.45f))),
        ),
        contentAlignment = Alignment.Center,
    ) {
        if (tile.logoUrl != null) {
            val model = remember(tile.logoUrl) { ImageRequest.Builder(context).data(tile.logoUrl).transformations(WhiteWordmark).build() }
            FittedLogo(model, height = width * 0.2f, maxWidth = width * 0.62f, alignment = Alignment.Center, evenOut = true)
        } else {
            Text(tile.name, color = Ink, fontSize = 20.sp, fontWeight = FontWeight.ExtraBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** A genre or decade: a picture under a wash of its colour, its name in the corner. */
@Composable
private fun GenreFace(tile: PageTile) {
    val context = LocalContext.current
    val hue = genreHue(tile.name)
    Box(Modifier.fillMaxSize()) {
        tile.pictureUrl?.let { AsyncImage(model = request(context, it, 416, 234), contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize()) }
        Box(Modifier.fillMaxSize().background(Brush.linearGradient(listOf(hue.copy(alpha = 0.78f), hue.copy(alpha = 0.42f), hue.darken(0.4f).copy(alpha = 0.7f)))))
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0.5f to Color.Transparent, 1f to Color.Black.copy(alpha = 0.55f))))
        // Genres in capitals; a decade as written ("2020s", "90s"), larger
        val decade = tile.kind == "decade"
        Text(
            if (decade) tile.name else tile.name.uppercase(),
            color = Color.White,
            fontSize = if (decade) 26.sp else 15.sp,
            fontWeight = FontWeight.ExtraBold,
            lineHeight = if (decade) 28.sp else 17.sp,
            maxLines = 2,
            modifier = Modifier.align(Alignment.BottomStart).padding(start = 12.dp, end = 12.dp, bottom = 10.dp),
        )
    }
}

/** Each genre keeps one colour (by its name), from a set that reads well under white text. */
private fun genreHue(name: String): Color {
    val hues = listOf(0xFFE5484D, 0xFFF76B15, 0xFF3E63DD, 0xFFD6409F, 0xFF12A594, 0xFF8E4EC6, 0xFF0090FF, 0xFFE54666, 0xFF30A46C, 0xFFAB4ABA)
    return Color(hues[Math.floorMod(name.hashCode(), hues.size)])
}

private fun Color.darken(by: Float) = Color(red * (1 - by), green * (1 - by), blue * (1 - by), alpha)

/** A wordmark's ink colour, once per picture (kept for the session). */
private val inks = mutableStateMapOf<String, Color?>()

@Composable
private fun rememberInk(url: String?): Color? {
    val context = LocalContext.current
    LaunchedEffect(url) {
        if (url == null || inks.containsKey(url)) return@LaunchedEffect
        val result = SingletonImageLoader.get(context).execute(ImageRequest.Builder(context).data(url).size(160, 160).allowHardware(false).build())
        // A pixel loop: off the main thread
        val bitmap = (result as? SuccessResult)?.image?.toBitmap()
        inks[url] = bitmap?.let { kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) { inkOf(it) } }
    }
    return url?.let { inks[it] }
}

/**
 * The wordmark's colour: the average of its saturated, visible pixels. Null when it has none to
 * speak of (a black, white or grey wordmark).
 */
private fun inkOf(bitmap: android.graphics.Bitmap): Color? {
    val w = bitmap.width
    val h = bitmap.height
    val px = IntArray(w * h)
    bitmap.getPixels(px, 0, w, 0, 0, w, h)
    var r = 0.0
    var g = 0.0
    var b = 0.0
    var n = 0.0
    var visible = 0
    for (c in px) {
        val a = (c ushr 24) / 255.0
        if (a < 0.5) continue
        visible++
        val cr = (c shr 16) and 0xFF
        val cg = (c shr 8) and 0xFF
        val cb = c and 0xFF
        val max = maxOf(cr, cg, cb)
        val sat = if (max == 0) 0.0 else (max - minOf(cr, cg, cb)).toDouble() / max
        if (sat < 0.35 || max < 60) continue
        r += cr * sat
        g += cg * sat
        b += cb * sat
        n += sat
    }
    if (visible == 0 || n < visible * 0.08) return null
    return Color((r / n).toFloat() / 255f, (g / n).toFloat() / 255f, (b / n).toFloat() / 255f)
}

/** Each tile's focus requester, by tile id ([PageTileCard]). */
private object TileFocus {
    private val map = java.util.concurrent.ConcurrentHashMap<String, androidx.compose.ui.focus.FocusRequester>()

    fun of(id: String): androidx.compose.ui.focus.FocusRequester = map.getOrPut(id) { androidx.compose.ui.focus.FocusRequester() }
}

/** Pages already loaded this session, by page and side, so going back to one is instant. */
private object PageCache {
    val data = HashMap<String, CinemaHomeData>()
}

/**
 * A service's or genre's page: its name (a service's wordmark) and a Movies | Shows switch on
 * top, then the billboard and rows like a tab. Back closes it.
 */
@Composable
internal fun CloudPageView(
    id: String,
    series: Boolean,
    onSeries: (Boolean) -> Unit,
    repo: CinemaRepository,
    onOpen: (UUID, BaseItemKind) -> Unit,
    onPlay: (UUID, Long) -> Unit,
    onClose: () -> Unit,
    rollUp: Boolean,
    lastInput: MutableLongState,
    /** A See all of one of its rows is open over it: it keeps still and leaves focus alone. */
    covered: Boolean = false,
) {
    val context = LocalContext.current
    val page = remember(id) { repo.cloudPage(id) }
    BackHandler(enabled = !covered, onBack = onClose)
    // Takes focus when it opens; built again under a See all (back from a title page opened
    // there), the grid has focus and See all gets it back on Back
    var grab by remember(id) { mutableStateOf(!covered) }
    val isCovered by androidx.compose.runtime.rememberUpdatedState(covered)
    if (page == null) {
        LaunchedEffect(Unit) { onClose() }
        return
    }
    // Only sides with a row to show (on a small library a service may have only shows)
    val sides = listOfNotNull(false.takeIf { repo.hasRows(page, false) }, true.takeIf { repo.hasRows(page, true) })
    val side = if (series in sides) series else sides.firstOrNull() ?: false
    var errors by remember(id) { mutableStateOf<String?>(null) }
    var attempt by remember(id) { mutableStateOf(0) }
    var loaded by remember(id) { mutableStateOf(PageCache.data.filterKeys { it.startsWith("$id:") }) }
    // Focus leaves the tab hidden underneath at once: until the page's rows take it (or when it
    // fails or has nothing), it stayed on the invisible tile, and the remote moved through hidden
    // cards; OK opened a title nobody could see
    val holder = remember { androidx.compose.ui.focus.FocusRequester() }
    val inPage = remember { booleanArrayOf(false) }
    LaunchedEffect(id) {
        for (i in 0 until 10) {
            androidx.compose.runtime.withFrameNanos {}
            // The page's own Play may already have it (a page seen before shows at once)
            if (isCovered || inPage[0] || runCatching { holder.requestFocus() }.getOrDefault(false)) break
        }
    }
    LaunchedEffect(id, side, attempt) {
        val key = "$id:$side"
        errors = null
        try {
            // The first rows show at once; the full page replaces them when it's in
            val d = repo.loadCloudPage(id, side) { first -> launch { if (loaded[key] == null) loaded = loaded + (key to first) } }
            PageCache.data[key] = d
            loaded = loaded + (key to d)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            timber.log.Timber.w(e, "Cinema page %s failed", id)
            if (loaded[key] == null) errors = e.message ?: "Couldn't load this page"
        }
    }
    Box(Modifier.fillMaxSize().background(Stage).onFocusChanged { inPage[0] = it.hasFocus }) {
        AnimatedContent(targetState = side, transitionSpec = { fadeIn(tween(300, easing = CinemaEase)) togetherWith fadeOut(tween(220, easing = CinemaFade)) }, label = "side") { s ->
            val d = loaded["$id:$s"]
            Box(Modifier.fillMaxSize()) {
                when {
                    d == null && errors != null -> LoadError(errors.orEmpty(), onRetry = { attempt++ }, onClassic = null, modifier = Modifier.align(Alignment.Center))
                    d == null -> Unit
                    d.rows.isEmpty() ->
                        Text(
                            "Nothing from ${page.name} in your library yet",
                            color = InkDim,
                            fontSize = 16.sp,
                            modifier = Modifier.align(Alignment.Center),
                        )
                    else -> CinemaScreen(d, "page:$id:$s", onOpen, onPlay, grabFocus = grab && !covered, rollUp = rollUp, lastInput = lastInput, onGrabbed = { grab = false })
                }
            }
        }
        // Holds focus while nothing on the page can (loading, nothing to show); Back closes the page
        Box(Modifier.size(1.dp).focusRequester(holder).focusable())
        // The page's own top bar: its name, then Movies | Shows
        val sideFocus = remember { androidx.compose.ui.focus.FocusRequester() }
        Row(
            Modifier.fillMaxWidth().padding(start = 48.dp, end = 40.dp, top = 18.dp).height(TopNavHeight - 18.dp)
                // Up from Play lands on the side being shown
                .upToSelected(sideFocus)
                // Down from Movies | Shows lands on the page's Play
                .onPreviewKeyEvent {
                    it.type == androidx.compose.ui.input.key.KeyEventType.KeyDown && it.key == androidx.compose.ui.input.key.Key.DirectionDown &&
                        DownTargets.of("page:$id:$side")?.let { f -> runCatching { f.requestFocus() }.getOrDefault(false) } == true
                },
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            if (page.logo != null) {
                val model = remember(page.logo) { ImageRequest.Builder(context).data(page.logo).transformations(WhiteWordmark).build() }
                FittedLogo(model, height = 22.dp, maxWidth = 150.dp, modifier = Modifier.padding(end = 18.dp))
            } else {
                Text(page.name, color = Ink, fontSize = 20.sp, fontWeight = FontWeight.ExtraBold, modifier = Modifier.padding(end = 18.dp))
            }
            if (sides.size > 1) {
                NavPill("Movies", selected = !side, modifier = if (!side) Modifier.focusRequester(sideFocus) else Modifier) { onSeries(false) }
                NavPill("Shows", selected = side, modifier = if (side) Modifier.focusRequester(sideFocus) else Modifier) { onSeries(true) }
            }
            Spacer(Modifier.weight(1f))
        }
    }
}
