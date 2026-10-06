package com.wholphinplus.sources.cinema

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.blur
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.lerp
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.foundation.layout.offset
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Border
import androidx.tv.material3.Card
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Icon
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import com.wholphinplus.sources.ui.SourcesEntryPoint
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.delay
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import org.jellyfin.sdk.model.api.BaseItemKind
import org.jellyfin.sdk.model.api.CollectionType
import java.util.UUID

/** Where Cinema mode's top menu can go; Wholphin maps these to its own screens. */
sealed interface CinemaNav {
    data class Library(
        val id: UUID,
        val kind: BaseItemKind,
        val collectionType: CollectionType?,
    ) : CinemaNav

    data object MyList : CinemaNav

    data object Search : CinemaNav

    data object Settings : CinemaNav
}

/** Is Cinema mode on? Read by Wholphin's navigation to show Cinema screens. */
@Composable
fun cinemaModeOn(): Boolean {
    val context = LocalContext.current
    val hook = remember { EntryPointAccessors.fromApplication(context.applicationContext, SourcesEntryPoint::class.java).sourceHook() }
    return hook.store.cinemaMode.collectAsState().value
}

@Composable
fun CinemaHome(
    onOpen: (UUID, BaseItemKind) -> Unit,
    onPlay: (UUID, Long) -> Unit,
    onNavigate: (CinemaNav) -> Unit,
    modifier: Modifier = Modifier,
) {
    @Suppress("NAME_SHADOWING") val onOpen = guarded(onOpen)
    @Suppress("NAME_SHADOWING") val onPlay = guarded(onPlay)
    val context = LocalContext.current
    val entry = remember { EntryPointAccessors.fromApplication(context.applicationContext, SourcesEntryPoint::class.java) }
    val hook = remember { entry.sourceHook() }
    val art = remember { entry.cinemaArt() }
    val repo = remember { CinemaRepository(hook, hook.collections) }
    DisposableEffect(Unit) { onDispose { art.save() } }
    remember { StreamCache.attach(context) }

    // The tab you were on survives a trip to a details page and back
    var tab by rememberSaveable { mutableStateOf(CinemaTab.HOME) }
    // Each tab shows its last load instantly and refreshes quietly when stale
    val pages = remember { mutableStateMapOf<CinemaTab, CinemaHomeData>().apply { putAll(TabCache.data) } }
    // Per tab: a failed Shows load must not put its error on Home. [attempt] reloads on Try again.
    val errors = remember { mutableStateMapOf<CinemaTab, String>() }
    var attempt by remember { mutableIntStateOf(0) }
    var grabFocus by remember { mutableStateOf(true) }
    var refocus by remember { mutableStateOf(false) }
    val rollUp by hook.store.cinemaRollUp.collectAsState()
    val overlays by hook.store.overlays.collectAsState()
    val ratingPrefs by hook.store.ratingPrefs.collectAsState()
    val ratings = remember { entry.ratings() }
    // Lists and charts that were just matched (first start, the 6-hourly refresh) show at once
    val listsChanged by hook.collections.changed.collectAsState()
    // Per tab: the [listsChanged] its page was loaded under; a newer one may bring rows it lacks
    val loadedUnder = remember { mutableMapOf<CinemaTab, Int>() }
    LaunchedEffect(tab, attempt, listsChanged) {
        if (tab != CinemaTab.MY_LIST && pages[tab] != null && System.currentTimeMillis() - (TabCache.at[tab] ?: 0L) < 2 * 60_000) return@LaunchedEffect
        // Warm the title art and badge facts for what's on screen first (badges: a few requests
        // per row, batched by the server, not one per card)
        // The first two rows (on screen) at once; rows further down once the remote is still
        fun warm(d: CinemaHomeData) {
            d.rows.forEachIndexed { i, row ->
                launch {
                    if (i >= ON_SCREEN_ROWS) Conductor.whenQuiet()
                    row.items.take(8).forEach { item -> item.tmdbId?.let { id -> launch { art.art(item.tmdbTv, id) } } }
                    if (overlays.needsStreams) {
                        val ids = row.items.filter { item -> item.kind != BaseItemKind.SERIES }.map { item -> item.id }
                        if (i < ON_SCREEN_ROWS) StreamCache.prefetch(ids, repo::streamTagsBatch) else Conductor.later { StreamCache.prefetch(ids, repo::streamTagsBatch) }
                    }
                }
            }
        }
        // A first visit shows the quick rows at once; the genre rows join when they arrive
        var partial = false
        val onFirst = { first: CinemaHomeData ->
            launch {
                if (pages[tab] == null) {
                    pages[tab] = first
                    partial = true
                }
                warm(first)
            }
            Unit
        }
        suspend fun loadTab(): CinemaHomeData =
            when (tab) {
                CinemaTab.HOME -> repo.load(RowsPage.HOME, onFirst)
                CinemaTab.SHOWS -> repo.load(RowsPage.SHOWS, onFirst)
                CinemaTab.MOVIES -> repo.load(RowsPage.MOVIES, onFirst)
                CinemaTab.NEW_POPULAR -> repo.load(RowsPage.NEW_POPULAR, onFirst)
                CinemaTab.MY_LIST -> CinemaHomeData(emptyList(), listOf(CinemaRow("My List", repo.myList())), null, null)
            }
        errors.remove(tab)
        // Nothing to show yet (the TV woke before its network): try again quietly a few times
        // before showing an error. Leaving the tab cancels the load; that's not a failure.
        var tries = 0
        while (true) {
            val failure =
                try {
                    val page = loadTab()
                    TabCache.data[tab] = page
                    TabCache.at[tab] = System.currentTimeMillis()
                    // A page already showing keeps its rows (no shuffle under the remote) unless
                    // rows just matched in the background gave it more to show (a first start)
                    val gained = loadedUnder[tab] != listsChanged && page.richerThan(pages[tab])
                    if (pages[tab] == null || partial || tab == CinemaTab.MY_LIST || gained) {
                        pages[tab] = page
                        loadedUnder[tab] = listsChanged
                    }
                    warm(page)
                    null
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    timber.log.Timber.w(e, "Cinema %s load failed", tab.name)
                    e
                }
            if (failure == null || pages[tab] != null) break
            if (++tries >= LOAD_TRIES) {
                errors[tab] = failure.message?.takeIf { it.isNotBlank() } ?: "Couldn't load your library"
                break
            }
            delay(1_500L * tries)
        }
    }
    // Once Home is up, quietly load Shows and Movies so switching tabs is instant
    LaunchedEffect(pages[CinemaTab.HOME] != null) {
        if (pages[CinemaTab.HOME] == null) return@LaunchedEffect
        delay(1_500)
        listOf(CinemaTab.SHOWS to RowsPage.SHOWS, CinemaTab.MOVIES to RowsPage.MOVIES).forEach { (t, rowsPage) ->
            if (pages[t] == null) {
                // Off the critical path: only while the remote is still, so browsing stays smooth
                try {
                    val page = Conductor.later { repo.load(rowsPage) }
                    TabCache.data[t] = page
                    TabCache.at[t] = System.currentTimeMillis()
                    if (pages[t] == null) pages[t] = page
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (_: Exception) {
                    // Loads normally when the tab is opened
                }
            }
        }
    }
    BackHandler(enabled = tab != CinemaTab.HOME) { tab = CinemaTab.HOME }
    // A service's or genre's page, opened from its tile (kept across a trip to a title page)
    var openPage by rememberSaveable { mutableStateOf<String?>(null) }
    var pageSeries by rememberSaveable { mutableStateOf(false) }
    val onTile =
        remember {
            { t: PageTile ->
                if (NavGuard.allow()) {
                    // From Shows a page opens on its shows; elsewhere on its movies
                    pageSeries = tab == CinemaTab.SHOWS
                    openPage = t.id
                }
            }
        }

    // When the remote was last used; read only by the billboard's rotation (no recomposition)
    val lastInput = remember { mutableLongStateOf(android.os.SystemClock.uptimeMillis()) }
    Box(
        modifier.fillMaxSize().background(Stage).onPreviewKeyEvent {
            lastInput.longValue = android.os.SystemClock.uptimeMillis()
            Conductor.touch()
            false
        },
    ) {
        // A page from a tile takes the screen: the tab underneath must not take focus meanwhile
        if (openPage == null) {
            CompositionLocalProvider(LocalArt provides art, LocalOverlays provides overlays, LocalStreamLookup provides StreamLookup(repo::streamTagsOf), LocalRatingPrefs provides ratingPrefs, LocalRatings provides ratings) {
                AnimatedContent(
                    targetState = tab,
                    transitionSpec = { fadeIn(tween(360, delayMillis = 80, easing = CinemaEase)) togetherWith fadeOut(tween(180)) },
                    label = "tab",
                ) { t ->
                    val d = pages[t]
                    val error = errors[t]
                    Box(Modifier.fillMaxSize()) {
                        when {
                            d == null && error != null ->
                                LoadError(error, onRetry = { attempt++ }, onClassic = { hook.store.setCinemaMode(false) }, Modifier.align(Alignment.Center))
                            // The logo only shows while the app starts; other tabs just fade in
                            d == null -> if (t == CinemaTab.HOME) Wordmark(Modifier.align(Alignment.Center), size = 34)
                            t == CinemaTab.MY_LIST -> MyListScreen(d.rows.firstOrNull()?.items.orEmpty(), onOpen)
                            // A page with no rows switched on (or nothing in them yet) says how to fill it
                            d.rows.isEmpty() -> EmptyPage(t, Modifier.align(Alignment.Center))
                            else -> {
                                val focusPlay = grabFocus && t == CinemaTab.HOME || refocus
                                CinemaScreen(d, onOpen, onPlay, focusPlay, rollUp, lastInput, onTile)
                                LaunchedEffect(Unit) {
                                grabFocus = false
                                refocus = false
                            }
                            }
                        }
                    }
                }
            }
            TopNav(tab, onTab = { tab = it }, onNavigate)
        }
        openPage?.let { id ->
            CompositionLocalProvider(LocalArt provides art, LocalOverlays provides overlays, LocalStreamLookup provides StreamLookup(repo::streamTagsOf), LocalRatingPrefs provides ratingPrefs, LocalRatings provides ratings) {
                CloudPageView(
                    id,
                    pageSeries,
                    { pageSeries = it },
                    repo,
                    onOpen,
                    onPlay,
                    onClose = {
                        openPage = null
                        // Back on the tab: Play takes focus again
                        refocus = true
                    },
                    rollUp = rollUp,
                    lastInput = lastInput,
                )
            }
        }
    }
}

/** A page with no rows yet: where to pick them. */
@Composable
private fun EmptyPage(
    tab: CinemaTab,
    modifier: Modifier = Modifier,
) {
    Column(modifier.padding(horizontal = 96.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("Nothing on ${tab.label} yet", color = Ink, fontSize = 28.sp, fontWeight = FontWeight.ExtraBold)
        Text(
            "Pick its rows in Settings → Home & Look → Rows: trending charts, streaming Top 10s, curated lists and your own library.",
            color = InkDim,
            fontSize = 16.sp,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )
    }
}

/** A page that couldn't load: why, Try again (focused), and a way back to Wholphin's home. */
@Composable
internal fun LoadError(
    message: String,
    onRetry: () -> Unit,
    onClassic: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    val retry = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { retry.requestFocus() } }
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Text("Couldn't load this page", color = Ink, fontSize = 22.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(6.dp))
        Text(message, color = InkDim, fontSize = 14.sp, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 560.dp))
        Spacer(Modifier.height(18.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            HeroButton("Try again", Icons.Filled.Refresh, primary = true, modifier = Modifier.focusRequester(retry), onClick = onRetry)
            onClassic?.let { HeroButton("Use the classic home", Icons.Filled.Settings, primary = false, onClick = it) }
        }
    }
}

