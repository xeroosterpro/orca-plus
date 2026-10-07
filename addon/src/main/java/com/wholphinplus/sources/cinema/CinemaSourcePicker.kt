package com.wholphinplus.sources.cinema

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.VisibilityThreshold
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import com.wholphinplus.sources.PickerUi
import com.wholphinplus.sources.core.ExternalSource
import kotlinx.coroutines.delay

/**
 * The art of the title last shown on a Cinema screen (billboard or title page), so the source
 * picker can stand on it. Matched by id: anything else gets the plain stage.
 */
internal object StageArt {
    @Volatile private var item: CinemaItem? = null

    fun set(i: CinemaItem) {
        item = i
    }

    /** The last title's backdrop, for previews (subtitle style). */
    fun backdrop(): String? = item?.backdropUrl

    fun forPick(
        itemId: String,
        seriesId: String?,
    ): CinemaItem? = item?.takeIf { it.id.toString() == itemId || it.detailsId.toString() == itemId || (seriesId != null && it.detailsId.toString() == seriesId) }
}

/**
 * Choosing a copy, in Cinema's look (owner, 2026-10-06: the picker "doesn't match the theme"):
 * the title's own picture, its logo and facts, and the copies as rows that read left to right:
 * the quality stamp, where it is and what's in it, its size. Fills the screen at one size from the
 * first moment: servers still looking hold shimmering places, real rows fade up into them, and
 * the final ranking glides them into order.
 */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
