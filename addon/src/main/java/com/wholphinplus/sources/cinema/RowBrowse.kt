package com.wholphinplus.sources.cinema

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.relocation.bringIntoViewResponder
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Icon
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import org.jellyfin.sdk.model.api.BaseItemKind
import timber.log.Timber
import java.util.UUID

/**
 * A row's titles as browsed: what's loaded so far and how to get more. Kept per page outside the
 * screen ([RowMemory]), so a page refresh or a trip to a title page and back keeps the titles
 * loaded since, a shuffle, and the focused card (it used to start over from the row's first page,
 * and focus fell out of the row when its card was past it).
 */
@Stable
internal class RowState(
    row: CinemaRow,
) {
    val title = row.title

    /** The row as the page last gave it; a fresh copy is reconciled against it ([refresh]). */
    private var base: CinemaRow = row

    /** How many titles the whole row has (a list's titles in the library), when known. */
    var total by mutableStateOf(row.total)
        private set

    /** Whether focus is in this row: its buttons show only then. */
    var focused by mutableStateOf(false)

    /** The buttons left of the first card, top to bottom, and which one has focus (-1: none). */
    val buttons = List(3) { FocusRequester() }
    var button = -1

    /** Where focus was in the row opened full screen, for coming back to it from a title page. */
    var gridAt = 0

    /** The card a title page was opened from ([CinemaItem.key]): focus goes back to it ([cardFocus]). */
    var returnKey by mutableStateOf<String?>(null)
    val cardFocus = FocusRequester()

    /** Scrolls the row to a card ([CinemaItem.key]) while it's on screen: back to a card past the built ones. */
    var reveal: (suspend (String) -> Unit)? = null

    /**
     * Up and Down between the buttons. Ahead of the row's own keys: the first row sends Up to
     * the billboard, which left See all out of reach there.
     */
    fun stepButtons(e: KeyEvent): Boolean {
        if (e.type != KeyEventType.KeyDown || button < 0) return false
        val to =
            when (e.key) {
                Key.DirectionUp -> button - 1
                Key.DirectionDown -> button + 1
                else -> return false
            }
        if (to !in buttons.indices) return false
        return runCatching { buttons[to].requestFocus() }.getOrDefault(false)
    }

    val items = mutableStateListOf<CinemaItem>().apply { addAll(row.items) }
    var source by mutableStateOf(row.source)
        private set
    private var next = row.source?.next ?: 0
    private var end by mutableStateOf(row.source == null)

    /**
     * How many titles to say the row has: the server's count until the last one is in, then the
     * titles really there (a repeat dropped, or one the server counts but doesn't list, read
     * "54 titles" over a grid of 53).
     */
    val count: Int? get() = total?.let { if (end) items.size else maxOf(it, items.size) }
    private var loading = false
    private val seen = row.items.mapTo(HashSet()) { it.detailsId }

    /** Bumped whenever the titles are replaced, so a load begun before is dropped. */
    private var generation = 0

    val shuffled: Boolean get() = source?.seed != null

    /**
     * A fresh copy of the row (the page reloaded, or came back from a title page): see [rowRefresh].
     * Titles already on screen keep their places, so focus and the scroll position hold.
     */
    fun refresh(row: CinemaRow) {
        if (row === base) return
        val how = rowRefresh(base, source, row)
        // Nothing loaded beyond the row's own titles: the fresh copy is all there is
        val loadedMore = items.size > base.items.size
        when {
            how == RowRefresh.KEEP -> Unit
            how == RowRefresh.RESET || !loadedMore -> adopt(row)
            else -> {
                val merged = mergedItems(base.items, items.toList(), row.items)
                if (merged != items.toList()) {
                    items.clear()
                    items.addAll(merged)
                }
                seen.clear()
                merged.mapTo(seen) { it.detailsId }
            }
        }
        base = row
        total = row.total
    }

    private fun adopt(row: CinemaRow) {
        generation++
        loading = false
        if (items.toList() != row.items) {
            items.clear()
            items.addAll(row.items)
        }
        source = row.source
        next = row.source?.next ?: 0
        end = row.source == null
        seen.clear()
        row.items.mapTo(seen) { it.detailsId }
    }

    /**
     * The next titles, once at a time. A page that brings nothing new (a shuffled row's random
     * pages, titles already shown) is followed by another, up to three, then the row ends.
     */
    suspend fun more(load: suspend (RowSource, Int) -> RowPage) {
        val s = source ?: return
        if (loading || end) return
        val g = generation
        loading = true
        try {
            var empty = 0
            while (true) {
                val page = load(s, next)
                if (source != s || generation != g) return // shuffled or replaced meanwhile
                next = page.next
                val fresh = page.items.filter { seen.add(it.detailsId) }
                items.addAll(fresh)
                Timber.i("Row %s: %d more, %d in all%s", title, fresh.size, items.size, if (page.end) ", the end" else "")
                if (page.end || (fresh.isEmpty() && ++empty >= 3)) {
                    end = true
                    return
                }
                if (fresh.isNotEmpty()) return
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.w(e, "More titles for %s failed", title)
        } finally {
            if (source == s && generation == g) loading = false
        }
    }

    /** A new order from the top of the row. */
    suspend fun shuffle(load: suspend (RowSource, Int) -> RowPage) {
        val s = source?.copy(seed = kotlin.random.Random.nextLong(), next = 0) ?: return
        source = s
        val g = ++generation
        loading = true
        try {
            val page = load(s, 0)
            if (source != s || generation != g) return
            seen.clear()
            items.clear()
            items.addAll(page.items.filter { seen.add(it.detailsId) })
            next = page.next
            end = page.end
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.w(e, "Shuffling %s failed", title)
        } finally {
            if (source == s && generation == g) loading = false
        }
    }
}

/** How a row's browsed titles meet a fresh copy of the row ([RowState.refresh]). */
internal enum class RowRefresh {
    /** Keep what's on screen (the remote shuffled it here). */
    KEEP,

    /** The fresh titles, then the ones loaded since that the fresh copy doesn't have. */
    MERGE,

    /** Start over from the fresh copy: it's another row, or a new random order. */
    RESET,
}

/**
 * [base]: the row as first given; [browsed]: where its titles come from now (a Shuffle changes the
 * seed); [fresh]: the new copy.
 */
internal fun rowRefresh(
    base: CinemaRow,
    browsed: RowSource?,
    fresh: CinemaRow,
): RowRefresh {
    val was = base.source
    val now = fresh.source
    if (was?.page != now?.page || was?.spec != now?.spec) return RowRefresh.RESET
    // Shuffled with the remote: that order stays until the row itself changes
    if (browsed?.seed != was?.seed) return RowRefresh.KEEP
    // The page brought a new random order of its own (Shuffle every visit)
    if (now?.seed != was?.seed) return RowRefresh.RESET
    return RowRefresh.MERGE
}

/**
 * The fresh first titles, then those loaded since [base] (by "load more") that aren't among them,
 * in their order. Titles the fresh copy dropped from the first page go; they can load again.
 */
internal fun mergedItems(
    base: List<CinemaItem>,
    browsed: List<CinemaItem>,
    fresh: List<CinemaItem>,
): List<CinemaItem> {
    val first = base.mapTo(HashSet()) { it.detailsId }
    val have = fresh.mapTo(HashSet()) { it.detailsId }
    return fresh + browsed.filter { it.detailsId !in first && have.add(it.detailsId) }
}

/**
 * Each page's [RowState]s (by row name), kept for the app's life like the pages themselves, so a
 * refresh or a trip to a title page and back finds them as they were.
 */
internal object RowMemory {
    private val pages = java.util.concurrent.ConcurrentHashMap<String, HashMap<String, RowState>>()

    /** [key]: the page ("tab:HOME", "page:<network>:true"). Focus marks from before are cleared. */
    fun page(key: String): HashMap<String, RowState> =
        pages.getOrPut(key) { HashMap() }.also { rows ->
            rows.values.forEach {
                it.focused = false
                it.button = -1
            }
        }

    /** Every page but [keep]'s (memory is short; they start over next time). */
    fun trim(keep: (String) -> Boolean) {
        pages.keys.filterNot(keep).forEach(pages::remove)
    }

    fun clear() = pages.clear()
}

/** What a tab's rows can do beyond their cards: load more, shuffle, open full screen, lock. */
@Stable
internal class RowActions(
    val load: suspend (RowSource, Int) -> RowPage,
    val locks: Set<String>,
    val setLocked: (RowSource, Boolean) -> Unit,
    val seeAll: (RowState) -> Unit,
)

internal val LocalRowActions = compositionLocalOf<RowActions?> { null }

/** A row card's picture height (208 dp wide, 16:9): where the row's buttons count as sitting. */
private val CARD_HEIGHT = 117.dp

/** Cards from the end at which a row asks for more: early, so browsing never waits on it. */
internal const val LOAD_AHEAD = 20

/**
 * Left of a row's first card: See all, Shuffle, Shuffle every visit. Out of the way of Up and
 * Down (they move card to card); Left from the first card reaches them. Hidden until the row has
 * focus; the focused one says what it does beside the row's name.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun RowControls(
    state: RowState,
    actions: RowActions,
) {
    val scope = rememberCoroutineScope()
    val source = state.source ?: return
    val every = source.lockKey in actions.locks
    val shown by animateFloatAsState(if (state.focused) 1f else 0f, tween(220), label = "controls")
    // The page scrolls to a focused button as to the first card: each button lower down used to
    // push the row up by its own offset (Shuffle hid the row's name, Shuffle every visit cut the
    // card tops under the billboard)
    val density = LocalDensity.current
    val asCard =
        remember(density) {
            object : androidx.compose.foundation.relocation.BringIntoViewResponder {
                override fun calculateRectForParent(localRect: androidx.compose.ui.geometry.Rect) =
                    androidx.compose.ui.geometry.Rect(localRect.left, 0f, localRect.right, with(density) { CARD_HEIGHT.toPx() })

                override suspend fun bringChildIntoView(localRect: () -> androidx.compose.ui.geometry.Rect?) = Unit
            }
        }
    Column(Modifier.bringIntoViewResponder(asCard).padding(end = 4.dp, top = 2.dp).graphicsLayer {
        alpha = shown
        // Drawn with the alpha, not into a layer of its own: a new layer's first draw cost
        // 40 ms on the Shield's GPU, right as the first row takes focus (the billboard's roll)
        compositingStrategy = androidx.compose.ui.graphics.CompositingStrategy.ModulateAlpha
    }, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        RowButton(state, 0, GridIcon, state.count?.let { "See all $it" } ?: "See all") { actions.seeAll(state) }
        RowButton(state, 1, ShuffleIcon, "Shuffle") { scope.launch { state.shuffle(actions.load) } }
        RowButton(state, 2, EveryVisitIcon, if (every) "Shuffle every visit: on" else "Shuffle every visit: off", lit = every) {
            actions.setLocked(source, !every)
            // Switched on in the row's own order: shuffle it now too
            if (!every && !state.shuffled) scope.launch { state.shuffle(actions.load) }
        }
    }
}

@Composable
private fun RowButton(
    state: RowState,
    index: Int,
    icon: ImageVector,
    label: String,
    lit: Boolean = false,
    onClick: () -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    // What it does, in a tag beside it while focused (over the first card; the row's name is
    // usually scrolled under the billboard by then)
    Box(contentAlignment = Alignment.CenterStart) {
    val f = rememberFocusFade(Color.White.copy(alpha = 0f), Ink, if (lit) Plus else InkDim.copy(alpha = 0.75f), Stage)
    Surface(
        onClick = onClick,
        modifier =
            Modifier.focusRequester(state.buttons[index]).glideLift(scale = 1.1f, edge = false).tapToClick(onClick).onFocusChanged {
                focused = it.isFocused
                if (it.isFocused) state.button = index else if (state.button == index) state.button = -1
            },
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(50)),
        colors = ClickableSurfaceDefaults.colors(containerColor = f.fill, contentColor = f.content, focusedContainerColor = f.fill, focusedContentColor = f.content),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1f),
        interactionSource = f.source,
    ) {
        Icon(icon, contentDescription = label, modifier = Modifier.padding(5.dp).size(16.dp))
    }
    if (focused) {
        // Takes no room: drawn past the button's right edge, over the first card
        Text(
            label,
            color = Stage,
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            modifier =
                Modifier
                    .layout { measurable, constraints ->
                        val p = measurable.measure(constraints.copy(minWidth = 0, maxWidth = Constraints.Infinity))
                        layout(0, 0) { p.place(34.dp.roundToPx(), -p.height / 2) }
                    }.background(Ink, RoundedCornerShape(50))
                    .padding(horizontal = 10.dp, vertical = 4.dp),
        )
    }
    }
}

/**
 * Titles in a grid, four across, with the focused one's picture behind: My List, and a row
 * opened full screen. [onNearEnd] asks for more as the last ones come into view.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun TitleGrid(
    title: String,
    items: List<CinemaItem>,
    emptyText: String,
    onOpen: (UUID, BaseItemKind) -> Unit,
    count: Int? = null,
    onNearEnd: (suspend () -> Unit)? = null,
    focusAt: Int? = null,
    onFocusedAt: (Int) -> Unit = {},
    /** On the first title, for Down from the top menu ([DownTargets]). */
    firstFocus: FocusRequester? = null,
) {
    var focused by remember { mutableStateOf<CinemaItem?>(null) }
    var shown by remember { mutableStateOf<CinemaItem?>(null) }
    LaunchedEffect(Unit) {
        snapshotFlow { focused }.collectLatest {
            delay(220)
            shown = it
        }
    }
    val density = LocalDensity.current
    // The focused row lands where the first row sits, so the row above is wholly out of sight
    val spec = remember(density) { pivot(with(density) { GRID_TOP.toPx() }) }
    Box(Modifier.fillMaxSize()) {
        StableBackdrop(shown?.backdropUrl, drift = false)
        Box(Modifier.fillMaxSize().background(Stage.copy(alpha = 0.55f)))
        Column(Modifier.fillMaxSize()) {
            Spacer(Modifier.height(TopNavHeight))
            Row(Modifier.padding(start = 48.dp, top = 22.dp, bottom = 6.dp), verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                Text(title, color = Ink, fontSize = 30.sp, fontWeight = FontWeight.ExtraBold)
                (count ?: items.size.takeIf { it > 0 })?.let { Text(if (it == 1) "1 title" else "$it titles", color = InkDim, fontSize = 16.sp, modifier = Modifier.padding(bottom = 5.dp)) }
            }
            if (items.isEmpty()) {
                Text(emptyText, color = InkDim, fontSize = 15.sp, modifier = Modifier.padding(start = 48.dp, top = 8.dp))
            } else {
                // The rows above and below are kept built, so Up and Down find their card at once
                val grid = androidx.compose.foundation.lazy.grid.rememberLazyGridState(cacheWindow = remember { androidx.compose.foundation.lazy.layout.LazyLayoutCacheWindow(ahead = 260.dp * com.wholphinplus.sources.DeviceClass.cacheScale, behind = 260.dp * com.wholphinplus.sources.DeviceClass.cacheScale) })
                if (onNearEnd != null) LoadNearEnd(onNearEnd) { grid.layoutInfo.visibleItemsInfo.lastOrNull()?.index to grid.layoutInfo.totalItemsCount }
                // Opened over a tab (See all), focus comes here: it stayed on the hidden tab's
                // button, so the remote moved nothing on screen. Back from a title page: that title.
                val start = focusAt?.coerceIn(0, items.lastIndex)
                val first = remember { FocusRequester() }
                if (start != null) {
                    LaunchedEffect(Unit) {
                        if (start > 0) grid.scrollToItem(start)
                        for (attempt in 0 until 20) {
                            androidx.compose.runtime.withFrameNanos {}
                            if (runCatching { first.requestFocus() }.getOrDefault(false)) break
                        }
                    }
                }
                val scope = rememberCoroutineScope()
                // Each built card's requester by index, the focused index, and the column a move
                // Up or Down is to keep (-1: none). Held Up used to drift from column 1 to 3: the
                // focus search picked whichever card it built first in the row above
                val cards = remember { HashMap<Int, FocusRequester>() }
                val at = remember { intArrayOf(-1, -1) }
                val titles by androidx.compose.runtime.rememberUpdatedState(items.size)
                val vertical =
                    remember(grid) {
                        Modifier.onPreviewKeyEvent { e -> e.type == KeyEventType.KeyDown && gridStep(e.key, grid, cards, at, titles) }
                    }
                // Room under the last row so the list ends on a row: it stopped wherever the last
                // row ran out, and the row at the top slid half under the title (cut card tops)
                val top = with(density) { GRID_TOP.roundToPx() }
                val bottom by remember(grid, top) {
                    androidx.compose.runtime.derivedStateOf {
                        val info = grid.layoutInfo
                        val card = info.visibleItemsInfo.firstOrNull()?.size?.height ?: return@derivedStateOf null
                        val pitch = card + info.mainAxisItemSpacing
                        val room = info.viewportSize.height - top - card
                        if (room < 0 || pitch <= 0) null else room - (room / pitch) * pitch
                    }
                }
                CompositionLocalProvider(LocalBringIntoViewSpec provides spec.gliding(grid)) {
                    LazyVerticalGrid(
                        state = grid,
                        columns = GridCells.Fixed(GRID_COLUMNS),
                        contentPadding = PaddingValues(start = 48.dp, end = 48.dp, top = GRID_TOP, bottom = bottom?.let { with(density) { it.toDp() } } ?: 120.dp),
                        horizontalArrangement = Arrangement.spacedBy(16.dp),
                        verticalArrangement = Arrangement.spacedBy(22.dp),
                        modifier = Modifier.fillMaxSize().then(vertical),
                    ) {
                        itemsIndexed(items, key = { _, it -> it.key }, contentType = { _, _ -> "card" }) { i, item ->
                            val self = remember { FocusRequester() }
                            androidx.compose.runtime.DisposableEffect(i, self) {
                                cards[i] = self
                                onDispose { if (cards[i] === self) cards.remove(i) }
                            }
                            Box(Modifier.focusRequester(self).then(if (i == start) Modifier.focusRequester(first) else Modifier).then(if (i == 0 && firstFocus != null) Modifier.focusRequester(firstFocus) else Modifier)) {
                                CinemaCard(
                                    item,
                                    onFocused = {
                                        focused = it
                                        onFocusedAt(i)
                                        at[0] = i
                                        // Landed by the focus search in another column: on to the right one
                                        val want = at[1]
                                        at[1] = -1
                                        if (want >= 0 && i % GRID_COLUMNS != want) {
                                            val fix = (i - i % GRID_COLUMNS + want).coerceAtMost(titles - 1)
                                            if (fix != i) cards[fix]?.let { f -> scope.launch { runCatching { f.requestFocus() } } }
                                        }
                                    },
                                    onClick = {
                                        DetailsPreview.put(it)
                                        onOpen(it.detailsId, it.detailsKind)
                                    },
                                    width = 204.dp,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

private const val GRID_COLUMNS = 4

/** Space above a grid's first row; a focused row is brought to this spot. */
private val GRID_TOP = 14.dp

/**
 * Up or Down in a [TitleGrid] ([at]: the focused index and the column to keep). Lands in the same
 * column; while the grid glides, a press waits until the focused title is at least half on
 * screen (holding the remote ran focus ahead of the glide, off screen, with no ring in sight).
 * Returns whether the press was handled here; if not, the focus search moves and the card it
 * lands on corrects the column.
 */
private fun gridStep(
    key: Key,
    grid: androidx.compose.foundation.lazy.grid.LazyGridState,
    cards: Map<Int, FocusRequester>,
    at: IntArray,
    count: Int,
): Boolean {
    val up =
        when (key) {
            Key.DirectionUp -> true
            Key.DirectionDown -> false
            Key.DirectionLeft, Key.DirectionRight -> {
                at[1] = -1
                return false
            }
            else -> return false
        }
    val i = at[0]
    if (i !in 0 until count) return false
    if (grid.isScrollInProgress) {
        val info = grid.layoutInfo
        val mid = info.visibleItemsInfo.firstOrNull { it.index == i }?.let { it.offset.y + it.size.height / 2 }
        if (mid == null || mid < info.viewportStartOffset || mid > info.viewportEndOffset) return true
    }
    var to = if (up) i - GRID_COLUMNS else i + GRID_COLUMNS
    // The top row: Up goes on to the menu
    if (to < 0) return false
    if (to >= count) {
        // Into a shorter last row: its last title; on the last row, stay
        if (i / GRID_COLUMNS < (count - 1) / GRID_COLUMNS) to = count - 1 else return true
    }
    at[1] = i % GRID_COLUMNS
    return cards[to]?.let { runCatching { it.requestFocus() }.getOrDefault(false) } == true
}

/**
 * Asks for more while the last shown item is within [LOAD_AHEAD] of the end, for as long as the
 * list is on screen. It used to be an effect on each card near the end: holding the remote down
 * scrolled that card away mid-load, the load was cancelled and nothing asked again (a See all
 * grid stopped at 80 of 157). One load at a time; after it, the position is checked again.
 */
@Composable
internal fun LoadNearEnd(
    load: suspend () -> Unit,
    position: () -> Pair<Int?, Int>,
) {
    val latest by androidx.compose.runtime.rememberUpdatedState(load)
    LaunchedEffect(Unit) {
        snapshotFlow(position).collect { (last, total) ->
            if (last != null && total > 0 && last >= total - LOAD_AHEAD) latest()
        }
    }
}

/** A row opened full screen (See all): every title, loading more as you go down. */
@Composable
internal fun RowGridScreen(
    state: RowState,
    actions: RowActions,
    onOpen: (UUID, BaseItemKind) -> Unit,
) {
    TitleGrid(
        state.title,
        state.items,
        "Nothing here yet.",
        onOpen,
        count = state.count,
        onNearEnd = { state.more(actions.load) },
        focusAt = state.gridAt,
        onFocusedAt = { state.gridAt = it },
    )
}

/** The row opened full screen, kept across a trip to a title page and back. */
internal object OpenGrid {
    var state: RowState? = null
}

private fun icon(
    name: String,
    path: String,
): ImageVector =
    ImageVector
        .Builder(name, 24.dp, 24.dp, 24f, 24f)
        .addPath(PathParser().parsePathString(path).toNodes(), fill = SolidColor(Color.White))
        .build()

// Material icons' shapes (the app ships only the core set, which lacks these)
// Material's "autorenew": comes round again, every visit
private val EveryVisitIcon = icon("EveryVisit", "M12 6v3l4-4-4-4v3c-4.42 0-8 3.58-8 8 0 1.57.46 3.03 1.24 4.26L6.7 14.8c-.45-.83-.7-1.79-.7-2.8 0-3.31 2.69-6 6-6zm6.76 1.74L17.3 9.2c.44.84.7 1.79.7 2.8 0 3.31-2.69 6-6 6v-3l-4 4 4 4v-3c4.42 0 8-3.58 8-8 0-1.57-.46-3.03-1.24-4.26z")
private val ShuffleIcon = icon("Shuffle", "M10.59 9.17L5.41 4 4 5.41l5.17 5.17 1.42-1.41zM14.5 4l2.04 2.04L4 18.59 5.41 20 17.96 7.46 20 9.5V4h-5.5zm.33 9.41l-1.41 1.41 3.13 3.13L14.5 20H20v-5.5l-2.04 2.04-3.13-3.13z")
internal val GridIcon = icon("Grid", "M3 3v8h8V3H3zm6 6H5V5h4v4zm-6 4v8h8v-8H3zm6 6H5v-4h4v4zm4-16v8h8V3h-8zm6 6h-4V5h4v4zm-6 4v8h8v-8h-8zm6 6h-4v-4h4v4z")