/** Quiet attempts before a page with nothing to show reports an error. */
internal const val LOAD_TRIES = 3

/** Cinema mode's top-menu pages. They switch in place, like a streaming app's tabs. */
enum class CinemaTab(
    val label: String,
) {
    HOME("Home"),
    SHOWS("Shows"),
    MOVIES("Movies"),
    NEW_POPULAR("New & Popular"),
    MY_LIST("My List"),
}

internal val TopNavHeight = 54.dp

// The billboard panel's heights (full, buttons hidden, rolled up) and the gap below it. The rows
// sit at RowsTopRolled and slide down by the panel's extra height (see CinemaScreen).
private val PanelTop = 16.dp
private val PanelFull = 262.dp
private val PanelNoButtons = 210.dp
private val PanelRolled = 72.dp
private val GapFull = 18.dp
private val GapRolled = 8.dp
private val RowsTopRolled = TopNavHeight + PanelTop + PanelRolled + GapRolled

/** After this long without a key press the billboard stops rotating. */
private const val IDLE_MS = 120_000L

/**
 * Focus state, kept out of the rows: cards only write it, so moving the remote recomposes the
 * top panel and backdrop but never the rows themselves.
 */
@Stable
private class HomeFocus {
    var billboard by mutableStateOf(true)
    var focused by mutableStateOf<CinemaItem?>(null)

