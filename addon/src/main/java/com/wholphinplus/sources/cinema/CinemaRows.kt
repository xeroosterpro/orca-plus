package com.wholphinplus.sources.cinema

import com.wholphinplus.sources.core.cancellable
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.VisibilityThreshold
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.BringIntoViewSpec
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.layout.layout
import androidx.compose.ui.zIndex
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.foundation.layout.offset
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * A row still on its way: its name with a small spinner, and cards of the real size gently
 * pulsing, so it fills in where it stands. Not focusable: the remote passes over it until it's in.
 * The pulse and the spinner are drawn only (no recomposition per frame).
 */
@Composable
internal fun LoadingRow(row: CinemaRow) {
    val motion = androidx.compose.animation.core.rememberInfiniteTransition(label = "loading")
    val glow =
        motion.animateFloat(
            0.05f,
            0.11f,
            androidx.compose.animation.core.infiniteRepeatable(tween(900, easing = CinemaEase), androidx.compose.animation.core.RepeatMode.Reverse),
            label = "glow",
        )
    val turn = motion.animateFloat(0f, 360f, androidx.compose.animation.core.infiniteRepeatable(tween(1000, easing = androidx.compose.animation.core.LinearEasing)), label = "turn")
    fun Modifier.pulse(corner: Dp) =
        drawBehind { drawRoundRect(Color.White.copy(alpha = glow.value), cornerRadius = androidx.compose.ui.geometry.CornerRadius(corner.toPx())) }
    val captions = LocalOverlays.current.captions
    // Laid out like CinemaRowView: the same name line, gaps and card sizes
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(Modifier.padding(start = 48.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(row.title, color = Ink, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
            Box(
                Modifier.size(12.dp).drawBehind {
                    rotate(turn.value) {
                        drawArc(Plus, 0f, 270f, false, style = androidx.compose.ui.graphics.drawscope.Stroke(2.dp.toPx(), cap = androidx.compose.ui.graphics.StrokeCap.Round))
                    }
                },
            )
        }
        Row(
            Modifier.fillMaxWidth().clipToBounds().wrapContentWidth(Alignment.Start, unbounded = true).padding(start = 48.dp, top = 8.dp, bottom = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(if (row.ranked) 4.dp else 10.dp),
            verticalAlignment = Alignment.Top,
        ) {
            repeat(6) {
                if (row.ranked) {
                    Row(verticalAlignment = Alignment.Bottom) {
                        Spacer(Modifier.width(NumeralWidth).height(PosterHeight))
                        Box(Modifier.width(PosterWidth).height(PosterHeight).pulse(4.dp))
                    }
                } else {
                    Column(Modifier.width(208.dp)) {
                        Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f).pulse(6.dp))
                        // The caption's room, so the row is as tall as it will be
                        if (captions) {
                            Column(Modifier.fillMaxWidth().padding(top = 9.dp, start = 2.dp, end = 2.dp)) {
                                Text(" ", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
                                Row(Modifier.padding(top = 2.dp)) { Text(" ", fontSize = 11.sp, maxLines = 1) }
                            }
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun CinemaRowView(
    row: CinemaRow,
    state: RowState,
    cardSpec: BringIntoViewSpec,
    onItemFocused: (CinemaItem) -> Unit,
    onItemClick: (CinemaItem) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(Modifier.onPreviewKeyEvent(state::stepButtons).then(modifier).onFocusChanged { state.focused = it.hasFocus }, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        // The name and how many titles the whole row has
        Row(Modifier.padding(start = 48.dp), verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(row.title, color = Ink, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
            state.count?.takeIf { it > 0 }?.let { Text("$it titles", color = InkDim, fontSize = 13.sp, modifier = Modifier.padding(bottom = 1.dp)) }
        }
        // The controls sit in the left margin, so the first card stays where every row starts
        val actions = LocalRowActions.current
        val controls = !row.ranked && state.source != null && actions != null
        // The cards just off screen to the side are built only for the row you rest on (owner,
        // 2026-10-09: rows "don't have to load too many horizontal unless the user stops on the
        // row"): a row passed through builds its visible cards only, about 5 instead of 7
        val rested = remember { mutableStateOf(false) }
        LaunchedEffect(state) {
            snapshotFlow { state.focused }.collectLatest { here ->
                if (here) kotlinx.coroutines.delay(ROW_REST_MS)
                rested.value = here
            }
        }
        val rowState = androidx.compose.foundation.lazy.rememberLazyListState(cacheWindow = remember { RestedRowWindow(rested) })
        // The row you rest on looks up the art of the next cards ahead of you: the page's warm-up
        // covers each row's first 8, and past them every card waited for its art (up to 1.5 s)
        // before its picture even started (owner: "the posters can't keep up… even though I'm
        // moving slow")
        val artAhead = LocalArt.current
        LaunchedEffect(state, rowState) {
            snapshotFlow { if (rested.value) rowState.firstVisibleItemIndex else -1 }.collectLatest { first ->
                if (first < 0 || artAhead == null) return@collectLatest
                state.items.drop(first).take(com.wholphinplus.sources.DeviceClass.artAhead).forEach { item ->
                    item.tmdbId?.let { id -> launch { runCatching { artAhead.art(item.tmdbTv, id) }.cancellable() } }
                }
            }
        }
        ReportMotion(rowState)
        // Continue Watching after a play: the title just watched comes first, and the list kept its
        // place on the card it showed, leaving the new first card off screen. Back to its start
        // (not while the remote is in the row: focus keeps its card then).
        if (row.continueWatching) {
            val first = state.items.firstOrNull()?.key
            val seen = remember { mutableStateOf(first) }
            LaunchedEffect(first) {
                if (first == seen.value) return@LaunchedEffect
                seen.value = first
                // Glides back (the browsing glide); it used to jump there in one frame
                if (!state.focused) glideToStart(rowState)
            }
        }
        // Continue Watching's long-press menu, and the cards it took out this session
        var menuFor by remember { mutableStateOf<CinemaItem?>(null) }
        val openMenu = remember { { item: CinemaItem -> menuFor = item } }
        val menuScope = rememberCoroutineScope()
        val continueRepo = rememberContinueRepo()
        val shownItems = if (row.continueWatching) state.items.filterNot { ContinueEdits.hides(it, continueRepo.first.overlay) } else state.items
        // Left and Right move by position ([RowSteps])
        val steps = remember { RowSteps() }
        val stepKeys = (if (row.ranked) row.items else shownItems).map { it.key }
        val focusedHere: (CinemaItem) -> Unit = { steps.at = it.key; onItemFocused(it) }
        // Nearing the end asks for more: a row goes on as long as its list
        if (actions != null && !row.ranked) LoadNearEnd({ state.more(actions.load) }) { rowState.layoutInfo.visibleItemsInfo.lastOrNull()?.index to rowState.layoutInfo.totalItemsCount }
        DisposableEffect(state, rowState, controls) {
            state.reveal = { key ->
                val at = state.items.indexOfFirst { it.key == key }
                if (at >= 0) rowState.scrollToItem(at + if (controls) 1 else 0)
            }
            onDispose { state.reveal = null }
        }
        // The row reaches one card past the screen's left edge ([LeftReach]): the card before the
        // focused one is placed there, out of sight, so Left finds it like Right finds the next
        val reach = with(LocalDensity.current) { LeftReach.roundToPx() }
        val reachSpec = remember(cardSpec, reach) { cardSpec.reaching(reach.toFloat()) }
        CompositionLocalProvider(LocalBringIntoViewSpec provides reachSpec.gliding(rowState), LocalRowRested provides rested) {
            LazyRow(
                state = rowState,
                // Right after the last card (a Top 10's #10) stays there; it used to reach the top menu
                modifier =
                    Modifier
                        .layout { m, c ->
                            val p = m.measure(c.copy(minWidth = c.minWidth + reach, maxWidth = if (c.hasBoundedWidth) c.maxWidth + reach else c.maxWidth))
                            layout((p.width - reach).coerceAtLeast(0), p.height) { p.place(-reach, 0) }
                        }.staysInRow()
                        .onPreviewKeyEvent { steps.step(it, stepKeys, rowState) },
                horizontalArrangement = Arrangement.spacedBy(if (row.ranked) 4.dp else 10.dp),
                contentPadding = PaddingValues(start = (if (controls) 4.dp else 48.dp) + LeftReach, end = 400.dp, top = 8.dp, bottom = 8.dp),
                verticalAlignment = Alignment.Top,
            ) {
                if (row.ranked) {
                    items(row.items, key = { it.key }, contentType = { "top10" }) { item ->
                        Top10Card(item, item.rank ?: (row.items.indexOf(item) + 1), focusedHere, onItemClick, if (item.key == state.returnKey) Modifier.focusRequester(state.cardFocus) else Modifier)
                    }
                } else {
                    // Above the cards, so a button's label can show over the first one
                    if (controls) item(key = "controls", contentType = "controls") { Box(Modifier.zIndex(1f)) { RowControls(state, actions!!) } }
                    if (row.continueWatching) {
                        // Held OK opens its menu: Remove / Mark as watched; cards then glide closed
                        items(shownItems, key = { it.key }, contentType = { "card" }) { item ->
                            Box(Modifier.animateItem(fadeInSpec = tween(420, easing = CinemaFade), placementSpec = CardsClose, fadeOutSpec = tween(280, easing = CinemaFade))) {
                                CinemaCard(item, focusedHere, onItemClick, modifier = if (item.key == state.returnKey) Modifier.focusRequester(state.cardFocus) else Modifier, onLongClick = openMenu)
                                if (menuFor?.key == item.key) {
                                    ContinueMenu(item, onClose = { menuFor = null }) { action ->
                                        menuFor = null
                                        val shownNow = shownItems
                                        menuScope.launch { applyContinueAction(action, item, state, shownNow, continueRepo.first, continueRepo.second) }
                                    }
                                }
                            }
                        }
                    } else {
                        val items = state.items
                        items(items, key = { it.key }, contentType = { "card" }) { item ->
                            CinemaCard(item, focusedHere, onItemClick, modifier = if (item.key == state.returnKey) Modifier.focusRequester(state.cardFocus) else Modifier)
                        }
                    }
                }
            }
        }
    }
}

/** How far a card row reaches past the screen's left edge: two wide cards (a held Left outruns the glide by about that). */
private val LeftReach = 560.dp

/** [this] for a list whose viewport starts [reachPx] left of the screen: the pivot measured from the screen's edge. */
private fun BringIntoViewSpec.reaching(reachPx: Float): BringIntoViewSpec {
    val inner = this
    return object : BringIntoViewSpec {
        override fun calculateScrollDistance(
            offset: Float,
            size: Float,
            containerSize: Float,
        ): Float = inner.calculateScrollDistance(offset - reachPx, size, containerSize - reachPx)
    }
}

/**
 * Held Left along a row: a press whose card isn't on screen yet waits (owner, 2026-10-09: "going
 * reverse horizontal on a row it's not smooth and bugging out"). The glide trails the focus; going
 * right the next card is always on screen, going left it was still past the left edge, so
 * Compose's focus search laid the row out ~10 times, found nothing and lost the press: two of three
 * held Left presses, the costliest key work on the Shield. Asking that card for focus directly
 * moved it but made the glide jump back ~70 px; skipping the press is cheap, and the next repeat,
 * once the glide has brought the card in, moves as usual.
 */
private class RowSteps {
    var at: Any? = null

    fun step(
        e: androidx.compose.ui.input.key.KeyEvent,
        keys: List<Any>,
        list: androidx.compose.foundation.lazy.LazyListState,
    ): Boolean {
        if (e.type != KeyEventType.KeyDown || e.key != androidx.compose.ui.input.key.Key.DirectionLeft) return false
        // Only a held key's repeat waits: a single press always goes through
        if (e.nativeKeyEvent.repeatCount == 0) return false
        val i = keys.indexOf(at)
        if (i <= 0) return false
        val target = keys[i - 1]
        return list.layoutInfo.visibleItemsInfo.none { it.key == target }
    }
}

/** How long the remote rests on a row before the cards just off its side are built ([RestedRowWindow]). */
private const val ROW_REST_MS = 300L

/** A row's side cache: none until it's [rested] on, then about four cards each way. */
@OptIn(ExperimentalFoundationApi::class)
private class RestedRowWindow(
    private val rested: androidx.compose.runtime.State<Boolean>,
) : androidx.compose.foundation.lazy.layout.LazyLayoutCacheWindow {
    override fun androidx.compose.ui.unit.Density.calculateAheadWindow(viewport: Int): Int =
        if (rested.value) com.wholphinplus.sources.DeviceClass.rowAheadDp.dp.roundToPx() else 0

    override fun androidx.compose.ui.unit.Density.calculateBehindWindow(viewport: Int): Int =
        // As much as ahead: the focused card sits at the row's left edge, so going left the next
        // card is always off screen; with one card kept there, each Left press built one and
        // spamming Left felt slower than Right (owner, 2026-10-09)
        if (rested.value) com.wholphinplus.sources.DeviceClass.rowAheadDp.dp.roundToPx() else 0
}

/** The billboard's chart line: a red TOP 10 badge and "#2 in Movies This Week". */
@Composable
internal fun RankLine(
    rank: Int,
    label: String?,
) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Column(
            Modifier.size(20.dp).background(Label, RoundedCornerShape(2.dp)),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Text("TOP", color = Color.White, style = BadgeText.copy(fontSize = 5.5.sp, lineHeight = 6.sp))
            Text("10", color = Color.White, style = BadgeText.copy(fontSize = 9.sp, lineHeight = 9.sp))
        }
        Text(listOfNotNull("#$rank", label?.let { "in $it" }).joinToString(" "), color = Ink, fontSize = 15.sp, fontWeight = FontWeight.Bold, maxLines = 1)
    }
}

/**
 * Scrolls a row back to its first card on [CinemaGlide]: the distance from the cards' own size
 * (a lazy row only knows the ones built), the last bit by the list itself. Far along (past what a
 * glide reads as one move), the stock jump, as the page does.
 */
private suspend fun glideToStart(state: androidx.compose.foundation.lazy.LazyListState) {
    val first = state.firstVisibleItemIndex
    if (first == 0 && state.firstVisibleItemScrollOffset == 0) return
    if (first > GLIDE_BACK_MAX) return state.scrollToItem(0)
    val info = state.layoutInfo
    val step = info.visibleItemsInfo.firstOrNull()?.let { it.size + info.mainAxisItemSpacing } ?: 0
    state.animateScrollBy(-(first * step + state.firstVisibleItemScrollOffset).toFloat(), CinemaGlide)
    if (state.firstVisibleItemIndex != 0 || state.firstVisibleItemScrollOffset != 0) state.scrollToItem(0)
}

/** Cards a row glides back over to its start; further, it jumps. */
private const val GLIDE_BACK_MAX = 12

/** Continue Watching's cards closing the gap a removed one leaves: the browsing glide, no snap. */
private val CardsClose = androidx.compose.animation.core.spring(dampingRatio = 1f, stiffness = 110f, visibilityThreshold = androidx.compose.ui.unit.IntOffset.VisibilityThreshold)
