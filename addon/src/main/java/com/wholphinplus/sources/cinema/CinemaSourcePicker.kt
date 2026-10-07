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

    fun forPick(
        itemId: String,
        seriesId: String?,
    ): CinemaItem? = item?.takeIf { it.id.toString() == itemId || it.detailsId.toString() == itemId || (seriesId != null && it.detailsId.toString() == seriesId) }
}

/**
 * Choosing a copy, in Cinema's look (owner, 2026-10-06: the picker "doesn't match the theme"):
 * the title's own picture, its logo, and the copies as rows that lift like cards. Fills the
 * screen at one size from the first moment, so servers answering never resize it; rows that
 * come in fade up, and the final ranking glides them into order.
 */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
internal fun CinemaSourcePicker(
    ui: PickerUi,
    row: @Composable (ExternalSource, Boolean, Modifier) -> Unit,
) {
    val art = remember(ui.itemId) { StageArt.forPick(ui.itemId, ui.seriesId) }
    val first = remember(ui.title) { FocusRequester() }
    LaunchedEffect(ui.title, ui.searching) {
        // A frame for the list to lay out, then the best copy (always the first)
        delay(50)
        runCatching { first.requestFocus() }
    }
    val enter = remember { Animatable(0f) }
    LaunchedEffect(Unit) { enter.animateTo(1f, tween(480, easing = CinemaEase)) }
    Dialog(onDismissRequest = ui.onCancel, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Box(Modifier.fillMaxSize().background(Stage)) {
            StableBackdrop(art?.backdropUrl, drift = false)
            // Darker than a billboard: the rows sit over the picture
            Box(Modifier.fillMaxSize().background(Brush.horizontalGradient(0f to Stage.copy(alpha = 0.92f), 0.55f to Stage.copy(alpha = 0.7f), 1f to Stage.copy(alpha = 0.25f))))
            Column(
                Modifier.fillMaxHeight().width(860.dp).padding(start = 48.dp, top = 40.dp).graphicsLayer {
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
                val logo = rememberArt(art)?.logoUrl() ?: art?.logoUrl
                if (logo != null) {
                    AsyncImage(model = logo, contentDescription = ui.title, contentScale = ContentScale.Fit, alignment = Alignment.CenterStart, modifier = Modifier.height(64.dp).width(320.dp))
                    Text(ui.title, color = InkDim, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                } else {
                    Text(ui.title, color = Ink, fontSize = 32.sp, fontWeight = FontWeight.ExtraBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                SearchLine(ui)
                val list = rememberLazyListState()
                val density = LocalDensity.current
                val spec = remember(density) { pivot(with(density) { 24.dp.toPx() }) }
                CompositionLocalProvider(LocalBringIntoViewSpec provides spec.gliding(list)) {
                    LazyColumn(
                        state = list,
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                        // Room for a lifted row's edge, and for the last rows to come up off the bottom
                        contentPadding = PaddingValues(start = 8.dp, end = 24.dp, top = 12.dp, bottom = 160.dp),
                        modifier = Modifier.fillMaxWidth().padding(start = 0.dp),
                    ) {
                        itemsIndexed(ui.rows, key = { _, r -> r.connectionId + r.url }) { index, r ->
                            val best = index == 0 && !ui.searching && ui.rows.size > 1
                            Box(
                                Modifier.animateItem(
                                    fadeInSpec = tween(360, easing = CinemaEase),
                                    placementSpec = spring(dampingRatio = 1f, stiffness = 110f, visibilityThreshold = IntOffset.VisibilityThreshold),
                                    fadeOutSpec = tween(200, easing = CinemaFade),
                                ),
                            ) { row(r, best, if (index == 0) Modifier.focusRequester(first) else Modifier) }
                        }
                    }
                }
            }
        }
    }
}

/** "Searching 3 servers… 1 done" with a soft moving bar, then "6 copies · best first". */
@Composable
private fun SearchLine(ui: PickerUi) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            if (ui.searching) "Looking on ${ui.serversTotal} servers… ${ui.serversDone} answered" else "${ui.rows.size} ${if (ui.rows.size == 1) "copy" else "copies"} · best first",
            color = InkDim,
            fontSize = 15.sp,
        )
        Box(Modifier.width(320.dp).height(2.dp).clip(RoundedCornerShape(1.dp)).background(Color.White.copy(alpha = if (ui.searching) 0.08f else 0f))) {
            if (ui.searching) {
                val sweep by rememberInfiniteTransition(label = "search").animateFloat(0f, 1f, infiniteRepeatable(tween(1_400, easing = CinemaFade), RepeatMode.Restart), label = "sweep")
                Box(Modifier.fillMaxHeight().width(96.dp).graphicsLayer { translationX = (sweep * (320 + 96) - 96).dp.toPx() }.background(Brush.horizontalGradient(listOf(Color.Transparent, Plus, Color.Transparent))))
            }
        }
    }
}

/** One copy: a dark glass row that lifts with a fading white edge, like the cards. */
@Composable
internal fun CinemaSourceRow(
    best: Boolean,
    server: String,
    compatible: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    badges: @Composable () -> Unit,
) {
    val shape = RoundedCornerShape(10.dp)
    Surface(
        onClick = onClick,
        shape = ClickableSurfaceDefaults.shape(shape),
        colors =
            ClickableSurfaceDefaults.colors(
                containerColor = Color.White.copy(alpha = 0.06f),
                contentColor = Ink,
                focusedContainerColor = Color(0xFF232326),
                focusedContentColor = Ink,
            ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1f),
        modifier = modifier.fillMaxWidth().glideLift(scale = 1.02f, corner = 10.dp).tapToClick(onClick),
    ) {
        Column(Modifier.padding(horizontal = 18.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(server, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (best) {
                    Text(
                        "BEST",
                        color = Color.White,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Black,
                        letterSpacing = 1.5.sp,
                        modifier = Modifier.background(Plus.copy(alpha = 0.9f), RoundedCornerShape(3.dp)).padding(horizontal = 7.dp, vertical = 2.dp),
                    )
                }
                if (compatible) Text("Server converts it", color = InkDim, fontSize = 13.sp)
            }
            badges()
        }
    }
}