    /** The row the focused card is in; the first row keeps the billboard full size. */
    var row by mutableIntStateOf(0)

    fun onCard(
        item: CinemaItem,
        rowIndex: Int,
    ) {
        billboard = false
        focused = item
        row = rowIndex
    }

    /** A Services or Genres tile: the billboard keeps the last title shown. */
    fun onTile(rowIndex: Int) {
        billboard = false
        row = rowIndex
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun CinemaScreen(
    data: CinemaHomeData,
    onOpen: (UUID, BaseItemKind) -> Unit,
    onPlay: (UUID, Long) -> Unit,
    grabFocus: Boolean,
    rollUp: Boolean,
    lastInput: androidx.compose.runtime.MutableLongState,
    onTile: (PageTile) -> Unit = {},
) {
    val context = LocalContext.current
    val focus = remember { HomeFocus() }
    // 0 = full billboard, 1 = rolled up to a quarter while browsing rows (if the setting is on).
    // Read only in layout/draw, so the animation never recomposes the screen.
    // The first row (Continue Watching) still shows the full billboard for the focused card;
    // it rolls up from the second row down
    // Derived so moving between rows only recomposes when "rolled" actually flips, not on every row
    val rolled by remember(rollUp) { derivedStateOf { rollUp && !focus.billboard && focus.row >= 1 } }
    val roll = animateFloatAsState(if (rolled) 1f else 0f, tween(320, easing = CinemaEase), label = "roll")
    // 1 while the buttons are hidden (browsing rows): the panel drops their space so the first
    // row moves up instead of leaving a gap
    val noButtons = animateFloatAsState(if (focus.billboard) 0f else 1f, tween(320, easing = CinemaEase), label = "noButtons")
    var featuredIndex by remember { mutableIntStateOf(0) }
    val featured = data.featured.getOrNull(featuredIndex % data.featured.size.coerceAtLeast(1))
    val playFocus = remember { FocusRequester() }
    val columnState = rememberLazyListState()
    val scope = rememberCoroutineScope()

    // What the top shows. While you move along a row it waits for you to settle (220 ms), so
    // fast scrolling stays fluid instead of reloading art on every press.
    var shown by remember { mutableStateOf(featured) }
    LaunchedEffect(featured) {
        snapshotFlow { if (focus.billboard) featured else focus.focused ?: featured }.collectLatest { target ->
            if (target?.key != shown?.key) {
                if (!focus.billboard) {
                    // Start fetching the picture at once, wait out the settle delay, then give it
                    // a little longer, so text and picture change together
                    coroutineScope {
                        val picture = async { preloadBackdrop(context, target?.backdropUrl, 520) }
                        delay(220)
                        picture.await()
                    }
                }
                shown = target
            }
        }
    }

    LaunchedEffect(Unit) { if (grabFocus) runCatching { playFocus.requestFocus() } }
    LaunchedEffect(focus.billboard, featuredIndex, data.featured.size) {
        if (focus.billboard && data.featured.size > 1) {
            delay(10_000)
            // Nobody has touched the remote for 2 minutes: hold this title, so the screen comes
            // to rest (nothing redrawn), until the next key press
            if (android.os.SystemClock.uptimeMillis() - lastInput.longValue > IDLE_MS) {
                val since = lastInput.longValue
                snapshotFlow { lastInput.longValue }.first { it != since }
            }
            // Next picture ready first, so the title and its picture swap together
            preloadBackdrop(context, data.featured[(featuredIndex + 1) % data.featured.size].backdropUrl, 3_000)
            featuredIndex = (featuredIndex + 1) % data.featured.size
        }
    }
    // Back, or Up from the first row: bring the buttons back first. They aren't on screen while
    // browsing, so they can only take focus a frame after the billboard returns (otherwise Up
    // skips past them to the top menu and the billboard stays rolled up)
    val toBillboard = remember(focus, scope, playFocus, columnState) {
        {
        focus.billboard = true
        scope.launch {
            // Retry each frame until Play is attached and actually takes focus (up to ~0.5 s)
            for (attempt in 0 until 30) {
                withFrameNanos {}
                if (runCatching { playFocus.requestFocus() }.getOrDefault(false)) break
            }
            columnState.animateScrollToItem(0)
        }
        Unit
        }
    }
    BackHandler(enabled = !focus.billboard) { toBillboard() }
    // Remembered: a new modifier each pass would recompose the first row on every key press
    val upToBillboard =
        remember(focus, toBillboard) {
            Modifier.onPreviewKeyEvent {
                if (it.type == KeyEventType.KeyDown && it.key == Key.DirectionUp && !focus.billboard) {
                    toBillboard()
                    true
                } else {
                    false
                }
            }
        }

    val onCardClick =
        remember(onOpen) {
            { item: CinemaItem ->
                DetailsPreview.put(item)
                onOpen(item.detailsId, item.detailsKind)
            }
        }
    val density = LocalDensity.current
    val rowSpec = remember(density) { pivot(with(density) { 40.dp.toPx() }) }
    val cardSpec = remember(density) { pivot(with(density) { 48.dp.toPx() }) }
    // Top 10 rows land the poster after its number, so the number stays on screen
    val topSpec = remember(density) { pivot(with(density) { (48.dp + NumeralWidth).toPx() }) }

    Box(Modifier.fillMaxSize()) {
        // The server's backdrop, else TMDB's clean picture (some servers have none for a title)
        val shownArt = rememberArt(shown)
        StableBackdrop(shown?.backdropUrl ?: shownArt?.cleanBackdrop?.let { "https://image.tmdb.org/t/p/w1280$it" }, drift = focus.billboard)
        // Rolled up, the rows sit higher over the picture: darken behind them so titles stay legible
        Box(
            Modifier.fillMaxSize().graphicsLayer { alpha = roll.value }.background(
                Brush.verticalGradient(0.12f to Color.Transparent, 0.4f to Stage.copy(alpha = 0.78f), 1f to Stage),
            ),
        )
        Column(Modifier.fillMaxSize()) {
            Spacer(Modifier.height(TopNavHeight))
            InfoPanel(
                item = shown,
                billboard = focus.billboard,
                roll = { roll.value },
                noButtons = { noButtons.value },
                playFocus = playFocus,
                onPlay = { shown?.let { onPlay(it.id, it.resumeMs) } },
                onMoreInfo = {
                    shown?.let {
                        DetailsPreview.put(it)
                        onOpen(it.detailsId, it.detailsKind)
                    }
                },
                onButtonsFocused = { focus.billboard = true },
            )
        }
        // The rows keep one fixed layout (where they sit when rolled up) and slide down by the
        // panel's extra height. Animating the panel's height used to re-measure the whole list of
        // rows on every frame of a roll: 5-6 dropped frames per roll on the emulator.
        CompositionLocalProvider(LocalBringIntoViewSpec provides rowSpec) {
            LazyColumn(
                state = columnState,
                verticalArrangement = Arrangement.spacedBy(16.dp),
                contentPadding = PaddingValues(bottom = 220.dp),
                modifier =
                    Modifier.fillMaxSize().padding(top = RowsTopRolled).graphicsLayer {
                        val panel = lerp(lerp(PanelFull, PanelNoButtons, noButtons.value), PanelRolled, roll.value)
                        val gap = lerp(GapFull, GapRolled, roll.value)
                        translationY = (panel + gap - PanelRolled - GapRolled).toPx()
                    },
            ) {
                itemsIndexed(data.rows, key = { _, r -> r.title }, contentType = { _, r -> if (r.tiles.isNotEmpty()) "tiles" else "row" }) { i, row ->
                    if (row.tiles.isNotEmpty()) {
                        val onTileFocused = remember(focus, i) { { focus.onTile(i) } }
                        TileRowView(row, cardSpec, onTileFocused, onTile, if (i == 0) upToBillboard else Modifier)
                    } else {
                        val onCard = remember(focus, i) { { item: CinemaItem -> focus.onCard(item, i) } }
                        CinemaRowView(row, if (row.ranked) topSpec else cardSpec, onCard, onCardClick, if (i == 0) upToBillboard else Modifier)
                    }
                }
            }
        }
    }
}

@Composable
private fun TopNav(
    tab: CinemaTab,
    onTab: (CinemaTab) -> Unit,
    onNavigate: (CinemaNav) -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().padding(start = 48.dp, end = 40.dp, top = 18.dp).height(TopNavHeight - 18.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Wordmark(Modifier.padding(end = 18.dp))
        CinemaTab.entries.forEach { t -> NavPill(t.label, selected = t == tab) { onTab(t) } }
        Spacer(Modifier.weight(1f))
        NavIcon(Icons.Filled.Search, "Search") { onNavigate(CinemaNav.Search) }
        NavIcon(Icons.Filled.Settings, "Settings") { onNavigate(CinemaNav.Settings) }
    }
}

@Composable
internal fun NavPill(
    text: String,
    selected: Boolean = false,
    onClick: () -> Unit,
) {
    Surface(
        onClick = onClick,
        modifier = Modifier.tapToClick(onClick),
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(50)),
        colors =
            ClickableSurfaceDefaults.colors(
                containerColor = if (selected) Color.White.copy(alpha = 0.16f) else Color.Transparent,
                contentColor = if (selected) Ink else InkDim,
                focusedContainerColor = Ink,
                focusedContentColor = Stage,
            ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.04f),
    ) {
        Text(text, fontSize = 14.sp, fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium, modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp))
    }
}

