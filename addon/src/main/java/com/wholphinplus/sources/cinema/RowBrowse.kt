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
import androidx.compose.ui.focus.onFocusChanged
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
 * A row's titles as browsed: what's loaded so far and how to get more. Kept per page (outside
 * the row), so scrolling a row off screen and back, or opening it full screen, keeps them.
 */
@Stable
internal class RowState(
    row: CinemaRow,
) {
    val title = row.title

    /** How many titles the whole row has (a list's titles in the library), when known. */
    val total = row.total

    /** Whether focus is in this row: its buttons show only then. */
    var focused by mutableStateOf(false)

    val items = mutableStateListOf<CinemaItem>().apply { addAll(row.items) }
    var source by mutableStateOf(row.source)
        private set
    private var next = row.source?.next ?: 0
    private var end = row.source == null
    private var loading = false
    private val seen = row.items.mapTo(HashSet()) { it.detailsId }

    val shuffled: Boolean get() = source?.seed != null

    /**
     * The next titles, once at a time. A page that brings nothing new (a shuffled row's random
     * pages, titles already shown) is followed by another, up to three, then the row ends.
     */
    suspend fun more(load: suspend (RowSource, Int) -> RowPage) {
        val s = source ?: return
        if (loading || end) return
        loading = true
        try {
            var empty = 0
            while (true) {
                val page = load(s, next)
                if (source != s) return // shuffled meanwhile
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
            if (source == s) loading = false
        }
    }

    /** A new order from the top of the row. */
    suspend fun shuffle(load: suspend (RowSource, Int) -> RowPage) {
        val s = source?.copy(seed = kotlin.random.Random.nextLong(), next = 0) ?: return
        source = s
        loading = true
        try {
            val page = load(s, 0)
            if (source != s) return
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
            if (source == s) loading = false
        }
    }
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

/** Cards from the end at which a row asks for more: early, so browsing never waits on it. */
internal const val LOAD_AHEAD = 20

/**
 * Left of a row's first card: See all, Shuffle, Shuffle every visit. Out of the way of Up and
 * Down (they move card to card); Left from the first card reaches them. Hidden until the row has
 * focus; the focused one says what it does beside the row's name.
 */
@Composable
internal fun RowControls(
    state: RowState,
    actions: RowActions,
) {
    val scope = rememberCoroutineScope()
    val source = state.source ?: return
    val every = source.lockKey in actions.locks
    val shown by animateFloatAsState(if (state.focused) 1f else 0f, tween(220), label = "controls")
    Column(Modifier.padding(end = 4.dp, top = 2.dp).graphicsLayer {
        alpha = shown
        // Drawn with the alpha, not into a layer of its own: a new layer's first draw cost
        // 40 ms on the Shield's GPU, right as the first row takes focus (the billboard's roll)
        compositingStrategy = androidx.compose.ui.graphics.CompositingStrategy.ModulateAlpha
    }, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        RowButton(GridIcon, state.total?.let { "See all $it" } ?: "See all") { actions.seeAll(state) }
        RowButton(ShuffleIcon, "Shuffle") { scope.launch { state.shuffle(actions.load) } }
        RowButton(EveryVisitIcon, if (every) "Shuffle every visit: on" else "Shuffle every visit: off", lit = every) {
            actions.setLocked(source, !every)
            // Switched on in the row's own order: shuffle it now too
            if (!every && !state.shuffled) scope.launch { state.shuffle(actions.load) }
        }
    }
}

@Composable
private fun RowButton(
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
        modifier = Modifier.glideLift(scale = 1.1f, edge = false).tapToClick(onClick).onFocusChanged { focused = it.isFocused },
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
    val spec = remember(density) { pivot(with(density) { 24.dp.toPx() }) }
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
                val grid = androidx.compose.foundation.lazy.grid.rememberLazyGridState()
                CompositionLocalProvider(LocalBringIntoViewSpec provides spec.gliding(grid)) {
                    LazyVerticalGrid(
                        state = grid,
                        columns = GridCells.Fixed(4),
                        contentPadding = PaddingValues(start = 48.dp, end = 48.dp, top = 14.dp, bottom = 120.dp),
                        horizontalArrangement = Arrangement.spacedBy(16.dp),
                        verticalArrangement = Arrangement.spacedBy(22.dp),
                        modifier = Modifier.fillMaxSize(),
                    ) {
                        itemsIndexed(items, key = { _, it -> it.key }, contentType = { _, _ -> "card" }) { i, item ->
                            if (onNearEnd != null && i >= items.size - LOAD_AHEAD) LaunchedEffect(items.size) { onNearEnd() }
                            CinemaCard(
                                item,
                                onFocused = { focused = it },
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

/** A row opened full screen (See all): every title, loading more as you go down. */
@Composable
internal fun RowGridScreen(
    state: RowState,
    actions: RowActions,
    onOpen: (UUID, BaseItemKind) -> Unit,
) {
    TitleGrid(state.title, state.items, "Nothing here yet.", onOpen, count = state.total, onNearEnd = { state.more(actions.load) })
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
private val GridIcon = icon("Grid", "M3 3v8h8V3H3zm6 6H5V5h4v4zm-6 4v8h8v-8H3zm6 6H5v-4h4v4zm4-16v8h8V3h-8zm6 6h-4V5h4v4zm-6 4v8h8v-8h-8zm6 6h-4v-4h4v4z")