internal fun CinemaSourcePicker(ui: PickerUi) {
    // Opened from Wholphin's player screen, outside Orca+'s screens: bring the title art along
    // (TMDB logos and backdrops), or a title the server has no art for shows a bare stage
    val context = androidx.compose.ui.platform.LocalContext.current
    val cinemaArt = remember { dagger.hilt.android.EntryPointAccessors.fromApplication(context.applicationContext, com.wholphinplus.sources.ui.SourcesEntryPoint::class.java).cinemaArt() }
    CompositionLocalProvider(LocalArt provides (LocalArt.current ?: cinemaArt)) { PickerScreen(ui) }
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun PickerScreen(ui: PickerUi) {
    val art = remember(ui.itemId) { StageArt.forPick(ui.itemId, ui.seriesId) }
    val first = remember(ui.title) { FocusRequester() }
    val list = rememberLazyListState()
    LaunchedEffect(ui.title, ui.searching) {
        // The final ranking reorders the rows under the focused one, and a lazy list keeps that
        // row where it was: the better copies ended up scrolled away above it. Back to the top,
        // then the best copy (always the first) takes focus once it's laid out.
        if (!ui.searching) list.scrollToItem(0)
        for (attempt in 0 until 20) {
            androidx.compose.runtime.withFrameNanos {}
            if (runCatching { first.requestFocus() }.getOrDefault(false)) break
        }
    }
    val enter = remember { Animatable(0f) }
    LaunchedEffect(Unit) { enter.animateTo(1f, tween(480, easing = CinemaEase)) }
    Dialog(onDismissRequest = ui.onCancel, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Box(Modifier.fillMaxSize().background(Stage)) {
            // The server's backdrop, else TMDB's clean one (as the billboard shows it)
            val titleArt = rememberArt(art)
            StableBackdrop(art?.backdropUrl ?: titleArt?.cleanBackdrop?.let { "https://image.tmdb.org/t/p/w1280$it" }, drift = false)
            // Darker than a billboard: the rows sit over the picture
            Box(Modifier.fillMaxSize().background(Brush.horizontalGradient(0f to Stage.copy(alpha = 0.94f), 0.5f to Stage.copy(alpha = 0.78f), 1f to Stage.copy(alpha = 0.2f))))
            Column(
                Modifier.fillMaxHeight().width(900.dp).padding(start = 48.dp, top = 40.dp).graphicsLayer {
                    alpha = enter.value
                    translationY = (1f - enter.value) * 20.dp.toPx()
                },
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("+", color = Plus, fontSize = 15.sp, fontWeight = FontWeight.Black)
                    Spacer(Modifier.width(5.dp))
                    Text("CHOOSE A COPY", color = InkDim, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 3.sp)
                }
                val logo = titleArt?.logoUrl() ?: art?.logoUrl
                if (logo != null) {
                    AsyncImage(model = logo, contentDescription = ui.title, contentScale = ContentScale.Fit, alignment = Alignment.CenterStart, modifier = Modifier.height(72.dp).width(340.dp))
                } else {
                    Text(ui.title, color = Ink, fontSize = 32.sp, fontWeight = FontWeight.ExtraBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                // The title's facts (an episode says which), or just the name under a logo
                val facts = art?.let { listOfNotNull(it.subtitle) + it.meta }.orEmpty()
                Text(
                    if (logo != null && facts.isEmpty()) ui.title else facts.joinToString("  ·  "),
                    color = Ink.copy(alpha = 0.8f),
                    fontSize = 15.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                SearchLine(ui)
                val density = LocalDensity.current
                val spec = remember(density) { pivot(with(density) { 24.dp.toPx() }) }
                // Servers still looking hold a place each (a few at most)
                val waiting = if (ui.searching) (ui.serversTotal - ui.serversDone).coerceIn(0, 3) else 0
                CompositionLocalProvider(LocalBringIntoViewSpec provides spec.gliding(list)) {
                    LazyColumn(
                        state = list,
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                        // Room for a lifted row's edge, and for the last rows to come up off the bottom
                        contentPadding = PaddingValues(start = 8.dp, end = 24.dp, top = 14.dp, bottom = 160.dp),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        val arrive = tween<Float>(360, easing = CinemaEase)
                        val glide = spring(dampingRatio = 1f, stiffness = 110f, visibilityThreshold = IntOffset.VisibilityThreshold)
                        val leave = tween<Float>(200, easing = CinemaFade)
                        itemsIndexed(ui.rows, key = { _, r -> r.connectionId + r.url }) { index, r ->
                            Box(Modifier.animateItem(fadeInSpec = arrive, placementSpec = glide, fadeOutSpec = leave)) {
                                CopyRow(
                                    r,
                                    best = index == 0 && !ui.searching && ui.rows.size > 1,
                                    onClick = { ui.onSelect(r) },
                                    modifier = if (index == 0) Modifier.focusRequester(first) else Modifier,
                                )
                            }
                        }
                        items(waiting, key = { "waiting$it" }) {
                            Box(Modifier.animateItem(fadeInSpec = arrive, placementSpec = glide, fadeOutSpec = leave)) { WaitingRow() }
                        }
                    }
                }
            }
        }
    }
}

/** "Looking on 3 servers… 1 answered" with a soft moving bar, then "6 copies · best first". */
@Composable
private fun SearchLine(ui: PickerUi) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            if (ui.searching) "Looking on ${ui.serversTotal} ${if (ui.serversTotal == 1) "server" else "servers"}…  ${ui.serversDone} answered" else "${ui.rows.size} ${if (ui.rows.size == 1) "copy" else "copies"}  ·  best first",
            color = InkDim,
            fontSize = 14.sp,
        )
        Box(Modifier.width(320.dp).height(2.dp).clip(RoundedCornerShape(1.dp)).background(Color.White.copy(alpha = if (ui.searching) 0.08f else 0f))) {
            if (ui.searching) {
                val sweep by rememberInfiniteTransition(label = "search").animateFloat(0f, 1f, infiniteRepeatable(tween(1_400, easing = CinemaFade), RepeatMode.Restart), label = "sweep")
                Box(Modifier.fillMaxHeight().width(96.dp).graphicsLayer { translationX = (sweep * (320 + 96) - 96).dp.toPx() }.background(Brush.horizontalGradient(listOf(Color.Transparent, Plus, Color.Transparent))))
            }
        }
    }
}

private val RowShape = RoundedCornerShape(10.dp)
private val RowHeight = 78.dp

/**
 * One copy, read left to right: the quality stamp (4K, and its HDR under it), where it is with
 * what's in it, then its size. A dark glass row that lifts with a fading edge, like the cards.
 */