@Composable
private fun NavIcon(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
) {
    Surface(
        onClick = onClick,
        modifier = Modifier.tapToClick(onClick),
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(50)),
        colors = ClickableSurfaceDefaults.colors(containerColor = Color.Transparent, contentColor = Ink, focusedContainerColor = Ink, focusedContentColor = Stage),
    ) {
        Icon(icon, contentDescription = label, modifier = Modifier.padding(7.dp).size(20.dp))
    }
}

/** Height eased between [full] and [rolled] by [roll] (0..1), in the layout pass only. */
private fun Modifier.rollHeight(
    full: Dp,
    rolled: Dp,
    roll: () -> Float,
): Modifier = animatedHeight { lerp(full, rolled, roll()) }

/**
 * A height read in the layout pass only, so animating it never recomposes. Clips its content,
 * allowing [slack] past the left, right and bottom edges (a focused button's zoom).
 */
private fun Modifier.animatedHeight(
    slack: Dp = 0.dp,
    height: () -> Dp,
): Modifier =
    (if (slack == 0.dp) clipToBounds() else drawWithContent {
        val s = slack.toPx()
        clipRect(-s, 0f, size.width + s, size.height + s) { this@drawWithContent.drawContent() }
    }).layout { measurable, constraints ->
        val h = height().roundToPx()
        val placeable = measurable.measure(constraints.copy(minHeight = h, maxHeight = h))
        layout(placeable.width, h) { placeable.place(0, 0) }
    }

/**
 * Fixed-height panel: the focused title changing or the buttons fading never change its size,
 * so the rows below don't move. Only rolling up (the setting) shrinks it, to a quarter: a small
 * logo and the episode or Top 10 line stay, the rest fades out.
 */
@Composable
private fun InfoPanel(
    item: CinemaItem?,
    billboard: Boolean,
    roll: () -> Float,
    noButtons: () -> Float,
    playFocus: FocusRequester,
    onPlay: () -> Unit,
    onMoreInfo: () -> Unit,
    onButtonsFocused: () -> Unit,
) {
    // Full: details + buttons (262). Buttons hidden: details only (210). Rolled up: a quarter (72).
    Box(Modifier.padding(start = 48.dp, top = PanelTop).animatedHeight(slack = 12.dp) { lerp(lerp(PanelFull, PanelNoButtons, noButtons()), PanelRolled, roll()) }.widthIn(max = 480.dp)) {
        AnimatedContent(
            targetState = item,
            transitionSpec = { fadeIn(tween(380, delayMillis = 90, easing = CinemaEase)) togetherWith fadeOut(tween(160)) },
            contentKey = { it?.key },
            label = "info",
        ) { shownItem ->
            Column {
                if (shownItem == null) return@Column
                val logo = rememberArt(shownItem)?.logoUrl() ?: shownItem.logoUrl
                // Kind tag above the logo; it folds away with its gap when the billboard rolls
                // up, so the logo and the chart line fit the rolled-up panel
                Box(Modifier.rollHeight(26.dp, 0.dp, roll), contentAlignment = Alignment.TopStart) { KindTag(shownItem.kind) }
                Box(Modifier.rollHeight(70.dp, 44.dp, roll).widthIn(max = 340.dp), contentAlignment = Alignment.BottomStart) {
                    // A logo that arrives a moment late crossfades over the title text
                    Crossfade(targetState = logo, animationSpec = tween(300, easing = CinemaEase), label = "logo") { l ->
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomStart) {
                            if (l != null) {
                                AsyncImage(model = l, contentDescription = shownItem.title, contentScale = ContentScale.Fit, alignment = Alignment.BottomStart, modifier = Modifier.fillMaxSize())
                            } else {
                                Text(shownItem.title, color = Ink, fontSize = 34.sp, fontWeight = FontWeight.ExtraBold, maxLines = 2, overflow = TextOverflow.Ellipsis, lineHeight = 36.sp)
                            }
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
                // One fixed line: the episode, else the title's Top 10 place, else blank
                Box(Modifier.height(20.dp), contentAlignment = Alignment.CenterStart) {
                    val rank = shownItem.rank
                    when {
                        shownItem.subtitle != null ->
                            Text(shownItem.subtitle, color = Ink, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        rank != null -> RankLine(rank, shownItem.rankLabel)
                    }
                }
                Spacer(Modifier.height(8.dp))
                Column(Modifier.graphicsLayer { alpha = (1f - roll() * 2f).coerceIn(0f, 1f) }, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    MetaLine(Meta(shownItem.meta, shownItem.rating))
                    Text(shownItem.overview, color = Ink.copy(alpha = 0.86f), fontSize = 14.sp, lineHeight = 20.sp, minLines = 3, maxLines = 3, overflow = TextOverflow.Ellipsis)
                }
            }
        }
        AnimatedVisibility(
            visible = billboard,
            // Waits for the panel to finish growing back (320 ms), so the buttons never fade in
            // over the description while there's no room for them yet
            enter = fadeIn(tween(220, delayMillis = 240, easing = CinemaEase)),
            exit = fadeOut(tween(180)),
            modifier = Modifier.align(Alignment.BottomStart),
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                HeroButton("Play", Icons.Filled.PlayArrow, primary = true, modifier = Modifier.focusRequester(playFocus), onFocused = onButtonsFocused, onClick = onPlay)
                HeroButton("More Info", Icons.Filled.Info, primary = false, onFocused = onButtonsFocused, onClick = onMoreInfo)
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun CinemaRowView(
    row: CinemaRow,
    cardSpec: BringIntoViewSpec,
    onItemFocused: (CinemaItem) -> Unit,
    onItemClick: (CinemaItem) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(row.title, color = Ink, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(start = 48.dp))
        CompositionLocalProvider(LocalBringIntoViewSpec provides cardSpec) {
            LazyRow(
                horizontalArrangement = Arrangement.spacedBy(if (row.ranked) 4.dp else 10.dp),
                contentPadding = PaddingValues(start = 48.dp, end = 400.dp, top = 8.dp, bottom = 8.dp),
            ) {
                if (row.ranked) {
                    items(row.items, key = { it.key }, contentType = { "top10" }) { item ->
                        Top10Card(item, item.rank ?: (row.items.indexOf(item) + 1), onItemFocused, onItemClick)
                    }
                } else {
                    items(row.items, key = { it.key }, contentType = { "card" }) { item ->
                        CinemaCard(item, onItemFocused, onItemClick)
                    }
                }
            }
        }
    }
}

/** The billboard's chart line: a red TOP 10 badge and "#2 in Movies This Week". */
@Composable
private fun RankLine(
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

private val BadgeText =
    TextStyle(
        fontWeight = FontWeight.Black,
        platformStyle = PlatformTextStyle(includeFontPadding = false),
        lineHeightStyle = LineHeightStyle(LineHeightStyle.Alignment.Center, LineHeightStyle.Trim.Both),
    )

private val NumeralWidth = 84.dp
internal val PosterWidth = 112.dp
internal val PosterHeight = 168.dp

/**
 * A Top 10 tile: a big outlined number with the poster tucked over its right edge, the way the
 * streaming charts row looks.
 */
@Composable
private fun Top10Card(
    item: CinemaItem,
    rank: Int,
    onFocused: (CinemaItem) -> Unit,
    onClick: (CinemaItem) -> Unit,
) {
    val context = LocalContext.current
    val density = LocalDensity.current
    val shape = RoundedCornerShape(4.dp)
    val numeral =
        remember(density) {
            TextStyle(
                fontSize = 136.sp,
                lineHeight = 136.sp,
                fontWeight = FontWeight.Black,
                letterSpacing = (-14).sp,
                platformStyle = PlatformTextStyle(includeFontPadding = false),
                lineHeightStyle = LineHeightStyle(LineHeightStyle.Alignment.Bottom, LineHeightStyle.Trim.Both),
            )
        }
    val stroke = remember(density) { Stroke(width = with(density) { 2.5.dp.toPx() }) }
    Row(verticalAlignment = Alignment.Bottom) {
        Box(Modifier.width(if (rank >= 10) NumeralWidth + 56.dp else NumeralWidth).height(PosterHeight), contentAlignment = Alignment.BottomEnd) {
            // Filled in stage black, outlined in grey, nudged under the poster
            Text("$rank", color = Stage, style = numeral, maxLines = 1, softWrap = false, modifier = Modifier.offset(x = 16.dp, y = 6.dp))
            Text("$rank", color = Color(0xFF8A8A8A), style = numeral.copy(drawStyle = stroke), maxLines = 1, softWrap = false, modifier = Modifier.offset(x = 16.dp, y = 6.dp))
        }
        Card(
            onClick = { onClick(item) },
            shape = CardDefaults.shape(shape),
            border = CardDefaults.border(focusedBorder = Border(BorderStroke(3.dp, Ink), shape = shape)),
            scale = CardDefaults.scale(focusedScale = 1.08f),
            glow = CardDefaults.glow(),
            colors = CardDefaults.colors(containerColor = Color(0xFF1F1F1F)),
            modifier = Modifier.width(PosterWidth).height(PosterHeight).onFocusChanged { if (it.isFocused) onFocused(item) }.tapToClick { onClick(item) },
        ) {
            PosterFace(item)
        }
    }
}

/** A Top 10 poster's picture and tags; shared with the settings preview. */
@Composable
internal fun PosterFace(item: CinemaItem) {
    val context = LocalContext.current
    Box(Modifier.fillMaxSize()) {
        val url = item.posterUrl ?: item.cardUrl
        if (url != null) {
            AsyncImage(model = request(context, url, 224, 336), contentDescription = item.title, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        }
        if (item.posterUrl == null) {
            Text(item.title, color = Ink, fontSize = 13.sp, fontWeight = FontWeight.ExtraBold, maxLines = 3, overflow = TextOverflow.Ellipsis, modifier = Modifier.align(Alignment.BottomStart).padding(8.dp))
        }
        if (LocalOverlays.current.progress) item.progress?.let { p -> ProgressBar(p, Modifier.align(Alignment.BottomStart)) }
        PosterBadges(item, tall = true)
    }
}

@Composable
internal fun CinemaCard(
    item: CinemaItem,
    onFocused: (CinemaItem) -> Unit,
    onClick: (CinemaItem) -> Unit,
    width: Dp = 208.dp,
) {
    val shape = RoundedCornerShape(6.dp)
    Column(Modifier.width(width)) {
        Card(
            onClick = { onClick(item) },
            shape = CardDefaults.shape(shape),
            border = CardDefaults.border(focusedBorder = Border(BorderStroke(3.dp, Ink), shape = shape)),
            scale = CardDefaults.scale(focusedScale = 1.08f),
            glow = CardDefaults.glow(),
            colors = CardDefaults.colors(containerColor = Color(0xFF1F1F1F)),
            modifier = Modifier.width(width).aspectRatio(16f / 9f).onFocusChanged { if (it.isFocused) onFocused(item) }.tapToClick { onClick(item) },
        ) {
            CinemaCardFace(item, width)
        }
        if (LocalOverlays.current.captions) CardCaption(item)
    }
}

/** "Apr 24, 2019", in the device's language. */
private val CAPTION_DATE = java.time.format.DateTimeFormatter.ofPattern("MMM d, yyyy")

/** Under a card (Poster tags → Title and date): the name, then its date and length. */
@Composable
internal fun CardCaption(item: CinemaItem) {
    Column(Modifier.fillMaxWidth().padding(top = 9.dp, start = 2.dp, end = 2.dp)) {
        Text(item.title, color = Ink, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Row(Modifier.fillMaxWidth().padding(top = 2.dp), verticalAlignment = Alignment.CenterVertically) {
            // The full release date from TMDB when the server only knows the year
            val released = rememberArt(item)?.released?.takeIf { item.kind != BaseItemKind.EPISODE }
            val date = released?.let { runCatching { CAPTION_DATE.format(java.time.LocalDate.parse(it)) }.getOrNull() } ?: item.captionDate.orEmpty()
            Text(date, color = InkDim, fontSize = 11.sp, maxLines = 1, modifier = Modifier.weight(1f))
            item.captionLength?.let { Text(it, color = InkDim, fontSize = 11.sp, maxLines = 1) }
        }
    }
}

/**
 * A wide card's picture and every tag on it (Settings → Orca+ → Poster tags). Shared by the
 * rows and the settings preview, so the preview is exactly what the rows draw.
 */
@Composable
internal fun CinemaCardFace(
    item: CinemaItem,
    width: Dp,
) {
    val context = LocalContext.current
    val o = LocalOverlays.current
    val art = rememberArt(item)
    val logo = art?.logoUrl() ?: item.logoUrl
    // A title with a logo: its clean picture with the logo laid over the bottom corner, the same
    // on every card (art with the name baked in puts it anywhere, at any size). Without one, or
    // with title logos off: art with the name baked in, else the picture with the name on it
    val clean = if (logo != null && o.titleLogos) item.cleanCardUrl ?: art?.cleanCardUrl() else null
    val titled = if (clean == null) art?.cardUrl() else null
    val url = clean ?: titled ?: item.cardUrl
    val drawName = clean != null || (titled == null && !item.cardHasTitleArt)
    Box(Modifier.fillMaxSize()) {
        if (url != null) {
            // Decoded at card size: small, fast, and cached for the next visit
            AsyncImage(model = request(context, url, 416, 234), contentDescription = item.title, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        } else {
            // No wide art on the server or TMDB: the poster on a blurred, enlarged copy of
            // itself, or a plain title card, instead of an empty grey box
            NoWideArt(item.posterUrl, context)
        }
        if (drawName) {
            Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0.4f to Color.Transparent, 1f to Color.Black.copy(alpha = 0.8f))))
            val bottom = if (item.badge != null && o.newLabels) 22.dp else 10.dp
            if (logo != null && clean != null) {
                FittedLogo(
                    logo,
                    height = width * 0.23f,
                    // Up to 60% of the card: the small score in the opposite corner still clears it
                    maxWidth = width * 0.6f,
                    alignment = Alignment.BottomStart,
                    modifier = Modifier.align(Alignment.BottomStart).padding(start = 10.dp, bottom = bottom),
                )
            } else {
                Text(
                    item.title,
                    color = Ink,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.ExtraBold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.align(Alignment.BottomStart).padding(start = 10.dp, end = 10.dp, bottom = bottom),
                )
            }
        }
        item.badge?.takeIf { o.newLabels }?.let {
            Text(
                it,
                color = Color.White,
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.align(Alignment.BottomCenter).background(Label, RoundedCornerShape(topStart = 3.dp, topEnd = 3.dp)).padding(horizontal = 7.dp, vertical = 2.dp),
            )
        }
        if (o.progress) item.progress?.let { p -> ProgressBar(p, Modifier.align(Alignment.BottomStart)) }
        PosterBadges(item)
        ServiceLogos(item, art?.serviceUrls().orEmpty().take(o.maxServices))
        CardRatings(item)
    }
}

/** A wide card's picture for a title that only has a poster, or nothing at all. */
@Composable
private fun NoWideArt(
    poster: String?,
    context: android.content.Context,
) {
    if (poster != null) {
        Box(Modifier.fillMaxSize()) {
            AsyncImage(
                model = request(context, poster, 224, 336),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier =
                    Modifier.fillMaxSize().graphicsLayer {
                        scaleX = 1.3f
                        scaleY = 1.3f
                    }.blur(18.dp),
            )
            // Blur needs Android 12; on older TVs this dims the enlarged copy instead
            Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.45f)))
            AsyncImage(
                model = request(context, poster, 224, 336),
                contentDescription = null,
                contentScale = ContentScale.Fit,
                alignment = Alignment.CenterEnd,
                modifier = Modifier.fillMaxSize().padding(vertical = 8.dp, horizontal = 10.dp),
            )
        }
    } else {
        Box(Modifier.fillMaxSize().background(Brush.linearGradient(listOf(Color(0xFF2B2734), Color(0xFF141318))))) {
            Text("+", color = Plus.copy(alpha = 0.35f), fontSize = 44.sp, fontWeight = FontWeight.Black, modifier = Modifier.align(Alignment.TopEnd).padding(end = 12.dp))
        }
    }
}

/**
 * What a card already knows about a title, handed to its Cinema page so the page draws at once
 * (art, logo, overview, Play) while the rest loads. Episodes aren't handed over: their card
 * opens the series.
 */
internal object DetailsPreview {
    private val map = java.util.concurrent.ConcurrentHashMap<UUID, CinemaItem>()

    fun put(item: CinemaItem) {
        if (item.id == item.detailsId) map[item.id] = item
    }

    operator fun get(id: UUID): CinemaItem? = map[id]
}

/** Titles to suggest before you search: what's on your home, billboard first. */
internal fun homeSuggestions(): List<CinemaItem> {
    val home = TabCache.data[CinemaTab.HOME] ?: return emptyList()
    return (home.featured + home.rows.flatMap { it.items })
        .filter { it.kind != BaseItemKind.EPISODE && (it.backdropUrl != null || it.cardUrl != null) }
        .distinctBy { it.detailsId }
        .take(18)
}

/**
 * A real title from your home for the Poster tags preview: one with a logo and streaming
 * services if there is one (so those tags show), else any title with art; null before Home
 * has loaded.
 */
internal fun previewTitle(art: CinemaArt?): CinemaItem? {
    val all = TabCache.data[CinemaTab.HOME]?.rows?.flatMap { it.items }.orEmpty().filter { it.kind != BaseItemKind.EPISODE && it.tmdbId != null }
    fun known(i: CinemaItem) = art?.cached(i.tmdbTv, i.tmdbId!!)
    return all.firstOrNull { known(it)?.let { a -> a.logo != null && a.services.isNotEmpty() } == true }
        ?: all.firstOrNull { known(it)?.logo != null }
        ?: all.firstOrNull { it.cardUrl != null }
}

/**
 * Loads Home ahead of time (the welcome tour does, so Home is there the moment it ends) and the
 * title art of its first rows, so previews can show a real title.
 */
internal suspend fun preloadHome(
    repo: CinemaRepository,
    art: CinemaArt,
) {
    val d = repo.load(RowsPage.HOME)
    TabCache.data[CinemaTab.HOME] = d
    TabCache.at[CinemaTab.HOME] = System.currentTimeMillis()
    kotlinx.coroutines.coroutineScope {
        d.rows.take(3).flatMap { it.items.take(6) }.filter { it.tmdbId != null && it.kind != BaseItemKind.EPISODE }.forEach { i ->
            launch { runCatching { art.art(i.tmdbTv, i.tmdbId!!) } }
        }
    }
}

/** Each tab's last page, kept for the life of the app so returning to it is instant. */
private object TabCache {
    val data = java.util.concurrent.ConcurrentHashMap<CinemaTab, CinemaHomeData>()
    val at = java.util.concurrent.ConcurrentHashMap<CinemaTab, Long>()
}

/** Rows that are on screen when a page opens; their art and badges load first. */
private const val ON_SCREEN_ROWS = 2

/** What MemoryTrim may drop: every tab's page but Home (they reload in the background). */
internal object CinemaCaches {
    fun trim() {
        TabCache.data.keys.filter { it != CinemaTab.HOME }.forEach {
            TabCache.data.remove(it)
            TabCache.at.remove(it)
        }
        trimDetails()
    }

    /** The rows were rearranged: every tab loads afresh next time it shows. */
    fun homeChanged() {
        TabCache.data.clear()
        TabCache.at.clear()
    }
}

/** My List: every saved title in a calm grid, the focused one's art behind it. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MyListScreen(
    items: List<CinemaItem>,
    onOpen: (UUID, BaseItemKind) -> Unit,
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
            Text("My List", color = Ink, fontSize = 30.sp, fontWeight = FontWeight.ExtraBold, modifier = Modifier.padding(start = 48.dp, top = 22.dp, bottom = 6.dp))
            if (items.isEmpty()) {
                Text(
                    "Titles you add with My List on a title's page show up here.",
                    color = InkDim,
                    fontSize = 15.sp,
                    modifier = Modifier.padding(start = 48.dp, top = 8.dp),
                )
            } else {
                CompositionLocalProvider(LocalBringIntoViewSpec provides spec) {
                    LazyVerticalGrid(
                        columns = GridCells.Fixed(4),
                        contentPadding = PaddingValues(start = 48.dp, end = 48.dp, top = 14.dp, bottom = 120.dp),
                        horizontalArrangement = Arrangement.spacedBy(16.dp),
                        verticalArrangement = Arrangement.spacedBy(22.dp),
                        modifier = Modifier.fillMaxSize(),
                    ) {
                        gridItems(items, key = { it.key }, contentType = { "card" }) { item ->
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

/** Settings' left side in Cinema mode: a faded billboard image, the wordmark and the heading. */
@Composable
fun CinemaSettingsSide(modifier: Modifier = Modifier) {
    val backdrop = remember { TabCache.data[CinemaTab.HOME]?.featured?.randomOrNull()?.backdropUrl }
    Box(modifier) {
        StableBackdrop(backdrop, drift = true, widthFraction = 1f, heightFraction = 1f)
        Box(Modifier.fillMaxSize().background(Brush.horizontalGradient(0.55f to Color.Transparent, 1f to Stage)))
        Box(Modifier.fillMaxSize().background(Stage.copy(alpha = 0.35f)))
        Column(Modifier.align(Alignment.BottomStart).padding(start = 56.dp, bottom = 64.dp)) {
            Wordmark(size = 26)
            Spacer(Modifier.height(14.dp))
            Text("Settings", color = Ink, fontSize = 46.sp, fontWeight = FontWeight.ExtraBold)
            Spacer(Modifier.height(6.dp))
            Text("Your home, playback, subtitles and servers", color = InkDim, fontSize = 15.sp)
        }
    }
}