@Composable
private fun CopyRow(
    r: ExternalSource,
    best: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val f = rememberFocusFade(Color.White.copy(alpha = 0.06f), Color(0xFF26262A), Ink, Ink)
    val badges = remember(r) { com.wholphinplus.sources.ui.badgesFor(r).map { it.text } }
    val hdr =
        when {
            "DV" in badges -> "DOLBY VISION"
            "HDR10+" in badges -> "HDR10+"
            "HDR10" in badges || "HDR" in badges -> "HDR10"
            "HLG" in badges -> "HLG"
            else -> null
        }
    // The rest of what's in it, on one line: "WEB-DL · HEVC · DD+ 5.1 · Atmos"
    val details =
        remember(r) {
            val rest = badges.filterNot { it == r.quality || it in setOf("DV", "HDR10+", "HDR10", "HDR", "HLG") }
            val joined = mutableListOf<String>()
            rest.forEach { t -> if (Regex("""\d\.\d""").matches(t) && joined.isNotEmpty()) joined[joined.lastIndex] += " $t" else joined += t }
            (if (r.compatible) listOf("Server converts it") else emptyList()) + joined
        }
    Surface(
        onClick = onClick,
        shape = ClickableSurfaceDefaults.shape(RowShape),
        colors = ClickableSurfaceDefaults.colors(containerColor = f.fill, contentColor = f.content, focusedContainerColor = f.fill, focusedContentColor = f.content),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1f),
        interactionSource = f.source,
        modifier = modifier.fillMaxWidth().height(RowHeight).glideLift(scale = 1.02f, corner = 10.dp).tapToClick(onClick),
    ) {
        Row(Modifier.fillMaxSize().padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.width(118.dp)) {
                Text(
                    r.quality.takeIf { it != "?" } ?: "—",
                    fontSize = if (r.quality == "4K") 28.sp else 22.sp,
                    fontWeight = FontWeight.Black,
                    letterSpacing = (-0.5).sp,
                    maxLines = 1,
                )
                Text(hdr ?: "SDR", color = if (hdr != null) Ink.copy(alpha = 0.85f) else InkDim.copy(alpha = 0.7f), fontSize = 9.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.5.sp, maxLines = 1)
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                    val own = r.connectionId == com.wholphinplus.sources.SourceHook.JELLYFIN_ROW
                    Text(if (own) "Your server" else r.serverLabel, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                    if (best) {
                        Text(
                            "BEST MATCH",
                            color = Color.White,
                            fontSize = 9.sp,
                            fontWeight = FontWeight.Black,
                            letterSpacing = 1.5.sp,
                            modifier = Modifier.background(Plus.copy(alpha = 0.9f), RoundedCornerShape(3.dp)).padding(horizontal = 7.dp, vertical = 3.dp),
                        )
                    }
                }
                Text(details.joinToString("  ·  "), color = InkDim, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Column(horizontalAlignment = Alignment.End, modifier = Modifier.padding(start = 16.dp)) {
                Text(r.size, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, style = androidx.compose.ui.text.TextStyle(fontFeatureSettings = "tnum"))
                Text(r.container.uppercase(), color = InkDim, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp, maxLines = 1)
            }
        }
    }
}

/** A server still looking: the row's shape, breathing softly, until its copies arrive. */
@Composable
private fun WaitingRow() {
    val breath by rememberInfiniteTransition(label = "waiting").animateFloat(0.03f, 0.08f, infiniteRepeatable(tween(1_100, easing = CinemaFade), RepeatMode.Reverse), label = "breath")
    Box(Modifier.fillMaxWidth().height(RowHeight).graphicsLayer { alpha = 1f }.background(Color.White.copy(alpha = breath), RowShape))
}

/**
 * Behind the subtitle style preview (Wholphin's page, via a hook): the last title you looked at,
 * so the preview is on real Orca+ art; Wholphin's stock photo ([fallback]) until there is one.
 */
@Composable
fun SubtitlePreviewBackdrop(fallback: @Composable () -> Unit) {
    val url = remember { StageArt.backdrop() } ?: return fallback()
    val context = androidx.compose.ui.platform.LocalContext.current
    AsyncImage(model = request(context, url), contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
}
